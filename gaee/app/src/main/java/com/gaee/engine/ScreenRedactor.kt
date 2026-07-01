package com.gaee.engine

/**
 * Strips sensitive data out of screen text (and any screen-harvested context) before it is
 * sent off-device to the cloud re-planner. The re-planner only needs button/label text to
 * navigate, so hiding numbers and secrets costs it nothing while protecting the user's
 * OTPs, card/account numbers, balances, and passwords.
 *
 * This is a best-effort filter, not a security boundary — pair it with keeping sensitive
 * flows (banking/payment apps) off the autonomous path entirely.
 */
object ScreenRedactor {

    private const val PLACEHOLDER = "[hidden]"

    // A secret value that directly follows a sensitive label. Keeps the label (so the planner
    // still knows what the field is) but hides the value. The value is a single non-whitespace
    // token (max 40 chars) so we never swallow trailing button labels or wrap onto other nodes.
    private val labeledSecret = Regex(
        """(?i)\b(password|passcode|pass\s*code|pin|otp|o\.t\.p|cvv|cvc|one[-\s]?time\s*(?:code|password|pin)?|security\s*code|verification\s*code|account\s*(?:number|no)|card\s*(?:number|no))\b[ \t]*[:\-]?[ \t]*(\S{2,40})"""
    )

    // Card-like numbers: 13–19 digits, optionally grouped with spaces or dashes.
    private val cardLike = Regex("""\b(?:\d[ \-]?){13,19}\b""")

    // Any standalone run of 4+ digits — covers OTPs, PINs, account numbers, balances, amounts.
    private val longDigits = Regex("""\b\d{4,}\b""")

    fun redact(text: String?): String {
        if (text.isNullOrBlank()) return text.orEmpty()
        var t = labeledSecret.replace(text) { m -> "${m.groupValues[1]}: $PLACEHOLDER" }
        t = cardLike.replace(t, PLACEHOLDER)
        t = longDigits.replace(t, PLACEHOLDER)
        return t
    }
}
