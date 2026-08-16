package com.processlens.domain.usecase

import com.processlens.core.common.AccessLevel
import com.processlens.core.common.DataSource
import com.processlens.core.common.Observed
import com.processlens.core.common.RestrictionReason
import com.processlens.core.common.valueOrNull
import com.processlens.domain.model.DiscoveryMethod
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.repository.ProcessTreeNode
import com.processlens.testing.Fixtures
import com.processlens.testing.assertContains
import com.processlens.testing.assertOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Process-list completeness and tree assembly (Sections 11, 12, 42, 47, 56).
 *
 * Two decisions live in [ProcessListAssembler], and both are decisions to withhold:
 *
 *  - **Is this the real process table?** Answered from the discovery methods that
 *    actually produced rows, never from the API level. A device with Shizuku on API 34
 *    has the whole table; one without it on API 29 does not; build-time reasoning cannot
 *    tell them apart, which is what Section 47 means by computing capability at runtime.
 *  - **How do these processes relate?** Only from real `ppid` values. Grouping an app's
 *    processes under its package name would look like a tree and be a fiction — they are
 *    siblings under `zygote` — and Section 42 forbids inventing relationships Android
 *    does not expose.
 */
class ProcessListAssemblerTest {

    private fun process(
        name: String,
        pid: Int? = null,
        parentPid: Int? = null,
        label: String? = null,
    ): ProcessInfo = Fixtures.process(
        name = name,
        label = label,
        pid = pid?.let { Fixtures.value(it) } ?: Observed.platform("test: pid withheld"),
        parentPid = parentPid?.let { Fixtures.value(it) }
            ?: Observed.platform("test: parent pid withheld"),
    )

    /** Flattens to `parent > child` paths, which is the shape these tests want to assert. */
    private fun paths(nodes: List<ProcessTreeNode>, prefix: String = ""): List<String> =
        nodes.flatMap { node ->
            val here = if (prefix.isEmpty()) node.process.processName else "$prefix > ${node.process.processName}"
            listOf(here) + paths(node.children, here)
        }

    // ------------------------------------------------------------------ completeness

    @Test
    fun `a proc walk is a complete list and needs no caveat`() {
        val completeness = ProcessListAssembler.describe(listOf(DiscoveryMethod.PROC_WALK))

        assertTrue(completeness.isCompleteList)
        // The only case with no note. Every other case gets wording, because Section 42
        // requires the limitation to be stated rather than inferred from a short list.
        assertNull(completeness.limitationNote)
    }

    @Test
    fun `an elevated shell listing is a complete list`() {
        for (method in listOf(DiscoveryMethod.SHELL_PS, DiscoveryMethod.ROOT_PS)) {
            val completeness = ProcessListAssembler.describe(listOf(method))
            assertTrue("$method should be complete", completeness.isCompleteList)
            assertNull(completeness.limitationNote)
        }
    }

    @Test
    fun `seeing only our own process is not a complete list`() {
        // SELF is technically a complete enumeration of one process, which is precisely
        // the restricted case this flag exists to expose. Treating it as complete would
        // tell the user their device is running one process.
        val completeness = ProcessListAssembler.describe(listOf(DiscoveryMethod.SELF))

        assertFalse(completeness.isCompleteList)
        val note = completeness.limitationNote!!
        assertContains(note, "only allows this app to see its own process")
        assertContains(note, "Shizuku or root")
    }

    @Test
    fun `usage access alone is not a process table`() {
        val completeness = ProcessListAssembler.describe(listOf(DiscoveryMethod.USAGE_STATS))

        assertFalse(completeness.isCompleteList)
        assertContains(completeness.limitationNote!!, "not the complete process table")
        // The note names how the rows were found, so the reader can judge them.
        assertContains(completeness.limitationNote!!, DiscoveryMethod.USAGE_STATS.label)
    }

    @Test
    fun `ActivityManager alone is not a process table`() {
        val completeness = ProcessListAssembler.describe(listOf(DiscoveryMethod.ACTIVITY_MANAGER))

        assertFalse(completeness.isCompleteList)
        assertContains(completeness.limitationNote!!, DiscoveryMethod.ACTIVITY_MANAGER.label)
    }

    @Test
    fun `every partial method combination names all of its sources`() {
        val methods = listOf(
            DiscoveryMethod.ACTIVITY_MANAGER,
            DiscoveryMethod.USAGE_STATS,
            DiscoveryMethod.SELF,
        )

        val note = ProcessListAssembler.describe(methods).limitationNote!!

        for (method in methods) {
            assertContains(note, method.label)
        }
    }

    @Test
    fun `self combined with usage access is still incomplete`() {
        // The common API 28+ shape: our own process plus recently-active packages. Both
        // are real; together they are still not the process table.
        val completeness = ProcessListAssembler.describe(
            listOf(DiscoveryMethod.SELF, DiscoveryMethod.USAGE_STATS),
        )

        assertFalse(completeness.isCompleteList)
        assertNotNull(completeness.limitationNote)
    }

    @Test
    fun `one complete method makes the list complete even alongside partial ones`() {
        // A /proc walk that succeeded is the whole table; the extra ActivityManager rows
        // it was merged with do not make it less so.
        val completeness = ProcessListAssembler.describe(
            listOf(DiscoveryMethod.ACTIVITY_MANAGER, DiscoveryMethod.PROC_WALK),
        )

        assertTrue(completeness.isCompleteList)
        assertNull(completeness.limitationNote)
    }

    @Test
    fun `no discovery method at all is explained rather than shown as an empty list`() {
        // Total failure is indistinguishable from "nothing is running" unless it is said
        // out loud, and "nothing is running" is never true on Android.
        val completeness = ProcessListAssembler.describe(emptyList())

        assertFalse(completeness.isCompleteList)
        assertContains(completeness.limitationNote!!, "No process discovery method succeeded")
    }

    @Test
    fun `completeness follows the discovery methods and not the enum ordering`() {
        // Pins the rule to `isCompleteList` on each method rather than to any particular
        // list of names, so adding a discovery method cannot silently change the verdict.
        for (method in DiscoveryMethod.entries) {
            val expected = method.isCompleteList && method != DiscoveryMethod.SELF
            assertEquals(
                "wrong completeness for $method",
                expected,
                ProcessListAssembler.describe(listOf(method)).isCompleteList,
            )
        }
    }

    // -------------------------------------------------------------------------- tree

    @Test
    fun `a tree is not built when parent pids are unreadable`() {
        val processes = listOf(
            process("com.a", pid = 100),
            process("com.b", pid = 200),
            process("com.c", pid = 300),
        )

        val tree = ProcessListAssembler.buildTree(processes)

        // Restricted, not an empty list and certainly not a flat list dressed as a tree.
        val restricted = tree as Observed.Restricted
        assertEquals(RestrictionReason.PLATFORM_RESTRICTED, restricted.reason)
        assertEquals(AccessLevel.SHIZUKU, restricted.unlockedBy)
        assertContains(restricted.detail, "Parent process identifiers are not readable")
    }

    @Test
    fun `a single process with a parent is not a tree`() {
        // On a restricted device the one process with a readable parent is our own, and
        // its parent tells the user nothing worth drawing.
        val processes = listOf(
            process("com.processlens", pid = 100, parentPid = 1),
            process("com.a", pid = 200),
        )

        assertTrue(ProcessListAssembler.buildTree(processes) is Observed.Restricted)
    }

    @Test
    fun `real parent pids produce real nesting`() {
        val processes = listOf(
            process("zygote", pid = 500, parentPid = 1),
            process("com.a", pid = 600, parentPid = 500),
            process("com.a_child", pid = 700, parentPid = 600),
            process("com.b", pid = 800, parentPid = 500),
        )

        val nodes = ProcessListAssembler.buildTree(processes).valueOrNull!!

        assertOrder(
            listOf(
                "zygote",
                "zygote > com.a",
                "zygote > com.a > com.a_child",
                "zygote > com.b",
            ),
            paths(nodes),
        )
    }

    @Test
    fun `a process whose parent is invisible is shown as a root`() {
        // Ordinary on Android: `init` is not in a restricted app's view, so its children
        // surface as roots. Better than dropping them or inventing a placeholder parent.
        val processes = listOf(
            process("com.a", pid = 600, parentPid = 500),
            process("com.b", pid = 700, parentPid = 500),
        )

        val nodes = ProcessListAssembler.buildTree(processes).valueOrNull!!

        assertOrder(listOf("com.a", "com.b"), paths(nodes))
    }

    @Test
    fun `a parent pid of zero is treated as no parent`() {
        val processes = listOf(
            process("kthreadd", pid = 2, parentPid = 0),
            process("kworker", pid = 3, parentPid = 2),
        )

        val nodes = ProcessListAssembler.buildTree(processes).valueOrNull!!

        assertOrder(listOf("kthreadd", "kthreadd > kworker"), paths(nodes))
    }

    @Test
    fun `every process appears exactly once`() {
        val processes = listOf(
            process("root", pid = 1, parentPid = 0),
            process("a", pid = 10, parentPid = 1),
            process("b", pid = 11, parentPid = 1),
            process("c", pid = 12, parentPid = 10),
            process("orphan", pid = 99, parentPid = 4242),
        )

        val flattened = paths(ProcessListAssembler.buildTree(processes).valueOrNull!!)

        assertEquals(processes.size, flattened.size)
        for (p in processes) {
            assertEquals(
                "${p.processName} should appear once, in: $flattened",
                1,
                flattened.count { it == p.processName || it.endsWith("> ${p.processName}") },
            )
        }
    }

    @Test
    fun `a self-parenting process is dropped rather than recursed into`() {
        // Seen in the wild during PID reuse. Such a row is its own child, so it is never
        // a root and never gets drawn — one corrupt row omitted, which is the right trade
        // against a tree that recurses until the stack overflows.
        val processes = listOf(
            process("self_parent", pid = 500, parentPid = 500),
            process("com.a", pid = 600, parentPid = 1),
        )

        val nodes = ProcessListAssembler.buildTree(processes).valueOrNull!!

        assertOrder(listOf("com.a"), paths(nodes))
        assertTrue(paths(nodes).none { it.contains("self_parent > self_parent") })
    }

    @Test
    fun `a ppid cycle terminates instead of overflowing the stack`() {
        // Two processes each naming the other as parent. Neither is a root, so the tree
        // is empty rather than infinite — and the call returns at all, which is the point.
        val processes = listOf(
            process("a", pid = 100, parentPid = 200),
            process("b", pid = 200, parentPid = 100),
        )

        val nodes = ProcessListAssembler.buildTree(processes).valueOrNull!!

        assertTrue(nodes.isEmpty())
    }

    @Test
    fun `a chain deeper than the depth guard is truncated not followed forever`() {
        // A synthetic 200-deep chain: real trees are nowhere near this, so hitting the
        // guard is evidence of corrupt ppids rather than a deep process hierarchy.
        val depth = 200
        val processes = (0 until depth).map { i ->
            process("p$i", pid = 1_000 + i, parentPid = if (i == 0) 0 else 999 + i)
        }

        val nodes = ProcessListAssembler.buildTree(processes).valueOrNull!!

        assertEquals(1, nodes.size)
        // Depth counts nodes, so the guard admits MAX_TREE_DEPTH levels below the root.
        assertEquals(
            ProcessListAssembler.MAX_TREE_DEPTH + 1,
            paths(nodes).maxOf { it.split(" > ").size },
        )
    }

    @Test
    fun `siblings are ordered by their display name`() {
        val processes = listOf(
            process("root", pid = 1, parentPid = 0),
            process("com.zulu", pid = 10, parentPid = 1, label = "Zulu"),
            process("com.alpha", pid = 11, parentPid = 1, label = "Alpha"),
            process("com.mike", pid = 12, parentPid = 1, label = "Mike"),
        )

        val children = ProcessListAssembler.buildTree(processes).valueOrNull!!.single().children

        assertOrder(
            listOf("Alpha", "Mike", "Zulu"),
            children.map { it.process.displayName },
        )
    }

    @Test
    fun `descendant counts are the real subtree sizes`() {
        val processes = listOf(
            process("root", pid = 1, parentPid = 0),
            process("a", pid = 10, parentPid = 1),
            process("b", pid = 11, parentPid = 10),
            process("c", pid = 12, parentPid = 11),
        )

        val root = ProcessListAssembler.buildTree(processes).valueOrNull!!.single()

        assertEquals(3, root.descendantCount)
    }

    @Test
    fun `processes are never grouped by package name`() {
        // The tempting fiction: three processes of one app, no readable ppids. Grouping
        // them under a synthetic "com.example" parent would render as a tree and assert a
        // relationship Android never reported — they are siblings under zygote.
        val processes = listOf(
            process("com.example", pid = 100),
            process("com.example:remote", pid = 101),
            process("com.example:worker", pid = 102),
        )

        assertTrue(ProcessListAssembler.buildTree(processes) is Observed.Restricted)
    }

    @Test
    fun `an empty process list yields no tree`() {
        assertTrue(ProcessListAssembler.buildTree(emptyList()) is Observed.Restricted)
    }

    @Test
    fun `a process with a readable parent but no readable pid is still placed`() {
        // Possible when a row came from a shell listing with a malformed pid column: the
        // process is real, so it appears, but nothing can be nested beneath it.
        val processes = listOf(
            process("root", pid = 1, parentPid = 0),
            process("noPid", parentPid = 1),
            process("other", pid = 20, parentPid = 1),
        )

        val nodes = ProcessListAssembler.buildTree(processes).valueOrNull!!
        val names = paths(nodes)

        assertEquals(3, names.size)
        assertTrue(names.any { it.endsWith("> noPid") })
    }

    @Test
    fun `the tree records where it came from`() {
        val processes = listOf(
            process("root", pid = 1, parentPid = 0),
            process("a", pid = 10, parentPid = 1),
        )

        // Provenance travels with the value, so the UI can say how it knows.
        val observed = ProcessListAssembler.buildTree(processes)
        assertEquals(DataSource.PROC_FS, (observed as Observed.Value<*>).source)
    }
}
