package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.FollowMode
import kotlin.test.Test
import kotlin.test.assertEquals

class TracerTest {
    @Test
    fun fullscreenModeChangesOnlyPresentationState() {
        val initial = initialTracerModel()

        val fullscreen = enterFullscreen(initial)
        val restored = exitFullscreen(fullscreen)

        assertEquals(PromptDisplayMode.Fullscreen, fullscreen.displayMode)
        assertEquals(initial.session, fullscreen.session)
        assertEquals(initial.document, fullscreen.document)
        assertEquals(PromptDisplayMode.Workspace, restored.displayMode)
        assertEquals(initial.session, restored.session)
    }

    @Test
    fun resetDispatchesSessionResetWithoutReplayingSpeech() {
        val advanced = advance(initialTracerModel())

        val reset = reset(advanced)

        assertEquals(0, reset.session.follow.committedTokens)
        assertEquals(FollowMode.Following, reset.session.mode)
        assertEquals("", reset.hypothesisText)
        assertEquals(-1, reset.stepIndex)

        val firstHypothesis = advance(reset)
        assertEquals(2, firstHypothesis.session.follow.committedTokens)
        assertEquals("hello world", firstHypothesis.hypothesisText)
    }
}
