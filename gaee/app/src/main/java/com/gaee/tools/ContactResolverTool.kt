package com.gaee.tools

import android.content.Context
import android.provider.ContactsContract
import com.gaee.model.ToolResult
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ContactResolverTool(private val context: Context) : BaseTool {
    override val name = "ContactResolverTool"

    private val prefs = context.getSharedPreferences("gaee_contacts", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Relationship/nickname map: "mom" → "Sunita Sinha", "doctor" → "Dr. Patel"
    private fun loadNicknameMap(): Map<String, String> {
        val json = prefs.getString("nickname_map", null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, String>>() {}.type
        return try { gson.fromJson(json, type) } catch (_: Exception) { emptyMap() }
    }

    fun saveNickname(alias: String, contactName: String) {
        val map = loadNicknameMap().toMutableMap()
        map[alias.lowercase().trim()] = contactName
        prefs.edit().putString("nickname_map", gson.toJson(map)).apply()
    }

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val query = args["query"]?.trim()
            ?: return ToolResult(false, "I need a name or relationship to look up.")

        // Step 1: check nickname map first
        val nicknameMap = loadNicknameMap()
        val nicknameMatch = nicknameMap[query.lowercase()]
        val effectiveQuery = nicknameMatch ?: query

        // Step 2: search contacts
        val candidates = searchContacts(effectiveQuery)

        return when {
            candidates.isEmpty() -> ToolResult(
                success = false,
                speakAfter = "I could not find $query in your contacts. Do you want to try a different name?",
                data = mapOf("query" to query, "found" to "false")
            )
            candidates.size == 1 -> {
                val (name, phone) = candidates[0]
                ToolResult(
                    success = true,
                    speakAfter = "",
                    data = mapOf("resolvedName" to name, "phone" to phone, "found" to "true")
                )
            }
            else -> {
                // Multiple matches — pick the best scored one
                val best = candidates[0]
                val (name, phone) = best
                ToolResult(
                    success = true,
                    speakAfter = "",
                    data = mapOf(
                        "resolvedName" to name,
                        "phone" to phone,
                        "found" to "true",
                        "multipleFound" to "true",
                        "allMatches" to candidates.joinToString("|") { it.first }
                    )
                )
            }
        }
    }

    private fun searchContacts(query: String): List<Pair<String, String>> {
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        ) ?: return emptyList()

        val results = mutableListOf<Triple<String, String, Int>>() // name, phone, score
        val lowerQuery = query.lowercase().trim()

        cursor.use {
            val nameCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numCol  = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val contactName = it.getString(nameCol) ?: continue
                val phone       = it.getString(numCol)?.replace("\\s".toRegex(), "") ?: continue
                val lowerName   = contactName.lowercase()

                val score = when {
                    lowerName == lowerQuery                              -> 100
                    lowerName.startsWith(lowerQuery)                    ->  90
                    lowerName.contains(lowerQuery)                      ->  80
                    lowerQuery.contains(lowerName) && lowerName.length > 2 -> 70
                    queryWordsMatchName(lowerQuery, lowerName)          ->  60
                    else                                                 ->   0
                }
                if (score > 0) results.add(Triple(contactName, phone, score))
            }
        }

        return results
            .sortedByDescending { it.third }
            .distinctBy { it.first } // dedupe same name with different numbers
            .map { it.first to it.second }
    }

    private fun queryWordsMatchName(query: String, name: String): Boolean {
        val words = query.split(" ").filter { it.length > 2 }
        return words.isNotEmpty() && words.any { name.contains(it) }
    }
}
