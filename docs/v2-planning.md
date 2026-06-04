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

### 🔲 Q2 — RAPTOR rounds
Unbounded, or cap at a fixed max (e.g. 3 rounds = 2 transfers)?

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
