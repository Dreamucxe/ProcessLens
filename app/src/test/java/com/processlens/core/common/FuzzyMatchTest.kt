package com.processlens.core.common

import com.processlens.testing.assertOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuzzy matching for the global search (Sections 30, 56).
 *
 * The assertions are mostly *relative* — "this target must outrank that one" — because
 * the absolute scores are an implementation detail the callers never see. What matters
 * is the ranking a user experiences when they type three letters into a list of
 * hierarchical package names.
 */
class FuzzyMatchTest {

    private fun score(query: String, target: String) = FuzzyMatch.score(query, target)

    // ------------------------------------------------------------------- matching

    @Test
    fun `an exact match scores highest of all`() {
        val exact = score("chrome", "chrome")

        assertTrue(exact > score("chrome", "chromework"))
        assertTrue(exact > score("chrome", "com.android.chrome"))
    }

    @Test
    fun `a prefix outranks a match buried later in the string`() {
        assertTrue(score("chrome", "chrome beta") > score("chrome", "com.android.chrome"))
    }

    @Test
    fun `a contiguous run outranks a scattered subsequence`() {
        // Typing "chrome" should land on Chrome, not on a package that merely contains
        // those six letters spread across it.
        assertTrue(score("chrome", "com.android.chrome") > score("chrome", "com.h.rome.example"))
    }

    @Test
    fun `a hit at a segment boundary outranks one buried mid token`() {
        // Package names are hierarchical, so a match that starts a dotted segment is
        // far more likely to be what the user meant. Both targets are the same length
        // so only the boundary distinguishes them.
        assertTrue(score("chrome", "aa.chrome") > score("chrome", "aachromeb"))
    }

    @Test
    fun `the shorter of two matching targets wins`() {
        // "chrome" should surface Chrome above Chrome Beta Webview Shell.
        assertTrue(score("chrome", "Chrome") > score("chrome", "Chrome Beta Webview Shell"))
    }

    @Test
    fun `a subsequence across segment starts matches`() {
        // "cac" hits the start of three dot-separated segments in com.android.chrome.
        assertTrue(FuzzyMatch.matches("cac", "com.android.chrome"))
    }

    @Test
    fun `matching is case insensitive in both directions`() {
        assertTrue(FuzzyMatch.matches("CHROME", "com.android.chrome"))
        assertTrue(FuzzyMatch.matches("chrome", "COM.ANDROID.CHROME"))
    }

    @Test
    fun `a camel case boundary is rewarded`() {
        // Thread names are camelCase rather than dotted, so "rt" should find
        // RenderQueueThread ahead of the same letters in a flat lowercase name.
        assertTrue(score("rt", "RenderQueueThread") > score("rt", "renderqueuethread"))
    }

    @Test
    fun `a query with no match scores zero`() {
        assertEquals(0, score("zzzz", "com.android.chrome"))
        assertFalse(FuzzyMatch.matches("zzzz", "com.android.chrome"))
    }

    @Test
    fun `a query longer than its target cannot match`() {
        assertEquals(0, score("chromium", "chrome"))
    }

    @Test
    fun `an out of order query does not match`() {
        // Subsequence, not anagram: "emorhc" is not a match for "chrome".
        assertEquals(0, score("emorhc", "chrome"))
    }

    @Test
    fun `an empty query matches everything weakly`() {
        // The search field starts empty and the list must not vanish, but neither may
        // an empty query outrank a real one.
        assertTrue(score("", "anything") > 0)
        assertTrue(score("", "anything") < score("anything", "anything"))
    }

    @Test
    fun `an empty target never matches`() {
        assertEquals(0, score("chrome", ""))
    }

    @Test
    fun `surrounding whitespace in the query is ignored`() {
        assertEquals(score("chrome", "com.android.chrome"), score("  chrome  ", "com.android.chrome"))
    }

    // ------------------------------------------------------------------ bestScore

    @Test
    fun `the best score across several fields wins`() {
        // A row is searched by label, process name and package; the strongest field
        // decides its rank, so typing an app's label finds it even when its package
        // name looks nothing like it.
        val best = FuzzyMatch.bestScore("aardvark", "Aardvark Browser", "com.zzz.app", "com.zzz.app")

        assertEquals(best, FuzzyMatch.score("aardvark", "Aardvark Browser"))
    }

    @Test
    fun `null fields are skipped rather than treated as empty matches`() {
        assertEquals(
            FuzzyMatch.score("chrome", "com.android.chrome"),
            FuzzyMatch.bestScore("chrome", null, "com.android.chrome", null),
        )
    }

    @Test
    fun `a row with nothing but null fields scores zero`() {
        assertEquals(0, FuzzyMatch.bestScore("chrome", null, null))
    }

    // ------------------------------------------------------------- highlighting

    @Test
    fun `a contiguous match reports the exact span it covered`() {
        val indices = FuzzyMatch.matchedIndices("chrome", "com.android.chrome")

        assertEquals((12..17).toList(), indices)
    }

    @Test
    fun `a subsequence match reports one index per query character`() {
        val indices = FuzzyMatch.matchedIndices("cac", "com.android.chrome")

        assertEquals(3, indices.size)
        // Strictly increasing: a highlight can never run backwards over the text.
        assertEquals(indices.sorted(), indices)
        assertEquals(indices.toSet().size, indices.size)
        indices.forEach { assertTrue(it in "com.android.chrome".indices) }
    }

    @Test
    fun `the reported indices really are the query characters`() {
        val target = "com.android.chrome"
        val indices = FuzzyMatch.matchedIndices("cac", target)

        assertEquals("cac", indices.map { target[it] }.joinToString(""))
    }

    @Test
    fun `no match reports no indices`() {
        assertTrue(FuzzyMatch.matchedIndices("zzzz", "com.android.chrome").isEmpty())
        assertTrue(FuzzyMatch.matchedIndices("", "com.android.chrome").isEmpty())
        assertTrue(FuzzyMatch.matchedIndices("chrome", "").isEmpty())
    }

    // ---------------------------------------------------------------- ranking end

    @Test
    fun `a realistic package list ranks the way a user would expect`() {
        val targets = listOf(
            "com.google.android.gms",
            "com.google.android.gms.persistent",
            "com.example.gamesomething",
            "com.android.chrome",
        )

        val ranked = targets
            .map { it to score("gms", it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }

        // Both gms processes must come before the incidental subsequence match, and
        // chrome must not appear at all.
        assertTrue(ranked.size >= 2)
        assertOrder(
            listOf("com.google.android.gms", "com.google.android.gms.persistent"),
            ranked.take(2),
        )
        assertFalse(ranked.contains("com.android.chrome"))
    }
}
