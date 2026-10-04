# SBB Ruby Slippers — Claude Code guidelines

Rules and conventions for every session.

---

## CI/CD — never use `sudo` to run application code

**Rule:** `sudo` is only permitted for infrastructure setup (package installation,
service start, writing root-owned config). Never use it to invoke `gradle`, `npm`,
`cargo`, `python`, or any test runner.

**Why it breaks:** CI runners install toolchains under the non-root user's home;
`sudo` switches to root which has a different `$HOME`, `$PATH`, and no access to
the user's Gradle or npm caches.

**Correct pattern — grant access instead of escalating:**

| Problem | Wrong | Right |
|:--------|:------|:------|
| Process needs a device file | `sudo myapp` | `sudo chmod a+rw /dev/ttyX` in setup; run `myapp` as user |
| Process needs a privileged port | `sudo server` | `sudo setcap cap_net_bind_service+ep ./server` in setup; run as user |
| Process needs group membership | `sudo myapp` | `sudo usermod -a -G group $USER` + `newgrp group` |

---

## Local development — run on the emulator

```bash
npm run start            # debug variant: boots emulator, builds, installs, launches, streams crash logs
npm run start:release    # release variant: same loop on the R8/ProGuard build — catches stripping crashes
```

`scripts/dev.sh` is the single orchestrator: runs `setup.sh` if the SDK is missing,
creates the shared `WorkspaceAVD` (android-35, pixel_7) if absent, boots it, then
gradle install + launch. All workspace Android apps share one `WorkspaceAVD` and
one system image — do not give this app its own AVD name.

Emulator-only helpers: `npm run setup`, `npm run emulator:create|start|delete`.
KVM is required (Linux). Troubleshooting lives in `scripts/setup.sh`.

---

## Repository layout

```
app/src/main/java/ch/rhosys/sbb/
  SbbRubySlippersApp.kt                ← @HiltAndroidApp + PostHog + HiltWorkerFactory
  MainActivity.kt                      ← Compose entry point, bottom nav scaffold
  data/
    local/
      calendar/CalendarRepository.kt   ← reads CalendarContract for 7-day events
      photo/PlacePhotoStore.kt         ← copies picked place photos into filesDir (Auto Backup-safe)
      db/
        AppDatabase.kt                 ← Room: places, saved_routes, recurring_routes, trip_history
        dao/{Place,SavedRoute,RecurringRoute,TripHistory}Dao.kt
        entity/                        ← Room entities + toDomain() / toEntity() mappers
      preferences/UserPreferencesRepository.kt  ← DataStore keys + typed flows
      repository/
        RoomPlaceRepository.kt         ← implements domain/PlaceRepository
        RoomRouteRepository.kt         ← implements domain/RouteRepository
    remote/
      ApiTransportRepository.kt        ← implements domain/TransportRepository; resolves GPS → name
      TransportApi.kt                  ← Retrofit interface → transport.opendata.ch
      dto/TransportDtos.kt             ← @Serializable DTOs (never passed to UI)
  di/
    DatabaseModule.kt                  ← provides AppDatabase, DAOs, binds Place/RouteRepository
    NetworkModule.kt                   ← provides OkHttp, Json, Retrofit, TransportApi, binds ApiTransportRepository
    PreferencesModule.kt               ← provides DataStore<Preferences>
  domain/
    PlaceRepository.kt                 ← interface: getPlaces/Home, upsert, setHome
    RouteRepository.kt                 ← interface: saved/recurring CRUD + calendar upsert/prune
    TransportRepository.kt             ← interface: getConnections(SearchEndpoint, SearchEndpoint)
    model/
      Connection.kt / Leg.kt / Stop.kt ← journey domain types (never API DTOs)
      Place.kt                         ← has Haversine distanceMetersTo()
      SavedRoute.kt / RecurringRoute.kt / SearchEndpoint.kt
  ui/
    error/StartupErrorScreen.kt
    home/{HomeScreen,HomeViewModel}.kt ← scorer + pull-over sheet (active journey above next departure) + tile grid
    places/{PlacesScreen (fun HomeEditScreen),PlacesViewModel (class HomeEditViewModel)}.kt ← place management: add/delete/resize/move tiles on the grid
    journey/
      JourneyStateHolder.kt            ← @Singleton: locked-in connection + from/to
      JourneysScreen.kt                ← three-tab screen: Active / Past / Planned
      JourneysViewModel.kt             ← 30 s polling; switch-prompt when saved ≥ threshold
      TripReviewScreen.kt              ← full leg breakdown; "Start journey" locks in connection
      TripReviewViewModel.kt
    navigation/{Screen,AppNavHost}.kt
    onboarding/{OnboardingScreen,OnboardingViewModel}.kt
    search/{ConnectionSearchScreen,ConnectionSearchViewModel}.kt ← smart suggestions + transport API autocomplete
    common/StationSearchPopup.kt       ← StationSearchField: tap opens near-full-screen search popup (Home + Search screens)
    map/StopMapPicker.kt               ← MapLibre "Choose on map" stop picker (OpenFreeMap liberty vector tiles), limited to Switzerland
    map/StopMapStyle.kt                ← stop symbol layers (dot + name as one symbol; zoom-tiered trains → trams → buses) + user location
    map/MapLayerGroup.kt               ← user-toggleable base-map layer groups (roads, labels, buildings, POIs, …), persisted in DataStore
    map/StopMapViewModel.kt            ← stops from local GTFS (GtfsRoute.mode) or, without a feed, the live API
    settings/{SettingsScreen,SettingsViewModel}.kt
    fares/FaresTeaserScreen.kt         ← placeholder; wired once OJP Fare token available
    theme/Theme.kt
    widget/DepartureWidget.kt          ← Glance placeholder (no real data yet)
  wear/
    PhoneWearDataPusher.kt             ← pushes active journey + home-screen places to the watch (Data Layer)
    WearRequestService.kt              ← answers watch requests (/sbb/connections, /sbb/save-journey)
    WearConnectionsProvider.kt         ← next 3 connections from current location to a place; save = startJourney
    WearProtocol.kt                    ← phone↔watch JSON types — keep identical to wear/…/WearProtocol.kt
  worker/
    CalendarSyncWorker.kt              ← @HiltWorker: syncs calendar events → saved routes
    GtfsImportWorker.kt                ← @HiltWorker: daily GTFS check (ETag + URL tracking; auto-detects Fahrplanwechsel)
    GtfsRtRefreshWorker.kt             ← @HiltWorker: 15 min RT delays feed (skips if no token)
app/src/main/res/xml/departure_widget_info.xml
wear/src/main/java/ch/rhosys/sbb/wear/   ← Wear OS app (same applicationId + signing key as :app, required by the Data Layer)
  tile/PlacesTileService.kt            ← "Go to" tile: home-screen places; tap → app on that place's connections
  WearApp.kt / JourneyScreen.kt        ← home: active journey + places list
  ConnectionsScreen.kt / ConnectionsViewModel.kt ← next 3 connections + "Save journey"
  PhoneClient.kt                       ← Data Layer reads + MessageClient.sendRequest to the phone
  WatchLocation.kt                     ← watch's own fix (phone location is the fallback)
deployment/
  android-upload-signing.json  ← PLACEHOLDER — must be replaced before release
  deploy-play-store.ts         ← Play Store upload script
  notify-deploy.ts             ← SES deploy notification
.github/workflows/build.yml    ← GitHub CI: compile / lint / test / debug-apk
.gitlab-ci.yml                 ← GitLab CI: validate + signed-AAB release pipeline
```

## Tile interaction model

Tiles occupy a 10-column-wide grid of square cells (`ui/common/PlaceGrid.kt`); each
`Place` stores its own `gridX/gridY/gridWidth/gridHeight`, defaulting to 2x2 for new
places. HomeScreen only *reads* that layout — moving and resizing happens on the
Places (edit) screen.

### HomeScreen
- **Tap** a place tile → navigates to ConnectionSearchScreen with from = current
  location, to = that place.
- **Drag** from one tile to another → animated flowing arrow (dashes animate
  source → target, arrowhead at tip); on release navigates to ConnectionSearchScreen
  with from/to pre-filled. Source tile highlights in primary, target in secondary.

### Places screen (`ui/places/PlacesScreen.kt`, `fun HomeEditScreen`)
- **Tap** a tile → opens the edit dialog (label / photo / delete).
- **Drag a tile's center** → moves it to a new grid position.
- **Drag a tile's corner handle** → resizes it (gridWidth/gridHeight).
- Both drags reject a drop that would overlap another tile — the tile outlines red
  while the candidate position/size is invalid and snaps back on release.
- Dragging a tile's center onto the trash zone (appears at top while dragging) deletes it.

## Connection ranking

One rule decides which connections are shown, for both the local router and the API:
`domain/model/JourneyCriteria.kt`. A connection is dropped only when another one leaves no
earlier, arrives no later (after expected delay), has no more changes and no less transfer
slack (capped at 5 min) — and is strictly better on one of those. The local router
(`RoutingEngine`) is a range RAPTOR that returns every such connection in a 60 min window,
in two passes (tight changes, then changes with 5 min spare). `mergeConnections` applies the
same rule when pages are merged.

Paging (scrolling past either end of the list) is bounded by count, not a time window: from
the first/last shown departure outward up to 24 h, at most 10 connections, nearest the edge
first (`LocalTransportRepository.pageConnections`; via the API, `time` + `isArrivalTime` +
`limit`). Only the first search uses the 60 min window.

## Known gaps (v2)

- **Expected delay in local routing**: every transfer is planned against the incoming leg's
  expected arrival (`ExpectedDelayProvider`), but only `ExpectedDelayProvider.NONE` exists —
  GtfsRtStore is keyed by GTFS trip_id strings the compact network drops. API results use
  their real-time arrival delay (`Leg.Transit.expectedDelayMinutes` defaults to it).

- **RT per-leg delays**: GtfsRtStore wired into JourneysViewModel for banner alerts;
  per-leg delay overlay (red "+Xmin" on individual stops) requires stationId on Stop
  objects from local GTFS routing (already set) and from the remote API (TODO).
- **Widget geofence**: DepartureWidget reads from JourneyStateHolder; geofence-driven
  auto-clear is a v2 enhancement.
- **Wear OS**: the "Go to" tile and active-journey screen exist; saving a journey from the
  watch while the phone app is backgrounded keeps the journey but can't start the progress
  notification's foreground service (Android 12+ background-start limit).
- **Fares**, **Sector recommendations**, **Journey sharing** — v2.
- **Android Auto Backup**: already wired up (`AndroidManifest.xml` → `backup_rules.xml` /
  `data_extraction_rules.xml`, excluding only the GTFS cache), covering the Room DB
  (places, saved/recurring routes, trip history) and DataStore preferences. Room uses
  `JournalMode.TRUNCATE` (`di/DatabaseModule.kt`) specifically so Auto Backup's plain
  file copy of the `.db` never misses writes still sitting in a WAL sidecar file. Place
  photos are copied into `filesDir/place_photos/` at pick time (`PlacePhotoStore`) since
  the photo picker's own `content://` Uri isn't a file backup can capture and stops
  resolving after a restore anyway.

## Data source

The app fetches directly from `https://transport.opendata.ch/` — a public,
no-auth Swiss transport open-data API. Main endpoints:
- `GET v1/connections?from=&to=` — journey planner
- `GET v1/stationboard?station=` — live departures
- `GET v1/locations?query=` — station/stop search

Rate limit: ~1 000 req/day, 3 route queries/min (per IP). Sufficient for a client
app. Live delays/cancellations come from `opentransportdata.swiss`'s **GTFS-RT**
feed instead (`GtfsRtRefreshWorker`) — needs a free API token (see `todo.md`, Todo
5). OJP is not used for routing or real-time data in this app; the only reason to
ever touch it is the OJP Fare endpoint, for a not-yet-built Fares feature.
