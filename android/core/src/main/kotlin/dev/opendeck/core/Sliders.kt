package dev.opendeck.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Faders drawn across two adjacent keys, dragged with a finger.
 *
 * The virtual-device protocol carries only key presses, so a drag goes to an optional helper on the computer, on
 * [PORT] next to Stream Deck's own. The client names the page it shows; the helper answers which key pairs are
 * faders; while a finger drags along a pair the client sends where on the pair the finger is. The helper changes
 * the value and the computer redraws the keys as usual, so nothing comes back during a drag. Without the helper
 * the keys stay plain keys.
 *
 * Wire, little-endian, client to helper:
 * - `'P' seq:u8 page:u16 len:u8 profileID[len]` the page shown, answered with `'S'`
 * - `'B' id:u8` a drag starts
 * - `'A' id:u8 position:u16` where the finger is along the pair's two key images, in 1/[FULL] from the outer edge
 *   of the 0 % end to the outer edge of the 100 % end (the gap between the keys is skipped); the helper knows
 *   where the plugin draws the track on them
 *
 * Helper to client: `'S' seq:u8 n:u8` then n entries `loCol loRow hiCol hiRow id`; lo is the 0 % end, hi the 100 % end.
 */
object Sliders {
    const val PORT = 28199
    const val FULL = 1000
    const val REPLY = 'S'.code
    const val ENTRY_SIZE = 5
    private const val MAX_PROFILE_ID = 64

    /**
     * One key of a fader. [sign] is +1 when moving right or down goes toward the 100 % end, -1 otherwise;
     * [hi] is true for the key at the 100 % end.
     */
    data class Key(val id: Int, val vertical: Boolean, val sign: Int, val hi: Boolean)

    fun pageFrame(seq: Int, page: Int, profileID: String): ByteArray {
        val id = profileID.toByteArray(Charsets.US_ASCII).take(MAX_PROFILE_ID).toByteArray()
        return byteArrayOf('P'.code.toByte(), seq.toByte(), page.toByte(), (page shr 8).toByte(), id.size.toByte()) + id
    }

    fun beginFrame(id: Int) = byteArrayOf('B'.code.toByte(), id.toByte())

    fun positionFrame(id: Int, position: Int) =
        byteArrayOf('A'.code.toByte(), id.toByte(), position.toByte(), (position shr 8).toByte())

    /** The keys of the faders in a reply's [entries], by key index; entries outside [layout] or not adjacent are dropped. */
    fun keys(entries: ByteArray, layout: Layout): Map<Int, Key> {
        val out = HashMap<Int, Key>()
        for (e in 0 until entries.size / ENTRY_SIZE) {
            val b = IntArray(ENTRY_SIZE) { entries[e * ENTRY_SIZE + it].toInt() and 0xFF }
            val (loCol, loRow, hiCol, hiRow, id) = b
            if (loCol >= layout.cols || hiCol >= layout.cols || loRow >= layout.rows || hiRow >= layout.rows) continue
            if (abs(loCol - hiCol) + abs(loRow - hiRow) != 1) continue
            val lo = Keypad.index(loRow, loCol, layout.cols)
            val hi = Keypad.index(hiRow, hiCol, layout.cols)
            if (lo in out || hi in out) continue
            val sign = if (hi > lo) 1 else -1
            out[lo] = Key(id, vertical = loCol == hiCol, sign = sign, hi = false)
            out[hi] = Key(id, vertical = loCol == hiCol, sign = sign, hi = true)
        }
        return out
    }

    /**
     * Where a finger [along] px from the top (or left) edge of [key]'s cell lies on its pair, for the wire. [pitch] is
     * one cell and [gap] the part of it around the key image; a finger past the pair counts as its end.
     */
    fun position(along: Float, pitch: Float, gap: Float, key: Key): Int {
        val first = (key.sign > 0) != key.hi // this key is the top (or left) one of the pair
        val px = (if (first) along else pitch + along).coerceIn(0f, 2 * pitch) // from the pair's top or left edge
        val k = if (px < pitch) 0 else 1
        val inImage = ((px - k * pitch - gap / 2) / (pitch - gap)).coerceIn(0f, 1f)
        val fromStart = k + inImage // 0..2 key images
        val fromLo = if (key.sign > 0) fromStart else 2 - fromStart
        return (fromLo / 2 * FULL).roundToInt()
    }
}
