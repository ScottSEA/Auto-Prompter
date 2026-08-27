package com.scottsea.autoprompter.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scottsea.autoprompter.core.FollowMode
import com.scottsea.autoprompter.core.PromptScrollDecision
import com.scottsea.autoprompter.core.PromptSessionState
import com.scottsea.autoprompter.core.PromptViewportConfig
import com.scottsea.autoprompter.core.Script
import com.scottsea.autoprompter.core.ScriptToken
import com.scottsea.autoprompter.core.promptScrollDecision
import com.scottsea.autoprompter.core.settings.PromptPreferences
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

private val PromptShape = RoundedCornerShape(20.dp)
internal const val SPEECH_SCROLL_DURATION_MILLIS: Int = 0
private const val FOCUS_STRIP_RADIUS = 12

/** The product's distance-readable prompt surface, driven by committed token progress. */
@Composable
internal fun PromptViewport(
    session: PromptSessionState,
    preferences: PromptPreferences,
    onUserScroll: () -> Unit,
    fillAvailableSpace: Boolean = false,
    edgeToEdge: Boolean = false,
    respectSafeDrawing: Boolean = false,
    speechListening: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val highlightedToken =
        rememberPredictiveCursorPosition(
            session = session,
            enabled = preferences.predictiveCursorEnabled,
            listening = speechListening && session.mode == FollowMode.Following,
        )
    if (preferences.focusStripEnabled) {
        FocusStripViewport(
            session = session,
            preferences = preferences,
            onUserScroll = onUserScroll,
            highlightedToken = highlightedToken,
            fillAvailableSpace = fillAvailableSpace,
            edgeToEdge = edgeToEdge,
            respectSafeDrawing = respectSafeDrawing,
            modifier = modifier,
        )
        return
    }

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var layoutResult by remember(session.script) { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val promptTextStyle =
        PromptTextStyle.copy(
            fontSize = (48f * preferences.fontScale).sp,
            lineHeight = (65f * preferences.fontScale).sp,
        )
    val policy =
        PromptViewportConfig(
            readingHorizonFraction = preferences.readingHorizonFraction,
        )
    val lineHeightPx = with(density) { promptTextStyle.lineHeight.toPx() }
    val padding =
        promptViewportPadding(
            viewportHeightPx = viewportHeightPx,
            lineHeightPx = lineHeightPx,
            config = policy,
        )
    val topSpace = with(density) { padding.topPx.toDp() }
    val bottomSpace = with(density) { padding.bottomPx.toDp() }
    val textModel = remember(session.script) { promptTextModel(session.script) }
    val promptText = remember(textModel, session.follow.committedTokens, highlightedToken) {
        promptAnnotatedText(
            model = textModel,
            committedTokens = session.follow.committedTokens,
            highlightedToken = highlightedToken,
        )
    }
    val manualScrollConnection =
        remember(onUserScroll) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) {
                        onUserScroll()
                    }
                    return Offset.Zero
                }
            }
        }

    LaunchedEffect(
        highlightedToken,
        session.mode,
        preferences,
        layoutResult,
        viewportHeightPx,
        scrollState.maxValue,
    ) {
        if (session.mode != FollowMode.Following) return@LaunchedEffect
        val layout = layoutResult ?: return@LaunchedEffect
        if (viewportHeightPx <= 0 || layout.lineCount == 0) return@LaunchedEffect
        val characterOffset =
            textModel.characterOffset(highlightedToken)
                .coerceIn(0, (layout.layoutInput.text.length - 1).coerceAtLeast(0))
        val line = layout.getLineForOffset(characterOffset)
        val anchorCenter =
            (padding.topPx + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f)
                .roundToInt()
        val contentHeight = scrollState.maxValue + viewportHeightPx
        when (
            val decision =
                promptScrollDecision(
                    anchorCenterPx = anchorCenter,
                    viewportHeightPx = viewportHeightPx,
                    contentHeightPx = contentHeight,
                    currentScrollPx = scrollState.value,
                    config = policy,
                )
        ) {
            PromptScrollDecision.Hold -> Unit
            is PromptScrollDecision.MoveTo -> {
                val motionScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
                if (motionScale == 0f || SPEECH_SCROLL_DURATION_MILLIS == 0) {
                    scrollState.scrollTo(decision.scrollPx)
                } else {
                    scrollState.animateScrollTo(
                        decision.scrollPx,
                        animationSpec =
                            tween(
                                durationMillis = SPEECH_SCROLL_DURATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                    )
                }
            }
        }
    }

    Surface(
        modifier =
            (if (fillAvailableSpace) {
                modifier.fillMaxSize()
            } else {
                modifier.fillMaxWidth().height(420.dp)
            })
                .semantics {
                    stateDescription =
                        if (session.mode == FollowMode.Following) {
                            "Following speech"
                        } else {
                            "Manual hold"
                        }
                },
        shape = if (edgeToEdge) RectangleShape else PromptShape,
        color = AutoPrompterPalette.PromptBackground,
        contentColor = AutoPrompterPalette.PromptInk,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (respectSafeDrawing) {
                            Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                        } else {
                            Modifier
                        },
                    ),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewportHeightPx = it.height }
                        .nestedScroll(manualScrollConnection),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 32.dp),
                ) {
                    Spacer(Modifier.height(topSpace))
                    Text(
                        modifier =
                            Modifier.graphicsLayer {
                                scaleX = if (preferences.mirrorHorizontally) -1f else 1f
                            },
                        text = promptText,
                        style = promptTextStyle,
                        color = AutoPrompterPalette.PromptInk,
                        onTextLayout = { layoutResult = it },
                    )
                    Spacer(Modifier.height(bottomSpace))
                }
            }
        }
    }
}

@Composable
private fun FocusStripViewport(
    session: PromptSessionState,
    preferences: PromptPreferences,
    onUserScroll: () -> Unit,
    highlightedToken: Int,
    fillAvailableSpace: Boolean,
    edgeToEdge: Boolean,
    respectSafeDrawing: Boolean,
    modifier: Modifier,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var layoutResult by remember(session.script) { mutableStateOf<TextLayoutResult?>(null) }
    val window =
        remember(session.script, session.follow.committedTokens, highlightedToken) {
            focusStripWindow(
                script = session.script,
                confirmedTokens = session.follow.committedTokens,
                highlightedToken = highlightedToken,
            )
        }
    val promptText =
        remember(window) {
            promptAnnotatedText(
                model = window.textModel,
                committedTokens = window.confirmedTokens,
                highlightedToken = window.highlightedToken,
            )
        }
    val sideRunway = with(density) { (viewportWidthPx / 2f).toDp() }
    val manualScrollConnection =
        remember(onUserScroll) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available != Offset.Zero) {
                        onUserScroll()
                    }
                    return Offset.Zero
                }
            }
        }

    LaunchedEffect(
        window,
        layoutResult,
        viewportWidthPx,
        scrollState.maxValue,
        session.mode,
    ) {
        if (session.mode != FollowMode.Following) return@LaunchedEffect
        val layout = layoutResult ?: return@LaunchedEffect
        if (viewportWidthPx <= 0 || layout.layoutInput.text.isEmpty()) return@LaunchedEffect
        val characterOffset =
            window.textModel
                .characterOffset(window.highlightedToken)
                .coerceIn(0, layout.layoutInput.text.length - 1)
        val logicalCenter = layout.getBoundingBox(characterOffset).center.x
        val center =
            if (preferences.mirrorHorizontally) {
                (layout.size.width - logicalCenter).roundToInt()
            } else {
                logicalCenter.roundToInt()
            }
        scrollState.scrollTo(center.coerceIn(0, scrollState.maxValue))
    }

    Surface(
        modifier =
            (if (fillAvailableSpace) {
                modifier.fillMaxSize()
            } else {
                modifier.fillMaxWidth().height(180.dp)
            }).semantics {
                stateDescription =
                    if (session.mode == FollowMode.Following) {
                        "Following speech"
                    } else {
                        "Manual hold"
                    }
            },
        shape = if (edgeToEdge) RectangleShape else PromptShape,
        color = AutoPrompterPalette.PromptBackground,
        contentColor = AutoPrompterPalette.PromptInk,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (respectSafeDrawing) {
                            Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                        } else {
                            Modifier
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewportWidthPx = it.width }
                        .nestedScroll(manualScrollConnection),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .horizontalScroll(scrollState),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(sideRunway))
                    Text(
                        modifier =
                            Modifier.graphicsLayer {
                                scaleX = if (preferences.mirrorHorizontally) -1f else 1f
                            },
                        text = promptText,
                        style =
                            PromptTextStyle.copy(
                                fontSize = (48f * preferences.fontScale).sp,
                                lineHeight = (65f * preferences.fontScale).sp,
                            ),
                        color = AutoPrompterPalette.PromptInk,
                        maxLines = 1,
                        softWrap = false,
                        onTextLayout = { layoutResult = it },
                    )
                    Spacer(Modifier.width(sideRunway))
                }
            }
        }
    }
}

internal data class PromptViewportPadding(
    val topPx: Float,
    val bottomPx: Float,
)

internal class PromptTextModel private constructor(
    val text: String,
    private val tokenStarts: IntArray,
    private val tokenEnds: IntArray,
) {
    val tokenCount: Int get() = tokenStarts.size

    fun characterOffset(committedTokens: Int): Int {
        require(committedTokens in 0..tokenCount) {
            "Committed token position $committedTokens must be within 0..$tokenCount."
        }
        if (text.isEmpty()) return 0
        return if (committedTokens == tokenCount) text.lastIndex else tokenStarts[committedTokens]
    }

    fun tokenEnd(position: Int): Int {
        require(position in 0 until tokenCount) {
            "Token position $position must be within 0 until $tokenCount."
        }
        return tokenEnds[position]
    }

    companion object {
        fun from(script: Script): PromptTextModel {
            val starts = IntArray(script.tokenCount)
            val ends = IntArray(script.tokenCount)
            val text =
                buildString {
                    script.tokens.forEachIndexed { index, token ->
                        if (index > 0) append(' ')
                        starts[index] = length
                        append(token.normalized)
                        ends[index] = length
                    }
                }
            return PromptTextModel(text, starts, ends)
        }
    }
}

internal fun promptTextModel(script: Script): PromptTextModel = PromptTextModel.from(script)

internal data class FocusStripWindow(
    val textModel: PromptTextModel,
    val confirmedTokens: Int,
    val highlightedToken: Int,
)

internal fun focusStripWindow(
    script: Script,
    confirmedTokens: Int,
    highlightedToken: Int,
): FocusStripWindow {
    require(confirmedTokens in 0..script.tokenCount)
    require(highlightedToken in 0..script.tokenCount)
    if (script.tokenCount == 0) {
        return FocusStripWindow(promptTextModel(script), 0, 0)
    }
    val anchor = highlightedToken.coerceAtMost(script.tokenCount - 1)
    val start = (anchor - FOCUS_STRIP_RADIUS).coerceAtLeast(0)
    val end = (anchor + FOCUS_STRIP_RADIUS + 1).coerceAtMost(script.tokenCount)
    val windowScript =
        Script(
            script.tokens
                .subList(start, end)
                .mapIndexed { index, token -> ScriptToken(index, token.normalized) },
        )
    return FocusStripWindow(
        textModel = promptTextModel(windowScript),
        confirmedTokens = (confirmedTokens - start).coerceIn(0, windowScript.tokenCount),
        highlightedToken = (highlightedToken - start).coerceIn(0, windowScript.tokenCount),
    )
}

/**
 * Derives symmetric reading runway around the horizon for any viewport and font scale.
 *
 * At scroll start the first line center sits on the horizon; at max scroll the final line center
 * can sit on the same horizon.
 */
internal fun promptViewportPadding(
    viewportHeightPx: Int,
    lineHeightPx: Float,
    config: PromptViewportConfig = PromptViewportConfig(),
): PromptViewportPadding {
    require(viewportHeightPx >= 0) { "Viewport height cannot be negative: $viewportHeightPx." }
    require(lineHeightPx >= 0f) { "Line height cannot be negative: $lineHeightPx." }
    val horizonPx = viewportHeightPx * config.readingHorizonFraction
    val halfLine = lineHeightPx / 2f
    return PromptViewportPadding(
        topPx = (horizonPx - halfLine).coerceAtLeast(0f),
        bottomPx = (viewportHeightPx - horizonPx - halfLine).coerceAtLeast(0f),
    )
}

/** Character offset of the token at [committedTokens], or the final character at script end. */
internal fun promptCharacterOffset(script: Script, committedTokens: Int): Int {
    return promptTextModel(script).characterOffset(committedTokens)
}

internal fun promptAnnotatedText(
    model: PromptTextModel,
    committedTokens: Int,
    highlightedToken: Int = committedTokens,
): AnnotatedString =
    buildAnnotatedString {
        require(committedTokens in 0..model.tokenCount) {
            "Committed token position $committedTokens must be within 0..${model.tokenCount}."
        }
        require(highlightedToken in committedTokens..model.tokenCount) {
            "Highlighted token $highlightedToken must be within $committedTokens..${model.tokenCount}."
        }
        append(model.text)
        val confirmedStart = model.characterOffset(committedTokens)
        val passedEnd =
            if (committedTokens == model.tokenCount) {
                model.text.length
            } else {
                confirmedStart
            }
        if (passedEnd > 0) {
            addStyle(
                SpanStyle(color = AutoPrompterPalette.PromptPassed),
                start = 0,
                end = passedEnd,
            )
        }
        if (highlightedToken < model.tokenCount) {
            addStyle(
                SpanStyle(color = AutoPrompterPalette.Primary),
                start = model.characterOffset(highlightedToken),
                end = model.tokenEnd(highlightedToken),
            )
        }
    }
