// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.*
import org.junit.Test

class LocalAddressPolicyTest {
    private val own = listOf("192.168.1.42", "2001:db8::42", "fe80::42%2")

    @Test fun acceptsOnlyExactLoopbackOrAnInterfaceAddress() {
        for (host in listOf("127.0.0.1", "::1", "[::1]", " 192.168.1.42 ", "2001:0db8:0:0:0:0:0:42", "fe80::42%2")) {
            assertTrue(host, LocalAddressPolicy.accepts(host, own))
        }
        for (host in listOf("192.168.1.43", "8.8.8.8", "127.0.0.2", "2001:db8::43", "fe80::42%3", "fe80::42")) {
            assertFalse(host, LocalAddressPolicy.accepts(host, own))
        }
    }

    @Test fun rejectsDnsNamesAmbiguousLiteralsUrlsWildcardsAndMulticast() {
        for (host in listOf("localhost", "example.org", "https://127.0.0.1", "127.1", "2130706433", "0x7f000001", "127.000.0.1", "127.0.0.1:5555", "192.168.1.256", "0.0.0.0", "::", "224.0.0.1", "ff02::1", "", "123456")) {
            assertNull(host, LocalAddressPolicy.normalizeOrNull(host))
            assertFalse(host, LocalAddressPolicy.accepts(host, own))
        }
    }

    @Test fun localInterfaceEnumerationFailureStillAllowsExplicitLoopback() {
        assertTrue(LocalAddressPolicy.accepts("127.0.0.1", emptyList()))
        assertTrue(LocalAddressPolicy.accepts("::1", emptyList()))
        assertFalse(LocalAddressPolicy.accepts("192.168.1.42", emptyList()))
    }

    @Test fun theConnectionAddressUsedByActivationIsAlwaysAccepted() {
        assertTrue(LocalAddressPolicy.accepts(LocalAddressPolicy.LOOPBACK, emptyList()))
        assertEquals("127.0.0.1", LocalAddressPolicy.normalizeOrNull(LocalAddressPolicy.LOOPBACK))
    }

    @Test fun discoveredEndpointKeepsOnlyOwnAddressesAndPrefersIpv4() {
        val ours = { host: String -> LocalAddressPolicy.accepts(host, own) }
        assertEquals(AdbEndpoint("192.168.1.42", 37123), selectOwnEndpoint(listOf("2001:db8::42", "192.168.1.42"), 37123, ours))
        assertEquals(AdbEndpoint("2001:db8::42", 37123), selectOwnEndpoint(listOf("192.168.1.99", "2001:db8::42"), 37123, ours))
        // another phone advertising the same service on the LAN is not ours
        assertNull(selectOwnEndpoint(listOf("192.168.1.99"), 37123, ours))
        assertNull(selectOwnEndpoint(listOf("192.168.1.42"), 0, ours))
        assertNull(selectOwnEndpoint(listOf("192.168.1.42"), 65536, ours))
        assertNull(selectOwnEndpoint(emptyList(), 37123, ours))
    }
}
