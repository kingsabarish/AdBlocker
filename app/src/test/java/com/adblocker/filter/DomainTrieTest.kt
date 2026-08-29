package com.adblocker.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTrieTest {

    @Test
    fun exactMatch() {
        val t = DomainTrie().apply { add("doubleclick.net") }
        assertTrue(t.matches("doubleclick.net"))
        assertFalse(t.matches("example.com"))
    }

    @Test
    fun subdomainMatch() {
        val t = DomainTrie().apply { add("doubleclick.net") }
        assertTrue(t.matches("ad.doubleclick.net"))
        assertTrue(t.matches("x.y.doubleclick.net"))
    }

    @Test
    fun wildcardMatchesSubdomainsAndExact() {
        val t = DomainTrie().apply { add("*.adnxs.com") }
        assertTrue(t.matches("foo.adnxs.com"))
        assertTrue(t.matches("a.b.adnxs.com"))
        assertTrue(t.matches("adnxs.com"))
        assertFalse(t.matches("notadnxs.com"))
    }

    @Test
    fun ignoresCommentsAndLocalhost() {
        val t = DomainTrie().apply {
            add("# comment")
            add("localhost")
            add("127.0.0.1")
        }
        assertFalse(t.matches("localhost"))
        assertFalse(t.matches("127.0.0.1"))
    }
}
