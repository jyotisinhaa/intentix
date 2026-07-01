package com.gaee.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "action_cache")
data class CacheEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val intent: String,
    val embedding: ByteArray,   // 384 floats × 4 bytes = 1536 bytes, L2-normalised
    val stepsJson: String,       // JSON array of ToolCall with {slot} templates
    val argsSlots: String,       // JSON array of slot names e.g. ["time","name"]
    val hitCount: Int = 0,
    val successRate: Float = 1.0f,
    val createdBy: String = "llm"
) {
    override fun equals(other: Any?) = other is CacheEntry && id == other.id
    override fun hashCode() = id.hashCode()
}
