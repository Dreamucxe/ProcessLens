package com.processlens.feature.overview

import com.processlens.core.common.AccessLevel
import com.processlens.core.common.Observed
import com.processlens.core.common.RestrictionReason
import com.processlens.domain.model.Availability
import com.processlens.domain.model.Capability
import com.processlens.domain.model.CapabilityStatus
import com.processlens.domain.model.RootState
import com.processlens.domain.model.ShizukuState
import com.processlens.domain.model.SystemCapabilities
import com.processlens.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the loading state machine (requirement 5 of the issue #1 spec: the
 * dashboard must always resolve — it must never sit forever on "Reading system
 * state").
 *
 * These are pure-function tests, which is the design: the decisions that used to
 * live inside a composable with an undeadlined spinner are now functions over
 * [OverviewViewModel.State], so the guarantee can be asserted without a Compose
 * harness or a device.
 */
class OverviewLoadStateTest {

    private fun state(
        system: com.processlens.domain.repository.SystemState? = null,
        loadFailure: Observed.Failed? = null,
        capabilities: SystemCapabilities? = null,
    ) = OverviewViewModel.State(
        system = system,
        loadFailure = loadFailure,
        capabilities = capabilities,
    )

    private fun caps(
        access: AccessLevel,
        statuses: Map<Capability, CapabilityStatus> = mapOf(
            Capability.CPU_OVERALL to CapabilityStatus(
                Capability.CPU_OVERALL,
                Availability.FULL,
                "read from /proc/stat",
            ),
        ),
    ): SystemCapabilities = SystemCapabilities(
        apiLevel = 34,
        accessLevel = access,
        shizukuState = ShizukuState.NOT_INSTALLED,
        rootState = if (access == AccessLevel.ROOT) RootState.GRANTED else RootState.UNAVAILABLE,
        hasUsageAccess = false,
        hasPhoneStatePermission = false,
        hasNotificationPermission = false,
        isBatteryOptimisationIgnored = false,
        statuses = statuses,
    )

    // ------------------------------------------------- the three sampling outcomes

    @Test
    fun `all readings succeed - the dashboard shows content`() {
        val s = state(system = Fixtures.systemState())

        assertEquals(OverviewLoadPhase.CONTENT, s.loadPhase(deadlineElapsed = false))
        assertEquals(OverviewLoadPhase.CONTENT, s.loadPhase(deadlineElapsed = true))
    }

    @Test
    fun `some readings are denied - the dashboard still shows content`() {
        // CPU and battery temperature restricted, the rest readable: the exact
        // shape of the reporter's device, where thermal and loadavg were denied.
        val s = state(
            system = Fixtures.systemState(
                cpuPercent = Observed.platform("SELinux denied /proc/stat", AccessLevel.SHIZUKU),
                batteryTemperature = Observed.platform("thermal zone denied", AccessLevel.ROOT),
            ),
        )

        assertEquals(
            "one restricted metric must never block the whole screen",
            OverviewLoadPhase.CONTENT,
            s.loadPhase(deadlineElapsed = true),
        )
    }

    @Test
    fun `every reading is denied - the dashboard still resolves to content`() {
        val denied = Observed.platform("SELinux enforcing", AccessLevel.ROOT)
        val s = state(
            system = Fixtures.systemState(
                cpuPercent = denied,
                batteryTemperature = denied,
                rxBytes = denied,
                txBytes = denied,
                ownCpuPercent = denied,
                ownMemoryBytes = denied,
            ),
        )

        assertEquals(
            "a device that denies everything shows per-metric 'Not available', never a spinner",
            OverviewLoadPhase.CONTENT,
            s.loadPhase(deadlineElapsed = true),
        )
    }

    // ------------------------------------------------- the loading always resolves

    @Test
    fun `before the deadline with no sample it is honest to show a spinner`() {
        assertEquals(OverviewLoadPhase.WAITING, state().loadPhase(deadlineElapsed = false))
    }

    @Test
    fun `after the deadline with no sample the spinner gives way to an explanation`() {
        assertEquals(
            "this is the fix for the permanent 'Reading system state' hang",
            OverviewLoadPhase.UNRESOLVED,
            state().loadPhase(deadlineElapsed = true),
        )
    }

    @Test
    fun `a thrown pipeline resolves immediately, without waiting out the deadline`() {
        val s = state(loadFailure = Observed.Failed("boom", "IllegalStateException"))

        assertEquals(OverviewLoadPhase.UNRESOLVED, s.loadPhase(deadlineElapsed = false))
    }

    // ----------------------------------------------------------- loadFailureState

    @Test
    fun `a failure keeps whatever content was already on screen`() {
        val previous = OverviewViewModel.State(
            system = Fixtures.systemState(),
            cpuHistory = listOf(10f, 20f, 30f),
        )

        val folded = loadFailureState(previous, IllegalStateException("db closed"))

        assertEquals("the trend the user was reading is not thrown away", previous.system, folded.system)
        assertEquals(listOf(10f, 20f, 30f), folded.cpuHistory)
        assertEquals("db closed", folded.loadFailure?.detail)
        assertFalse(folded.isRefreshing)
    }

    @Test
    fun `a failure with no message falls back to the exception type`() {
        val folded = loadFailureState(OverviewViewModel.State(), NullPointerException())

        assertEquals("NullPointerException", folded.loadFailure?.detail)
    }

    // ------------------------------------------------------------- unresolved copy

    @Test
    fun `the unresolved title names a failure as a failure and a wait as a wait`() {
        assertEquals("No reading has arrived yet", unresolvedTitle(null))
        assertEquals(
            "System state could not be read",
            unresolvedTitle(Observed.Failed("x")),
        )
    }

    @Test
    fun `both unresolved explanations insist that nothing is being withheld`() {
        assertTrue(unresolvedExplanation(null).contains("not being withheld"))
        assertTrue(unresolvedExplanation(Observed.Failed("x")).contains("Nothing is being withheld"))
    }

    // --------------------------------------------------- capability matrix guard

    @Test
    fun `the placeholder matrix is not treated as an evaluated answer`() {
        assertFalse(isCapabilityMatrixEvaluated(null))
        assertFalse(
            "unknown() seeds an empty matrix so twelve combines resolve on frame one",
            isCapabilityMatrixEvaluated(SystemCapabilities.unknown(34)),
        )
    }

    @Test
    fun `a matrix with any row is treated as evaluated`() {
        assertTrue(isCapabilityMatrixEvaluated(caps(AccessLevel.NORMAL)))
    }

    // --------------------------------------------------- elevated-access notice

    @Test
    fun `the access notice never fires against the placeholder`() {
        assertFalse(showElevatedAccessNotice(SystemCapabilities.unknown(34), isDismissed = false))
    }

    @Test
    fun `the access notice fires for a probed device at normal access`() {
        assertTrue(showElevatedAccessNotice(caps(AccessLevel.NORMAL), isDismissed = false))
    }

    @Test
    fun `the access notice does not fire for a device already running elevated`() {
        assertFalse(showElevatedAccessNotice(caps(AccessLevel.SHIZUKU), isDismissed = false))
        assertFalse(showElevatedAccessNotice(caps(AccessLevel.ROOT), isDismissed = false))
    }

    @Test
    fun `a dismissed notice stays dismissed`() {
        assertFalse(showElevatedAccessNotice(caps(AccessLevel.NORMAL), isDismissed = true))
    }

    // ----------------------------------------------------------- unlockedByLevels

    @Test
    fun `unlockedByLevels reads each restriction's own claim and drops the un-unlockable`() {
        val levels = unlockedByLevels(
            Observed.of(1, com.processlens.core.common.DataSource.PROC_FS),
            Observed.Restricted(RestrictionReason.PLATFORM_RESTRICTED, AccessLevel.SHIZUKU),
            Observed.Restricted(RestrictionReason.NOT_PRESENT_ON_DEVICE, null),
            Observed.Restricted(RestrictionReason.PERMISSION_REQUIRED, AccessLevel.NORMAL),
            Observed.Restricted(RestrictionReason.REQUIRES_ELEVATED_ACCESS, AccessLevel.ROOT),
        )

        assertEquals(listOf(AccessLevel.SHIZUKU, AccessLevel.ROOT), levels)
    }

    @Test
    fun `unlockedByLevels over the matrix counts only improvable rows that name a level`() {
        val caps = caps(
            AccessLevel.NORMAL,
            statuses = mapOf(
                Capability.CPU_OVERALL to CapabilityStatus(
                    Capability.CPU_OVERALL, Availability.FULL, "ok",
                ),
                Capability.BATTERY_PER_APP to CapabilityStatus(
                    Capability.BATTERY_PER_APP, Availability.UNAVAILABLE, "needs a shell",
                    unlockedBy = AccessLevel.SHIZUKU,
                ),
                Capability.MEMORY_PER_PROCESS to CapabilityStatus(
                    Capability.MEMORY_PER_PROCESS, Availability.LIMITED, "partial",
                    unlockedBy = AccessLevel.SHIZUKU,
                ),
                Capability.CPU_TEMPERATURE to CapabilityStatus(
                    Capability.CPU_TEMPERATURE, Availability.UNAVAILABLE,
                    "no thermal zone", unlockedBy = null,
                ),
            ),
        )

        assertEquals(listOf(AccessLevel.SHIZUKU), unlockedByLevels(caps))
        assertEquals(
            "LIMITED and UNAVAILABLE both count, the un-unlockable row does not",
            2,
            countUnlockable(caps),
        )
    }

    @Test
    fun `nothing is unlockable when every row is full`() {
        assertEquals(0, countUnlockable(caps(AccessLevel.ROOT)))
        assertTrue(unlockedByLevels(caps(AccessLevel.ROOT)).isEmpty())
    }

    @Test
    fun `a value carries no unlock suggestion`() {
        assertNull(
            unlockedByLevels(Observed.of(5, com.processlens.core.common.DataSource.PROC_FS))
                .firstOrNull(),
        )
    }
}
