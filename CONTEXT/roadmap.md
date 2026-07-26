# Roadmap — "Hello"

> **Status:** living document. Ordered by the **validate-before-productionize** discipline in
> `strategy.md` §7 — NOT by what's most fun to build. **Last updated:** 2026-07-25.
> Golden rule: **do not build the backend/security/scale plumbing before payment is proven.**

---

## Stage 0 — Immediate hygiene (do now, regardless of everything)
Cheap, pure-safety items that shouldn't wait for any decision:
- [ ] **Rotate the Anthropic API key** — currently shipped in the APK, treat as compromised
  (`launch-readiness.md` §1.1).
- [ ] **Remove hardcoded `"my love" → "Anurag"` nickname** from `MainViewModel` (§1.2).
- [ ] Commit the `CONTEXT/` folder to git.

## Stage 1 — Validate demand (before building more)
The whole business hinges on this; it's cheap and it's first.
- [ ] **Payment test** — priced landing page + deposit/pre-order to NRI children (`monetization.md`
  §5). *Kill/continue gate.*
- [ ] **Sharpen the pitch** — the child's relief + parent's safety, tested against real community
  reactions.
- [ ] **Recruit 5–10 beta families** from the founder's network + responders.

## Stage 2 — Prove the product with real families (tiny, hand-held)
Manual setup by us; learn what actually breaks and whether they *keep* using it.
- [ ] **Build the guide-and-teach hybrid** (`strategy.md` §2) — narrate each step + **guided
  step-by-step fallback** when automation can't complete. This is the highest-value unbuilt feature;
  it's what makes fragile automation survivable.
- [ ] **Narrow to a bulletproof task set** — the ~5 daily tasks done at ~99%, over "any task at 80%."
- [ ] **The "child loop"** — opt-in alerts/updates to the child abroad (scam blocked, unusual
  activity). The core of the value proposition and the likely paid feature.
- [ ] **Measure week-2 retention** (where "do it for me" products die) and whether the child perceives
  enough value to keep paying.

## Stage 3 — Productionize (only after Stage 1–2 signal is positive)
Everything from `launch-readiness.md`:
- [ ] **Backend proxy** holding the API key + auth + usage metering (§1.1) → unlocks billing.
- [ ] **Billing / subscription** integration (`monetization.md`).
- [ ] **Release hardening** — signing, R8 with correct keep rules, real `applicationId`, disable/limit
  backup, strip PII from logs (§2).
- [ ] **Fix the main-thread/ANR issue** in the automation path (§2).
- [ ] **Crash reporting** (Crashlytics/Sentry) (§2).
- [ ] **Privacy policy + Data-Safety** artifact (§1.3).
- [ ] **Distribution decision** — direct APK vs Play re-scope (`strategy.md` §4). If Play: replace
  SMS/Call with intents, frame accessibility as assistive, complete declarations (§1.4).

## Stage 4 — Durability & scale
- [ ] Tests on the core (IntentClassifier / ActionCache / ExecutionEngine) + CI + lint.
- [ ] Server-driven config (models, prompts, scam rules) so updates don't need an APK.
- [ ] Resilient model download (retry/resume/checksum).
- [ ] Hindi/Hinglish + localization (`strings.xml` is currently 5 lines, English-only).
- [ ] Stronger scam detection (beyond keyword matching) — or partner/augment.

## Later — expansions from strength (NOT now; on the kill list until the wedge is won)
Personalization/user memory, RAG cache few-shot, reminder follow-through, B2B2C partnerships
(banks/phone-sellers/communities), and only-much-later any of the parked ideas (real-world
navigation, broader audiences).

---

## What's already built (context)
Phase 1–3 engine + tools, F0 (conversational answers), **F1 scam detection**, **F2 proactive alerts
(speak + banner + history card)** — validated on a real device. See `features.md` and the root
`GAEE_Phase*.md` engineering docs. The build is ahead of the *product/business* work — which is
exactly why the roadmap leads with validation, not code.
