# Architecture — "Hello" (GAEE engine)

> **Status:** living document — the technical map. Summarizes the shipped code; the root
> `GAEE_Phase2_Plan.md` / `GAEE_Phase2_Implementation.md` / `GAEE_Phase3_Plan.md` are the detailed
> engineering history. **Last updated:** 2026-07-25.

---

## 1. Stack
- **Android**, Kotlin only. **XML layouts + ViewBinding (not Compose)**. MVVM + coroutines.
- min SDK 26 / target 35. Single `:app` module, package `com.gaee` (placeholder appId — see
  `launch-readiness.md`).
- Retrofit/OkHttp (though Claude calls are hand-rolled OkHttp + org.json), Room (action cache),
  ONNX Runtime + ML Kit (on-device MiniLM embeddings), Gson, WorkManager.

## 2. Package map (`app/src/main/java/com/gaee/`)
- **engine/** — orchestration + intelligence: `VoiceListener`, `IntentClassifier`, `ActionPlanner`,
  `ActionCache` (+Dao/Database), `LlmPlanner`, `ExecutionEngine`, `EmbeddingEngine`,
  `WordPieceTokenizer`, `ModelDownloader`; scam stack (`ScamDetector`, `ScamCloudCheck`,
  `ScreenRedactor`, `SensitiveScreenGuard`, `DestructiveActionGuard`); proactive stack
  (`ProactiveNotifier`, `ProactiveAlertLog`); reminders (`ReminderScheduler`, `ReminderStore`);
  `GaeeBackgroundWorker`.
- **tools/** — one `BaseTool` per capability (~19): Alarm, Reminder, Call, Sms, AppLauncher, Volume,
  Wifi, Weather, Camera, Tts, UINavigator, WebFetcher, MediaController, ContactResolver,
  NotificationReader, ScreenReader, Scheduler, Answer, DeviceControl.
- **service/** — `GaeeAccessibilityService` (UI automation engine), `GaeeNotificationService`
  (notification capture + scam screening).
- **ui/** — `MainActivity`, `MainViewModel`, `ConfirmationDialog`.
- **receiver/** — `BootReceiver`, `ReminderReceiver`. **model/** — plain data classes.
- **src/debug/** — `DebugScamTrigger` (debug-only test hook; never in release).

## 3. Core orchestration path
```
MainActivity (mic tap)
  → MainViewModel.handleTranscript
    → IntentClassifier.classify        (on-device keywords; Claude Haiku fallback if online)
    → ActionPlanner.plan
        → ActionCache.findBestMatch    (MiniLM embedding + cosine ≥ 0.78 → reuse steps)
        → miss → LlmPlanner.generatePlan (Haiku/Sonnet) → cache the plan
        → deterministic fallbackPlan if LLM unavailable
    → ExecutionEngine.execute(steps)
        → run each tool step
        → UINavigator steps: read screen → verify → replan on failure (bounded)
        → guardrails: sensitive-screen handoff, destructive double-confirm
    → TtsTool speaks result
```
**Design bet:** the LLM is called ~once per *new* task type; the semantic cache handles it forever
after → most everyday tasks become fast, cheap, repeatable.

## 4. Model routing
`LlmPlanner.chooseModel`: simple intents → **`claude-haiku-4-5`** (cheap); complex/multi-step or >3
args → **`claude-sonnet-4-6`**. `replan` always uses Sonnet. Model ids are **hardcoded across ~5
files** — centralize later (`launch-readiness.md` §3).

## 5. The "control any app" mechanism (and its fragility)
`UINavigator` → `GaeeAccessibilityService` finds nodes by **visible text**
(`findAccessibilityNodeInfosByText` + lowercase `contains` traversal), does `ACTION_CLICK` /
`ACTION_SET_TEXT`, or falls back to coordinate gestures (`tapAt`, `swipe`). **This is best-effort,
not robust** — breaks on UI changes, non-English labels, icon-only controls, WebView/Compose screens,
and timing. The **replan "brain with eyes"** loop (re-read screen → Sonnet adapts) is the mitigation
(needs network + key, capped at `MAX_REPLANS=3`). The product answer to fragility is the
**guide-and-teach fallback** (`strategy.md` §2) + launching narrow. Do **not** market as a guarantee.

## 6. Guardrails (safety design — a genuine strength)
- **`ScreenRedactor`** — strips OTP/card/PIN digits before any screen text goes to the cloud.
- **`SensitiveScreenGuard`** — detects PIN/password/OTP/payment screens; the assistant **hands off to
  the human** and won't touch or transmit them.
- **`DestructiveActionGuard`** — irreversible taps (Delete/Block/…) require a **double-confirm**,
  default-reject.
- **Re-plan tool allowlist** — recovery may only use nav/observe tools, never initiate a new
  sensitive action. **`MAX_REPLANS=3`** bounds cost/loops.
- **Scam:** two-tier (on-device `ScamDetector` → opt-in `ScamCloudCheck`), redaction first, opt-in
  default OFF.

## 7. API key handling — current vs backend proxy (planned)

### Today (broken)
The Claude key is compiled into the APK (`BuildConfig.CLAUDE_API_KEY` from `gaee/.env`) and the phone
calls `api.anthropic.com` **directly** with it:
```
[Phone / APK]  --- holds the Claude key --->  [Anthropic]
```
Anyone can unzip the APK and extract the key → unlimited spend on our account, no metering, no way to
tie usage to a paying user. **This is a launch blocker** (`launch-readiness.md` §1.1).

### The fix — a backend proxy the app talks to instead
The APK **never holds the Claude key.** A server we control sits in the middle:
```
[Phone / APK] --(1) request + per-user token--> [OUR server] --(2, holds Claude key)--> [Anthropic]
[Phone / APK] <---------(3) answer relayed back------------- [OUR server]
```
- The **Claude key lives only on the server** (env var / secrets manager) — never shipped, not
  decompilable.
- The phone holds a **per-user login token** — not the Claude key. Useless against Anthropic
  directly; revocable per user; the hook for **usage metering + subscription checks** (the server
  refuses non-paying tokens → `monetization.md`).
- Bonus: model ids, prompts, and scam rules can move server-side → updatable **without a new APK**.

**Scope:** small. A few endpoints (auth + a Claude relay), the key as a server secret, simple
per-user auth (e.g. Firebase Auth to start), cheap hosting (a small function/container). App change =
swap `api.anthropic.com` + `x-api-key` for `our-server/…` + the user token in the ~5 direct call
sites (`LlmPlanner`, `IntentClassifier`, `ScamCloudCheck`, `AnswerTool`, `GaeeBackgroundWorker`).

**Timing:** this is **Stage 3** work — do **not** build it before payment is validated
(`roadmap.md`, `go-live-checklist.md`). The only key action for *now* is to **rotate** the leaked key.

## 8. Known architectural gaps (see `launch-readiness.md`)
- **No backend** — devices call Anthropic directly with an embedded key (see §7).
- **Main-thread blocking** in the automation path → ANR risk.
- **Everything client-side** — can't change model/prompt/scam-rule without an APK.
- **No i18n** — India/English-locked end to end.
- **Silent `catch(Exception)`** in ~25 files hides failures (e.g. corrupt cache row → empty plan →
  assistant silently does nothing).
