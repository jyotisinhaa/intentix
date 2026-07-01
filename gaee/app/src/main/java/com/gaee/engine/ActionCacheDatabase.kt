package com.gaee.engine

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.gaee.model.CacheEntry

@Database(entities = [CacheEntry::class], version = 1, exportSchema = false)
abstract class ActionCacheDatabase : RoomDatabase() {

    abstract fun dao(): ActionCacheDao

    companion object {
        @Volatile private var INSTANCE: ActionCacheDatabase? = null

        fun getInstance(context: Context): ActionCacheDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, ActionCacheDatabase::class.java, "gaee_cache.db")
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
