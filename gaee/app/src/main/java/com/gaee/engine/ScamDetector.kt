package com.gaee.engine

/**
 * Screens an incoming message (SMS, WhatsApp, etc.) and decides whether it looks like a scam
 * aimed at a vulnerable / elderly user. This is Tier 1 of the Phase 3 scam-detection feature:
 * cheap, instant, fully on-device heuristics that run on every message so nothing leaves the
 * phone for the obvious cases.
 *
 * Design mirrors [SensitiveScreenGuard]: [assess] is a pure function so it can be reasoned about
 * and unit-tested without a device. It returns a [Verdict] plus a short human reason used to build
 * the spoken warning.
 *
 * The three verdicts map onto the two-tier plan:
 *  - [Verdict.SAFE]       → do nothing, never touched the cloud.
 *  - [Verdict.SUSPICIOUS] → one weak signal; a future Tier-2 cloud check (Claude) can adjudicate.
 *  - [Verdict.DANGEROUS]  → an unambiguous scam pattern; warn the user out loud right now.
 *
 * This is a best-effort filter, NOT a guarantee. It pairs with safe blanket advice ("never share
 * your PIN or tap links from unknown senders") that is correct regardless of the verdict.
 */
object ScamDetector {

    enum class Verdict { SAFE, SUSPICIOUS, DANGEROUS }

    data class Result(
        val verdict: Verdict,
        val score: Int,
        val reasons: List<String>
    )

    // --- Signal weights ---------------------------------------------------------------------
    // "strong" signals are near-certain scam markers; "moderate" ones are suggestive on their own.
    private const val STRONG = 2
    private const val MODERATE = 1

    // Asking the user to hand over a secret. No legitimate SMS ever needs this.
    private val credentialAskWords = listOf(
        "share your otp", "share the otp", "send otp", "send your otp", "tell your otp",
        "share your pin", "enter your pin", "share your password", "share your cvv",
        "verify your card", "card number", "confirm your password", "update your kyc",
        "verify your account", "share your account"
    )

    // Prize / reward bait — the classic lottery hook.
    private val rewardBaitWords = listOf(
        "you won", "you have won", "congratulations you", "lottery", "lucky winner",
        "claim your prize", "claim your reward", "free gift", "cash prize", "you are selected",
        "won a prize", "gift card", "redeem now"
    )

    // Manufactured urgency / threat — scams create panic to short-circuit thinking.
    private val urgencyThreatWords = listOf(
        "account is blocked", "account has been blocked", "account will be blocked",
        "account suspended", "account is suspended", "will be suspended", "account deactivated",
        "act now", "immediately", "within 24 hours", "within 24hrs", "urgent action",
        "legal action", "your account will be closed", "final warning", "last chance",
        "avoid suspension", "failure to comply"
    )

    // UPI "collect request" fraud — approving one PULLS money OUT of your account.
    private val paymentCollectWords = listOf(
        "collect request", "approve the request", "approve this request", "pay ₹1 to receive",
        "pay rs 1 to receive", "accept the payment request", "approve to receive"
    )

    // Impersonation of a bank / government / tax authority.
    private val impersonationWords = listOf(
        "this is sbi", "this is hdfc", "this is icici", "this is axis bank",
        "income tax", "govt of india", "government of india", "kyc verification",
        "your bank", "customer care", "bank official"
    )

    // A message that merely DELIVERS a one-time code (as opposed to asking the user to share one).
    // Genuine bank/app OTP texts look like this — used by the legit-OTP false-positive guard below.
    private val otpDeliveryWords = listOf(
        "is your otp", "your otp is", "otp is ", "otp for ", "otp to ", "use otp",
        "verification code is", "your verification code", "is your verification code",
        "one time password is", "is your one time password", "security code is",
        "is your login code", "login code is", "your login code"
    )

    // A URL anywhere in the body (phishing payload).
    private val urlPattern = Regex("""(?i)\b(?:https?://|www\.)\S+|\b[a-z0-9-]+\.(?:com|net|org|xyz|info|top|link|click|in|co)\b""")

    // High-risk URL shapes: link shorteners, raw IP addresses.
    private val shortenerPattern = Regex("""(?i)\b(?:bit\.ly|tinyurl\.com|t\.co|goo\.gl|ow\.ly|is\.gd|buff\.ly|rebrand\.ly|cutt\.ly|tiny\.cc)\b""")
    private val rawIpUrlPattern = Regex("""https?://\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")

    // A phone number embedded in the message body asking the user to "call this number".
    private val callbackNumberPattern = Regex("""(?i)\b(?:call|dial|contact|whatsapp)\b[^\n]{0,30}?(?:\+?\d[\d\s\-]{7,}\d)""")

    /**
     * @param sender      display name / number the message came from (null or blank = unknown).
     * @param text        the message body (title + text may be concatenated by the caller).
     * @param senderIsKnownContact whether [sender] resolves to a saved contact. Unknown senders
     *                    make every other signal more damning.
     */
    fun assess(sender: String?, text: String, senderIsKnownContact: Boolean = false): Result {
        if (text.isBlank()) return Result(Verdict.SAFE, 0, emptyList())

        // Guard 1 — trusted saved contact. Messages from someone in the address book are not
        // screened as scams. This kills the single biggest source of false alarms (family texts),
        // which matters more than the rare case of a scam forwarded by a compromised contact.
        if (senderIsKnownContact) return Result(Verdict.SAFE, 0, emptyList())

        val lower = text.lowercase()
        val credentialAsk = credentialAskWords.any { lower.contains(it) }
        val hasLink = urlPattern.containsMatchIn(text)
        val riskyLink = shortenerPattern.containsMatchIn(lower) || rawIpUrlPattern.containsMatchIn(lower)

        // Guard 2 — legit OTP delivery. A message that merely DELIVERS a one-time code, with no link
        // and no request to share/verify anything, is a genuine bank/app text — never a scam. Without
        // this, real OTP messages (which contain scary words and long digits) get over-flagged.
        val isOtpDelivery = otpDeliveryWords.any { lower.contains(it) }
        if (isOtpDelivery && !hasLink && !credentialAsk) {
            return Result(Verdict.SAFE, 0, listOf("this only delivers a one-time code and does not ask you to share it"))
        }

        var score = 0
        val reasons = mutableListOf<String>()

        if (credentialAsk) { score += STRONG; reasons += "it asks for a PIN, OTP, or password" }

        if (rewardBaitWords.any { lower.contains(it) }) {
            score += STRONG; reasons += "it claims you won a prize or lottery"
        }
        if (paymentCollectWords.any { lower.contains(it) }) {
            score += STRONG; reasons += "it asks you to approve a payment request"
        }
        if (urgencyThreatWords.any { lower.contains(it) }) {
            score += MODERATE; reasons += "it tries to rush or scare you"
        }
        if (impersonationWords.any { lower.contains(it) }) {
            score += MODERATE; reasons += "it pretends to be your bank or the government"
        }

        when {
            riskyLink -> { score += STRONG; reasons += "it contains a suspicious link" }
            hasLink -> { score += MODERATE; reasons += "it contains a link" }
        }

        if (callbackNumberPattern.containsMatchIn(text)) {
            score += MODERATE; reasons += "it wants you to call an unknown number"
        }

        // Unknown / unverified sender amplifies risk (known contacts already returned SAFE above).
        if (score > 0) score += MODERATE

        // Decision — lean toward ESCALATE, not DECLARE. Only unambiguous patterns are called
        // DANGEROUS on-device (the credential-ask + link combo is the classic phishing shape, and a
        // single strong signal already scores 3 with the sender amplifier). Everything merely doubtful
        // is left SUSPICIOUS so Tier 2 (Claude) adjudicates it before we ever alarm the user — this is
        // what keeps false alarms down without missing real scams.
        val verdict = when {
            score >= 3 || (credentialAsk && hasLink) -> Verdict.DANGEROUS
            score >= 1 -> Verdict.SUSPICIOUS
            else -> Verdict.SAFE
        }
        return Result(verdict, score, reasons)
    }

    /**
     * A short, TTS-friendly spoken warning for a flagged message. Names the top reason so the user
     * understands *why*, then gives the always-safe instruction.
     */
    fun spokenWarning(result: Result): String {
        val reason = result.reasons.firstOrNull()
        val lead = when (result.verdict) {
            Verdict.DANGEROUS -> "Careful — this message looks like a scam"
            Verdict.SUSPICIOUS -> "Please be careful — this message may not be genuine"
            Verdict.SAFE -> return ""
        }
        val because = if (reason != null) ", because $reason" else ""
        return "$lead$because. Do not tap any links, and never share your PIN, OTP, or password."
    }
}
