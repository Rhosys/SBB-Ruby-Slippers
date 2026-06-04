# V2 Planning — open questions

V2 scope: on-device RAPTOR routing engine, multi-origin search, fares, a
real-time home-screen widget, and Wear OS. We work through these one at a time.
Each question records its status and, once decided, where the decision lives.

Status legend: ✅ decided · 🔄 in progress · 🔲 open

---

## RAPTOR engine

### ✅ Q1 — GTFS storage strategy
Room full schema / raw SQLite / pre-processed binary adjacency arrays?

**Decided.** Hot path (R1–R4) = pre-built **CSR `int[]` arrays, `mmap`'d** from a
binary file; not a database. Cold path (search/display) = an ordinary indexed
store (SQLite/Room + FTS). RAPTOR is round-based DP, **not** a graph search, so no
graph DB (this supersedes the earlier ObjectBox lean). Build location (on-device
vs server+CDN) is gated on a latency benchmark — see decision record.
→ [`gtfs-storage-architecture.md`](./gtfs-storage-architecture.md),
evidence in [`gtfs-queries.md`](./gtfs-queries.md).

### ✅ Q2 — RAPTOR rounds
Unbounded, or cap at a fixed max?

**Decided: execution-budget-bounded, progressive, resumable RAPTOR.** Not a flat
cap — completeness and optimization are separated.

- **Phase 1 — completeness (never cut short):** run rounds until the destination
  is actually reached. A 6-transfer trip needs 7 rounds, so no small fixed cap.
  The first complete itinerary is emitted the moment the destination is reached.
- **Phase 2 — optimization on a budget (background):** keep running further
  rounds, updating the result list as each lands. Stop when the **first** of
  these is true, checked after each round:
  - total elapsed ≥ **20 s** (hard upper bound), or
  - the **last round took ≥ 10 s** (diminishing-returns circuit breaker), or
  - **7 rounds** computed.
- **Resumable:** on budget stop, retain state; a **"Find more connections"**
  action resumes from the next round rather than restarting.

The long budget is acceptable because phase 2 is pure background enrichment — the
user has a usable trip from phase 1 within milliseconds and never waits on it.

**Implications:**
- RAPTOR is an incremental, resumable **coroutine emitting a `Flow` of results**,
  not a blocking call. UI binds to the flow; first result fast, list refines.
- State checkpointed (per-round labels, best arrivals, running result set) so
  "Find more" continues at round N+1.
- Result list = **Pareto front of (arrival time × number of transfers)**; each
  extra round contributes ≤1 new candidate, dominated ones dropped. Transfers are
  kept as a second criterion (not pruned purely on arrival time).
- Pairs with target pruning on the arrival-time criterion.

**Open sub-decisions (pending user confirm):**
- Budget exhausted before destination reached (≈impossible for real Swiss O/D):
  "no connection found" + v1 REST fallback, *or* let phase 1 exceed 7 rounds /
  20 s so completeness strictly wins?
- "Find more connections": resume with the same budget again, or relax it?

### 🔲 Q3 — GTFS-RT handling
Simple delay overlay (shift arrival/departure by reported delta), or full spec
(trip updates + vehicle positions + service alerts)?

### 🔲 Q4 — Migration of the v1 REST path
Keep `ApiTransportRepository` as a named fallback while RAPTOR is built, or remove
it the moment RAPTOR passes the same tests?
*(Note: §4 of the architecture doc already leans toward keeping it as the
offline / no-CSR / CDN-unreachable fallback — confirm here.)*

---

## Multi-origin

### 🔲 Q5 — Results presentation for "From Anywhere"
Per-origin connection lists ("from Home 12:03, from Office 12:11"), or just the
globally earliest arrival regardless of origin?

---

## Fares

### 🔲 Q6 — Ambition level
Level A (zone-based estimate, CHF range) / Level B (national direct-tariff
point-to-point, no saver tickets) / Level C (accurate bookable incl.
Half-Fare / GA / Sparbillette)?
*(Blocked on the fares-coverage spike: inspect Swiss `fare_rules.txt` /
`fare_attributes.txt` before committing — see architecture doc §7.)*

### 🔲 Q7 — Fare profile in Settings
Should the app know whether the user holds a Half-Fare card or GA and adjust
prices accordingly? (FA2 → `UserPreferencesRepository.fareProfile`.)

---

## Home-screen widget

### 🔲 Q8 — Widget modes
Journey monitor only (tracks locked-in trip) / departure board only (next N
departures from a pinned place) / both with user-selectable config?

### 🔲 Q9 — Widget config activity
Let the user configure via an "Edit" tap on the home screen, or just pick up the
default home place automatically?

---

## Wear OS

### 🔲 Q10 — Phone-relay vs standalone
Phone-relay (watch displays, phone computes) for V2 with standalone as a later
upgrade, or standalone from the start?

### 🔲 Q11 — Wear OS screens in scope
Just the current journey strip with delay badges, or also a next-departure glance
and a quick lock-in confirmation?

---

## Release order

### 🔲 Q12 — What ships first
Engine (RAPTOR) / widget / Wear OS / fares — or is this all one V2 release?
