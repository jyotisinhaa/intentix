# Features — "Hello"

> **Status:** living document — per-feature spec + status. **Last updated:** 2026-07-25.
> Status key: ✅ built & verified · 🟡 partial · ⬜ planned · ❌ parked (kill list, `strategy.md` §6).

---

## Assistant core
| Feature | Status | Notes |
|---|---|---|
| Voice → intent classification | ✅ | On-device keywords; Claude Haiku fallback when online; keyword-only offline |
| Generic tool library (~19 tools) | ✅ | Alarm, reminder, call, SMS, WhatsApp-via-UI, weather, app launch, media, volume, Wi-Fi, device control, camera, etc. |
| Semantic action cache | ✅ | MiniLM embeddings + cosine ≥ 0.78; LLM called once per new task type |
| "Brain with eyes" executiVon | ✅ | Verify screen after UI steps; replan on failure (Sonnet, bounded) |
| Model routing (Haiku/Sonnet) | ✅ | Simple → Haiku, complex → Sonnet |
| **F0 — Conversational "Ask anything"** | ✅ | `AnswerTool`: Claude Sonnet + web search, grounded in date/location |
| **Narrate + guided-fallback (guide-and-teach)** | ⬜ | **Highest-priority unbuilt feature** — narrate each step + step-by-step guidance when automation can't (`strategy.md` §2, `roadmap.md` Stage 2) |

## Protection (the paid hook)
| Feature | Status | Notes |
|---|---|---|
| **F1 — Scam detection (two-tier)** | ✅ | On-device `ScamDetector` (safe/suspicious/dangerous, escalate-not-declare) → opt-in `ScamCloudCheck` (Claude Haiku, redacted). Guards: known-contact, legit-OTP, de-dupe. Corpus: 20 scams/20 ham → 0 miss, 0 false alarm |
| **F2 — Proactive alerts** | ✅ | `ProactiveNotifier`: speaks + heads-up banner over other apps + in-app history card; never-nag priority rules. Opt-in "Smart scam protection" toggle (default OFF). **Verified on device** (Pixel 10 Pro, Android 16) |
| **The "child loop"** | ⬜ | Opt-in alerts/updates to the NRI child abroad (scam blocked, unusual activity). Core value prop + likely paid feature (`roadmap.md` Stage 2) |

## Safety guardrails
| Feature | Status |
|---|---|
| Screen-text redaction before cloud (`ScreenRedactor`) | ✅ |
| Sensitive-screen handoff (PIN/OTP/payment) | ✅ |
| Destructive-action double-confirm | ✅ |
| Re-plan tool allowlist + `MAX_REPLANS` bound | ✅ |

## Planned (see `roadmap.md`)
| Feature | Status |
|---|---|
| Personalization / user memory (RAG-style) | ⬜ |
| RAG cache few-shot on miss | ⬜ |
| Reminder follow-through | ⬜ |
| On-device hardening | ⬜ |

## Parked — kill list (`strategy.md` §6)
| Idea | Status |
|---|---|
| Young-professional / general productivity | ❌ |
| Hands-free for everyone | ❌ |
| Real-world physical navigation / wayfinding | ❌ |
| "Controls any app" marketed as a guarantee | ❌ |
