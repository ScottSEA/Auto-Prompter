package com.scottsea.autoprompter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scottsea.autoprompter.core.FollowMode
import com.scottsea.autoprompter.core.PromptRemoteCommand
import com.scottsea.autoprompter.core.progressIn
import com.scottsea.autoprompter.core.settings.PromptPreferences
import kotlin.math.roundToInt

internal val ExpandedWorkspaceBreakpoint: Dp = 900.dp
internal val ExpandedWorkspaceMinHeight: Dp = 600.dp

internal fun isExpandedWorkspace(width: Dp, height: Dp): Boolean =
    width >= ExpandedWorkspaceBreakpoint && height >= ExpandedWorkspaceMinHeight

/**
 * Uses a persistent two-pane presenter workspace on tablets and desktop-sized browser windows.
 *
 * Compact windows retain one scrollable column. Expanded windows keep the prompt visible while the
 * independently scrollable tools pane is edited, instead of stretching a phone layout across a
 * tablet.
 */
@Composable
internal fun ResponsivePromptWorkspace(
    modifier: Modifier = Modifier,
    promptPane: @Composable (expanded: Boolean, modifier: Modifier) -> Unit,
    toolsPane: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
    ) {
        if (isExpandedWorkspace(maxWidth, maxHeight)) {
            Row(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                promptPane(
                    true,
                    Modifier.weight(1.35f).fillMaxHeight(),
                )
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    toolsPane()
                }
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                promptPane(false, Modifier.fillMaxWidth())
                toolsPane()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PromptStage(
    model: TracerModel,
    preferences: PromptPreferences,
    speechListening: Boolean,
    onCommand: (PromptRemoteCommand) -> Unit,
    onUserScroll: () -> Unit,
    onFullscreen: () -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val committed = model.session.follow.committedTokens
    val total = model.session.script.tokenCount
    val progress = model.session.follow.progressIn(model.session.script)
    val following = model.session.mode == FollowMode.Following

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Auto-Prompter", style = MaterialTheme.typography.headlineSmall)
        Text(model.document.title, style = MaterialTheme.typography.titleMedium)
        PromptViewport(
            session = model.session,
            preferences = preferences,
            onUserScroll = onUserScroll,
            fillAvailableSpace = expanded,
            edgeToEdge = false,
            speechListening = speechListening,
            modifier = if (expanded) Modifier.weight(1f) else Modifier,
        )
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text =
                        if (following) {
                            "Following speech"
                        } else {
                            "Position held"
                        },
                    modifier =
                        Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "$committed of $total words · ${(progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onCommand(PromptRemoteCommand.Previous) },
                        enabled = committed > 0,
                    ) {
                        Text("Previous")
                    }
                    OutlinedButton(
                        onClick = { onCommand(PromptRemoteCommand.Next) },
                        enabled = committed < total,
                    ) {
                        Text("Next")
                    }
                    Button(onClick = { onCommand(PromptRemoteCommand.ToggleFollow) }) {
                        Text(if (following) "Hold position" else "Resume following")
                    }
                    OutlinedButton(
                        onClick = { onCommand(PromptRemoteCommand.Restart) },
                        enabled = committed > 0,
                    ) {
                        Text("Restart")
                    }
                    OutlinedButton(onClick = onFullscreen) {
                        Text("Fullscreen")
                    }
                }
            }
        }
    }
}
