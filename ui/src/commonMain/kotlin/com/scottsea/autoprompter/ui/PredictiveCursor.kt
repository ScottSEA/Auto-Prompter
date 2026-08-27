package com.scottsea.autoprompter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import com.scottsea.autoprompter.core.PromptSessionState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal data class PredictiveCursorState(
    val confirmedTokens: Int,
    val confirmedAtMillis: Long,
    val millisPerToken: Long,
)

internal fun observeConfirmedCursor(
    previous: PredictiveCursorState?,
    confirmedTokens: Int,
    nowMillis: Long,
): PredictiveCursorState {
    require(confirmedTokens >= 0) { "Confirmed cursor cannot be negative." }
    require(nowMillis >= 0L) { "Cursor observation time cannot be negative." }
    if (previous == null || confirmedTokens < previous.confirmedTokens) {
        return PredictiveCursorState(confirmedTokens, nowMillis, DEFAULT_MILLIS_PER_TOKEN)
    }
    if (confirmedTokens == previous.confirmedTokens) {
        return if (previous.confirmedAtMillis == 0L && nowMillis > 0L) {
            previous.copy(confirmedAtMillis = nowMillis)
        } else {
            previous
        }
    }

    val tokenDelta = confirmedTokens - previous.confirmedTokens
    val elapsed = (nowMillis - previous.confirmedAtMillis).coerceAtLeast(0L)
    val observed =
        if (elapsed == 0L) {
            previous.millisPerToken
        } else {
            (elapsed / tokenDelta).coerceIn(MIN_MILLIS_PER_TOKEN, MAX_MILLIS_PER_TOKEN)
        }
    val smoothed = ((previous.millisPerToken * 3L + observed) / 4L)
    return PredictiveCursorState(
        confirmedTokens = confirmedTokens,
        confirmedAtMillis = nowMillis,
        millisPerToken = smoothed,
    )
}

internal fun predictedCursorPosition(
    state: PredictiveCursorState,
    nowMillis: Long,
    tokenCount: Int,
    enabled: Boolean,
    listening: Boolean,
): Int {
    require(tokenCount >= 0) { "Token count cannot be negative." }
    val confirmed = state.confirmedTokens.coerceIn(0, tokenCount)
    if (!enabled || !listening || confirmed == tokenCount) return confirmed
    val elapsed = (nowMillis - state.confirmedAtMillis).coerceAtLeast(0L)
    val lead = (elapsed / state.millisPerToken).toInt().coerceIn(0, MAX_PREDICTIVE_LEAD)
    return (confirmed + lead).coerceAtMost(tokenCount)
}

@Composable
internal fun rememberPredictiveCursorPosition(
    session: PromptSessionState,
    enabled: Boolean,
    listening: Boolean,
): Int {
    val confirmed = session.follow.committedTokens
    var cursor by remember(session.script) {
        mutableStateOf(observeConfirmedCursor(null, confirmed, nowMillis = 0L))
    }
    var nowMillis by remember(session.script) { mutableLongStateOf(0L) }

    LaunchedEffect(confirmed) {
        withFrameMillis { frameMillis ->
            nowMillis = frameMillis
            cursor = observeConfirmedCursor(cursor, confirmed, frameMillis)
        }
    }
    LaunchedEffect(enabled, listening) {
        if (!enabled || !listening) return@LaunchedEffect
        while (currentCoroutineContext().isActive) {
            withFrameMillis { frameMillis -> nowMillis = frameMillis }
            delay(PREDICTION_REFRESH_MILLIS)
        }
    }

    val current =
        if (cursor.confirmedTokens == confirmed) {
            cursor
        } else {
            observeConfirmedCursor(cursor, confirmed, nowMillis)
        }
    return predictedCursorPosition(
        state = current,
        nowMillis = nowMillis,
        tokenCount = session.script.tokenCount,
        enabled = enabled,
        listening = listening,
    )
}

private const val DEFAULT_MILLIS_PER_TOKEN = 300L
private const val MIN_MILLIS_PER_TOKEN = 160L
private const val MAX_MILLIS_PER_TOKEN = 1_200L
private const val MAX_PREDICTIVE_LEAD = 2
private const val PREDICTION_REFRESH_MILLIS = 50L
