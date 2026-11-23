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
        // -1000 = 0xFC18, little-endian
        assertArrayEquals(byteArrayOf('M'.code.toByte(), 3, 0x18, 0xFC.toByte()), Sliders.moveFrame(3, -1000))
        assertArrayEquals(byteArrayOf('M'.code.toByte(), 200.toByte(), 0xE8.toByte(), 0x03), Sliders.moveFrame(200, 1000))
    }

    @Test
    fun keysOfThePhoneDeck() {
        // Page 1 of the phone deck: + on row 0 over - on row 1; and a sideways pair with + on the right.
        val entries = byteArrayOf(0, 1, 0, 0, 0, 3, 1, 3, 0, 9, 1, 2, 2, 2, 4)
        val keys = Sliders.keys(entries, grid)
        assertEquals(6, keys.size)
        val up = Sliders.Key(0, vertical = true, sign = -1) // moving up (negative y) raises the value
        assertEquals(up, keys[0])
        assertEquals(up, keys[5])
        assertEquals(Sliders.Key(9, vertical = true, sign = -1), keys[3])
        assertEquals(Sliders.Key(4, vertical = false, sign = 1), keys[11])
        assertEquals(Sliders.Key(4, vertical = false, sign = 1), keys[12])
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
    fun offsetIsAFractionOfTheTwoKeys() {
        val up = Sliders.Key(0, vertical = true, sign = -1)
        assertEquals(500, Sliders.offset(-230f, 230f, up)) // one key up = half the pair
        assertEquals(-250, Sliders.offset(115f, 230f, up))
        assertEquals(1000, Sliders.offset(-5000f, 230f, up)) // clamped
        assertEquals(-1000, Sliders.offset(5000f, 230f, up))
    }
}
