package com.gaee.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.gaee.R
import com.google.android.material.button.MaterialButton
import java.util.Locale

class ConfirmationDialog : DialogFragment() {

    private var onConfirm: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private var speakText: String = ""
    private var countDownTimer: CountDownTimer? = null
    private var voiceRecognizer: SpeechRecognizer? = null

    companion object {
        fun newInstance(
            speakText: String,
            onConfirm: () -> Unit,
            onCancel: () -> Unit
        ): ConfirmationDialog {
            return ConfirmationDialog().apply {
                this.speakText = speakText
                this.onConfirm = onConfirm
                this.onCancel = onCancel
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_GAEE_FullscreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_confirmation, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvPrompt = view.findViewById<TextView>(R.id.tv_confirmation_prompt)
        val btnYes = view.findViewById<MaterialButton>(R.id.btn_yes)
        val btnNo = view.findViewById<MaterialButton>(R.id.btn_no)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_countdown)
        val tvCountdown = view.findViewById<TextView>(R.id.tv_countdown)

        tvPrompt.text = speakText

        btnYes.setOnClickListener { confirm() }
        btnNo.setOnClickListener { cancel() }

        startCountdown(progressBar, tvCountdown)
        startVoiceConfirmation()
    }

    private fun startCountdown(progressBar: ProgressBar, tvCountdown: TextView) {
        progressBar.max = 5000
        progressBar.progress = 5000

        countDownTimer = object : CountDownTimer(5000, 100) {
            override fun onTick(millisUntilFinished: Long) {
                progressBar.progress = millisUntilFinished.toInt()
                tvCountdown.text = "${(millisUntilFinished / 1000) + 1}"
            }
            override fun onFinish() {
                cancel()
            }
        }.start()
    }

    private fun startVoiceConfirmation() {
        val ctx = context ?: return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return

        voiceRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
        voiceRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val match = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase()?.trim() ?: return
                when {
                    match.contains("yes") || match.contains("okay") || match.contains("sure") || match.contains("yeah") -> confirm()
                    match.contains("no") || match.contains("cancel") || match.contains("stop") || match.contains("wait") -> cancel()
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        voiceRecognizer?.startListening(intent)
    }

    private fun confirm() {
        countDownTimer?.cancel()
        voiceRecognizer?.destroy()
        onConfirm?.invoke()
        dismissAllowingStateLoss()
    }

    private fun cancel() {
        countDownTimer?.cancel()
        voiceRecognizer?.destroy()
        onCancel?.invoke()
        dismissAllowingStateLoss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countDownTimer?.cancel()
        voiceRecognizer?.destroy()
    }
}
