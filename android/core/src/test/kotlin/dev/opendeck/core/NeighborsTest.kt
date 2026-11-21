package dev.opendeck.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeighborsTest {
    private val mac = WakeOnLan.parseMac("00:d8:61:19:4e:a6")!!

    @Test
    fun ipNeighOnTheP20() {
        // As `ip neigh show 192.168.1.100` printed it on the Huawei P20 (Android 10).
        assertArrayEquals(mac, Neighbors.fromIpNeigh("192.168.1.100 dev wlan0 lladdr 00:d8:61:19:4e:a6 REACHABLE\n", "192.168.1.100"))
    }

    @Test
    fun ipNeighPicksTheRightLineAndState() {
        val out = """
            192.168.1.1 dev wlan0 lladdr 11:22:33:44:55:66 REACHABLE
            192.168.1.10 dev wlan0 lladdr 00:d8:61:19:4e:a7 STALE
            192.168.1.100 dev wlan0 lladdr 00:d8:61:19:4e:a6 DELAY
            fe80::1 dev wlan0 lladdr 11:22:33:44:55:66 router STALE
        """.trimIndent()
        assertArrayEquals(mac, Neighbors.fromIpNeigh(out, "192.168.1.100"))
        assertNull(Neighbors.fromIpNeigh(out, "192.168.1.2"))
    }

    @Test
    fun ipNeighRejectsUnusableEntries() {
        assertNull(Neighbors.fromIpNeigh("192.168.1.100 dev wlan0  FAILED", "192.168.1.100"))
        assertNull(Neighbors.fromIpNeigh("192.168.1.100 dev wlan0 lladdr 00:d8:61:19:4e:a6 INCOMPLETE", "192.168.1.100"))
        assertNull(Neighbors.fromIpNeigh("192.168.1.100 dev wlan0 lladdr 00:00:00:00:00:00 REACHABLE", "192.168.1.100"))
        assertNull(Neighbors.fromIpNeigh("192.168.1.100 dev wlan0 lladdr 01:00:5e:00:00:fb REACHABLE", "192.168.1.100")) // multicast
        assertNull(Neighbors.fromIpNeigh("", "192.168.1.100"))
    }

    @Test
    fun procNetArp() {
        val arp = """
            IP address       HW type     Flags       HW address            Mask     Device
            192.168.1.1      0x1         0x2         11:22:33:44:55:66     *        wlan0
            192.168.1.100    0x1         0x2         00:d8:61:19:4e:a6     *        wlan0
            192.168.1.50     0x1         0x0         00:00:00:00:00:00     *        wlan0
        """.trimIndent()
        assertArrayEquals(mac, Neighbors.fromProcArp(arp, "192.168.1.100"))
        assertNull(Neighbors.fromProcArp(arp, "192.168.1.50"))
        assertNull(Neighbors.fromProcArp(arp, "192.168.1.7"))
    }

    @Test
    fun subnetMembership() {
        fun ip(s: String) = s.split('.').map { it.toInt().toByte() }.toByteArray()
        assertTrue(Neighbors.inSubnet(ip("192.168.1.100"), ip("192.168.1.102"), 24))
        assertFalse(Neighbors.inSubnet(ip("192.168.2.100"), ip("192.168.1.102"), 24))
        assertTrue(Neighbors.inSubnet(ip("10.0.3.9"), ip("10.0.1.1"), 22))
        assertFalse(Neighbors.inSubnet(ip("10.0.4.9"), ip("10.0.1.1"), 22))
        assertFalse(Neighbors.inSubnet(ip("10.0.4.9"), ByteArray(16), 64))
    }
}
