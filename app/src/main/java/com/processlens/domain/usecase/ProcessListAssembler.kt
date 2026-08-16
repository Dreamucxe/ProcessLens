package com.processlens.domain.usecase

import com.processlens.core.common.AccessLevel
import com.processlens.core.common.DataSource
import com.processlens.core.common.Observed
import com.processlens.core.common.valueOrNull
import com.processlens.domain.model.DiscoveryMethod
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.repository.ProcessTreeNode

/**
 * Turns a raw list of discovered processes into the two things the UI needs to interpret
 * it: how complete the list is, and how the processes relate to each other (Sections 11, 12).
 *
 * Pure, so it is verifiable without a device. That matters here more than for most helpers,
 * because both functions exist to *refuse* to say something:
 *
 *  - [describe] will not call a list complete because the API level suggests it should be.
 *    Completeness is derived from the discovery methods that actually produced the rows —
 *    a device with elevated access on API 34 has the whole table, and one on API 29
 *    without it does not, and no amount of build-time reasoning can tell them apart
 *    (Section 47).
 *  - [buildTree] will not invent a parent. Android exposes `ppid` only where /proc or a
 *    shell is readable; where it is not, this returns [Observed.Restricted] rather than a
 *    flat list dressed as a tree, and it never groups by package name. An app's processes
 *    are siblings under `zygote`, not parents of one another, so grouping them by name
 *    would fabricate a relationship the platform does not expose — which Section 42
 *    forbids outright.
 */
object ProcessListAssembler {

    /** Depth guard for reported-ppid cycles, which real OEM kernels do produce. */
    const val MAX_TREE_DEPTH = 32

    /**
     * Whether the list is the system's actual process table, and the sentence to show
     * when it is not.
     *
     * [limitationNote] is null only in the complete case — every restricted case gets
     * wording, because Section 42 requires the limitation to be communicated rather than
     * left for the user to infer from a suspiciously short list.
     */
    data class Completeness(
        val isCompleteList: Boolean,
        val limitationNote: String?,
    )

    fun describe(methods: List<DiscoveryMethod>): Completeness {
        // Complete only if a method that *is* complete produced the list. SELF is
        // excluded from that test: it is technically a complete enumeration of one
        // process, which is exactly the restricted case this flag exists to expose.
        val hasCompleteWalk = methods.any { it.isCompleteList && it != DiscoveryMethod.SELF }
        val onlySelf = methods.size == 1 && methods.first() == DiscoveryMethod.SELF

        return Completeness(
            isCompleteList = hasCompleteWalk,
            limitationNote = when {
                hasCompleteWalk -> null
                onlySelf -> "Android only allows this app to see its own process on this device. " +
                    "Recently-active apps are listed from usage access where granted; " +
                    "Shizuku or root access shows the full process table."
                methods.isEmpty() -> "No process discovery method succeeded on this device, so no " +
                    "processes could be listed. Shizuku or root access shows the full process table."
                else -> "This is not the complete process table. Android restricts process " +
                    "visibility on this version, so only processes discovered through " +
                    methods.joinToString(", ") { it.label } + " are shown."
            },
        )
    }

    /**
     * Parent/child structure built from real `ppid` values only.
     *
     * Requires at least two processes with a readable parent: one is not a tree, and on a
     * restricted device that one is our own process, whose parent tells the user nothing.
     */
    fun buildTree(processes: List<ProcessInfo>): Observed<List<ProcessTreeNode>> {
        val withParents = processes.filter { it.parentPid.valueOrNull != null }

        if (withParents.size < 2) {
            return Observed.platform(
                "Parent process identifiers are not readable on this device, so the process " +
                    "tree cannot be built. Shizuku or root access exposes them.",
                AccessLevel.SHIZUKU,
            )
        }

        val byPid = processes.mapNotNull { p -> p.pid.valueOrNull?.let { it to p } }.toMap()
        val childrenOf = HashMap<Int, MutableList<ProcessInfo>>()
        val roots = ArrayList<ProcessInfo>()

        for (process in processes) {
            val ppid = process.parentPid.valueOrNull
            if (ppid == null || ppid == 0 || !byPid.containsKey(ppid)) {
                // No visible parent: a genuine root as far as this device shows us.
                roots += process
            } else {
                childrenOf.getOrPut(ppid) { ArrayList() } += process
            }
        }

        fun build(process: ProcessInfo, depth: Int): ProcessTreeNode {
            val pid = process.pid.valueOrNull
            // A cycle in reported ppids (seen on some OEM kernels during PID reuse) would
            // otherwise recurse until the stack overflows.
            val children = if (pid == null || depth >= MAX_TREE_DEPTH) {
                emptyList()
            } else {
                childrenOf[pid].orEmpty()
                    .filter { it.pid.valueOrNull != pid }
                    .map { build(it, depth + 1) }
            }
            return ProcessTreeNode(process, children.sortedBy { it.process.displayName })
        }

        return Observed.of(
            roots.map { build(it, 0) }.sortedBy { it.process.displayName },
            DataSource.PROC_FS,
        )
    }
}
