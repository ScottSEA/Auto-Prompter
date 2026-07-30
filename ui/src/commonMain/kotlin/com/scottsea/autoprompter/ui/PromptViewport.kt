package com.scottsea.autoprompter.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.scottsea.autoprompter.core.promptScrollDecision
import com.scottsea.autoprompter.core.settings.PromptPreferences
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

private val PromptShape = RoundedCornerShape(20.dp)
internal const val SPEECH_SCROLL_DURATION_MILLIS: Int = 0

/** The product's distance-readable prompt surface, driven by committed token progress. */
@Composable
internal fun PromptViewport(
    session: PromptSessionState,
    preferences: PromptPreferences,
    onUserScroll: () -> Unit,
    fillAvailableSpace: Boolean = false,
    modifier: Modifier = Modifier,
) {
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
    val promptText = remember(textModel, session.follow.committedTokens) {
        promptAnnotatedText(textModel, session.follow.committedTokens)
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
        session.follow.committedTokens,
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
            textModel.characterOffset(session.follow.committedTokens)
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
                .onSizeChanged { viewportHeightPx = it.height }
                .semantics {
                    stateDescription =
                        if (session.mode == FollowMode.Following) {
                            "Following speech"
                        } else {
                            "Manual hold"
                        }
                },
        shape = if (fillAvailableSpace) RectangleShape else PromptShape,
        color = AutoPrompterPalette.PromptBackground,
        contentColor = AutoPrompterPalette.PromptInk,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
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
): AnnotatedString =
    buildAnnotatedString {
        require(committedTokens in 0..model.tokenCount) {
            "Committed token position $committedTokens must be within 0..${model.tokenCount}."
        }
        append(model.text)
        val currentStart = model.characterOffset(committedTokens)
        val passedEnd =
            if (committedTokens == model.tokenCount) {
                model.text.length
            } else {
                currentStart
            }
        if (passedEnd > 0) {
            addStyle(
                SpanStyle(color = AutoPrompterPalette.PromptPassed),
                start = 0,
                end = passedEnd,
            )
        }
        if (committedTokens < model.tokenCount) {
            addStyle(
                SpanStyle(color = AutoPrompterPalette.Primary),
                start = currentStart,
                end = model.tokenEnd(committedTokens),
            )
        }
    }
