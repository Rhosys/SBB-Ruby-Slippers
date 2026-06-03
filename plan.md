# SBB Ruby Slippers — Product & Functionality Plan

> Status: **scoped** — v1/v2 split defined, core product + engine decisions resolved.
> This is a living document; update it as implementation decisions are made.

---

## THE WEDGE

**SBB Mobile picks ONE departure stop. We optimize across ALL of them simultaneously.**

Every existing transit app asks "which stop are you at?" We ask "where are you *right now*, and where do you need to be?" — then find every viable combination of nearby stops, ranked by when you'd actually *arrive*, accounting for your personal walking and running pace.

The name is the product: *click your heels, you're home.*

> **Note:** true multi-origin optimization requires the on-device GTFS + RAPTOR
> engine, which is a **v2** milestone (see *Release scope*). **v1 ships on the
> existing `transport.opendata.ch` API** with conventional single-origin journey
> planning — proving the surrounding product (tiles, plans, calendar, monitoring,
> widget) while the engine is built in parallel. The wedge lands in v2.

---

## RELEASE SCOPE — v1 / v2

**v1 — API-backed, ships first (weeks, not months)**
Runs on the existing `transport.opendata.ch` Retrofit layer. Everything that does
*not* require the on-device routing engine:
- Single-origin journey planning, stationboard, location search (existing API)
- Guided first-run onboarding (location permission + set Home)
- Places / Saved routes / Recurring routes (CRUD + local storage)
- App-open intent scorer (scoring API-routed candidates)
- Trip lock-in + Journey Strip
- Disruption notifications + Switch prompts — **best-effort on the delay data
  `transport.opendata.ch` returns** (`StopDto.delay`); not full GTFS-RT
- Calendar integration (CalendarContract → saved routes)
- Home-screen widget (Glance)
- Settings

**v2 — the engine + the wedge**
- On-device GTFS static download + SQLite + RAPTOR (the multi-origin core)
- True multi-origin optimization across all nearby stops (the differentiator)
- GTFS-RT live overlay (richer real-time than the v1 API delay fields)
- Offline routing after first download
- Multi-hop / day planner

**Hard rule:** v1 must not block on any v2 infrastructure. The data layer is
abstracted behind `TransportRepository` so the v2 engine swaps in underneath
without touching the UI.

---

## THE COMMUTER — visual identity

An animated SVG figure that communicates transfer risk at a glance, no text required:

| State | Meaning | Animation |
|-------|---------|-----------|
| 🟢 Standing | Comfortable, no rush | Still |
| 🟡 Walking | Normal pace, you'll make it | Walking cycle |
| 🟠 Running | Tight, move now | Running cycle |
| 🔴 Teleporting | Switch this leg NOW | Multiple stacked silhouettes with motion blur |

The Commuter appears on: transfer blocks (Journey Strip), home-screen widget, disruption push notifications, onboarding idle screen.
Implementation: Lottie or Android Animated Vector Drawable (AVD).

---

## CORE SCREENS & FLOWS

### 1. Home screen — "Next departure" hero

- Opens directly to a live card: best option for reaching the user's **inferred**
  destination (see *App-open intent restoration* below — the home screen is the
  output of that engine, not a blank search form)
- No search form on first open — destination is restored/inferred, or picked from
  tiles or a tap-to-search field
- **Hero card:** departure time · line · platform · walk time to stop · arrival at destination
- **Alternatives below:** same destination, different stops/routes/effort levels
- **Tile chips** below alternatives: quick-tap access to saved + auto-detected
  destinations (see *Tiles & history*)
- **Map toggle:** view all nearby reachable stops spatially
- Options silently promote as they expire (1 min after actual departure + reported delay)

### 1a. App-open intent restoration

On every launch (including cold start from a fully closed app, or after the phone
was off), before showing anything the app **infers what the user is trying to do**
rather than dumping them on an empty screen.

Inputs:
- Last searched destination (unsaved)
- **Saved routes** (one-off, time-specific trips)
- **Recurring routes** (schedule-matched against current datetime via device timezone)
- **Places** (named locations for ASAP routing; Home has special fallback role)
- Current GPS location

Logic — **weighted scoring across all four sources** (see *Places, Saved Routes &
Recurring Routes* for full scoring table). The top candidate loads straight into
the hero. A stale or location-mismatched candidate scores itself out.

**Nearest-place context:** the scorer always knows which saved Place the user is
currently closest to. This informs every signal — e.g. if the user is near Work
at 17:30, Home scores higher as a destination even without a saved plan.

**Home fallback:** if nothing scores above the minimum threshold *and* the user's
nearest Place is not Home, assume the destination is Home.
If the user is already nearest to Home → no auto-destination; show place chips +
search.

Other saved **places** appear as quick-tap chips so one tap re-targets the hero.

> **First-run note:** a brand-new user has no places, plans, or history, so the
> scorer has nothing to score. First launch runs guided onboarding instead (see
> below); the scorer only takes over once the user has accumulated some signal.

### 1b. First-run onboarding

A short guided setup on first launch — the scorer can't infer intent with an empty
profile, so we bootstrap one:
1. **Welcome + the pitch** (one screen, skippable)
2. **Foreground location permission** — with contextual rationale (nearby-stop
   discovery). Background location is *not* requested here — it comes later at
   trip lock-in (see *Location*).
3. **Set Home** — prompt to save the user's Home place (search or use current GPS).
   Skippable, but Home anchors the app-open fallback, so we nudge.
4. **Drop into a search-first screen** — not the inferred hero (no data yet). As the
   user searches, takes trips, and saves places, the app-open scorer progressively
   takes over on subsequent launches.

### 2. Multi-origin optimization — the engine

**Architecture: On-device GTFS + RAPTOR**

Data layer:
- Swiss GTFS static (downloaded on first launch, stored in SQLite, updated yearly each December)
- GTFS-RT protobuf overlay (fetched every 30-120s, merged in-memory on top of static schedule)
- First-launch pipeline: WorkManager job — download ZIP → parse CSVs → build routing indexes → done

Routing:
- Algorithm: RAPTOR (Round-based Public Transit Optimized Router) implemented in Kotlin (~500 lines core)
- Multi-origin is native to RAPTOR: initialize round 0 with ALL nearby reachable stops simultaneously
- Returns all Pareto-optimal results (earliest arrival + fewest transfers) in one 5-50ms pass
- No rate limits. No backend. Works offline after initial download.

Given: GPS location + destination + now
1. Query local GTFS `stops.txt` SQLite → find all stops within user's walk + run radius (time-based, not metres)
2. For each stop: walking/running time to reach it → earliest possible board time
3. RAPTOR round 0: seed all reachable stops with their board times
4. RAPTOR propagates full graph (A→B→C→D + all A' variants) in one pass
5. Rank results — tiebreaker order: **(1) earliest arrival at destination → (2) fewest transfers → (3) shortest walk to the boarding stop.** First result wins the hero card; the rest become alternatives.
6. Repeat on GTFS-RT refresh cycle (30-120s)

Walk/run pace: user-configured in settings. Defaults: 6 km/h walk, 10 km/h run.

Known hard parts:
- Transfer walking graph: GTFS `transfers.txt` covers known interchanges; major hub internals (Zürich HB etc.) may need manual supplement from OSM footpath data or swisstopo
- GTFS-RT overlay: static data stays immutable; delays applied in a separate in-memory layer
- First-launch pipeline: WorkManager expedited job, downloads in priority order (see below). `transport.opendata.ch` fallback covers the gap. Once Phase 1 imports, silently switches to on-device RAPTOR.

**GTFS partition strategy — download in priority order:**

| Layer | Contents | When | Est. size |
|-------|---------|------|-----------|
| Stops (all CH) | All stops.txt — powers search box | Always first | ~1-2 MB |
| Home canton | Trips where every stop is within user's GPS-detected canton | Phase 1 | ~5-15 MB |
| Inter-canton | Trips crossing canton boundaries (IC, IR, RE, cross-canton S-Bahn) | Phase 1 | ~10-15 MB |
| Other cantons | Each remaining canton's local network | Phase 2 (background) | ~20-40 MB total |
| International | Trips with stops outside Switzerland | On-demand | Small |

Phase 1 (~15-25 MB) targets ~30s on LTE → user gets on-device routing for most local trips very quickly.
If destination is outside home canton and Phase 2 isn't done yet → transparent `transport.opendata.ch` fallback for that query.

**Hosting:** Pre-processed per-canton GTFS splits self-hosted on S3 + CDN. Build-time CI job re-splits on each opentransportdata.swiss annual timetable publish. Files versioned; app fetches a manifest first to check freshness.

Canton detection: GPS lat/lng matched against a static canton boundary polygon file bundled in the APK (~50 KB GeoJSON).

### 3. Trip lock-in

Gesture split (tap is reserved for detail, so lock-in lives on swipe):
- **Tap** hero card → expands to trip detail (separate screen / popup / hover-style
  expansion) showing full leg breakdown, alternatives, "Take this trip"
- **Swipe** hero card → locked in directly (the quick path)
- **Undo:** lock-in shows a brief snackbar with **Undo** to catch accidental swipes
  (mitigates the accidental-lock-in risk of a swipe gesture)
- Lock-in triggers: background monitoring, location permission upgrade prompt (if needed)

### 4. Journey Strip — active trip screen

A live vertical timeline of the full journey:

```
[Zürich HB]
    │
  ... skipped stops
    │
  Oerlikon (2 stops away)
    │
  Stettbach (1 stop away)
    │
► [Dietlikon] — get off here          🟡 4 min buffer
    │
  Walk 350m · 4 min · Platform 3
    │
[S8 · 17:42]
    │
  ...
    │
[Winterthur] ← destination
```

- Each leg: line name, stops, live delay badge
- Each transfer: Commuter state, buffer minutes, walking distance to next stop/platform
- **Per-leg swap button**: replace that leg with next best alternative; app re-optimizes the rest toward the same destination automatically
- Change arrival target time on the fly; whole journey re-optimises
- Goal: destination, as soon as possible — always

### 5. Disruption push notification

Format: problem + best alternative pre-calculated, surfaced as a **prompt with an
explicit Switch / Dismiss choice** — the app never silently re-routes a locked-in
trip, and never just passively notifies and walks away. The user stays in control.

Example notification:
> *"S12 is 8 min late. S8 17:55 from platform 4 arrives 4 min earlier. **Switch?**"*
> — actions: **[ Switch ]  [ Dismiss ]**

On **Switch**: the new option becomes the locked-in trip and the rest of the
journey re-optimises toward the same destination.
On **Dismiss**: the original trip stays locked in; the better alternative remains
visible on the Journey Strip so the user can swap manually later.

**Switch-prompt trigger threshold:**
- Fires whenever a recomputed alternative *improves* final arrival — down to 1 min.
- The minimum-improvement-to-prompt is **user-configurable in Settings**
  (minutes saved). Default **1 min** (i.e. any improvement prompts).
- A confirmed missed connection always prompts, regardless of the threshold.

Triggers:
- Connection will be missed (calculated from delay + transfer buffer)
- Leg cancelled
- Arrival time shifts significantly
- Platform change **only at major multi-track stations** (Zürich HB, Bern, Luzern,
  Lausanne, Genève, Lugano, Winterthur, Basel SBB, Olten, St. Gallen, ...)
- A new optimal alternative beats the locked-in trip by at least the configured
  minimum-improvement threshold

### 6. Multi-hop / Day planner

A distinct "plan a trip" mode for itineraries with flexible dwell times:
- Add waypoints + dwell time range (e.g. "at Zurich office for 20–40 min")
- App optimizes the whole chain: depart as late as possible for leg 1 while still meeting the final target arrival
- Surfaces as a timeline with per-leg slack/risk indicators and the Commuter at each transfer
- Architecture must support this from day one; screen design can be v2

### 7. Home-screen widget (Jetpack Glance — v1, essential)

- Destination name · departure time · Commuter state · minutes to leave
- Tap → opens active trip screen or home screen
- Updates on the converging monitoring schedule

---

## PLACES, SAVED ROUTES & RECURRING ROUTES

Three distinct concepts. Most users will tap a place chip most of the time; the
other two exist for users who want a pre-planned or scheduled workflow.

---

### Places
Named locations — no time or direction attached.
- Examples: Home, Work, Mum's, Gym
- One place can be designated **Home** — used as the app-open fallback destination
  when no plan scores high enough and the user is not already near it
- Tapping a tile routes from current GPS → that place, ASAP
- Displayed as quick-tap chips on the home screen
- The app **always computes the user's nearest saved Place** at runtime — this is a
  live context signal used throughout the app (see *App-open scorer* and the hero
  card). No fixed distance threshold; it is simply whichever place is closest.

**Place CRUD:**
- **Create:** save current GPS location as a new place, or pick any searched/pinned
  map destination
- **Edit:** rename, change destination coordinates, mark as Home, reorder
- **Delete:** remove a place (no cascade — saved routes that referenced it keep their
  destination coordinates)

---

### Saved routes
A one-off trip saved for a specific date + time.
- Example: "Next Thursday at 15:00 → Bern Hauptbahnhof"
- Stored locally; the app-open scorer surfaces it when that datetime is approaching
- After the trip the user can keep it (becomes history), delete it, or promote it to
  a recurring route
- Can be created from: a search result, a place tile, or the active Journey Strip
  ("save this trip")

---

### Recurring routes
A saved route with a **recurrence rule** — modelled after calendar scheduling.
- Examples: "Weekdays at 08:20 → Work", "Every second Wednesday at 19:00 → Zürich HB", 
  custom cron-style
- The app-open scorer evaluates each recurring route against the current datetime
  using `ZoneId.systemDefault()`: if a scheduled occurrence is imminent, it scores highly
- Recurrence rule storage: iCal RRULE syntax internally (covers daily, weekly,
  custom patterns); schedule evaluation uses `ZoneId.systemDefault()` at runtime —
  no timezone stored in the database
- Exposed in the UI as friendly presets + an "advanced" mode for power users
- User can pause, edit, or delete a recurring route
- On each occurrence: optionally fires a pre-departure reminder notification

---

### App-open scorer — full input set

All four signal sources are evaluated on every launch:

| Source | Signals used |
|--------|-------------|
| **Last search** (unsaved) | Recency + proximity to origin |
| **Saved routes** | How close *now* is to the saved datetime + proximity |
| **Recurring routes** | Does a scheduled occurrence fall within the next ~2 hours? + proximity |
| **Places** | Nearest saved Place computed at runtime; Home as fallback destination when not already nearest to it |

Highest-scoring candidate loads into the home-screen hero automatically. Ties
broken by recency. If nothing scores above a minimum threshold: show the **dynamic
search surface** (below), no auto-destination.

---

### Dynamic search surface (drag-to-route)

The fallback when the scorer has nothing to surface. Also reachable any time the
user wants to start a fresh search. **v1/v2-agnostic** — the interaction is
identical regardless of which routing engine is underneath.

The tile grid mixes the user's saved **Places** with special control tiles:

| Special tile | Role |
|--------------|------|
| **Current Location** | GPS origin/destination |
| **From** | Opens a text search to find any stop/address/POI in the network; fills the *origin* end. Shows a connector icon (→) to read as "From ●—" |
| **To** | Same text search, fills the *destination* end. Connector icon (—→●) to read as "—→ To" |

The **From / To** tiles are the same text-search control in different roles; the
connector icon is the visual cue for which end it fills.

Interaction:
- **Click** any tile → "**FROM Current Location → that tile**" (the common case)
- **Drag** tile A → tile B → "**FROM A → B**"
- A drag that connects **From → To** (both unspecified search tiles) → prompt the
  user to define *both* endpoints
- Any place tile can be either end depending on drag direction; Current Location and
  the From/To search tiles compose freely with saved places

Search-mode matrix (all combinations supported):

| | From | To |
|---|------|-----|
| Current Location | ✓ | ✓ |
| Saved Place (tile) | ✓ | ✓ |
| Text search (From / To tile) | ✓ | ✓ |

---

## DOMAIN MODEL — engine-independent (enables the v2 swap)

The single most important architectural rule for making the v1→v2 engine swap
painless: **the UI must never see API DTOs.** It binds only to domain models that
neither the API nor RAPTOR own.

Today the code leaks DTOs straight to the UI — `TransportRepository.getConnections`
returns `ConnectionsResponseDto`, and `ConnectionSearchScreen` binds to
`ConnectionDto` fields like `duration: String?` (`"00:45"`) and string times. Those
shapes exist only because that's what `transport.opendata.ch` returns. RAPTOR
produces nothing like them.

**Fix — introduce a domain layer the UI binds to:**

```
domain/
  model/
    Connection.kt   ← what every screen + ViewModel binds to
    Leg.kt          ← one transit leg or walk segment
    Stop.kt         ← a stop with real-time state (delay, platform, cancelled)
  TransportRepository.kt   ← returns domain models, NOT DTOs
```

- Domain types use real types (`Instant`, `Duration`, enums) — not API strings
- Repository interface returns domain models:
  `suspend fun getConnections(origin: Origin, to: Destination): List<Connection>`
- Two interchangeable implementations behind the one interface:

```
data/remote/ApiTransportRepository.kt   ← maps ConnectionDto → Connection   (v1)
data/gtfs/GtfsTransportRepository.kt    ← maps RAPTOR output → Connection    (v2)
```

The UI, ViewModels, scorer, monitoring, and widget all bind to `Connection` and
never change when the engine swaps. **This refactor happens in v1, up front** —
it's good architecture regardless, and it's the precondition for v2 dropping in
cleanly.

---

## V1 → V2 ENGINE TRANSITION

When the GTFS infra is done, swapping the engine is one Hilt binding:

```kotlin
// v1
@Binds abstract fun bindRepo(impl: ApiTransportRepository): TransportRepository
// v2
@Binds abstract fun bindRepo(impl: GtfsTransportRepository): TransportRepository
```

**What changes for the user (mostly invisible, strictly better):**
- Connections become genuinely **multi-origin** — hero may switch stops because
  RAPTOR weighed all nearby origins at once (the wedge finally works)
- "Walk to stop" on the hero becomes meaningful — RAPTOR chose *which* stop and why
- Richer real-time — GTFS-RT brings cancellations + platform changes + track-level
  precision vs the single `delay` integer the v1 API returns
- **Works offline** after the initial download; routing no longer hits the network
- **No rate limit** — routing is local, so refresh/monitoring/scorer run freely

**Runtime router selection.** It is not a hard cutover. On first launch, while GTFS
downloads, the app uses `ApiTransportRepository`. A readiness flag flips per region
as Phase 1/2 imports complete; from the next query onward the app routes via RAPTOR
for covered regions and falls back to the API for anything not yet downloaded. If a
user searched during the download window, results may improve afterward — surface a
subtle "routing updated" indicator rather than changing silently.

---

## DESTINATION RESOLUTION

Accepted destination types: transit stops, street addresses, POIs, map coordinate (pin/tap).

Resolution pipeline:
- **Search box:** free-text query against local GTFS `stops.txt` SQLite for stop names;
  swisstopo (geo.admin.ch) for addresses + POIs
  - If result is a stop → use directly as RAPTOR target
  - If result is address/POI → lat/lng → nearest stops via local GTFS SQLite
- **Map pin:** swisstopo reverse geocode for display label; nearest stops from local SQLite
- **Fallback geocoder:** OSM Nominatim (for anything swisstopo doesn't cover)
- **Pre-GTFS fallback:** `transport.opendata.ch /v1/locations` used only before the GTFS
  stop index is available (first launch, before Phase 1 download completes)

Destination stores as: `{displayName, lat, lng, resolvedStopIds[]}` — always anchored to stops for routing.

---

## DATA ARCHITECTURE

### Sources

| Source | Used for | Auth |
|--------|---------|------|
| `transport.opendata.ch` | Fallback journey planning + location search before GTFS is ready | None |
| `opentransportdata.swiss` GTFS static | Full Swiss timetable — downloaded once, powers RAPTOR | Free registration |
| `opentransportdata.swiss` GTFS-RT | Live delays, cancellations, platform changes — overlaid on static | Same free token |

**OJP is not used.** RAPTOR replaces OJP for journey planning; `stops.txt` from the
local GTFS SQLite replaces OJP for stop/location search. The only reason to touch
`opentransportdata.swiss` at all is to download the GTFS feeds.

`transport.opendata.ch` is the pre-GTFS fallback only — once Phase 1 of the GTFS
download is complete it is no longer called for routing or search.

### Caching strategy

- Disk cache of last search results, stationboards, delays, last-known stop lists
- **Stale-while-revalidate everywhere** — show cached data instantly, refresh behind the scenes
- Subtle staleness indicator (never silent failure, never blocking)
- During tunnel/offline: Journey Strip keeps running on cached schedule data, Commuter shows "⚠ offline"

### Rate-limit discipline (CRITICAL in v1)

`transport.opendata.ch` allows only **~1 000 req/day and 3 route queries/min per IP**.
In v1 *everything* routes through this API (no on-device engine yet), so naïve
implementation would exhaust the daily budget before midday. Mitigations are not
optional for v1:

- **Coalesce the app-open scorer:** score from cached/stored data first; fire at most
  one live route query on open, not one per candidate
- **Cache aggressively with TTLs:** connection results and stationboards cached and
  reused within a short TTL; identical queries served from cache
- **Throttle monitoring:** the T-20/10/5/2/1 schedule already bounds background
  queries; ensure they respect the 3/min ceiling (stagger, don't burst)
- **Debounced autocomplete (300ms)** and single-active-query-per-destination
- **Back off on 429:** exponential backoff + surface cached data, never hammer
- **Per-trip query budget:** monitoring for a locked-in trip must fit within the
  daily allowance alongside normal browsing

> This is the strongest argument for prioritising the v2 GTFS/RAPTOR engine: it
> removes the rate limit entirely (routing goes on-device). Until then, v1 lives
> within these constraints by design.

### Background monitoring schedule (WorkManager)

Once locked in: scheduled jobs at **T-20 → T-10 → T-5 → T-2 → T-1** minutes before departure.
Each job: fetch GTFS-RT, recompute risk via RAPTOR, fire notification if disrupted, update widget.

GTFS-RT refresh schedule:
- **Foreground (app open):** every 60s
- **Background:** only on WorkManager schedule (T-20/10/5/2/1) — no continuous polling
- Battery-efficient: no foreground service except during active background-location window

---

## LOCATION

- **Foreground** (onboarding): nearby stop discovery, multi-origin radius
- **Background** (requested at lock-in with contextual explanation): "approaching your stop" alert even with screen locked
- Progressive trust — no upfront "always allow" demand

**Android 13/14 background-location caveat:** `ACCESS_BACKGROUND_LOCATION` can no
longer be granted via an in-app dialog — the OS forces a redirect to system Settings
("Allow all the time" is not offered inline). The lock-in flow must therefore:
- Explain *why* before sending the user out (contextual pre-prompt)
- Deep-link to the app's location settings page
- Degrade gracefully if the user declines: the trip still works, only the
  screen-locked "approaching your stop" alert is unavailable. Never block lock-in on
  background permission.

---

## CALENDAR INTEGRATION

Automatically creates travel plans from calendar events so the app-open scorer
knows what's coming up without the user doing anything.

### Source

**Android device calendar via `CalendarContract`** (not Google Calendar OAuth).
- One `READ_CALENDAR` permission — covers every calendar synced to the device
  (Google Calendar, Exchange, iCloud via third-party apps, any other CalendarProvider)
- No OAuth flow, no Google Cloud project, no third-party credential storage
- Permission requested contextually when the user first enables calendar sync

### What gets pulled

- Every event in the next **7 days** that has a non-empty `Location` field
- All-day events are included (travel time planned for their start-of-day)
- Location field is free text → resolved via the transport.opendata.ch
  `/v1/locations?query=` endpoint (same geocoder used by the search screen)
- Each resolved event becomes a **saved route**: GPS → resolved stop(s), timed to
  arrive at the event location by the event's start time
- **Unresolvable locations are silently skipped.** Calendar `Location` fields are
  notoriously noisy ("Conference room B", "Zoom", "see invite"). If the geocoder
  returns no confident stop/address match, no plan is created and the user is not
  notified — better to create nothing than a spurious broken plan. (A future "Needs
  review" surface could revisit skipped events; out of scope for now.)

### De-duplication & lifecycle

- Each calendar-sourced plan is tagged with the originating `CalendarContract` event
  UID so re-syncs don't create duplicates
- If the source event is moved or rescheduled: plan updates automatically on the
  next sync
- If the source event is deleted: plan is flagged and the user is prompted to remove
  it (not silently deleted, in case the user wants to keep the route)
- User can manually delete or edit any calendar-sourced plan; a manual edit breaks
  the link to the calendar event (no further auto-updates for that plan)

### Background worker (WorkManager)

- `PeriodicWorkRequest` — fires every **N hours** (user-configurable, default 4 h)
- On each run: query CalendarContract for events in the next 7 days with a location,
  resolve locations, upsert plans, prune stale ones
- Feeds directly into the app-open scorer on the next launch (scorer reads from the
  local plans store, which the worker has already populated)
- No network call needed for the CalendarContract read; location resolution does hit
  the API (one call per *new or changed* event location, not every sync)
- Worker respects the standard WorkManager constraints: runs only when network is
  available for the geocoding step

### Settings additions (see Settings section)

- Calendar sync toggle (on/off)
- Sync interval (configurable; e.g. 1 / 2 / 4 / 6 / 12 hours; default 4 h)
- Which calendars to include (default: all; user can exclude specific calendars)

---

## INTEGRATIONS

- **SBB Mobile deep-link:** trip detail → "Buy ticket" → opens SBB Mobile with route pre-filled
- **EasyRide shortcut button** on trip detail: one tap → SBB Mobile opens directly in EasyRide check-in mode

---

## SURFACES ROADMAP

| Surface | Priority |
|---------|---------|
| Android phone app | v1 |
| Home-screen widget (Glance) | v1 |
| Disruption push notifications | v1 |
| Wear OS companion | Roadmap |
| Lock-screen / Dynamic Island equivalent | Roadmap |

---

## ACCESSIBILITY

- TalkBack / content descriptions on every Composable: **v1 (discipline, not optional)**
- Step-free routing filter: later
- Large-type dynamic font scaling: supported via Material3 / sp units

---

## PERFORMANCE REQUIREMENTS

- Cold start → useful cached content: **< ~1 second**
- Stale-while-revalidate: network never blocks the UI
- Compose stability: `@Stable`/`@Immutable` on all state models, `LazyColumn` with stable keys, no unnecessary recomposition, baseline profiles for startup
- Rate-limit discipline: debounced autocomplete (300ms), hard-cached location lookups, coalesced parallel requests, single active query per destination

---

## SETTINGS

User-configurable preferences (persisted on-device via DataStore):

| Setting | Default | Notes |
|---------|---------|-------|
| Walking pace | 6 km/h | Feeds multi-origin reachable-stop radius + transfer buffers |
| Running pace | 10 km/h | Used for "run" effort-level alternatives |
| **Switch-prompt minimum improvement** | **1 min** | Smallest final-arrival saving that triggers a "Switch?" prompt. 1 = prompt on any improvement. Confirmed missed connections always prompt regardless. |
| **Home place** | (set on first launch or in Places) | Anchors the app-open fallback; app always computes nearest saved Place at runtime |
| **Calendar sync** | Off | Toggle; requires `READ_CALENDAR` permission on first enable |
| **Calendar sync interval** | 4 hours | How often the WorkManager job re-reads the device calendar; options: 1 / 2 / 4 / 6 / 12 h |
| **Calendars to include** | All | Multi-select list of calendars found on device; user can exclude specific ones |

---

## KEY OPEN DESIGN QUESTIONS (to resolve in implementation)

1. **SBB Mobile deep-link scheme:** What URL/intent scheme does SBB Mobile expose for pre-filled connections and EasyRide? (Requires reverse-engineering or official docs.)
2. **Multi-hop screen UX:** Detailed design of the day-planner flow.
3. **Commuter animation spec:** Exact frames/states for the teleporting sprint. Commission or build in-house?
4. **opentransportdata.swiss token management:** Needed for GTFS static download (build-time CI) and GTFS-RT at runtime. Token in app bundle? In CI secret? What are the GTFS-RT rate limits?
5. **Stop walking-graph data:** For transfer walking time (e.g. platform-to-platform inside a station), GTFS `transfers.txt` has minimum transfer times but not walking paths. Do we supplement from OSM, swisstopo, or hand-authored data for major hubs?
