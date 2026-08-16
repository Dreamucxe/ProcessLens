package com.processlens.domain.usecase

import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.EventType
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.ProcessSnapshot
import com.processlens.domain.model.SnapshotEntry
import com.processlens.domain.model.UserSettings
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Derives timeline events by comparing consecutive snapshots (Section 15).
 *
 * Deliberately pure: no Android types, no I/O, no clock. Every event it produces is
 * a function of two real samples and the user's thresholds, which means the whole
 * detection rulebook is unit-testable on the JVM and — more importantly — that an
 * event can only exist if two actual measurements justify it.
 *
 * The detector is stateful across calls only in the sense that the caller passes the
 * previous snapshot back in; it holds no hidden history that could drift.
 */
class SpikeDetector @Inject constructor() {

    /**
     * Compares [current] against [previous] and returns the events that follow.
     *
     * Returns an empty list on the first sample of a recording: a spike is a change,
     * and there is nothing to have changed from. Reporting the first sample's CPU as
     * a "spike" would manufacture an event out of a single reading.
     */
    fun detect(
        investigationId: Long,
        previous: ProcessSnapshot?,
        current: ProcessSnapshot,
        settings: UserSettings,
        targetPackage: String? = null,
    ): List<InvestigationEvent> {
        if (previous == null) return emptyList()
        if (!settings.automaticEventDetection) return emptyList()

        val events = ArrayList<InvestigationEvent>()
        val intervalMillis = (current.timestamp - previous.timestamp).coerceAtLeast(1L)
        val interval = "sampled ${intervalMillis / 1000.0} s apart"

        detectSystemCpu(investigationId, previous, current, settings, interval, events)
        detectProcessCpu(investigationId, previous, current, settings, interval, targetPackage, events)
        detectMemory(investigationId, previous, current, settings, interval, events)
        detectProcessLifecycle(investigationId, previous, current, interval, targetPackage, events)
        detectBattery(investigationId, previous, current, settings, interval, events)
        detectNetwork(investigationId, previous, current, intervalMillis, interval, events)
        detectScreen(investigationId, previous, current, interval, events)

        return events
    }

    // -------------------------------------------------------------------- CPU

    private fun detectSystemCpu(
        id: Long,
        previous: ProcessSnapshot,
        current: ProcessSnapshot,
        settings: UserSettings,
        interval: String,
        out: MutableList<InvestigationEvent>,
    ) {
        val now = current.cpuPercent ?: return
        val before = previous.cpuPercent ?: return

        val critical = settings.cpuCriticalThreshold.toFloat()
        val warning = settings.cpuWarningThreshold.toFloat()

        // Crossing the threshold is the event, not merely being above it — otherwise
        // a sustained 90% load would emit an identical event on every single sample
        // and bury the moment it actually started.
        when {
            now >= critical && before < critical -> out += event(
                id, current.timestamp, EventType.CPU_SPIKE, EventSeverity.CRITICAL,
                title = "System CPU reached ${now.roundToInt()}%",
                detail = "System-wide CPU usage rose from ${before.roundToInt()}% to " +
                    "${now.roundToInt()}%, crossing the ${critical.roundToInt()}% critical threshold.",
                value = now.toDouble(), previousValue = before.toDouble(),
                evidence = "Two consecutive system CPU samples, $interval",
            )
            now >= warning && before < warning -> out += event(
                id, current.timestamp, EventType.CPU_SPIKE, EventSeverity.WARNING,
                title = "System CPU reached ${now.roundToInt()}%",
                detail = "System-wide CPU usage rose from ${before.roundToInt()}% to " +
                    "${now.roundToInt()}%, crossing the ${warning.roundToInt()}% warning threshold.",
                value = now.toDouble(), previousValue = before.toDouble(),
                evidence = "Two consecutive system CPU samples, $interval",
            )
            before >= warning && now < warning -> out += event(
                id, current.timestamp, EventType.CPU_DROPPED, EventSeverity.INFO,
                title = "System CPU fell to ${now.roundToInt()}%",
                detail = "System-wide CPU usage fell from ${before.roundToInt()}% to ${now.roundToInt()}%.",
                value = now.toDouble(), previousValue = before.toDouble(),
                evidence = "Two consecutive system CPU samples, $interval",
            )
        }
    }

    private fun detectProcessCpu(
        id: Long,
        previous: ProcessSnapshot,
        current: ProcessSnapshot,
        settings: UserSettings,
        interval: String,
        targetPackage: String?,
        out: MutableList<InvestigationEvent>,
    ) {
        val before = previous.entries.associateBy { it.processName }

        for (entry in current.entries) {
            if (!entry.matches(targetPackage)) continue
            val now = entry.cpuPercent ?: continue
            val was = before[entry.processName]?.cpuPercent ?: continue

            val threshold = settings.cpuWarningThreshold.toFloat()
            if (now >= threshold && was < threshold) {
                out += event(
                    id, current.timestamp, EventType.CPU_SPIKE,
                    severity = if (now >= settings.cpuCriticalThreshold) {
                        EventSeverity.CRITICAL
                    } else {
                        EventSeverity.WARNING
                    },
                    title = "${entry.displayName} CPU reached ${now.roundToInt()}%",
                    detail = "${entry.processName} rose from ${was.roundToInt()}% to " +
                        "${now.roundToInt()}% of one CPU core between samples.",
                    value = now.toDouble(), previousValue = was.toDouble(),
                    evidence = "Per-process CPU time delta from /proc, $interval",
                    processName = entry.processName, packageName = entry.packageName,
                )
            }
        }
    }

    // ----------------------------------------------------------------- memory

    private fun detectMemory(
        id: Long,
        previous: ProcessSnapshot,
        current: ProcessSnapshot,
        settings: UserSettings,
        interval: String,
        out: MutableList<InvestigationEvent>,
    ) {
        val thresholdBytes = settings.memoryIncreaseWarningMb.toLong() * 1024L * 1024L

        // Per-process growth, which is the actionable signal.
        val before = previous.entries.associateBy { it.processName }
        for (entry in current.entries) {
            val now = entry.memoryBytes ?: continue
            val was = before[entry.processName]?.memoryBytes ?: continue
            val delta = now - was
            if (delta >= thresholdBytes) {
                out += event(
                    id, current.timestamp, EventType.MEMORY_INCREASE, EventSeverity.WARNING,
                    title = "${entry.displayName} grew by ${formatMb(delta)}",
                    detail = "${entry.processName} increased from ${formatMb(was)} to " +
                        "${formatMb(now)} between samples.",
                    value = now.toDouble(), previousValue = was.toDouble(),
                    evidence = "Resident memory from /proc, $interval",
                    processName = entry.processName, packageName = entry.packageName,
                )
            } else if (delta <= -thresholdBytes) {
                out += event(
                    id, current.timestamp, EventType.MEMORY_DECREASE, EventSeverity.INFO,
                    title = "${entry.displayName} released ${formatMb(abs(delta))}",
                    detail = "${entry.processName} fell from ${formatMb(was)} to ${formatMb(now)}.",
                    value = now.toDouble(), previousValue = was.toDouble(),
                    evidence = "Resident memory from /proc, $interval",
                    processName = entry.processName, packageName = entry.packageName,
                )
            }
        }

        // System-wide pressure: available memory crossing ActivityManager's own low
        // threshold matters more than any absolute figure, because that is the point
        // at which the platform starts killing processes.
        val availableNow = current.memoryAvailableBytes
        val availableBefore = previous.memoryAvailableBytes
        val systemDelta = availableBefore - availableNow
        if (systemDelta >= thresholdBytes) {
            out += event(
                id, current.timestamp, EventType.MEMORY_PRESSURE, EventSeverity.WARNING,
                title = "Available memory fell by ${formatMb(systemDelta)}",
                detail = "System available memory fell from ${formatMb(availableBefore)} to " +
                    "${formatMb(availableNow)}.",
                value = availableNow.toDouble(), previousValue = availableBefore.toDouble(),
                evidence = "ActivityManager memory info, $interval",
            )
        }
    }

    // -------------------------------------------------------------- lifecycle

    private fun detectProcessLifecycle(
        id: Long,
        previous: ProcessSnapshot,
        current: ProcessSnapshot,
        interval: String,
        targetPackage: String?,
        out: MutableList<InvestigationEvent>,
    ) {
        // Only meaningful when both samples could see more than our own process. A
        // list that shrank because visibility was lost is not a process stopping.
        if (previous.entries.size <= 1 || current.entries.size <= 1) return

        val beforeByName = previous.entries.associateBy { it.processName }
        val afterByName = current.entries.associateBy { it.processName }

        for (entry in current.entries) {
            if (!entry.matches(targetPackage)) continue
            val was = beforeByName[entry.processName]
            if (was == null) {
                out += event(
                    id, current.timestamp, EventType.PROCESS_STARTED, EventSeverity.NORMAL,
                    title = "${entry.displayName} started",
                    detail = "${entry.processName} appeared in the process list" +
                        (entry.pid?.let { " with PID $it" } ?: "") + ".",
                    value = null, previousValue = null,
                    evidence = "Process appeared between two samples, $interval",
                    processName = entry.processName, packageName = entry.packageName,
                )
            } else if (was.pid != null && entry.pid != null && was.pid != entry.pid) {
                // Same name, different PID: the process was replaced. This is a
                // restart, and stating it requires both PIDs to have been readable.
                out += event(
                    id, current.timestamp, EventType.PROCESS_RESTARTED, EventSeverity.WARNING,
                    title = "${entry.displayName} restarted",
                    detail = "${entry.processName} changed from PID ${was.pid} to PID ${entry.pid}, " +
                        "which means the previous process ended and a new one started.",
                    value = entry.pid.toDouble(), previousValue = was.pid.toDouble(),
                    evidence = "Process identifier changed between samples, $interval",
                    processName = entry.processName, packageName = entry.packageName,
                )
            }
        }

        for (entry in previous.entries) {
            if (!entry.matches(targetPackage)) continue
            if (!afterByName.containsKey(entry.processName)) {
                out += event(
                    id, current.timestamp, EventType.PROCESS_STOPPED, EventSeverity.NORMAL,
                    title = "${entry.displayName} stopped",
                    detail = "${entry.processName} was no longer present in the process list.",
                    value = null, previousValue = null,
                    evidence = "Process absent from the following sample, $interval",
                    processName = entry.processName, packageName = entry.packageName,
                )
            }
        }
    }

    // ---------------------------------------------------------------- battery

    private fun detectBattery(
        id: Long,
        previous: ProcessSnapshot,
        current: ProcessSnapshot,
        settings: UserSettings,
        interval: String,
        out: MutableList<InvestigationEvent>,
    ) {
        if (current.batteryLevel != previous.batteryLevel) {
            val delta = current.batteryLevel - previous.batteryLevel
            out += event(
                id, current.timestamp, EventType.BATTERY_LEVEL_CHANGE, EventSeverity.INFO,
                title = "Battery ${if (delta < 0) "fell" else "rose"} to ${current.batteryLevel}%",
                detail = "Battery level moved from ${previous.batteryLevel}% to ${current.batteryLevel}%.",
                value = current.batteryLevel.toDouble(),
                previousValue = previous.batteryLevel.toDouble(),
                evidence = "Battery level reported by the platform, $interval",
            )
        }

        if (current.isCharging != previous.isCharging) {
            out += event(
                id, current.timestamp, EventType.BATTERY_CHARGING_CHANGE, EventSeverity.INFO,
                title = if (current.isCharging) "Charging started" else "Charging stopped",
                detail = "The device " +
                    (if (current.isCharging) "began charging." else "stopped charging.") +
                    " Battery figures before and after this point are not comparable.",
                value = null, previousValue = null,
                evidence = "Charging state reported by the platform, $interval",
            )
        }

        val tempNow = current.batteryTemperatureDeciCelsius
        val tempBefore = previous.batteryTemperatureDeciCelsius
        if (tempNow != null && tempBefore != null) {
            val threshold = settings.batteryTemperatureWarningDeciCelsius
            if (tempNow >= threshold && tempBefore < threshold) {
                out += event(
                    id, current.timestamp, EventType.BATTERY_TEMPERATURE_RISE, EventSeverity.WARNING,
                    title = "Battery temperature reached ${formatTemp(tempNow)}",
                    detail = "Battery temperature rose from ${formatTemp(tempBefore)} to " +
                        "${formatTemp(tempNow)}, crossing the ${formatTemp(threshold)} threshold.",
                    value = tempNow.toDouble(), previousValue = tempBefore.toDouble(),
                    evidence = "Battery temperature reported by the platform, $interval",
                )
            }
        }
    }

    // ---------------------------------------------------------------- network

    private fun detectNetwork(
        id: Long,
        previous: ProcessSnapshot,
        current: ProcessSnapshot,
        intervalMillis: Long,
        interval: String,
        out: MutableList<InvestigationEvent>,
    ) {
        val rxNow = current.networkRxBytes
        val rxBefore = previous.networkRxBytes
        val txNow = current.networkTxBytes
        val txBefore = previous.networkTxBytes
        if (rxNow == null || rxBefore == null || txNow == null || txBefore == null) return

        // Counters reset at boot and can go backwards after a counter rollover; a
        // negative delta is discarded rather than reported as huge traffic.
        val rxDelta = rxNow - rxBefore
        val txDelta = txNow - txBefore
        if (rxDelta < 0 || txDelta < 0) return

        val total = rxDelta + txDelta
        if (total < NETWORK_THRESHOLD_BYTES) return

        val perSecond = (total * 1000.0 / intervalMillis).roundToInt()
        out += event(
            id, current.timestamp, EventType.NETWORK_ACTIVITY, EventSeverity.INFO,
            title = "Network transferred ${formatKb(total)}",
            detail = "The device sent ${formatKb(txDelta)} and received ${formatKb(rxDelta)} " +
                "between samples (about ${formatKb(perSecond.toLong())}/s). These are device-wide " +
                "counters — Android does not attribute them to a specific process.",
            value = total.toDouble(), previousValue = null,
            evidence = "TrafficStats device totals, $interval",
        )
    }

    private fun detectScreen(
        id: Long,
        previous: ProcessSnapshot,
        current: ProcessSnapshot,
        interval: String,
        out: MutableList<InvestigationEvent>,
    ) {
        if (current.isScreenOn == previous.isScreenOn) return
        out += event(
            id, current.timestamp, EventType.SCREEN_STATE_CHANGE, EventSeverity.INFO,
            title = if (current.isScreenOn) "Screen turned on" else "Screen turned off",
            detail = "Display state changed. Background activity limits differ between these states.",
            value = null, previousValue = null,
            evidence = "Display state reported by the platform, $interval",
        )
    }

    // ----------------------------------------------------------------- helpers

    private fun event(
        investigationId: Long,
        timestamp: Long,
        type: EventType,
        severity: EventSeverity,
        title: String,
        detail: String,
        value: Double?,
        previousValue: Double?,
        evidence: String,
        processName: String? = null,
        packageName: String? = null,
    ) = InvestigationEvent(
        investigationId = investigationId,
        timestamp = timestamp,
        type = type,
        severity = severity,
        title = title,
        detail = detail,
        packageName = packageName,
        processName = processName,
        value = value,
        previousValue = previousValue,
        evidence = evidence,
    )

    private companion object {
        /** Below this, a delta is indistinguishable from routine keep-alive traffic. */
        const val NETWORK_THRESHOLD_BYTES = 256L * 1024L
    }
}

private fun SnapshotEntry.matches(targetPackage: String?): Boolean {
    if (targetPackage == null) return true
    return packageName == targetPackage ||
        processName == targetPackage ||
        processName.startsWith("$targetPackage:")
}

private val SnapshotEntry.displayName: String
    get() = packageName?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: processName

private fun formatMb(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 100) "${mb.roundToInt()} MB" else String.format("%.1f MB", mb)
}

private fun formatKb(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

private fun formatTemp(deciCelsius: Int): String =
    String.format("%.1f °C", deciCelsius / 10.0)
