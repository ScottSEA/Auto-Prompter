package com.scottsea.autoprompter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.scottsea.autoprompter.core.FollowMode
import com.scottsea.autoprompter.core.coveredText
import com.scottsea.autoprompter.core.progressIn
import com.scottsea.autoprompter.core.remainingText
import kotlin.math.roundToInt

/** Platform composition roots call this single shared entry point. */
@Composable
fun TracerApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            TracerScreen()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TracerScreen() {
    var model by remember { mutableStateOf(initialTracerModel()) }

    val committed = model.session.follow.committedTokens
    val total = model.session.script.tokenCount
    val percent = (model.session.follow.progressIn(model.session.script) * 100).roundToInt()
    val following = model.session.mode == FollowMode.Following
    val modeLabel = if (following) "Following speech" else "Manual hold"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Auto-Prompter follow tracer", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Architecture tracer bullet: shared core alignment rendered on Android and web.",
            style = MaterialTheme.typography.bodySmall,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tracerScenarioNames().forEachIndexed { index, name ->
                if (index == model.scenarioIndex) {
                    Button(onClick = { model = selectScenario(index) }) { Text(name) }
                } else {
                    OutlinedButton(onClick = { model = selectScenario(index) }) { Text(name) }
                }
            }
        }

        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Script", style = MaterialTheme.typography.labelLarge)
                Text(scriptWithProgress(model))
            }
        }

        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Simulated current hypothesis", style = MaterialTheme.typography.labelLarge)
                Text(model.hypothesisText.ifEmpty { "(none)" })
            }
        }

        Text("Mode: $modeLabel", style = MaterialTheme.typography.titleSmall)
        Text("Followed position: $committed / $total tokens ($percent%)")
        LinearProgressIndicator(
            progress = { model.session.follow.progressIn(model.session.script) },
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { model = advance(model) }) { Text("Advance") }
            OutlinedButton(onClick = { model = revise(model) }) { Text("Revise shorter") }
            OutlinedButton(onClick = { model = nudgeBackward(model) }) { Text("Nudge back") }
            OutlinedButton(onClick = { model = nudgeForward(model) }) { Text("Nudge forward") }
            OutlinedButton(onClick = { model = toggleFollow(model) }) {
                Text(if (following) "Hold" else "Resume")
            }
            OutlinedButton(onClick = { model = reset(model) }) { Text("Reset") }
        }
    }
}

/** Renders the script with the followed (spoken) prefix emphasized. */
private fun scriptWithProgress(model: TracerModel) = buildAnnotatedString {
    val covered = model.session.script.coveredText(model.session.follow)
    val remaining = model.session.script.remainingText(model.session.follow)
    if (covered.isNotEmpty()) {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(covered) }
    }
    if (remaining.isNotEmpty()) {
        if (covered.isNotEmpty()) append(" ")
        append(remaining)
    }
}
