# Go-Live Checklist — "Hello"

> **Status:** living document. The **single ordered list** of everything from now → publicly live,
> with status. This is the operational companion to `roadmap.md` (which explains the *why*/staging);
> this file is the *what next, in order*. **Last updated:** 2026-07-25.

**Status key:** ✅ done · 🔄 in progress · ⬜ not started · ⛔ **GATE** (must pass before continuing) ·
🔴 urgent

> **Guiding rule (`strategy.md` §7):** validate demand *before* building scale/backend. The gates
> below enforce it — don't skip ahead.

---

## Phase 0 — Immediate hygiene & safety (do now, no dependencies)
| # | Step | Status |
|---|---|---|
| 0.1 | 🔴 **Rotate the Anthropic API key** (shipped in the APK → treat as compromised). Do it in console.anthropic.com. *(Only you can do this.)* | ⬜ |
| 0.2 | Remove hardcoded `"my love" → "Anurag"` nickname from `MainViewModel` | ⬜ |
| 0.3 | Commit the `CONTEXT/` folder + 0.2 fix to git | ⬜ |

## Phase 1 — Validate demand ⛔ (the make-or-break gate)
| # | Step | Status |
|---|---|---|
| 1.1 | Write the pitch: child's relief + parent's safety (`monetization.md`) | ⬜ |
| 1.2 | Build a priced landing page with a real "Start / Pre-order" button | ⬜ |
| 1.3 | Drive traffic from NRI / elder-care communities + own network | ⬜ |
| 1.4 | ⛔ **GATE: do real NRI children actually pay / deposit?** If no → stop and rethink, do **not** build the backend | ⬜ |

## Phase 2 — Prove the product with a tiny beta (5–10 families, hand-held)
| # | Step | Status |
|---|---|---|
| 2.1 | Build the **guide-and-teach hybrid** (narrate + step-by-step fallback) — highest-value unbuilt feature | ⬜ |
| 2.2 | Narrow to a **bulletproof ~5-task set** (99% reliable) instead of "any task, 80%" | ⬜ |
| 2.3 | Build the **"child loop"** — opt-in alerts to the child abroad | ⬜ |
| 2.4 | **Share the beta via APK (available NOW)** — a build already exists at `gaee/app/build/outputs/apk/debug/app-debug.apk`; the child sideloads it on the parent's phone. **Rotate the key (0.1) first.** No Play Store needed for beta. | ⬜ |
| 2.5 | Recruit + manually onboard 5–10 real families | ⬜ |
| 2.6 | ⛔ **GATE: week-2 retention + is the child willing to keep paying?** If they churn → fix the product before scaling | ⬜ |

## Phase 3 — Build to ship (only after Phase 1 & 2 pass)
| # | Step | Status |
|---|---|---|
| 3.1 | **Backend proxy** — holds the Claude key, per-user auth, usage metering (`architecture.md` §7) | ⬜ |
| 3.2 | Move the ~5 direct Claude call sites to the proxy; key leaves the APK entirely | ⬜ |
| 3.3 | **Billing / subscription** integration (`monetization.md`) | ⬜ |
| 3.4 | Fix the **main-thread / ANR** issue in the automation path | ⬜ |
| 3.5 | Add **crash reporting** (Crashlytics/Sentry); strip PII from logs | ⬜ |
| 3.6 | Release hardening: **signing config**, **R8** with correct keep rules, real **applicationId**, disable/limit **backup** | ⬜ |
| 3.7 | Write + host **privacy policy**; prepare **Data-Safety** declaration | ⬜ |

## Phase 4 — Distribution decision & go live

> **APK now vs Play later (important):** an **installable APK exists today** (see 2.4) and is the
> right vehicle for the beta — the child sideloads it, which fits our distribution model. The
> **Play Store is NOT available yet**: blocked by no signing/upload key, no privacy policy,
> placeholder `com.gaee` appId, a Play Console account ($25), and — the big one — the **restricted
> permissions** (Accessibility-all-apps + SMS + Call), which risk rejection. Play is a *scale-phase*
> decision after validation + the policy re-scope below.

| # | Step | Status |
|---|---|---|
| 4.1 | ⛔ **Decide: direct APK vs Play Store** (`strategy.md` §4) — changes 4.2 | ⬜ |
| 4.2a | *If Play:* replace SMS/Call with intents; frame accessibility as assistive; complete permission declarations + prominent disclosure (`launch-readiness.md` §1.4) | ⬜ |
| 4.2b | *If direct APK:* hosting + a guided install flow (the **child** installs) + "unknown sources" walkthrough | ⬜ |
| 4.3 | Final on-device QA pass across a few real phone models / Android versions | ⬜ |
| 4.4 | 🚀 **Publish / distribute the first public build** | ⬜ |

## Phase 5 — Post-launch durability
| # | Step | Status |
|---|---|---|
| 5.1 | Tests on the core (IntentClassifier / ActionCache / ExecutionEngine) + **CI** + lint | ⬜ |
| 5.2 | Server-driven config (models, prompts, scam rules) — update without an APK | ⬜ |
| 5.3 | Resilient model download (retry/resume/checksum) | ⬜ |
| 5.4 | Hindi/Hinglish + localization (`strings.xml` is English-only) | ⬜ |
| 5.5 | Stronger scam detection (beyond keyword matching) | ⬜ |
| 5.6 | Grow real scam corpus; monitor precision/recall + retention in production | ⬜ |

---

## Already done (context — not part of the remaining path)
| Item | Status |
|---|---|
| Engine + ~19 tools, semantic cache, "brain with eyes" | ✅ |
| F0 conversational answers | ✅ |
| F1 scam detection (two-tier) + corpus (0 miss / 0 false alarm) | ✅ |
| F2 proactive alerts (speak + banner + history card) — verified on device | ✅ |
| Safety guardrails (redaction, sensitive-screen handoff, destructive double-confirm) | ✅ |
| Strategy + CONTEXT documentation | ✅ |

---

## The honest shape of the remaining work
- **Phase 0** is hours. **Phase 1** is the whole ballgame — cheap, and it decides if the rest happens.
- **Phases 3–4** (backend, billing, hardening, policy) are the real engineering lift, but they're
  **deliberately last** — you only pay that cost once demand is proven.
- The impressive part (the assistant/scam intelligence) is mostly ✅. The remaining path is
  **productization + validation**, not more features.
