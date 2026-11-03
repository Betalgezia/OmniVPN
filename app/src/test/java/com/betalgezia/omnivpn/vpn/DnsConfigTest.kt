package com.betalgezia.omnivpn.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DnsConfigTest {
    @Test
    fun catalogueIsCompleteAndConsistent() {
        assertEquals(12, DnsCatalog.all.size)
        assertEquals(DnsCatalog.all.size, DnsCatalog.all.map { it.id }.toSet().size)
        assertTrue(DnsCatalog.all.all { it.endpoint is DnsEndpoint.Doh })
        assertTrue(
            DnsCatalog.all.all {
                val text = it.endpoint.text
                text.startsWith("https://") && text.endsWith("/dns-query")
            }
        )
        assertTrue(DnsCatalog.groups.any { it.title == "С обходом геоблокировок (для ИИ)" })
        assertEquals("cloudflare", DnsCatalog.byId("cloudflare")?.id)
        assertNull(DnsCatalog.byId("nope"))
    }

    @Test
    fun parsesDohUrlsAndBareHosts() {
        val full = parseDnsEndpoint("https://cloudflare-dns.com/dns-query")
        assertEquals(DnsEndpoint.Doh("cloudflare-dns.com", 443, "/dns-query"), full)

        // The form the preset list itself uses.
        assertEquals(full, parseDnsEndpoint("cloudflare-dns.com/dns-query"))
        assertEquals(DnsEndpoint.Doh("dns.google", 443, "/dns-query"), parseDnsEndpoint("dns.google"))
        assertEquals(
            DnsEndpoint.Doh("dns.example", 8443, "/custom"),
            parseDnsEndpoint("https://dns.example:8443/custom")
        )
        assertEquals(
            DnsEndpoint.Doh("dns.example", 443, "/dns-query?x=1"),
            parseDnsEndpoint("https://dns.example/dns-query?x=1")
        )
        // Scheme is case-insensitive; the stored host is lower-case.
        assertEquals(DnsEndpoint.Doh("dns.google", 443, "/dns-query"), parseDnsEndpoint("HTTPS://DNS.Google/dns-query"))
    }

    @Test
    fun parsesPlainIpForUdp() {
        assertEquals(DnsEndpoint.Udp("1.1.1.1", 53), parseDnsEndpoint("1.1.1.1"))
        assertEquals(DnsEndpoint.Udp("9.9.9.9", 5353), parseDnsEndpoint(" 9.9.9.9:5353 "))
        assertTrue(isIpv4("255.255.255.255"))
        assertFalse(isIpv4("256.1.1.1"))
        assertFalse(isIpv4("1.1.1"))
        assertFalse(isIpv4("1.1.1.1.1"))
        assertFalse(isIpv4("01.1.1.1"))
    }

    @Test
    fun rejectsInsecureOrBrokenInput() {
        assertNull(parseDnsEndpoint(""))
        assertNull(parseDnsEndpoint("   "))
        assertNull(parseDnsEndpoint("http://dns.google/dns-query"))
        assertNull(parseDnsEndpoint("ftp://dns.google/dns-query"))
        assertNull(parseDnsEndpoint("https://"))
        assertNull(parseDnsEndpoint("https:///dns-query"))
        assertNull(parseDnsEndpoint("1.1.1.1:0"))
        assertNull(parseDnsEndpoint("1.1.1.1:70000"))
        assertNull(parseDnsEndpoint("999.1.1.1"))
        assertNull(parseDnsEndpoint("bad host/dns-query"))
    }

    @Test
    fun canonicalTextRoundTrips() {
        listOf(
            DnsEndpoint.Udp("9.9.9.9"),
            DnsEndpoint.Udp("9.9.9.9", 5353),
            DnsEndpoint.Doh("cloudflare-dns.com"),
            DnsEndpoint.Doh("dns.example", 8443, "/q")
        ).forEach { endpoint ->
            assertEquals(endpoint, parseDnsEndpoint(endpoint.text), endpoint.text)
        }
        assertEquals("https://cloudflare-dns.com/dns-query", DnsEndpoint.Doh("cloudflare-dns.com").text)
        assertEquals("9.9.9.9:5353", DnsEndpoint.Udp("9.9.9.9", 5353).text)
    }

    @Test
    fun summariesNameTheChoice() {
        assertEquals("По умолчанию · 1.1.1.1", dnsSummary(DnsCatalog.DEFAULT_ID, ""))
        assertEquals("Cloudflare · Без фильтрации", dnsSummary("cloudflare", ""))
        assertEquals("Свой · dns.example", dnsSummary(DnsCatalog.CUSTOM_ID, "https://dns.example/dns-query"))
        assertEquals("Свой DNS", dnsSummary(DnsCatalog.CUSTOM_ID, "not a host"))
        assertEquals("По умолчанию · 1.1.1.1", dnsSummary("gone", ""))
    }
}
