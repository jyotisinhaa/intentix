# Launch Readiness — "Hello"

> **Status:** living document. Findings from a Principal-Architect audit of the codebase
> (2026-07-25). This is the "what must be true before we ship to real users" checklist.
> **Verdict: NOT launch-ready.** The prototype proves the idea; productization is the missing work.
> See `strategy.md` §7 — **do not do most of this before payment is validated.**

---

## 0. The one-line truth
The scam/assistant intelligence — the hard part — is largely done. What's missing is the
**boring-but-mandatory product plumbing**: backend, security, policy, hardening. Productization, not
more features.

---

## 1. 🔴 Blockers — must fix before ANY public build

### 1.1 Live API key compiled into the APK + no backend
- `gaee/app/build.gradle.kts` injects `CLAUDE_API_KEY` and `OPENWEATHERMAP_API_KEY` as
  `BuildConfig` string fields (from `gaee/.env`). They are embedded verbatim in `classes.dex` and
  trivially extractable with `apktool`/`jadx` — worse with R8 off (§2).
- **Every device calls `api.anthropic.com/v1/messages` directly** with the embedded key. Call sites:
  `LlmPlanner.kt`, `IntentClassifier.kt`, `ScamCloudCheck.kt`, `AnswerTool.kt`, `GaeeBackgroundWorker.kt`.
- **Impact:** anyone who installs can extract the key and spend against the Anthropic account without
  limit; every user's usage bills us with no metering.
- **Action:** (a) **rotate the key now** — treat as compromised; (b) build a **thin backend proxy**
  that holds the key, authenticates app instances, and meters usage. This is a real code change.

### 1.2 Hardcoded personal data shipped to every user
- `MainViewModel.init` writes a developer's personal alias into *every* install
  (`saveNickname("my love", "Anurag")`). **Remove immediately.**

### 1.3 No privacy policy / Data-Safety declaration
- The app sends **screen text, message bodies, approximate location, and voice transcripts** to a
  third party (Anthropic). No privacy policy or data-safety artifact exists in the repo.
- **Action:** write + host a privacy policy; complete Play Data Safety accurately (mandatory even for
  off-Play distribution as a trust artifact).

### 1.4 Google Play policy exposure (only if targeting Play — see `strategy.md`)
- **Accessibility service is maximally broad:** `accessibility_service_config.xml` has
  `packageNames=""` (all apps), `typeAllMask`, `canRetrieveWindowContent=true`,
  `canPerformGestures=true`. Using accessibility for general automation violates Play policy unless
  we qualify for an assistive-tech exception + declaration.
- **`SEND_SMS` + `CALL_PHONE`** are restricted permissions — Play generally grants them only to the
  default SMS/dialer app. **Likely rejection** for an assistant.
- **Notification Listener** needs a policy declaration + prominent disclosure.
- **Action (if Play):** replace SMS/Call with system intents (`ACTION_SENDTO`, dialer); frame
  accessibility honestly as an **assistive tool for low-vision/motor-impaired/elderly**; complete all
  declarations + prominent-disclosure screens. (Direct-APK distribution sidesteps this but keeps
  1.1–1.3.)

---

## 2. 🟠 High priority — before a real (non-toy) release

- **No signing config.** `build.gradle.kts` has no `signingConfigs`; release is unsigned. Add signing
  / Play App Signing.
- **R8/minify/shrink all OFF.** `release { isMinifyEnabled = false }`, no `shrinkResources`, no
  `proguardFiles`. `proguard-rules.pro` exists but is **never referenced** and is incomplete (only
  keeps `com.gaee.model`/`engine`; nothing for ONNX Runtime, Retrofit, OkHttp, Gson, Room). Turning
  minify on without fixing these will break Gson/Retrofit reflection and Room at runtime.
- **Placeholder applicationId `com.gaee`** — no real reverse-domain. It's **permanent once
  published**; set the real one (e.g. `com.<company>.hello`) before first release.
- **`allowBackup="true"`** over a **plaintext PII cache** — Room `action_cache` stores the literal
  user request (`intent`, `argsSlots`: contact names, numbers, message text) unencrypted, and it's
  included in cloud auto-backup. Disable backup or add backup rules; consider encryption.
- **Main-thread ANR on the flagship feature.** `ExecutionEngine.execute` is `suspend` with **no
  `withContext(IO)`**; `UINavigator`/`GaeeAccessibilityService.waitForScreen` run a
  `Thread.sleep`-style poll (up to ~6s) plus full node-tree traversal on `Dispatchers.Main`. Direct
  ANR risk during every multi-step automation. Offload to a background dispatcher.
- **No crash reporting.** No Crashlytics/Sentry — we'll be blind to production crashes. Also
  `LlmPlanner`/`ScamCloudCheck`/`AnswerTool` log raw Claude payloads to logcat (PII leak). Add crash
  reporting; strip PII from logs.

---

## 3. 🟡 Medium — durability & scale

- **Near-zero tests on the core.** Only 2 JVM tests (`ScamCorpusTest`, `ProactiveAlertLogTest`).
  `IntentClassifier` regex routing, `ActionCache`/`ActionPlanner` slot-filling + cosine matching, and
  `ExecutionEngine` (execution/re-plan/guards) are **untested** — the highest-risk, most logic-dense
  classes. Instrumented tests make live network calls (flaky, need secrets).
- **No CI, no lint.** `.github/` empty; no ktlint/detekt/.editorconfig. No static gate.
- **No cost metering / rate-limiting / billing** — a handful of users could run a large Anthropic
  bill (ties to the backend in 1.1).
- **No server-driven config.** Model ids, prompts, and scam rules are hardcoded across ~5 files
  (`claude-haiku-4-5`, `claude-sonnet-4-6`, `web_search_20260209`). A model rename or scam-rule update
  requires a new APK. Centralize + make server-updatable.
- **Non-resilient model download.** `ModelDownloader` deletes both files on any error, no
  retry/resume/backoff, no checksum, unpinned HuggingFace URL; a transient blip permanently degrades
  to keyword-only mode with no user feedback.

---

## 4. 🟢 Product-integrity caveats (not code bugs, but launch risks)
- **Automation is best-effort, not a guarantee** — tap-by-text breaks on UI changes, non-English
  labels, icon-only buttons, WebView/Compose screens. For this user, unreliable = churn
  (`strategy.md` §8). Mitigation is the **guide-and-teach fallback** (to build) + launching
  **narrow-and-bulletproof**.
- **Scam detection is keyword-based and India/English-locked** — 5-line `strings.xml`, no
  localization; defeated by obfuscation ("O.T.P", unicode look-alikes) and non-English scams. Do not
  market as guaranteed protection.

---

## 5. Sequencing (respect the validation discipline)
Per `strategy.md` §7: **validate payment → tiny hand-held beta → THEN productionize.** Most of §1–§3
is the "productionize" phase. The exceptions worth doing **immediately regardless** (cheap, pure
hygiene): **rotate the API key (1.1a)** and **remove the hardcoded nickname (1.2)**.
