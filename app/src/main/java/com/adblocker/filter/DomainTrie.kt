package com.adblocker.filter

/**
 * In-memory domain matcher for ad/tracker blocklists.
 *
 * Stores each domain as its labels in reverse (e.g. `ads.example.com` -> [com, example, ads]).
 * `matches` walks every suffix of the query's reversed labels, so a stored parent domain (or a
 * `*.parent` wildcard root) also matches any of its subdomains.
 */
class DomainTrie {
    private val exact = HashSet<List<String>>() // reversed labels of a blocked domain
    private val wildcardRoots = HashSet<List<String>>() // reversed labels of a "*.parent" root

    fun add(domain: String) {
        val d = domain.lowercase().trim()
        if (d.isEmpty() || d.startsWith("#") || d == "localhost" || d.startsWith("127.0.0.1")) return
        val (labels, wildcard) = if (d.startsWith("*.")) {
            d.removePrefix("*.").split('.') to true
        } else {
            d.split('.') to false
        }
        val cleaned = labels.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        val rev = cleaned.asReversed()
        if (wildcard) wildcardRoots.add(rev) else exact.add(rev)
    }

    fun matches(domain: String): Boolean {
        val d = domain.lowercase().trim()
        if (d.isEmpty()) return false
        val labels = d.split('.').filter { it.isNotEmpty() }
        if (labels.isEmpty()) return false
        val rev = labels.asReversed()
        // A stored domain is a *prefix* of the reversed labels (e.g. parent "doubleclick.net"
        // reverses to [net, doubleclick], which is the prefix of [net, doubleclick, ad]).
        for (k in 1..rev.size) {
            val prefix = rev.subList(0, k)
            if (exact.contains(prefix) || wildcardRoots.contains(prefix)) return true
        }
        return false
    }
}
