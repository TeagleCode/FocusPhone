package com.teaglecode.focusphone.policy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.teaglecode.focusphone.data.PolicyStore
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * A DNS filter built on VpnService.
 *
 * Only DNS is routed into the tunnel. The tunnel advertises a private resolver
 * address and adds a route for that single address, so ordinary traffic keeps
 * using the normal network path and is never touched by this process. Routing
 * everything (0.0.0.0/0) would require forwarding every packet upstream by
 * hand, and any gap in that forwarding takes the phone offline entirely.
 *
 * Queries for blocked names are answered with NXDOMAIN rather than dropped, so
 * the browser fails immediately instead of hanging until it times out.
 *
 * Android permits only one active VPN, so this cannot coexist with a
 * commercial VPN app.
 */
class SiteBlockerVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val upstreamPool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    private lateinit var store: PolicyStore

    override fun onCreate() {
        super.onCreate()
        store = PolicyStore(this)
    }

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
            .addAddress(TUN_CLIENT, 32)
            // The system resolves through this address, which routes into the
            // tunnel below. Nothing else does.
            .addDnsServer(TUN_RESOLVER)
            .addRoute(TUN_RESOLVER, 32)
            // Let the launcher itself bypass the tunnel.
            .addDisallowedApplication(packageName)
            .establish() ?: return

        tunnel = fd
        running = true
        active = true
        worker = Thread { pump(fd) }.also { it.start() }
    }

    private fun pump(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(MAX_PACKET)

        while (running) {
            val length = try {
                input.read(buffer)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "tunnel read failed", e)
                break
            }
            if (length <= 0) continue

            val packet = buffer.copyOf(length)
            val query = DnsQuery.parse(packet) ?: continue

            // Read the list per query so edits in settings take effect without
            // restarting the tunnel.
            val blocked = store.blockedDomains()
            if (matches(query.name, blocked)) {
                writeSafely(output, query.nxDomainResponse())
            } else {
                forward(query, output)
            }
        }
    }

    /** A blocked entry covers the domain itself and every subdomain of it. */
    private fun matches(name: String, blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return false
        val lower = name.lowercase().trimEnd('.')
        return blocked.any { raw ->
            val domain = raw.lowercase().trimEnd('.')
            domain.isNotEmpty() && (lower == domain || lower.endsWith(".$domain"))
        }
    }

    /**
     * Sends the query on to a real resolver over a protected socket, then
     * writes the reply back into the tunnel. Protecting the socket is what
     * stops our own lookup being routed back through the VPN.
     */
    private fun forward(query: DnsQuery, output: FileOutputStream) {
        upstreamPool.execute {
            runCatching {
                DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS

                    val upstream = InetAddress.getByName(UPSTREAM_DNS)
                    socket.send(
                        DatagramPacket(query.payload, query.payload.size, upstream, 53)
                    )

                    val reply = ByteArray(MAX_PACKET)
                    val packet = DatagramPacket(reply, reply.size)
                    socket.receive(packet)

                    writeSafely(output, query.responseWith(reply.copyOf(packet.length)))
                }
            }.onFailure {
                // A resolver that cannot be reached must not look like a block:
                // let the client retry rather than forging a negative answer.
                Log.w(TAG, "upstream lookup failed for ${query.name}", it)
            }
        }
    }

    @Synchronized
    private fun writeSafely(output: FileOutputStream, packet: ByteArray) {
        runCatching { output.write(packet) }
            .onFailure { Log.w(TAG, "tunnel write failed", it) }
    }

    private fun stop() {
        running = false
        active = false
        worker?.interrupt()
        worker = null
        runCatching { tunnel?.close() }
        tunnel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stop()
        upstreamPool.shutdownNow()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Site filter", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Site filter active")
            .setContentText("Blocked domains fail to resolve.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.teaglecode.focusphone.STOP_VPN"
        private const val CHANNEL = "focus_vpn"
        private const val NOTIF_ID = 42
        private const val TAG = "FocusVpn"

        private const val TUN_CLIENT = "10.111.222.1"
        private const val TUN_RESOLVER = "10.111.222.2"
        private const val UPSTREAM_DNS = "1.1.1.1"
        private const val UPSTREAM_TIMEOUT_MS = 5_000
        private const val MAX_PACKET = 32_767

        /** Lets settings show whether the filter is actually up. */
        @Volatile private var active = false

        fun isRunning(): Boolean = active
    }
}
