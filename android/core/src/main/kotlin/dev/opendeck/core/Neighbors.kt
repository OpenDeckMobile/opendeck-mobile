package dev.opendeck.core

/** Reads MAC addresses from the kernel's neighbour (ARP) table, as `ip neigh` or /proc/net/arp print it. */
object Neighbors {
    /** Entry states whose MAC is a confirmed answer from the host (not FAILED, INCOMPLETE or NOARP). */
    private val USABLE_STATES = setOf("REACHABLE", "STALE", "DELAY")

    /**
     * The MAC of [ip] in `ip neigh show` output, e.g.
     * `192.168.1.100 dev wlan0 lladdr 00:d8:61:19:4e:a6 REACHABLE`. Null if absent, unusable or not unicast.
     */
    fun fromIpNeigh(output: String, ip: String): ByteArray? {
        for (line in output.lineSequence()) {
            val f = line.trim().split(Regex("\\s+"))
            if (f.firstOrNull() != ip) continue
            val i = f.indexOf("lladdr")
            if (i < 0 || i + 1 >= f.size) continue
            if (f.drop(i + 2).none { it in USABLE_STATES }) continue
            return unicast(f[i + 1])
        }
        return null
    }

    /**
     * The MAC of [ip] in /proc/net/arp, e.g.
     * `192.168.1.100    0x1         0x2         00:d8:61:19:4e:a6     *        wlan0`. Only complete entries (flag 0x2).
     */
    fun fromProcArp(text: String, ip: String): ByteArray? {
        for (line in text.lineSequence().drop(1)) {
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 4 || f[0] != ip) continue
            val flags = f[2].removePrefix("0x").toIntOrNull(16) ?: continue
            if (flags and 0x2 == 0) continue
            return unicast(f[3])
        }
        return null
    }

    private fun unicast(text: String): ByteArray? {
        val mac = WakeOnLan.parseMac(text) ?: return null
        return mac.takeIf { it[0].toInt() and 1 == 0 } // multicast bit clear
    }

    /** True if IPv4 [ip] lies in [network]/[prefixLength]. */
    fun inSubnet(ip: ByteArray, network: ByteArray, prefixLength: Int): Boolean {
        if (ip.size != 4 || network.size != 4 || prefixLength !in 0..32) return false
        fun v(b: ByteArray) = b.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xFF) }
        val mask = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        return v(ip) and mask == v(network) and mask
    }
}
