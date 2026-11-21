package dev.opendeck.core

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Why a socket to the computer could not be opened. */
enum class Reachability {
    /** Nothing answered: the computer is off, asleep, or a firewall drops the connection. */
    UNREACHABLE,

    /** The computer answered with a reset: it is on, but nothing listens on the port (Stream Deck isn't running). */
    REFUSED,

    /** Something else, e.g. a server that answered but refused the WebSocket upgrade. */
    OTHER;

    companion object {
        /** Classifies a connect failure; null means it timed out before the socket reported anything. */
        fun of(error: Throwable?): Reachability {
            if (error == null) return UNREACHABLE
            val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(8).toList()
            val all = chain + chain.flatMap { it.suppressed.toList() }
            if (all.any { t -> t.message?.let { "ECONNREFUSED" in it || "Connection refused" in it } == true }) return REFUSED
            if (all.any { t ->
                    t is SocketTimeoutException || t is NoRouteToHostException || t is UnknownHostException ||
                        t is ConnectException ||
                        t.message?.let { "EHOSTUNREACH" in it || "ENETUNREACH" in it || "timed out" in it } == true
                }
            ) return UNREACHABLE
            return OTHER
        }
    }
}
