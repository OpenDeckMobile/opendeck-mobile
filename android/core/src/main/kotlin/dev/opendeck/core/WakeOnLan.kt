package dev.opendeck.core

/** Wake-on-LAN magic packets and the MAC addresses they carry. */
object WakeOnLan {
    const val PORT = 9
    const val MAC_LENGTH = 6
    const val PACKET_LENGTH = 6 + 16 * MAC_LENGTH

    /**
     * Parses a MAC address as Windows (`00-D8-61-19-4E-A6`), Linux/macOS (`00:d8:61:19:4e:a6`), Cisco
     * (`00d8.6119.4ea6`) or bare (`00d861194ea6`) writes it. Null if it isn't one, or is all zeros or broadcast.
     */
    fun parseMac(text: String): ByteArray? {
        val s = text.trim()
        val hex = when {
            s.length == 12 -> s
            s.length == 17 -> {
                val sep = s[2]
                if (sep != ':' && sep != '-') return null
                val parts = s.split(sep)
                if (parts.size != 6 || parts.any { it.length != 2 }) return null
                parts.joinToString("")
            }
            s.length == 14 && s.split('.').let { p -> p.size == 3 && p.all { it.length == 4 } } -> s.replace(".", "")
            else -> return null
        }
        if (hex.any { Character.digit(it, 16) < 0 }) return null
        val mac = ByteArray(MAC_LENGTH) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        if (mac.all { it == 0.toByte() } || mac.all { it == 0xFF.toByte() }) return null
        return mac
    }

    /** `00:D8:61:19:4E:A6`. */
    fun formatMac(mac: ByteArray): String {
        require(mac.size == MAC_LENGTH) { "MAC must be $MAC_LENGTH bytes" }
        return mac.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    /** The normalized form of [text], or null if it isn't a MAC address. */
    fun normalizeMac(text: String): String? = parseMac(text)?.let { formatMac(it) }

    /** Six 0xFF bytes followed by the MAC repeated 16 times. */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == MAC_LENGTH) { "MAC must be $MAC_LENGTH bytes" }
        val out = ByteArray(PACKET_LENGTH)
        for (i in 0 until 6) out[i] = 0xFF.toByte()
        for (r in 0 until 16) mac.copyInto(out, 6 + r * MAC_LENGTH)
        return out
    }

    /** The directed broadcast address of the IPv4 subnet [address]/[prefixLength], e.g. 192.168.1.255 for a /24. */
    fun broadcastAddress(address: ByteArray, prefixLength: Int): ByteArray {
        require(address.size == 4) { "IPv4 only" }
        require(prefixLength in 0..32) { "bad prefix length" }
        val ip = address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val hostMask = if (prefixLength == 0) 0xFFFFFFFFL else (1L shl (32 - prefixLength)) - 1
        val bcast = ip or hostMask
        return ByteArray(4) { i -> (bcast shr (8 * (3 - i))).toByte() }
    }
}
