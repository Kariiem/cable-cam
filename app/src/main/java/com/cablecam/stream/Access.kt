package com.cablecam.stream

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Who may connect. The server listens on every interface, and that includes the mobile-data
 * interface, so only peers on a private network are served: USB/Bluetooth tethering, Wi-Fi LAN,
 * hotspot (all private IPv4 ranges) and adb forward (loopback).
 */
object Access {
    fun allowed(remote: InetAddress): Boolean =
        remote.isLoopbackAddress ||
            (remote is Inet4Address && (remote.isSiteLocalAddress || remote.isLinkLocalAddress))
}
