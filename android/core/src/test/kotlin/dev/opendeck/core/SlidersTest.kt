package dev.opendeck.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlidersTest {
    private val grid = Layout(5, 3)

    @Test
    fun frames() {
        val p = Sliders.pageFrame(7, 0x0102, "ab")
        assertArrayEquals(byteArrayOf('P'.code.toByte(), 7, 2, 1, 2, 'a'.code.toByte(), 'b'.code.toByte()), p)
        assertArrayEquals(byteArrayOf('B'.code.toByte(), 3), Sliders.beginFrame(3))
        // 1000 = 0x03E8, little-endian
        assertArrayEquals(byteArrayOf('A'.code.toByte(), 3, 0xE8.toByte(), 0x03), Sliders.positionFrame(3, 1000))
        assertArrayEquals(byteArrayOf('A'.code.toByte(), 200.toByte(), 0, 0), Sliders.positionFrame(200, 0))
    }

    @Test
    fun macReply() {
        assertArrayEquals(byteArrayOf('W'.code.toByte()), Sliders.macFrame())
        val mac = byteArrayOf(0x00, 0xD8.toByte(), 0x61, 0x19, 0x4E, 0xA6.toByte())
        assertArrayEquals(mac, Sliders.mac(byteArrayOf('W'.code.toByte()) + mac))
        assertEquals(null, Sliders.mac(byteArrayOf('W'.code.toByte()) + ByteArray(6))) // the helper found no adapter
        assertEquals(null, Sliders.mac(byteArrayOf('W'.code.toByte(), 1, 2, 3, 4, 5, 6))) // multicast bit set
        assertEquals(null, Sliders.mac(byteArrayOf('S'.code.toByte()) + mac))
        assertEquals(null, Sliders.mac(byteArrayOf('W'.code.toByte()) + mac.copyOf(5)))
    }

    @Test
    fun keysOfThePhoneDeck() {
        // Page 1 of the phone deck: + on row 0 over - on row 1; and a sideways pair with + on the right.
        val entries = byteArrayOf(0, 1, 0, 0, 0, 3, 1, 3, 0, 9, 1, 2, 2, 2, 4)
        val keys = Sliders.keys(entries, grid)
        assertEquals(6, keys.size)
        // Moving up (negative y) goes toward 100 %.
        assertEquals(Sliders.Key(0, vertical = true, sign = -1, hi = true), keys[0])
        assertEquals(Sliders.Key(0, vertical = true, sign = -1, hi = false), keys[5])
        assertEquals(Sliders.Key(9, vertical = true, sign = -1, hi = true), keys[3])
        assertEquals(Sliders.Key(4, vertical = false, sign = 1, hi = false), keys[11])
        assertEquals(Sliders.Key(4, vertical = false, sign = 1, hi = true), keys[12])
    }

    @Test
    fun keysDropWhatTheGridCannotShow() {
        val entries = byteArrayOf(
            5, 0, 5, 1, 0, // column 5 doesn't exist on a 5x3 grid
            0, 0, 1, 1, 1, // diagonal
            0, 0, 0, 2, 2, // not adjacent
            1, 0, 1, 1, 3,
            1, 1, 2, 1, 4, // reuses key 6
            7, // a partial entry
        )
        assertEquals(setOf(1, 6), Sliders.keys(entries, grid).keys)
        assertTrue(Sliders.keys(ByteArray(0), grid).isEmpty())
    }

    @Test
    fun positionAlongAVerticalPairWithTheTopKeyAt100() {
        val top = Sliders.Key(0, vertical = true, sign = -1, hi = true)
        val bottom = top.copy(hi = false)
        assertEquals(1000, Sliders.position(0f, 230f, 0f, top))
        assertEquals(500, Sliders.position(230f, 230f, 0f, top)) // the line between the keys
        assertEquals(750, Sliders.position(115f, 230f, 0f, top))
        assertEquals(250, Sliders.position(115f, 230f, 0f, bottom))
        assertEquals(0, Sliders.position(230f, 230f, 0f, bottom))
        // The finger leaves the touched key for its partner, or the pair altogether.
        assertEquals(750, Sliders.position(-115f, 230f, 0f, bottom))
        assertEquals(1000, Sliders.position(-900f, 230f, 0f, bottom))
        assertEquals(0, Sliders.position(900f, 230f, 0f, top))
    }

    @Test
    fun positionSkipsTheGapAroundTheImages() {
        val left = Sliders.Key(4, vertical = false, sign = 1, hi = false)
        // 10 % gap: each image starts 11.5 px into its 230 px cell and is 207 px wide.
        assertEquals(0, Sliders.position(5f, 230f, 23f, left))
        assertEquals(0, Sliders.position(11.5f, 230f, 23f, left))
        assertEquals(500, Sliders.position(225f, 230f, 23f, left)) // in the gap: end of the first image
        assertEquals(500, Sliders.position(241.5f, 230f, 23f, left)) // start of the second image
        assertEquals(1000, Sliders.position(455f, 230f, 23f, left))
        assertEquals(1000, Sliders.position(218.5f, 230f, 23f, left.copy(hi = true)))
    }
}
