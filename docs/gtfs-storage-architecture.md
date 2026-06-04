# GTFS Storage & Routing Architecture (V2)

Decision record. Built on the query evidence in [`gtfs-queries.md`](./gtfs-queries.md).

The query catalogue asked: *what does the app need to read, and what does that
imply about data structure?* This document answers the follow-on: *given those
queries, what storage and what algorithm do we actually build?*

---

## 1. RAPTOR is not a graph search — so we do not need a graph database

This is the correction that resolves most of the earlier confusion.

The intuition "transit routing = graph search, therefore graph DB" is wrong for
the algorithm we are using. The graph approaches to transit routing are:

- **Time-expanded graph + Dijkstra/A\***: every `(stop, time)` event is a node,
  edges connect them. Millions of nodes; routing pointer-chases through them.
  *This* is what would justify a graph database.
- **Time-dependent graph + Dijkstra**: fewer nodes, time-dependent edge weights.

**RAPTOR is deliberately none of these.** It was invented because graph search
over a timetable is slow. RAPTOR is round-based dynamic programming directly over
the timetable:

- Round *k* = "the best arrival time at every stop using at most *k* transfers."
- Each round scans the routes serving already-reached stops, rides the earliest
  boardable trip on each, and relaxes arrival times at downstream stops.
- There is **no graph, no node expansion, no edge traversal.** It is array
  scanning in rounds.

Consequence: a graph database optimises pointer-chasing between nodes — exactly
the operation RAPTOR never performs. RAPTOR wants flat, contiguous arrays it can
sweep. So the hot path is not served by *any* database. See §3.

---

## 2. Two paths with completely different needs

The queries split cleanly into two groups that share almost nothing.

| | **Hot path (routing)** | **Cold path (display & search)** |
|---|---|---|
| Queries | R1, R2, R3, R4/C2 | M1, M2, M3, J1–J5, S1, S2 |
| Frequency | ~2 000 reads per user query | a handful per screen |
| Latency budget | microseconds per read | tens of ms is fine |
| Data | 8M stop_times → pre-built arrays | ~30K stops, names, routes |
| Access shape | sequential array sweeps | keyed/point lookups, text search |
| Served by | **memory-mapped CSR binary (§3)** | **small indexed store (§5)** |

The hot path is the only thing with a hard performance constraint, and it is the
only thing that drives an unusual storage choice. Everything in the cold column is
served by any ordinary indexed store.

---

## 3. The hot path is a memory-mapped CSR binary, not a database

RAPTOR runs on three flat arrays, built once and held in memory:

```
routeStops[route]  = [stopA, stopB, stopC, ...]      // the ordered stop pattern
stopTimes[route]   = int[trip_index][stop_pos]       // arrival/departure seconds
stopRoutes[stop]   = [routes passing through stop]    // reverse index
```

These are **CSR (Compressed Sparse Row)** layouts: one big values array plus an
offsets array that says where each row begins. No objects, no pointers — just
`int[]`. Both R1 ("what can I board at stop X") and R2 ("where does this trip go
from here") are answered by indexing into arrays you are *already positioned in*.
R2 in particular is not a lookup at all — it is `routeStops[route][pos++]`.

### The cost is building these arrays, not holding them

Footprint is phone-sized (rough estimate, needs a benchmark):

| Array | Size |
|---|---|
| stop_times (arrival+departure, int32) | 8M × 8B ≈ 64 MB |
| route stop-patterns | ~4 MB |
| stop→routes reverse index | ~10–20 MB |
| stops / calendar / names | ~10 MB |
| **Total** | **~80–150 MB** |

Holding ~150 MB is fine on a 4–8 GB phone. The expensive part is *parsing 8M CSV
rows and assembling the arrays* — estimated 10–60 s on-device. That cost must not
land on every app start.

### The binary *is* the in-memory structure

A flat `int[]` written to disk is byte-for-byte the in-memory structure. So app
start does not "load and parse" — it `mmap`s the file:

```
APP START (every launch):
    mmap("live.csr")          // milliseconds; pages fault in lazily on access
```

The OS maps the file into the address space instantly and pages data in on demand
as RAPTOR touches it. You never explicitly read 150 MB at startup; only the pages
a given query touches fault in (fast on modern UFS flash), and the rest may never
load. This is precisely why a `mmap`-able CSR binary beats SQLite/ObjectBox for
the hot path: a database would force either re-querying (slow) or
deserialize-into-objects-at-startup (the cost we are avoiding).

---

## 4. Build the CSR centrally; serve it from a CDN

The parse-and-build in §3 is *deterministic* — every client would compute the
exact same bytes from the same feed. Doing it on every device, every feed cycle,
is wasted work. Build it once, server-side, and publish the ready-to-`mmap`
binary to a CDN.

```
SERVER PIPELINE (scheduled, ~weekly, matches GTFS publish cadence):
    download GTFS zip from opentransportdata.swiss
    parse + expand frequencies + merge calendar + build CSR arrays
    write little-endian binary, compress (zstd/gzip)
    upload to S3 → CloudFront, update manifest

CLIENT (background check, ~daily):
    GET cdn/sbb/csr/{formatVersion}/latest.json
        → { feedVersion, url, sha256, sizeBytes, validUntil }
    if feedVersion newer than on-device:
        download .bin, verify sha256, decompress to disk, atomic-swap into "live"
```

### What this buys us

- **The client ships no GTFS parser.** All CSV parsing, frequency expansion
  (F1), calendar merge (R4/C2), and time normalisation (C1) happen *once* in the
  server pipeline. The client is reduced to: download → verify → `mmap` → RAPTOR.
  Smaller APK, far less client code, fewer device-specific edge cases.
- **One binary format across wire, disk, and memory.** Build little-endian (all
  Android is LE). Compress for transfer only; `mmap` the decompressed file.
- **Versioning is a CDN path.** Two independent axes:
  - `formatVersion` — the binary layout, compiled into the app, bumped only when
    an app release changes the struct. Namespacing the CDN path by it means old
    app versions keep receiving binaries in *their* format — no skew.
  - `feedVersion` — the GTFS feed date, changes ~weekly.
- **Integrity** via `sha256` in the manifest (served over HTTPS from our CDN);
  manifest signing is a possible later hardening.

### Honest tradeoffs

- **This reintroduces a backend.** V1 was explicitly "no backend of ours; client
  hits `transport.opendata.ch` directly." This is a *static-content build
  pipeline* (scheduled job → S3 → CloudFront), not a request-serving API — much
  lighter — and it fits the existing Rhosys AWS stack (the deploy pipeline already
  uses AWS KMS/SES). But it is a new thing we own and operate.
- **The v1 REST path becomes the graceful fallback**, not dead code. Fresh
  install offline, CDN unreachable, or no CSR present yet → fall back to
  `ApiTransportRepository` against `transport.opendata.ch` for basic queries.
  Degraded (no multi-origin, no offline routing) but functional.
- **Disk budget**: ~100–150 MB resident, transiently ~2× during atomic swap.
  Acceptable on most devices; add a low-storage guard.

---

## 5. The cold path is a small ordinary indexed store

Everything in the cold column of §2 is served by a conventional store over ~30K
stops and ~2K routes — small enough that the technology barely matters:

- **M1** (stops near a lat/lng): a spatial index or even a full in-memory scan of
  30K stops (~5 ms). Cheap either way.
- **M2** (station → child platforms): a `parent_station` index.
- **M3** (autocomplete): a prefix / full-text index on stop names, ranked by an
  importance signal pre-computed at import (count of stop_times referencing the
  stop = how busy it is).
- **J1–J5, S1, S2** (names, line badges, headsigns, platforms): point lookups by
  id.

This is a natural fit for **SQLite (via Room)** — already in the app — with an FTS
table for M3. It can ship inside the CSR bundle from the CDN (same build, same
atomic swap) or be carried as a sidecar file. No graph DB, no ObjectBox required
here; the earlier ObjectBox lean was solving a hot-path problem that the CSR
binary already solves better.

---

## 6. Real-time stays client-side and live

RT1 (delays), RT2 (cancellations), RT3 (alerts) never touch the static store and
cannot be CDN-baked the way the static feed is — GTFS-RT changes every ~30 s.

- Client fetches the GTFS-RT protobuf feed (TripUpdates, Alerts) on a ~30 s timer.
- Parses it into an in-memory overlay: `Map<(trip_id, stop_sequence), delaySeconds>`,
  a cancellation set, and a small alerts list.
- RAPTOR and the journey display read scheduled times from the CSR and apply the
  overlay on top. The overlay is rebuilt from scratch each cycle; nothing persists.

Whatever we decide about static storage has zero bearing on this layer.

---

## 7. Fares — still a spike, unchanged

`fare_attributes.txt` / `fare_rules.txt` ride along in the same GTFS feed, so if
they are populated they get built into the cold store like any other table — no
new architecture. The open question remains *coverage*: Swiss pricing (national
direct tariff, ~20 regional zone tariffs, Half-Fare/GA) is unlikely to be fully
expressed in standard GTFS fare files. **Required spike before committing:**
download the Swiss feed and inspect what `fare_rules.txt` actually contains. No
architectural decision should depend on fares being present until then.

---

## 8. Summary of decisions

1. **Algorithm**: RAPTOR (round-based, not graph search) → no graph database.
2. **Hot path**: pre-built CSR `int[]` arrays, `mmap`'d from a binary file. No DB
   on the routing path. App start is a `mmap`, not a parse.
3. **Build location**: server-side pipeline builds the CSR once; clients download
   it from a CDN. The client ships **no GTFS parser**.
4. **Versioning**: CDN path namespaced by `formatVersion` (app-pinned) ×
   `feedVersion` (weekly); `sha256` integrity; atomic on-device swap.
5. **Cold path**: ordinary SQLite/Room + FTS over ~30K stops for search and
   display lookups.
6. **Real-time**: client-side in-memory overlay from the GTFS-RT feed, rebuilt
   every ~30 s, applied on top of CSR scheduled times.
7. **Fallback**: the v1 `transport.opendata.ch` REST path is retained as the
   offline / no-CSR / CDN-unreachable degraded mode.
8. **Fares**: blocked on a feed-inspection spike before any commitment.

### Open spikes / measurements before build

- [ ] Benchmark CSR build time on a representative device (validates the
      "build server-side" payoff and the import job design).
- [ ] Measure CSR size on disk and compressed-over-wire (validates CDN transfer
      and disk-budget assumptions).
- [ ] Inspect Swiss `fare_rules.txt` / `fare_attributes.txt` coverage (FA1/FA2).
- [ ] Confirm `mmap` + lazy page-in behaviour and cold-start latency on Android
      with a ~150 MB mapped file.

---

*Supersedes the earlier ObjectBox + CSR two-layer lean: the CSR binary alone
serves the hot path, and the cold path needs only an ordinary indexed store.*
