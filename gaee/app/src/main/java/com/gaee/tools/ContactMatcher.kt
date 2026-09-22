package com.gaee.tools

/**
 * Where a [ContactAlias] came from -- used as a tiebreak in [ContactMatcher.rank] when two
 * candidates score equally (NICKNAME > RELATION > NAME > RELATION_NAME).
 *
 * RELATION_NAME ranks LAST, below the plain NAME source: it exists only so
 * ContactMatcherTest can regression-lock the tiebreak ordering, and to prevent a future author
 * from accidentally slotting a relative's-own-name alias above a real contact's own name.
 * ContactResolverTool deliberately no longer emits any alias with this source at all -- see the
 * comment in its gatherAliases() for why (a Relation row's DATA1 names the OTHER person, not the
 * row's own contact, so indexing it points a name at the wrong phone number).
 */
enum class AliasSource { NAME, NICKNAME, RELATION, RELATION_NAME }

/**
 * One searchable name/label for a contact. A single contact can have several: its display
 * name (NAME), a saved custom label like "doctor" (NICKNAME), a canonical relation word like
 * "mother" (RELATION), or a relation combined with the contact's own name (RELATION_NAME).
 */
data class ContactAlias(
    val contactId: Long,
    val displayName: String,
    val alias: String,
    val source: AliasSource
)

/** One ranked search result -- one row per contact, at its best-scoring alias. */
data class ContactMatch(val contactId: Long, val name: String, val score: Int, val source: AliasSource)

/**
 * Pure-Kotlin contact name/relationship matching -- zero android.* imports so it is JVM-testable
 * (see CLAUDE.md: src/test vs src/androidTest). Moved out of ContactResolverTool so the scoring
 * tiers, query normalisation, and relation canonicalisation can be regression-locked without a
 * device.
 */
object ContactMatcher {

    private val leadingArticles = setOf("my", "the", "our", "a")

    // Canonical relation word for every synonym GAEE understands natively. Deliberately
    // English-only -- see the comment on ContactResolverTool.relationLabel() for why.
    //
    // IDENTITY, not a matching convenience: this map also backs nicknameKey() (the SharedPreferences
    // key for taught nicknames), so two entries may only fold together here if they can NEVER refer
    // to two different real people. mom/mum/mummy/ma really are the same word for the same person --
    // folding them to "mother" is safe. "daughter" and "son" are NOT that: they are two distinct
    // relations that Android's ContactsContract merely happens to store under one TYPE_CHILD
    // constant (see ContactResolverTool.relationLabel()). Folding them to the same canonical word
    // HERE previously made nicknameKey("daughter") == nicknameKey("son"), so teaching "my son is
    // Rohan" after "my daughter is Priya" silently overwrote Priya's entry -- broke the feature for
    // any family with both. They stay as separate identities; TYPE_CHILD Relation-row MATCHING is
    // handled separately, only inside rank(), by relationMatchVariants() below.
    private val relationSynonyms: Map<String, String> = mapOf(
        "mom" to "mother", "mum" to "mother", "mummy" to "mother", "ma" to "mother", "mother" to "mother",
        "dad" to "father", "daddy" to "father", "papa" to "father", "father" to "father",
        "daughter" to "daughter", "girl" to "daughter",
        "son" to "son", "boy" to "son",
        "child" to "child",
        "hubby" to "spouse", "husband" to "spouse", "wife" to "spouse", "spouse" to "spouse",
        "brother" to "brother", "bro" to "brother",
        "sister" to "sister", "sis" to "sister",
        "friend" to "friend"
    )

    // Relation words Android can only ever expose via the single, gender-neutral TYPE_CHILD
    // Relation-row label "child" (see ContactResolverTool.relationLabel() -- there is no
    // TYPE_DAUGHTER / TYPE_SON). Used ONLY by rank()'s relationMatchVariants(), to let a "daughter"
    // or "son" query still find a TYPE_CHILD row, WITHOUT touching canonicalRelation()/nicknameKey()
    // identity above.
    private val childRelationWords = setOf("daughter", "son", "child")

    // NICKNAME > RELATION > NAME > RELATION_NAME. RELATION_NAME ranks below NAME (not just below
    // RELATION) as defence-in-depth: nothing should ever be able to out-rank a contact's own real
    // name with an alias built from a DIFFERENT person's name (see the AliasSource doc comment).
    private fun sourceRank(source: AliasSource): Int = when (source) {
        AliasSource.NICKNAME -> 3
        AliasSource.RELATION -> 2
        AliasSource.NAME -> 1
        AliasSource.RELATION_NAME -> 0
    }

    /** Lowercases, trims, strips a single leading "my"/"the"/"our"/"a", and collapses whitespace. */
    fun normalizeQuery(raw: String): String {
        val collapsed = raw.trim().lowercase().replace(Regex("\\s+"), " ")
        val words = collapsed.split(" ")
        return if (words.size > 1 && words[0] in leadingArticles) {
            words.drop(1).joinToString(" ")
        } else {
            collapsed
        }
    }

    /**
     * Folds a relation synonym to its canonical form (mom/mum/mummy/ma -> "mother",
     * dad/daddy/papa -> "father", hubby/husband -> "spouse", ...). Returns null if [normalized]
     * is not a recognised relation synonym.
     */
    fun canonicalRelation(normalized: String): String? = relationSynonyms[normalized]

    /**
     * True if [normalized] is a relationship word GAEE understands natively -- as opposed to a
     * user-saved custom label (e.g. "doctor") that only exists because someone taught it.
     */
    fun isRelationshipWord(normalized: String): Boolean = relationSynonyms.containsKey(normalized)

    /** The 5-tier fuzzy match score between a query and a single alias, 0-100. */
    fun scoreAlias(query: String, alias: String): Int {
        val q = query.lowercase().trim()
        val a = alias.lowercase().trim()
        return when {
            a == q -> 100
            a.startsWith(q) -> 90
            a.contains(q) -> 80
            q.contains(a) && a.length > 2 -> 70
            wordOverlap(q, a) -> 60
            else -> 0
        }
    }

    private fun wordOverlap(query: String, alias: String): Boolean {
        val words = query.split(" ").filter { it.length > 2 }
        return words.isNotEmpty() && words.any { alias.contains(it) }
    }

    // Bonus query variant for TYPE_CHILD's single-word limitation, ADDITIONAL to (never in place
    // of) [normalized]/[canonicalRelation] -- "daughter" must still match a real TYPE_CUSTOM label
    // that literally reads "daughter" (the common real-world case), so the original word is never
    // dropped. [relationWord] should be the canonical form already ([canonicalRelation] applied)
    // so "girl" (-> canonical "daughter") also reaches "child", not just the literal words
    // "daughter"/"son"/"child" themselves.
    private fun relationMatchVariants(relationWord: String): List<String> =
        if (relationWord in childRelationWords) listOf("child") else emptyList()

    /**
     * Scores every alias in [aliases] against [rawQuery] (normalised, then also expanded via
     * [canonicalRelation] when applicable), keeps the best-scoring alias per contact, and sorts
     * by score desc, then source precedence (NICKNAME > RELATION > NAME > RELATION_NAME), then
     * name asc. Drops zero scores. Returns an empty list for an empty [aliases] list or a
     * blank/empty-after-normalisation [rawQuery] (never treats an empty query as "match
     * everything").
     */
    fun rank(aliases: List<ContactAlias>, rawQuery: String): List<ContactMatch> {
        val normalized = normalizeQuery(rawQuery)
        // CRITICAL: alias.contains("") is true for every alias in Kotlin -- a blank/empty query
        // must never reach scoreAlias, or it would "match" the entire contact book.
        if (normalized.isBlank()) return emptyList()
        if (aliases.isEmpty()) return emptyList()

        val canonical = canonicalRelation(normalized)
        // Primary variants: what the user actually said, and a TRUE synonym for the same person
        // (mom -> mother). Scored at full tier, up to an exact 100.
        val primaryVariants = listOfNotNull(normalized, canonical).distinct()
        // Secondary/bonus variant: "child" as a stand-in for daughter/son, ONLY when relevant.
        // Deliberately capped below the exact-match tier (100) -- "child" is not the word the user
        // said, only a platform stand-in for it, so it must never tie with (let alone beat) a
        // literal exact match on the real word (see ContactMatcherTest
        // rank_literalDaughterAlias_beatsChildRelationAlias_notATie).
        val bonusVariant = relationMatchVariants(canonical ?: normalized).firstOrNull()

        val bestPerContact = mutableMapOf<Long, ContactMatch>()
        for (alias in aliases) {
            val primaryScore = primaryVariants.maxOf { scoreAlias(it, alias.alias) }
            val bonusScore = bonusVariant?.let { minOf(scoreAlias(it, alias.alias), 90) } ?: 0
            val score = maxOf(primaryScore, bonusScore)
            if (score <= 0) continue
            val existing = bestPerContact[alias.contactId]
            if (existing == null ||
                score > existing.score ||
                (score == existing.score && sourceRank(alias.source) > sourceRank(existing.source))
            ) {
                bestPerContact[alias.contactId] = ContactMatch(alias.contactId, alias.displayName, score, alias.source)
            }
        }

        return bestPerContact.values.sortedWith(
            compareByDescending<ContactMatch> { it.score }
                .thenByDescending { sourceRank(it.source) }
                .thenBy { it.name }
        )
    }

    /**
     * The normalised, relation-canonicalised key used for the SharedPreferences nickname map,
     * so "My Daughter" and "daughter" (or "Mom" and "my mother") resolve to the same key.
     */
    fun nicknameKey(raw: String): String {
        val normalized = normalizeQuery(raw)
        return canonicalRelation(normalized) ?: normalized
    }
}
