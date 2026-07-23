package com.scottsea.autoprompter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

@Composable
fun TracerScreen() {
    var model by remember { mutableStateOf(initialTracerModel()) }

    val committed = model.follow.committedTokens
    val total = model.script.tokenCount
    val percent = (model.follow.progressIn(model.script) * 100).roundToInt()

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

        Text("Followed position: $committed / $total tokens ($percent%)")
        LinearProgressIndicator(
            progress = { model.follow.progressIn(model.script) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model = advance(model) }) { Text("Advance") }
            OutlinedButton(onClick = { model = revise(model) }) { Text("Revise shorter") }
            OutlinedButton(onClick = { model = reset() }) { Text("Reset") }
        }
    }
}

/** Renders the script with the followed (spoken) prefix emphasized. */
private fun scriptWithProgress(model: TracerModel) = buildAnnotatedString {
    val covered = model.script.coveredText(model.follow)
    val remaining = model.script.remainingText(model.follow)
    if (covered.isNotEmpty()) {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(covered) }
    }
    if (remaining.isNotEmpty()) {
        if (covered.isNotEmpty()) append(" ")
        append(remaining)
    }
}
