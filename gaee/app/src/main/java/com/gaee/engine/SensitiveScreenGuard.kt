package com.gaee.engine

/**
 * Decides whether the current screen is too sensitive for the assistant to operate on — i.e. a
 * PIN / password / OTP entry or a payment confirmation. On such screens GAEE stops touching the
 * UI and coaches the user to finish the step themselves (the human always closes the loop on money
 * and secrets). Detection uses two signals: a known payment/bank app, OR sensitive fields/words on
 * screen.
 *
 * [assess] is pure so it can be reasoned about and unit-tested without a device.
 */
object SensitiveScreenGuard {

    enum class Kind { NONE, CREDENTIAL, PAYMENT }

    // Package-name prefixes of common payment / bank apps. Extend as needed.
    private val financialPackages = listOf(
        "com.phonepe", "com.google.android.apps.nbu.paisa",      // PhonePe, Google Pay
        "net.one97.paytm", "in.org.npci.upiapp",                 // Paytm, BHIM
        "com.mobikwik", "com.freecharge",
        "com.sbi", "com.icicibank", "com.csam.icici", "com.snapwork.hdfc",
        "com.msf.kbank", "com.bankofbaroda", "com.axis"
    )

    private val credentialKeywords = listOf(
        "upi pin", "enter pin", "atm pin", "mpin", "m-pin", "otp",
        "one time password", "one-time password", "cvv", "cvc",
        "card number", "enter password"
    )

    private val paymentKeywords = listOf(
        "proceed to pay", "confirm payment", "pay now", "make payment",
        "place order", "confirm order", "complete purchase", "pay ₹", "pay rs"
    )

    fun assess(packageName: String?, screenText: String, hasPasswordField: Boolean): Kind {
        val lower = screenText.lowercase()
        if (hasPasswordField || credentialKeywords.any { lower.contains(it) }) return Kind.CREDENTIAL

        val inFinancialApp = packageName != null && financialPackages.any { packageName.startsWith(it) }
        val paymentCue = paymentKeywords.any { lower.contains(it) }
        // Payment if an explicit pay action shows, or we're in a money app showing an amount to pay.
        if (paymentCue || (inFinancialApp && (lower.contains("₹") || lower.contains("pay")))) {
            return Kind.PAYMENT
        }
        return Kind.NONE
    }

    fun guidance(kind: Kind): String = when (kind) {
        Kind.CREDENTIAL -> "This screen asks for a PIN or password. For your safety, please type it " +
            "in yourself — I won't touch it. Tap the microphone if you need me afterwards."
        Kind.PAYMENT -> "This looks like a payment screen. For your safety, please check the details " +
            "and finish the payment yourself. Tap the microphone if you need me afterwards."
        Kind.NONE -> ""
    }
}
