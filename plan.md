# SBB Ruby Slippers — Product & Functionality Plan

> Status: **complete first pass** — all core questions resolved.
> This is a living document; update it as implementation decisions are made.

---

## THE WEDGE

**SBB Mobile picks ONE departure stop. We optimize across ALL of them simultaneously.**

Every existing transit app asks "which stop are you at?" We ask "where are you *right now*, and where do you need to be?" — then find every viable combination of nearby stops, ranked by when you'd actually *arrive*, accounting for your personal walking and running pace.

The name is the product: *click your heels, you're home.*

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
- Last thing the user searched for / the last (and other) **saved travel plans**
- Current time
- Current GPS location

Logic — **weighted scoring across signals**. Every candidate destination (the last
active plan, each saved tile/plan, and Home) is scored on:
- **Recency** — how recently it was searched/used/locked-in
- **Time-of-day fit** — does *now* fall in the plan's time window? (a plan may be
  "ASAP" or "at a specific time / window")
- **Proximity** — is the user near this plan's natural origin right now?

The top-scoring candidate is loaded straight into the home-screen hero. A stale
plan (its departure long past, or the user is now nowhere near its origin) scores
itself out.

**Home fallback:** if nothing scores high enough, *and* the user is **more than
500 m from their Home tile**, assume the destination is Home and load that.
(Within 500 m of Home → no auto-destination; show tiles + search.)

If the user has other **primary tiles** (Work, a friend's place, …), surface them
as quick-click tiles alongside the inferred hero so one tap re-targets.

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
1. OJP LocationInfoRequest → find all stops within user's walk + run radius (time-based, not metres)
2. For each stop: walking/running time to reach it → earliest possible board time
3. RAPTOR round 0: seed all reachable stops with their board times
4. RAPTOR propagates full graph (A→B→C→D + all A' variants) in one pass
5. Rank results — tiebreaker order: **(1) earliest arrival at destination → (2) fewest transfers → (3) shortest walk to the boarding stop.** First result wins the hero card; the rest become alternatives.
6. Repeat on GTFS-RT refresh cycle (30-120s)

Walk/run pace: user-configured in settings. Defaults: 6 km/h walk, 10 km/h run.

Known hard parts:
- Transfer walking graph: GTFS `transfers.txt` covers known interchanges; major hub internals (Zürich HB etc.) may need manual supplement or OJP footpath queries
- GTFS-RT overlay: static data stays immutable; delays applied in a separate in-memory layer
- First-launch pipeline: WorkManager expedited job, downloads in priority order (see below). OJP fallback covers the gap. Once Phase 1 imports, silently switches to on-device RAPTOR.

**GTFS partition strategy — download in priority order:**

| Layer | Contents | When | Est. size |
|-------|---------|------|-----------|
| Stops (all CH) | All stops.txt — powers search box | Always first | ~1-2 MB |
| Home canton | Trips where every stop is within user's GPS-detected canton | Phase 1 | ~5-15 MB |
| Inter-canton | Trips crossing canton boundaries (IC, IR, RE, cross-canton S-Bahn) | Phase 1 | ~10-15 MB |
| Other cantons | Each remaining canton's local network | Phase 2 (background) | ~20-40 MB total |
| International | Trips with stops outside Switzerland | On-demand | Small |

Phase 1 (~15-25 MB) targets ~30s on LTE → user gets on-device routing for most local trips very quickly.
If destination is outside home canton and Phase 2 isn't done yet → transparent OJP fallback for that query.

**Hosting:** Pre-processed per-canton GTFS splits self-hosted on S3 + CDN. Build-time CI job re-splits on each opentransportdata.swiss annual timetable publish. Files versioned; app fetches a manifest first to check freshness.

Canton detection: GPS lat/lng matched against a static canton boundary polygon file bundled in the APK (~50 KB GeoJSON).

### 3. Trip lock-in

- Swipe left/right on hero card → locked in (quick path)
- Or: tap card → trip detail → "Take this trip" button (deliberate path)
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

## TILES & HISTORY

A **tile** is the user's unit of "a place I travel to." Tiles and saved travel
plans are the same thing — a tile is a destination plus an optional intent:
either **ASAP** or **at a specific time / time window** (e.g. "Work, weekday
mornings"; "Home, after 17:00"). Tapping a tile starts a plan to it. The user can
save **multiple** tiles/plans.

Tile kinds:
- **Primary tiles** — explicitly named, durable places: **Home**, Work, a friend's
  place, etc. Home is special: it anchors the >500 m app-open fallback.
- **Implicit tiles** — the app detects recurring destinations from history and
  promotes them automatically; no explicit star required.

**Tile management (CRUD):**
- **Create** — save the current location as a tile, or create a new tile from any
  searched/picked destination
- **Edit** — rename, change the destination, change/clear the time window, mark as
  primary (e.g. designate which tile is Home)
- **Delete** — remove a tile
- Quick-click tile chips appear on the home screen and feed the app-open scorer

**History:**
- **Auto-save everything:** every searched destination AND every trip taken →
  stored locally, infinite history, never uploaded
- History feeds implicit-tile detection and the recency signal in the app-open scorer

**Privacy:** all tiles and history stay on-device, never uploaded.

---

## DESTINATION RESOLUTION

Accepted destination types: transit stops, street addresses, POIs, map coordinate (pin/tap).

Resolution pipeline:
- **Search box:** OJP LocationInfoRequest (free-text → stops + addresses + POIs in one response)
  - If result is a stop → use directly as RAPTOR target
  - If result is address/POI → lat/lng → nearest stops via local GTFS SQLite (no extra API)
- **Map pin:** swisstopo (geo.admin.ch) reverse geocode for display label; nearest stops from local SQLite
- **Fallback geocoder:** OSM Nominatim (for anything swisstopo doesn't cover)

Destination stores as: `{displayName, lat, lng, resolvedStopIds[]}` — always anchored to stops for routing.

---

## DATA ARCHITECTURE

### Sources (dual, fallback between them)

| Source | Used for | Auth |
|--------|---------|------|
| `transport.opendata.ch` | Connections, stationboard, location search | None |
| `opentransportdata.swiss` OJP / GTFS-RT | Real-time delays, cancellations, platform changes | Free token |

If either source lacks delay data, fall back to the other. Never block UI on either.

### Caching strategy

- Disk cache of last search results, stationboards, delays, last-known stop lists
- **Stale-while-revalidate everywhere** — show cached data instantly, refresh behind the scenes
- Subtle staleness indicator (never silent failure, never blocking)
- During tunnel/offline: Journey Strip keeps running on cached schedule data, Commuter shows "⚠ offline"

### Background monitoring schedule (WorkManager)

Once locked in: scheduled jobs at **T-20 → T-10 → T-5 → T-2 → T-1** minutes before departure.
Each job: fetch GTFS-RT, recompute risk via RAPTOR, fire notification if disrupted, update widget.

GTFS-RT refresh schedule:
- **Foreground (app open):** every 60s
- **Background:** only on WorkManager schedule (T-20/10/5/2/1) — no continuous polling
- Battery-efficient: no foreground service except during active background-location window

---

## LOCATION

- **Foreground** (install): nearby stop discovery, multi-origin radius
- **Background** (requested at lock-in with contextual explanation): "approaching your stop" alert even with screen locked
- Progressive trust — no upfront "always allow" demand

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

---

## KEY OPEN DESIGN QUESTIONS (to resolve in implementation)

1. **SBB Mobile deep-link scheme:** What URL/intent scheme does SBB Mobile expose for pre-filled connections and EasyRide? (Requires reverse-engineering or official docs.)
2. **Multi-hop screen UX:** Detailed design of the day-planner flow.
3. **Commuter animation spec:** Exact frames/states for the teleporting sprint. Commission or build in-house?
4. **opentransportdata.swiss token management:** Token in app bundle? In CI secret? Rate limits?
5. **Stop walking-graph data:** For transfer walking time (e.g. platform-to-platform inside a station), is this available from OJP, or do we derive it from map data?
