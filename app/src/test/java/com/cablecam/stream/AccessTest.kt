package com.cablecam.stream

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessTest {
    @Test fun onlyPrivateAndLoopbackPeers() {
        val cases = mapOf(
            "127.0.0.1" to true, "::1" to true,
            "192.168.42.129" to true, "192.168.1.10" to true, "10.0.0.5" to true,
            "172.16.3.4" to true, "172.31.255.1" to true, "169.254.10.20" to true,
            "172.32.0.1" to false, "8.8.8.8" to false, "100.64.0.1" to false,
            "2001:db8::1" to false, "fe80::1" to false, "fd00::1" to false,
        )
        for ((ip, want) in cases) assertEquals(ip, want, Access.allowed(InetAddress.getByName(ip)))
    }
}
