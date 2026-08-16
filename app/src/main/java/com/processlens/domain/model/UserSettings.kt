package com.processlens.domain.model

/**
 * All user-configurable state (Section 40). Persisted as a single-row Room
 * entity, per the entity list in Section 44, so settings share the same
 * migration discipline as everything else and a schema change can never silently
 * reset a user's preferences.
 */
data class UserSettings(
    // ---- Appearance ----
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentColor: AccentColor = AccentColor.INDIGO,
    val useDynamicColor: Boolean = false,
    val glassEffectEnabled: Boolean = true,
    val animationIntensity: AnimationIntensity = AnimationIntensity.FULL,
    /** User override; the system's own accessibility setting also forces this on. */
    val reducedMotion: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val highContrast: Boolean = false,

    // ---- Monitoring ----
    val refreshRate: RefreshRate = RefreshRate.TWO_SECONDS,
    val cpuPollingEnabled: Boolean = true,
    val memoryPollingEnabled: Boolean = true,
    val batteryPollingEnabled: Boolean = true,
    val networkPollingEnabled: Boolean = true,
    /** Section 43: poll process detail less often than the cheap system gauges. */
    val processListPollMultiplier: Int = 2,

    // ---- Investigation ----
    val defaultDurationMinutes: Int = 5,
    val cpuWarningThreshold: Int = 50,
    val cpuCriticalThreshold: Int = 80,
    val memoryIncreaseWarningMb: Int = 500,
    val batteryTemperatureWarningDeciCelsius: Int = 400,
    val automaticEventDetection: Boolean = true,
    val investigationSampleIntervalMillis: Long = 2_000L,

    // ---- Privacy ----
    val localOnlyMode: Boolean = true,
    val includeSystemAppsInExport: Boolean = true,
    val exportFormat: ExportFormat = ExportFormat.JSON,

    // ---- Advanced ----
    val shizukuEnabled: Boolean = true,
    val rootEnabled: Boolean = false,
    val showOwnResourceUsage: Boolean = true,
    val showSystemProcesses: Boolean = true,

    // ---- First run ----
    val onboardingCompleted: Boolean = false,
) {
    /** Effective motion setting: either the app toggle or reduced intensity. */
    val motionDisabled: Boolean
        get() = reducedMotion || animationIntensity == AnimationIntensity.NONE
}

enum class ThemeMode(val label: String) {
    DARK("Dark"), LIGHT("Light"), SYSTEM("Follow system")
}

/** Section 4: electric blue/indigo primary, with a few calm alternatives. */
enum class AccentColor(val label: String, val seed: Long) {
    INDIGO("Indigo", 0xFF5B72E8),
    BLUE("Electric blue", 0xFF2E8BFF),
    TEAL("Teal", 0xFF12B5A5),
    VIOLET("Violet", 0xFF8B5CF6),
    AMBER("Amber", 0xFFE0952B),
}

enum class AnimationIntensity(val label: String, val scale: Float) {
    NONE("None", 0f),
    SUBTLE("Subtle", 0.6f),
    FULL("Full", 1f),
}

/** Section 6's exact set, plus manual. */
enum class RefreshRate(val label: String, val millis: Long) {
    ONE_SECOND("1 second", 1_000L),
    TWO_SECONDS("2 seconds", 2_000L),
    FIVE_SECONDS("5 seconds", 5_000L),
    TEN_SECONDS("10 seconds", 10_000L),
    MANUAL("Manual", Long.MAX_VALUE),
    ;

    val isAutomatic: Boolean get() = this != MANUAL
}

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    JSON("JSON", "json", "application/json"),
    CSV("CSV", "csv", "text/csv"),
    TEXT("Plain text", "txt", "text/plain"),
}

/** A favourited app, process or investigation (Section 31). */
data class Favorite(
    val id: Long = 0,
    val type: FavoriteType,
    /** Package name, process name, or investigation id as text. */
    val key: String,
    val label: String,
    val createdAt: Long,
)

enum class FavoriteType(val label: String) {
    APPLICATION("Application"),
    PROCESS("Process"),
    INVESTIGATION("Investigation"),
}
