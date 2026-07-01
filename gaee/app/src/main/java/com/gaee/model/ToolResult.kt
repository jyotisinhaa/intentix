package com.gaee.model

data class ToolResult(
    val success: Boolean,
    val speakAfter: String,
    val error: String? = null,
    val data: Map<String, String>? = null
)
