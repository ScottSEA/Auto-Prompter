package com.scottsea.autoprompter.webspeech

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalFirstSpeechRecognitionEngineTest {
    @Test
    fun localPackRejectionRestartsImmediatelyInProviderMode() {
        val local = FakeSpeechRecognitionEngine()
        val provider = FakeSpeechRecognitionEngine()
        val engines = ArrayDeque(listOf(local, provider))
        val localFailures = mutableListOf<String>()
        val surfacedErrors = mutableListOf<String>()
        var listeningEvents = 0
        val engine =
            LocalFirstSpeechRecognitionEngine(
                engineFactory = { engines.removeFirst() },
                onLocalUnavailable = localFailures::add,
            )
        engine.onStart = { listeningEvents += 1 }
        engine.onError = surfacedErrors::add
        engine.configure(
            language = "en-US",
            continuous = true,
            interimResults = true,
            maxAlternatives = 1,
            processLocally = true,
        )
        engine.start()

        local.driveError("language-not-supported")
        provider.driveStart()

        assertEquals(1, local.detachCalls)
        assertEquals(false, provider.lastProcessLocally)
        assertEquals(1, provider.startCalls)
        assertEquals(listOf("language-not-supported"), localFailures)
        assertEquals(emptyList(), surfacedErrors)
        assertEquals(1, listeningEvents)

        provider.driveError("network")
        assertEquals(listOf("network"), surfacedErrors)
        engine.detach()
    }

    @Test
    fun providerFallbackStartFailureIsSurfaced() {
        val local = FakeSpeechRecognitionEngine()
        val provider = FakeSpeechRecognitionEngine()
        provider.startFailure = IllegalStateException("busy")
        val engines = ArrayDeque(listOf(local, provider))
        val surfacedErrors = mutableListOf<String>()
        val engine =
            LocalFirstSpeechRecognitionEngine(
                engineFactory = { engines.removeFirst() },
                onLocalUnavailable = {},
            )
        engine.onError = surfacedErrors::add
        engine.configure("en-US", true, true, 1, processLocally = true)
        engine.start()

        local.driveError("service-not-allowed")

        assertEquals(listOf("local-fallback-failed:busy"), surfacedErrors)
        engine.detach()
    }
}
