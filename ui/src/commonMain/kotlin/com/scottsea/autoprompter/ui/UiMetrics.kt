package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.FollowMode
import com.scottsea.autoprompter.core.PromptRemoteCommand
import com.scottsea.autoprompter.core.settings.PromptPreferences
import com.scottsea.autoprompter.core.speech.SpeechEvent

/** Privacy-safe UI measurements; no transcript or script text can be represented. */
sealed interface UiMetric {
    data class SpeechFolded(
        val event: String,
        val tokenCount: Int?,
        val isFinal: Boolean?,
        val accepted: Boolean?,
        val committedBefore: Int,
        val committedAfter: Int,
        val followMode: FollowMode,
    ) : UiMetric

    data class PromptCommandApplied(
        val command: PromptRemoteCommand,
        val committedBefore: Int,
        val committedAfter: Int,
        val modeBefore: FollowMode,
        val modeAfter: FollowMode,
    ) : UiMetric

    data class SpeechControl(
        val action: String,
    ) : UiMetric

    data class DisplayModeChanged(
        val fullscreen: Boolean,
    ) : UiMetric

    data class PreferencesChanged(
        val fontScale: Float,
        val readingHorizonFraction: Float,
        val mirrored: Boolean,
        val predictiveCursor: Boolean,
        val focusStrip: Boolean,
        val phraseBias: Boolean,
    ) : UiMetric
}

internal fun speechFoldMetric(
    before: TracerModel,
    after: TracerModel,
    event: SpeechEvent,
): UiMetric.SpeechFolded {
    val hypothesis = (event as? SpeechEvent.Hypothesis)?.value
    val accepted =
        hypothesis?.let {
            after.live.latestUtterance == it.utteranceId &&
                after.live.latestRevision == it.revision &&
                (
                    before.live.latestUtterance != it.utteranceId ||
                        before.live.latestRevision != it.revision
                )
        }
    return UiMetric.SpeechFolded(
        event =
            when (event) {
                SpeechEvent.Starting -> "starting"
                SpeechEvent.Listening -> "listening"
                is SpeechEvent.Hypothesis -> "hypothesis"
                is SpeechEvent.Ended -> "ended_${event.reason.name.lowercase()}"
                is SpeechEvent.Failed -> "failed_${event.error::class.simpleName.orEmpty().lowercase()}"
            },
        tokenCount = hypothesis?.toPromptHypothesis()?.tokens?.size,
        isFinal = hypothesis?.isFinal,
        accepted = accepted,
        committedBefore = before.session.follow.committedTokens,
        committedAfter = after.session.follow.committedTokens,
        followMode = after.session.mode,
    )
}

internal fun preferencesMetric(preferences: PromptPreferences): UiMetric.PreferencesChanged =
    UiMetric.PreferencesChanged(
        fontScale = preferences.fontScale,
        readingHorizonFraction = preferences.readingHorizonFraction,
        mirrored = preferences.mirrorHorizontally,
        predictiveCursor = preferences.predictiveCursorEnabled,
        focusStrip = preferences.focusStripEnabled,
        phraseBias = preferences.phraseBiasEnabled,
    )
