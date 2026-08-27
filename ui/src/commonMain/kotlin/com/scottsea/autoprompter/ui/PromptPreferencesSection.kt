package com.scottsea.autoprompter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.scottsea.autoprompter.core.settings.PromptPreferences
import kotlin.math.roundToInt

internal fun adjustPromptFontScale(
    preferences: PromptPreferences,
    delta: Float,
): PromptPreferences =
    preferences.copy(
        fontScale = rounded((preferences.fontScale + delta).coerceIn(0.75f, 2f)),
    )

internal fun adjustReadingHorizon(
    preferences: PromptPreferences,
    delta: Float,
): PromptPreferences =
    preferences.copy(
        readingHorizonFraction =
            rounded(
                (preferences.readingHorizonFraction + delta).coerceIn(0.25f, 0.60f),
            ),
    )

internal fun togglePromptMirror(preferences: PromptPreferences): PromptPreferences =
    preferences.copy(mirrorHorizontally = !preferences.mirrorHorizontally)

internal fun togglePredictiveCursor(preferences: PromptPreferences): PromptPreferences =
    preferences.copy(predictiveCursorEnabled = !preferences.predictiveCursorEnabled)

internal fun toggleFocusStrip(preferences: PromptPreferences): PromptPreferences =
    preferences.copy(focusStripEnabled = !preferences.focusStripEnabled)

internal fun togglePhraseBias(preferences: PromptPreferences): PromptPreferences =
    preferences.copy(phraseBiasEnabled = !preferences.phraseBiasEnabled)

private fun rounded(value: Float): Float = (value * 100f).roundToInt() / 100f

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PromptPreferencesSection(
    preferences: PromptPreferences,
    loaded: Boolean,
    failure: String?,
    onChange: (PromptPreferences) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Prompt settings", style = MaterialTheme.typography.titleMedium)
            Text(
                if (loaded) {
                    "Text ${(preferences.fontScale * 100).roundToInt()}% · " +
                        "reading line ${(preferences.readingHorizonFraction * 100).roundToInt()}% from top · " +
                        (if (preferences.focusStripEnabled) "Focus Strip" else "multiline")
                } else {
                    "Loading saved prompt settings…"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            failure?.let { message ->
                Text(
                    message,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HorizontalDivider()
            Text("Reading position", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onChange(adjustPromptFontScale(preferences, -0.1f)) },
                    enabled = loaded && preferences.fontScale > 0.75f,
                ) { Text("Smaller text") }
                OutlinedButton(
                    onClick = { onChange(adjustPromptFontScale(preferences, 0.1f)) },
                    enabled = loaded && preferences.fontScale < 2f,
                ) { Text("Larger text") }
                OutlinedButton(
                    onClick = { onChange(adjustReadingHorizon(preferences, -0.05f)) },
                    enabled = loaded && preferences.readingHorizonFraction > 0.25f,
                ) { Text("Reading line higher") }
                OutlinedButton(
                    onClick = { onChange(adjustReadingHorizon(preferences, 0.05f)) },
                    enabled = loaded && preferences.readingHorizonFraction < 0.60f,
                ) { Text("Reading line lower") }
            }
            HorizontalDivider()
            Text("Prompt behavior", style = MaterialTheme.typography.titleSmall)
            PromptSettingToggle(
                label = "Mirror prompt",
                supportingText = "For beam-splitter and reflective rigs",
                checked = preferences.mirrorHorizontally,
                enabled = loaded,
                onCheckedChange = { onChange(togglePromptMirror(preferences)) },
            )
            PromptSettingToggle(
                label = "Predictive cursor",
                supportingText = "Visually leads confirmed speech by up to two words",
                checked = preferences.predictiveCursorEnabled,
                enabled = loaded,
                onCheckedChange = { onChange(togglePredictiveCursor(preferences)) },
            )
            PromptSettingToggle(
                label = "Focus Strip",
                supportingText = "Keeps the active word centered on one line",
                checked = preferences.focusStripEnabled,
                enabled = loaded,
                onCheckedChange = { onChange(toggleFocusStrip(preferences)) },
            )
            PromptSettingToggle(
                label = "Bias recognition toward script",
                supportingText = "Uses upcoming phrases when the recognizer supports it",
                checked = preferences.phraseBiasEnabled,
                enabled = loaded,
                onCheckedChange = { onChange(togglePhraseBias(preferences)) },
            )
        }
    }
}

@Composable
private fun PromptSettingToggle(
    label: String,
    supportingText: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { onCheckedChange() },
                )
                .semantics {
                    stateDescription = if (checked) "On" else "Off"
                }
                .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(supportingText, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}
