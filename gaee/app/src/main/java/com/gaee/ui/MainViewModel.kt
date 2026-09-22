package com.gaee.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gaee.engine.ActionCache
import com.gaee.engine.ActionPlanner
import com.gaee.engine.DownloadState
import com.gaee.engine.EmbeddingEngine
import com.gaee.engine.ExecutionEngine
import com.gaee.engine.IntentClassifier
import com.gaee.engine.LlmPlanner
import com.gaee.engine.ModelDownloader
import com.gaee.engine.ProactiveAlert
import com.gaee.engine.ProactiveAlertLog
import com.gaee.engine.VoiceListener
import com.gaee.engine.VoiceState
import com.gaee.model.IntentResult
import com.gaee.model.ModelTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed class UiState {
    object Idle : UiState()
    object Listening : UiState()
    object Thinking : UiState()
    data class AwaitingConfirmation(val intent: IntentResult) : UiState()
    data class AwaitingMessage(val promptText: String) : UiState()
    data class AwaitingActionConfirmation(val title: String, val message: String) : UiState()
    data class Error(val message: String) : UiState()
    data class NeedsCloudPermission(val onApprove: () -> Unit, val onDecline: () -> Unit) : UiState()
    data class ModelDownloading(val progressPercent: Int, val fileName: String) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val voiceListener = VoiceListener(application)
    val classifier = IntentClassifier(application)
    val executionEngine = ExecutionEngine(application)

    private val embeddingEngine = EmbeddingEngine(application)
    private val modelDownloader = ModelDownloader(application)
    private val actionCache = ActionCache(application)
    private val llmPlanner = LlmPlanner(classifier.claudeApiKey)
    private val planner = ActionPlanner(actionCache, embeddingEngine, llmPlanner)

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    // Proactive scam-warning history (F2). Kept on its OWN flow, not in _uiState — that single slot
    // is driven by the mic state machine and would clobber/be clobbered by a warning card.
    val scamAlerts: StateFlow<List<ProactiveAlert>> = ProactiveAlertLog.alerts

    private var pendingIntent: IntentResult? = null
    private var pendingTranscript: String = ""
    private var awaitingMessageFor: IntentResult? = null
    // Resolves when the user answers a mid-execution destructive-action popup
    private var pendingActionConfirm: CompletableDeferred<Boolean>? = null
    // Resolves when the user answers a mid-plan clarification question (e.g. ContactResolverTool
    // asking "who is your daughter?"). Separate from awaitingMessageFor: that pattern returns from
    // the coroutine and forces a re-classify + re-run from the top, which would repeat side effects
    // for a question asked mid-plan (e.g. after an app has already launched). This suspends the
    // plan coroutine in place and resumes it at the failing step instead.
    private var pendingUserAnswer: CompletableDeferred<String?>? = null

    init {
        viewModelScope.launch {
            voiceListener.state.collect { voiceState ->
                when (voiceState) {
                    is VoiceState.Listening -> _uiState.value = UiState.Listening
                    is VoiceState.Result -> {
                        // MUST be checked before awaitingMessageFor: a spoken answer to a mid-plan
                        // clarification (e.g. "Priya") must resume the suspended plan in place, not
                        // be re-classified as a fresh command (which would turn "Priya" into a
                        // brand-new "open_app Priya" intent).
                        val answerDeferred = pendingUserAnswer
                        val partial = awaitingMessageFor
                        if (answerDeferred != null) {
                            pendingUserAnswer = null
                            answerDeferred.complete(voiceState.transcript)
                        } else if (partial != null) {
                            awaitingMessageFor = null
                            handleMessageCapture(partial, voiceState.transcript)
                        } else {
                            handleTranscript(voiceState.transcript)
                        }
                    }
                    is VoiceState.Error -> {
                        awaitingMessageFor = null
                        // A mishear must never leave the plan coroutine suspended forever waiting
                        // on an answer that will now never come. No identity check needed here
                        // (unlike requestUserAnswer's finally block): this reads and nulls the
                        // pendingUserAnswer FIELD directly rather than a locally-captured deferred,
                        // so there is no stale-reference risk — whatever is currently pending IS
                        // the one this state change should clear.
                        pendingUserAnswer?.complete(null)
                        pendingUserAnswer = null
                        _uiState.value = UiState.Error(voiceState.message)
                        executionEngine.ttsTool.speak(voiceState.message)
                    }
                    is VoiceState.Idle -> {
                        if (_uiState.value is UiState.Listening || _uiState.value is UiState.AwaitingMessage) {
                            // Same reasoning as the Error branch above — direct field access, no
                            // identity check needed.
                            awaitingMessageFor = null
                            pendingUserAnswer?.complete(null)
                            pendingUserAnswer = null
                            _uiState.value = UiState.Idle
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            modelDownloader.state.collect { state ->
                when (state) {
                    is DownloadState.Downloading ->
                        _uiState.value = UiState.ModelDownloading(state.progressPercent, state.fileName)
                    is DownloadState.Complete -> {
                        embeddingEngine.load()
                        if (_uiState.value is UiState.ModelDownloading) _uiState.value = UiState.Idle
                    }
                    is DownloadState.Error -> {
                        if (_uiState.value is UiState.ModelDownloading) _uiState.value = UiState.Idle
                    }
                    else -> {}
                }
            }
        }

        viewModelScope.launch { initTierAndModel() }
        speakPendingBackgroundResult()
    }

    // A deferred task (SchedulerTool → GaeeBackgroundWorker) may have finished overnight.
    // Speak its result once on the next app open, then clear it.
    private fun speakPendingBackgroundResult() {
        val prefs = getApplication<Application>()
            .getSharedPreferences(com.gaee.engine.GaeeBackgroundWorker.PREFS, 0)
        val pending = prefs.getString(com.gaee.engine.GaeeBackgroundWorker.PENDING_RESULT, null)
        if (!pending.isNullOrBlank()) {
            prefs.edit().remove(com.gaee.engine.GaeeBackgroundWorker.PENDING_RESULT).apply()
            executionEngine.ttsTool.speak(pending)
        }
    }

    private suspend fun initTierAndModel() {
        val tier = classifier.detectTier()
        if (tier == ModelTier.KEYWORD) {
            val prefs = getApplication<Application>().getSharedPreferences("gaee", 0)
            val hasAsked = prefs.getBoolean("cloud_asked", false)
            if (!hasAsked) {
                prefs.edit().putBoolean("cloud_asked", true).apply()
                _uiState.value = UiState.NeedsCloudPermission(
                    onApprove = { classifier.approveCloud(); startModelDownloadIfNeeded() ; _uiState.value = UiState.Idle },
                    onDecline = { classifier.declineCloud(); _uiState.value = UiState.Idle }
                )
                return
            }
        }
        startModelDownloadIfNeeded()
    }

    private fun startModelDownloadIfNeeded() {
        if (embeddingEngine.isReady) return
        if (modelDownloader.filesExist) {
            embeddingEngine.load()
            return
        }
        if (modelDownloader.isOnWifi()) {
            viewModelScope.launch { modelDownloader.download() }
        }
        // Not on WiFi: model will be downloaded next time the user is on WiFi
    }

    fun onMicTap() {
        if (_uiState.value is UiState.Idle || _uiState.value is UiState.Error) {
            executionEngine.ttsTool.stop()
            voiceListener.startListening()
        }
    }

    private fun handleTranscript(transcript: String) {
        pendingTranscript = transcript
        _uiState.value = UiState.Thinking
        viewModelScope.launch {
            val intent = classifier.classify(transcript)

            if (intent.intent in setOf("send_sms", "send_whatsapp") && intent.args["message"].isNullOrBlank()) {
                val name = intent.args["name"] ?: "them"
                val askText = "What would you like to say to $name?"
                awaitingMessageFor = intent
                _uiState.value = UiState.AwaitingMessage(askText)
                executionEngine.ttsTool.speakAndWait(askText)
                voiceListener.startListening()
                return@launch
            }

            if (intent.confirmationRequired) {
                pendingIntent = intent
                _uiState.value = UiState.AwaitingConfirmation(intent)
                executionEngine.ttsTool.speak(intent.speakBefore)
            } else {
                executionEngine.ttsTool.speak(intent.speakBefore)
                runPlan(intent)
            }
        }
    }

    private fun handleMessageCapture(partial: IntentResult, messageTranscript: String) {
        val name = partial.args["name"] ?: "them"
        val completed = partial.copy(
            args = partial.args + mapOf("message" to messageTranscript),
            speakBefore = "I will send a message to $name saying: $messageTranscript. Is that okay?"
        )
        pendingIntent = completed
        _uiState.value = UiState.AwaitingConfirmation(completed)
        executionEngine.ttsTool.speak(completed.speakBefore)
    }

    fun onConfirm() {
        val intent = pendingIntent ?: return
        pendingIntent = null
        _uiState.value = UiState.Thinking
        viewModelScope.launch { runPlan(intent) }
    }

    fun onCancel() {
        pendingIntent = null
        _uiState.value = UiState.Idle
        executionEngine.ttsTool.speak("Cancelled for safety.")
    }

    // Shows the destructive-action popup and suspends the plan until the user answers.
    private suspend fun requestActionConfirm(title: String, message: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingActionConfirm = deferred
        _uiState.value = UiState.AwaitingActionConfirmation(title, message)
        executionEngine.ttsTool.speak(message)
        return deferred.await()
    }

    // Called by the UI when the user taps Yes/No (or dismisses) on the destructive-action popup.
    fun onActionConfirmResult(approved: Boolean) {
        val deferred = pendingActionConfirm ?: return
        pendingActionConfirm = null
        deferred.complete(approved)
    }

    // Asks a generic mid-plan clarification question (e.g. ContactResolverTool's "who is your
    // daughter?") and suspends the plan AT the failing step until the user answers — mirrors
    // requestActionConfirm above, but returns the spoken text instead of a yes/no. Reuses
    // UiState.AwaitingMessage so no new UiState class or MainActivity change is needed.
    // withTimeoutOrNull is a second deadlock backstop on top of the VoiceState.Error/Idle
    // handlers, which already complete this deferred with null on a mishear or cancel.
    private suspend fun requestUserAnswer(question: String): String? {
        val deferred = CompletableDeferred<String?>()
        pendingUserAnswer = deferred
        _uiState.value = UiState.AwaitingMessage(question)
        executionEngine.ttsTool.speakAndWait(question)
        voiceListener.startListening()
        try {
            return withTimeoutOrNull(20_000) { deferred.await() }
        } finally {
            // Identity check, not a blind null-out: if a NEWER deferred is already installed by
            // the time we get here, some other in-flight caller owns pendingUserAnswer now and
            // this stale reference must not clobber it. Without this check, a 20s timeout firing
            // late would null out the wrong deferred, and the VoiceState.Result handler's
            // `pendingUserAnswer != null` check would then treat the user's NEXT spoken command
            // as a leftover answer, complete it into a deferred nobody is awaiting, and discard
            // it silently — leaving the UI stuck on Listening (mic disabled) forever.
            if (pendingUserAnswer === deferred) {
                pendingUserAnswer = null
                // The recogniser session may still be live past the 20s window — stop it so it
                // doesn't linger and so a later onMicTap can start a clean one.
                voiceListener.stopListening()
            }
        }
    }

    private suspend fun runPlan(intent: IntentResult) {
        val steps = planner.plan(intent, pendingTranscript)
        // Pass the goal + planner so ExecutionEngine can re-plan from the live screen if a UI step fails,
        // a confirmer so irreversible taps require the user's explicit (double) yes, and an asker so a
        // tool can pause the plan mid-step for a clarification (e.g. "who is your daughter?").
        val results = executionEngine.execute(steps, intent, llmPlanner, ::requestActionConfirm, ::requestUserAnswer)
        val success = results.all { it.success }
        planner.lastCacheHitId?.let { id -> actionCache.recordOutcome(id, success) }
        _uiState.value = UiState.Idle
        voiceListener.reset()
    }

    override fun onCleared() {
        super.onCleared()
        pendingUserAnswer?.complete(null)
        pendingUserAnswer = null
        pendingActionConfirm?.complete(false)
        pendingActionConfirm = null
        voiceListener.destroy()
        executionEngine.shutdown()
        embeddingEngine.shutdown()
    }
}
