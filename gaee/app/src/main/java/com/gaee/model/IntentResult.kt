package com.gaee.model

data class IntentResult(
    val intent: String,
    val confidence: Float,
    val args: Map<String, String>,
    val confirmationRequired: Boolean,
    val speakBefore: String,
    val unknown: Boolean = false
)
