package dev.opendeck.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {
    private val mac = byteArrayOf(0x00, 0xD8.toByte(), 0x61, 0x19, 0x4E, 0xA6.toByte())

    @Test
    fun parsesCommonNotations() {
        for (text in listOf(
            "00-D8-61-19-4E-A6",
            "00:d8:61:19:4e:a6",
            "00d861194ea6",
            "00D8.6119.4EA6",
            "  00-d8-61-19-4e-a6 \n",
        )) {
            assertArrayEquals(text, mac, WakeOnLan.parseMac(text))
        }
    }

    @Test
    fun rejectsMalformed() {
        for (text in listOf(
            "",
            "00-D8-61-19-4E",
            "00-D8-61-19-4E-A6-01",
            "00:D8-61:19-4E:A6", // mixed separators
            "0-D8-61-19-4E-A6-",
            "00d861194ea",
            "00d861194eag",
            "00 d8 61 19 4e a6",
            "000-D8-61-19-4E-A6",
            "00:00:00:00:00:00",
            "ff:ff:ff:ff:ff:ff",
            "192.168.1.100",
        )) {
            assertNull(text, WakeOnLan.parseMac(text))
        }
    }

    @Test
    fun normalizesToColonUppercase() {
        assertEquals("00:D8:61:19:4E:A6", WakeOnLan.normalizeMac("00d861194ea6"))
        assertEquals("00:D8:61:19:4E:A6", WakeOnLan.formatMac(mac))
        assertNull(WakeOnLan.normalizeMac("nope"))
    }

    @Test
    fun magicPacketIsSyncStreamThenMacSixteenTimes() {
        val p = WakeOnLan.magicPacket(mac)
        assertEquals(102, p.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, p.copyOfRange(0, 6))
        for (r in 0 until 16) assertArrayEquals("repeat $r", mac, p.copyOfRange(6 + 6 * r, 12 + 6 * r))
    }

    @Test(expected = IllegalArgumentException::class)
    fun magicPacketNeedsSixBytes() {
        WakeOnLan.magicPacket(ByteArray(5))
    }

    @Test
    fun broadcastAddressOfSubnet() {
        fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        assertArrayEquals(ip(192, 168, 1, 255), WakeOnLan.broadcastAddress(ip(192, 168, 1, 23), 24))
        assertArrayEquals(ip(10, 0, 3, 255), WakeOnLan.broadcastAddress(ip(10, 0, 1, 5), 22))
        assertArrayEquals(ip(172, 16, 255, 255), WakeOnLan.broadcastAddress(ip(172, 16, 4, 1), 16))
        assertArrayEquals(ip(192, 168, 1, 23), WakeOnLan.broadcastAddress(ip(192, 168, 1, 23), 32))
        assertArrayEquals(ip(255, 255, 255, 255), WakeOnLan.broadcastAddress(ip(192, 168, 1, 23), 0))
    }
}
