// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.core.content.ContextCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * In-IME voice capture via [SpeechRecognizer], optional MiMo cleanup ([ProofreadService.cleanupVoiceTranscript]),
 * then inserts text through [LatinIME.onTextInput].
 */
object VoiceInputController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var speechRecognizer: SpeechRecognizer? = null

    @JvmStatic
    fun startListening(latinIME: LatinIME) {
        mainHandler.post { startListeningOnMainThread(latinIME) }
    }

    private fun startListeningOnMainThread(latinIME: LatinIME) {
        if (!SpeechRecognizer.isRecognitionAvailable(latinIME)) {
            Toast.makeText(latinIME, R.string.voice_input_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(latinIME, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(latinIME, R.string.voice_input_permission_required, Toast.LENGTH_LONG).show()
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = null

        val sr = SpeechRecognizer.createSpeechRecognizer(latinIME.applicationContext) ?: run {
            Toast.makeText(latinIME, R.string.voice_input_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        speechRecognizer = sr

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {
                mainHandler.post {
                    KeyboardSwitcher.getInstance().showLoadingAnimation()
                }
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                mainHandler.post {
                    KeyboardSwitcher.getInstance().hideLoadingAnimation()
                    if (error != SpeechRecognizer.ERROR_CLIENT) {
                        Toast.makeText(latinIME, R.string.voice_input_error, Toast.LENGTH_SHORT).show()
                    }
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val raw = matches?.firstOrNull()?.trim().orEmpty()
                mainHandler.post {
                    KeyboardSwitcher.getInstance().hideLoadingAnimation()
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    if (raw.isEmpty()) {
                        Toast.makeText(latinIME, R.string.proofread_no_text, Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    runCleanupAndInsert(latinIME, raw)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        sr.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            Log.e("VoiceInputController", "startListening failed", e)
            Toast.makeText(latinIME, R.string.voice_input_error, Toast.LENGTH_SHORT).show()
            sr.destroy()
            speechRecognizer = null
        }
    }

    private fun runCleanupAndInsert(latinIME: LatinIME, transcript: String) {
        KeyboardSwitcher.getInstance().showLoadingAnimation()
        ioScope.launch {
            val result = try {
                ProofreadService(latinIME.applicationContext).cleanupVoiceTranscript(transcript)
            } catch (e: Exception) {
                Result.failure(e)
            }
            mainHandler.post {
                KeyboardSwitcher.getInstance().hideLoadingAnimation()
                result.fold(
                    onSuccess = { cleaned -> latinIME.onTextInput(cleaned) },
                    onFailure = { e ->
                        Toast.makeText(
                            latinIME,
                            latinIME.getString(R.string.proofread_error, e.message ?: "unknown"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
    }
}
