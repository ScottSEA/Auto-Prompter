package com.scottsea.autoprompter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Prompt settings", style = MaterialTheme.typography.labelLarge)
            Text(
                if (loaded) {
                    "Text ${(preferences.fontScale * 100).roundToInt()}% · " +
                        "reading line ${(preferences.readingHorizonFraction * 100).roundToInt()}% from top · " +
                        if (preferences.mirrorHorizontally) "mirrored" else "normal"
                } else {
                    "Loading saved prompt settings…"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            failure?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }
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
                OutlinedButton(
                    onClick = { onChange(togglePromptMirror(preferences)) },
                    enabled = loaded,
                ) {
                    Text(if (preferences.mirrorHorizontally) "Turn mirror off" else "Mirror prompt")
                }
            }
        }
    }
}
