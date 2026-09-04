package com.adblocker.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [BlocklistManager.extractDomain], the pure line-parsing step of the
 * two-phase blocklist load. Matching semantics live in [DiskMatcherTest].
 */
class BlocklistManagerTest {

    @Test
    fun hostsFormatExtractsDomain() {
        assertEquals("ads.example.com", BlocklistManager.extractDomain("0.0.0.0 ads.example.com"))
        assertEquals("ads.example.com", BlocklistManager.extractDomain("127.0.0.1 ads.example.com"))
    }

    @Test
    fun bareDomainIsParsed() {
        assertEquals("doubleclick.net", BlocklistManager.extractDomain("doubleclick.net"))
    }

    @Test
    fun parsingIsCaseInsensitiveAndTrimmed() {
        assertEquals("ads.example.com", BlocklistManager.extractDomain("0.0.0.0 Ads.Example.COM"))
        assertEquals("ads.example.com", BlocklistManager.extractDomain("  0.0.0.0 ads.example.com  "))
    }

    @Test
    fun commentsAndBlanksAreRejected() {
        assertNull(BlocklistManager.extractDomain("# a comment"))
        assertNull(BlocklistManager.extractDomain("   "))
        assertNull(BlocklistManager.extractDomain("! adblock entry"))
    }

    @Test
    fun wildcardPrefixIsStripped() {
        assertEquals("tracker.net", BlocklistManager.extractDomain("*.tracker.net"))
    }

    @Test
    fun localhostAndLoopbackAreRejected() {
        assertNull(BlocklistManager.extractDomain("localhost"))
        assertNull(BlocklistManager.extractDomain("0.0.0.0 localhost"))
        assertNull(BlocklistManager.extractDomain("127.0.0.1"))
    }

    @Test
    fun bareTldIsRejected() {
        // Guards the IME space-insertion over-block: "example. com" used to be parsed
        // as the bare TLD "com" (blocking every .com domain). A single-label domain is
        // now rejected so no over-blocking can occur.
        assertNull(BlocklistManager.extractDomain("com"))
        assertNull(BlocklistManager.extractDomain("example. com"))
    }
}