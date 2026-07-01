# GAEE Phase 2 — LLM as the Brain of the Phone
## Detailed Engineering Plan

**Goal from proposal:** The engine handles any task without task-specific code.
All plans are discovered by the LLM and cached automatically.
**User's vision:** Say anything → phone does it.

---

## What Phase 2 Already Has (Built)

| Component | File | Status |
|---|---|---|
| MiniLM embedding engine (ONNX) | `EmbeddingEngine.kt` | Done |
| BERT WordPiece tokenizer | `WordPieceTokenizer.kt` | Done |
| Action cache (Room DB, cosine search) | `ActionCache.kt` | Done |
| Cache miss → LLM → cache write | `ActionPlanner.kt` | Done |
| LLM plan generator (Claude Haiku) | `LlmPlanner.kt` | Done |
| Model downloader (HuggingFace, WiFi only) | `ModelDownloader.kt` | Done |
| UINavigator (tap, type, scroll, back) | `UINavigator.kt` | Done |
| Accessibility service backbone | `GaeeAccessibilityService.kt` | Done |
| Web fetcher (HTTP GET/POST) | `WebFetcherTool.kt` | Done |
| Media controller (play/pause/next/prev) | `MediaControllerTool.kt` | Done |

---

## What Is Still Missing

The proposal lists **~15 generic tools** that together cover 95% of all phone tasks.
What exists covers maybe 60%. Here is what is missing and why each one matters.

---

### 1. ContactResolverTool — HIGH PRIORITY

**What it does:** Looks up any contact by name, relationship word ("my daughter", "mom", "the doctor"), or nickname.

**Why it matters:** Right now contact resolution is hardcoded inside `CallTool` and `SmsTool`. The LLM cannot ask "who is the user's daughter?" as a separate step. To make the LLM a true brain, it must be able to call a tool like:
```
ContactResolverTool { query: "my daughter" } → { name: "Priya", phone: "+91-98765-43210" }
```
Then use that result in the next step. This enables plans like:
- "Send a photo to my daughter on WhatsApp"
- "Call the doctor and say I'll be 10 minutes late"
- "Text my son that I'm home safe"

**What to build:**
- Query ContactsContract by display name (fuzzy match)
- Store a small nickname map in SharedPreferences: "mom" → "Sunita Sinha", "doctor" → "Dr. Patel"
- When query matches no contact, TTS asks: "I found 3 people named Maria. Which one — Maria D'souza or Maria Fernandes?"
- Return structured result: `{ name, phone, email }`

**File to create:** `app/src/main/java/com/gaee/tools/ContactResolverTool.kt`

---

### 2. NotificationReaderTool — HIGH PRIORITY

**What it does:** Reads, summarizes, and dismisses notifications from any app.

**Why it matters:** This enables the most-used elderly ask: *"Do I have any messages?"* or *"What did my daughter send?"* Without this, the LLM is blind to incoming information. The proposal lists this as a core tool.

Supports:
- "Read my WhatsApp messages"
- "Do I have any missed calls?"
- "What notifications do I have?"
- "Dismiss all notifications"
- Scam detection on incoming messages (Phase 3 can hook into this)

**What to build:**
- Implement `NotificationListenerService` — a separate service that captures all notifications
- Store last 50 notifications in memory (not disk — privacy)
- `NotificationReaderTool.execute()` queries the in-memory list and formats them for TTS
- Permission needed: `BIND_NOTIFICATION_LISTENER_SERVICE` (user must enable in Settings → Notifications → Notification access)

**Files to create:**
- `app/src/main/java/com/gaee/service/GaeeNotificationService.kt`
- `app/src/main/java/com/gaee/tools/NotificationReaderTool.kt`

---

### 3. ScreenReaderTool — HIGH PRIORITY

**What it does:** Reads all visible text on the current screen and returns it to the LLM.

**Why it matters:** This is what gives the LLM **eyes**. Without it:
- The LLM cannot verify if a step worked ("did the message actually send?")
- The user cannot ask "what does this say?" about anything on their screen
- Multi-step tasks through apps (WhatsApp, Uber) are blind — the LLM cannot confirm it is on the right screen

With it, the execution loop becomes:
1. LLM says: tap "Send"
2. UINavigator taps "Send"
3. ScreenReader reads the screen
4. LLM confirms: "I can see 'Message sent' — success"

**What to build:**
- Uses `GaeeAccessibilityService.rootInActiveWindow` to dump the full UI tree as text
- Filters out noise (icons, progress bars) and returns readable text only
- Returns: `{ screenText: "...", appName: "WhatsApp", currentActivity: "..." }`

**File to create:** `app/src/main/java/com/gaee/tools/ScreenReaderTool.kt`

---

### 4. WhatsApp & Messaging via UINavigator

**What it does:** Sends messages, photos, and voice notes through WhatsApp (and other messaging apps) using UINavigator — no WhatsApp API needed.

**Why it matters:** The proposal specifically names this as the "no API needed superpower." The LLM generates a plan like:
```
1. AppLauncherTool  { app_name: "whatsapp" }
2. UINavigator      { action: "tap",  target: "search" }
3. UINavigator      { action: "type", text: "Priya" }
4. UINavigator      { action: "tap",  target: "Priya Sinha" }
5. UINavigator      { action: "tap",  target: "message box" }
6. UINavigator      { action: "type", text: "I am home safe" }
7. UINavigator      { action: "tap",  target: "Send" }
8. TtsTool          { text: "Message sent to Priya on WhatsApp." }
```

**What to build (in `GaeeAccessibilityService`):**
- Add `swipe(direction)` — needed to navigate carousels, photo galleries, WhatsApp chats
- Add `waitForScreen(text, timeoutMs)` — waits until a specific element appears (crucial for app transitions)
- Add `readScreen()` — returns all text on current screen as a string (feeds ScreenReaderTool)
- Add coordinate-based tap `tapAt(x, y)` — fallback when no text label is findable

**File to update:** `GaeeAccessibilityService.kt`

---

### 5. Large Model for Complex Tasks

**What it does:** Routes complex, multi-step tasks to Claude Sonnet instead of Haiku.

**Why it matters:** Haiku is fast and cheap but weak at multi-step reasoning. For simple commands ("set alarm for 7am") Haiku is fine. But for tasks like:
- "Book me a cab to the hospital at 10am tomorrow"
- "Order my usual from Swiggy"
- "Send my daughter a voice message saying I'm going to the park"

...the LLM needs to reason across multiple steps, handle ambiguity, and adapt when a step fails. That requires Sonnet.

**What to build in `LlmPlanner.kt`:**
```kotlin
private fun chooseModel(intent: IntentResult): String {
    val complexIntents = setOf("book_uber", "order_food", "send_whatsapp_media",
                                "trip_plan", "multi_app_task")
    return if (intent.intent in complexIntents || intent.args.size > 3)
        "claude-sonnet-4-6"
    else
        "claude-haiku-4-5"
}
```
Also: pass the current screen state (from ScreenReader) to the LLM so it can see what is on screen while reasoning.

---

### 6. Execution Verification Loop

**What it does:** After each UINavigator step, reads the screen to confirm the step worked. If not, retries once with a different approach.

**Why it matters:** Without verification, the plan is blind. If WhatsApp doesn't open, the LLM keeps typing into thin air. The proposal explicitly requires: *"After each step, verify success by reading the screen state."*

**What to build in `ExecutionEngine.kt`:**
```
Run step
  ↓
If step is UINavigator:
  Wait 500ms
  Read screen via ScreenReaderTool
  Check if expected result is visible
  If not → retry once with different target text
  If still failing → speak "I couldn't do that, let me try a different way"
  Ask LLM to re-plan from current screen state
```

---

### 7. WorkManager for Background / Deferred Tasks

**What it does:** Queues heavy tasks (trip planning, long research, nightly cleanup) to run when the phone is on charge at night.

**Why it matters:** If a user says "plan a trip to Shimla next month", that is too complex to run in real-time. WorkManager runs it overnight and speaks the result in the morning.

**What to build:**
- `SchedulerTool.kt` — registers a WorkManager job with the task description
- `GaeeBackgroundWorker.kt` — wakes up, calls Claude Sonnet with the task, stores result
- On next morning's first app open: speak the result

**Files to create:**
- `app/src/main/java/com/gaee/tools/SchedulerTool.kt`
- `app/src/main/java/com/gaee/engine/GaeeBackgroundWorker.kt`

---

### 8. Expanded Intent Library in the LLM Prompt

**What it does:** Tells the LLM about every type of task the phone can now do, not just the 10 Phase 1 tasks.

**Why it matters:** Currently `IntentClassifier`'s system prompt only lists 10 intents. The LLM does not know it can control WhatsApp, read notifications, or book Uber. The prompt must be updated.

**Intents to add to the system prompt:**

| Intent | Args | Example command |
|---|---|---|
| `send_whatsapp` | `name, message` | "Send Priya a WhatsApp saying I'm home" |
| `send_whatsapp_media` | `name, media_type` | "Send my daughter today's photo" |
| `read_notifications` | `app (optional)` | "Do I have any messages?" |
| `dismiss_notifications` | `app (optional)` | "Clear all my notifications" |
| `play_media` | `query, app` | "Play Kishore Kumar songs on YouTube" |
| `control_media` | `action` | "Pause the music", "Next song" |
| `resolve_contact` | `query` | Used internally by LLM plans |
| `read_screen` | — | "What does this say?" |
| `go_back` | — | "Go back", "Cancel that" |
| `schedule_task` | `task, time` | "Remind me to take medicine at 8pm" |

---

### 9. API Keys — Must Be Set Before Testing

**Nothing works until these are set:**

| Key | Where to set | Get from |
|---|---|---|
| Claude API key | `IntentClassifier.kt` line 21: `val claudeApiKey = "YOUR_CLAUDE_API_KEY"` | console.anthropic.com |
| OpenWeatherMap key | `WeatherTool.kt` line 14: `val apiKey = "YOUR_OPENWEATHERMAP_API_KEY"` | openweathermap.org (free tier) |

---

## Phase 2 — Build Order (Recommended Sequence)

```
Week 5:
  1. Set API keys (Claude + OpenWeather) — nothing else works without this
  2. Build ContactResolverTool
  3. Expand intent classifier prompt with new intents

Week 6:
  4. Add swipe + waitForScreen + readScreen to GaeeAccessibilityService
  5. Build ScreenReaderTool
  6. Test WhatsApp flow end-to-end: "Send Priya a WhatsApp saying I'm home"

Week 7:
  7. Build NotificationReaderTool + GaeeNotificationService
  8. Add large model routing in LlmPlanner (Sonnet for complex tasks)
  9. Add execution verification loop in ExecutionEngine

Week 8:
  10. Build SchedulerTool + GaeeBackgroundWorker (WorkManager)
  11. Run 100 test commands across 20 tasks — measure cache hit rate
  12. Fix failures, re-test
```

---

## Phase 2 Exit Criteria (from proposal)

The engine must handle ALL of these without any task-specific code:

| # | Task | Required tools |
|---|---|---|
| 1 | Set alarm for 7am | AlarmTool |
| 2 | Send WhatsApp to daughter | UINavigator + ContactResolver + ScreenReader |
| 3 | Play a YouTube video | UINavigator + MediaController |
| 4 | Get weather | WeatherTool |
| 5 | Book an Uber | UINavigator + WebFetcher |
| 6 | Call a contact | CallTool + ContactResolver |
| 7 | Send SMS | SmsTool + ContactResolver |
| 8 | Open any app | AppLauncher |
| 9 | Read WhatsApp messages | NotificationReader OR UINavigator |
| 10 | Dismiss notifications | NotificationReaderTool |
| 11 | Play / pause music | MediaController |
| 12 | "What does this say?" | ScreenReaderTool |
| 13 | Order food on Swiggy/Zomato | UINavigator + ScreenReader (multi-step) |

**The LLM generates plans for all 13 tasks from scratch. No task has a hardcoded handler.**

---

## The "LLM as Full Brain" Architecture

When complete, this is the flow for EVERY command:

```
User speaks
    ↓
VoiceListener → transcript
    ↓
IntentClassifier (Haiku or Sonnet)
    → intent + args + confidence
    ↓
ActionPlanner
    → check embedding cache (cosine ≥ 0.78?)
    → CACHE HIT:  fill arg slots → execute  (50ms)
    → CACHE MISS: call LLM → generate steps → store in cache → execute
    ↓
ExecutionEngine
    → for each step:
        run tool
        if UINavigator: read screen → verify → retry if needed
        if fails: re-plan from current screen state
    ↓
TtsTool speaks result
    ↓
Cache updated with outcome (success_rate++)
```

The LLM is called **once per new task type**, then the cache handles it forever.
After 2 weeks of real use, >90% of everyday tasks are sub-100ms cache hits.

---

## Permissions Still Needed

These are not yet in `AndroidManifest.xml` for Phase 2:

| Permission | Why |
|---|---|
| `BIND_NOTIFICATION_LISTENER_SERVICE` | NotificationReaderTool |
| `BIND_ACCESSIBILITY_SERVICE` | Already declared for UINavigator |
| `FOREGROUND_SERVICE` | WorkManager background tasks |
| `RECEIVE_BOOT_COMPLETED` | Restart WorkManager jobs after reboot |

---

*Based on GAEE_Proposal_final.docx — Phase 2 section, Tool Library section, and Layer 3/5 specifications.*
