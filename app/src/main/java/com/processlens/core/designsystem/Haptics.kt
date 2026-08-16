package com.processlens.core.designsystem

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Haptics (Section 36).
 *
 * Routed through the platform view rather than Compose's `LocalHapticFeedback`
 * because [HapticFeedbackConstants] exposes the richer set — a confirm tick, a
 * segment tick — that Compose's two-constant abstraction does not, and because the
 * view path respects the system "touch vibration" setting for free.
 *
 * Every call honours the app's own haptics toggle. Deliberately restrained: a tick on
 * every list scroll in a tool that refreshes twice a second would be unbearable, so
 * haptics fire only on discrete user commitments — starting a recording, toggling a
 * favourite, crossing a scrubber step.
 */
class Haptics internal constructor(
    private val view: android.view.View?,
    private val enabled: Boolean,
) {
    private fun perform(constant: Int) {
        if (!enabled) return
        val v = view ?: return
        try {
            v.performHapticFeedback(constant)
        } catch (t: Throwable) {
            // Haptics are a nicety; a device that refuses them must not crash a tap.
        }
    }

    /** A light tick — selection changed, filter applied, scrubber stepped. */
    fun tick() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        },
    )

    /** A firmer confirmation — recording started, export written, favourite added. */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        },
    )

    /** A rejection — an action the platform will not permit on this device. */
    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )

    /** Long-press acknowledgement, for the row context menus. */
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)
}

/**
 * The haptics handle for the current composition, already wired to the user's
 * setting. Screens call `haptics.confirm()` without checking any toggle themselves.
 */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = ProcessLensTheme.hapticsEnabled
    return remember(view, enabled) { Haptics(view, enabled) }
}
