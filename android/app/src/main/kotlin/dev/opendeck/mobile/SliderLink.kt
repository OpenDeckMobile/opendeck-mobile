package dev.opendeck.mobile

import android.os.SystemClock
import android.util.Log
import dev.opendeck.core.Sliders
import dev.opendeck.core.WakeOnLan
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * The connection to the computer's fader helper (see [Sliders]). Frames go out in order on one writer thread;
 * replies arrive on a thread that blocks on the socket. The helper is optional and may start after Stream Deck
 * or restart: a page or drag start (re)connects, a new connection first names the last page again (fader ids
 * depend only on the page, so they stay valid), and [retry] lets a touch reconnect without waiting for a page change.
 * The first connection also asks the helper for the computer's MAC address ([onMac], on the writer thread).
 */
class SliderLink(
    private val host: String,
    private val onReply: (seq: Int, entries: ByteArray) -> Unit,
    private val onClosed: () -> Unit,
    private val onMac: (ByteArray) -> Unit,
) {
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "sliders-write") }
    // Written on the writer thread only; the reader thread checks whether its socket is still this one.
    @Volatile private var socket: Socket? = null
    // Writer thread only.
    private var lastPage: ByteArray? = null
    private var lastAttempt = Long.MIN_VALUE / 2
    private var macAsked = false

    fun page(frame: ByteArray) = submit {
        lastPage = frame
        write(frame, connect = true)
    }

    fun begin(frame: ByteArray) = submit { write(frame, connect = true) }

    /** Moves only use an open connection: reconnecting on every move would queue a connect per finger event. */
    fun move(frame: ByteArray) = submit { write(frame, connect = false) }

    /** A touch landed: if the helper isn't connected, ask for the page again, at most every [RETRY_MS]. */
    fun retry() = submit {
        val page = lastPage
        if (open() == null && page != null && SystemClock.elapsedRealtime() - lastAttempt >= RETRY_MS) write(page, connect = true)
    }

    fun close() {
        if (writer.isShutdown) return
        writer.execute { socket?.close() }
        writer.shutdown()
    }

    private fun submit(task: () -> Unit) {
        if (!writer.isShutdown) writer.execute(task)
    }

    private fun open(): Socket? = socket?.takeUnless { it.isClosed }

    private fun write(frame: ByteArray, connect: Boolean) {
        var s = open()
        if (s == null) {
            if (!connect) return
            s = connect() ?: return
            // A new connection knows no page yet: name it before a drag start that comes with it.
            val page = lastPage
            if (page != null && page !== frame && !send(s, page)) return
        }
        send(s, frame)
    }

    private fun send(s: Socket, frame: ByteArray): Boolean = try {
        s.getOutputStream().write(frame)
        true
    } catch (e: IOException) {
        s.close()
        false
    }

    private fun connect(): Socket? {
        lastAttempt = SystemClock.elapsedRealtime()
        return try {
            if (!macAsked) askMac()
            val s = Socket()
            s.tcpNoDelay = true // drag frames are 4 bytes: Nagle would hold each one for the previous one's ACK
            s.connect(InetSocketAddress(host, Sliders.PORT), CONNECT_TIMEOUT_MS)
            socket = s
            Thread({ read(s) }, "sliders-read").start()
            s
        } catch (e: IOException) {
            Log.i(TAG, "no fader helper at $host: ${e.message}")
            null
        }
    }

    /**
     * Asks for the computer's MAC address on a connection of its own, closed before the fader one opens: a helper
     * too old to know the question closes the connection it arrives on. Throws if the helper isn't there at all.
     */
    private fun askMac() {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, Sliders.PORT), CONNECT_TIMEOUT_MS)
            macAsked = true // once per helper found, whether or not it knows the answer
            try {
                s.soTimeout = CONNECT_TIMEOUT_MS
                s.getOutputStream().write(Sliders.macFrame())
                val reply = ByteArray(Sliders.MAC_REPLY_SIZE)
                DataInputStream(s.getInputStream()).readFully(reply)
                val mac = Sliders.mac(reply)
                Log.i(TAG, "fader helper at $host: MAC ${mac?.let { WakeOnLan.formatMac(it) }}")
                mac?.let(onMac)
            } catch (e: IOException) {
                Log.i(TAG, "fader helper at $host gave no MAC address: ${e.message}")
            }
        }
    }

    private fun read(s: Socket) {
        try {
            val input = DataInputStream(s.getInputStream().buffered())
            while (input.readUnsignedByte() == Sliders.REPLY) {
                val seq = input.readUnsignedByte()
                val entries = ByteArray(input.readUnsignedByte() * Sliders.ENTRY_SIZE)
                input.readFully(entries)
                onReply(seq, entries)
            }
        } catch (_: IOException) {
            // closed or dropped
        }
        s.close()
        // A socket already replaced by a new connection must not clear the faders that connection delivered.
        if (socket === s) onClosed()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 1500
        const val RETRY_MS = 10_000L
        const val TAG = "OpenDeck"
    }
}
