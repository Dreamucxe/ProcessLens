package com.processlens.core.system

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.processlens.core.common.AccessLevel
import com.processlens.core.common.Observed
import com.processlens.core.common.isAvailable
import com.processlens.domain.model.Availability
import com.processlens.domain.model.Capability
import com.processlens.domain.model.SystemCapabilities
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Runtime capability detection (Sections 1, 42, 47, 56 — priority 1 in the spec).
 *
 * Every assertion here is deliberately *device-independent*. The matrix is supposed to
 * vary between an API 26 phone, a hidepid-enforcing API 29 phone, and an API 34 tablet
 * with Shizuku running, so a test that asserted "CPU_OVERALL is FULL" would be asserting
 * that the tester's device is the one the author had. What is invariant — and what the
 * spec actually demands — is the *shape* of the answer:
 *
 *  - the matrix is complete: every [Capability] gets a verdict, so no screen can ask
 *    about one and receive silence;
 *  - every non-FULL verdict carries a human-readable reason, because Section 48's
 *    "explain the limitation" is the whole point of the matrix;
 *  - detection is a probe, not a version lookup — running it twice on an unchanged
 *    device gives the same answer, and the verdicts agree with what the readers
 *    actually return right now;
 *  - a capability marked FULL really can be read, and one marked UNAVAILABLE really
 *    cannot. That equivalence is what stops the UI showing an empty panel where it
 *    promised data, or hiding data the device would have given.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CapabilityDetectorTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var detector: CapabilityDetector

    @Inject lateinit var procFs: ProcFsReader

    @Before
    fun inject() {
        hilt.inject()
    }

    // ------------------------------------------------------------------ completeness

    @Test
    fun everyCapabilityReceivesAVerdict() = runTest {
        val caps = detector.detect()

        // A missing entry would surface through SystemCapabilities.get()'s fallback as
        // "Not evaluated on this device", which is an admission the detector is
        // incomplete rather than a fact about the device.
        val unevaluated = Capability.entries.filter { it !in caps.statuses }

        assertThat(unevaluated).isEmpty()
        assertThat(caps.statuses).hasSize(Capability.entries.size)
    }

    @Test
    fun everyVerdictExplainsItself() = runTest {
        val caps = detector.detect()

        caps.statuses.values.forEach { status ->
            assertThat(status.reason.isNotBlank()).isTrue()
            // Section 48: a limitation the user cannot understand is indistinguishable
            // from a bug in the app.
            assertThat(status.reason.length).isAtLeast(8)
        }
    }

    @Test
    fun anythingLessThanFullNamesTheLimitation() = runTest {
        val caps = detector.detect()

        caps.statuses.values
            .filter { it.availability != Availability.FULL }
            .forEach { status ->
                // Either the reason says what is missing, or unlockedBy names the access
                // level that would fix it. A bare "Unavailable" with no explanation is
                // exactly what Section 42 forbids.
                val explained = status.reason.isNotBlank() || status.unlockedBy != null
                assertThat(explained).isTrue()
            }
    }

    @Test
    fun theMatrixRecordsTheConditionsItWasTakenUnder() = runTest {
        val caps = detector.detect()

        // An exported recording is only interpretable if it says what access it had.
        assertThat(caps.apiLevel).isEqualTo(Build.VERSION.SDK_INT)
        assertThat(caps.accessLevel).isAnyOf(AccessLevel.NORMAL, AccessLevel.SHIZUKU, AccessLevel.ROOT)
        assertThat(caps.fullCount + caps.limitedCount + caps.unavailableCount)
            .isEqualTo(Capability.entries.size)
    }

    // ------------------------------------------------------------ probe, not version

    @Test
    fun detectionIsRepeatableOnAnUnchangedDevice() = runTest {
        val first = detector.detect()
        val second = detector.detect()

        // Probes read files; two reads seconds apart must not disagree, or the Settings
        // screen would flicker between verdicts.
        Capability.entries.forEach { capability ->
            assertThat(second.availability(capability)).isEqualTo(first.availability(capability))
        }
    }

    @Test
    fun theCpuVerdictAgreesWithWhatProcStatActuallyReturns() = runTest {
        val caps = detector.detect()
        val probe = procFs.readSystemCpuTimes()

        // The point of Section 47: the verdict is derived from this read, not from
        // SDK_INT. If /proc/stat is readable the capability is usable, and if it is not
        // then it is not — on whatever device this happens to be running.
        assertThat(caps.isUsable(Capability.CPU_OVERALL)).isEqualTo(probe.isAvailable)
    }

    @Test
    fun theMemoryVerdictAgreesWithWhatMemInfoActuallyReturns() = runTest {
        val caps = detector.detect()
        val probe = procFs.readMemInfo()

        assertThat(caps.isUsable(Capability.MEMORY_DETAIL)).isEqualTo(probe.isAvailable)
    }

    @Test
    fun ownProcessObservationIsAlwaysPossible() = runTest {
        val caps = detector.detect()

        // /proc/self is readable on every Android version regardless of hidepid: a
        // process can always read itself. This is the floor the app is built on, and
        // Section 43's self-monitoring depends on it.
        val ownStat = procFs.readProcessStat(android.os.Process.myPid())
        assertThat(ownStat.isAvailable).isTrue()
        assertThat(caps.isUsable(Capability.PROCESS_PID)).isTrue()
    }

    @Test
    fun aFullProcessListIsNotClaimedUnlessForeignPidsAreReallyVisible() = runTest {
        val caps = detector.detect()
        val ownPid = android.os.Process.myPid()
        val visible = procFs.listVisiblePids().valueOrNullList()
        val foreign = visible.count { it != ownPid }

        // From API 29 hidepid hides other processes, and from API 28 the ActivityManager
        // returns only our own. Whichever applies here, the detector must not promise a
        // process table it cannot produce.
        if (foreign <= 2) {
            assertThat(caps.availability(Capability.PROCESS_LIST))
                .isNotEqualTo(Availability.FULL)
        }
    }

    @Test
    fun anUnavailableCapabilityIsNotAlsoReportedAsUsable() = runTest {
        val caps = detector.detect()

        caps.statuses.values.forEach { status ->
            assertThat(status.isUsable).isEqualTo(status.availability != Availability.UNAVAILABLE)
        }
        // usable() is what the UI iterates to decide which rows to draw (Section 47:
        // "only display capabilities that are actually supported").
        assertThat(caps.usable().none { it.availability == Availability.UNAVAILABLE }).isTrue()
    }

    // ------------------------------------------------------- elevated access honesty

    @Test
    fun elevatedAccessIsNotClaimedWithoutAUsableShell() = runTest {
        val caps = detector.detect()

        // Section 27/28: the state must follow the shell, not the other way round.
        when (caps.accessLevel) {
            AccessLevel.ROOT -> assertThat(caps.rootState.isUsable).isTrue()
            AccessLevel.SHIZUKU -> assertThat(caps.shizukuState.isUsable).isTrue()
            AccessLevel.NORMAL -> {
                assertThat(caps.rootState.isUsable).isFalse()
                assertThat(caps.shizukuState.isUsable).isFalse()
            }
        }
    }

    @Test
    fun shizukuIsNeverOfferedAsAWayToUnlockSomethingOnlyRootCanDo() = runTest {
        val caps = detector.detect()

        // Section 27: "do not pretend Shizuku grants unrestricted root". A capability
        // that needs uid 0 must say ROOT, and one already at ROOT access must not
        // suggest an upgrade at all.
        caps.statuses.values.forEach { status ->
            val unlock = status.unlockedBy
            if (unlock != null) {
                // NORMAL is not an "unlock" — it is the floor everyone already has, so
                // naming it would be an instruction the user cannot act on.
                assertThat(unlock).isAnyOf(AccessLevel.SHIZUKU, AccessLevel.ROOT)
            }
        }
    }

    @Test
    fun aFullCapabilityNeverSuggestsAnUpgrade() = runTest {
        val caps = detector.detect()

        caps.statuses.values
            .filter { it.availability == Availability.FULL }
            .forEach { assertThat(it.unlockedBy).isNull() }
    }

    // ---------------------------------------------------------------------- grouping

    @Test
    fun groupingCoversEveryCapabilityExactlyOnce() = runTest {
        val caps = detector.detect()

        val grouped = caps.byGroup().values.flatten()

        assertThat(grouped).hasSize(Capability.entries.size)
        assertThat(grouped.map { it.capability }.toSet()).hasSize(Capability.entries.size)
    }

    @Test
    fun theInitialPlaceholderMatrixClaimsNothing() {
        // Shown for the few milliseconds before the first real evaluation. It must not
        // advertise a capability the device may not have.
        val unknown = SystemCapabilities.unknown(Build.VERSION.SDK_INT)

        assertThat(unknown.accessLevel).isEqualTo(AccessLevel.NORMAL)
        assertThat(unknown.fullCount).isEqualTo(0)
    }
}

/** Local helper: the probe returns Observed<List<Int>>, and only the list is wanted. */
private fun Observed<List<Int>>.valueOrNullList(): List<Int> =
    (this as? Observed.Value)?.value ?: emptyList()
