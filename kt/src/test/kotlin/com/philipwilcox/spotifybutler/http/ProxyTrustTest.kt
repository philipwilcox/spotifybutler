package com.philipwilcox.spotifybutler.http

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyTrustTest {
    private val defaultProxyAddress = InetAddress.getByName("172.17.0.1")
    private val alternateProxyAddress = InetAddress.getByName("192.0.2.10")

    @Test
    fun `matching proxy token trusts forwarded headers`() {
        assertTrue(
            isTrustedProxy(
                remoteAddress = alternateProxyAddress,
                proxyToken = "proxy-secret",
                trustedProxyAddresses = emptySet(),
                trustedProxyToken = "proxy-secret",
            ),
        )
    }

    @Test
    fun `missing or incorrect proxy token does not trust forwarded headers`() {
        assertFalse(isTrustedProxy(alternateProxyAddress, null, emptySet(), "proxy-secret"))
        assertFalse(isTrustedProxy(alternateProxyAddress, "wrong-secret", emptySet(), "proxy-secret"))
    }

    @Test
    fun `default Docker gateway and configured source address both trust`() {
        assertTrue(isTrustedProxy(defaultProxyAddress, null, setOf("172.17.0.1"), null))
        assertTrue(isTrustedProxy(alternateProxyAddress, null, setOf("172.17.0.1", "192.0.2.10"), null))
    }
}
