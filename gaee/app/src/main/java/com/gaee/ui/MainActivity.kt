package com.gaee.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gaee.R
import com.gaee.engine.ProactiveAlert
import com.gaee.service.GaeeNotificationService
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var btnMic: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var switchScamProtection: SwitchMaterial
    private lateinit var cardScamHistory: MaterialCardView
    private lateinit var tvScamHistory: TextView
    private var actionDialog: androidx.appcompat.app.AlertDialog? = null

    private val requiredPermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.CAMERA)
        }
        // Android 13+: heads-up scam banners (F2) need this granted or they are silently dropped.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions resolved; app proceeds regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnMic = findViewById(R.id.btn_mic)
        tvStatus = findViewById(R.id.tv_status)
        switchScamProtection = findViewById(R.id.switch_scam_protection)
        cardScamHistory = findViewById(R.id.card_scam_history)
        tvScamHistory = findViewById(R.id.tv_scam_history)

        btnMic.setOnClickListener { viewModel.onMicTap() }
        setupScamProtectionToggle()

        requestMissingPermissions()
        checkOverlayPermission()

        lifecycleScope.launch {
            viewModel.uiState.collect { state -> renderState(state) }
        }
        lifecycleScope.launch {
            viewModel.scamAlerts.collect { alerts -> renderScamHistory(alerts) }
        }
    }

    // F2: the in-app record of proactive scam warnings. Its own surface (separate from the mic
    // uiState), shown as a tappable card that opens the full list.
    private fun renderScamHistory(alerts: List<ProactiveAlert>) {
        if (alerts.isEmpty()) {
            cardScamHistory.visibility = android.view.View.GONE
            return
        }
        val count = alerts.size
        val noun = if (count == 1) "scam warning" else "scam warnings"
        tvScamHistory.text = "⚠ $count $noun. Tap to see."
        cardScamHistory.visibility = android.view.View.VISIBLE
        cardScamHistory.setOnClickListener { showScamHistoryDialog(alerts) }
    }

    private fun showScamHistoryDialog(alerts: List<ProactiveAlert>) {
        val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)
        val body = alerts.joinToString("\n\n") { a ->
            "${timeFmt.format(Date(a.timeMs))} — ${a.app}\n${a.message}"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Scam warnings")
            .setMessage(body)
            .setPositiveButton("CLOSE", null)
            .show()
    }

    private fun renderState(state: UiState) {
        when (state) {
            is UiState.Idle -> {
                actionDialog?.dismiss()
                actionDialog = null
                tvStatus.text = "Tap to speak"
                btnMic.isEnabled = true
                btnMic.alpha = 1f
            }
            is UiState.AwaitingActionConfirmation -> {
                tvStatus.text = "Waiting for your answer..."
                showActionConfirmDialog(state)
            }
            is UiState.Listening -> {
                tvStatus.text = "Listening..."
                btnMic.isEnabled = false
                btnMic.alpha = 0.6f
            }
            is UiState.Thinking -> {
                tvStatus.text = "Thinking..."
                btnMic.isEnabled = false
                btnMic.alpha = 0.6f
            }
            is UiState.AwaitingConfirmation -> {
                tvStatus.text = "Waiting for your answer..."
                showConfirmationDialog(state)
            }
            is UiState.AwaitingMessage -> {
                tvStatus.text = "Listening for your message..."
                btnMic.isEnabled = false
                btnMic.alpha = 0.6f
            }
            is UiState.Error -> {
                tvStatus.text = state.message
                btnMic.isEnabled = true
                btnMic.alpha = 1f
            }
            is UiState.NeedsCloudPermission -> {
                showCloudPermissionDialog(state.onApprove, state.onDecline)
            }
            is UiState.ModelDownloading -> {
                tvStatus.text = "Downloading AI model: ${state.progressPercent}%\n${state.fileName}"
                btnMic.isEnabled = false
                btnMic.alpha = 0.4f
            }
        }
    }

    // "Smart scam protection" opt-in (Phase 3 F1, decision 2A). Off by default; turning it on lets
    // the phone check doubtful messages online (secrets hidden first) so it catches cleverly-worded
    // scams the on-device filter would miss. Enabling asks for a plain-language confirmation.
    private fun setupScamProtectionToggle() {
        switchScamProtection.isChecked = GaeeNotificationService.isCloudScreeningEnabled(this)
        switchScamProtection.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Smart scam protection")
                    .setMessage(
                        "When a message looks unsafe, your phone will check it on the internet to " +
                        "warn you about scams. Secret numbers like your PIN or OTP are always hidden " +
                        "first. You can turn this off any time."
                    )
                    .setPositiveButton("TURN ON") { _, _ ->
                        GaeeNotificationService.setCloudScreeningEnabled(this, true)
                    }
                    .setNegativeButton("CANCEL") { _, _ -> switchScamProtection.isChecked = false }
                    .setOnCancelListener { switchScamProtection.isChecked = false }
                    .setCancelable(true)
                    .show()
            } else {
                GaeeNotificationService.setCloudScreeningEnabled(this, false)
            }
        }
    }

    private fun showConfirmationDialog(state: UiState.AwaitingConfirmation) {
        val existing = supportFragmentManager.findFragmentByTag("confirm")
        if (existing != null) return

        ConfirmationDialog.newInstance(
            speakText = state.intent.speakBefore,
            onConfirm = { viewModel.onConfirm() },
            onCancel = { viewModel.onCancel() }
        ).show(supportFragmentManager, "confirm")
    }

    // Double-confirm popup for irreversible taps. Defaults to reject: back / outside tap = No.
    private fun showActionConfirmDialog(state: UiState.AwaitingActionConfirmation) {
        actionDialog?.dismiss()
        actionDialog = MaterialAlertDialogBuilder(this)
            .setTitle(state.title)
            .setMessage(state.message)
            .setPositiveButton("YES, DO IT") { _, _ -> viewModel.onActionConfirmResult(true) }
            .setNegativeButton("NO, STOP") { _, _ -> viewModel.onActionConfirmResult(false) }
            .setCancelable(true)
            .setOnCancelListener { viewModel.onActionConfirmResult(false) }
            .show()
    }

    private fun showCloudPermissionDialog(onApprove: () -> Unit, onDecline: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Your phone needs a little help")
            .setMessage(
                "To understand what you say, this app uses a smart assistant. " +
                "Your phone works best when it connects to the internet to use one. " +
                "Your voice is sent securely and is never stored. " +
                "You can turn this off any time."
            )
            .setPositiveButton("YES, THAT IS FINE") { _, _ -> onApprove() }
            .setNegativeButton("NO THANKS") { _, _ -> onDecline() }
            .setCancelable(false)
            .show()
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("One more step")
                .setMessage(
                    "To show you confirmation before making calls or sending messages, " +
                    "this app needs permission to display over other apps. " +
                    "Please enable it in the next screen."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = android.content.Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("Skip") { _, _ -> }
                .show()
        }
    }
}
