# Proposal Gap Analysis — built vs pending till release

> **Status:** living document. Cross-references the original `GAEE_Proposal_final.docx` (4-phase
> plan) against the **actual codebase** (verified via architecture audits, 2026-07-25).
> **Note:** the proposal's **Phase 3 = "Family Dashboard & Polish"** — *different* from the repo's
> `GAEE_Phase3_Plan.md` (which was scam/protection). This doc follows the **proposal's** phases.
> Status: ✅ done · ⚠️ partial/stubbed · ❌ missing · ➕ built beyond proposal.

---

## Phase 1 — The Skeleton — ✅ mostly done
| Proposal item | Status | Note |
|---|---|---|
| Android project setup (Kotlin, SDK 26, Accessibility, overlay, mic) | ✅ | |
| Voice pipeline (SpeechRecognizer) | ✅ | `VoiceListener` |
| Intent classifier + structured-JSON prompt | ✅ | Expanded well beyond the original 10 intents |
| Tool library v1 (system control, launcher, comms, TTS) | ✅ | As separate tools: Alarm/Reminder/Call/Sms/AppLauncher/Volume/Wifi/Camera/Weather/Tts |
| Big-button spoken confirmation dialog | ✅ | `ConfirmationDialog` |
| Hardcoded top-10 action plans | ✅ | Later replaced by cache (as intended) |
| **Tier-1 on-device Gemini Nano** | ⚠️ **STUBBED** | `checkOnDeviceModel()` always returns false; `classifyOnDevice()` just calls keywords. The "three-tier" promise is really **two-tier** (cloud + keyword) in practice |
| **First real elderly user test on the app** (Criterion 12 — *"the only one that matters"*) | ❌ | 20 *interviews* were done, but not watching a 60+ user complete tasks *on the app* |

**Verdict:** functionally complete except the on-device tier (stubbed) and the real-user app test.

---

## Phase 2 — The Cache Engine — ✅ built
| Proposal item | Status | Note |
|---|---|---|
| MiniLM embeddings (ONNX) | ✅ | `EmbeddingEngine` |
| Room/SQLite action cache | ✅ | `ActionCache` |
| Cosine similarity search (≥0.78) | ✅ | |
| Cache miss → LLM → cache write | ✅ | `ActionPlanner` |
| UINavigator (AccessibilityService) | ✅ | The "no-API" superpower — but best-effort/fragile |
| WebFetcher + weather API | ✅ | Needs OpenWeatherMap key |
| MediaController (YouTube/Spotify/gallery) | ✅ | |
| Remove hardcoded plans | ✅ | |
| **Run 100 test commands / measure hit + success rate** | ❌ | Not done — no measured hit-rate; tests are minimal |

**Verdict:** capability is there; the **measurement/validation step was skipped**.

---

## Phase 3 (proposal = Family Dashboard & Polish) — ⚠️ partial, biggest gaps
| Proposal item | Status | Note |
|---|---|---|
| **Family Dashboard** (task history, trusted contacts, shortcut config, scam log) | ❌ | The proposal's **headline** + *"primary sales and retention tool"* — not built |
| **Companion app** for family (React Native/Flutter) | ❌ | Not built |
| **Cloud sync** (on-device events → encrypted log → family app; Supabase backend) | ❌ | **No backend exists at all** |
| **Teach Mode** (watch-and-record → new cache entry) | ❌ | Not built |
| Scam detection classifier | ✅ | F1 — but **keyword-based**, not "fine-tuned on scam transcripts" |
| Large-model integration (complex tasks) | ✅ | Sonnet routing |
| WorkManager night jobs | ✅ | `GaeeBackgroundWorker` / `SchedulerTool` |
| UX polish (font/contrast/slow TTS/friendly errors) | ⚠️ | Slow TTS ✅; friendly errors partial; broad polish no |
| Recruit 10 elderly beta users, run 4 weeks | ❌ | Not done |
| — Built *beyond* proposal — | ➕ | **F0 conversational Q&A** (`AnswerTool`) ✅ and **F2 proactive alerts** (banner + history card) ✅ |

**Verdict:** the *protection/intelligence* slice got built (and extended); the *family/backend/teach*
slice — the part the proposal said families pay for — is **entirely missing**.

---

## Phase 4 — Distribution & Business (= "release") — ❌ nothing done
| Proposal item | Status |
|---|---|
| Google Play launch | ❌ |
| Freemium billing ($9.99/mo family) | ❌ |
| Partnerships (senior communities / AARP / elder care) | ❌ |
| Content / YouTube / referrals / testimonials | ❌ |
| Technical launch blockers (embedded key/no proxy, signing, R8, privacy policy, ANR, Play policy) | ❌ | see `launch-readiness.md` |

---

## The "pending till release" shortlist (against the proposal)
1. **Family Dashboard + companion app + backend/sync** — the biggest gap; the proposal's paid core.
2. **Backend** (nothing exists) — also needed for the API key proxy, billing, and the dashboard sync.
3. **Teach Mode** — a proposal Phase-3 feature, unbuilt.
4. **On-device Tier-1 (Gemini Nano)** — stubbed; the three-tier promise is unmet.
5. **Real validation** — the skipped measurements (100-command hit-rate; 10 elderly users × 4 weeks;
   the Criterion-12 app test).
6. **All of Phase 4** — Play launch, billing, distribution — plus every technical launch blocker.

---

## Reconciliation with current strategy (read this — the proposal is the *old* plan)
The proposal predates this session's strategy pivot. Two big reframes:

- **Family Dashboard ≈ the "child loop"** (`strategy.md`). Good news: the single biggest *proposal*
  gap is *also* the most important item under the **NRI wedge** — they converge. Build it as the
  child-facing safety/updates loop, not a generic dashboard.
- **Release path is reframed.** The proposal assumes a week-13 **Play launch + $9.99 freemium**.
  Current strategy **defers Play** (restricted-permission rejection risk) and **validates payment
  first** → see `roadmap.md` (validate → tiny beta → productionize) and `go-live-checklist.md`.
  So "till release" is now the checklist's Phases 1–4, not the proposal's Phase 4.
- **Some proposal scope is now deprioritized / on the kill list** (`strategy.md` §6): the broad
  "any task" ambition → a **narrow bulletproof task set**; "everyone" → **NRI families only**.

**Bottom line:** Phases 1–2 are essentially done (minus validation + the stubbed on-device tier).
The proposal's **Phase 3 family/backend half and all of Phase 4 are the real remaining work** — and
the most valuable of it (the family/child loop + backend) is exactly what the current strategy also
puts next.
