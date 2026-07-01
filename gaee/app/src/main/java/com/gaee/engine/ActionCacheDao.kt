package com.gaee.engine

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.gaee.model.CacheEntry

@Dao
interface ActionCacheDao {

    @Insert
    suspend fun insert(entry: CacheEntry): Long

    @Query("SELECT * FROM action_cache WHERE intent = :intent")
    suspend fun getByIntent(intent: String): List<CacheEntry>

    @Query("UPDATE action_cache SET hitCount = hitCount + 1 WHERE id = :id")
    suspend fun incrementHitCount(id: Long)

    @Query("UPDATE action_cache SET successRate = (successRate * hitCount + :outcome) / (hitCount + 1) WHERE id = :id")
    suspend fun recordOutcome(id: Long, outcome: Float)

    @Query("SELECT COUNT(*) FROM action_cache")
    suspend fun count(): Int
}
