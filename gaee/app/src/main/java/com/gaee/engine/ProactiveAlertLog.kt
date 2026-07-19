package com.gaee.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Priority of a proactive alert — drives how loudly [ProactiveNotifier] surfaces it. */
enum class AlertLevel { DANGEROUS, IMPORTANT, SILENT }

/** One proactive alert the assistant raised on its own (e.g. a flagged scam message). */
data class ProactiveAlert(
    val app: String,
    val message: String,
    val timeMs: Long,
    val level: AlertLevel
)

/**
 * In-memory history of proactive alerts, newest-first and capped. Backs the in-app "warnings" card
 * so a user (or caregiver) can review what was flagged. Deliberately RAM-only — matching the privacy
 * posture of `GaeeNotificationService` (notifications are never written to disk).
 */
object ProactiveAlertLog {

    private const val MAX = 20

    private val _alerts = MutableStateFlow<List<ProactiveAlert>>(emptyList())
    val alerts: StateFlow<List<ProactiveAlert>> = _alerts

    @Synchronized
    fun add(alert: ProactiveAlert) {
        _alerts.value = (listOf(alert) + _alerts.value).take(MAX)
    }

    @Synchronized
    fun clear() {
        _alerts.value = emptyList()
    }
}
