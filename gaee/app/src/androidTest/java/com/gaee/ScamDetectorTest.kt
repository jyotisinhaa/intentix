package com.gaee

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaee.engine.ScamDetector
import com.gaee.engine.ScamDetector.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 3 F1: on-device scam heuristics. Pure logic — no device state needed. */
@RunWith(AndroidJUnit4::class)
class ScamDetectorTest {

    @Test
    fun bankPhishingWithLink_isDangerous() {
        val msg = "Dear customer, your SBI account is blocked. Verify your KYC now: http://bit.ly/xy2z"
        val r = ScamDetector.assess(sender = "VM-SBIINB", text = msg)
        assertEquals(Verdict.DANGEROUS, r.verdict)
    }

    @Test
    fun lotteryBaitFromUnknown_isFlagged() {
        val msg = "Congratulations you won a lottery of 25 lakh! Claim your prize now."
        val r = ScamDetector.assess(sender = "+911234567890", text = msg)
        assertTrue(r.verdict == Verdict.DANGEROUS || r.verdict == Verdict.SUSPICIOUS)
    }

    @Test
    fun otpShareRequestWithLink_isDangerous() {
        val msg = "Your account will be suspended. Share your OTP and click www.hdfc-verify.xyz"
        val r = ScamDetector.assess(sender = "unknown", text = msg)
        assertEquals(Verdict.DANGEROUS, r.verdict)
    }

    @Test
    fun normalMessageFromContact_isSafe() {
        val msg = "Hi Ma, I'll reach home by 8pm. Should I bring vegetables?"
        val r = ScamDetector.assess(sender = "Priya", text = msg, senderIsKnownContact = true)
        assertEquals(Verdict.SAFE, r.verdict)
    }

    @Test
    fun legitOtpAlone_isNotDangerous() {
        // A real bank OTP delivery (no link, no ask to share) should not scream "scam".
        val msg = "123456 is your OTP for login. Valid for 10 minutes. Do not share with anyone."
        val r = ScamDetector.assess(sender = "AD-HDFCBK", text = msg)
        assertTrue(r.verdict != Verdict.DANGEROUS)
    }

    @Test
    fun spokenWarning_namesReasonAndSafeAdvice() {
        val r = ScamDetector.assess(sender = "unknown", text = "You won a prize! Click http://1.2.3.4/win")
        val warning = ScamDetector.spokenWarning(r)
        assertTrue(warning.contains("scam"))
        assertTrue(warning.lowercase().contains("do not tap"))
    }
}
