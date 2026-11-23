package dev.opendeck.mobile

import android.util.Log
import dev.opendeck.core.Sliders
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * The connection to the computer's fader helper (see [Sliders]). Frames go out in order on one writer thread;
 * replies arrive on a thread that blocks on the socket. The helper is optional: when it doesn't answer, frames
 * are dropped and the next page frame tries again.
 */
class SliderLink(private val host: String, private val onReply: (seq: Int, entries: ByteArray) -> Unit) {
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "sliders-write") }
    private var socket: Socket? = null // writer thread only

    /** [connect]: open the connection first if it isn't (page frames); drags only use an open one. */
    fun send(frame: ByteArray, connect: Boolean = false) {
        if (writer.isShutdown) return
        writer.execute {
            val s = socket?.takeUnless { it.isClosed } ?: (if (connect) open() else null) ?: return@execute
            try {
                s.getOutputStream().write(frame)
            } catch (e: IOException) {
                s.close()
            }
        }
    }

    fun close() {
        if (writer.isShutdown) return
        writer.execute { socket?.close() }
        writer.shutdown()
    }

    private fun open(): Socket? = try {
        Socket().apply {
            tcpNoDelay = true // drag frames are 4 bytes: Nagle would hold each one for the previous one's ACK
            connect(InetSocketAddress(host, Sliders.PORT), CONNECT_TIMEOUT_MS)
        }.also { s ->
            socket = s
            Thread({ read(s) }, "sliders-read").start()
        }
    } catch (e: IOException) {
        Log.i(TAG, "no fader helper at $host: ${e.message}")
        null
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
            // closed or dropped: the next page frame reconnects
        }
        s.close()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 1500
        const val TAG = "OpenDeck"
    }
}
