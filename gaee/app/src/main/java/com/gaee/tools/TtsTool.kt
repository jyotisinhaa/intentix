package com.gaee.tools

import android.content.Context
import android.speech.tts.TextToSpeech
import com.gaee.model.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class TtsTool(private val context: Context) : BaseTool {
    override val name = "TtsTool"

    private var tts: TextToSpeech? = null
    private var isReady = false
    // TextToSpeech init is async. A speak() call that arrives before the engine is ready would
    // otherwise be dropped silently — bad for a scam warning fired the moment the app starts. Hold
    // the latest pending text and speak it once initialization completes.
    private var pendingText: String? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.85f)
                isReady = true
                pendingText?.let { speak(it); pendingText = null }
            }
        }
    }

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val text = args["text"] ?: return ToolResult(false, "")
        speak(text)
        return ToolResult(true, "")
    }

    fun speak(text: String) {
        if (isReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gaee_utterance")
        } else {
            // Engine still starting up — remember it and speak on init (see the init callback).
            pendingText = text
        }
    }

    suspend fun speakAndWait(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        if (!isReady) { cont.resume(Unit); return@suspendCancellableCoroutine }
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
            override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gaee_utterance")
        cont.invokeOnCancellation { tts?.stop() }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
