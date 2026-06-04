# GTFS Query Catalogue

Every read query the app needs to make against GTFS data.
For each query: what is being asked, the exact scenario that triggers it,
why it cannot be avoided, and what it forces us to understand about the
shape of the underlying data.

Storage and format decisions come after this catalogue is complete and understood.

---

## The raw GTFS files

A quick map of what each file actually contains, so the queries below make sense.

| File | One row represents | Approximate Swiss feed size |
|---|---|---|
| `stops.txt` | A physical stopping point — a platform, a bus stop, a station entrance | 30 000 rows |
| `routes.txt` | A named line — "IC 5", "S8", "Bus 31" | 2 000 rows |
| `trips.txt` | One specific run of a route on a specific day pattern | 500 000 rows |
| `stop_times.txt` | One vehicle visit to one stop on one trip — the time it arrives and departs | 8 000 000 rows |
| `calendar.txt` | A service pattern — "runs Mon–Fri between these dates" | 5 000 rows |
| `calendar_dates.txt` | An exception to a service pattern — "also runs on this holiday" or "does not run on this date" | 20 000 rows |
| `transfers.txt` | A surveyed walking path between two nearby stops with a minimum transfer time | 5 000 rows |
| `frequencies.txt` | A trip that runs every N minutes instead of at fixed times | 1 000 rows |
| `feed_info.txt` | Metadata about this feed — version number, valid date range | 1 row |
| `fare_attributes.txt` | A fare product — price, currency, how many transfers it covers | Unknown Swiss coverage |
| `fare_rules.txt` | Which fare applies on which routes or between which zones | Unknown Swiss coverage |

The critical observation before reading any query: **`stop_times.txt` is
where almost all the data lives.** Everything else is reference data.
8 million rows × the fields needed per row is what determines whether a
given storage approach works or breaks under the RAPTOR access pattern.

---

## The queries

---

### R1 — "What trips can I board at stop X right now?"

**Full question:**
Given a stop_id and a time T on day D, return every trip that departs this
stop at or after T and actually operates on day D — ordered by departure time,
earliest first.

**When it fires:**
This is the innermost operation of the RAPTOR routing algorithm. RAPTOR works
in rounds. In each round it takes the set of stops you have already reached
and asks, for each one: "what can I board here?" A typical routing query
covers 200–400 reached stops across 3–5 rounds. R1 fires roughly 1 000–2 000
times per user query.

**Why it cannot be skipped:**
Without this query there is no routing. Everything else in this catalogue is
either setup for this query or presentation of its results.

**What it tells us about the data:**
There are three pieces of information that must come together to answer R1:

1. **Which stop_times rows belong to stop X** — this is a filter on stop_id,
   which is one column in an 8-million-row table.
2. **Which of those rows have departure_time ≥ T** — another filter on that
   same table, this time on the time column. For this to be fast, the data
   either needs to be physically sorted by (stop_id, departure_time) or needs
   an index that can binary-search to the first eligible departure and scan
   forward.
3. **Which of those trips actually run on day D** — this requires a join or
   lookup against the calendar/calendar_dates data. It cannot be inlined into
   stop_times because the same trip_id recurs on many days; the service-day
   logic lives separately in trips.txt → service_id → calendar.

The data structure implication: if you want R1 to be fast, the 8M stop_times
rows almost certainly need to be pre-grouped and pre-sorted per stop. A naive
index on a general-purpose table will work but will carry per-call overhead.
A data structure that physically places all departures for stop X together,
sorted by time, eliminates the search cost entirely.

---

### R2 — "Where does this trip go from here?"

**Full question:**
Given a trip_id and the stop_sequence where I boarded, return every subsequent
stop on that trip in order — with each stop's arrival time, departure time,
and stop_id.

**When it fires:**
Immediately after R1 identifies a boardable trip. RAPTOR boards the trip and
then "rides it" forward: every stop it visits after the boarding point is a
candidate newly-reached stop. This fires once per boarded trip, which is
typically dozens to a few hundred times per query.

**Why it cannot be skipped:**
The whole point of boarding a trip is to reach the stops it serves. Without
this query, RAPTOR cannot propagate labels forward through the network.

**What it tells us about the data:**
Stop_times for a single trip are a short, ordered sequence — typically
5–50 rows. The critical property is that they need to be readable **in
stop_sequence order** and **locatable by trip_id** quickly.

This points to a different access pattern than R1. Where R1 needs to find all
trips serving a stop, R2 needs to find all stops served by a trip. These two
requirements pull the data in opposite directions. A structure optimised for
R1 (grouped by stop) is not automatically fast for R2 (grouped by trip), and
vice versa. The data either needs to be indexed two ways, or R2 data needs to
be stored separately from R1 data.

---

### R3 — "Where can I walk from this stop?"

**Full question:**
Given a stop_id, return all other stops reachable on foot from it — with the
walk time in seconds for each.

**When it fires:**
At the end of every RAPTOR round, after all trips for that round have been
processed. Any stop that was reached by a vehicle in this round might have
walkable neighbours that can be reached "for free" before the next round
starts. This fires once per reached stop per round — same scale as R1.

**Why it cannot be skipped:**
Without walking transfers, RAPTOR would require you to board and alight at
the exact same physical stop_id. In reality, Zürich HB has dozens of
stop_ids for its various platforms and exits, and many pairs of stations are
connected by a surveyed interchange walk (e.g., Basel SBB ↔ Basel Bad Bf).
Without R3, these connections are invisible to the router.

**What it tells us about the data:**
There are two sources of walking connections:

1. **`transfers.txt`** — surveyed, authoritative minimum transfer times between
   specific stop pairs. These are sparse (5 000 rows for the whole country)
   and known at import time.
2. **Proximity-derived transfers** — any two stops within, say, 300 metres of
   each other can be connected by a computed walking time (distance ÷ user's
   walking pace). These are not in the GTFS file; they must be computed.

Both sources produce the same thing: a mapping from stop_id → list of
(neighbour_stop_id, walk_seconds). This is a **graph adjacency list**. The
stops are nodes; walking connections are edges. The total number of edges is
bounded by the density of transfer.txt entries plus the number of stop pairs
within the proximity threshold.

The data structure implication: for R3 to be fast at hot-path frequency, the
adjacency list needs to be pre-built and stored compactly per stop — not
reconstructed by joining or scanning on every call.

---

### R4 — "Does this service run today?"

**Full question:**
Given a service_id and a calendar date D, return true or false — does this
service operate on this date?

**When it fires:**
This is not called in the RAPTOR inner loop directly. Instead, it is called
once before routing starts, to build a set of all service_ids that are active
on the query date. That set is then used as a filter inside R1.

**Why it cannot be skipped:**
The same trip_id appears in stop_times for every day it ever runs. Without
filtering by calendar, RAPTOR would happily propose boarding a service that
ran last Tuesday but not today.

**What it tells us about the data:**
There are two GTFS files involved:

- `calendar.txt` says: "service_id S runs on Mondays, Tuesdays, ..., Saturdays
  between date A and date B."
- `calendar_dates.txt` says: "on this specific date, add or remove service_id S
  regardless of what calendar.txt says."

The exceptions in calendar_dates take precedence over the weekly pattern in
calendar. To answer R4 correctly you must check both files and let the
exception file win. Swiss public holidays all appear as exceptions here —
Christmas Day trains run on a Sunday pattern but the calendar.txt entry says
they run on a Wednesday.

The practical consequence: R4 is best answered by pre-computing a
`Set<service_id>` for today's date once at query start, not by evaluating
calendar logic per-trip inside the loop. That set becomes a constant for the
lifetime of one routing query.

---

### R5 — "Which trip_ids are valid today?"

**Full question:**
Given the set of active service_ids for today (from R4), return the set of
all trip_ids that belong to those service_ids.

**When it fires:**
Computed once per query day, alongside R4. Together R4 and R5 produce a
filter that R1 applies: "only consider departures whose trip_id is in this set."

**Why it cannot be skipped:**
R1 finds departures for a stop after a given time. Many of those departures
belong to trips that do not run today. Without the R5 filter, RAPTOR would
board non-existent services.

**What it tells us about the data:**
The link between a service_id and its trips lives in `trips.txt`. Each trip
has exactly one service_id. This is a straightforward 1-to-many lookup:
given a set of service_ids, find all trip_ids. The data is small enough
(500 000 trips) that the result set can be a hash-set held in memory for the
duration of a query.

---

### M1 — "What stops are near this location?"

**Full question:**
Given a latitude/longitude and a walking distance threshold (derived from the
user's configured walking pace and a time budget), return all stop_ids within
that radius with the estimated walk time to each.

**When it fires:**
Once per origin or destination place, before RAPTOR starts. If the user has
saved three places and the app is doing a multi-origin query, M1 fires three
times. The results seed RAPTOR's initial labels (for origins) or define the
target stop set (for destinations).

**Why it cannot be skipped:**
RAPTOR operates on stop_ids, not on arbitrary coordinates. A user's "Home"
is a lat/lng. To route from Home, we must first find all stops within
walking distance of Home and give each one an initial label of "reachable
at walk_time seconds." Without M1, the app cannot connect the user's real
world location to the transit network.

**What it tells us about the data:**
`stops.txt` contains a lat and lon for every stop. Finding stops within a
radius requires either:
- A **spatial index** (R-tree, geohash grid, or similar) that can eliminate
  most of the 30 000 stops without computing Haversine distance for all of them
- Or loading all stops into memory and filtering in Kotlin — feasible at 30 000
  rows but needs the stop list in RAM permanently

The data structure implication: stops need to be queryable by geographic
proximity. A flat table with no spatial organisation forces a full scan every
time M1 is called.

---

### M2 — "What are all the platform stops for this station?"

**Full question:**
Given a station name or parent_station id, return every child stop_id that
belongs to it.

**When it fires:**
When the user types "Zürich HB" as their destination, or when a saved place
has a station name. Before routing, that name must be expanded into all the
individual platform stop_ids that serve it.

**Why it cannot be skipped:**
Swiss stations are split into many stop_ids — one per platform, often one
per transport mode. "Zürich HB" has over 50 stop_ids (tram stops outside,
platform 1 through 18, underground platforms for S-Bahn, bus bay stops). A
route to "Zürich HB" must target all of them, not one arbitrary one, or RAPTOR
will miss connections that arrive on the "wrong" platform.

**What it tells us about the data:**
GTFS represents this with a parent-child hierarchy in `stops.txt`. Each
platform stop has a `parent_station` field pointing to the logical station
stop_id. The logical station stop_id has `location_type = 1`; platforms have
`location_type = 0`.

The data structure implication: stops need to be queryable by parent_station,
which means the parent-child relationship must be indexed or pre-grouped at
import time.

---

### M3 — "Find stops matching this search text."

**Full question:**
Given a partial string typed by the user (e.g. "Zür", "Bahnhof", "HB"),
return matching stop names ordered by relevance — preferring parent stations
over individual platforms, and more important stations over obscure ones.

**When it fires:**
Every keystroke in the From/To search fields. This is the autocomplete
query and needs to feel instant (< 100 ms).

**Why it cannot be skipped:**
Users cannot type exact stop_ids. They type natural language names. The app
must translate those names to stop_ids.

**What it tells us about the data:**
Stop names are strings. Prefix and substring matching on 30 000 names is
the requirement. Relevance ranking (a station with 50 000 daily passengers
should rank above a halt with 20) requires some importance signal — which
GTFS does not directly provide but can be approximated by the number of
stop_times rows referencing a stop_id (more departures = more important).

The data structure implication: a full-text or prefix index on stop names is
needed for responsive autocomplete. The importance signal must be pre-computed
at import time.

---

### M4 — "Seed RAPTOR from all my saved places at once."

**Full question:**
For each saved place in the app, run M1 and merge all the resulting
(stop_id, walk_time) pairs into a single initial label set for RAPTOR.

**When it fires:**
When the app-open scorer determines the user's intent is ambiguous — "I'm
probably going somewhere but not sure which origin applies." RAPTOR starts
with labels for all stops near all saved places simultaneously.

**Why it cannot be skipped:**
Multi-origin routing is one of V2's core features. Without it, the app must
pick one origin and commit, potentially missing a faster route from a
different nearby starting point.

**What it tells us about the data:**
This is not a new data query — it is M1 run multiple times. It does tell us
that M1 must be cheap enough to call 3–5 times in quick succession before
routing starts, reinforcing the need for a fast spatial lookup.

---

### J1 — "What is this stop called and where is it?"

**Full question:**
Given a stop_id, return its display name, latitude, longitude, and any
platform label.

**When it fires:**
Once per stop in the rendered journey — departure stop, arrival stop, and
every transfer point. Called after RAPTOR has found a route and the UI needs
to display it.

**Why it cannot be skipped:**
RAPTOR works with integer stop_ids. The user sees names. J1 is the
translation from internal id to human-readable display.

**What it tells us about the data:**
A straightforward point lookup on `stops.txt` by primary key. The only
implication is that stop_ids must be efficiently retrievable by id — trivial
for any indexed store.

---

### J2 — "What line is this, and what colour is it?"

**Full question:**
Given a trip_id, return the route it belongs to — its short name (e.g. "IC 5",
"S8"), its transport type (rail, tram, bus), and its brand colour if defined.

**When it fires:**
Once per leg in the journey display, to render the line badge.

**Why it cannot be skipped:**
The user needs to know which vehicle to board. "Board the red S8" is more
useful than "board trip_id 7492837."

**What it tells us about the data:**
Two-hop lookup: trip_id → route_id (from trips.txt), then route_id → name and
colour (from routes.txt). Both tables are small and this access pattern is
trivially fast with any index. The only structural note: trip_id alone is not
enough — you need the route relationship.

---

### J3 — "Which platform does this train depart from?"

**Full question:**
Given a trip_id and a stop_id, return the platform or track identifier for
that specific combination.

**When it fires:**
Once per leg, for the "Platform 3" label in the journey detail.

**Why it cannot be skipped:**
Platform information is critical for the user to find the correct train,
especially at large stations with many tracks. It also changes — real-time
updates can announce a platform change.

**What it tells us about the data:**
The platform is stored in the `stop_headsign` or `stop_times.stop_id` field
depending on the GTFS producer. In the Swiss feed, each platform is its own
stop_id — so the platform is implicit in the stop_id itself, not a separate
field. This means J3 may resolve to: "given this stop_id, what platform code
does it represent?" — which is answered by M2's parent_station logic in reverse.

---

### J4 — "What are the intermediate stops on this leg?"

**Full question:**
Given a trip_id and the stop_sequences of the boarding and alighting stops,
return all stops in between — with their arrival and departure times.

**When it fires:**
When the user taps "expand" on a leg to see intermediate stations, or for the
journey strip detail screen.

**Why it cannot be skipped:**
Users check intermediate stops to confirm they are on the right train and to
monitor their progress during travel.

**What it tells us about the data:**
This is a subset of R2 — the same trip forward scan but bounded on both ends.
Requires stop_times ordered by stop_sequence for the given trip_id. No new
structural requirements beyond what R2 already implies.

---

### J5 — "Where is this trip going?"

**Full question:**
Given a trip_id, return its headsign — the destination text displayed on the
front of the vehicle.

**When it fires:**
Once per leg, for the "Direction: Basel SBB" label.

**Why it cannot be skipped:**
The headsign is what passengers use to confirm the right vehicle on the
platform. "Direction: Luzern" on a train at Zürich HB tells you it is not
the IC to Geneva.

**What it tells us about the data:**
The headsign lives in `trips.txt` as a single field per trip_id. Simple point
lookup, no structural implications.

---

### S1 — "What is leaving this station in the next hour?"

**Full question:**
Given one or more stop_ids and a time window [T, T+N], return all departures
in that window ordered by departure time — including trip_id, departure time,
and stop_sequence.

**When it fires:**
When the user opens the Stationboard screen, or when the departure widget
renders. May also fire periodically to refresh the board.

**Why it cannot be skipped:**
The Stationboard screen is a core feature. Users check it before going to
a platform.

**What it tells us about the data:**
Structurally identical to R1 but returning a list rather than feeding RAPTOR.
The same index requirements apply. The difference is that S1 is called once
for display, whereas R1 is called thousands of times during routing. S1 can
tolerate a few more milliseconds.

---

### S2 — "For each departure on the board, what line and destination is it?"

**Full question:**
Given a trip_id from S1, return the route short name, headsign, transport
type, and colour.

**When it fires:**
Once per row in the stationboard, after S1 returns.

**Why it cannot be skipped:**
The stationboard would show raw trip_ids without this lookup.

**What it tells us about the data:**
Same two-hop lookup as J2. No new structural implications.

---

### RT1 — "Is this trip running late right now?"

**Full question:**
Given a trip_id and a stop_sequence, return the current delay in seconds at
that stop — or zero if on time.

**When it fires:**
During RAPTOR (to adjust scheduled times with real-time delays) and during
journey display (to show delay badges). The GTFS-RT feed is refreshed every
30 seconds; this query reads from the in-memory result.

**Why it cannot be skipped:**
A route that was optimal at query time may no longer be optimal 2 minutes
later if one of its trains is now running 8 minutes late. Real-time data is
what separates a useful app from a timetable PDF.

**What it tells us about the data:**
This query does not touch GTFS static data at all. The real-time overlay is a
separate data structure — a map of (trip_id, stop_sequence) → delay_seconds,
built from the GTFS-RT protobuf feed and held entirely in memory. It is
rebuilt from scratch every 30 seconds.

The structural implication for the GTFS data is indirect: RAPTOR needs to be
able to hand a trip_id and stop_sequence to RT1 mid-computation, which means
the RAPTOR algorithm must carry these identifiers forward as it propagates
labels. The internal label representation must include trip_id.

---

### RT2 — "Is this trip cancelled?"

**Full question:**
Given a trip_id, has it been cancelled for today's service?

**When it fires:**
Before boarding a trip in RAPTOR, and before displaying a departure on the
stationboard. A cancelled trip must not be boarded.

**Why it cannot be skipped:**
Boarding a cancelled train is the worst outcome of an incorrect routing result.

**What it tells us about the data:**
Cancellations come from the GTFS-RT TripUpdate with `CANCELED` or `DELETED`
schedule relationship. This is a boolean flag in the RT memory map, keyed by
trip_id. Structurally the same map as RT1, just a different value type.

---

### RT3 — "Are there service alerts for this stop or line?"

**Full question:**
Given a stop_id or route_id, return any active alert messages (text, severity,
affected time window).

**When it fires:**
When rendering a connection card or stationboard row. Used to show a warning
badge (e.g. "Strike on line IC 5 this afternoon").

**Why it cannot be skipped:**
Alerts are the primary way operators communicate disruptions. An app that
routes around a strike without telling the user why is confusing.

**What it tells us about the data:**
Alert data comes from the GTFS-RT Alerts feed — a third entity type alongside
TripUpdates and VehiclePositions. The data is a list of
(affected stop_ids or route_ids, message text, active period). Volume is low
(typically < 50 active alerts at any time). Held in memory, indexed by
stop_id and route_id. No interaction with static GTFS data during lookup.

---

### C1 — "This departure time says 25:30 — what does that mean?"

**Full question:**
Given a time string from stop_times.txt that exceeds 24:00:00, compute the
actual clock time and determine which calendar date it belongs to.

**When it fires:**
During GTFS import (to normalise all times before storing them) and
potentially during R1 at query boundaries.

**Why it cannot be skipped:**
GTFS allows times past midnight to keep overnight trips on a single calendar
day entry. A train that departs at 23:50 and arrives at 00:30 the next day
has stop_times of 23:50:00 and 24:30:00. If the app treats 24:30 as invalid
or misinterprets it, every overnight route breaks.

**What it tells us about the data:**
All times in stop_times must be treated as **elapsed seconds since service
day start**, not as clock times. A "day" in GTFS starts at roughly 04:00
(after the last late-night services end). Times between 0 and ~86400 map to
the service date; times above 86400 map to the following calendar date.

This means the calendar date attached to a departure is not always the same
as the `calendar.txt` date for that service_id. The data normalisation at
import time must handle this correctly, or R1 will silently return wrong
results near midnight.

---

### C2 — "Does this service still run on this specific date?"

**Full question:**
Given a service_id and a date D, check calendar_dates.txt for an exception
entry — either adding service on a day the regular calendar omits, or
removing service on a day the regular calendar includes.

**When it fires:**
As part of R4, when computing the active service_id set for the query date.

**Why it cannot be skipped:**
Switzerland has roughly 10 public holidays per year, plus occasional strike
days. On each of those days, `calendar_dates.txt` has exception entries that
override the weekly pattern. Ignoring exceptions means routing correctly on
weekdays but potentially catastrophically wrong on Christmas Day.

**What it tells us about the data:**
The active-service computation is a two-step merge: start with what
`calendar.txt` says for today's day-of-week, then apply all `calendar_dates`
exceptions for today's date — additions turn on service_ids that would
otherwise be inactive, removals turn off service_ids that would otherwise
be active. The exception always wins. This merge must happen before any
routing begins.

---

### F1 — "Does this trip run on a schedule or on headways?"

**Full question:**
Given a trip_id, does it have an entry in `frequencies.txt`? If so, return
the time window and headway in seconds.

**When it fires:**
During GTFS import, to identify frequency-based trips. These trips must be
expanded into concrete departure instances before being fed to RAPTOR.

**Why it cannot be skipped:**
Frequency-based trips have no fixed departure times in `stop_times.txt` — the
times there are offsets from the period start, not actual clock times. If
RAPTOR reads them as literal times it will produce completely wrong results.
These trips must be converted to concrete timed instances (one per headway
interval) at import time.

**What it tells us about the data:**
Frequency trips are a small minority (~1 000 out of 500 000 trips), but they
need special handling. The import pipeline must distinguish them and treat
them differently. The stored representation of stop_times must either mark
which rows came from frequency expansion or store the expanded instances
directly — so that R1 never needs to know about the distinction.

---

### I1 — "What version of the feed is currently loaded?"

**Full question:**
Return the feed_version and feed_start/end_date from the currently imported
GTFS data.

**When it fires:**
On app startup and when the periodic feed-sync WorkManager job runs, to
compare against the latest published feed version from opentransportdata.swiss.

**Why it cannot be skipped:**
The Swiss GTFS feed is updated roughly weekly. If the app does not know what
it has loaded, it cannot decide whether to download and re-import a newer
version.

**What it tells us about the data:**
A single metadata record — the content of `feed_info.txt`. No structural
implications beyond "we need to store one row of metadata that survives a
full re-import and is readable before routing begins."

---

### I2 — "Is a newer feed available?"

**Full question:**
Compare the loaded feed_version against the version available at the
opentransportdata.swiss download endpoint. Return whether an update is
available and what date range it covers.

**When it fires:**
Periodically (WorkManager job, default interval TBD), and when the loaded
feed's end_date is within a few days of today.

**Why it cannot be skipped:**
A stale feed means the router proposes services that no longer run and misses
new ones. Feeds become unreliable outside their published validity window.

**What it tells us about the data:**
This query is mostly a network call, not a local data query. Locally it just
needs I1. The structural implication is that a feed import must be
**atomic** — the app must not start a query mid-import against a half-written
dataset. Old data must remain fully readable until the new import is complete
and verified, then swap atomically.

---

### FA1 — "What does this journey cost?"

**Full question:**
Given the sequence of zones or routes traversed by a journey, return the
applicable fare_id and its price in CHF.

**When it fires:**
After RAPTOR produces a result, to populate the price field on a connection
card.

**Why it cannot be skipped:**
Price is a stated V2 feature. Without FA1, connection cards show no fare.

**What it tells us about the data:**
`fare_rules.txt` maps fares to routes, origin/destination zones, or
origin/destination stop pairs. `fare_attributes.txt` maps fare_ids to prices.

The critical unknown: **we do not know yet how completely the Swiss GTFS feed
populates these files.** Swiss pricing is a multi-layer system (national
direct tariff, ~20 regional zone tariffs, saver tickets, Half-Fare card
discounts) and it is unlikely all of this is in the standard GTFS fare files.
This query is in the catalogue because it is a planned feature, but it has a
required spike before it can be implemented: download the Swiss feed and
inspect `fare_rules.txt` to see what is actually there.

---

### FA2 — "How does the user's Half-Fare card or GA affect this price?"

**Full question:**
Given a fare_id from FA1 and the user's fare profile (Half-Fare card, GA,
Seven25, no card), return the adjusted price or a flag indicating the journey
is free.

**When it fires:**
Alongside FA1, after RAPTOR returns a result.

**Why it cannot be skipped:**
Displaying the full fare to a Half-Fare cardholder is actively misleading.
GA holders pay nothing and should see "covered by GA" rather than a price.

**What it tells us about the data:**
The fare profile is user-supplied data stored in the app's own DataStore
(a Settings field). Applying it to FA1's result is a simple multiplication
or flag check — it does not require any additional GTFS data. The structural
implication is only that `UserPreferencesRepository` needs a `fareProfile`
field.

---

## What the queries collectively tell us

Reading across all queries, several things become clear about the shape the
data must take — independent of any storage technology:

**1. The 8M stop_times rows must be organised by stop, sorted by time.**
R1 is the dominant cost. The data structure that serves R1 fastest is one
where all departures for a given stop_id are physically adjacent and sorted
by departure_time. Any approach that scatters these rows randomly and relies
on an index to assemble them adds overhead that compounds across 2 000 calls.

**2. The same stop_times data must also be organised by trip.**
R2 needs to walk a trip forward. This is the opposite grouping to R1.
The data either needs to be stored twice (once per stop, once per trip),
or one direction needs to be the primary store and the other needs an index
or secondary structure.

**3. Stops need spatial organisation.**
M1 is called before every query. A full scan of 30 000 stops is fast enough
in memory (< 5 ms), but if M1 is called 5 times for multi-origin seeding,
that cost multiplies. Stops need to be findable by proximity without scanning
the whole table.

**4. The real-time data is entirely separate.**
RT1, RT2, RT3 never touch the GTFS static store. They read from a memory map
that is rebuilt every 30 seconds from the GTFS-RT feed. Whatever we decide
about static storage has no bearing on the RT layer.

**5. Calendar resolution must happen before routing, not during.**
R4 and R5 produce a set of valid trip_ids for today. This set must be
pre-computed and held in memory for the entire routing query. Computing it
trip-by-trip inside R1 would add calendar logic to the innermost loop.

**6. Import must be atomic.**
I2 forces us to maintain a "currently live" dataset that RAPTOR reads, and
a "being imported" dataset that can be safely written without corrupting
in-progress queries. The swap between them must be instantaneous from
RAPTOR's perspective.

**7. Fares are genuinely uncertain until the spike is done.**
FA1 and FA2 are in the catalogue because they are planned, but the Swiss GTFS
fare data coverage is unknown. No architectural decisions should depend on
fares being present in GTFS until that is verified.

---

*Storage format and technology decisions to be made after this catalogue is
reviewed and agreed.*
