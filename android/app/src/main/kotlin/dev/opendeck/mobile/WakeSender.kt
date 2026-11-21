package dev.opendeck.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dev.opendeck.core.WakeOnLan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/** Sends Wake-on-LAN magic packets on the Wi-Fi network. */
class WakeSender(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    enum class Result { SENT, NO_WIFI, FAILED }

    /** Broadcasts the magic packet for [mac] a few times, to 255.255.255.255 and the Wi-Fi subnet's broadcast. */
    suspend fun send(mac: ByteArray): Result = withContext(Dispatchers.IO) {
        val network = wifiNetwork() ?: return@withContext Result.NO_WIFI
        val targets = buildList {
            add(InetAddress.getByName("255.255.255.255"))
            connectivity.getLinkProperties(network)?.linkAddresses
                ?.filter { it.address is Inet4Address && it.prefixLength in 1..30 }
                ?.forEach { add(InetAddress.getByAddress(WakeOnLan.broadcastAddress(it.address.address, it.prefixLength))) }
        }.distinct()
        val payload = WakeOnLan.magicPacket(mac)
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                network.bindSocket(socket) // out of the Wi-Fi interface even if mobile data is the default route
                repeat(REPEATS) { i ->
                    for (t in targets) socket.send(DatagramPacket(payload, payload.size, t, WakeOnLan.PORT))
                    if (i < REPEATS - 1) delay(REPEAT_GAP_MS)
                }
            }
            Log.i(TAG, "magic packet sent to ${targets.joinToString { it.hostAddress ?: "?" }}")
            Result.SENT
        } catch (e: Exception) {
            Log.w(TAG, "magic packet failed", e)
            Result.FAILED
        }
    }

    /** The Wi-Fi (or Ethernet) network, or null if the phone isn't on one. */
    private fun wifiNetwork(): Network? = connectivity.allNetworks.firstOrNull { n ->
        connectivity.getNetworkCapabilities(n)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
    }

    private companion object {
        const val REPEATS = 3
        const val REPEAT_GAP_MS = 100L
        const val TAG = "OpenDeckWake"
    }
}
