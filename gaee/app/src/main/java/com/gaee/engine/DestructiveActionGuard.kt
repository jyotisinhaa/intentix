package com.gaee.engine

/**
 * Flags taps that are irreversible / destructive (delete, remove, unsubscribe, …). ExecutionEngine
 * double-confirms these with the user through a popup before tapping, and defaults to NOT doing them
 * — because there is no undo. Pure/testable.
 */
object DestructiveActionGuard {

    private val destructiveKeywords = listOf(
        "delete", "remove", "unsubscribe", "deactivate", "uninstall",
        "erase", "permanently delete", "delete forever", "delete account",
        "close account", "empty trash", "block", "unfriend"
    )

    fun isDestructive(target: String?): Boolean {
        if (target.isNullOrBlank()) return false
        val t = target.lowercase()
        return destructiveKeywords.any { t.contains(it) }
    }
}
