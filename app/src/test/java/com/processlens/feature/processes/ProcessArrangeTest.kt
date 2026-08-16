package com.processlens.feature.processes

import com.processlens.core.common.Observed
import com.processlens.domain.model.ProcessImportance
import com.processlens.domain.model.ProcessInfo
import com.processlens.testing.Fixtures
import com.processlens.testing.assertOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sorting, filtering and search for the process browser (Sections 9, 30, 56).
 *
 * `arrange` is a pure function on the companion for exactly this reason: the ordering
 * rules are the part most likely to quietly misrepresent the device, and they are
 * testable here without a ViewModel, a repository or a device.
 *
 * The load-bearing case is a metric Android would not let us read. It must sink to the
 * bottom of a numeric sort in *both* directions, because a restricted value is not a
 * small value — putting it first in an ascending CPU sort would read as "these
 * processes are idle", which is the false impression Section 42 forbids.
 */
class ProcessArrangeTest {

    private fun arrange(
        processes: List<ProcessInfo>,
        sort: ProcessSort = ProcessSort.CPU,
        descending: Boolean = true,
        filter: ProcessFilter = ProcessFilter.ALL,
        query: String = "",
        showSystem: Boolean = true,
    ): List<String> = ProcessListViewModel.arrange(
        processes = processes,
        sort = sort,
        descending = descending,
        filter = filter,
        query = query,
        showSystem = showSystem,
    ).map { it.displayName }

    // ------------------------------------------------------------------ filtering

    @Test
    fun `the user filter excludes system processes`() {
        val list = listOf(
            Fixtures.process("com.example.app", isSystem = false),
            Fixtures.process("com.android.systemui", isSystem = true),
        )

        assertOrder(listOf("com.example.app"), arrange(list, filter = ProcessFilter.USER))
    }

    @Test
    fun `the system filter keeps only system processes`() {
        val list = listOf(
            Fixtures.process("com.example.app", isSystem = false),
            Fixtures.process("com.android.systemui", isSystem = true),
        )

        assertOrder(listOf("com.android.systemui"), arrange(list, filter = ProcessFilter.SYSTEM))
    }

    @Test
    fun `the show system setting hides system processes even under the all filter`() {
        val list = listOf(
            Fixtures.process("com.example.app", isSystem = false),
            Fixtures.process("com.android.systemui", isSystem = true),
        )

        assertOrder(
            listOf("com.example.app"),
            arrange(list, filter = ProcessFilter.ALL, showSystem = false),
        )
    }

    @Test
    fun `the foreground filter uses importance bands`() {
        val list = listOf(
            Fixtures.process("fg", importance = ProcessImportance.FOREGROUND),
            Fixtures.process("visible", importance = ProcessImportance.VISIBLE),
            Fixtures.process("svc", importance = ProcessImportance.SERVICE),
            Fixtures.process("cached", importance = ProcessImportance.CACHED),
        )

        assertOrder(
            listOf("fg", "visible"),
            arrange(list, filter = ProcessFilter.FOREGROUND, sort = ProcessSort.NAME, descending = false),
        )
    }

    @Test
    fun `the background filter starts at service importance`() {
        val list = listOf(
            Fixtures.process("fg", importance = ProcessImportance.FOREGROUND),
            Fixtures.process("perceptible", importance = ProcessImportance.PERCEPTIBLE),
            Fixtures.process("svc", importance = ProcessImportance.SERVICE),
            Fixtures.process("cached", importance = ProcessImportance.CACHED),
        )

        assertOrder(
            listOf("cached", "svc"),
            arrange(list, filter = ProcessFilter.BACKGROUND, sort = ProcessSort.NAME, descending = false),
        )
    }

    @Test
    fun `the cached filter is exact rather than a band`() {
        val list = listOf(
            Fixtures.process("svc", importance = ProcessImportance.SERVICE),
            Fixtures.process("cached", importance = ProcessImportance.CACHED),
            Fixtures.process("gone", importance = ProcessImportance.GONE),
        )

        assertOrder(listOf("cached"), arrange(list, filter = ProcessFilter.CACHED))
    }

    @Test
    fun `the with metrics filter keeps only rows that have a real reading`() {
        val list = listOf(
            Fixtures.process("has-cpu", cpu = Fixtures.value(10f)),
            Fixtures.process("has-memory", memory = Fixtures.value(1_000L)),
            // Nothing readable: on API 29+ this is what most rows look like.
            Fixtures.process("has-nothing"),
        )

        assertOrder(
            listOf("has-cpu", "has-memory"),
            arrange(list, filter = ProcessFilter.WITH_METRICS, sort = ProcessSort.NAME, descending = false),
        )
    }

    @Test
    fun `filtering an empty list yields an empty list`() {
        assertTrue(arrange(emptyList()).isEmpty())
    }

    // -------------------------------------------------------------------- sorting

    @Test
    fun `cpu sorts descending by default`() {
        val list = listOf(
            Fixtures.process("bravo", cpu = Fixtures.value(5f)),
            Fixtures.process("alpha", cpu = Fixtures.value(80f)),
        )

        assertOrder(listOf("alpha", "bravo"), arrange(list, sort = ProcessSort.CPU, descending = true))
    }

    @Test
    fun `an unreadable cpu sinks to the bottom when sorting descending`() {
        val list = listOf(
            Fixtures.process("restricted"),
            Fixtures.process("busy", cpu = Fixtures.value(80f)),
            Fixtures.process("quiet", cpu = Fixtures.value(5f)),
        )

        assertOrder(
            listOf("busy", "quiet", "restricted"),
            arrange(list, sort = ProcessSort.CPU, descending = true),
        )
    }

    @Test
    fun `an unreadable cpu sinks to the bottom when sorting ascending too`() {
        // The whole point of the rule. Ascending order asks "who is quietest", and a
        // process whose CPU Android refused to disclose is not the answer.
        val list = listOf(
            Fixtures.process("restricted"),
            Fixtures.process("busy", cpu = Fixtures.value(80f)),
            Fixtures.process("quiet", cpu = Fixtures.value(5f)),
        )

        assertOrder(
            listOf("quiet", "busy", "restricted"),
            arrange(list, sort = ProcessSort.CPU, descending = false),
        )
    }

    @Test
    fun `two unreadable metrics fall back to alphabetical order`() {
        val list = listOf(
            Fixtures.process("delta"),
            Fixtures.process("charlie"),
        )

        assertOrder(listOf("charlie", "delta"), arrange(list, sort = ProcessSort.CPU))
    }

    @Test
    fun `a zero cpu reading still outranks an unreadable one`() {
        // A measured 0% is a fact; an unavailable figure is not. Descending order puts
        // the fact first because it is a row the user can reason about.
        val list = listOf(
            Fixtures.process("restricted"),
            Fixtures.process("measured-idle", cpu = Fixtures.value(0f)),
        )

        assertOrder(
            listOf("measured-idle", "restricted"),
            arrange(list, sort = ProcessSort.CPU, descending = true),
        )
    }

    @Test
    fun `memory sorts on its own readings`() {
        val list = listOf(
            Fixtures.process("small", memory = Fixtures.value(1_000_000L)),
            Fixtures.process("large", memory = Fixtures.value(900_000_000L)),
            Fixtures.process("unknown", cpu = Fixtures.value(50f)),
        )

        assertOrder(
            listOf("large", "small", "unknown"),
            arrange(list, sort = ProcessSort.MEMORY, descending = true),
        )
    }

    @Test
    fun `name sorts alphabetically in both directions`() {
        val list = listOf(
            Fixtures.process("charlie"),
            Fixtures.process("alpha"),
            Fixtures.process("bravo"),
        )

        assertOrder(
            listOf("alpha", "bravo", "charlie"),
            arrange(list, sort = ProcessSort.NAME, descending = false),
        )
        assertOrder(
            listOf("charlie", "bravo", "alpha"),
            arrange(list, sort = ProcessSort.NAME, descending = true),
        )
    }

    @Test
    fun `name sorting uses the display name rather than the process name`() {
        // An app's label is what the user recognises, so that is what the A–Z applies
        // to. Sorting on the raw process name would put "com.zeta.app" (labelled
        // "Aardvark") in the wrong place entirely.
        val list = listOf(
            Fixtures.process("com.aaa.app", label = "Zebra"),
            Fixtures.process("com.zzz.app", label = "Aardvark"),
        )

        assertOrder(
            listOf("Aardvark", "Zebra"),
            arrange(list, sort = ProcessSort.NAME, descending = false),
        )
    }

    @Test
    fun `pid sorts numerically rather than as text`() {
        val list = listOf(
            Fixtures.process("nine", pid = Fixtures.value(9)),
            Fixtures.process("hundred", pid = Fixtures.value(100)),
            Fixtures.process("twenty", pid = Fixtures.value(20)),
        )

        assertOrder(
            listOf("nine", "twenty", "hundred"),
            arrange(list, sort = ProcessSort.PID, descending = false),
        )
    }

    @Test
    fun `threads sort on the readable count`() {
        val list = listOf(
            Fixtures.process("few", threads = Fixtures.value(3)),
            Fixtures.process("many", threads = Fixtures.value(120)),
        )

        assertOrder(listOf("many", "few"), arrange(list, sort = ProcessSort.THREADS, descending = true))
    }

    @Test
    fun `start time sorts newest first when descending`() {
        val list = listOf(
            Fixtures.process("old", startTime = Fixtures.value(Fixtures.T0 - 60_000L)),
            Fixtures.process("new", startTime = Fixtures.value(Fixtures.T0)),
        )

        assertOrder(
            listOf("new", "old"),
            arrange(list, sort = ProcessSort.START_TIME, descending = true),
        )
    }

    @Test
    fun `importance sorts by the platform band order`() {
        val list = listOf(
            Fixtures.process("cached", importance = ProcessImportance.CACHED),
            Fixtures.process("foreground", importance = ProcessImportance.FOREGROUND),
            Fixtures.process("service", importance = ProcessImportance.SERVICE),
        )

        assertOrder(
            listOf("foreground", "service", "cached"),
            arrange(list, sort = ProcessSort.IMPORTANCE, descending = false),
        )
    }

    @Test
    fun `sorting never drops or duplicates a row`() {
        val list = List(20) { i ->
            Fixtures.process(
                "proc$i",
                cpu = if (i % 3 == 0) Fixtures.value(i.toFloat()) else Observed.platform("withheld"),
                memory = if (i % 2 == 0) Fixtures.value(i * 1_000L) else Observed.platform("withheld"),
            )
        }

        for (sort in ProcessSort.entries) {
            for (descending in listOf(true, false)) {
                val out = arrange(list, sort = sort, descending = descending)
                assertEquals("$sort/$descending changed the row count", 20, out.size)
                assertEquals("$sort/$descending duplicated a row", 20, out.toSet().size)
            }
        }
    }

    // --------------------------------------------------------------------- search

    @Test
    fun `a query filters to matching rows`() {
        val list = listOf(
            Fixtures.process("com.android.chrome"),
            Fixtures.process("com.example.notes"),
        )

        assertOrder(listOf("com.android.chrome"), arrange(list, query = "chrome"))
    }

    @Test
    fun `a query ranks the closer match first regardless of the sort column`() {
        // Search results are ranked by relevance, not by CPU: the user typed a name.
        val list = listOf(
            Fixtures.process("com.example.gamesomething", cpu = Fixtures.value(99f)),
            Fixtures.process("com.google.android.gms", cpu = Fixtures.value(1f)),
        )

        val out = arrange(list, query = "gms", sort = ProcessSort.CPU, descending = true)
        assertEquals("com.google.android.gms", out.first())
    }

    @Test
    fun `a query matches a subsequence across segment boundaries`() {
        val list = listOf(Fixtures.process("com.android.chrome"))

        // "cac" hits the start of three dot-separated segments.
        assertOrder(listOf("com.android.chrome"), arrange(list, query = "cac"))
    }

    @Test
    fun `a query matches the app label as well as the package`() {
        val list = listOf(Fixtures.process("com.zzz.app", label = "Aardvark Browser"))

        assertOrder(listOf("Aardvark Browser"), arrange(list, query = "aardvark"))
    }

    @Test
    fun `a query is case insensitive`() {
        val list = listOf(Fixtures.process("com.android.Chrome"))

        assertEquals(1, arrange(list, query = "CHROME").size)
    }

    @Test
    fun `a query that matches nothing yields an empty list rather than everything`() {
        val list = listOf(
            Fixtures.process("com.android.chrome"),
            Fixtures.process("com.example.notes"),
        )

        assertTrue(arrange(list, query = "zzzzqqq").isEmpty())
    }

    @Test
    fun `a whitespace only query is treated as no query`() {
        val list = listOf(
            Fixtures.process("bravo"),
            Fixtures.process("alpha"),
        )

        assertOrder(
            listOf("alpha", "bravo"),
            arrange(list, query = "   ", sort = ProcessSort.NAME, descending = false),
        )
    }

    @Test
    fun `search still respects the active filter`() {
        // Searching must not smuggle a system process past a user-apps filter.
        val list = listOf(
            Fixtures.process("com.android.chrome", isSystem = true),
            Fixtures.process("com.example.chrome.client", isSystem = false),
        )

        assertOrder(
            listOf("com.example.chrome.client"),
            arrange(list, query = "chrome", filter = ProcessFilter.USER),
        )
    }

    @Test
    fun `search respects the show system setting`() {
        val list = listOf(
            Fixtures.process("com.android.chrome", isSystem = true),
        )

        assertTrue(arrange(list, query = "chrome", showSystem = false).isEmpty())
    }
}
