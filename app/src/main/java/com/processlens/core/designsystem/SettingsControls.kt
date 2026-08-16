package com.processlens.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * The controls the settings screen is built from (Sections 40, 41, 49).
 *
 * They live in the design system rather than in the settings package because the
 * accessibility contract they carry has to hold everywhere, and it is easier to get
 * right once. Each control merges into a single focusable node whose state
 * description is a word — "On", "Off", "Every 2 seconds" — so a screen-reader user
 * hears what a sighted user sees, rather than a switch with no context or a label
 * with no value (Section 49: never communicate state through appearance alone).
 */

/**
 * A labelled switch.
 *
 * The whole row is the tap target, not just the thumb: a 48 dp minimum is the
 * accessibility floor and a 32 dp switch does not meet it.
 */
@Composable
fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.minTouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    haptics.tick()
                    onCheckedChange(it)
                },
            )
            .semantics {
                contentDescription = listOfNotNull(title, subtitle).joinToString(". ")
                stateDescription = if (checked) "On" else "Off"
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // The switch itself is decorative here: the row above owns the semantics, and
        // a second announcement of the same state would be read out twice.
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { },
            colors = SwitchDefaults.colors(
                checkedTrackColor = ProcessLensTheme.accent.base,
                checkedThumbColor = MaterialTheme.colorScheme.surface,
            ),
        )
    }
}

/**
 * A row of chips, one of which is selected.
 *
 * Used wherever the choice is a short closed set — theme, accent, refresh rate,
 * export format. [FlowRow] rather than a horizontal scroll so nothing is hidden
 * off-screen at large font scales.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SettingChoice(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val haptics = rememberHaptics()
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Chip(
                    text = label(option),
                    selected = isSelected,
                    onClick = {
                        if (!isSelected) {
                            haptics.tick()
                            onSelect(option)
                        }
                    },
                    modifier = Modifier.semantics {
                        stateDescription = if (isSelected) "Selected" else "Not selected"
                    },
                )
            }
        }
    }
}

/**
 * An integer slider with the current value spelled out.
 *
 * The value is printed as text, not merely implied by the thumb position, because a
 * threshold a user cannot read is a threshold they cannot set (Section 49). Stepped
 * rather than continuous: a CPU warning line of 63.4% is false precision.
 */
@Composable
fun SettingSlider(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    valueLabel: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    steps: Int = 0,
) {
    val haptics = rememberHaptics()
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics {
                contentDescription = listOfNotNull(title, subtitle).joinToString(". ")
                stateDescription = valueLabel
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                valueLabel,
                style = MonoStyle,
                color = ProcessLensTheme.accent.bright,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            onValueChangeFinished = { haptics.tick() },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = ProcessLensTheme.accent.base,
                activeTrackColor = ProcessLensTheme.accent.base,
            ),
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
