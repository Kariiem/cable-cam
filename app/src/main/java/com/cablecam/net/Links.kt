package com.cablecam.net

import com.cablecam.stream.StreamServer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** How a PC can reach the phone. Declared in display order. */
enum class LinkKind(val label: String) {
    USB("USB tethering"),
    WIFI("Wi-Fi"),
    HOTSPOT("Phone hotspot"),
    BLUETOOTH("Bluetooth tethering"),
    ETHERNET("Ethernet adapter"),
}

data class Link(val kind: LinkKind, val iface: String, val ip: String) {
    val url: String get() = Links.url(ip)
}

/** Finds the phone's addresses a PC could connect to. The classification is pure and unit tested. */
object Links {
    const val ADB_URL = "http://127.0.0.1:${StreamServer.DEFAULT_PORT}${StreamServer.STREAM_PATH}"
    const val ADB_COMMAND = "adb forward tcp:${StreamServer.DEFAULT_PORT} tcp:${StreamServer.DEFAULT_PORT}"

    fun url(ip: String) = "http://$ip:${StreamServer.DEFAULT_PORT}${StreamServer.STREAM_PATH}"

    /** The link kind for a network interface name, or null for interfaces a PC cannot use (mobile data, VPN, ...). */
    fun classify(iface: String): LinkKind? {
        val n = iface.lowercase()
        return when {
            n.startsWith("rndis") || n.startsWith("ncm") || n.startsWith("usb") -> LinkKind.USB
            n.startsWith("bt-pan") || n.startsWith("bnep") || n.startsWith("bt_pan") -> LinkKind.BLUETOOTH
            n == "wlan0" -> LinkKind.WIFI
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") ||
                n.startsWith("wlan") || n.startsWith("wifi") -> LinkKind.HOTSPOT
            n.startsWith("eth") -> LinkKind.ETHERNET
            else -> null
        }
    }

    /** [ifaces]: interface name to its IPv4 addresses. Only private addresses count. */
    fun build(ifaces: List<Pair<String, List<String>>>): List<Link> =
        ifaces.flatMap { (name, ips) ->
            val kind = classify(name) ?: return@flatMap emptyList()
            ips.filter(::isPrivate).map { Link(kind, name, it) }
        }.sortedWith(compareBy({ it.kind }, { it.iface }, { it.ip }))

    fun isPrivate(ip: String): Boolean {
        val p = ip.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 169 && p[1] == 254)
    }

    /** The link a client came in on, from the phone-side address of its connection. Loopback is adb forward. */
    fun kindOf(local: InetAddress, links: List<Link>): LinkKind? =
        if (local.isLoopbackAddress) LinkKind.USB else links.firstOrNull { it.ip == local.hostAddress }?.kind

    /** The links that exist right now. */
    fun current(): List<Link> = runCatching {
        build(NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.map { nif ->
            nif.name to nif.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        })
    }.getOrDefault(emptyList())
}
