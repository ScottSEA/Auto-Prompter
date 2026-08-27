package com.scottsea.autoprompter.androidmedia

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.scottsea.autoprompter.core.speech.LanguageTag

/**
 * The real on-device [NativeSpeechRecognizer], wrapping Android's [SpeechRecognizer]. It is a thin,
 * device-only adapter: it is never exercised by unit tests (framework classes are unavailable on the
 * JVM host), so all decision logic lives in the JVM-tested seams ([NativeSpeechSession],
 * [nativeSpeechErrorForCode], [nativeSpeechAvailability]). This class only forwards main-thread calls
 * and translates [RecognitionListener] callbacks into [NativeRecognitionEvents].
 *
 * It requests offline-only free-form en-US recognition with partial results. `EXTRA_PREFER_OFFLINE`
 * plus construction via [SpeechRecognizer.createOnDeviceSpeechRecognizer] keeps recognition on-device;
 * the network backend is never used. Any phrase hints from the session plan are attached as
 * `EXTRA_BIASING_STRINGS` on API 33+ (via [biasingPhraseHints]); on older on-device-capable devices
 * (API 31-32) the platform cannot bias and the hints are simply not applied.
 */
@Suppress("NewApi")
internal class AndroidNativeSpeechRecognizer private constructor(
    private val recognizer: SpeechRecognizer,
    private val intent: Intent,
    events: NativeRecognitionEvents,
) : NativeSpeechRecognizer {
    private val listener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = events.onReadyForSpeech()

            override fun onBeginningOfSpeech() = events.onBeginningOfSpeech()

            override fun onRmsChanged(rmsdB: Float) = events.onAudioLevel(rmsdB)

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = events.onEndOfSpeech()

            override fun onError(error: Int) = events.onError(error)

            override fun onPartialResults(partialResults: Bundle?) {
                events.onPartialTranscript(firstTranscript(partialResults))
            }

            override fun onResults(results: Bundle?) {
                events.onFinalTranscript(firstTranscript(results))
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

    init {
        recognizer.setRecognitionListener(listener)
    }

    override fun startListening() = recognizer.startListening(intent)

    override fun stopListening() = recognizer.stopListening()

    override fun cancel() = recognizer.cancel()

    override fun close() {
        recognizer.setRecognitionListener(null)
        recognizer.destroy()
    }

    companion object {
        private fun firstTranscript(bundle: Bundle?): String =
            bundle
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()

        fun create(
            context: Context,
            language: LanguageTag,
            phraseHints: List<String>,
            events: NativeRecognitionEvents,
        ): AndroidNativeSpeechRecognizer {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val intent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.value)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    biasingPhraseHints(phraseHints, Build.VERSION.SDK_INT)?.let { hints ->
                        putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, hints.toTypedArray())
                    }
                }
            return AndroidNativeSpeechRecognizer(recognizer, intent, events)
        }
    }
}
