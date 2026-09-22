package com.gaee.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.gaee.model.ToolResult
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class ContactResolverTool(private val context: Context) : BaseTool {
    override val name = "ContactResolverTool"

    private val prefs = context.getSharedPreferences("gaee_contacts", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Guards migrateLegacyNicknames() so it runs at most once per process, and OFF the
    // constructor path: ExecutionEngine's `tools` map is built with `by lazy`, on whatever thread
    // first calls execute() on ANY tool (viewModelScope == Main.immediate) -- a disk read in the
    // constructor would block the main thread on the very first voice command of the app's life.
    private val migrated = AtomicBoolean(false)

    // Second-turn learn writes only ever affect the taught nickname map -- never a real Contacts
    // row -- so a stale in-memory book is not a correctness issue here.
    private fun migrateLegacyNicknames() {
        if (prefs.getInt("nickname_map_version", 0) >= 1) return
        val map = loadNicknameMap().toMutableMap()
        map.remove("my love")
        prefs.edit()
            .putString("nickname_map", gson.toJson(map))
            .putInt("nickname_map_version", 1)
            .apply()
    }

    // Relationship/nickname map: "mom" → "Sunita Sinha", "doctor" → "Dr. Patel". Keyed through
    // ContactMatcher.nicknameKey() so "My Daughter" / "daughter" / "Mom" / "my mother" all share
    // one entry.
    private fun loadNicknameMap(): Map<String, String> {
        val json = prefs.getString("nickname_map", null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, String>>() {}.type
        // A corrupt/half-written JSON blob must never throw OUT of this function: it is called
        // from migrateLegacyNicknames() too, and on Gson's Kotlin binding a caught exception here
        // does NOT protect a caller further up if this function itself were called from an init
        // block -- which is exactly why migration was moved off the constructor path above.
        return try { gson.fromJson(json, type) } catch (_: Exception) { emptyMap() }
    }

    fun saveNickname(alias: String, contactName: String) {
        val map = loadNicknameMap().toMutableMap()
        map[ContactMatcher.nicknameKey(alias)] = contactName
        prefs.edit().putString("nickname_map", gson.toJson(map)).apply()
    }

    override suspend fun execute(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        if (migrated.compareAndSet(false, true)) migrateLegacyNicknames()

        val query = args["query"]?.trim()
            ?: return@withContext ToolResult(false, "I need a name or relationship to look up.")
        // Present on the retry after we asked "what is their name?" — the ORIGINAL query
        // ("my daughter") to teach once we resolve the name the user just gave us.
        val learnAs = args["learnAs"]?.trim()?.takeIf { it.isNotBlank() }

        // A missing/revoked READ_CONTACTS must speak a DISTINCT, actionable message -- never the
        // "I don't know who X is" ask-and-learn branch below. Those two failures look identical
        // to a user ("the phone doesn't know my daughter") but only one of them is fixable by
        // teaching a name; the other needs a Settings trip. needsUserInput is deliberately NOT
        // set here: asking the user to name someone will not restore a permission.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext noContactsAccessResult(query)
        }

        // Step 1: check the saved nickname map first
        val nicknameMap = loadNicknameMap()
        val nicknameMatch = nicknameMap[ContactMatcher.nicknameKey(query)]
        val effectiveQuery = nicknameMatch ?: query

        // Step 2: search contacts (name, saved contact nicknames, and relation labels). The
        // permission check above closes the common case, but the grant can still be revoked
        // between that check and this query (race, or a restricted/managed profile) -- catch it
        // here too rather than letting it fall into ExecutionEngine's generic exception handler,
        // which would say the same "something went wrong" for a fixable permission problem as
        // for a real bug.
        val book = try {
            gatherAliases()
        } catch (e: SecurityException) {
            return@withContext noContactsAccessResult(query)
        }
        val candidates = ContactMatcher.rank(book.aliases, effectiveQuery)

        if (candidates.isEmpty()) {
            val normalizedQuery = ContactMatcher.normalizeQuery(query)
            val question = "I don't know who your $normalizedQuery is yet. What is their name?"
            return@withContext ToolResult(
                success = false,
                // Distinct from `question` above on purpose: this is what gets spoken if the
                // clarification loop gives up (retry also fails, MAX_CLARIFICATIONS is hit, or
                // the answer is blank/times out). It must never repeat the same question into a
                // mic that has already closed -- it must say what failed and what to try instead.
                speakAfter = "I could not find $normalizedQuery in your contacts. Tap the big button and tell me their name.",
                data = mapOf(
                    "found" to "false",
                    "query" to query,
                    "needsUserInput" to "true",
                    "askUser" to question,
                    "retryArg" to "query",
                    "learnAs" to query
                )
            )
        }

        // Ambiguity check: only a TIE at the very top score should interrogate the user -- a
        // clear best match (strictly higher score than the runner-up) still resolves silently,
        // exactly as before. Guessing among tied contacts is how "call my daughter" can end up
        // calling the wrong Maria; ContactMatcher.rank()'s `thenBy { name }` tiebreak exists only
        // to make ordering deterministic for tests, not to pick a winner for a real phone call.
        val topScore = candidates[0].score
        val tied = candidates.filter { it.score == topScore }
        if (tied.size > 1) {
            val normalizedQuery = ContactMatcher.normalizeQuery(query)
            val names = tied.take(3).map { it.name }
            val question = "I found ${names.size} people named $normalizedQuery. Which one — " +
                (if (names.size == 1) names[0]
                else names.dropLast(1).joinToString(", ") + ", or " + names.last()) + "?"
            return@withContext ToolResult(
                success = false,
                speakAfter = "I found more than one person matching $normalizedQuery. Tap the big button and tell me their full name.",
                data = buildMap {
                    put("found", "false")
                    put("query", query)
                    put("needsUserInput", "true")
                    put("askUser", question)
                    put("retryArg", "query")
                    put("multipleFound", "true")
                    put("allMatches", names.joinToString("|"))
                    // Do NOT set learnAs here from scratch -- the user is picking between two
                    // real contacts, not teaching a nickname ("maria" -> whichever they picked
                    // would be wrong). But if the CALLER already carried a learnAs (this
                    // ambiguity surfaced while answering "who is your daughter?"), preserve it:
                    // the eventually-resolved name is still the thing to learn.
                    if (learnAs != null) put("learnAs", learnAs)
                }
            )
        }

        val best = candidates[0]
        val phone = book.numbers[best.contactId]
            ?: return@withContext ToolResult(
                success = false,
                speakAfter = "I found ${best.name} but they have no phone number saved. Do you want to try someone else?",
                data = mapOf("query" to query, "found" to "false")
            )

        // A hit via the taught SharedPreferences map counts as "learned", ahead of whichever
        // Contacts-native source it also happens to match, since that is what the user actually
        // asked for ("my daughter" resolving straight to a name we were told before).
        val matchSource = when {
            nicknameMatch != null -> "learned"
            best.source == AliasSource.NICKNAME -> "nickname"
            best.source == AliasSource.RELATION || best.source == AliasSource.RELATION_NAME -> "relation"
            else -> "name"
        }

        // Second turn of ask-and-learn: we now have a resolved name for a query the user just
        // taught us — remember it so it is never asked again. A failed SAVE must not discard a
        // SUCCESSFUL lookup: the call/message the user asked for must still go through, just
        // without the "I will remember" confirmation.
        val speakAfter = if (learnAs != null) {
            try {
                saveNickname(learnAs, best.name)
                "I will remember that your ${ContactMatcher.normalizeQuery(learnAs)} is ${best.name}."
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }

        val data = mutableMapOf(
            "resolvedName" to best.name,
            "phone" to phone,
            "found" to "true",
            "matchSource" to matchSource
        )
        if (candidates.size > 1) {
            data["multipleFound"] = "true"
            // Capped at 3: scoreAlias awards 80 for a bare `contains`, so a 1-2 char query can
            // match most of the book -- an uncapped join here would merge dozens of real contact
            // names into `context`, which is serialised into the cloud re-plan prompt.
            data["allMatches"] = candidates.take(3).joinToString("|") { it.name }
        }

        ToolResult(success = true, speakAfter = speakAfter, data = data)
    }

    private fun noContactsAccessResult(query: String): ToolResult = ToolResult(
        success = false,
        speakAfter = "I can't see your contacts right now. Please turn on Contacts access for GAEE in Settings.",
        data = mapOf("found" to "false", "query" to query)
    )

    /** The two ContentResolver queries backing [ContactMatcher.rank], plus the id→number join. */
    private data class ContactBook(val aliases: List<ContactAlias>, val numbers: Map<Long, String>)

    // Exactly TWO ContentResolver queries, regardless of contact count — no per-row (N+1) lookup.
    private fun gatherAliases(): ContactBook {
        val numberByContactId = mutableMapOf<Long, String>()
        val nameByContactId = mutableMapOf<Long, String>()
        val aliases = mutableListOf<ContactAlias>()

        // Query 1: phone numbers + display names. One pass builds numberByContactId (first
        // number seen per contact wins), nameByContactId, and the NAME aliases.
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (idCol >= 0 && nameCol >= 0 && numCol >= 0) {
                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idCol)
                    val contactName = cursor.getString(nameCol) ?: continue
                    val number = cursor.getString(numCol)?.replace("\\s".toRegex(), "") ?: continue

                    if (!numberByContactId.containsKey(contactId)) {
                        numberByContactId[contactId] = number
                    }
                    if (!nameByContactId.containsKey(contactId)) {
                        nameByContactId[contactId] = contactName
                        aliases.add(ContactAlias(contactId, contactName, contactName, AliasSource.NAME))
                    }
                }
            }
        }

        // Query 2: nicknames + relations. A contact with no phone number is unreachable by call
        // or SMS, so rows for a CONTACT_ID absent from numberByContactId are dropped in memory —
        // that in-memory drop IS the join with query 1; no per-row query.
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3
            ),
            "${ContactsContract.Data.MIMETYPE} IN (?,?)",
            arrayOf(
                ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE
            ),
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
            val mimeCol = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val data1Col = cursor.getColumnIndex(ContactsContract.Data.DATA1)
            val data2Col = cursor.getColumnIndex(ContactsContract.Data.DATA2)
            val data3Col = cursor.getColumnIndex(ContactsContract.Data.DATA3)
            if (idCol >= 0 && mimeCol >= 0 && data1Col >= 0) {
                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idCol)
                    val displayName = nameByContactId[contactId] ?: continue
                    if (!numberByContactId.containsKey(contactId)) continue // unreachable — drop

                    when (cursor.getString(mimeCol)) {
                        ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> {
                            val nickname = cursor.getString(data1Col)?.takeIf { it.isNotBlank() } ?: continue
                            aliases.add(ContactAlias(contactId, displayName, nickname, AliasSource.NICKNAME))
                        }
                        ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE -> {
                            val type = if (data2Col >= 0) cursor.getInt(data2Col) else ContactsContract.CommonDataKinds.Relation.TYPE_CUSTOM
                            val custom = if (data3Col >= 0) cursor.getString(data3Col) else null
                            val label = relationLabel(type, custom)
                            if (label != null) {
                                aliases.add(ContactAlias(contactId, displayName, label, AliasSource.RELATION))
                            }
                            // Deliberately NOT indexing DATA1 (the relative's own name, e.g.
                            // "Priya") as an alias here. For a Relation row, DATA1 names the
                            // OTHER person, not the contact that owns this row -- registering it
                            // would point a real person's name at the WRONG contact's phone
                            // number. Concrete failure this caused: contact "Sunita Sinha" has a
                            // Relation row Child="Priya"; a separate real contact "Priya Sinha"
                            // also exists. "Call Priya" scored both at 100 and the tiebreak
                            // picked Sunita -- CallTool dialled the wrong person before ever
                            // speaking a name. AliasSource.RELATION_NAME is kept as an enum member
                            // (referenced by ContactMatcherTest) and ranked below NAME as
                            // defence-in-depth, but nothing populates it anymore.
                        }
                    }
                }
            }
        }

        return ContactBook(aliases, numberByContactId)
    }

    /**
     * Maps a Contacts Relation TYPE (+ custom label for TYPE_CUSTOM) to a plain English word.
     * Deliberately NOT Relation.getTypeLabel(resources, type, custom) — that returns a
     * device-localized string, but ContactMatcher's synonym table (mom/mum/daughter/...) is
     * English-only, so a localized label would silently stop matching on a non-English device.
     *
     * NOTE: Android's ContactsContract has no separate TYPE_SON / TYPE_DAUGHTER constant — only
     * the gender-neutral TYPE_CHILD — so both "my son" and "my daughter" queries only match a
     * TYPE_CHILD relation via the generic "child" label, never a gendered word. ContactMatcher
     * does NOT fold "daughter"/"son" to "child" as their canonical identity (that would make
     * nicknameKey("daughter") == nicknameKey("son") and teaching one overwrite the other) — instead
     * rank()'s relationMatchVariants() adds "child" as an extra, capped-score query variant so this
     * Relation row can still be found without touching what a taught nickname is keyed by. That is
     * an Android platform limitation, not a gap in ContactMatcher's synonym table.
     */
    private fun relationLabel(type: Int, custom: String?): String? = when (type) {
        ContactsContract.CommonDataKinds.Relation.TYPE_CUSTOM -> custom?.takeIf { it.isNotBlank() }
        ContactsContract.CommonDataKinds.Relation.TYPE_MOTHER -> "mother"
        ContactsContract.CommonDataKinds.Relation.TYPE_FATHER -> "father"
        ContactsContract.CommonDataKinds.Relation.TYPE_CHILD -> "child"
        ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE -> "spouse"
        ContactsContract.CommonDataKinds.Relation.TYPE_BROTHER -> "brother"
        ContactsContract.CommonDataKinds.Relation.TYPE_SISTER -> "sister"
        ContactsContract.CommonDataKinds.Relation.TYPE_FRIEND -> "friend"
        else -> null
    }
}
