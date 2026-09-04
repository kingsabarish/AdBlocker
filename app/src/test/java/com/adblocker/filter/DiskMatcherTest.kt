package com.adblocker.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for [DiskMatcher], the memory-mapped binary-search blocklist matcher
 * that replaced the old in-heap [DomainTrie].
 */
class DiskMatcherTest {

    private fun buildMatcher(domains: List<String>): DiskMatcher {
        // A unique temp dir per call: DiskMatcher.write maps blocklist.bin, and on Windows
        // an mmap'd file stays lock-delimited until the mapping is GC'd. Sharing one path
        // across tests makes a later truncate throw IOException, so isolate each build.
        val dir = Files.createTempDirectory("adblocker-diskmatcher-").toFile()
        val matcher = DiskMatcher.build(dir, domains.toMutableList())
        // Best-effort cleanup; the matcher maps the file so deletion may be deferred.
        runCatching { File(dir, "blocklist.bin").delete() }
        return matcher
    }

    @Test
    fun exactMatch() {
        val m = buildMatcher(listOf("doubleclick.net"))
        assertTrue(m.matches("doubleclick.net"))
        assertFalse(m.matches("example.com"))
    }

    @Test
    fun subdomainMatch() {
        val m = buildMatcher(listOf("doubleclick.net"))
        assertTrue(m.matches("ad.doubleclick.net"))
        assertTrue(m.matches("x.y.doubleclick.net"))
    }

    @Test
    fun leadingWildcardStoredAsRegistrableDomain() {
        // extractDomain strips the leading "*." before storing, so the matcher
        // works on the root domain: it matches the root itself plus all subdomains.
        val m = buildMatcher(listOf("tracker.net"))
        assertTrue(m.matches("tracker.net"))
        assertTrue(m.matches("a.tracker.net"))
        assertTrue(m.matches("a.b.tracker.net"))
        assertFalse(m.matches("tracker.org"))
        assertFalse(m.matches("notracker.net"))
    }

    @Test
    fun partialPrefixIsNotAMatch() {
        val m = buildMatcher(listOf("doubleclick.net"))
        assertFalse(m.matches("notdoubleclick.net"))
        assertFalse(m.matches("xdoubleclick.net"))
    }

    @Test
    fun matchIsCaseInsensitive() {
        val m = buildMatcher(listOf("doubleclick.net"))
        assertTrue(m.matches("DoubleClick.NET"))
        assertTrue(m.matches("AD.doubleclick.net"))
    }
}