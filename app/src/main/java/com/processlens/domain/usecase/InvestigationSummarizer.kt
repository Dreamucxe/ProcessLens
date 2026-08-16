package com.processlens.domain.usecase

import com.processlens.domain.model.EventGroup
import com.processlens.domain.model.EventType
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.InvestigationSummary
import com.processlens.domain.model.Observed3State
import com.processlens.domain.model.ProcessSnapshot
import com.processlens.domain.model.RankedProcess
import com.processlens.domain.model.SnapshotEntry
import kotlin.math.abs

/**
 * Derives an investigation's summary from the samples it captured (Sections 14, 15, 24, 25).
 *
 * Pure: snapshots and events in, an [InvestigationSummary] out. No storage, no clock, no
 * Android. That is deliberate — this object contains the app's most consequential
 * reasoning, the step where raw samples become sentences a person will act on, and it is
 * the one place where overstating the evidence would send someone to fix the wrong thing.
 * Keeping it free of dependencies means every claim it makes is verifiable off-device.
 *
 * The summary is *recomputed on demand* rather than written once when a recording ends.
 * A summary stored as prose cannot be audited; one derived from the stored samples can
 * always be traced back to them, and if a derivation is later found to be wrong, existing
 * recordings gain the corrected reading instead of keeping a stale claim.
 *
 * Three rules govern every function here:
 *
 *  1. **Absence is reported, never rounded to zero.** A metric that was unreadable is
 *     absent from the snapshots, and [limitations] says so in words. Section 42.
 *  2. **Co-occurrence is not causation.** Everything [correlations] emits is phrased as a
 *     *potential* correlation with an explicit disclaimer, because sampled snapshots
 *     establish that two things happened close together and nothing more. Section 15.
 *  3. **A figure that the data cannot support is omitted.** [batteryDrain] returns null
 *     rather than subtracting endpoints across a charge cycle. Section 17.
 */
object InvestigationSummarizer {

    /** How close two events must be in time to be reported as coinciding. */
    const val CORRELATION_WINDOW_MILLIS = 10_000L

    fun summarize(
        investigation: Investigation,
        snapshots: List<ProcessSnapshot>,
        events: List<InvestigationEvent>,
    ): InvestigationSummary {
        if (snapshots.isEmpty()) {
            // A recording that captured nothing gets a summary saying exactly that,
            // rather than a summary of zeroes. Zeroes would read as "nothing happened";
            // the truth is "nothing was observed", which is a different finding.
            return InvestigationSummary(
                investigation = investigation,
                durationMillis = investigation.durationMillis,
                highestCpuProcess = null,
                largestMemoryIncrease = null,
                mostFrequentlyRestarted = null,
                batteryDrainPercent = null,
                batteryTemperatureRiseDeciCelsius = null,
                networkActivityDetected = false,
                wakeLockActivityDetected = Observed3State.NOT_OBSERVABLE,
                eventCounts = events.groupingBy { it.type.group }.eachCount(),
                correlations = emptyList(),
                limitations = listOf(
                    "No samples were captured, so nothing can be concluded from this recording.",
                ),
            )
        }

        val first = snapshots.first()
        val last = snapshots.last()

        return InvestigationSummary(
            investigation = investigation,
            durationMillis = last.timestamp - first.timestamp,
            highestCpuProcess = highestCpu(snapshots),
            largestMemoryIncrease = largestMemoryIncrease(snapshots),
            mostFrequentlyRestarted = mostRestarted(events),
            batteryDrainPercent = batteryDrain(snapshots),
            batteryTemperatureRiseDeciCelsius = temperatureRise(snapshots),
            networkActivityDetected = networkActivity(snapshots),
            wakeLockActivityDetected = wakeLockState(events),
            eventCounts = events.groupingBy { it.type.group }.eachCount(),
            correlations = correlations(snapshots, events),
            limitations = limitations(snapshots, events),
        )
    }

    /**
     * Peak CPU across the recording, attributed to the process that held it.
     *
     * Peak rather than average because an investigation is usually looking for a
     * culprit that spiked, and averaging a 90% spike over five idle minutes hides it.
     * The label says "peak" so the figure is not mistaken for a sustained rate.
     */
    internal fun highestCpu(snapshots: List<ProcessSnapshot>): RankedProcess? {
        var best: SnapshotEntry? = null
        for (snapshot in snapshots) {
            for (entry in snapshot.entries) {
                val cpu = entry.cpuPercent ?: continue
                if (best == null || cpu > (best.cpuPercent ?: Float.NEGATIVE_INFINITY)) best = entry
            }
        }
        val peak = best?.cpuPercent ?: return null
        return RankedProcess(
            processName = best.processName,
            packageName = best.packageName,
            label = "Highest observed CPU",
            value = peak.toDouble(),
            unit = "%",
        )
    }

    /**
     * The process whose memory grew most between its first and last observation.
     *
     * Computed per process name, not per PID: an app restarted mid-recording gets a
     * new PID, and treating those as unrelated would hide growth across a restart.
     */
    internal fun largestMemoryIncrease(snapshots: List<ProcessSnapshot>): RankedProcess? {
        val firstSeen = HashMap<String, Long>()
        val lastSeen = HashMap<String, Long>()
        val packageOf = HashMap<String, String?>()

        for (snapshot in snapshots) {
            for (entry in snapshot.entries) {
                val memory = entry.memoryBytes ?: continue
                firstSeen.putIfAbsent(entry.processName, memory)
                lastSeen[entry.processName] = memory
                packageOf[entry.processName] = entry.packageName
            }
        }

        val best = firstSeen.keys
            .mapNotNull { name ->
                val from = firstSeen[name] ?: return@mapNotNull null
                val to = lastSeen[name] ?: return@mapNotNull null
                name to (to - from)
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?: return null

        return RankedProcess(
            processName = best.first,
            packageName = packageOf[best.first],
            label = "Largest memory increase",
            value = best.second.toDouble(),
            unit = "bytes",
        )
    }

    /** Restart counts come from recorded events, never inferred after the fact. */
    internal fun mostRestarted(events: List<InvestigationEvent>): RankedProcess? {
        val restarts = events
            .filter { it.type == EventType.PROCESS_RESTARTED || it.type == EventType.PROCESS_STARTED }
            .mapNotNull { it.processName }
            .groupingBy { it }
            .eachCount()
            .filter { it.value > 1 }
            .maxByOrNull { it.value }
            ?: return null

        val packageName = events.firstOrNull { it.processName == restarts.key }?.packageName
        return RankedProcess(
            processName = restarts.key,
            packageName = packageName,
            label = "Most frequently restarted",
            value = restarts.value.toDouble(),
            unit = "starts",
        )
    }

    /**
     * Battery drain, only when the device was discharging throughout.
     *
     * Returns null if charging occurred at any point: a level that went 40 → 60 → 45
     * did not "drain 5%", and reporting the endpoint difference as drain would be a
     * fabricated figure of exactly the kind Section 17 prohibits.
     */
    internal fun batteryDrain(snapshots: List<ProcessSnapshot>): Int? {
        if (snapshots.isEmpty()) return null
        if (snapshots.any { it.isCharging }) return null
        val from = snapshots.first().batteryLevel
        val to = snapshots.last().batteryLevel
        val drop = from - to
        return if (drop > 0) drop else null
    }

    internal fun temperatureRise(snapshots: List<ProcessSnapshot>): Int? {
        val temps = snapshots.mapNotNull { it.batteryTemperatureDeciCelsius }
        if (temps.size < 2) return null
        val rise = temps.max() - temps.first()
        return if (rise > 0) rise else null
    }

    /**
     * Whether any traffic was observed. False here means "counters did not move",
     * which is only meaningful because the counters themselves were readable —
     * if they were not, they are absent from every snapshot and this returns false
     * while [limitations] records that network observation was unavailable.
     */
    internal fun networkActivity(snapshots: List<ProcessSnapshot>): Boolean {
        val rx = snapshots.mapNotNull { it.networkRxBytes }
        val tx = snapshots.mapNotNull { it.networkTxBytes }
        val rxMoved = rx.size >= 2 && rx.max() > rx.min()
        val txMoved = tx.size >= 2 && tx.max() > tx.min()
        return rxMoved || txMoved
    }

    /**
     * Three-state, because "no wake locks" and "we cannot see wake locks" are
     * different findings and conflating them would misinform an investigation into
     * battery drain.
     */
    internal fun wakeLockState(events: List<InvestigationEvent>): Observed3State {
        val wakeLockEvents = events.filter { it.type.group == EventGroup.WAKELOCK }
        if (wakeLockEvents.isNotEmpty()) return Observed3State.DETECTED

        val declaredUnobservable = events.any {
            it.type == EventType.OBSERVATION_LIMITED &&
                it.detail.contains("wake lock", ignoreCase = true)
        }
        return if (declaredUnobservable) Observed3State.NOT_OBSERVABLE else Observed3State.NOT_DETECTED
    }

    /**
     * Temporal co-occurrences worth a human's attention (Section 14).
     *
     * Every string is phrased as a *potential correlation*. Section 15 is explicit:
     * sampled snapshots establish that two things happened close together, and
     * nothing more. The wording never claims one caused the other, because the data
     * cannot support that claim — and a diagnostic tool that overstates its evidence
     * sends people to fix the wrong thing.
     */
    internal fun correlations(
        snapshots: List<ProcessSnapshot>,
        events: List<InvestigationEvent>,
    ): List<String> {
        val out = ArrayList<String>()

        // CPU spikes near battery temperature rises.
        val cpuSpikes = events.filter { it.type.group == EventGroup.CPU }
        val tempRises = events.filter { it.type == EventType.BATTERY_TEMPERATURE_RISE }
        for (spike in cpuSpikes) {
            val near = tempRises.firstOrNull {
                abs(it.timestamp - spike.timestamp) <= CORRELATION_WINDOW_MILLIS
            }
            if (near != null) {
                out += "Potential correlation: ${spike.title} and a battery temperature rise " +
                    "occurred within ${CORRELATION_WINDOW_MILLIS / 1000} seconds of each other. " +
                    "This recording shows they coincided; it does not establish that one caused the other."
                break
            }
        }

        // Memory growth alongside a discharging battery.
        val memoryEvents = events.filter { it.type == EventType.MEMORY_INCREASE }
        if (memoryEvents.isNotEmpty() && batteryDrain(snapshots) != null) {
            out += "Potential correlation: memory growth was recorded during a period of " +
                "battery discharge. Both were observed in the same window; no causal link is implied."
        }

        // Network activity while the screen was off — worth a look, not a verdict.
        val screenOffSamples = snapshots.filter { !it.isScreenOn }
        if (screenOffSamples.size >= 2) {
            val rx = screenOffSamples.mapNotNull { it.networkRxBytes }
            val tx = screenOffSamples.mapNotNull { it.networkTxBytes }
            val moved = (rx.size >= 2 && rx.max() > rx.min()) || (tx.size >= 2 && tx.max() > tx.min())
            if (moved) {
                out += "Potential correlation: network traffic was recorded while the screen was off. " +
                    "Android does not attribute device-wide traffic counters to a specific process, " +
                    "so this recording cannot identify which application was responsible."
            }
        }

        // Repeated restarts of one process.
        mostRestarted(events)?.let { restarted ->
            out += "Repeated activity: ${restarted.processName} was observed starting " +
                "${restarted.value.toInt()} times during this recording."
        }

        return out
    }

    /**
     * What this recording could *not* see. Present in every summary so a reader
     * never mistakes an absence of findings for an absence of activity.
     */
    internal fun limitations(
        snapshots: List<ProcessSnapshot>,
        events: List<InvestigationEvent>,
    ): List<String> {
        val out = ArrayList<String>()

        if (snapshots.none { it.cpuPercent != null }) {
            out += "System-wide CPU usage was not readable on this device, so no CPU figures were recorded."
        }
        if (snapshots.none { it.networkRxBytes != null }) {
            out += "Network byte counters were not readable, so network activity could not be observed."
        }
        if (snapshots.none { it.batteryTemperatureDeciCelsius != null }) {
            out += "Battery temperature was not reported by this device."
        }
        if (wakeLockState(events) != Observed3State.DETECTED) {
            out += "Wake locks require Shizuku or root access to observe. " +
                "If none were recorded, this recording cannot rule them out."
        }

        val entriesWithCpu = snapshots.sumOf { s -> s.entries.count { it.cpuPercent != null } }
        if (entriesWithCpu == 0 && snapshots.isNotEmpty()) {
            out += "Per-process CPU usage was not readable, so activity could not be attributed " +
                "to individual processes."
        }

        val peakProcesses = snapshots.maxOfOrNull { it.processCount } ?: 0
        if (peakProcesses in 1..2) {
            out += "Android limited process visibility to this application's own process " +
                "during this recording, so other applications' activity was not observable."
        }

        events.filter { it.type == EventType.OBSERVATION_LIMITED }
            .map { it.detail }
            .distinct()
            .forEach { out += it }

        return out.distinct()
    }
}
