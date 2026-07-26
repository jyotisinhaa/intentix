# Monetization — "Hello"

> **Status:** living document. **No pricing is decided yet.** Numbers below are **hypotheses to
> test**, explicitly not commitments. The point of this doc is the *model* and the *validation test*,
> not a price. See `strategy.md` (wedge, payer) and §7 (validate payment first).
> **Last updated:** 2026-07-25.

---

## 1. Who pays (settled)
**The NRI adult child abroad** — not the parent. The parent won't subscribe, manage billing, or
perceive the value. The child has disposable income, acute guilt, physical distance, and a concrete
fear (their parent getting scammed). They pay for **peace of mind + relief from being remote tech
support**, not for "AI."

**Sell the outcome, not the tech:** *"Stop being your parents' 24/7 tech support — and know they're
safe from scams, from anywhere in the world."*

---

## 2. What they're actually buying (value ladder)
1. **Safety** (the hook) — proactive scam/fraud protection + alerts to the child when something
   dangerous happens. This is what triggers the purchase.
2. **Relief** — the parent self-serves the repetitive tasks (guided), so fewer 3am support calls.
3. **Independence & dignity for the parent** — the emotional payoff the child is really buying.
4. **Presence from afar** — the "child loop": updates that make a distant child feel connected and
   in control.

---

## 3. Pricing models to test (hypotheses — pick via the test in §5, do not assume)
| Model | Shape | Pros | Cons |
|---|---|---|---|
| **Subscription (recurring)** *(leading hypothesis)* | Monthly/annual, billed to the child | Predictable revenue; matches ongoing protection value | Churn risk if value isn't felt continuously |
| **Freemium** | Free assisted-use; **pay for scam protection + child alerts** | Low install friction; protection is the natural paywall | Free tier still costs us API money (needs the backend/metering from `launch-readiness.md`) |
| **Family plan** | One price covers both parents / multiple family installs | Higher ACV; fits multi-parent NRI reality | More complex onboarding |
| **One-time + optional service** | Upfront for setup + optional monthly | Simple | No recurring base; weak for a protection product |

**Working hypothesis to validate (NOT a decision):** a **subscription paid annually by the child**,
with **scam protection + child alerts as the paid core** and basic assisted-use possibly free. Annual
suits gifting ("I set this up for Mom for the year"). *Price point is deliberately left blank until
the §5 test produces real willingness-to-pay.*

Anchoring for the eventual number (context, not a price): the child is comparing against the *cost of
worry* and the *cost of their own time*, not against a free app. Frame value there.

---

## 4. Cost side (why the backend matters)
Every task may hit Claude (classification, planning, scam adjudication, Q&A). Without the backend
proxy + metering (`launch-readiness.md` §1.1), **usage is an uncapped cost** and there's no way to
tie revenue to spend. Unit economics = subscription price − (per-user Anthropic + infra). This must
be modeled before scaling; a heavy free tier could be margin-negative.

---

## 5. The payment-validation test (the #1 next action)
Interview "yes I'd pay" is worthless. **Only money moving counts.** Cheapest real test, before any
backend work:

1. **Landing page** targeting NRI children: headline = the child's relief + parent's safety, a short
   explainer, and a **real price with a "Start / Pre-order" button**.
2. **Ask for money or a deposit** — a pre-order, a refundable deposit, or a paid pilot slot. Watch the
   *flinch* at the price.
3. **Drive traffic from the actual channel** (`strategy.md` §4): posts in a few NRI / elder-care
   communities + the founder's own network. Small, real, targeted.
4. **Measure:** landing→checkout conversion, and how many *actually pay*. A handful of real payments
   from strangers ≫ 100 interview "yes"es.

**Kill/continue signal:** if motivated NRI children won't put down even a small deposit for scam
protection for their parent, that's the cheapest possible way to learn the business isn't there —
*before* building the backend.

---

## 6. Open questions (resolve via the test / beta, don't guess)
- Actual price point and billing cadence (monthly vs annual).
- Free-vs-paid line — is assisted-use free with protection paid, or all-paid?
- One parent vs family plan as the default unit.
- Whether the "child loop" alerts alone could be the paid product (lighter, less permission-heavy).
