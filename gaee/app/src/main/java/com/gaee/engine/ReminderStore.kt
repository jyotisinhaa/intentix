package com.gaee.engine

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** One scheduled reminder. repeatMinutes == 0 means one-shot. */
data class Reminder(
    val id: Int,
    val message: String,
    var nextFireMillis: Long,
    val repeatMinutes: Int
)

/** Persists reminders so they survive process death and can be re-armed after reboot. */
class ReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences("gaee_reminders", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY = "reminders"
        private const val COUNTER = "counter"
    }

    fun all(): MutableList<Reminder> {
        val json = prefs.getString(KEY, null) ?: return mutableListOf()
        return try {
            gson.fromJson(json, object : TypeToken<MutableList<Reminder>>() {}.type)
                ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(list: List<Reminder>) {
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun add(r: Reminder) = save(all().apply { add(r) })

    fun update(r: Reminder) {
        val list = all()
        val i = list.indexOfFirst { it.id == r.id }
        if (i >= 0) { list[i] = r; save(list) } else add(r)
    }

    fun remove(id: Int) = save(all().filter { it.id != id })

    fun clear() = save(emptyList())

    fun get(id: Int): Reminder? = all().firstOrNull { it.id == id }

    fun nextId(): Int {
        val c = prefs.getInt(COUNTER, 1)
        prefs.edit().putInt(COUNTER, c + 1).apply()
        return c
    }
}
