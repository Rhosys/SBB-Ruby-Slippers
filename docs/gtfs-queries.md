# GTFS Query Catalogue

Tracks every read query the app needs to make against GTFS data, grouped by
scenario. Used to drive the storage-strategy decision (Room vs raw SQLite vs
pre-processed binary) and to keep indices honest as requirements evolve.

**Temperature legend**
- 🔴 Hot — called in every RAPTOR round iteration (10s–100s of thousands of times per query)
- 🟡 Warm — called once or a few times per user-initiated query
- 🔵 Cold — called at import time, on app start, or very rarely at runtime

---

## 1. RAPTOR inner loop

These dominate the routing cost. Storage decisions live or die here.

| # | Query | Temperature | Notes |
|---|---|---|---|
| R1 | Given stop_id X and time T on day D, return all (trip_id, departure_time, stop_sequence) that depart X at or after T and operate on D | 🔴 | Called for every stop reached in every RAPTOR round. Needs `(stop_id, departure_time)` index on stop_times + service-day filter |
| R2 | Given trip_id T and stop_sequence S, return all subsequent stops with their arrival/departure times | 🔴 | Walk forward through a trip once it is boarded. Sequential scan within a trip; must be fast |
| R3 | Given stop_id X, return all other stops reachable by foot transfer (from transfers.txt or Haversine ≤ threshold) with their walk time | 🔴 | Called at the end of every RAPTOR round to propagate foot-transfer labels |
| R4 | Given service_id S, is it active on date D? (calendar.txt + calendar_dates.txt exceptions) | 🟡 | Pre-computed once per query day into a `Set<service_id>` and reused throughout all rounds |
| R5 | Given the active service_ids for day D, which trip_ids are valid? | 🟡 | Used to filter R1 results; can be a pre-computed bit-set or hash-set per day |

---

## 2. Multi-origin seeding

Run before RAPTOR starts to establish initial labels.

| # | Query | Temperature | Notes |
|---|---|---|---|
| M1 | Given (lat, lng) and walk radius R, return all stop_ids within range with walk time (at user's walking pace) | 🟡 | Called once per origin place. Spatial lookup — needs lat/lng index or an R-tree / grid bucket |
| M2 | Given a named place (e.g. "Zürich HB"), return all stop_ids that belong to it (including child stops of the parent station) | 🟡 | Handles the grouped-station case where one "station" is several platform stop_ids |
| M3 | Given a free-text string, return matching stops ordered by relevance | 🟡 | Autocomplete and destination resolution. Needs full-text or prefix index on stop_name |

---

## 3. Destination resolution

Mirror of M1–M3 but for the "to" endpoint.

| # | Query | Temperature | Notes |
|---|---|---|---|
| D1 | Given (lat, lng), return the nearest stop_ids within walk radius | 🟡 | Same spatial query as M1 |
| D2 | Given a stop_id, return its parent_station (if any) and all sibling stops | 🟡 | Needed when the user picks a station — route to any platform |

---

## 4. Journey display

Called once after RAPTOR returns a result, to render the connection cards.

| # | Query | Temperature | Notes |
|---|---|---|---|
| J1 | Given stop_id, return stop_name, lat, lng, platform hint | 🔵 | Display name and location for each leg's departure/arrival stop |
| J2 | Given trip_id, return route_id, route_short_name, route_type, route_color | 🔵 | Line badge ("IC 5", "S8") and colour |
| J3 | Given trip_id and stop_id, return the scheduled platform / track | 🔵 | "Platform 3" display in leg detail |
| J4 | Given trip_id, return the full ordered stop sequence with times | 🔵 | Leg detail expansion ("intermediate stops") |
| J5 | Given trip_id, return trip_headsign and direction_id | 🔵 | "Direction: Basel SBB" label |

---

## 5. Stationboard

Live departure board for a named station.

| # | Query | Temperature | Notes |
|---|---|---|---|
| S1 | Given stop_id(s) and time window [T, T+N min], return next departures ordered by departure time, with trip_id, route info, headsign, platform | 🟡 | Same index as R1 but returns a flat list rather than feeding RAPTOR |
| S2 | Given a station name, resolve to one or more stop_ids (incl. child platforms) | 🟡 | Same as M2 |

---

## 6. Real-time overlay (GTFS-RT)

Applied on top of static results. Data lives in memory, not in the GTFS DB.

| # | Query | Temperature | Notes |
|---|---|---|---|
| RT1 | Given trip_id + stop_id, return current arrival/departure delay (seconds) | 🔴 | Applied during RAPTOR and display to offset scheduled times. Memory map: `(trip_id, stop_sequence) → delay` |
| RT2 | Given trip_id, is the trip cancelled today? | 🟡 | Checked before boarding a trip in RAPTOR |
| RT3 | Given stop_id or route_id, are there active service alerts? | 🔵 | Display only — warning badge on connection card or stationboard row |
| RT4 | Given trip_id, return current vehicle position (lat, lng, bearing) | 🔵 | V3 scope. "Train is currently at Olten" |

---

## 7. Calendar edge cases

| # | Query | Temperature | Notes |
|---|---|---|---|
| C1 | Trips with stop_times > 24:00:00 — map to the correct calendar day | 🟡 | Swiss overnight services depart e.g. at 25:30 (= 01:30 next day). RAPTOR must normalise times before comparing |
| C2 | Trip T runs on day D via calendar_dates.txt ADDED exception (not in regular calendar) | 🟡 | calendar_dates overrides take precedence over calendar.txt |
| C3 | Trip T is REMOVED on day D via calendar_dates.txt exception | 🟡 | Must exclude from active service set even if calendar.txt says it runs |

---

## 8. Frequencies

| # | Query | Temperature | Notes |
|---|---|---|---|
| F1 | Does trip_id T have a frequencies.txt entry? If so, return headway_secs and window | 🟡 | Frequency-based trips (some city buses) need to be expanded into concrete departure times before feeding RAPTOR |

---

## 9. Accessibility

| # | Query | Temperature | Notes |
|---|---|---|---|
| A1 | Given stop_id, return wheelchair_boarding flag | 🔵 | Future accessibility filter |
| A2 | Given trip_id, return wheelchair_accessible and bikes_allowed flags | 🔵 | Future filter / display badge |

---

## 10. Fares (spike — see fares section of plan)

| # | Query | Temperature | Notes |
|---|---|---|---|
| FA1 | Given origin zone_id and destination zone_id, return fare_id and price (Fares v1) | 🔵 | Only if Swiss GTFS populates fare_rules.txt meaningfully |
| FA2 | Given a journey's leg sequence, return applicable fare_products (Fares v2) | 🔵 | Fares v2 schema; Swiss coverage TBD |

---

## 11. Import / sync

| # | Query | Temperature | Notes |
|---|---|---|---|
| I1 | What feed_version and feed_start/end_date is currently loaded? | 🔵 | Compared against latest published feed to decide whether to re-import |
| I2 | Are all mandatory GTFS files present (stops, routes, trips, stop_times, calendar or calendar_dates)? | 🔵 | Integrity check at import time |

---

## Storage decision checklist

When evaluating a storage option, verify it can handle:

- [ ] R1 at < 1 ms per call (determines end-to-end query latency)
- [ ] M1/D1 spatial lookup without a full table scan over 30k stops
- [ ] C1 midnight-crossing arithmetic without corrupting day boundaries
- [ ] RT1 overlay without touching the static DB at all (pure memory)
- [ ] F1 frequency expansion before RAPTOR, not during
- [ ] Feed swap (I1/I2) atomically — old data stays readable until new import commits
