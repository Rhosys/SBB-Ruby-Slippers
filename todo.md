# SBB Ruby Slippers — Todo

## Infrastructure Todos (require out-of-repo work)

These cannot be completed from within this repository. They require access to
`_rhosys-apps-infra`, the `_tools` repo, AWS, GCP Console, and Google Play Console.

### 🔲 1. Generate the upload keystore
In the `_tools` repo:
```bash
generate-android-keystore --alias sbb > deployment/android-upload-signing.json
```
(RSA-4096, PKCS12, 30-year validity, password encrypted via AWS KMS
`alias/deployment-encryption-key` in `eu-west-1`.)
Commit the resulting JSON to this repo, replacing the current placeholder.

### 🔲 2. GCP infra — add WIF binding for this project (`_rhosys-apps-infra/gcp/main.tf`)
Add a `google_service_account_iam_member` resource to bind the WIF
`principalSet` for this GitLab project path to the Play Store service account:

```hcl
resource "google_service_account_iam_member" "sbb_ruby_slippers_gitlab_wif" {
  service_account_id = google_service_account.gitlab_play_store.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/projects/454629444494/locations/global/workloadIdentityPools/gitlab-oidc/providers/gitlab-com/attribute.project_path/rhosys/rapid/sbb-ruby-slippers"
}
```
**Note:** CEL conditions and wildcards do NOT work for SA impersonation — this
explicit per-app binding is required.

### 🔲 3. Play Console — create the app
1. Create app with package name `ch.rhosys.sbb` in Google Play Console.
2. Upload the first signed AAB manually (required before the API can be used).
3. Grant `gitlab-play-store@rhosys-apps.iam.gserviceaccount.com` the
   **Release Manager** role on this app (Play Console → Setup → API access).

### 🔲 4. GitLab mirror — create the GitLab project
Create a GitLab project mirroring this GitHub repo so the signed-release
deploy pipeline runs there. Set the project variable:
- `AWS_ACCOUNT_ID` = `REDACTED`

(AWS infra reuses existing `GitLabRunnerRole` + `alias/deployment-encryption-key` —
no AWS changes needed beyond the keystore in Todo 1.)

### 🔲 5. Register at opentransportdata.swiss and wire the API token
Free registration at `opentransportdata.swiss` → `api-manager.opentransportdata.swiss`.
Two things unlock immediately with the token:

- **GTFS-RT** — `GtfsRtRefreshWorker` already fetches from `opentransportdata.swiss`
  but uses an empty bearer token. Wire the token through a new
  `stringPreferencesKey("opentransport_api_token")` in `UserPreferencesRepository`,
  expose it as a `Flow<String>`, and pass it into the worker via `WorkManager`
  `inputData` when scheduling. Add a token-entry field to `SettingsScreen`.
- **OJP 2.0** — `GET /ojp` XML journey planner with 50 req/min free tier.
  The beta **OJP Fare** endpoint returns price information per connection — first
  step toward fares in `TripReviewScreen`.

Add the token as a secret to GitHub Actions (`OPENTRANSPORT_API_TOKEN`) and to the
GitLab project variable of the same name once the mirror exists (Todo 4).

### 🔲 6. SwissPass / SwissID OAuth 2.0 login
Register with SBB's developer portal and implement "Login with SwissPass" on the
onboarding screen. Uses standard OAuth 2.0 RFC 8252 + PKCE — no SDK required
(the iOS SDK is public; no Android SDK published, but raw OIDC endpoints work fine).

What it unlocks for the user:
- Read subscription tier (GA, Half-Fare, none) → `UserPreferencesRepository` →
  apply discount to displayed fares from OJP Fare API.
- Single sign-on across SBB partner services.

Steps:
1. Register app at `developer.sbb.ch` — get client ID + OIDC metadata URL.
2. Add `net.openid:appauth` (AppAuth Android) to `libs.versions.toml`.
3. Add `SwissPassAuthRepository` (new) that wraps the AppAuth flow.
4. Add "Continue with SwissPass" button to `OnboardingScreen` (optional step).
5. Store token/tier in DataStore; expose `subscriptionTier: Flow<SubscriptionTier>` from
   `UserPreferencesRepository`.

### 🔲 7. Contact SBB partner team — B2P API access + deep link spec
Two things only unlock through a formal contact with SBB:

**B2P API** (`developer.sbb.ch/apis/b2p`) — requires SBB approval after registering.
Gives live supersaver pricing, seat reservations, and pre-booking with exclusive holds.
Needed before `TripReviewScreen` can offer in-app ticket purchase.
Contact: `opendata@sbb.ch` with a brief description of the app.

**Deep link spec** — `sbb://` URI scheme exists in SBB Mobile but is not publicly
documented. Request the spec to allow handing off to SBB Mobile from `TripReviewScreen`
("Buy in SBB Mobile" fallback when B2P is not yet wired up).

### 🔲 8. (Optional) Branded launcher icon
Replace the placeholder launcher icon with a custom SBB Ruby Slippers icon in
`app/src/main/res/drawable/ic_launcher_*.xml` and `mipmap-anydpi-v26/`.

### 🔲 9. (Optional) PostHog project
Create a dedicated PostHog project for SBB Ruby Slippers at `live.rhosys.ch`
and replace the `phc_D195...` key in `SbbRubySlippersApp.kt` with the new key.
Currently reuses the Lyra (Kinetic-Jewelry) project key.

---

## Backend Service Todos

The Android app currently calls two external services directly:

| Service | Auth | Rate limit | Used for |
|:--------|:-----|:-----------|:---------|
| `transport.opendata.ch` | None | ~1 000 req/day per IP | Connections, stationboard, location search |
| `opentransportdata.swiss` | Free API token | Per-token quota | GTFS static ZIP, GTFS-RT protobuf |

A Rhosys-owned backend (`api.rhosys.ch/sbb/v1/`) should proxy both.  Benefits:
- **Token stays server-side** — the opentransportdata.swiss key never ships in the APK.
- **Shared rate-limit pool** — all client IPs share one upstream budget; aggressive server-side caching avoids burning it.
- **Switchable upstream** — if `transport.opendata.ch` is replaced or rate-limited, the Android app base URL (`NetworkModule.BASE_URL`) is changed in one place.

Once the backend exists, change `NetworkModule.kt`:
```kotlin
// before
private const val BASE_URL = "https://transport.opendata.ch/"
// after
private const val BASE_URL = "https://api.rhosys.ch/sbb/v1/"
```

And update the two worker constants:
```kotlin
// GtfsImportWorker
private const val GTFS_FEED_URL = "https://api.rhosys.ch/sbb/v1/gtfs/static.zip"

// GtfsRtRefreshWorker
private const val RT_FEED_URL = "https://api.rhosys.ch/sbb/v1/gtfs/realtime.pb"
```

---

### Endpoint contract

All endpoints return JSON except the GTFS feeds (binary/ZIP).  The Android app
uses Retrofit + kotlinx-serialization; field names must match the DTOs in
`data/remote/dto/TransportDtos.kt` exactly.

---

#### `GET /v1/connections`

Proxy to `transport.opendata.ch/v1/connections`.

| Parameter | Type | Default | Notes |
|:----------|:-----|:--------|:------|
| `from` | string | — | Station name or GTFS `stop_id` |
| `to` | string | — | Station name or GTFS `stop_id` |
| `limit` | int | 4 | Max connections to return |

**Response** (`ConnectionsResponseDto`):
```json
{
  "connections": [
    {
      "from": {
        "station": { "id": "8503000", "name": "Zürich HB",
                     "coordinate": { "type": "WGS84", "x": 8.5402, "y": 47.3782 } },
        "departure": "2026-06-04T08:00:00+02:00",
        "delay": 0,
        "platform": "3"
      },
      "to": {
        "station": { "id": "8507000", "name": "Bern",
                     "coordinate": { "type": "WGS84", "x": 7.4395, "y": 46.9490 } },
        "arrival": "2026-06-04T08:57:00+02:00",
        "delay": 0,
        "platform": "7"
      },
      "duration": "00d00:57:00",
      "transfers": 0,
      "products": ["IC"],
      "sections": [
        {
          "departure": {
            "station": { "id": "8503000", "name": "Zürich HB" },
            "departure": "2026-06-04T08:00:00+02:00",
            "delay": 0,
            "platform": "3"
          },
          "arrival": {
            "station": { "id": "8507000", "name": "Bern" },
            "arrival": "2026-06-04T08:57:00+02:00",
            "delay": 0,
            "platform": "7"
          },
          "journey": {
            "name": "IC 1",
            "category": "IC",
            "number": "1",
            "operator": "SBB",
            "to": "Genève-Aéroport"
          },
          "walk": null
        }
      ]
    }
  ],
  "from": { "id": "8503000", "name": "Zürich HB" },
  "to":   { "id": "8507000", "name": "Bern" }
}
```

Walk sections have `"journey": null` and `"walk": { "duration": 3 }` (minutes).

**Server-side cache:** 60 s (live delays can change every minute).

---

#### `GET /v1/stationboard`

Proxy to `transport.opendata.ch/v1/stationboard`.

| Parameter | Type | Default |
|:----------|:-----|:--------|
| `station` | string | — |
| `limit` | int | 10 |

**Response** (`StationboardResponseDto`):
```json
{
  "station": { "id": "8503000", "name": "Zürich HB",
               "coordinate": { "type": "WGS84", "x": 8.5402, "y": 47.3782 } },
  "stationboard": [
    {
      "stop": {
        "station": { "id": "8503000", "name": "Zürich HB" },
        "departure": "2026-06-04T08:00:00+02:00",
        "delay": 0,
        "platform": "3"
      },
      "name": "IC 1",
      "category": "IC",
      "number": "1",
      "operator": "SBB",
      "to": "Genève-Aéroport",
      "passList": [
        {
          "station": { "id": "8507000", "name": "Bern" },
          "arrival": "2026-06-04T08:57:00+02:00",
          "departure": "2026-06-04T08:59:00+02:00",
          "delay": 0,
          "platform": "7"
        }
      ]
    }
  ]
}
```

**Server-side cache:** 30 s.

---

#### `GET /v1/locations`

Proxy to `transport.opendata.ch/v1/locations`.  Accepts either:

- `?query=<name>` — station/stop name search
- `?x=<longitude>&y=<latitude>` — reverse geocode (WGS-84, decimal degrees)

**Response** (`LocationsResponseDto`):
```json
{
  "stations": [
    {
      "id": "8503000",
      "name": "Zürich HB",
      "score": 1.0,
      "coordinate": { "type": "WGS84", "x": 8.5402, "y": 47.3782 },
      "distance": 0
    }
  ]
}
```

`distance` is non-null only for coordinate lookups (metres from query point).

**Server-side cache:** 5 min for name queries; no caching for coordinate lookups
(used to resolve the user's live GPS position).

---

#### `GET /v1/gtfs/static.zip`

Serve the current Swiss timetable GTFS ZIP sourced from `opentransportdata.swiss`.

- The backend fetches and stores the ZIP in S3 on a background schedule (weekly or
  when a new edition is published by SBB).
- Respond with `ETag` and `Last-Modified` headers so `GtfsImportWorker` can issue
  conditional `If-None-Match` / `If-Modified-Since` requests and skip the download
  when unchanged (~300 MB file).
- `Content-Type: application/zip`
- Auth to opentransportdata.swiss happens server-side using the stored API token
  (never exposed to the client).

**GtfsImportWorker** (`worker/GtfsImportWorker.kt`) will call this URL once it exists.
Update `GTFS_FEED_URL` as shown at the top of this section.

---

#### `GET /v1/gtfs/realtime.pb`

Serve the current GTFS-RT `FeedMessage` as a raw protobuf binary.

- Backend fetches from `opentransportdata.swiss` using the stored API token.
- Response is forwarded verbatim — no JSON wrapping.
- `Content-Type: application/octet-stream`
- **Server-side cache: 30 s** (the upstream RT feed is refreshed every 30 s; the
  backend caches and fans out to all clients so only one upstream request fires per
  30 s window regardless of concurrent Android clients).

**GtfsRtRefreshWorker** (`worker/GtfsRtRefreshWorker.kt`) calls this URL every 15 min
(background) and the `GtfsRtDecoder` in the app parses the protobuf binary directly.
Update `RT_FEED_URL` as shown at the top of this section.

The feed contains two message types the app decodes:
- `TripUpdate` (field 5 in `FeedEntity`) — per-stop departure/arrival delays in seconds
- `Alert` (field 6 in `FeedEntity`) — service disruption text shown as a banner in
  `JourneysScreen`

---

### Authentication

The app sends `Authorization: Bearer <token>` on every request to the Rhosys backend.
A per-app static token (rotated per release, stored in `BuildConfig`) is sufficient —
individual user identity is not required for any of these endpoints.

The backend validates the token and forwards the downstream request without the
`Authorization` header (or with its own opentransportdata.swiss token where needed).

---

### Rate limiting & caching summary

| Upstream | Limit | Backend strategy |
|:---------|:------|:-----------------|
| `transport.opendata.ch` connections | ~3 req/min per IP | 60 s response cache keyed on `from+to+limit` |
| `transport.opendata.ch` stationboard | shared daily budget | 30 s response cache keyed on `station+limit` |
| `transport.opendata.ch` locations | shared daily budget | 5 min cache for name queries; no cache for coordinate |
| `opentransportdata.swiss` GTFS ZIP | per-token quota | S3-backed; serve from S3; re-fetch weekly |
| `opentransportdata.swiss` GTFS-RT | per-token quota | 30 s in-memory cache; one upstream call fans out to N clients |

---

### Infrastructure notes

- Backend runtime: lightweight service behind `api.rhosys.ch/sbb/` — a Node/Bun
  or Go Lambda is sufficient given the proxy-only workload.
- GTFS ZIP: store in the same S3 bucket used by the signing pipeline
  (`alias/deployment-encryption-key` account); serve via pre-signed URL or CloudFront.
- Secrets: `OPENTRANSPORT_API_TOKEN` stored in AWS Secrets Manager (same account as
  existing KMS key); backend reads it at startup.
- The existing `deployment/` pipeline and GitLab CI do not need changes for the
  backend; it is a separate deployable in `_rhosys-apps-infra`.

---

## Feature Todos

### 🔲 Fare display in TripReviewScreen
Once opentransportdata.swiss token is registered (Infra Todo 5), call the OJP Fare
beta endpoint for the selected connection and display the price (full fare, Half-Fare,
GA-free) in `TripReviewScreen`. Requires `subscriptionTier` from SwissPass OAuth
(Infra Todo 6) to pick the right price column.
Implementation sketch: `OjpFareRepository` → `GET /ojp` with `<OJPFareRequest>` XML →
parse `<FareResult>` → emit `FareResult(fullFareCHF, halfFareCHF)` domain type →
show in TripReviewScreen below the departure/arrival header.

### 🔲 "Buy in SBB Mobile" handoff
Once the deep link spec is obtained (Infra Todo 7), add a secondary button in
`TripReviewScreen` that opens SBB Mobile at the same connection. Fallback for when
B2P in-app purchase is not yet wired up.
URI sketch: `sbb://journey?from=<id>&to=<id>&date=<ISO>&via=<id>` (spec TBD).
