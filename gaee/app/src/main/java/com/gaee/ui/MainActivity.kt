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
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var btnMic: MaterialButton
    private lateinit var tvStatus: TextView

    private val requiredPermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.CAMERA)
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

        btnMic.setOnClickListener { viewModel.onMicTap() }

        requestMissingPermissions()
        checkOverlayPermission()

        lifecycleScope.launch {
            viewModel.uiState.collect { state -> renderState(state) }
        }
    }

    private fun renderState(state: UiState) {
        when (state) {
            is UiState.Idle -> {
                tvStatus.text = "Tap to speak"
                btnMic.isEnabled = true
                btnMic.alpha = 1f
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

    private fun showConfirmationDialog(state: UiState.AwaitingConfirmation) {
        val existing = supportFragmentManager.findFragmentByTag("confirm")
        if (existing != null) return

        ConfirmationDialog.newInstance(
            speakText = state.intent.speakBefore,
            onConfirm = { viewModel.onConfirm() },
            onCancel = { viewModel.onCancel() }
        ).show(supportFragmentManager, "confirm")
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
