# Privacy & Trust — "Hello"

> **Status:** living document. Trust is not a nice-to-have here — it **is** the product. The app
> asks a vulnerable user for the exact permissions a scam would ask for, so how we handle data and
> earn trust is existential. **Last updated:** 2026-07-25.

---

## 1. The trust paradox (and the answer)
An anti-scam app for elderly users needs to read the screen, messages, contacts, and control every
app — **indistinguishable from what a scam demands.** An older person should *never* grant that to a
cold app-store install.

**The resolution: trust is transferred through the child.** The NRI child vets it, installs it, sets
it up, and vouches for it ("I set this up for you, Ma"). This is why "the child installs it" is not
just distribution — it's the **trust mechanism** (`strategy.md` §1, §5). Design onboarding around the
child, not a stranger-facing self-install.

## 2. What data leaves the device, and to whom
| Data | When | Destination | Mitigation |
|---|---|---|---|
| Voice transcript | Every cloud-classified command | Anthropic (Claude) | On-device keyword classification first; cloud only when needed/online |
| On-screen text | During cloud re-planning | Anthropic | **`ScreenRedactor`** strips OTP/card/PIN digits first; sensitive screens blocked entirely |
| Incoming message text | Only if verdict = "suspicious" **and** opt-in ON | Anthropic (`ScamCloudCheck`) | **Opt-in default OFF**; redaction first; known-contact skipped; safe/dangerous handled on-device |
| Approximate location (lat/lon ~4dp) | Location-relative questions | Anthropic (`AnswerTool`) | Only when the question needs it |
| Contacts | Known-contact scam guard | **On-device only** — not transmitted | — |

## 3. Current privacy posture (built)
- **On-device first** — keyword intent classification, on-device scam Tier-1, embeddings/cache all
  local; cloud only on miss/uncertainty.
- **Opt-in cloud scam check** — `GaeeNotificationService` gates Tier-2 behind a user toggle, default
  **OFF**; until then message text never leaves the phone.
- **Redaction before cloud** — `ScreenRedactor` on screen text and context.
- **Sensitive-screen handoff** — PIN/OTP/payment screens are never operated on or transmitted.
- **Notifications stored in RAM only** (not disk) in `GaeeNotificationService`; alert history capped
  in-memory (`ProactiveAlertLog`).

## 4. Gaps to fix before launch (see `launch-readiness.md`)
- 🔴 **No privacy policy / Data-Safety declaration** — mandatory; we send screen/message/location to a
  third party.
- 🔴 **Embedded API key + direct-to-Anthropic calls** — no backend; a proxy is also the place to
  centralize/limit what's sent.
- 🟠 **Room action-cache stores PII in plaintext** (`intent`, `argsSlots` = names/numbers/message
  text) and is in **auto-backup** (`allowBackup=true`). Encrypt or exclude from backup.
- 🟠 **Logcat leaks** — raw Claude payloads/plan args logged; strip PII from logs.

## 5. Trust principles (product commitments)
- **Never act on money/credentials autonomously** — the human always completes payment/PIN/OTP.
- **Never nag** — proactive alerts follow priority rules (dangerous > important > silent).
- **Best-effort, honestly stated** — scam detection is a strong filter, **not** a guarantee; never
  marketed as one.
- **Consent is explicit and reversible** — cloud scam-screening is opt-in and toggleable any time.
- **Minimize what leaves the device** — redact, prefer on-device, transmit only what a step needs.
