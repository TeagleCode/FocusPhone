package com.focus.launcher.policy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.focus.launcher.data.PolicyStore
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * A loopback VPN used purely as a DNS filter. Packets are inspected; UDP/53
 * queries whose QNAME matches the blocklist are dropped, everything else is
 * written straight back out.
 *
 * Note: Android permits only one active VPN, so this cannot coexist with a
 * commercial VPN app.
 */
class SiteBlockerVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        start()
        return START_STICKY
    }

    private fun start() {
        if (running) return
        val fd = Builder()
            .setSession("Focus site filter")
            .addAddress("10.111.222.1", 32)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            // Let the launcher itself bypass the tunnel.
            .addDisallowedApplication(packageName)
            .establish() ?: return

        tunnel = fd
        running = true

        val blocked = PolicyStore(this).blockedDomains()
        worker = Thread { pump(fd, blocked) }.also { it.start() }
    }

    private fun pump(fd: ParcelFileDescriptor, blocked: Set<String>) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767)

        while (running) {
            buffer.clear()
            val length = try {
                input.read(buffer.array())
            } catch (e: Exception) {
                break
            }
            if (length <= 0) continue

            val packet = buffer.array().copyOf(length)
            if (isBlockedDnsQuery(packet, blocked)) {
                // Dropping the query causes the lookup to fail, so the page
                // will not resolve.
                continue
            }
            try {
                output.write(packet)
            } catch (e: Exception) {
                break
            }
        }
    }

    /** Minimal IPv4 + UDP + DNS parse, just enough to read the question name. */
    private fun isBlockedDnsQuery(packet: ByteArray, blocked: Set<String>): Boolean {
        if (blocked.isEmpty() || packet.size < 28) return false
        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4) return false

        val ihl = (packet[0].toInt() and 0xF) * 4
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return false // not UDP

        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or
            (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return false

        val dnsStart = ihl + 8 + 12 // UDP header + DNS header
        val name = readQName(packet, dnsStart) ?: return false
        val lower = name.lowercase()
        return blocked.any { lower == it || lower.endsWith(".$it") }
    }

    private fun readQName(packet: ByteArray, start: Int): String? {
        val parts = mutableListOf<String>()
        var i = start
        while (i < packet.size) {
            val len = packet[i].toInt() and 0xFF
            if (len == 0) break
            if (len > 63 || i + len + 1 > packet.size) return null
            parts += String(packet, i + 1, len)
            i += len + 1
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(".")
    }

    private fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        tunnel?.close()
        tunnel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Site filter", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Site filter active")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.focus.launcher.STOP_VPN"
        private const val CHANNEL = "focus_vpn"
        private const val NOTIF_ID = 42
    }
}
