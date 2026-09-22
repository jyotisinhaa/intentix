# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project layout

The Android app lives in `gaee/` (single `:app` module, package `com.gaee`). The repo root (`E:\lumi`) also holds planning docs: see @GAEE_Phase2_Plan.md for the current roadmap of tools still to build.

GAEE is an Android assistant for elderly users where an LLM acts as "the brain of the phone": voice command → Claude classifies intent → an embedding/Room action cache is checked → on a miss the LLM generates a multi-step plan → an Accessibility service (`UINavigator`) executes it by tapping/typing/scrolling real app UIs (no per-app APIs). New code is structured as generic **tools** (`gaee/app/src/main/java/com/gaee/tools/`) the LLM can compose — avoid hardcoding task-specific handlers.

## Required before first build

Two API keys are read from `gaee/.env` (gitignored, not in the repo — create it yourself):

```
CLAUDE_API_KEY=sk-ant-...
OPENWEATHERMAP_API_KEY=...
```

From console.anthropic.com and openweathermap.org respectively. `build.gradle.kts` loads that file and exposes both as `BuildConfig.CLAUDE_API_KEY` / `BuildConfig.OPENWEATHERMAP_API_KEY`, falling back to a same-named environment variable, then to a placeholder string.

**Never hardcode a key in Kotlin source** — read it from `BuildConfig`. A missing `.env` still builds cleanly and fails only at runtime, silently: the placeholder is sent as a real key and every API call fails. If the app runs but does nothing, check `.env` first.

`gaee/local.properties` pins the Android SDK path (machine-specific; don't commit changes to it).

## Build

From `gaee/` (Windows; AGP 8.4.0, Kotlin 1.9.24, min SDK 26 / target 35). PowerShell needs the `.\` prefix:

- `.\gradlew.bat assembleDebug` — fastest compile check
- `.\gradlew.bat build` — full check
- `.\gradlew.bat testDebugUnitTest` — JVM unit tests (`app/src/test/`), no device needed
- `.\gradlew.bat connectedDebugAndroidTest` — instrumented tests (`app/src/androidTest/`), needs a connected phone

Put pure logic (scoring, parsing, priority rules) in `src/test` so it runs without a device; reserve `src/androidTest` for things that genuinely need Android — Contacts, Accessibility, Telephony, Room. No ktlint or detekt setup exists yet.

## Stack (non-obvious bits)

- Kotlin only; **XML layouts + ViewBinding, not Jetpack Compose**. MVVM (`MainViewModel`, coroutines).
- Retrofit/OkHttp (Claude API), Room (action cache), ONNX Runtime + ML Kit (on-device MiniLM embeddings), Gson.
- Build files are Kotlin DSL (`build.gradle.kts`); dependencies come from the `gradle/libs.versions.toml` version catalog as `libs.*`.
- `app/build.gradle.kts` uses `packagingOptions.pickFirst("**/libonnxruntime.so")` to resolve native ABI conflicts — keep it.

## Runtime gotchas

- The Accessibility service (`GaeeAccessibilityService`) and Notification listener (`GaeeNotificationService`) require the user to grant access manually in Android Settings; they can't be enabled from code.
- When defaulting an LLM model id, use current Claude models (e.g. `claude-haiku-4-5`, `claude-sonnet-4-6`), not older ones.
