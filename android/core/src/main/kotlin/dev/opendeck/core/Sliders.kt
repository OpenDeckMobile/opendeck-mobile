package dev.opendeck.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Faders drawn across two adjacent keys, dragged with a finger.
 *
 * The virtual-device protocol carries only key presses, so a drag goes to an optional helper on the computer, on
 * [PORT] next to Stream Deck's own. The client names the page it shows; the helper answers which key pairs are
 * faders; while a finger drags along a pair the client sends the offset from where the drag started. The helper
 * changes the value and the computer redraws the keys as usual, so nothing comes back during a drag. Without the
 * helper the keys stay plain keys.
 *
 * Wire, little-endian, client to helper:
 * - `'P' seq:u8 page:u16 len:u8 profileID[len]` the page shown, answered with `'S'`
 * - `'B' id:u8` a drag starts
 * - `'M' id:u8 offset:i16` offset from the drag start: [FULL] is the length of the pair's two keys, + toward hi
 *
 * Helper to client: `'S' seq:u8 n:u8` then n entries `loCol loRow hiCol hiRow id`; lo is the 0 % end, hi the 100 % end.
 */
object Sliders {
    const val PORT = 28199
    const val FULL = 1000
    const val REPLY = 'S'.code
    const val ENTRY_SIZE = 5
    private const val MAX_PROFILE_ID = 64

    /** One key of a fader. [sign] is +1 when moving right or down goes toward the 100 % end, -1 otherwise. */
    data class Key(val id: Int, val vertical: Boolean, val sign: Int)

    fun pageFrame(seq: Int, page: Int, profileID: String): ByteArray {
        val id = profileID.toByteArray(Charsets.US_ASCII).take(MAX_PROFILE_ID).toByteArray()
        return byteArrayOf('P'.code.toByte(), seq.toByte(), page.toByte(), (page shr 8).toByte(), id.size.toByte()) + id
    }

    fun beginFrame(id: Int) = byteArrayOf('B'.code.toByte(), id.toByte())

    fun moveFrame(id: Int, offset: Int) = byteArrayOf('M'.code.toByte(), id.toByte(), offset.toByte(), (offset shr 8).toByte())

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
            val key = Key(id, vertical = loCol == hiCol, sign = if (hi > lo) 1 else -1)
            out[lo] = key
            out[hi] = key
        }
        return out
    }

    /** The offset for a finger moved [along] px (right or down positive) on keys [pitch] px apart. */
    fun offset(along: Float, pitch: Float, key: Key): Int =
        (along * key.sign / (2 * pitch) * FULL).roundToInt().coerceIn(-FULL, FULL)
}
