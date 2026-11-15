package com.cablecam.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @Test fun classifiesInterfaceNames() {
        val cases = mapOf(
            "rndis0" to LinkKind.USB, "ncm0" to LinkKind.USB, "usb0" to LinkKind.USB,
            "wlan0" to LinkKind.WIFI,
            "ap0" to LinkKind.HOTSPOT, "wlan1" to LinkKind.HOTSPOT, "swlan0" to LinkKind.HOTSPOT,
            "bt-pan" to LinkKind.BLUETOOTH, "bnep0" to LinkKind.BLUETOOTH,
            "eth0" to LinkKind.ETHERNET,
            "lo" to null, "dummy0" to null, "ccmni1" to null, "rmnet_data0" to null, "tun0" to null, "p2p0" to null,
        )
        for ((name, want) in cases) assertEquals(name, want, Links.classify(name))
    }

    @Test fun buildsSortedLinksFromPrivateAddressesOnly() {
        val links = Links.build(
            listOf(
                "tun0" to listOf("10.111.222.1"),            // VPN: ignored
                "ccmni1" to listOf("10.20.30.40"),           // mobile data: ignored
                "bt-pan" to listOf("192.168.44.1"),
                "wlan0" to listOf("192.168.1.2", "8.8.4.4"), // public address: ignored
                "rndis0" to listOf("192.168.42.129"),
                "ap0" to listOf("192.168.43.1"),
            )
        )
        assertEquals(
            listOf(
                Link(LinkKind.USB, "rndis0", "192.168.42.129"),
                Link(LinkKind.WIFI, "wlan0", "192.168.1.2"),
                Link(LinkKind.HOTSPOT, "ap0", "192.168.43.1"),
                Link(LinkKind.BLUETOOTH, "bt-pan", "192.168.44.1"),
            ),
            links
        )
        assertEquals("http://192.168.42.129:8765/cam.ts", links[0].url)
    }

    @Test fun privateRanges() {
        for (ip in listOf("10.0.0.1", "172.16.0.1", "172.31.9.9", "192.168.0.1", "169.254.1.1")) assertTrue(ip, Links.isPrivate(ip))
        for (ip in listOf("172.32.0.1", "11.0.0.1", "100.64.0.1", "1.2.3", "a.b.c.d", "256.1.1.1", "")) assertFalse(ip, Links.isPrivate(ip))
    }

    @Test fun clientLinkKind() {
        val links = listOf(Link(LinkKind.BLUETOOTH, "bt-pan", "192.168.44.1"), Link(LinkKind.WIFI, "wlan0", "192.168.1.2"))
        assertEquals(LinkKind.BLUETOOTH, Links.kindOf(InetAddress.getByName("192.168.44.1"), links))
        assertEquals(LinkKind.WIFI, Links.kindOf(InetAddress.getByName("192.168.1.2"), links))
        assertEquals("adb forward", LinkKind.USB, Links.kindOf(InetAddress.getByName("127.0.0.1"), links))
        assertNull(Links.kindOf(InetAddress.getByName("192.168.9.9"), links))
    }

    @Test fun vpnInterfaces() {
        for (n in listOf("tun0", "tun1", "ppp0", "wg0")) assertTrue(n, Links.isVpn(n))
        for (n in listOf("wlan0", "rndis0", "ccmni1", "lo", "bt-pan")) assertFalse(n, Links.isVpn(n))
    }

    @Test fun adbStrings() {
        assertEquals("http://127.0.0.1:8765/cam.ts", Links.ADB_URL)
        assertEquals("adb forward tcp:8765 tcp:8765", Links.ADB_COMMAND)
    }
}
