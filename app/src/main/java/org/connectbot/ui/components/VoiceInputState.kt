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
import android.os.Handler
import android.os.Looper
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
 * Hint asking recognizers to sit through this much silence before calling an utterance finished.
 * Dictating a command involves thinking mid-sentence, so the stock timeout is far too eager.
 * Recognizers are free to ignore the hint, which is why [VoiceInputState] also restarts itself.
 */
private const val SILENCE_TIMEOUT_MS = 3000L

/** Pause before listening again, long enough for the recognizer to release its audio session. */
private const val RESTART_DELAY_MS = 150L

/**
 * How many times in a row listening may end without recognizing anything before the session is
 * given up. Past this the user has simply stopped talking and the microphone should not stay open.
 */
private const val MAX_EMPTY_RESTARTS = 5

/** Range of `onRmsChanged` values, in dB, that is mapped onto the 0f..1f audio level. */
private const val RMS_FLOOR_DB = -2f
private const val RMS_CEILING_DB = 10f

/** Weight of each new RMS sample, so the level rises and falls smoothly instead of flickering. */
private const val RMS_SMOOTHING = 0.3f

/**
 * Drives in-place dictation with [SpeechRecognizer]. Unlike launching
 * [RecognizerIntent.ACTION_RECOGNIZE_SPEECH], this keeps the caller's UI on screen and streams
 * partial results into the text being composed.
 *
 * A recognizer decides on its own that an utterance is over once it hears a pause, and there is no
 * API to turn that off. So listening restarts automatically after every finalized segment: each
 * segment is handed to `onSegmentResult` as it lands and the session keeps running until [stop] or
 * [cancel], which is what makes a single stop button meaningful. `onSessionEnd` marks the point
 * where nothing more is coming, and carries a message resource when the session ended badly.
 *
 * Create with [rememberVoiceInputState]; all methods must be called from the main thread.
 */
@Stable
class VoiceInputState internal constructor(
    private val context: Context,
    private val onPartialResult: (String) -> Unit,
    private val onSegmentResult: (String) -> Unit,
    private val onSessionEnd: (Int?) -> Unit,
) {
    /** Whether any recognition service is installed. When false there is nothing to offer. */
    val isAvailable: Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** True for the whole dictation session, spanning the restarts between segments. */
    var isListening by mutableStateOf(false)
        private set

    /** Smoothed microphone level in 0f..1f, for animating the button while listening. */
    var audioLevel by mutableFloatStateOf(0f)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** Set while the user still wants to dictate; cleared only by [stop], [cancel] or a failure. */
    private var keepListening = false

    /** Consecutive restarts that produced nothing, used to close an abandoned session. */
    private var emptyRestarts = 0

    /** Whether this session has recognized anything, so giving up can stay quiet if it has. */
    private var recognizedAnything = false

    private val listenAgain = Runnable {
        if (keepListening) listen()
    }

    /** Whether the microphone permission has already been granted. */
    fun hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    /**
     * Starts a dictation session. Partial results stream continuously and each finished segment
     * is delivered to `onSegmentResult`, until [stop] or [cancel] ends the session.
     */
    fun start() {
        if (isListening) return
        keepListening = true
        isListening = true
        emptyRestarts = 0
        recognizedAnything = false
        audioLevel = 0f
        listen()
    }

    /** Ends the session, letting the recognizer finalize whatever it heard last. */
    fun stop() {
        if (!isListening) return
        keepListening = false
        audioLevel = 0f
        handler.removeCallbacks(listenAgain)
        recognizer?.stopListening()
    }

    /**
     * Ends the session immediately, discarding anything not yet delivered. `onSessionEnd` is not
     * invoked: the caller asked for this and already knows the session is over.
     */
    fun cancel() {
        if (!isListening) return
        endSession()
        recognizer?.cancel()
    }

    /** Releases the underlying recognizer. Called automatically when the composition leaves. */
    fun release() {
        endSession()
        recognizer?.destroy()
        recognizer = null
    }

    private fun endSession() {
        keepListening = false
        isListening = false
        audioLevel = 0f
        handler.removeCallbacks(listenAgain)
    }

    private fun listen() {
        val speech = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }

        speech.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
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
            },
        )
    }

    /** Queues another listen, or closes the session once too many have come back empty. */
    private fun listenAgainOrGiveUp() {
        if (emptyRestarts >= MAX_EMPTY_RESTARTS) {
            finish(R.string.terminal_text_input_voice_no_speech)
            return
        }
        handler.removeCallbacks(listenAgain)
        handler.postDelayed(listenAgain, RESTART_DELAY_MS)
    }

    /**
     * Closes the session, reporting [message] only when nothing was recognized. Once a segment has
     * landed, running out of speech is how dictation is meant to end, not something to complain
     * about.
     */
    private fun finish(@StringRes message: Int) {
        endSession()
        onSessionEnd(if (recognizedAnything) null else message)
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
            audioLevel = 0f
            if (error.endsOnlyThisSegment()) {
                Timber.d("Speech segment ended with error %d", error)
                emptyRestarts++
                if (keepListening) {
                    listenAgainOrGiveUp()
                } else {
                    finish(messageFor(error))
                }
                return
            }

            Timber.w("Speech recognition failed with error %d", error)
            endSession()
            onSessionEnd(messageFor(error))
        }

        override fun onResults(results: Bundle?) {
            audioLevel = 0f
            val spoken = results.firstRecognition()

            if (spoken != null) {
                emptyRestarts = 0
                recognizedAnything = true
                onSegmentResult(spoken)
            } else {
                emptyRestarts++
            }

            if (keepListening) {
                listenAgainOrGiveUp()
            } else {
                finish(R.string.terminal_text_input_voice_no_speech)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults.firstRecognition()?.let(onPartialResult)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

/**
 * Whether an error only means the current segment is over. These arrive routinely whenever the
 * speaker pauses, so they must not tear down a session the user has not stopped.
 */
private fun Int.endsOnlyThisSegment(): Boolean = this == SpeechRecognizer.ERROR_NO_MATCH ||
    this == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
    this == SpeechRecognizer.ERROR_RECOGNIZER_BUSY

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
 * [onSessionEnd] runs once dictation is over, with a string resource to show when it ended
 * badly and null when it simply finished.
 */
@Composable
fun rememberVoiceInputState(
    onPartialResult: (String) -> Unit,
    onSegmentResult: (String) -> Unit,
    onSessionEnd: (Int?) -> Unit,
): VoiceInputState {
    val context = LocalContext.current
    val currentPartial by rememberUpdatedState(onPartialResult)
    val currentSegment by rememberUpdatedState(onSegmentResult)
    val currentSessionEnd by rememberUpdatedState(onSessionEnd)

    val state = remember(context) {
        VoiceInputState(
            context = context,
            onPartialResult = { currentPartial(it) },
            onSegmentResult = { currentSegment(it) },
            onSessionEnd = { currentSessionEnd(it) },
        )
    }

    DisposableEffect(state) {
        onDispose { state.release() }
    }

    return state
}
