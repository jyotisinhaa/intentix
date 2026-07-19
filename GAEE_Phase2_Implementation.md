# GAEE Phase 2 — Implementation & Guardrails

**Status as of 2026-06-30.** This document records what was actually built to complete Phase 2
("LLM as the brain of the phone"), how the autonomous execution loop works, and the safety
guardrails layered on top. It is the companion to the roadmap in `GAEE_Phase2_Plan.md`.

> **One remaining blocker before anything LLM-driven runs:** the two API keys are still
> placeholders — `claudeApiKey` in `app/src/main/java/com/gaee/engine/IntentClassifier.kt` and
> `apiKey` in `app/src/main/java/com/gaee/tools/WeatherTool.kt`.

---

## 1. Phase 2 tools completed

| Capability | Where | Notes |
|---|---|---|
| Screen "eyes" | `GaeeAccessibilityService.readScreen()` | Dumps visible text + `{screenText}`/`{appName}` |
| Swipe / tap-at / wait | `GaeeAccessibilityService` | `swipe`, `tapAt`, `waitForScreen`; exposed via `UINavigator` actions `swipe/read/wait/tap_at` |
| ScreenReaderTool | `tools/ScreenReaderTool.kt` | "What does this say?" + internal verification |
| Sonnet routing | `LlmPlanner.chooseModel()` | Complex intents (WhatsApp, scheduling, >3 args) → `claude-sonnet-4-6`; simple → `claude-haiku-4-5` |
| Verification + retry | `ExecutionEngine.execute()` | Failed UI step retried once after 600 ms; 400 ms settle between UI steps |
| Background tasks | `tools/SchedulerTool.kt` + `engine/GaeeBackgroundWorker.kt` | WorkManager, charging constraint; result spoken on next app open |
| WhatsApp flow | `send_whatsapp` intent + UINavigator plan | No WhatsApp API — drives the real UI |
| New intents | `IntentClassifier`, `LlmPlanner` | `send_whatsapp`, `read_screen`, `go_back`, `play_media`, `control_media`, `schedule_task` |
| Permissions/deps | `AndroidManifest.xml`, version catalog | `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`; `androidx.work:work-runtime-ktx:2.9.1` |

(Already present before this pass and left intact: `ContactResolverTool`, `NotificationReaderTool`,
`GaeeNotificationService`.)

---

## 2. LLM as a "brain with eyes" — the re-planning loop

When executing a multi-step UI plan, each step acts on a **live** app screen that can differ from
what the plan assumed (slow app launch, popup, renamed button, different scroll position). The
execution loop in `ExecutionEngine.execute(steps, intent, replanner)` handles this in three stages:

```
Run a UINavigator step
  │
  ├─ fails?  → wait 600 ms, retry the SAME step once          (fixes transient timing)
  │
  ├─ still fails?  → read the REAL screen text
  │                 → ask Claude (Sonnet) to adapt the remaining steps to what's actually shown
  │                 → speak "Let me try a different way."
  │                 → replace the queue with the adapted steps and continue
  │
  └─ re-plan gave nothing?  → speak a graceful apology and stop
```

- Steps live in a **mutable queue** so re-planned steps splice in cleanly.
- Accumulated `{slot}` data (e.g. `{resolvedName}`, `{phone}`) carries across re-plans.
- `LlmPlanner.replan(intent, screenState, failedStep, context)` builds a recovery prompt (shares the
  `toolCatalog` with the initial planner) and calls Sonnet.
- **Bound:** `ExecutionEngine.MAX_REPLANS = 3` — the loop can never run forever, and Sonnet only
  fires on real failures (happy-path tasks cost nothing extra).

**Example:** WhatsApp renames "Send" to a "Send message" icon → the planned `tap "Send"` fails →
assistant reads the screen, sees "Send message", taps it, message goes out. No code change needed.

---

## 3. Guardrails

Autonomy that taps real buttons and re-plans from the cloud needs safety rails. These stack as
defense-in-depth.

### 3a. Screen-text redaction — DONE
`engine/ScreenRedactor.kt`. Before any screen text leaves the device for the cloud re-planner, it
strips:
- labeled secrets (`password / OTP / PIN / CVV / card no…` → value hidden, label kept),
- card-like numbers (13–19 digits, spaced/dashed),
- any run of 4+ digits (OTPs, PINs, balances, account numbers).

Applied in `LlmPlanner` to the re-planner's `screenState` **and** `contextJson` (context can hold a
prior screen dump and the `{phone}` number), and to the initial planner's optional screen state.
Real `{phone}`/`{slot}` substitution is unaffected — it happens later in `ExecutionEngine` from the
**un-redacted** on-device context, so Claude only ever sees `[hidden]`.

> Best-effort filter, **not** a security boundary. The real protection for money is 3b.

### 3b. Sensitive-screen "coach + wait" handoff — DONE
On a **PIN / password / OTP / payment** screen the assistant stops touching the UI and coaches the
user to finish that step themselves ("please type your PIN yourself — I won't touch it").

- `engine/SensitiveScreenGuard.kt` (pure/testable) → `assess(packageName, screenText, hasPasswordField)`
  returns `NONE / CREDENTIAL / PAYMENT`. Signals (either triggers): a known payment/bank app package,
  credential/payment keywords, or a masked password field.
- `GaeeAccessibilityService.assessSensitivity()` gathers the signals (reuses `collectText`, adds
  `hasPasswordField` via `node.isPassword`).
- `ExecutionEngine.sensitiveHandoff()` is checked **(a)** before every UINavigator tap/type/swipe and
  **(b)** before any cloud re-plan — so a payment/credential screen is never operated on **and never
  sent to the cloud**. On a hit it speaks `SensitiveScreenGuard.guidance(kind)` and returns.

### 3c. Re-plan tool allowlist — DONE
**Problem:** the re-planner's returned steps could use *any* tool, so Claude could inject
`CallTool` / `SmsTool` / `WebFetcherTool` (POST) / `AppLauncherTool` / `SchedulerTool` mid-task —
bypassing the classifier's confirmation gate that only runs on the original command.

**Fix (all-or-nothing):** in `LlmPlanner.replan()`, after parsing Claude's returned steps, validate
them against `replanAllowedTools = {UINavigator, ScreenReaderTool, TtsTool}`. If **every** step is
allowed → run it; if **any** step uses a blocked tool → return `emptyList()` (discard the whole
recovery). `ExecutionEngine` already treats an empty re-plan as "recovery failed" → graceful apology
+ stop, so **no ExecutionEngine change was needed**. `buildReplanPrompt()` also tells Claude up front
to use only those three tools (reduces rejected re-plans; the code filter is the real guarantee).
`AppLauncherTool` is intentionally excluded — recovery must not switch/relaunch apps. Principle:
**recovery may navigate, never initiate a new sensitive action.**

### 3d. Destructive-action double-confirm — DONE
The 3b handoff fully stops on money/credential screens. Irreversible-but-non-financial taps
(`Delete`, `Remove`, `Unsubscribe`, `Deactivate`, `Uninstall`, `Block`…) are caught by
`engine/DestructiveActionGuard.isDestructive(target)`. Before executing such a tap,
`ExecutionEngine.confirmDestructive()` **asks the user TWICE through a popup** and **defaults to
reject** (no undo):
1. Popup #1 — "I am about to tap \"Delete\". This cannot be undone. Do you want me to do it?"
2. Popup #2 (only if #1 = yes) — "This will \"Delete\" for good and cannot be reversed. Tap Yes only
   if you are certain."

Both must be **Yes** to proceed. Any No — or dismissing the popup (back / tap outside) — rejects and
stops the plan ("Okay, I did not do that…"). If no confirmer is wired (`confirm == null`), the action
is refused by default.

**Wiring:** `execute(steps, intent, replanner, confirm)` gained a suspending `confirm(title, message)`
callback. `MainViewModel.requestActionConfirm()` backs it with a `CompletableDeferred<Boolean>` and a
new `UiState.AwaitingActionConfirmation`; `MainActivity.showActionConfirmDialog()` renders a
`MaterialAlertDialogBuilder` popup (positive "YES, DO IT", negative "NO, STOP", cancel = No). The plan
coroutine suspends on `deferred.await()` until the user answers each popup.

### 3e. Existing bound
`MAX_REPLANS = 3` caps mid-execution re-plans per task (cost + loop safety). Already in place.

---

## 4. Files created / modified in this pass

**Created**
- `engine/GaeeBackgroundWorker.kt` — WorkManager CoroutineWorker (Sonnet), stores deferred result
- `tools/SchedulerTool.kt` — queues deferred/overnight tasks
- `tools/ScreenReaderTool.kt` — screen "eyes"
- `engine/ScreenRedactor.kt` — privacy redaction (3a)
- `engine/SensitiveScreenGuard.kt` — sensitive-screen classifier (3b)
- `engine/DestructiveActionGuard.kt` — irreversible-tap classifier (3d)

(3c re-plan allowlist added inside `engine/LlmPlanner.kt` — no new file.)

**Modified**
- `service/GaeeAccessibilityService.kt` — `readScreen`, `swipe`, `tapAt`, `waitForScreen`,
  `assessSensitivity`, `hasPasswordField`
- `tools/UINavigator.kt` — actions `swipe / read / wait / tap_at`
- `engine/LlmPlanner.kt` — `chooseModel`, shared `toolCatalog`, `replan` + recovery prompt,
  redaction hooks, new fallback plans
- `engine/ExecutionEngine.kt` — re-planning loop, verification/retry, `sensitiveHandoff`
- `engine/IntentClassifier.kt` — expanded system prompt + keyword branches for the new intents
- `ui/MainViewModel.kt` — passes `(intent, llmPlanner)` into `execute`; speaks deferred result
- `AndroidManifest.xml`, `gradle/libs.versions.toml`, `app/build.gradle.kts` — perms + WorkManager

---

## 5. Build & verification

- **Compile:** from `gaee/` run `gradlew.bat compileDebugKotlin` → BUILD SUCCESSFUL (only pre-existing
  deprecation / unused-var warnings remain).
- **Redaction sanity:** `"Your OTP is 483920"` → `"Your OTP is [hidden]"`; `"4532 1234 5678 9010"` →
  `[hidden]`; `"Send" / "Priya" / "Search"` unchanged.
- **Sensitive-screen sanity:** `("com.phonepe…","Enter UPI PIN",true)`→CREDENTIAL;
  `("chrome","Your OTP is … Verify",false)`→CREDENTIAL; `("com.phonepe…","Proceed to pay ₹840",false)`
  →PAYMENT; `("com.whatsapp","Search Priya Message Send",false)`→NONE.
- **On-device:** enable the accessibility service, then trigger a flow that reaches a password/OTP or
  a payment screen → assistant should speak the coaching line and stop (no tap/type, nothing sent to
  cloud). Normal WhatsApp/navigation flows behave as before.

---

## 6. Open items

The guardrail set is now **closed**: 3a redaction ✅, 3b sensitive-screen handoff ✅,
3c re-plan allowlist ✅, 3d destructive-tap double-confirm ✅, 3e `MAX_REPLANS` ✅.

1. **API keys** — set both before any live testing (see top of doc).
2. **Full `gradlew build` + on-device test** — only `compileDebugKotlin` has been run so far.
