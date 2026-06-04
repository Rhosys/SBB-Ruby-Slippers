# V2 Planning — open questions

V2 scope: on-device routing engine, fares, a
real-time home-screen widget, and Wear OS. We work through these one at a time.
Each question records its status and, once decided, where the decision lives.

Status legend: ✅ decided · 🔄 in progress · 🔲 open

---

## Routing engine

### ✅ Q1 — GTFS storage strategy
Room full schema / raw SQLite / pre-processed binary adjacency arrays?

**Decided.** Hot path (R1–R4) = pre-built **CSR `int[]` arrays, `mmap`'d** from a
binary file; not a database. Cold path (search/display) = an ordinary indexed
store (SQLite/Room + FTS). The routing engine is **not** a graph search, so no
graph DB (this supersedes the earlier ObjectBox lean). Build location (on-device
vs server+CDN) is gated on a latency benchmark — see decision record.
→ [`gtfs-storage-architecture.md`](./gtfs-storage-architecture.md),
evidence in [`gtfs-queries.md`](./gtfs-queries.md).

### ✅ Q2 — Routing rounds
Unbounded, or cap at a fixed max?

**Decided: execution-budget-bounded, progressive, resumable on-device routing.** Not a flat
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
- **Budget exhausted before destination reached:** call the REST API directly.
- **Resumable:** on budget stop, retain state; a **"Find more connections"**
  action resumes from the next round rather than restarting.
- **"Find more connections" budget:** same budget again (≤20 s / ≤7 more rounds).

**Dual-source:** on-device routing and the REST API always run in parallel on every query.
Results are not compared for correctness — they will legitimately differ (different
walking speeds, transfer minimums, optimization weights). Both result sets are
surfaced: the on-device Pareto front and the SBB-recommended trip (REST API result) as
a labeled option. Different is the feature, not a bug.

**Active-journey state lifecycle:**

| State | What's stored | Until when |
|---|---|---|
| Candidate (shown in results, not locked) | Full routing state | Departure time passes |
| Locked in | Full routing state + active RT tracking | Journey completes |
| Completed | Trip token + price + anomalies only | Forever (trip history) |

State is persisted to disk (survives process death). Re-computation triggers only
when a delay or cancellation notification arrives for a trip in the active journey
— not from scratch, from the checkpoint prior to the affected segment. `JourneyStateHolder`
owns both the locked-in connection and the routing state snapshot.

**Implications:**
- The routing engine is an incremental, resumable **coroutine emitting a `Flow` of results**,
  not a blocking call. UI binds to the flow; first result fast, list refines.
- State checkpointed (per-round labels, best arrivals, running result set) so
  "Find more" continues at round N+1.
- Result list = **Pareto front of (arrival time × number of transfers)**; each
  extra round contributes ≤1 new candidate, dominated ones dropped.
- Pairs with target pruning on the arrival-time criterion.

**Open sub-decision:**
- "Find more connections": same budget again, or relax it (e.g. unbounded until
  the next 10s-round breaker)?

### ✅ Q3 — GTFS-RT handling
Simple delay overlay, or full spec?

**Decided: TripUpdates (full spec) + Alerts (categorized). No VehiclePositions.**

**TripUpdates (full, not delta-shift only):**
Handling `SKIPPED` stops costs little extra and prevents a class of broken
re-routings. `ADDED` and `REPLACED` trips are rarer but trip-breaking if ignored.
TripUpdates are the primary trigger for re-computation from checkpoint.

**Active-journey state re-computation triggers** (broader than RT alone):
- **TripUpdate**: delay or cancellation notification for a trip in the active journey.
- **Current time**: as departure approaches, re-evaluate even without RT change
  (sanity check proximity to departure).
- **User location**: if the user is not where the journey assumes (wrong stop,
  wrong platform), trigger re-route consideration independent of RT.

**Alerts — three-tier categorization:**

| Tier | Criterion | Where shown |
|---|---|---|
| Journey-affecting | `informed_entity` matches a route, trip, or stop in active journey | Indicator on connection card + journey strip |
| Line-level | Affects a line in the journey, not the specific trip | Trip details only |
| Network/general | Strikes, infrastructure, unrelated lines | Trip details only, user-filterable |

Tier 1 is always shown (cannot be opted out). Tiers 2–3 are user-configurable.
The alert filter config is part of the user settings that back up to the user's
account (see QA1).

**VehiclePositions:** skipped for V2. Only useful for a live map view, not in scope.

**Prerequisite:** opentransportdata.swiss token (Todo #5 in `todo.md`) — the free
`transport.opendata.ch` API does not serve GTFS-RT.

### ✅ Q4 — Migration of the v1 REST path
**Decided: two explicit, independent sources with separate retry and failure modes.**

`LocalTransportRepository` and `ApiTransportRepository` are two distinct named
bindings in DI — not merged behind a single `TransportRepository` binding. Each
has its own retry policy, its own failure handling, and its own result type.
The `ConnectionSearchViewModel` holds both and coordinates them in parallel.

Failure modes are independent:
- On-device routing can fail: CSR not yet built, budget exhausted without reaching
  destination, missing calendar data.
- REST API can fail: network error, rate limit (3 queries/min), server error.

The UI can show an on-device result even when the REST call fails, and vice versa.
"SBB recommended" is surfaced only when the REST call succeeds; it does not block
or degrade the on-device result display.

---

## Fares

### ✅ Q6 — Ambition level
**Decided: Level C — as accurate as possible. Aim for personalised pricing
(Half-Fare, GA, Sparbillette, U25 Night, etc.); adjust based on what the data
actually contains once the feed is inspected.**

Implementation is gated on the fares-coverage spike (inspect Swiss
`fare_rules.txt` / `fare_attributes.txt`) — see architecture doc §7. If GTFS
fare data is insufficient, a separate pricing API or zone-based fallback will
be evaluated at that point.

### ✅ Q7 — Fare profile in Settings
**Decided: yes, included as part of Level C.**

`UserPreferencesRepository` gets a `fareProfile` field (Half-Fare, GA, none,
etc.). All fare displays adjust to the user's profile. A GA holder sees
"covered by GA"; a Half-Fare holder sees the halved price.

---

## Home-screen widget

### ✅ Q8 — Widget modes
**Decided: one widget, three automatic states — no user configuration.**

| State | Condition | Shows |
|---|---|---|
| Journey active | A trip is locked in | Active leg: next stop, time remaining, delay badge |
| At saved location | No active journey + geofence entry for a saved place | Departure board: next N departures from that place |
| Idle | Neither of the above | Nothing (blank / app icon) |

**Battery design:**
- Journey active: widget updates piggy-back on the RT poll already running for
  the active journey. Zero extra battery cost.
- At saved location: **Android Geofencing** (not continuous GPS) detects
  entry/exit of each saved place's radius. Low-power significant-location-change
  detector, not GPS. The stationboard RT poll starts only when a geofence fires
  and stops on geofence exit.
- Idle: no polling, no location work, widget is static.

### ✅ Q9 — Widget config activity
**Decided: no config activity.** Widget reads saved places automatically.
No user setup required.

---

## Wear OS

### ✅ Q10 — Phone-relay vs standalone
**Decided: phone-relay for V2. Standalone is a V3 consideration.**

The watch is a notification and display surface driven entirely by the phone's
state machine. All routing state, RT polling, and decision logic lives on the
phone. The watch receives pre-computed results and triggered notifications via
the Wearable Data Layer API — it makes no decisions of its own.

**Notifications the phone pushes to the watch** (and phone notification tray):
- Delay affecting the active journey
- Track / platform change
- Sector / carriage recommendation (which section to board)
- Journey-affecting alerts
- Departure countdown ("train leaves in 5 min — platform 3")
- Transfer warning ("4 min to change at Olten — platform 2")

The phone decides when each notification fires; the watch just renders it.

### ✅ Q11 — Wear OS screens in scope
**Decided: tile + app screen. Connection search stays on the phone.**

**Tile** (swipeable from watch face, always available):
- Journey active: next stop, time remaining, delay badge.
- No active journey: next departure from current saved location (if geofenced).
- Otherwise: blank.

**App screen** (minimal):
- Journey active: compressed journey strip — current leg, upcoming legs, delay.
- No active journey: next departure glance from nearest saved location.

Full connection search on the watch is V3.

---

## Journey sharing

### 🔲 QS2 — Deep-link journey share
A "Share journey" button on the connection detail / active journey strip opens the
Android share sheet targeting contacts (or any app). The shared payload is a deep
link that opens the app directly to the same journey.

Pending decisions:
- Deep link format: encode enough to reconstruct the query (from, to, departure
  datetime, and optionally the specific trip token) — not a full routing state
  dump.
- Recipient without the app: link resolves to a web fallback (SBB website or
  Google Maps directions) so the share is useful even if the recipient doesn't
  have the app installed. Requires an `Intent.ACTION_VIEW` handler and an
  `AppLinks` / `intent-filter` with `autoVerify`.
- Whether shared journeys show as a distinct entry type in trip history.

---

## User account and backup

### ✅ QA1 — Cloud backup scope and mechanism
**Decided: Android Auto Backup for V2. Trip export (manual) in V3.**

Enable Android Auto Backup — 2 lines of manifest config, zero backend, zero auth.
Covers phone replacement and app reinstall automatically via the user's Google
account. No cross-device real-time sync, but sufficient for V2.

V3: manual export of trip history in a portable format (JSON/CSV). No backend
required; user saves the file wherever they want.

---

## Return trip

### 🔲 QRT1 — "Plan return trip" button
On the journey detail / active journey strip, a button that opens a new connection
search pre-filled with:
- Origin and destination **swapped**
- Routing time set to **DepartAfter** the **arrival time** of the current trip
- Date carried forward (handles overnight trips correctly)

Tapping it is equivalent to the user manually reversing the search and entering
the arrival time — just one tap instead of several.

---

## Reachability screen

### 🔲 QR1 — "Where can I get by this time?"
A screen where the user enters a departure place and a target arrival time, and the
app shows every stop reachable before that time — i.e. an isochrone over the
transit network.

This is a natural output of the routing engine: after running rounds with no
specific destination, `best[stopId]` holds the earliest possible arrival at every
stop in the network. The reachability set is all stops where that arrival ≤ the
target time.

**Display:** stops grouped by travel-time bucket (e.g. within 15 min / 15–30 min /
30–45 min / 45–60 min), showing stop name, earliest arrival, and number of
transfers required. Optionally filterable by transport mode.

**Inputs:** origin (same search as connection search — saved place, current
location, or text search) + target time (time picker, defaults to "now + 60 min").

**UX question (to be asked during implementation):** list only, or also a map view
showing the reachable area visually? Map view requires a map library decision.

Pending decisions:
- List vs map (or both)?
- Max travel time ceiling (60 min default? user-adjustable?).
- Whether to include walk-only reachable stops (no transit) in the result.

---

## Platform sector recommendations

### 🔲 QS1 — Sector and stairwell guidance
Show the user which sector/carriage to board, based on platform layout and known
stairwell/exit positions at the destination.

Platform layout data is **not in GTFS**. SBB publishes it separately via
opentransportdata.swiss in NeTEx/HRDF format (`Perron` data — platform berth
positions keyed by stop_id and track). This is a separate import, a separate data
store, and a separate query type from everything in the GTFS catalogue.

Pending decisions:
- Confirm that opentransportdata.swiss Perron data is available and covers the
  stops we need.
- Define the data model (platform → sector list → berth positions → stairwell
  annotations).
- Define where this surfaces in the UI (journey leg detail, boarding prompt).

---

## Release order

### ✅ Q12 — Release order and build process
**Decided: build everything, serially, one feature at a time. TDD throughout.
Ask UX/reliability/performance questions as each feature is built. CI benchmarks
on every feature with a measurable cost.**

**Build order** (each depends on the previous):
1. **Routing engine** — CSR binary format, import pipeline, routing algorithm,
   `LocalTransportRepository`, progressive `Flow`-based results, state persistence
2. **RT layer** — GTFS-RT TripUpdates + Alerts, state re-computation triggers
   (time, location, delay), alert categorization
3. **Widget** — geofence-driven, 3 automatic states, battery-optimized
4. **Wear OS** — tile + app screen + notification relay via Wearable Data Layer
5. **Fares** — gated on feed-coverage spike; Level C with fare profile
6. **Sector recommendations** — gated on Perron data investigation
7. **Journey sharing** — deep link, Android share sheet, web fallback
8. **Android Auto Backup** — manifest config + backup rules

**Process per feature:**
- Tests first; iterate until green
- Ask questions as implementation raises UX, reliability, or performance concerns
- CI benchmark jobs for any feature with a measurable cost (routing query latency
  cold/warm, CSR build time, geofence trigger latency, etc.)
- Audit before moving to the next feature
