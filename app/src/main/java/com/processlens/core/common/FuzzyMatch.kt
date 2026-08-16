package com.processlens.core.common

/**
 * Subsequence-based fuzzy matching for the global search (Section 30).
 *
 * Implemented locally rather than pulled from a library because the scoring has
 * to suit identifiers: `cac` should rank `com.android.chrome` highly (it hits
 * the start of three dot-separated segments), and `chrome` should beat it on the
 * same target because it is a contiguous run. A generic edit-distance ranker
 * gets both of those backwards.
 */
object FuzzyMatch {

    private const val SCORE_EXACT = 1_000
    private const val SCORE_PREFIX = 700
    private const val SCORE_CONTAINS = 500
    private const val BONUS_SEGMENT_START = 45
    private const val BONUS_CONSECUTIVE = 22
    private const val BONUS_CAMEL_CASE = 30
    private const val PENALTY_GAP = 3
    private const val PENALTY_LEADING = 2

    /**
     * Higher is better; 0 means no match. Callers filter on `> 0` and sort
     * descending, so the absolute numbers only matter relative to each other.
     */
    fun score(query: String, target: String): Int {
        if (query.isEmpty()) return 1
        if (target.isEmpty()) return 0

        val q = query.trim().lowercase()
        val t = target.lowercase()

        if (t == q) return SCORE_EXACT
        if (t.startsWith(q)) return SCORE_PREFIX + lengthBonus(q, t)

        // A hit at a segment boundary ("...​.chrome" for "chrome") is worth much
        // more than one buried mid-token, because package names are hierarchical.
        val containsIdx = t.indexOf(q)
        if (containsIdx >= 0) {
            val atBoundary = containsIdx == 0 || !t[containsIdx - 1].isLetterOrDigit()
            val base = SCORE_CONTAINS + if (atBoundary) BONUS_SEGMENT_START else 0
            return base + lengthBonus(q, t) - (containsIdx * PENALTY_LEADING).coerceAtMost(80)
        }

        return subsequenceScore(q, target, t)
    }

    fun matches(query: String, target: String): Boolean = score(query, target) > 0

    /** Scores against several fields and keeps the best — name, package, etc. */
    fun bestScore(query: String, vararg targets: String?): Int =
        targets.filterNotNull().maxOfOrNull { score(query, it) } ?: 0

    /**
     * Character positions in [target] that the query matched, for highlighting.
     * Returns an empty list when there is no match.
     */
    fun matchedIndices(query: String, target: String): List<Int> {
        if (query.isEmpty() || target.isEmpty()) return emptyList()
        val q = query.trim().lowercase()
        val t = target.lowercase()

        val contains = t.indexOf(q)
        if (contains >= 0) return (contains until contains + q.length).toList()

        val out = ArrayList<Int>(q.length)
        var ti = 0
        for (qc in q) {
            var found = -1
            while (ti < t.length) {
                if (t[ti] == qc) {
                    found = ti
                    ti++
                    break
                }
                ti++
            }
            if (found < 0) return emptyList()
            out += found
        }
        return out
    }

    private fun lengthBonus(query: String, target: String): Int {
        // Prefer the shortest target that contains the query: typing "chrome"
        // should surface `Chrome` above `Chrome Beta Webview Shell`.
        val excess = (target.length - query.length).coerceAtLeast(0)
        return (60 - excess).coerceAtLeast(0)
    }

    private fun subsequenceScore(q: String, original: String, t: String): Int {
        var score = 0
        var ti = 0
        var lastMatch = -2
        var consecutive = 0

        for (qc in q) {
            var found = -1
            while (ti < t.length) {
                if (t[ti] == qc) {
                    found = ti
                    break
                }
                ti++
            }
            if (found < 0) return 0

            score += 10
            if (found == lastMatch + 1) {
                consecutive++
                score += BONUS_CONSECUTIVE * consecutive.coerceAtMost(4)
            } else {
                consecutive = 0
                val gap = found - lastMatch - 1
                if (lastMatch >= 0) score -= (gap * PENALTY_GAP).coerceAtMost(30)
            }

            val prev = if (found > 0) t[found - 1] else '.'
            if (!prev.isLetterOrDigit()) {
                score += BONUS_SEGMENT_START
            } else if (found < original.length && original[found].isUpperCase() &&
                original.getOrNull(found - 1)?.isLowerCase() == true
            ) {
                // camelCase boundary: "RenderThread" matches "rt".
                score += BONUS_CAMEL_CASE
            }

            lastMatch = found
            ti++
        }

        score -= (t.length - q.length).coerceIn(0, 40) / 2
        return score.coerceAtLeast(1)
    }
}
