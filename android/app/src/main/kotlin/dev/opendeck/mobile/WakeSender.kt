package dev.opendeck.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dev.opendeck.core.Neighbors
import dev.opendeck.core.WakeOnLan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Sends Wake-on-LAN magic packets on the Wi-Fi network, and learns the MAC addresses they need. */
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

    /**
     * The MAC address of the computer at IPv4 [host], read from the phone's neighbour table right after a connect,
     * as `00:D8:61:19:4E:A6`. Null unless [host] is on the Wi-Fi subnet itself: for a routed host the table holds
     * the router's MAC, which must never be stored. Reading the table works up to Android 10 (blocked for apps
     * from Android 11), so null is normal on newer phones.
     */
    suspend fun learnMac(host: String): String? = withContext(Dispatchers.IO) {
        if (!IPV4.matches(host)) return@withContext null
        val ip = InetAddress.getByName(host).address // a literal: no lookup
        val props = wifiNetwork()?.let { connectivity.getLinkProperties(it) } ?: return@withContext null
        val onLink = props.linkAddresses.any { it.address is Inet4Address && Neighbors.inSubnet(ip, it.address.address, it.prefixLength) }
        if (!onLink) return@withContext null
        val gateways = props.routes.mapNotNull { r -> r.gateway?.takeIf { it is Inet4Address && !it.isAnyLocalAddress }?.hostAddress }.distinct()
        if (host in gateways) return@withContext null
        val mac = neighbour(host) ?: return@withContext null
        if (gateways.any { g -> neighbour(g)?.contentEquals(mac) == true }) {
            Log.i(TAG, "$host has the gateway's MAC, not learning it")
            return@withContext null
        }
        WakeOnLan.formatMac(mac)
    }

    /** [ip]'s MAC from `ip neigh`, else /proc/net/arp; null if neither has a usable entry or both are blocked. */
    private fun neighbour(ip: String): ByteArray? {
        val fromIp = try {
            val p = ProcessBuilder("ip", "neigh", "show", ip).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy()
            Neighbors.fromIpNeigh(out, ip).also { if (it == null) Log.i(TAG, "ip neigh: no usable entry for $ip: ${out.trim().take(200)}") }
        } catch (e: Exception) {
            Log.i(TAG, "ip neigh failed: $e")
            null
        }
        if (fromIp != null) return fromIp
        return try {
            Neighbors.fromProcArp(File("/proc/net/arp").readText(), ip)
        } catch (e: Exception) {
            Log.i(TAG, "/proc/net/arp failed: $e")
            null
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
        val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    }
}
