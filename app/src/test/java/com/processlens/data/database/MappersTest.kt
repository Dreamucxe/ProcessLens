package com.processlens.data.database

import com.processlens.domain.model.AccentColor
import com.processlens.domain.model.AnimationIntensity
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.EventType
import com.processlens.domain.model.ExportFormat
import com.processlens.domain.model.FavoriteType
import com.processlens.domain.model.InvestigationState
import com.processlens.domain.model.RefreshRate
import com.processlens.domain.model.ThemeMode
import com.processlens.domain.model.UserSettings
import com.processlens.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Entity ↔ domain mapping (Sections 44, 56).
 *
 * These are the tests behind Section 44's "never wipe existing user data after schema
 * changes" and Section 56's "theme persistence" and "settings" entries. The mapping is
 * pure — no Room instance, no `org.json`, no Android framework — so it runs on a plain
 * JDK, and it is where the two failure modes that would silently corrupt a user's saved
 * state actually live:
 *
 *  1. **Ordinal drift.** If enums crossed this boundary as ordinals, inserting a value
 *     into the middle of [AccentColor] would repaint every user's accent on upgrade.
 *     [enumsPersistByNameNotOrdinal] pins the encoding to `name` so that reordering the
 *     declaration cannot change what a stored row means.
 *  2. **Downgrade crash.** A row written by a newer build can contain a name this build
 *     has never heard of. Throwing there would make the app unopenable, so the mapper
 *     falls back to a documented default — verified below for every enum column.
 *
 * The `Converters`-backed snapshot mapping is deliberately *not* here: it needs
 * `org.json`, which the unit-test `android.jar` stubs out, so it is covered under
 * `androidTest` where the real framework runs it.
 */
class MappersTest {

    // ------------------------------------------------------------------ settings

    @Test
    fun `settings survive a round trip through the entity unchanged`() {
        // Every field moved off its default, so a mapper that dropped one — or wired two
        // columns to the same field — cannot pass by accident.
        val original = UserSettings(
            themeMode = ThemeMode.LIGHT,
            accentColor = AccentColor.TEAL,
            useDynamicColor = true,
            glassEffectEnabled = false,
            animationIntensity = AnimationIntensity.SUBTLE,
            reducedMotion = true,
            hapticsEnabled = false,
            highContrast = true,
            refreshRate = RefreshRate.TEN_SECONDS,
            cpuPollingEnabled = false,
            memoryPollingEnabled = false,
            batteryPollingEnabled = false,
            networkPollingEnabled = false,
            processListPollMultiplier = 5,
            defaultDurationMinutes = 15,
            cpuWarningThreshold = 33,
            cpuCriticalThreshold = 77,
            memoryIncreaseWarningMb = 250,
            batteryTemperatureWarningDeciCelsius = 385,
            automaticEventDetection = false,
            investigationSampleIntervalMillis = 5_000L,
            localOnlyMode = false,
            includeSystemAppsInExport = false,
            exportFormat = ExportFormat.CSV,
            shizukuEnabled = false,
            rootEnabled = true,
            showOwnResourceUsage = false,
            showSystemProcesses = false,
            onboardingCompleted = true,
        )

        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `default settings survive a round trip unchanged`() {
        // The defaults are what a fresh install reads back, so they get their own case:
        // a default-valued field is exactly the one a mapper is most likely to omit
        // without any test noticing.
        val defaults = UserSettings()
        assertEquals(defaults, defaults.toEntity().toDomain())
    }

    @Test
    fun `theme choice is persisted for every mode`() {
        for (mode in ThemeMode.entries) {
            val restored = UserSettings(themeMode = mode).toEntity().toDomain()
            assertEquals("theme mode $mode did not survive persistence", mode, restored.themeMode)
        }
    }

    @Test
    fun `accent choice is persisted for every colour`() {
        for (accent in AccentColor.entries) {
            val restored = UserSettings(accentColor = accent).toEntity().toDomain()
            assertEquals("accent $accent did not survive persistence", accent, restored.accentColor)
        }
    }

    @Test
    fun `enums persist by name not ordinal`() {
        val entity = UserSettings(
            themeMode = ThemeMode.SYSTEM,
            accentColor = AccentColor.AMBER,
            refreshRate = RefreshRate.FIVE_SECONDS,
            animationIntensity = AnimationIntensity.NONE,
            exportFormat = ExportFormat.TEXT,
        ).toEntity()

        // The stored text is the declaration name. This is the assertion that makes a
        // future reordering of any of these enums safe: nothing on disk is positional.
        assertEquals("SYSTEM", entity.themeMode)
        assertEquals("AMBER", entity.accentColor)
        assertEquals("FIVE_SECONDS", entity.refreshRate)
        assertEquals("NONE", entity.animationIntensity)
        assertEquals("TEXT", entity.exportFormat)
    }

    @Test
    fun `an unknown theme name falls back to dark rather than throwing`() {
        // What a downgrade looks like: a build that added a theme wrote its name, and
        // this build has to open that row without crashing.
        val fromNewerBuild = UserSettings().toEntity().copy(themeMode = "MIDNIGHT_OLED")
        assertEquals(ThemeMode.DARK, fromNewerBuild.toDomain().themeMode)
    }

    @Test
    fun `an unknown name falls back to a default for every enum column`() {
        val corrupted = UserSettings().toEntity().copy(
            themeMode = "???",
            accentColor = "???",
            animationIntensity = "???",
            refreshRate = "???",
            exportFormat = "???",
        )

        val restored = corrupted.toDomain()

        assertEquals(ThemeMode.DARK, restored.themeMode)
        assertEquals(AccentColor.INDIGO, restored.accentColor)
        assertEquals(AnimationIntensity.FULL, restored.animationIntensity)
        assertEquals(RefreshRate.TWO_SECONDS, restored.refreshRate)
        assertEquals(ExportFormat.JSON, restored.exportFormat)
    }

    @Test
    fun `the fallback theme is dark because that is the app default`() {
        // Pins the fallback to the *documented* default rather than to whichever value
        // happens to be declared first — Section 0.1 specifies a dark default, and a
        // downgrade should land there, not on light.
        assertEquals(ThemeMode.DARK, UserSettings().themeMode)
        assertEquals(
            UserSettings().themeMode,
            UserSettings().toEntity().copy(themeMode = "nonsense").toDomain().themeMode,
        )
    }

    @Test
    fun `settings always occupy the single reserved row`() {
        // A second settings row would make "the user's settings" ambiguous, so the id is
        // fixed rather than autogenerated.
        assertEquals(
            UserSettings().toEntity().id,
            UserSettings(themeMode = ThemeMode.LIGHT).toEntity().id,
        )
    }

    // ------------------------------------------------------------- investigation

    @Test
    fun `an investigation survives a round trip`() {
        val original = Fixtures.investigation(
            id = 42L,
            name = "Overnight drain",
            state = InvestigationState.INTERRUPTED,
            targetPackage = "com.example.app",
            sampleIntervalMillis = 10_000L,
            accessLevelName = "Shizuku",
            apiLevel = 30,
            notes = "Screen off the whole time",
        )

        // Counts are derived from joined queries rather than stored on the row, so they
        // are excluded from the comparison — the entity has no column for them.
        val restored = original.toEntity().toDomain(
            eventCount = original.eventCount,
            snapshotCount = original.snapshotCount,
            processesObserved = original.processesObserved,
        )

        assertEquals(original, restored)
    }

    @Test
    fun `investigation state persists by name for every state`() {
        for (state in InvestigationState.entries) {
            val entity = Fixtures.investigation(state = state).toEntity()
            assertEquals(state.name, entity.state)
            assertEquals(state, entity.toDomain().state)
        }
    }

    @Test
    fun `an unknown investigation state falls back to completed`() {
        // Completed, not recording: a row this build cannot interpret must never be
        // treated as an active recording, or the recorder would try to resume it.
        val entity = Fixtures.investigation().toEntity().copy(state = "PAUSED_BY_FUTURE_BUILD")
        assertEquals(InvestigationState.COMPLETED, entity.toDomain().state)
        assertNotEquals(InvestigationState.RECORDING, entity.toDomain().state)
    }

    @Test
    fun `an open recording keeps a null end time`() {
        val entity = Fixtures.investigation(
            endedAt = null,
            state = InvestigationState.RECORDING,
        ).toEntity()

        assertNull(entity.endedAt)
        assertNull(entity.toDomain().endedAt)
    }

    // --------------------------------------------------------------------- event

    @Test
    fun `an event survives a round trip`() {
        val original = Fixtures.event(
            type = EventType.MEMORY_INCREASE,
            severity = EventSeverity.CRITICAL,
            title = "Memory grew",
            detail = "480 MB to 1.2 GB",
            value = 1_200.0,
            previousValue = 480.0,
            evidence = "Two consecutive samples, 2 s apart",
        )

        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `event type and severity persist by name for every value`() {
        for (type in EventType.entries) {
            val entity = Fixtures.event(type = type).toEntity()
            assertEquals(type.name, entity.type)
            assertEquals(type, entity.toDomain().type)
        }
        for (severity in EventSeverity.entries) {
            val entity = Fixtures.event(severity = severity).toEntity()
            assertEquals(severity.name, entity.severity)
            assertEquals(severity, entity.toDomain().severity)
        }
    }

    @Test
    fun `an unrecognised event type is read as an observation limit`() {
        // The honest fallback: a row this build cannot classify is not a finding, so it
        // is filed as a limitation rather than promoted into, say, a CPU spike.
        val entity = Fixtures.event().toEntity().copy(type = "QUANTUM_SPIKE")
        assertEquals(EventType.OBSERVATION_LIMITED, entity.toDomain().type)
    }

    @Test
    fun `an unrecognised severity is read as info rather than critical`() {
        // Erring towards info matters: an unreadable row must not manufacture a
        // critical-looking entry on someone's timeline.
        val entity = Fixtures.event().toEntity().copy(severity = "APOCALYPTIC")
        assertEquals(EventSeverity.INFO, entity.toDomain().severity)
    }

    @Test
    fun `an event with no attribution keeps its nulls`() {
        // A system-wide event genuinely has no process or package, and null must not be
        // rewritten as an empty string — the UI distinguishes the two.
        val original = Fixtures.event(
            packageName = null,
            processName = null,
            value = null,
            previousValue = null,
        )

        val restored = original.toEntity().toDomain()

        assertNull(restored.packageName)
        assertNull(restored.processName)
        assertNull(restored.value)
        assertNull(restored.previousValue)
    }

    // ------------------------------------------------------------------ favorite

    @Test
    fun `favorite type persists by name for every type`() {
        for (type in FavoriteType.entries) {
            val entity = com.processlens.domain.model.Favorite(
                type = type,
                key = "com.example",
                label = "Example",
                createdAt = Fixtures.T0,
            ).toEntity()

            assertEquals(type.name, entity.type)
            assertEquals(type, entity.toDomain().type)
        }
    }

    @Test
    fun `an unknown favorite type falls back to application`() {
        val entity = com.processlens.domain.model.Favorite(
            type = FavoriteType.APPLICATION,
            key = "k",
            label = "l",
            createdAt = Fixtures.T0,
        ).toEntity().copy(type = "WIDGET")

        assertEquals(FavoriteType.APPLICATION, entity.toDomain().type)
    }
}
