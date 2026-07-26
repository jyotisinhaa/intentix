# Product Strategy — "Hello"

> **Status:** living document. This is the strategic source of truth; `project-overview.md` describes
> *what the product is*, this describes *who it's for, why they pay, and how it wins/fails*.
> **Last updated:** 2026-07-25.

---

## 1. The wedge (committed)

**Target: immigrant / NRI families.** Two people, two roles:

| Role | Who | What they need |
|---|---|---|
| **Payer / installer / trust-bridge** | The **adult child abroad** (US/UK/Canada/Gulf) | Relief from being remote tech-support + worry that a parent they can't reach will be scammed or stuck |
| **User** | The **parent living alone back home** (typically India) | To use the phone independently, without feeling like a burden or losing dignity |

This is **not** "elderly people" generically. Narrowing to NRI families is deliberate — it gives the
sharpest **payer, pain, and distribution channel** we've found.

**Why this segment beats "elderly generally":**
- **Best possible payer profile:** an NRI child has disposable income, acute guilt, and *physical
  distance* — they can't just drive over to help, so they'll pay to solve it remotely.
- **Acute (not tolerated) pain:** parent alone, timezone gaps that make screen-share help miserable,
  constant background anxiety about scams and falls.
- **Reachable channel:** NRI communities are dense and online (Facebook elder-care/NRI groups,
  diaspora subreddits, WhatsApp networks) — a real place to market, not "everyone."
- **Shrinks the install wall:** the tech-savvy child sets it up during an annual home visit or via a
  guided remote session — motivated, capable, doing it for someone they love.
- **Scam hook intact:** NRI parents are heavily targeted by fraud, and the distant child knows and
  fears it.

**One-line pitch:** *Software that lets an NRI child keep their aging parent independent and safe
from afar.* (Contrast: "assistant for elderly" is not fundable; this is.)

---

## 2. The product shape — guide **+** automate **+** teach

Not a black box that silently does things. A **narrating helper** that:
1. **Does** the task (voice → the phone acts), and
2. **Narrates** what it's doing (*"I'm opening WhatsApp for you now"*), and
3. **Degrades to step-by-step guidance** when automation can't (*"tap the three dots at the top
   right"*), so the parent completes it themselves.

**Why the hybrid is strictly better than pure "do it for me":**
- **Never a dead end.** Automation is fragile (tap-by-text breaks on UI changes / non-English labels).
  Pure automation that fails once → the parent panics and reverts to calling their kid, and churns.
  The guidance fallback means a failure becomes a gentle instruction, not a broken promise.
  **Reliability = retention** for this user; the hybrid is how we survive imperfect automation.
- **Defuses the trust paradox.** An app silently hijacking the screen looks like a scam — to the exact
  population that's scared of scams. An app that *narrates* feels like a helper, not a hijacker.
- **Preserves dignity / builds independence.** The parent learns over time and feels capable — the
  emotional outcome the child is really buying. Google Assistant deliberately does *not* do this.

**Emotional hook (why they pay):** scam/fraud protection + reduced worry. We sell the **child's
relief**, not the parent's task list.

---

## 3. Positioning

- **Not "replace the kid" — relieve the kid.** Handle the 20 repetitive, low-stakes things so the
  child is only pulled in for the real ones, and add the safety layer they can't provide from abroad.
- **The real competitor is the child on a screen-share** (free, trusted, patient enough) — *not*
  Google. We win where the kid is weak: availability (3am, at work), infinite repetition tolerance,
  removing the parent's guilt of "bothering" them, and scale (one kid, two parents, a job).
- **We do not out-tech Google.** Our edge is **focus + trust + the guide-and-teach experience**, aimed
  at a niche Big Tech is too broad to serve.

---

## 4. Distribution

**There is no viral, self-serve, app-store-scale channel for this** — it is inherently high-touch.
That's a structural property of selling software to people who can't install software, and it's also
part of the **moat** (if it were easy, Google would do it).

- **Now — direct high-touch grind.** First 10 families from the founder's own network (people with
  aging parents). Then market to NRI adult children in existing communities (FB elder-care/NRI groups,
  diaspora subreddits, WhatsApp). Trigger moments: a scam scare, the Nth support call, a frustrating
  visit home. Message the **child's pain**, never the tech.
- **Install:** the child installs during a home visit or a guided remote session. Setup videos help
  but do **not** erase the friction of enabling Accessibility + several permissions — lean on
  "the child sets it up," not "the parent does it alone."
- **Later — B2B2C.** Banks/insurers (hate elderly fraud), senior phone sellers (pre-install, the
  Jitterbug model), senior communities/NGOs. Solves distribution structurally but has long sales
  cycles and needs traction first.

---

## 5. Moat

- **Willingness to do the unscalable, high-touch distribution** big players won't touch.
- **The guide-and-teach hybrid** — a genuinely different experience from Google's silent task-doing,
  purpose-built for a scared, dependent user.
- **Trust transferred through the child** — solves the "anti-scam app needs scam-like permissions"
  paradox in a way a cold app-store install never could.

Not a moat: the automation tech itself (Google out-engineers us day one) or keyword scam detection
(Truecaller/Google win the data fight).

---

## 6. Kill list (explicitly out of scope)

Parked to protect focus — these re-tempt whenever the wedge feels hard:
- **Young-professional productivity** (calendar nudges, trip planning) — hyper-competitive, no payer,
  terrible retention, zero validation.
- **"Hands-free for everyone"** — no pain, no payer, head-on with free Siri/Google.
- **Real-world physical navigation / wayfinding** ("your cab is opposite the supermarket") — a
  different, far harder product; a *someday* feature, not now.
- **Marketing "controls any app" as a guarantee** — the automation is best-effort; overselling it to a
  vulnerable audience is a liability.

These can return **as expansions from strength**, after the wedge is won — never as the beachhead.

---

## 7. Validation sequence (discipline)

Do **not** build the backend/security/Play plumbing before demand is proven.

1. **Validate payment (biggest open risk).** A real pre-sale / deposit / priced landing page to NRI
   adult children. Stated "yes I'd pay" in interviews is near-worthless; only money moving counts.
2. **Tiny hand-held beta.** 5–10 real families, manual setup by us, measure **week-2 retention** (where
   "do it for me" products die) and whether the child perceives enough value to keep paying.
3. **Only then productionize.** Backend proxy, security hardening, Play policy — see
   `launch-readiness.md`.

---

## 8. Open risks to carry forward

- **Payment unvalidated** — the #1 risk; nobody has paid yet.
- **Automation reliability = retention** — a fragile core feature directly threatens the business, not
  just quality. Mitigated (not solved) by the guidance fallback; consider launching *narrow and
  bulletproof* (the 5 daily tasks at ~99%) over *broad and flaky*.
- **Install friction** — high-touch, gated behind Accessibility + permission grants.
- **Play-policy exposure** — Accessibility-to-automate + SMS/Call are restricted; likely can't ship on
  Play as-is (see `launch-readiness.md`).
- **Scam detection is weak** — keyword-based, India/English-locked; loses a data fight to incumbents.
- **Security debt** — the live API key currently ships in the APK; rotate + build a proxy before any
  public build (see `launch-readiness.md`).

---

## 9. Related context
- `project-overview.md` — what the product is, features, flows, success criteria.
- `launch-readiness.md` *(to write)* — the technical/security/policy blockers from the architecture
  audit.
- `monetization.md` *(to write)* — pricing, packaging, the payment test.
- `roadmap.md` *(to write)* — phased build order aligned to the validation sequence above.
