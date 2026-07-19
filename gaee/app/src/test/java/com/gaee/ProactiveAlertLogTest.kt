package com.gaee

import com.gaee.engine.AlertLevel
import com.gaee.engine.ProactiveAlert
import com.gaee.engine.ProactiveAlertLog
import com.gaee.engine.ProactiveNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Phase 3 F2 — pure JVM tests for the alert history store and the never-nag priority rules. */
class ProactiveAlertLogTest {

    @Before
    fun reset() = ProactiveAlertLog.clear()

    @Test
    fun log_keepsNewestFirst_andCapsAt20() {
        for (i in 1..25) {
            ProactiveAlertLog.add(ProactiveAlert("App$i", "msg $i", i.toLong(), AlertLevel.DANGEROUS))
        }
        val alerts = ProactiveAlertLog.alerts.value
        assertEquals("log must cap at 20", 20, alerts.size)
        assertEquals("newest is first", "msg 25", alerts.first().message)
        assertEquals("oldest kept is #6", "msg 6", alerts.last().message)
    }

    @Test
    fun priorityRules_dangerousSpeaksAndNotifies() {
        val d = ProactiveNotifier.deliveryFor(AlertLevel.DANGEROUS)
        assertTrue(d.speak)
        assertTrue(d.notify)
    }

    @Test
    fun priorityRules_importantNotifiesButSilent() {
        val d = ProactiveNotifier.deliveryFor(AlertLevel.IMPORTANT)
        assertFalse("important must not speak (avoid nagging)", d.speak)
        assertTrue(d.notify)
    }

    @Test
    fun priorityRules_silentLogsOnly() {
        val d = ProactiveNotifier.deliveryFor(AlertLevel.SILENT)
        assertFalse(d.speak)
        assertFalse(d.notify)
    }
}
