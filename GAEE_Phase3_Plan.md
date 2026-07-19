# GAEE Phase 3 — Protection, Intelligence & Personalization
## Engineering Plan (additive to Phase 2)

**Guiding principle:** Phase 3 is **purely additive**. Everything built in Phase 1 and Phase 2 stays
exactly as-is — no tool, guardrail, cache, or flow is removed or replaced. Phase 3 only **adds**
features and **enhances** existing ones behind their current behavior.

---

## The shift from Phase 2 → Phase 3

- **Phase 2 was about capability:** *"Say anything → the phone does it."* The LLM became the brain;
  generic tools + semantic cache + the "brain with eyes" re-planning loop + safety guardrails.
- **Phase 3 is about protection & intelligence:** *"The phone looks out for you even when you don't
  ask."* The center of gravity moves from **doing tasks** to **judgment, awareness, and
  personalization** — especially safety for elderly users.

> One line: **Phase 2 = the phone does what you ask. Phase 3 = the phone also protects and
> understands you.**

---

## Feature 0 — Conversational "Ask Anything" — DONE (2026-07-01)

Turns GAEE from a task-executor into a true assistant that also *answers* questions, not just
*acts*. Anything that isn't a device action now routes to a conversational path instead of
misfiring into "open app."

- **New intent** `ask_question` + **new tool** `AnswerTool` (`tools/AnswerTool.kt`).
- Answers via **Claude Sonnet 4.6 + web search** (`web_search_20260209`, max 3 uses) for live facts
  (weather, news, prices, sports) and general knowledge (math, definitions, how-to).
- **Grounded** in the phone's current date/time and approximate location, so "today", "this year",
  and "near me" resolve correctly.
- **Short multi-turn memory** (last 6 messages) so follow-ups like "what about tomorrow?" work.
- TTS-friendly system prompt (1–3 plain spoken sentences, no markdown/symbols).
- Verified on-device (`AnswerFlowTest`): moon-distance answer, correct current year (2026),
  deterministic routing to a single `[AnswerTool]` step.

Wiring: `IntentClassifier` (cloud prompt + `isQuestionLike` keyword fallback), `LlmPlanner`
(`deterministicIntents` + fallback plan), `ExecutionEngine` (registers `AnswerTool`, speaks its
answer). Purely additive — device actions and guardrails unchanged.

---

## Non-removal guarantee (what Phase 3 must preserve)

These Phase 2 assets remain untouched and are only *reused/extended*:

| Preserved | Role Phase 3 builds on |
|---|---|
| All 15+ tools (`tools/`) | Phase 3 adds new tools alongside; composes existing ones |
| `ActionCache` + `EmbeddingEngine` (semantic cache) | Enhanced, not replaced (see F5) |
| `LlmPlanner` re-planning loop + `MAX_REPLANS` | Unchanged |
| Guardrails 3a–3e (redaction, sensitive-screen handoff, re-plan allowlist, destructive double-confirm) | Unchanged; Phase 3 adds *incoming*-threat protection on top |
| `GaeeNotificationService` + `NotificationReaderTool` | The feed for scam detection (F1) |
| `GaeeAccessibilityService` (`readScreen`, etc.) | Reused for awareness features |

Any Phase 3 change to a Phase-2 file must be strictly **additive** (new methods/branches), gated so
default behavior is identical when the new feature is off.

---

## Feature 1 — Scam & Fraud Detection on Incoming Messages — HIGH PRIORITY

**The one feature the Phase 2 plan explicitly earmarked for Phase 3.**

**What it does:** watches incoming messages/notifications and warns the user, in plain speech, when
something looks like a scam ("You won a lottery — click here", fake bank OTP requests, "your account
is blocked, call this number", UPI-collect-request fraud).

**Why it matters:** elderly users are the #1 fraud target. This is the single highest-value
protective feature — and it directly fulfills the earmark in `GAEE_Phase2_Plan.md`.

### The requirement that drives the design

**Rarely miss a real scam, and rarely flag a real message as a scam.** These two goals fight each
other for a keyword-only filter: tune it aggressively and it false-alarms on genuine messages
(a real bank OTP, a message from family); tune it gently and it misses cleverly-worded scams. For an
elderly user, a noisy detector is almost as harmful as a blind one — repeated false alarms cause
**alarm fatigue**, and they stop trusting the one warning that matters. The only way to push *both*
error rates down at once is to add a smarter judge for the uncertain cases and explicit
false-positive guards — and to **measure** both error rates so the requirement is a number, not a
claim.

### Two-tier design

**Tier 1 — on-device heuristics (DONE).** `engine/ScamDetector.kt` scores each message with fixed,
offline, private keyword/structural heuristics and returns `SAFE / SUSPICIOUS / DANGEROUS` + a short
reason. This is deliberately hardcoded — as a *pre-filter* it must be instant, free, and never leave
the phone (same category as `SensitiveScreenGuard` / `DestructiveActionGuard`). It is **not** the
final judge; its job is to triage. Already wired into `GaeeNotificationService.screenForScam`, which
speaks a warning via `TtsTool` on a dangerous outcome. Rebalance still to do (see below).

**Tier 2 — Claude adjudication (TO BUILD).** The un-hardcoded intelligence. When Tier 1 is *unsure*
(`SUSPICIOUS`), a smarter reader decides — Claude understands *meaning*, so it distinguishes
"your account is blocked, click here" (scam) from "123456 is your OTP, do not share" (a genuine bank
message) far better than any word list. Reuses the existing one-shot Claude plumbing from
`engine/GaeeBackgroundWorker.kt` (raw OkHttp + org.json, key `BuildConfig.CLAUDE_API_KEY`, model
`claude-haiku-4-5`, no web_search). **`ScreenRedactor.redact()` runs first** so PIN/OTP/card digits
never leave the device. Claude is prompted to bias *against* alarming unless clearly dangerous, and
only a **confident dangerous** verdict speaks up.

### Privacy — opt-in cloud check

The Tier-2 cloud check is **opt-in**, matching the app's existing `NeedsCloudPermission` posture.
A "smart scam protection" toggle (SharedPreferences `gaee`, default **OFF**) is surfaced during
setup for the user or a family member to enable. Until it is on, the app stays **on-device Tier-1
only** and nothing is sent to the cloud. The `SUSPICIOUS` branch in `screenForScam` checks this flag
before any network call.

### False-positive guards (serve "don't misflag")

Applied in Tier 1 / the notification hook so genuine messages are not flagged:
- **Known contact → suppress.** Messages from a saved contact are not screened as scams
  (sender/number checked against `ContactResolverTool` / ContactsContract).
- **Legit-OTP shape → not dangerous.** A message that merely *delivers* an OTP ("123456 is your OTP,
  do not share") with no link and no "share/verify" ask is treated as safe.
- **De-dupe.** Never warn twice for the same sender + message within a short window.

Corresponding "don't miss" lever: Tier 1 leans toward **escalate**, not **declare** — anything even
slightly doubtful is routed to Tier 2 (when opt-in is on) rather than silently passed.

### Measured test corpus (proves the requirement)

`app/src/androidTest/java/com/gaee/ScamCorpusTest.kt` — ~40 labelled messages: India-context scams
(UPI-collect, KYC, electricity-disconnect, "digital arrest", fake-delivery) **and** legit lookalikes
(real bank OTPs, real delivery/OTP texts, family messages). Asserts Tier-1 precision/recall stays
within a set bar, so "rarely miss / rarely misflag" is verified, not asserted.

### Status

- ✅ **Tier 1 + rebalance + guards** — `engine/ScamDetector.kt`: keyword/score classifier that now
  leans *escalate, not declare*, plus the three false-positive guards (known-contact suppress,
  legit-OTP-delivery, and de-dupe — de-dupe lives in the service).
- ✅ **Tier 2 cloud adjudication** — `engine/ScamCloudCheck.kt`: one-shot Claude call
  (`claude-haiku-4-5`, mirrors `GaeeBackgroundWorker`), `ScreenRedactor.redact()` applied *before*
  the network, returns `safe/suspicious/dangerous` + reason; warns only on a confident `dangerous`.
- ✅ **Opt-in gate (logic)** — `GaeeNotificationService.isCloudScreeningEnabled` /
  `setCloudScreeningEnabled` (SharedPreferences `gaee` → `scam_cloud_enabled`, default **OFF**). The
  `SUSPICIOUS` branch escalates to Tier 2 only when this is on; otherwise stays on-device.
- ✅ **Wiring** — `service/GaeeNotificationService.screenForScam`: DANGEROUS → warn once;
  SUSPICIOUS → cloud (if opted in); SAFE → ignore. Known-contact guard via `ContactsContract`;
  `TtsTool` speaks; background `CoroutineScope` for the cloud call, cancelled in `onDestroy`.
- ✅ **Measured corpus** — `app/src/test/java/com/gaee/ScamCorpusTest.kt` (JVM unit test, runs
  without a device). **Result: 20 scams / 20 ham → 0 missed, 0 false alarms.**
  `testImplementation("junit:junit:4.13.2")` added for JVM logic tests.
- ✅ **Compiles** — `compileDebugKotlin`, `compileDebugAndroidTestKotlin`, and `testDebugUnitTest`
  all pass.
- ✅ **Opt-in toggle UI** — a "Smart scam protection" `SwitchMaterial` on the main screen
  (`activity_main.xml`), wired in `MainActivity.setupScamProtectionToggle()`: reflects the saved
  flag, and enabling shows a plain-language consent dialog (secrets hidden, off any time) before it
  turns on. Default OFF.
- ☐ **On-device pass** — verify end-to-end on a real device with the notification listener granted.

**Files:** `engine/ScamDetector.kt`, new `engine/ScamCloudCheck.kt`, additive changes in
`service/GaeeNotificationService.kt`, `ui/MainActivity.kt` + `res/layout/activity_main.xml` (toggle),
`app/build.gradle.kts` (junit test dep), and `app/src/test/java/com/gaee/ScamCorpusTest.kt`
(+ existing `androidTest/.../ScamDetectorTest.kt`). (No `ScamDetectorTool.kt` — screening runs in the
notification service, not as an LLM-composed tool.)

---

## Feature 2 — Proactive Voice Alerts / Assistant Initiative — HIGH PRIORITY

**What it does:** lets the assistant **speak first**, unprompted, for things that matter — a detected
scam (F1), a medicine reminder that wasn't acted on, an unusual bank transaction alert.

**Why it matters:** Phase 2 only ever reacts to a mic tap. Real protection requires initiative.

### Design decision — "speak + banner + history" (not banner-only)

A scam warning fires from the background service while the user is inside WhatsApp/SMS — *not* in
this app. So the alert must reach them **over another app**, which only a **heads-up notification**
can do. Rather than replace the spoken warning with a banner, F2 does **both** (voice is the
safety-net for users who can't read or miss the banner), and records each alert in an **in-app
history card** a caregiver can review.

### What was built — DONE

- ✅ **`engine/ProactiveNotifier.kt`** — reusable "speak up" channel. `alert(level, …)` with
  never-nag priority rules (`deliveryFor`): DANGEROUS → speak + heads-up banner; IMPORTANT → banner
  only; SILENT → log only. Owns a `TtsTool`; posts a high-importance heads-up notification on channel
  `gaee_scam_alerts` (mirrors `ReminderReceiver`), `setContentIntent` opens the app. No
  `setFullScreenIntent` — deliberately non-aggressive to avoid alarm fatigue. Notification IDs
  namespaced from reminders. `SecurityException` (permission off) is swallowed — voice still covers it.
- ✅ **`engine/ProactiveAlertLog.kt`** — in-memory (RAM-only, privacy), newest-first, capped at 20;
  `StateFlow<List<ProactiveAlert>>` backing the history card.
- ✅ **`service/GaeeNotificationService.kt`** — `warnOnce` now routes to
  `ProactiveNotifier.alertScam(...)` (speak + banner + log) behind the existing 5-min de-dupe; the old
  `tts`/`speakWarning` were replaced. `onDestroy` shuts the notifier down.
- ✅ **In-app history card** — `MainViewModel.scamAlerts` (its **own** flow, not `_uiState`, so it
  can't clobber the mic state machine); a `MaterialCardView` in `activity_main.xml`; `MainActivity`
  shows "⚠ N scam warnings. Tap to see." and opens a dialog listing them.
- ✅ **`POST_NOTIFICATIONS`** now requested at runtime (`MainActivity.requiredPermissions`, `SDK ≥ 33`)
  — was declared but never requested, so banners would have been silently dropped.
- ✅ **Tests** — `app/src/test/java/com/gaee/ProactiveAlertLogTest.kt` (JVM): log cap/order +
  priority rules (4/4 pass). `compileDebugKotlin`, `compileDebugAndroidTestKotlin`, `testDebugUnitTest`
  all pass.
- ☐ **On-device pass** — verify a scam SMS pops the banner over the Messages app + speaks + lands in
  the history card, and tapping the banner opens the app.

**Files:** new `engine/ProactiveNotifier.kt`, `engine/ProactiveAlertLog.kt`; modified
`service/GaeeNotificationService.kt`, `ui/MainViewModel.kt`, `ui/MainActivity.kt`,
`res/layout/activity_main.xml`; test `app/src/test/java/com/gaee/ProactiveAlertLogTest.kt`.

---

## Feature 3 — Personalization & User Memory — MEDIUM

**What it does:** the assistant learns the individual — routines ("calls daughter every Sunday"),
preferences ("always books the cheaper cab"), who matters, and how they phrase things.

**Why it matters:** turns a generic assistant into *their* assistant; improves cache hit rate and
disambiguation.

**What to build:**
- `UserProfileStore` (Room or SharedPreferences) — durable, on-device facts about the user.
- Extend the existing nickname map (`ContactResolverTool`) into a richer relationship/importance model.
- Feed relevant profile facts into the LLM prompt as context (this is the *first genuinely RAG-style*
  use — retrieved user facts augment generation).

**Files:** `engine/UserProfileStore.kt`; additive reads in `LlmPlanner`/`IntentClassifier` prompts.

---

## Feature 4 — Follow-Through & Gentle Monitoring — MEDIUM

**What it does:** closes the loop on reminders and watches for concerning patterns — e.g. a medicine
reminder fired but the user never acknowledged; repeated confusion on a screen.

**Why it matters:** an assistant that reminds but never checks back isn't really caring for the user.

**What to build:**
- Extend `ReminderTool` / WorkManager (`GaeeBackgroundWorker`) with acknowledgement tracking and a
  gentle re-prompt.
- Optional caregiver/family summary (see F6).

**Files:** additive to `tools/ReminderTool.kt`, `engine/GaeeBackgroundWorker.kt`.

---

## Feature 5 — RAG-Style Cache Enhancement — MEDIUM (ties Phase 2 cache → true RAG)

**What it does:** today a cache **miss** calls the LLM with *no* examples. This enhancement retrieves
the closest past plans (below the 0.78 hit threshold but still similar) and feeds them to the LLM as
**few-shot examples** — retrieval-**augmented** generation, layered *on top of* the existing cache.

**Why it matters:** better first-try plans on novel commands, without removing the fast exact-reuse
path. The current hit/skip-LLM behavior is unchanged; this only improves the miss path.

**What to build:**
- Add `findSimilar(intent, embedding, topK)` to `ActionCache` (does not touch `findBestMatch`).
- On a miss, pass those examples into `LlmPlanner.generatePlan` via a new optional param.

**Files:** additive methods in `engine/ActionCache.kt`, `engine/LlmPlanner.kt` (new optional param,
default keeps current behavior).

---

## Feature 6 — On-Device Intelligence & Privacy Hardening — LOWER

**What it does:** move more judgment (scam heuristics, intent classification) on-device so sensitive
decisions need the cloud less often. Complements the existing tier system (`ModelTier`) and
`ScreenRedactor`.

**Why it matters:** privacy + offline resilience for a vulnerable user base.

**What to build:** expand on-device heuristics in F1's first tier; optional on-device small model via
the existing `ModelDownloader` path.

---

## Feature 7 — Family / Caregiver Loop — OPTIONAL

**What it does:** with consent, a weekly summary or urgent alert (scam blocked, fall-word detected)
to a trusted family member.

**Why it matters:** elderly-care products live or die on the caregiver relationship. Strictly
opt-in; privacy-gated.

---

## Build order (recommended)

```
Stage 1 (protection first — highest user value):
  1. F1 ScamDetector + hook into GaeeNotificationService
  2. F2 ProactiveNotifier (so F1 can actually speak up)

Stage 2 (make it personal):
  3. F3 UserProfileStore + prompt context
  4. F5 RAG-style cache few-shot on miss

Stage 3 (care loop):
  5. F4 reminder follow-through
  6. F6 on-device hardening
  7. F7 caregiver loop (optional, opt-in)
```

---

## Phase 3 exit criteria

- [ ] Every Phase 1 & Phase 2 task still works identically (regression check — nothing removed).
- [ ] A known scam SMS triggers a spoken warning without the user asking (F1 + F2).
- [ ] The assistant references at least one learned personal fact in a task (F3).
- [ ] Cache-miss plans use retrieved examples; cache-hit path unchanged (F5).
- [ ] All new cloud calls pass through `ScreenRedactor`; guardrails 3a–3e still enforced.
- [ ] Full `gradlew build` succeeds; on-device pass completed.

---

*Extends `GAEE_Phase2_Plan.md` and `GAEE_Phase2_Implementation.md`. Phase 3 removes nothing —
it only protects, personalizes, and enhances.*
