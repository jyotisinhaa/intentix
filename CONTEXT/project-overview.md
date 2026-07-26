# Project Overview — "Hello" (GAEE)

> **Status:** living document. This is the top-level anchor for the `CONTEXT/` folder; other context
> files (strategy, launch-readiness, monetization, roadmap) hang off this one.
> **Last updated:** 2026-07-25.

> **Confirmed product decisions** (strategy lives in `strategy.md` — read that first)
> 1. **Product name — "Hello"** (public-facing). **GAEE** is the internal engine codename.
> 2. **Wedge — immigrant / NRI families.** The **adult child abroad** is the payer, installer, and
>    trust-bridge; the **parent alone back home** (India) is the user. Not "elderly" generically.
> 3. **Product shape — guide + automate + teach.** A narrating helper that does tasks, says what it's
>    doing, and **falls back to step-by-step guidance** when automation can't — never a dead end.
> 4. **Primary market — India (parents) via the diaspora (payers)**; English now, Hindi/Hinglish next.
> 5. **Monetization specifics** (price, free-vs-paid, packaging) **to be decided** in `monetization.md`.
>    No numbers invented here. Validate payment *before* productionizing (see `strategy.md` §7).

---

## 1. What this project does

**Hello is an Android assistant that lets an older person use their phone by voice — and lets their
adult child abroad keep them independent and safe from afar.** It is aimed squarely at **NRI
families**: the parent alone back home is the user; the child overseas is the buyer (see
`strategy.md`).

Three halves:

1. **Do what you ask, and show you how (guide + automate + teach).** A voice command is understood by
   an LLM, which composes a plan from a library of **generic tools** and executes it by driving real
   app UIs through Android's Accessibility service — **no per-app APIs**. Critically, it **narrates**
   what it's doing and, when automation can't complete a step, **falls back to step-by-step guidance**
   ("tap the three dots at the top right") so the parent finishes it themselves. Never a dead end.
2. **Look out for you (protection).** Even when the user asks nothing, the phone watches incoming
   messages and **warns about scams** — a spoken warning + an on-screen banner over whatever app
   they're in. This is the emotional hook that makes the distant child pay.
3. **Answer you (conversation).** Questions that aren't device actions are answered conversationally.

The core bet: **one LLM + a small set of generic tools + a self-learning cache**, wrapped in a
narrate-and-teach experience, can help a vulnerable user do and understand phone tasks — and protect
them — in a way Big Tech's silent, general assistants don't.

---

## 2. Goals

**Product goals**
- Make a modern smartphone genuinely usable by an older parent through **voice + guidance** — doing
  tasks *and* teaching, so they gain independence rather than a black box.
- **Protect** them from fraud proactively (the emotional hook that makes the distant child pay).
- **Relieve the adult child** — fewer support calls, less worry, from another country.
- Be **trustworthy**: privacy-first (on-device where possible), never acts on money/credentials
  without the human, never nags. Trust is transferred through the child who installs it.

**Business goal (to refine in `monetization.md`)**
- Turn this into a **paid product** with **scam protection as the headline value**.
- **Dual audience — either can be the user *and* the buyer:** an elderly user for themselves, or a
  family member for their parent. So the product must be **usable and purchasable both ways** —
  simple enough for an older adult to run solo, and easy for family to set up **remotely** in minutes.
- Pricing / packaging / free-vs-paid are deferred to `monetization.md`.

---

## 3. Core user flows

### 3a. Assistant flow (reactive — "say anything → phone does it")
```
User taps mic and speaks
   → transcribe to text
   → classify intent (on-device keywords, else Claude)
   → check semantic action cache
        • hit  → fill in the details and run instantly (~cache speed)
        • miss → LLM generates a step-by-step plan, then caches it
   → execute step-by-step via tools + Accessibility service
        • after each UI step, read the screen to verify
        • if a step fails, re-plan from what's actually on screen (bounded retries)
        • stop and hand off if a PIN / password / payment screen appears
   → speak the result
```
After the LLM is called **once** for a new kind of task, the cache handles it forever → most everyday
tasks become fast, repeatable, and cheap.

### 3b. Protection flow (proactive — "looks out for you")
```
A message notification arrives (WhatsApp, SMS, etc.)
   → on-device scam check rates it: Safe / Not-sure / Scam
        • Safe    → do nothing
        • Scam    → warn now: heads-up banner over the current app + spoken warning + history entry
        • Not-sure→ stay silent on-device; if "Smart scam protection" is ON, ask Claude to judge,
                     and warn only if Claude is confident it's dangerous
   → the warning names the reason in plain words and always adds safe advice
     ("don't tap links, never share your PIN/OTP")
```

### 3c. Conversation flow (answers, not just actions)
Anything that isn't a device action ("how far is the moon?", "what's today's date?") is answered
conversationally via Claude + web search, grounded in the phone's date/time and rough location.

---

## 4. Features

### Built (working today)
| Area | Feature |
|---|---|
| Understanding | Voice → intent classification (on-device keywords + Claude fallback) |
| Conversation | "Ask anything" answers via Claude + web search (grounded in date/place) |
| Doing things | Generic tool library: alarms, reminders, calls, SMS, **WhatsApp via UI**, weather, app launch, media play/control, volume, Wi-Fi, device control (home/recents/back), camera |
| Reliability | Self-learning **semantic action cache**; **"brain with eyes"** execution — verify screen, re-plan on failure |
| Narrate + teach *(to build)* | Speak each step aloud and **fall back to guided step-by-step instructions** when automation can't complete — the hybrid that makes fragile automation safe (see `strategy.md` §2) |
| Routing | Simple tasks → cheap model (Haiku); complex/multi-step → stronger model (Sonnet) |
| Safety | Screen-text **redaction** before cloud; **sensitive-screen handoff** (won't touch PIN/OTP/payment); **destructive-action double-confirm**; bounded re-plans |
| **Protection (F1)** | **Two-tier scam detection** — fast on-device keyword/score check → opt-in Claude adjudication for uncertain cases; false-positive guards (known contact, legit-OTP, de-dupe) |
| **Proactive alerts (F2)** | **ProactiveNotifier** — speaks + posts a heads-up banner over other apps + in-app **history card**; never-nag priority rules |
| Trust | **Opt-in** "Smart scam protection" toggle; cloud scam-check is off by default |

*Validated on a real device (Pixel 10 Pro, Android 16): scam → banner over YouTube + spoken warning +
history card; genuine OTP → no false alarm.*

### Planned (see `roadmap.md`)
- **Narrate + guided-fallback** — the guide/teach half of the core product (highest priority; makes
  fragile automation safe — `strategy.md` §2).
- **The child loop** — opt-in updates / urgent alerts (scam blocked, unusual activity) to the **NRI
  child abroad**. The heart of the value proposition and the likely monetization hook.
- **Personalization / user memory** — learn routines, relationships, preferences (RAG-style).
- **RAG cache** — feed nearest past plans as few-shot examples on a cache miss.
- **Reminder follow-through** — check the user actually acted on a reminder.
- **On-device hardening** — more judgment on-device, less cloud.

---

## 5. Scope

### In scope
- **Android** phones (min SDK 26 / Android 8+), single app (`com.gaee`).
- **Voice-first** operation for common daily tasks via generic tools + Accessibility.
- **Proactive scam/fraud protection** on incoming messages.
- **Privacy-respecting** design: on-device first; explicit opt-in before any message text leaves the
  phone; secrets redacted before any cloud call.
- **India-first** scam knowledge and language (English now; Hindi/Hinglish later), sold via the
  diaspora (the NRI child is the buyer).

### Out of scope — the kill list (see `strategy.md` §6)
Parked deliberately to protect focus; may return as expansions *after* the wedge is won:
- **Young-professional / general productivity** (calendar nudges, trip planning) — no payer, brutal
  competition, no validation.
- **"Hands-free for everyone"** — no pain, no payer, head-on with free Siri/Google.
- **Real-world physical navigation / wayfinding** ("your cab is opposite the supermarket") — a
  different, far harder product; someday, not now.
- **Marketing "controls any app" as a guarantee** — automation is best-effort, not a promise.

Also out of scope (structural):
- **iOS** (Apple restricts the Accessibility-driven approach).
- **Rooting / custom ROM** — must work on a normal, unrooted phone.
- **Acting on money or credentials autonomously** — the human always completes payment/PIN/OTP steps.
- **A full replacement launcher / home screen** — Hello is an assistant, not the OS shell.
- **Guaranteed scam detection** — a strong best-effort filter, explicitly *not* a security guarantee.
- **Medical, legal, or financial advice** beyond safe, general guidance.

---

## 6. Success criteria

**Product works if…**
- A first-time elderly user can complete the top ~10 everyday tasks **by voice**, hands-free, without
  training.
- After ~2 weeks of use, the large majority of everyday commands are **fast cache hits** (LLM rarely
  re-invoked for known tasks).
- A known scam message triggers a **spoken + on-screen warning without the user asking**, and a
  genuine message (real bank OTP, family text) is **not** flagged — measured, not asserted
  (current corpus: 20 scams / 20 genuine → 0 missed, 0 false alarms on the on-device tier).
- The assistant **never** operates on a PIN/OTP/payment screen and **never** sends a message text to
  the cloud without the user's opt-in.

**Business works if… (to refine in `monetization.md`)**
- An **NRI adult child pays** — for the safety, the independence it gives their parent, and the
  relief of fewer support calls from across the world. (Payment is the #1 unvalidated risk —
  `strategy.md` §7.)
- Onboarding works via the **child**: they install and set it up during a home visit or a guided
  remote session (not "the parent does it alone in minutes").
- Retention holds because the protection is felt (warnings that land) without nagging (few false
  alarms).

---

## 7. Related context files
- `strategy.md` — **written.** Wedge (NRI), payer vs user, product shape, positioning, distribution,
  moat, kill list, validation sequence, risks. *Read this first for any product decision.*
- `launch-readiness.md` — *to write.* The technical/security/policy blockers from the architecture
  audit (embedded API key, no backend proxy, Play policy on Accessibility+SMS, signing/R8, ANR,
  privacy policy, crash reporting).
- `monetization.md` — *to write.* Who pays, pricing, packaging, the payment-validation test.
- `roadmap.md` — *to write.* Phased build order aligned to the validation sequence.
- `architecture.md`, `features.md`, `privacy-and-trust.md`, `testing.md` — *to write* as needed.

*(Existing planning docs at repo root — `GAEE_Phase2_Plan.md`, `GAEE_Phase2_Implementation.md`,
`GAEE_Phase3_Plan.md` — are the detailed engineering history these context files summarize and
supersede for product-level decisions.)*
