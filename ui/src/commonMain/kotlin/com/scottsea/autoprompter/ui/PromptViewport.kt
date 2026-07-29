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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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

/** The product's distance-readable prompt surface, driven by committed token progress. */
@Composable
internal fun PromptViewport(
    session: PromptSessionState,
    preferences: PromptPreferences,
    onUserScroll: () -> Unit,
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
    val promptText = remember(session.script, session.follow.committedTokens) {
        promptAnnotatedText(session)
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
            promptCharacterOffset(session.script, session.follow.committedTokens)
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
                if (motionScale == 0f) {
                    scrollState.scrollTo(decision.scrollPx)
                } else {
                    scrollState.animateScrollTo(
                        decision.scrollPx,
                        animationSpec =
                            tween(
                                durationMillis = 220,
                                easing = FastOutSlowInEasing,
                            ),
                    )
                }
            }
        }
    }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(420.dp)
                .onSizeChanged { viewportHeightPx = it.height }
                .semantics {
                    stateDescription =
                        if (session.mode == FollowMode.Following) {
                            "Following speech"
                        } else {
                            "Manual hold"
                        }
                },
        shape = PromptShape,
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
    require(committedTokens in 0..script.tokenCount) {
        "Committed token position $committedTokens must be within 0..${script.tokenCount}."
    }
    if (script.tokens.isEmpty()) return 0
    if (committedTokens == script.tokenCount) {
        return script.tokens.sumOf { it.normalized.length } + script.tokenCount - 2
    }
    var offset = 0
    for (index in 0 until committedTokens) {
        offset += script.tokens[index].normalized.length + 1
    }
    return offset
}

private fun promptAnnotatedText(session: PromptSessionState): AnnotatedString =
    buildAnnotatedString {
        session.script.tokens.forEachIndexed { index, token ->
            if (index > 0) append(" ")
            val color: Color =
                when {
                    index < session.follow.committedTokens -> AutoPrompterPalette.PromptPassed
                    index == session.follow.committedTokens -> AutoPrompterPalette.Primary
                    else -> AutoPrompterPalette.PromptInk
                }
            val weight =
                if (index == session.follow.committedTokens) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Medium
                }
            withStyle(SpanStyle(color = color, fontWeight = weight)) {
                append(token.normalized)
            }
        }
    }
