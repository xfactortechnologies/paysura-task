# Paysura POS — Android paid exercise

A short, **paid** take-home for Android engineers shortlisted for the Paysura field-agent POS app.

This repository contains **the stub payment API you will build against**. Every candidate gets the
same server with the same failure behaviour, so the exercise can be reviewed fairly and quickly.

---

## What this is really testing

Paysura is a point-of-sale app used by field agents in South Sudan. An agent takes cash and sells
airtime or prepaid electricity. Their own prepaid wallet is debited. Connectivity is intermittent,
the handsets are low-end, and **a payment that is charged but never reported to the agent is a real
customer harmed** — someone paid and got nothing.

So the interesting problem in this app is not the UI. It is:

> **What does the app do when a payment request does not come back?**

That is what this exercise is about. There are no design mockups to match and no visual polish
required — plain Material 3 components are completely fine.

---

## What we provide vs. what you write

|  | Provided here | You write |
| :-- | :-- | :-- |
| Stub payment API (the server the app talks to) | ✅ `server.js` | — |
| API contract, error shapes, trigger behaviour | ✅ this README | — |
| Android app | — | ✅ everything |
| Test doubles inside *your own* tests (e.g. MockWebServer) | — | ✅ yours to choose |

**Do not modify `server.js`.** If you believe it is wrong or ambiguous, say so in your README —
that is a useful signal, not a complaint. Reading and working against a contract you did not design
is a large part of the real job.

---

## Quick start

Requires **Node 18+**. No `npm install` — the server has zero dependencies.

```bash
node server.js
# Paysura exercise stub API
# listening on   http://localhost:4499
```

From an Android emulator use `http://10.0.2.2:4499`. From a physical device on the same network use
your machine's LAN IP.

Verify it is up:

```bash
curl -s localhost:4499/health

curl -s -X POST localhost:4499/api/method/paysura.api.accounting.bills.pay_bill \
  -H 'Content-Type: application/json' \
  -d '{"transaction_id":"11111111-1111-4111-8111-111111111111",
       "agent_id":"AGT-0001","biller":"mtn","customer_id":"920000000",
       "amount":100.00,"currency":"SSP"}'
```

Useful knobs:

```bash
PORT=8080 node server.js          # different port
SLOW_MS=15000 node server.js      # how long trigger .01 takes to answer (default 12000)
RESOLVE_MS=10000 node server.js   # how long a pending payment takes to settle (default 30000)
QUIET=1 node server.js            # no request logging
```

Test helpers (not part of the real API — use them freely in your own tests):

```bash
curl -X POST localhost:4499/__reset      # wipe all server state
curl localhost:4499/__transactions       # what the server believes happened
```

---

## The task

Build a **single-screen** Compose app that takes a payment and survives the interesting failures.

1. A form: customer reference and amount. A `Pay` button.
2. On `Pay`, mint a **UUIDv4 `transaction_id`** and **write a journal row to Room *before* the
   network request leaves.**
3. Submit the payment.
4. **The UI observes the request for at most 8 seconds.** After that it stops waiting and returns to
   an idle/ready state, with the transaction visible somewhere as unresolved.
5. **The request itself must not be cancelled** when the UI stops observing. It runs to completion
   and updates the journal row wherever it lands.
6. Show a list of journal rows and their states, so a reviewer can see what the app believes.
7. On next launch, resolve anything left non-terminal before showing the main screen.

A background worker that polls the status endpoint is welcome but **not required** — a start-up
resolver is enough. Say in your README which you did and why.

### The two rules that must not be inverted

| Situation | What the app must do |
| :-- | :-- |
| Outcome **unknown** (timeout, no response, 5xx) | **Reuse the same `transaction_id`.** The replay is idempotent and is the only way to learn what happened. |
| Outcome **terminal negative** (`declined`, `reversed`) | **Mint a new `transaction_id`.** The old one is terminal server-side and replaying it returns that status forever. |

---

## The API

Two endpoints. Both are `POST` with a JSON body.

```
POST /api/method/paysura.api.accounting.bills.pay_bill
POST /api/method/paysura.api.accounting.bills.get_transaction_status
```

The paths look odd because the real backend is [Frappe](https://frappeframework.com/), which exposes
whitelisted Python methods at `/api/method/<dotted.path>`. The stub mirrors it deliberately.

### Request

```jsonc
{
  "transaction_id": "9f1c1b8e-0000-4000-8000-000000000000",  // UUIDv4, minted by YOU
  "agent_id": "AGT-0001",
  "biller": "mtn",
  "customer_id": "920000000",
  "amount": 100.00,
  "currency": "SSP"
}
```

### Success envelope

**Frappe wraps every return value in a `message` key.** Unwrap it once, centrally — not in forty
places.

```jsonc
{
  "message": {
    "status": "success",
    "transaction_id": "9f1c1b8e-…",
    "currency": "SSP",
    "journal_entry": "ACC-JV-STUB-0001",
    "provider_reference": "STUB-9F1C1B8E",
    "provider_cost": 97.00,
    "agent_commission": 2.00,
    "remaining_balance": 12450.00,
    "vend_data": null
  }
}
```

> **`vend_data` is always `null` in this exercise, and that is a real answer — not a gap.**
> It carries the receipt for billers whose vend produces something the customer must carry away:
> prepaid electricity, where the meter token *is* the product. This stub sells **airtime only**,
> which produces no such artifact, so the key is always `null`.
>
> Model it as nullable and pass it through. Don't drop it from your DTO, and don't assume `null`
> means "not implemented yet" — on the real backend a non-null value comes with its own rules about
> when the app may print, and a client that silently discarded the key would be the bug.

### The `status` vocabulary

**`pay_bill` returns HTTP 200 for a business outcome. Branch on `status`, never on the status code.**

| `status` | Money | Terminal? |
| :-- | :-- | :-- |
| `success` | Deducted | ✅ yes |
| `declined` | Untouched — the biller said no | ✅ yes, **negative** → new id for a retry |
| `pending` | **Held.** Outcome not yet known | ❌ **no** — resolve it later |
| `duplicate` | Already charged once | ✅ yes — show the original result |
| `reversed` | Was held, then returned | ✅ yes, **negative** → new id for a retry |

### Error envelope

Non-2xx responses carry Frappe's own fields **plus** a stable `error` slug and typed values.

```jsonc
{
  "exc_type": "ValidationError",
  "_server_messages": "[\"{\\\"message\\\": \\\"Insufficient wallet balance.\\\"}\"]",
  "error": "insufficient_balance",
  "required": 100.05,
  "available": 40.00,
  "currency": "SSP"
}
```

**Switch on `error`. Render the typed numbers.** Never parse `_server_messages` — it is a
translated string, double-JSON-encoded, and it will be in a different language next quarter.
(The double encoding is not a bug in the stub. The real server does that.)

Slugs the stub can return: `insufficient_balance`, `amount_invalid`, `customer_id_required`,
`transaction_id_required`, `jwt_expired`, `invalid_json`, `method_not_found`.

---

## Triggers — how to force each behaviour

**The cents of the amount select the behaviour.** Nothing is random or time-dependent, so the same
request always does the same thing, and your tests can rely on it.

| Amount | HTTP | Behaviour | What it exercises |
| :-- | :-- | :-- | :-- |
| `100.00` | 200 | Immediate `success` | Happy path |
| **`100.01`** | 200 | **Answers after 12 s** — long after your 8 s budget | ⭐ **The main event.** The payment *is* recorded at once. If your app cancelled the request when the UI stopped watching, this money moved and your app never found out. |
| `100.02` | — | **Never responds.** Connection held open forever | Recorded as `pending` server-side and settles after 30 s. Only a status poll or a replay can reveal it. |
| `100.03` | 202 | `pending` | Accepted, still processing; settles after 30 s |
| `100.04` | 200 | `declined` | Terminal negative → a retry needs a **new** id |
| `100.05` | 417 | `insufficient_balance` + typed fields | Slug routing, rendering real numbers |
| `100.06` | 500 | Server error | Indeterminate, **not** failed. Recorded pending, **reverses** after 30 s |
| `100.07` | 401 | `jwt_expired` on the **first** attempt; the retry succeeds | *Optional.* Silent refresh + replay of the same id |

Any other cents value behaves as `success`.

### Replaying an id

Send the same `transaction_id` twice and the server answers from its own record — it never charges
twice. What you get back depends on what the first call did:

- first call succeeded → `duplicate`
- first call is still pending → `pending`
- first call declined → `declined`
- server had an error, and it has since reversed → `reversed`

### The status endpoint

```bash
curl -s -X POST localhost:4499/api/method/paysura.api.accounting.bills.get_transaction_status \
  -H 'Content-Type: application/json' \
  -d '{"transaction_id":"9f1c1b8e-…","agent_id":"AGT-0001"}'
```

Returns `{"message": {"status": "...", …}}` where `status` is one of `pending`, `settled`,
`reversed`, `declined`, or `not_found`. Every response except `not_found` also carries the same
nullable `vend_data` key described above.

> ⚠️ **`not_found` is not proof that nothing happened.** The request may simply not have landed yet.
> Treat it as *still unknown* and keep the row non-terminal.

---

## What we are looking for

Roughly in order of weight:

1. **The 8-second budget does not cancel the payment.** Time out the *observation*, not the *work*.
   `viewModelScope` + `withTimeout` around the call is the wrong answer and we will test for it.
2. **Intent is journalled before the request leaves**, and the app recovers after process death.
   Kill the app mid-payment (`adb shell am kill <pkg>` during trigger `.01`) — nothing should be lost.
3. **Correct id discipline** — reuse on unknown, re-mint on terminal negative.
4. **Money is never a `Double` or `Float`.** Integer minor units or `BigDecimal`. This includes the
   Room schema and any equality comparison.
5. **Tests that mean something.** Five good ones beat fifty trivial ones. We would specifically like
   to see a test proving the request completes after the UI stopped observing it.
6. **Clear structure.** State hoisted out of composables, a repository that owns the request, DI or
   plain constructor wiring — either is fine at this size.

### Constraints

- **Kotlin + Jetpack Compose + Room.** Coroutines/Flow, not RxJava.
- Single Activity, single screen is fine. **No design work expected.**
- No Flutter, KMP, or cross-platform layers.
- Don't build a multi-module Gradle setup for this — one module is right at this size.

---

## What to submit

A Git repository (or a zip) containing:

1. The Android project — builds with `./gradlew assembleDebug` from a clean checkout.
2. Your tests — `./gradlew testDebugUnitTest` passes.
3. A short `README.md` (one page is plenty) covering:
   - how you kept the request alive past the UI budget, and where that scope lives;
   - your journal states and the transitions between them;
   - what you would do differently with more time;
   - **anything in this brief you disagree with, or found ambiguous.** We mean this — the strongest
     submissions push back on something.

A screen recording of trigger `.01` (start the payment, watch the UI give up at 8 s, see the row
reach a terminal state afterwards) is worth more than any amount of prose.

---

## Time and payment

**Please cap this at 6–8 hours.** It is a paid exercise — we are not asking for a weekend. If you
run out of time, stop and write down what you would have done next; we would rather see a clean
partial solution with honest notes than a rushed complete one.

Payment: **[agree the fee before starting]**. Invoice on submission, regardless of whether we
proceed to a contract.

---

## Questions

Ask. Ambiguity in a brief is a fact of the job, and asking early is a positive signal — guessing
silently is not. Anything asked by one candidate that materially changes the exercise will be shared
with all of them.
