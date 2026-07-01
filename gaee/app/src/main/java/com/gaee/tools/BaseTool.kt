package com.gaee.tools

import com.gaee.model.ToolResult

interface BaseTool {
    val name: String
    suspend fun execute(args: Map<String, String>): ToolResult
}
