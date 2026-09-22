---
name: verify
description: Run all GAEE quality gates in order — build, unit tests, hazard scan, git hygiene — and report a pass/fail table. Use before committing or when asked to verify the project is healthy.
disable-model-invocation: true
---

Run every gate below in order. **Do not stop at the first failure** — run them all, then report one table, so the user sees the full picture rather than one error at a time.

All Gradle commands run from `gaee/`. PowerShell needs the `.\` prefix.

## Gate 1 — Compile

```
.\gradlew.bat assembleDebug
```

Fastest signal. If this fails, gates 2 still runs but expect noise; note the dependency in your report.

## Gate 2 — JVM unit tests

```
.\gradlew.bat testDebugUnitTest
```

Paste the real summary line. Never report green from memory.

## Gate 3 — Hazard scan

Grep the working tree for the failure modes that matter in this app. Report each hit with file:line, and judge it in context — a grep hit is a candidate, not automatically a defect.

```
grep -rn "catch (_: Exception) { }\|catch (e: Exception) { }" gaee/app/src/main
grep -rn "ToolResult(false, \"\")" gaee/app/src/main
grep -rn "YOUR_CLAUDE_API_KEY\|YOUR_OPENWEATHERMAP_API_KEY\|sk-ant-" gaee/app/src/main
grep -rn "Log\.\|printStackTrace" gaee/app/src/main
grep -rn "!!" gaee/app/src/main/java/com/gaee/tools
grep -rn "GlobalScope" gaee/app/src/main
```

Flag as CRITICAL: a hardcoded key, a failure path with no `speakAfter`, or screen/message text reaching `Log.*`.

## Gate 4 — Secret hygiene

```
git -C E:/lumi check-ignore gaee/.env
git -C E:/lumi log --all --oneline -- gaee/.env
git -C E:/lumi status --short
```

`.env` must be ignored and must have never been committed. Also confirm no change is staged to `gaee/local.properties` — it is machine-specific.

## Gate 5 — Wiring

For any tool added or changed since the last commit, confirm it is reachable end to end:
- registered where `ExecutionEngine` resolves tools by name
- named in `IntentClassifier`'s intents and `LlmPlanner`'s tool list
- any new permission present in `AndroidManifest.xml`

An unreachable tool is dead code no matter how well it compiles.

## Report

```
| Gate            | Result | Detail                          |
|-----------------|--------|---------------------------------|
| Compile         | PASS   | assembleDebug 42s               |
| Unit tests      | FAIL   | 2 failed of 14 — ScamCorpusTest |
| Hazard scan     | WARN   | 1 empty catch, CallTool.kt:88   |
| Secret hygiene  | PASS   | .env ignored, never committed   |
| Wiring          | PASS   | ScreenReaderTool reachable      |

Verdict: FAIL — fix unit tests before committing.
```

Verdict is FAIL if any gate fails, WARN if only warnings, PASS otherwise. State plainly what is broken; do not soften it.
