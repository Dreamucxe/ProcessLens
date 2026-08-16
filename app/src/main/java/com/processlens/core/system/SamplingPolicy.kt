package com.processlens.core.system

import com.processlens.core.common.Observed
import com.processlens.domain.model.UserSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which sampling ProcessLens is allowed to perform (Sections 40, 43).
 *
 * Section 43 says ProcessLens must not become the problem it investigates, and the
 * per-domain switches in Settings are how a user enforces that: a person watching a
 * memory leak has no reason to pay for a thermal-zone scan and a `/proc/meminfo`
 * parse every two seconds.
 *
 * The switches are honoured *here*, at the point of collection, rather than in the
 * UI. That distinction is the whole point. Hiding a value the app went on reading
 * would save nothing and would make the setting a lie; skipping the read genuinely
 * removes the file access, the Binder call and the delta arithmetic from the polling
 * loop, and the field the read would have filled comes back as
 * [Observed.samplingDisabled] naming the switch responsible.
 *
 * What is *not* gated: the single cheap reads that describe a domain rather than
 * sample it — total RAM, the battery level from the sticky broadcast, the active
 * transport. Those are one cached call each, they are what makes the screen
 * intelligible at all, and pretending they were unavailable would be a fabrication
 * in the opposite direction.
 *
 * Mutable singleton state rather than a flow: the observers read it inside a
 * suspend function on a background dispatcher, once per sample, and a
 * [Volatile] read is the cheapest way to answer that question honestly.
 */
@Singleton
class SamplingPolicy @Inject constructor() {

    @Volatile
    var cpuEnabled: Boolean = true
        private set

    @Volatile
    var memoryDetailEnabled: Boolean = true
        private set

    @Volatile
    var batteryDetailEnabled: Boolean = true
        private set

    @Volatile
    var networkCountersEnabled: Boolean = true
        private set

    fun apply(settings: UserSettings) {
        cpuEnabled = settings.cpuPollingEnabled
        memoryDetailEnabled = settings.memoryPollingEnabled
        batteryDetailEnabled = settings.batteryPollingEnabled
        networkCountersEnabled = settings.networkPollingEnabled
    }

    companion object {
        const val CPU_OFF =
            "CPU sampling is switched off in Settings → Monitoring. ProcessLens is not " +
                "reading /proc/stat, the per-core counters, the frequency nodes or the " +
                "thermal zones while it is off."

        const val MEMORY_OFF =
            "Memory detail sampling is switched off in Settings → Monitoring. Total and " +
                "available memory still come from ActivityManager, but /proc/meminfo is " +
                "not being read, so the cached, buffer, free and swap breakdown is absent."

        const val BATTERY_OFF =
            "Battery detail sampling is switched off in Settings → Monitoring. The level " +
                "and charging state still come from the system's own broadcast, but the " +
                "temperature, voltage, current and charge counters are not being queried."

        const val NETWORK_OFF =
            "Network counter sampling is switched off in Settings → Monitoring. The " +
                "connection type is still read, but the byte counters and throughput are " +
                "not — and throughput cannot be recovered retrospectively, because it is " +
                "a rate between two samples that were never taken."
    }
}
