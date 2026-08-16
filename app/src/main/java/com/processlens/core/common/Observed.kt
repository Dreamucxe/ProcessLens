package com.processlens.core.common

/**
 * The single most important type in ProcessLens.
 *
 * Android restricts most process and system detail from ordinary apps, and the
 * amount it restricts varies by API level, by OEM, by permission state and by
 * whether an elevated shell is available. Section 42 of the specification is
 * absolute: the app must never fabricate a value it could not actually read.
 *
 * Every observation therefore travels wrapped in this type. There is no way to
 * obtain a number from the system layer without also obtaining the story of
 * where it came from, or the reason it is missing. A screen cannot accidentally
 * render a plausible-looking zero, because [Restricted] and [Failed] carry no
 * value to render.
 */
sealed interface Observed<out T> {

    /** A real reading. [source] and [precision] are surfaced in the UI. */
    data class Value<out T>(
        val value: T,
        val source: DataSource,
        val precision: Precision = Precision.EXACT,
    ) : Observed<T>

    /**
     * Android (or the current permission/access state) does not expose this.
     * This is not an error: it is the expected, documented behaviour, and the UI
     * explains it rather than retrying.
     */
    data class Restricted(
        val reason: RestrictionReason,
        /** The lowest access level that *would* expose this, or null if nothing does. */
        val unlockedBy: AccessLevel? = null,
        val detail: String = "",
    ) : Observed<Nothing>

    /**
     * A read that should have worked but did not — an I/O failure, an OEM kernel
     * that omits a file, a shell that returned unparseable output. Distinct from
     * [Restricted] because it is a genuine fault worth showing in the technical
     * details expander (Section 48).
     */
    data class Failed(
        val detail: String,
        val cause: String? = null,
    ) : Observed<Nothing>

    companion object {
        fun <T> of(value: T, source: DataSource, precision: Precision = Precision.EXACT): Observed<T> =
            Value(value, source, precision)

        /** Restriction imposed by the platform on this API level. */
        fun platform(detail: String, unlockedBy: AccessLevel? = null): Observed<Nothing> =
            Restricted(RestrictionReason.PLATFORM_RESTRICTED, unlockedBy, detail)

        /** A runtime or special permission the user has not granted. */
        fun needsPermission(detail: String): Observed<Nothing> =
            Restricted(RestrictionReason.PERMISSION_REQUIRED, null, detail)

        /** The device/kernel simply does not have this (e.g. no current sensor). */
        fun notPresent(detail: String): Observed<Nothing> =
            Restricted(RestrictionReason.NOT_PRESENT_ON_DEVICE, null, detail)

        /**
         * The user switched this sampling off (Sections 40, 43).
         *
         * A readable value that was deliberately not read. [detail] names the
         * setting responsible, so the UI can point at the switch rather than
         * implying Android is at fault.
         */
        fun samplingDisabled(detail: String): Observed<Nothing> =
            Restricted(RestrictionReason.SAMPLING_DISABLED, null, detail)

        inline fun <T> catching(
            source: DataSource,
            precision: Precision = Precision.EXACT,
            block: () -> T,
        ): Observed<T> = try {
            val v = block()
            if (v == null) Failed("Read returned no data") else Value(v, source, precision)
        } catch (se: SecurityException) {
            Restricted(
                RestrictionReason.PLATFORM_RESTRICTED,
                AccessLevel.SHIZUKU,
                se.message ?: "SecurityException",
            )
        } catch (t: Throwable) {
            Failed(t.message ?: t::class.java.simpleName, t::class.java.name)
        }
    }
}

/** The value if this is a real reading, otherwise null. Never invents a default. */
val <T> Observed<T>.valueOrNull: T? get() = (this as? Observed.Value)?.value

val Observed<*>.isAvailable: Boolean get() = this is Observed.Value

inline fun <T, R> Observed<T>.map(transform: (T) -> R): Observed<R> = when (this) {
    is Observed.Value -> Observed.Value(transform(value), source, precision)
    is Observed.Restricted -> this
    is Observed.Failed -> this
}

/**
 * Human-readable explanation for the "Not available" state (Section 42) — the
 * exact wording the UI shows, kept here so it is identical everywhere.
 */
fun Observed<*>.unavailabilityText(): String? = when (this) {
    is Observed.Value -> null
    is Observed.Failed -> "Could not be read on this device."
    is Observed.Restricted -> when (reason) {
        RestrictionReason.PLATFORM_RESTRICTED ->
            "Android does not expose this information to normal applications on this device."
        RestrictionReason.PERMISSION_REQUIRED ->
            "A permission is required before this can be read."
        RestrictionReason.NOT_PRESENT_ON_DEVICE ->
            "This device does not report this value."
        RestrictionReason.REQUIRES_ELEVATED_ACCESS ->
            "This requires elevated access."
        RestrictionReason.NOT_SUPPORTED_ON_API_LEVEL ->
            "This Android version does not provide this information."
        RestrictionReason.SAMPLING_DISABLED ->
            "Sampling for this is switched off in ProcessLens' own settings."
    }
}

enum class RestrictionReason {
    /** Deliberately withheld by the platform sandbox (hidepid, SELinux, API gating). */
    PLATFORM_RESTRICTED,
    PERMISSION_REQUIRED,
    /** Hardware or kernel does not report it at all. */
    NOT_PRESENT_ON_DEVICE,
    REQUIRES_ELEVATED_ACCESS,
    NOT_SUPPORTED_ON_API_LEVEL,

    /**
     * ProcessLens could read this but was told not to.
     *
     * Kept separate from every other reason on purpose. The others describe the
     * device; this one describes the user's own choice, and conflating them would
     * blame Android for a limitation the user imposed and can lift in one tap.
     */
    SAMPLING_DISABLED,
}

/**
 * Where a reading physically came from. Shown in the UI so a user can judge how
 * much to trust it, and recorded in exports so a saved investigation stays
 * interpretable later.
 */
enum class DataSource(val label: String, val access: AccessLevel) {
    OWN_PROC("/proc (own process)", AccessLevel.NORMAL),
    PROC_FS("/proc", AccessLevel.NORMAL),
    SYS_FS("/sys", AccessLevel.NORMAL),
    ACTIVITY_MANAGER("ActivityManager", AccessLevel.NORMAL),
    USAGE_STATS("UsageStatsManager", AccessLevel.NORMAL),
    PACKAGE_MANAGER("PackageManager", AccessLevel.NORMAL),
    BATTERY_MANAGER("BatteryManager", AccessLevel.NORMAL),
    NETWORK_STATS("NetworkStatsManager", AccessLevel.NORMAL),
    CONNECTIVITY("ConnectivityManager", AccessLevel.NORMAL),
    TRAFFIC_STATS("TrafficStats", AccessLevel.NORMAL),
    STORAGE_MANAGER("StatFs", AccessLevel.NORMAL),
    RUNTIME("Java Runtime", AccessLevel.NORMAL),
    DEBUG_MEMINFO("Debug.MemoryInfo", AccessLevel.NORMAL),
    SHELL_SHIZUKU("Shizuku shell", AccessLevel.SHIZUKU),
    DUMPSYS_SHIZUKU("dumpsys via Shizuku", AccessLevel.SHIZUKU),
    SHELL_ROOT("root shell", AccessLevel.ROOT),
    DUMPSYS_ROOT("dumpsys via root", AccessLevel.ROOT),
    RECORDED("recorded investigation", AccessLevel.NORMAL),
    ;

    /** True when the figure is a direct platform reading rather than parsed text. */
    val isFirstParty: Boolean
        get() = access == AccessLevel.NORMAL
}

/**
 * How exact a figure is. Section 7 requires that estimates be labelled as such:
 * a CPU percentage derived from two /proc samples is not the same kind of fact
 * as a byte count returned by the platform.
 */
enum class Precision(val label: String) {
    /** Returned directly by the platform; no inference. */
    EXACT("Exact"),

    /** Derived from a delta between two samples — accurate but interval-dependent. */
    SAMPLED("Sampled"),

    /** Inferred from indirect signals. Always shown with a qualifier in the UI. */
    ESTIMATED("Estimated"),
}

/**
 * Privilege tier the app is currently operating at (Sections 27, 28). Ordered so
 * that `>=` comparisons work: ROOT satisfies a SHIZUKU requirement.
 */
enum class AccessLevel(val label: String, val rank: Int) {
    NORMAL("Normal", 0),
    SHIZUKU("Shizuku", 1),
    ROOT("Root", 2),
    ;

    infix fun satisfies(required: AccessLevel): Boolean = rank >= required.rank
}
