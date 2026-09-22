package com.gaee

import com.gaee.tools.AliasSource
import com.gaee.tools.ContactAlias
import com.gaee.tools.ContactMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contact resolution (Phase 2, Parts 1-3) -- [ContactMatcher] is the pure-Kotlin scoring/ranking
 * core pulled out of ContactResolverTool so it runs on the JVM (`gradlew testDebugUnitTest`)
 * with no ContactsContract, no device. These tests regression-lock the 5-tier score verbatim,
 * the query normalisation rules, relation-word canonicalisation, and the rank() ordering
 * contract (max-per-contact, score desc, then source precedence, then name asc).
 *
 * remember_contact / teach-mode is explicitly out of scope here -- these tests only cover the
 * matcher, not the classifier prompt or LlmPlanner.
 */
class ContactMatcherTest {

    // --- scoreAlias: the 5 tiers, moved verbatim from ContactResolverTool -------------------

    @Test
    fun scoreAlias_exactMatch_scores100() {
        assertEquals("exact alias==query must score 100", 100, ContactMatcher.scoreAlias("priya", "priya"))
    }

    @Test
    fun scoreAlias_prefixMatch_scores90() {
        assertEquals("alias starting with query must score 90", 90, ContactMatcher.scoreAlias("pri", "priya"))
    }

    @Test
    fun scoreAlias_containsMatch_scores80() {
        assertEquals(
            "alias containing query (but not as a prefix) must score 80",
            80,
            ContactMatcher.scoreAlias("iya", "priya")
        )
    }

    @Test
    fun scoreAlias_queryContainsAlias_scores70() {
        assertEquals(
            "query containing a longer-than-2-char alias must score 70",
            70,
            ContactMatcher.scoreAlias("call priya now", "priya")
        )
    }

    @Test
    fun scoreAlias_wordOverlap_scores60() {
        assertEquals(
            "a shared word (len > 2) between query and alias must score 60",
            60,
            ContactMatcher.scoreAlias("ring priya up", "priya kumar")
        )
    }

    @Test
    fun scoreAlias_unrelated_scores0() {
        assertEquals("no relation at all must score 0", 0, ContactMatcher.scoreAlias("xyz", "priya"))
    }

    @Test
    fun scoreAlias_pinsTierBoundary_prefixVsContains() {
        // "dr. patel" starts with "dr.", not "pat" -- must land in the contains tier (80), not
        // the prefix tier (90). Pins the exact tier boundary called out for regression.
        assertEquals(
            "\"pat\" is not a prefix of \"dr. patel\" -- must score 80 (contains), not 90 (prefix)",
            80,
            ContactMatcher.scoreAlias("pat", "dr. patel")
        )
    }

    @Test
    fun scoreAlias_shortAlias_doesNotFireQueryContainsAliasTier() {
        // "call a taxi" contains "a", but the tier-70 rule requires alias.length > 2 -- a
        // 1-char alias must not score 70 (nor accidentally hit any other tier).
        assertEquals(
            "alias length <= 2 must not trigger the query-contains-alias (70) tier",
            0,
            ContactMatcher.scoreAlias("call a taxi", "a")
        )
    }

    // --- normalizeQuery ----------------------------------------------------------------------

    @Test
    fun normalizeQuery_stripsLeadingPossessiveArticleWord_andLowercasesTrims() {
        assertEquals("\"My Daughter\" must normalise to \"daughter\"", "daughter", ContactMatcher.normalizeQuery("My Daughter"))
        assertEquals(
            "surrounding whitespace and case must be stripped",
            "doctor",
            ContactMatcher.normalizeQuery("  the DOCTOR ")
        )
        assertEquals("\"our\" must be stripped as a leading article", "son", ContactMatcher.normalizeQuery("our son"))
    }

    // --- canonicalRelation ---------------------------------------------------------------------

    @Test
    fun canonicalRelation_foldsSynonyms_toCanonicalForm() {
        assertEquals("mother", ContactMatcher.canonicalRelation("mom"))
        assertEquals("mother", ContactMatcher.canonicalRelation("mum"))
        assertEquals("mother", ContactMatcher.canonicalRelation("mummy"))
        assertEquals("mother", ContactMatcher.canonicalRelation("ma"))
        assertEquals("father", ContactMatcher.canonicalRelation("dad"))
        assertEquals("father", ContactMatcher.canonicalRelation("daddy"))
        assertEquals("father", ContactMatcher.canonicalRelation("papa"))
        assertEquals("spouse", ContactMatcher.canonicalRelation("hubby"))
        assertEquals("spouse", ContactMatcher.canonicalRelation("husband"))
    }

    @Test
    fun canonicalRelation_returnsNull_forNonRelationWord() {
        assertNull("a plain first name is not a relation word", ContactMatcher.canonicalRelation("priya"))
    }

    @Test
    fun canonicalRelation_keepsDaughterAndSon_asDistinctIdentities() {
        // "daughter" and "son" are DISTINCT relations that Android's ContactsContract merely
        // happens to store under one TYPE_CHILD constant -- unlike mom/mum (true synonyms for the
        // same person), folding these to one canonical word here would make
        // nicknameKey("daughter") == nicknameKey("son"), so teaching one overwrites the other.
        // canonicalRelation must therefore be an IDENTITY for these two words (girl/boy fold onto
        // them, same as any other spelling variant of the same relation).
        assertEquals("daughter", ContactMatcher.canonicalRelation("daughter"))
        assertEquals("son", ContactMatcher.canonicalRelation("son"))
        assertEquals("daughter", ContactMatcher.canonicalRelation("girl"))
        assertEquals("son", ContactMatcher.canonicalRelation("boy"))
        assertEquals("child", ContactMatcher.canonicalRelation("child"))
    }

    // --- nicknameKey: the invariant that makes learn-once-answer-forever work -----------------

    @Test
    fun nicknameKey_isStable_acrossPossessivePhrasing() {
        assertEquals(
            "\"My Daughter\" and \"daughter\" must share one nickname-map key",
            ContactMatcher.nicknameKey("daughter"),
            ContactMatcher.nicknameKey("My Daughter")
        )
    }

    @Test
    fun nicknameKey_isStable_acrossRelationSynonyms() {
        assertEquals(
            "\"Mom\" and \"my mother\" must share one nickname-map key",
            ContactMatcher.nicknameKey("my mother"),
            ContactMatcher.nicknameKey("Mom")
        )
    }

    @Test
    fun nicknameKey_daughterAndSon_areDistinctKeys() {
        // THE regression guard: daughter and son are two different people. Before this fix,
        // both folded to canonical "child" and shared one SharedPreferences key, so teaching
        // "my son is Rohan" after "my daughter is Priya" silently overwrote Priya's entry --
        // broke the feature for any family with both a son and a daughter.
        org.junit.Assert.assertNotEquals(
            "\"my daughter\" and \"my son\" must NOT share a nickname-map key",
            ContactMatcher.nicknameKey("my daughter"),
            ContactMatcher.nicknameKey("my son")
        )
    }

    // --- isRelationshipWord vs custom labels ---------------------------------------------------

    @Test
    fun isRelationshipWord_false_forCustomLabel_doctor() {
        assertFalse(
            "\"doctor\" is a user-saved custom label, not a canonical relation word",
            ContactMatcher.isRelationshipWord("doctor")
        )
    }

    @Test
    fun rank_customLabelAlias_stillMatchesExactly_despiteNotBeingARelationWord() {
        val aliases = listOf(
            ContactAlias(contactId = 1L, displayName = "Dr. Patel", alias = "doctor", source = AliasSource.NICKNAME)
        )
        val results = ContactMatcher.rank(aliases, "the doctor")
        assertEquals("a custom-label nickname must still resolve", 1, results.size)
        assertEquals(
            "\"the doctor\" normalises to \"doctor\", an exact match against the saved label",
            100,
            results[0].score
        )
    }

    // --- rank(): one row per contact, source precedence, ordering ----------------------------

    @Test
    fun rank_collapsesMultipleAliases_toOneRowPerContact() {
        val aliases = listOf(
            ContactAlias(1L, "Priya Sinha", "priya sinha", AliasSource.NAME),
            ContactAlias(1L, "Priya Sinha", "priya", AliasSource.NICKNAME),
            ContactAlias(1L, "Priya Sinha", "daughter", AliasSource.RELATION),
            ContactAlias(1L, "Priya Sinha", "daughter priya", AliasSource.RELATION_NAME)
        )
        val results = ContactMatcher.rank(aliases, "priya")
        assertEquals("one contact with 4 aliases must produce exactly one ranked row", 1, results.size)
        assertEquals(1L, results[0].contactId)
    }

    @Test
    fun rank_nicknameExactMatch_outranksNameExactMatch_atEqualScore() {
        val aliases = listOf(
            ContactAlias(1L, "Sunita Sinha", "mom", AliasSource.NICKNAME),
            ContactAlias(2L, "Mom", "mom", AliasSource.NAME)
        )
        val results = ContactMatcher.rank(aliases, "mom")
        assertEquals(100, results[0].score)
        assertEquals(100, results[1].score)
        assertEquals(
            "NICKNAME must outrank NAME when both score 100 (source precedence tiebreak)",
            AliasSource.NICKNAME,
            results[0].source
        )
        assertEquals(1L, results[0].contactId)
    }

    @Test
    fun rank_relationAlias_beatsNameThatMerelyContainsTheRelationWord() {
        val aliases = listOf(
            ContactAlias(1L, "Priya Sinha", "daughter", AliasSource.RELATION),
            ContactAlias(2L, "Daughter Cake Shop", "daughter cake shop", AliasSource.NAME)
        )
        val results = ContactMatcher.rank(aliases, "my daughter")
        assertEquals(
            "an exact RELATION match must rank above a NAME that merely starts with the word",
            1L,
            results[0].contactId
        )
    }

    @Test
    fun rank_relation_outranksRelationName_atEqualScore() {
        val aliases = listOf(
            ContactAlias(1L, "Priya Sinha", "daughter", AliasSource.RELATION),
            ContactAlias(2L, "Rohan Sinha", "daughter", AliasSource.RELATION_NAME)
        )
        val results = ContactMatcher.rank(aliases, "daughter")
        assertEquals(100, results[0].score)
        assertEquals(100, results[1].score)
        assertEquals(
            "RELATION must outrank RELATION_NAME when both score 100 (source precedence)",
            AliasSource.RELATION,
            results[0].source
        )
        assertEquals(1L, results[0].contactId)
    }

    // --- rank(): TYPE_CHILD stand-in matching (fix #17) -- daughter/son must still find a
    // TYPE_CHILD Relation row, but a literal match on the real word must always win outright ----

    @Test
    fun rank_daughterQuery_stillMatchesChildRelationAlias() {
        // The Relation path must stay alive: a TYPE_CHILD row's only obtainable label is "child"
        // (relationLabel()), so a query for "my daughter" must still find it via the bonus variant.
        val aliases = listOf(
            ContactAlias(1L, "Priya Sinha", "child", AliasSource.RELATION)
        )
        val results = ContactMatcher.rank(aliases, "my daughter")
        assertEquals("a TYPE_CHILD Relation alias must still be found for a \"daughter\" query", 1, results.size)
        assertEquals(1L, results[0].contactId)
    }

    @Test
    fun rank_literalDaughterAlias_beatsChildRelationAlias_notATie() {
        // Two different real contacts: one has a literal alias reading "daughter" (e.g. a
        // TYPE_CUSTOM relation label, or a taught nickname); the other only has the generic
        // TYPE_CHILD "child" label. The literal match on the word the user actually said must win
        // outright -- not tie -- since it is the more specific, more likely-correct match.
        val aliases = listOf(
            ContactAlias(1L, "Priya Sinha", "daughter", AliasSource.RELATION),
            ContactAlias(2L, "Rohan Sinha", "child", AliasSource.RELATION)
        )
        val results = ContactMatcher.rank(aliases, "my daughter")
        assertEquals(2, results.size)
        assertTrue(
            "a literal \"daughter\" alias must score strictly higher than a \"child\"" +
                " Relation-row stand-in match, not tie with it",
            results[0].score > results[1].score
        )
        assertEquals(1L, results[0].contactId)
    }

    @Test
    fun rank_emptyAliasList_returnsEmpty() {
        assertTrue("no aliases to search must yield no matches", ContactMatcher.rank(emptyList(), "priya").isEmpty())
    }

    @Test
    fun rank_blankQuery_returnsEmpty_notEverything() {
        val aliases = listOf(
            ContactAlias(1L, "Priya Sinha", "priya sinha", AliasSource.NAME),
            ContactAlias(2L, "Rohan Sinha", "rohan sinha", AliasSource.NAME)
        )
        assertTrue(
            "a blank query must not be treated as an empty-string match-everything",
            ContactMatcher.rank(aliases, "   ").isEmpty()
        )
    }

    // --- rank(): tie vs. clear winner -- this is what ContactResolverTool keys off to decide
    // whether to resolve silently or ask the user which contact they meant -----------------------

    @Test
    fun rank_tiedTopScore_bothCandidatesReturned_atEqualScore() {
        val aliases = listOf(
            ContactAlias(1L, "Maria D'souza", "maria dsouza", AliasSource.NAME),
            ContactAlias(2L, "Maria Fernandes", "maria fernandes", AliasSource.NAME)
        )
        val results = ContactMatcher.rank(aliases, "maria")
        assertEquals(2, results.size)
        assertEquals(
            "both candidates must tie at the same top score so a caller can detect ambiguity" +
                " and ask the user, instead of silently guessing via the name-asc tiebreak",
            results[0].score,
            results[1].score
        )
    }

    @Test
    fun rank_clearWinner_topScoreStrictlyHigherThanRunnerUp_isNotATie() {
        val aliases = listOf(
            ContactAlias(1L, "Priya Sinha", "priya", AliasSource.NICKNAME),
            ContactAlias(2L, "Priyanka Rao", "priyanka rao", AliasSource.NAME)
        )
        val results = ContactMatcher.rank(aliases, "priya")
        assertTrue(
            "a clear best match must score strictly higher than the runner-up -- a caller must" +
                " resolve this silently, not interrogate the user on every multi-candidate lookup",
            results[0].score > results[1].score
        )
    }

    @Test
    fun rank_isPureOverInput_doesNotFilterOnPhoneLikeStrings() {
        // Filtering out non-phone-number results is ContactResolverTool's job (androidTest,
        // since it needs a real ContactsContract cursor) -- rank() must not special-case
        // anything that merely looks like a phone number.
        val aliases = listOf(
            ContactAlias(1L, "9876543210", "9876543210", AliasSource.NAME)
        )
        val results = ContactMatcher.rank(aliases, "9876543210")
        assertEquals(
            "rank must score a numeric-looking alias like any other string, with no phone filtering",
            1,
            results.size
        )
        assertEquals(100, results[0].score)
    }
}
