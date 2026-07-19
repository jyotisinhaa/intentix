package com.gaee

import com.gaee.engine.ScamDetector
import com.gaee.engine.ScamDetector.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 3 F1 — measured scam/ham corpus. Turns "rarely miss a scam / rarely flag a real message"
 * into a number instead of a claim, over ~40 India-context labelled messages. Runs on the JVM
 * (`gradlew testDebugUnitTest`) because [ScamDetector.assess] is pure — no device needed.
 *
 * Two hard requirements on the on-device Tier 1:
 *  - NO MISS: no scam may be judged SAFE (SUSPICIOUS is fine — it escalates to Tier 2).
 *  - NO FALSE ALARM: no genuine message may be judged DANGEROUS (SUSPICIOUS is fine — Tier 2 clears
 *    it, and offline it stays silent).
 * SUSPICIOUS is the safe middle for both directions; that is the point of the two-tier design.
 */
class ScamCorpusTest {

    private data class Case(val text: String, val isScam: Boolean, val knownContact: Boolean = false)

    private val corpus = listOf(
        // --- Scams (must NOT be SAFE) -------------------------------------------------------
        Case("Dear customer, your SBI account is blocked. Complete KYC now: http://bit.ly/kyc123", true),
        Case("Congratulations! You have won Rs 25,00,000 in the KBC lottery. Claim your prize now.", true),
        Case("URGENT: Your electricity will be disconnected tonight. Pay now: http://1.2.3.4/pay", true),
        Case("Your account will be suspended. Verify your card immediately at www.hdfc-secure.xyz", true),
        Case("This is Income Tax Department. You have a pending refund. Share your account number to receive it.", true),
        Case("You have a parcel waiting. Pay Rs 50 customs fee: http://tinyurl.com/parcel99", true),
        Case("Please approve the request of Rs 9999 to receive your refund.", true),
        Case("Your KYC verification is pending. Update immediately or account will be blocked.", true),
        Case("Dear user, share your OTP to confirm your booking.", true),
        Case("Congratulations, you are selected for a free gift. Click here: bit.ly/freegift", true),
        Case("Final warning: legal action will be taken. Call this number now +919812345678", true),
        Case("Your Paytm KYC is suspended. Verify your account now to avoid suspension.", true),
        Case("Hello, I am calling from your bank. There is a problem with your card. Please share your CVV.", true),
        Case("You won a cash prize of 10 lakh. Send your bank details to claim.", true),
        Case("Your account is blocked due to suspicious activity. Reactivate: http://icici-verify.top/login", true),
        Case("Your debit card is blocked. Verify your card here: http://sbi.co/upd", true),
        Case("Digital arrest warrant issued against you. Call immediately to avoid arrest +911122334455", true),
        Case("Your loan is approved. Pay processing fee via this UPI collect request to receive funds.", true),
        Case("Verify your Netflix account or it will be cancelled. www.netflix-billing.info", true),
        Case("Update your KYC to continue using your wallet. Failure to comply will block your account.", true),

        // --- Ham (must NOT be DANGEROUS) ----------------------------------------------------
        Case("123456 is your OTP for HDFC Bank login. Valid for 10 minutes. Do not share with anyone.", false),
        Case("Your Amazon order has been shipped and will arrive tomorrow.", false),
        Case("Your electricity bill of Rs 850 is due on 25th. Thank you.", false),
        Case("OTP for your Swiggy order is 4821. Enjoy your meal!", false),
        Case("Your appointment with Dr. Sharma is confirmed for Monday 10 AM.", false),
        Case("Reminder: Your recharge of Rs 299 expires in 2 days. Recharge to continue.", false),
        Case("Track your parcel here: www.bluedart.com/track", false),
        Case("Your verification code is 558213. Enter it to log in.", false),
        Case("Hi, this is Dr. Patel's clinic. Your report is ready for pickup.", false),
        Case("Your monthly statement is now available in the app.", false),
        Case("Sale is live! Get 50% off on all items today only. Shop now.", false),
        Case("Your food order will be delivered in 30 minutes.", false),
        Case("Payment of Rs 1200 received successfully. Thank you for your purchase.", false),
        Case("Your OTP is 9087. Use OTP to complete your transaction. Never share it.", false),
        Case("Meeting rescheduled to 3 PM tomorrow. Please confirm.", false),
        Case("Your gas cylinder booking is confirmed. Delivery on 22nd.", false),
        Case("Congratulations on your new account! Welcome to our bank.", false),
        Case("Your train PNR 1234567 is confirmed. Seat B2-45.", false),
        Case("Doctor appointment reminder: tomorrow 11 AM at City Hospital.", false),
        // Known contact forwarding a link — the contact guard must keep it off the alarm path.
        Case("Ma, check out this recipe: www.cooking.com/recipe", false, knownContact = true),
    )

    @Test
    fun corpus_noMissedScams_andNoFalseAlarms() {
        val missedScams = mutableListOf<String>()
        val falseAlarms = mutableListOf<String>()

        for (c in corpus) {
            val v = ScamDetector.assess(sender = "unknown", text = c.text, senderIsKnownContact = c.knownContact).verdict
            if (c.isScam && v == Verdict.SAFE) missedScams += c.text
            if (!c.isScam && v == Verdict.DANGEROUS) falseAlarms += c.text
        }

        val scams = corpus.count { it.isScam }
        val ham = corpus.count { !it.isScam }
        println("ScamCorpus: scams=$scams ham=$ham missed=${missedScams.size} falseAlarms=${falseAlarms.size}")
        missedScams.forEach { println("MISSED SCAM: $it") }
        falseAlarms.forEach { println("FALSE ALARM: $it") }

        assertEquals("Scams judged SAFE (missed): $missedScams", 0, missedScams.size)
        assertEquals("Genuine messages judged DANGEROUS (false alarm): $falseAlarms", 0, falseAlarms.size)
    }
}
