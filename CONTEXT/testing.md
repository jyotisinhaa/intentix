# Testing — "Hello"

> **Status:** living document — how we verify the product, and where the gaps are.
> **Last updated:** 2026-07-25.

---

## 1. What exists today
### JVM unit tests (`app/src/test/`, run without a device — CI-able)
- **`ScamCorpusTest`** — ~40 labelled India-context messages (20 scam / 20 genuine). Asserts
  **NO-MISS** (no scam judged SAFE) and **NO-FALSE-ALARM** (no genuine message judged DANGEROUS).
  Current result: **0 missed, 0 false alarms.** This is the model to copy — pure logic, measurable.
- **`ProactiveAlertLogTest`** — alert-log cap/order + never-nag priority rules (DANGEROUS→speak+notify,
  IMPORTANT→notify, SILENT→log-only).

### Instrumented tests (`app/src/androidTest/`, need a device + live key)
- `AnswerFlowTest`, `WeatherFlowTest`, `ReminderFlowTest`, `DeviceAndFallbackTest`, `ScamDetectorTest`.
- ⚠️ Several make **real network calls** (e.g. `AnswerFlowTest` hits live Claude) — flaky,
  non-hermetic, unrunnable in CI without secrets.

## 2. On-device manual test method (F1 + F2) — reusable
Validated on **Pixel 10 Pro, Android 16**. Because there's no SIM/live message, we use a
**debug-only broadcast** (`src/debug/DebugScamTrigger`, never in release) that drives the *real*
`ScamDetector` → `ProactiveNotifier` chain:

```
# install debug build, then:
adb shell pm grant com.gaee android.permission.POST_NOTIFICATIONS
# launch app once (clears "stopped" state, warms TTS), then fire:
adb shell "am broadcast -n com.gaee/.debug.DebugScamTrigger -a com.gaee.DEBUG_SCAM \
  -f 0x01000000 --es text 'You won a lottery. Claim at http://bit.ly/x'"
```
**Observed & verified:** DANGEROUS → heads-up banner **peeking over YouTube** + spoken warning +
in-app history card; genuine OTP ("123456 is your OTP…") → **SAFE, no alert** (no false alarm).
A real TTS bug was found and fixed this way (speech dropped before engine init → now queued).

## 3. The gaps (see `launch-readiness.md` §3)
- **Core orchestration is untested** — `IntentClassifier` (regex time/name/day parsing),
  `ActionCache`/`ActionPlanner` (slot-fill + cosine), `ExecutionEngine` (execute/replan/guards). These
  are the highest-risk, most logic-dense classes. **Biggest gap: `IntentClassifier` has no unit
  tests** despite heavy regex.
- **No CI** (`.github/` empty), **no lint** (ktlint/detekt/.editorconfig).
- Instrumented tests aren't hermetic (live API).

## 4. What to test next (priority order)
1. **`IntentClassifier` keyword/regex routing** — pure JVM tests over a table of utterances → expected
   intent+args (mirror `ScamCorpusTest` style).
2. **`ActionCache` cosine match + slot-filling** — deterministic, high-value.
3. **`ExecutionEngine` guards** — sensitive-screen handoff, destructive double-confirm, replan
   allowlist (logic, mockable).
4. **CI** — GitHub Actions running `testDebugUnitTest` + lint on every push; keep live-network tests
   out of the CI path.

## 5. The metrics that actually matter (product, not code)
Per `strategy.md` §7 — beyond unit tests, the launch-deciding measurements are:
- **Payment conversion** (landing → deposit/pay) — does the NRI child actually pay?
- **Week-2 retention** — do families still use it after the novelty? (where "do it for me" dies)
- **Automation success rate on the narrow task set** — reliability = retention.
- **Scam precision/recall on a growing real corpus** — no-miss / no-false-alarm, extended beyond 40.
