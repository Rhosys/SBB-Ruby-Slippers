# GTFS Data & Query Catalogue

This document is the source of truth for every read query the app makes
against GTFS data. It is storage-agnostic: we describe *what* we need
before deciding *how* to store it. The storage decision lives at the bottom
and should be revisited whenever a new query is added.

---

## Part 1 — The data

What each GTFS file contains and its approximate size for the Swiss national feed.

| File | Contains | ~Row count | Notes |
|---|---|---|---|
| `stops.txt` | Stop id, name, lat/lng, parent_station, platform code, wheelchair flag | 30 000 | One row per physical stopping point |
| `routes.txt` | Route id, short name (e.g. "IC 5"), type (rail/bus/tram), colour | 2 000 | One row per line |
| `trips.txt` | Trip id → route id, service_id, headsign, direction | 500 000 | One row per individual service run |
| `stop_times.txt` | trip_id, stop_id, arrival_time, departure_time, stop_sequence, platform | 8 000 000 | The bulk of the data. Times can exceed 24:00:00 for overnight services |
| `calendar.txt` | service_id → Mon–Sun booleans + start/end date | 5 000 | Regular weekly patterns |
| `calendar_dates.txt` | service_id, date, exception type (add / remove) | 20 000 | Overrides: public holidays, special services |
| `transfers.txt` | from_stop_id, to_stop_id, min_transfer_time | 5 000 | Explicit interchange times between nearby stops |
| `frequencies.txt` | trip_id, start/end time, headway_secs | 1 000 | Frequency-based trips (some city buses) — no fixed times |
| `fare_attributes.txt` | fare_id, price, currency, transfers allowed | ? | Swiss coverage uncertain — see fares spike |
| `fare_rules.txt` | fare_id → route/zone/origin/destination rules | ? | Swiss coverage uncertain |
| `feed_info.txt` | feed_version, feed_start_date, feed_end_date | 1 | Used to detect when a newer feed is available |

**Key size fact:** `stop_times.txt` is ~8 million rows and is the only file
that genuinely strains a naive storage approach. Every other file is small
enough that access pattern matters more than raw size.

---

## Part 2 — The queries

Grouped by scenario. Each query is described in plain terms with no
reference to any storage technology.

### Temperature

- 🔴 **Hot** — called thousands of times per routing query (inside the RAPTOR loop)
- 🟡 **Warm** — called a handful of times per user action
- 🔵 **Cold** — called at startup, import time, or very rarely

---

### 2A. RAPTOR routing inner loop

RAPTOR works in rounds. In each round it scans every stop that was reached
in the previous round, looks for trips that can be boarded there, then walks
those trips forward to find newly reachable stops.

| # | Question | Temperature |
|---|---|---|
| R1 | "What trips depart stop X at or after time T on day D, ordered by departure time?" | 🔴 |
| R2 | "For trip T, what stops come after stop_sequence S, in order, with their arrival and departure times?" | 🔴 |
| R3 | "What stops can be reached from stop X by walking (either from transfers.txt or by proximity), and how long does each walk take?" | 🔴 |
| R4 | "Is service_id S running on date D?" (calendar + exceptions) | 🟡 computed once per day, result cached for entire query |
| R5 | "What trip_ids are valid today?" (derived from R4) | 🟡 computed once per query day |

**Critical constraint on R1:** this is called once per stop per RAPTOR round.
A typical query has 3–5 rounds over a few hundred reached stops, so R1 fires
roughly 1 000–2 000 times per user query. Each call must return in well under
1 ms or the total query takes seconds.

---

### 2B. Origin and destination resolution

Run once before RAPTOR starts, to turn a user-supplied place into a set of
seed stops with initial walk times.

| # | Question | Temperature |
|---|---|---|
| M1 | "What stops are within walking distance of (lat, lng)? Return stop_id and walk time (at the user's configured pace)." | 🟡 |
| M2 | "What stop_ids belong to station named X? Include all platform child-stops of the parent." | 🟡 |
| M3 | "What stops match the search string 'Zürich'? Return ordered by relevance." | 🟡 |
| M4 | "For multi-origin: given a list of saved places, run M1 for each and merge the resulting seed sets." | 🟡 |

---

### 2C. Journey display

Called after RAPTOR returns a result, to fill in names and colours for the UI.

| # | Question | Temperature |
|---|---|---|
| J1 | "What is the name, lat/lng, and platform label for stop_id X?" | 🔵 |
| J2 | "What is the short name, type, and colour for the route that trip T belongs to?" | 🔵 |
| J3 | "What is the scheduled platform for trip T at stop X?" | 🔵 |
| J4 | "What are all the intermediate stops for trip T between stop_sequence A and B, in order?" | 🔵 |
| J5 | "What is the headsign (destination display) for trip T?" | 🔵 |

---

### 2D. Stationboard

Live departure board — same data as RAPTOR but returned as a flat list
rather than fed into the algorithm.

| # | Question | Temperature |
|---|---|---|
| S1 | "What are the next N departures from stop_ids [X, Y, Z] between time T and T+M minutes, ordered by departure time?" | 🟡 |
| S2 | "For each departure from S1, what is the route name, headsign, and platform?" | 🔵 |

---

### 2E. Real-time overlay (GTFS-RT)

The real-time feed provides delay and cancellation data that sits *on top of*
the static schedule. These are never stored in the GTFS database — they live
in a separate in-memory structure that is refreshed every 30 seconds.

| # | Question | Temperature |
|---|---|---|
| RT1 | "What is the current delay (seconds) for trip T at stop_sequence S?" | 🔴 applied during RAPTOR and at display time |
| RT2 | "Is trip T cancelled right now?" | 🟡 |
| RT3 | "Are there service alerts for stop X or route R?" | 🔵 display badge only |

---

### 2F. Calendar edge cases

| # | Question | Temperature |
|---|---|---|
| C1 | "Trip T has a stop_time of 25:30:00 — what calendar date does that actually belong to?" | 🟡 normalise at import or query time |
| C2 | "Does calendar_dates.txt add or remove service_id S on date D, overriding calendar.txt?" | 🟡 merged into R4 result |

---

### 2G. Frequency-based trips

| # | Question | Temperature |
|---|---|---|
| F1 | "Does trip T use frequencies.txt? If so, what are the headway and operating window?" | 🟡 these trips must be expanded into concrete departure times before RAPTOR sees them |

---

### 2H. Feed management

| # | Question | Temperature |
|---|---|---|
| I1 | "What feed_version and date range is currently loaded?" | 🔵 |
| I2 | "Is a newer feed available from opentransportdata.swiss?" | 🔵 checked by a periodic WorkManager job |

---

### 2I. Fares (spike — answer depends on Swiss GTFS coverage)

| # | Question | Temperature |
|---|---|---|
| FA1 | "What fare applies for a journey from zone A to zone B?" | 🔵 |
| FA2 | "What is the CHF price for fare_id F, and how many transfers does it allow?" | 🔵 |

---

## Part 3 — Access patterns

Each query resolved to its fundamental data structure operation.

| # | Access pattern | Why it matters |
|---|---|---|
| R1 | **Sorted range scan** — given a key (stop_id), find rows where departure_time ≥ T, in order | The hot-path bottleneck. A B-tree on (stop_id, departure_time) in SQL can do this, but the overhead per call adds up at 2 000 calls/query |
| R2 | **Sequential forward scan** — given a key (trip_id, stop_sequence), read rows in order until end of trip | Essentially a sequential read once you are positioned. Fast in any row-oriented store |
| R3 | **Adjacency lookup** — given a node (stop_id), return its neighbours and edge weights | Classic graph traversal. In SQL: a join against transfers + a spatial query for proximity. In a graph DB: one hop |
| M1, D1 | **Spatial range query** — find all points within radius R of (lat, lng) | SQL has no spatial index by default. Needs an R-tree extension (SpatiaLite, SQLite R*Tree), a grid bucket, or pre-computed nearest-stop arrays |
| M3 | **Full-text prefix search** — find stop names matching a string | SQLite FTS5 handles this. ObjectBox has built-in string search |
| RT1 | **Point lookup in memory** — given (trip_id, stop_sequence), return delay | A Kotlin `HashMap` is sufficient. Never touches any DB |
| J1–J5 | **Point lookups by id** — given stop_id or trip_id, return a handful of fields | Any indexed store handles this trivially |
| FA1–FA2 | **Small table scan or point lookup** — fare tables are tiny | Irrelevant to storage decision |

---

## Part 4 — Storage candidates evaluated

### Option A: Room (SQLite via ORM)

Room maps GTFS files directly to `@Entity` classes. Queries written in `@Query` annotations.

| Pattern | Verdict | Detail |
|---|---|---|
| R1 sorted range scan | ⚠️ Adequate with care | Needs a composite index on `(stop_id, departure_time)` and the service-day filter pre-computed into a join or temp table. Each call has JNI + cursor overhead |
| R2 sequential scan | ✅ Good | Simple `WHERE trip_id = ? AND stop_sequence > ?` |
| R3 adjacency lookup | ⚠️ Awkward | Transfers table is fine; proximity walk-transfers need a spatial workaround |
| M1/D1 spatial | ❌ No native support | Must implement bounding-box pre-filter + Haversine post-filter in SQL, or load all stops into memory |
| M3 full-text | ✅ FTS5 available | Room supports `@Fts4`/FTS5 entities |
| RT1 | ✅ Not in DB | Memory map regardless of storage choice |
| Feed swap | ✅ Room migrations | Atomic schema migrations supported |

**Summary:** Works, but R1 hot-path performance is a known risk. Room adds a
layer of abstraction that makes hand-tuning indices harder.

---

### Option B: Raw SQLite (no ORM)

Same data as Option A, same indices, but queries written as raw SQL strings
via Android's `SQLiteDatabase` or the sqlite4java library.

| Pattern | Verdict | Detail |
|---|---|---|
| R1 sorted range scan | ✅ Tunable | Can write exactly the query RAPTOR needs; can use temp tables, CTEs, or prepared statements with no ORM overhead |
| R2 sequential scan | ✅ Good | Same as Room |
| R3 adjacency lookup | ⚠️ Same as Room | Transfers fine; proximity still awkward |
| M1/D1 spatial | ⚠️ Better | Can use SQLite R*Tree extension for real spatial indexing |
| M3 full-text | ✅ FTS5 | Available in raw SQLite |
| Feed swap | ✅ Atomic | WAL mode + rename |

**Summary:** Strictly better than Room for the hot path. Loses migrations
and code-gen convenience. Still carries per-call JNI overhead on R1.

---

### Option C: ObjectBox (embedded object graph)

ObjectBox stores Kotlin objects in its own binary format. Relationships are
first-class (ToMany, ToOne). No SQL.

| Pattern | Verdict | Detail |
|---|---|---|
| R1 sorted range scan | ⚠️ Unproven at scale | ObjectBox queries are fast for object retrieval, but 8M stop_times objects with time-range queries have not been benchmarked against the RAPTOR pattern |
| R2 sequential scan | ✅ Natural | Follow the `Trip → List<StopTime>` relationship in order |
| R3 adjacency lookup | ✅ Natural | `Stop.transfers` is a ToMany; one hop |
| M1/D1 spatial | ✅ Built-in | ObjectBox has a native geo-distance query |
| M3 full-text | ✅ Built-in | ObjectBox has string prefix and full-text query |
| RT1 | ✅ Not in DB | Memory map |
| Feed swap | ✅ | ObjectBox handles schema evolution |

**Summary:** Best fit for the warm/cold queries. The spatial and graph
traversal support is genuinely better than SQLite. The R1 hot-path risk is
unknown — needs a benchmark with the actual Swiss feed size before
committing.

---

### Option D: Pre-processed CSR binary (Compressed Sparse Row)

At import time, build an in-memory adjacency structure from the DB and
serialise it to a binary file. At runtime, memory-map it — RAPTOR reads
directly from the mapped arrays, zero DB calls.

Structure:
- **Stop index**: for each stop_id (integer), a pointer into the departure array
- **Departure array**: entries sorted by (stop_id, departure_time), each entry is
  `(departure_time, trip_id, stop_sequence)` — packed integers
- **Trip array**: for each trip_id, a pointer into the stop-times array
- **Stop-times array**: `(stop_id, arrival_time, departure_time)` entries in
  stop_sequence order

| Pattern | Verdict | Detail |
|---|---|---|
| R1 sorted range scan | ✅ Best possible | Binary search on the departure array for the stop's range, then linear scan forward — no syscall, no JNI, pure memory reads |
| R2 sequential scan | ✅ Best possible | Linear scan through the trip's stop-time slice |
| R3 adjacency lookup | ✅ Good | Pre-computed transfer array per stop |
| M1/D1 spatial | ❌ Not in CSR | Handled by whichever DB stores stops |
| M3 full-text | ❌ Not in CSR | Handled by whichever DB stores stops |
| RT1 | ✅ Not in CSR | Separate memory map |
| Feed swap | ⚠️ File rename | Must atomically swap both the DB and the CSR file |

**Summary:** Optimal for RAPTOR, unfit for anything else. Cannot stand alone.

---

## Part 5 — Recommended architecture

No single option wins across all access patterns. The right answer is a
**two-layer split** driven by the access pattern classification:

```
┌─────────────────────────────────────────────────────────────────┐
│  ObjectBox graph store                                          │
│  stops, routes, trips, calendar, transfers, fares, search       │
│  → warm/cold queries (J1–J5, M1–M4, S1–S2, FA1–FA2, I1–I2)    │
└────────────────────┬────────────────────────────────────────────┘
                     │ built from, at each feed import
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│  CSR binary (memory-mapped file)                                │
│  stop → sorted departures, trip → ordered stop-times           │
│  → hot-path queries only (R1, R2, R3)                          │
└─────────────────────────────────────────────────────────────────┘
                     +
┌─────────────────────────────────────────────────────────────────┐
│  In-memory HashMap (rebuilt every 30s from GTFS-RT)            │
│  (trip_id, stop_sequence) → delay_seconds + cancelled flag     │
│  → RT1, RT2                                                     │
└─────────────────────────────────────────────────────────────────┘
```

ObjectBox is the source of truth and is used for everything except the
RAPTOR inner loop. The CSR file is a derived index — if it is ever lost or
corrupted, it can be fully rebuilt from ObjectBox. The RT map is ephemeral.

**Open question before confirming this direction:** ObjectBox's R1
performance with 8M objects needs a benchmark. If it proves fast enough
(< 0.5 ms per call on a mid-range device), the CSR layer may be
unnecessary, which simplifies the architecture significantly.
