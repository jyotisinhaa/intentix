# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project layout

The Android app lives in `gaee/` (single `:app` module, package `com.gaee`). The repo root (`E:\lumi`) also holds planning docs: see @GAEE_Phase2_Plan.md for the current roadmap of tools still to build.

GAEE is an Android assistant for elderly users where an LLM acts as "the brain of the phone": voice command → Claude classifies intent → an embedding/Room action cache is checked → on a miss the LLM generates a multi-step plan → an Accessibility service (`UINavigator`) executes it by tapping/typing/scrolling real app UIs (no per-app APIs). New code is structured as generic **tools** (`gaee/app/src/main/java/com/gaee/tools/`) the LLM can compose — avoid hardcoding task-specific handlers.

## Required before first build

Two API keys are hardcoded placeholders and the app fails silently without them:
- Claude key — `gaee/app/src/main/java/com/gaee/engine/IntentClassifier.kt` (`claudeApiKey`), from console.anthropic.com
- OpenWeatherMap key — `gaee/app/src/main/java/com/gaee/tools/WeatherTool.kt` (`apiKey`), from openweathermap.org

`gaee/local.properties` pins the Android SDK path (machine-specific; don't commit changes to it).

## Build

From `gaee/`: `gradlew.bat build` (Windows; AGP 8.4.0, Kotlin 1.9.24, min SDK 26 / target 35). No test, ktlint, or detekt setup exists yet.

## Stack (non-obvious bits)

- Kotlin only; **XML layouts + ViewBinding, not Jetpack Compose**. MVVM (`MainViewModel`, coroutines).
- Retrofit/OkHttp (Claude API), Room (action cache), ONNX Runtime + ML Kit (on-device MiniLM embeddings), Gson.
- `app/build.gradle` uses `packagingOptions.pickFirst("**/libonnxruntime.so")` to resolve native ABI conflicts — keep it.

## Runtime gotchas

- The Accessibility service (`GaeeAccessibilityService`) and Notification listener (`GaeeNotificationService`) require the user to grant access manually in Android Settings; they can't be enabled from code.
- When defaulting an LLM model id, use current Claude models (e.g. `claude-haiku-4-5`, `claude-sonnet-4-6`), not older ones.
