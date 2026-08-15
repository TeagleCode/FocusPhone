package com.teaglecode.focusphone.policy

/**
 * Just enough IPv4 + UDP + DNS to read a question name off the tunnel and put
 * an answer back. Nothing here tries to be a general packet library: it only
 * handles the shape of traffic our own tunnel route can produce, which is
 * IPv4/UDP to a single resolver address.
 */
class DnsQuery private constructor(
    /** The DNS message, without IP or UDP headers. */
    val payload: ByteArray,
    val name: String,
    private val srcIp: ByteArray,
    private val dstIp: ByteArray,
    private val srcPort: Int,
    private val dstPort: Int
) {

    /**
     * Wraps a DNS message in UDP and IPv4 headers addressed back to whoever
     * asked, which means swapping source and destination.
     */
    fun responseWith(dnsMessage: ByteArray): ByteArray {
        val udpLength = UDP_HEADER + dnsMessage.size
        val total = IP_HEADER + udpLength
        val out = ByteArray(total)

        out[0] = 0x45                      // IPv4, 5 words of header
        out[1] = 0
        writeShort(out, 2, total)
        writeShort(out, 4, 0)              // identification
        writeShort(out, 6, 0x4000)         // don't fragment
        out[8] = 64                        // TTL
        out[9] = 17                        // UDP
        writeShort(out, 10, 0)             // checksum, filled in below
        dstIp.copyInto(out, 12)            // reply comes *from* the resolver
        srcIp.copyInto(out, 16)
        writeShort(out, 10, checksum(out, 0, IP_HEADER))

        writeShort(out, IP_HEADER, dstPort)
        writeShort(out, IP_HEADER + 2, srcPort)
        writeShort(out, IP_HEADER + 4, udpLength)
        // A zero UDP checksum is explicitly permitted over IPv4, and the
        // packet never leaves the device.
        writeShort(out, IP_HEADER + 6, 0)

        dnsMessage.copyInto(out, IP_HEADER + UDP_HEADER)
        return out
    }

    /**
     * The blocked answer: the original question echoed back with the response
     * bit set and RCODE 3. Failing fast is kinder than a dropped packet, which
     * leaves the client retrying until it times out.
     */
    fun nxDomainResponse(): ByteArray {
        val message = payload.copyOf()
        if (message.size < DNS_HEADER) return responseWith(message)
        // QR=1, RA=1, RCODE=3 (name does not exist)
        message[2] = ((message[2].toInt() or 0x80) and 0xFF).toByte()
        message[3] = 0x83.toByte()
        // No answer, authority or additional records.
        writeShort(message, 6, 0)
        writeShort(message, 8, 0)
        writeShort(message, 10, 0)
        return responseWith(message)
    }

    companion object {
        private const val IP_HEADER = 20
        private const val UDP_HEADER = 8
        private const val DNS_HEADER = 12

        fun parse(packet: ByteArray): DnsQuery? {
            if (packet.size < IP_HEADER + UDP_HEADER + DNS_HEADER) return null
            if ((packet[0].toInt() shr 4 and 0xF) != 4) return null

            val ihl = (packet[0].toInt() and 0xF) * 4
            if (ihl < IP_HEADER || packet.size < ihl + UDP_HEADER) return null
            if ((packet[9].toInt() and 0xFF) != 17) return null // not UDP

            val dstPort = readShort(packet, ihl + 2)
            if (dstPort != 53) return null

            val udpLength = readShort(packet, ihl + 4)
            val payloadSize = (udpLength - UDP_HEADER)
                .coerceAtMost(packet.size - ihl - UDP_HEADER)
            if (payloadSize < DNS_HEADER) return null

            val payload = packet.copyOfRange(
                ihl + UDP_HEADER,
                ihl + UDP_HEADER + payloadSize
            )
            val name = readQName(payload) ?: return null

            return DnsQuery(
                payload = payload,
                name = name,
                srcIp = packet.copyOfRange(12, 16),
                dstIp = packet.copyOfRange(16, 20),
                srcPort = readShort(packet, ihl),
                dstPort = dstPort
            )
        }

        /** Reads the QNAME of the first question, which starts after the header. */
        private fun readQName(dns: ByteArray): String? {
            val parts = mutableListOf<String>()
            var i = DNS_HEADER
            while (i < dns.size) {
                val len = dns[i].toInt() and 0xFF
                if (len == 0) break
                // Compression pointers do not appear in questions.
                if (len > 63 || i + len + 1 > dns.size) return null
                parts += String(dns, i + 1, len, Charsets.US_ASCII)
                i += len + 1
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(".")
        }

        private fun readShort(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

        private fun writeShort(b: ByteArray, at: Int, value: Int) {
            b[at] = ((value shr 8) and 0xFF).toByte()
            b[at + 1] = (value and 0xFF).toByte()
        }

        /** Standard one's-complement header checksum. */
        private fun checksum(b: ByteArray, from: Int, length: Int): Int {
            var sum = 0
            var i = from
            val end = from + length
            while (i + 1 < end) {
                sum += readShort(b, i)
                i += 2
            }
            if (i < end) sum += (b[i].toInt() and 0xFF) shl 8
            while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
            return sum.inv() and 0xFFFF
        }
    }
}
