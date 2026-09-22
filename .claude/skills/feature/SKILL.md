---
name: feature
description: Run the full engineering pipeline for a GAEE feature — plan, test, implement, build, review. Use when asked to build a feature end to end.
argument-hint: [what to build]
disable-model-invocation: true
---

Run the engineering pipeline for: **$ARGUMENTS**

Work through these stages in order. Each stage is a subagent launched with the `Agent` tool. Do not skip ahead, and do not start a stage until the previous one has reported back.

**1. Plan** — `planner`
Give it the feature description. It returns files, tool contract, steps, test list, risks, and any prompt updates needed.
Show me the plan and **stop for my approval before any code is written.**

**2. Tests first** — `test-engineer`
Pass the approved plan. It writes the JVM tests from the plan's test list, runs them, and confirms they fail for the right reason. Report the actual failure output.

**3. Implement** — `kotlin-dev`
Pass the plan and the failing tests. It writes the code, wires the tool into `ExecutionEngine` and the classifier/planner prompts, and adds any manifest permission.

**4. Build and go green** — `build-doctor`
From `gaee/`: `.\gradlew.bat assembleDebug` then `.\gradlew.bat testDebugUnitTest`. Loop with `kotlin-dev` until both pass. Paste the real output.

**5. Review** — `reviewer`
Fresh context, reads the diff, reports findings by severity with a verdict. Fix anything CRITICAL or HIGH, then re-review.

**5b. Silent-failure sweep** — `silent-failure-hunter`, whenever a tool or `ExecutionEngine` path changed. It catches the failure mode that matters most here: the phone going quiet instead of telling the user something went wrong.

**6. Privacy gate** — `privacy-auditor`, but only if the change touches the Accessibility service, the notification listener, contacts, SMS, redaction, or anything sent to the Claude API. Skip it otherwise and say you skipped it.

Then summarise: what was built, what the tests cover, what is still unverified (anything needing the physical phone), and what I should test by hand.

Do not commit anything unless I ask.
