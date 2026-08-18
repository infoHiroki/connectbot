/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import org.connectbot.R
import timber.log.Timber
import java.util.Locale

/**
 * Recognizers keep listening this long through a pause before deciding the utterance is over.
 * Dictating a command usually involves thinking mid-sentence, so the stock timeout is too eager.
 * Recognizers are free to ignore these hints, which is why stopping is also driven by the button.
 */
private const val SILENCE_TIMEOUT_MS = 3000L

/** Range of `onRmsChanged` values, in dB, that is mapped onto the 0f..1f audio level. */
private const val RMS_FLOOR_DB = -2f
private const val RMS_CEILING_DB = 10f

/** Weight of each new RMS sample, so the level rises and falls smoothly instead of flickering. */
private const val RMS_SMOOTHING = 0.3f

/**
 * Drives in-place dictation with [SpeechRecognizer]. Unlike launching
 * [RecognizerIntent.ACTION_RECOGNIZE_SPEECH], this keeps the caller's UI on screen, which lets
 * partial results stream into the text being composed and lets the caller decide when listening
 * stops rather than relying on the recognizer's own endpointing.
 *
 * Create with [rememberVoiceInputState]; all methods must be called from the main thread.
 */
@Stable
class VoiceInputState internal constructor(
    private val context: Context,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onFailure: (Int) -> Unit,
) {
    /** Whether any recognition service is installed. When false there is nothing to offer. */
    val isAvailable: Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** True from the moment listening starts until a result or an error arrives. */
    var isListening by mutableStateOf(false)
        private set

    /** Smoothed microphone level in 0f..1f, for animating the button while listening. */
    var audioLevel by mutableFloatStateOf(0f)
        private set

    private var recognizer: SpeechRecognizer? = null

    /** Whether the microphone permission has already been granted. */
    fun hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    /** Starts listening. Partial results arrive continuously until [stop] or [cancel]. */
    fun start() {
        if (isListening) return

        val speech = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TIMEOUT_MS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TIMEOUT_MS,
            )
        }

        isListening = true
        audioLevel = 0f
        speech.startListening(intent)
    }

    /** Stops recording and asks the recognizer to finalize whatever it heard so far. */
    fun stop() {
        if (!isListening) return
        audioLevel = 0f
        recognizer?.stopListening()
    }

    /** Abandons the current utterance without waiting for a final result. */
    fun cancel() {
        if (!isListening) return
        isListening = false
        audioLevel = 0f
        recognizer?.cancel()
    }

    /** Releases the underlying recognizer. Called automatically when the composition leaves. */
    fun release() {
        recognizer?.destroy()
        recognizer = null
        isListening = false
        audioLevel = 0f
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            val normalized =
                ((rmsdB - RMS_FLOOR_DB) / (RMS_CEILING_DB - RMS_FLOOR_DB)).coerceIn(0f, 1f)
            audioLevel = audioLevel * (1f - RMS_SMOOTHING) + normalized * RMS_SMOOTHING
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            audioLevel = 0f
        }

        override fun onError(error: Int) {
            Timber.w("Speech recognition failed with error %d", error)
            isListening = false
            audioLevel = 0f
            onFailure(messageFor(error))
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            audioLevel = 0f
            val spoken = results.firstRecognition()
            if (spoken != null) {
                onFinalResult(spoken)
            } else {
                onFailure(R.string.terminal_text_input_voice_no_speech)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults.firstRecognition()?.let(onPartialResult)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

@StringRes
private fun messageFor(error: Int): Int = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    -> R.string.terminal_text_input_voice_no_speech

    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    -> R.string.terminal_text_input_voice_network_error

    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
    -> R.string.terminal_text_input_voice_permission_denied

    else -> R.string.terminal_text_input_voice_error
}

private fun Bundle?.firstRecognition(): String? = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    ?.firstOrNull()
    ?.takeIf { it.isNotEmpty() }

/**
 * Remembers a [VoiceInputState] bound to the current composition. The callbacks are always
 * invoked with their most recent value, and the recognizer is destroyed when the composition
 * leaves so that the microphone is never held open by a dismissed dialog.
 *
 * [onFailure] receives a string resource describing why dictation ended without a result.
 */
@Composable
fun rememberVoiceInputState(
    onPartialResult: (String) -> Unit,
    onFinalResult: (String) -> Unit,
    onFailure: (Int) -> Unit,
): VoiceInputState {
    val context = LocalContext.current
    val currentPartial by rememberUpdatedState(onPartialResult)
    val currentFinal by rememberUpdatedState(onFinalResult)
    val currentFailure by rememberUpdatedState(onFailure)

    val state = remember(context) {
        VoiceInputState(
            context = context,
            onPartialResult = { currentPartial(it) },
            onFinalResult = { currentFinal(it) },
            onFailure = { currentFailure(it) },
        )
    }

    DisposableEffect(state) {
        onDispose { state.release() }
    }

    return state
}
