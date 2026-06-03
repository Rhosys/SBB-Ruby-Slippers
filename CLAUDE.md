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

## Repository layout

```
app/src/main/java/ch/rhosys/sbb/
  SbbRubySlippersApp.kt         ← Application class (PostHog, crash handler)
  MainActivity.kt               ← Compose entry point, bottom nav
  data/remote/
    TransportApi.kt             ← Retrofit interface → transport.opendata.ch
    TransportRepositoryImpl.kt  ← wires API to domain interface
    dto/TransportDtos.kt        ← kotlinx-serialization DTOs
  di/
    NetworkModule.kt            ← Hilt DI: OkHttp, Json, Retrofit, TransportApi
  domain/
    TransportRepository.kt      ← interface used by ViewModels
  ui/
    navigation/{Screen,AppNavHost}.kt
    search/                     ← Connection search (from → to → list)
    stationboard/               ← Station departure board
    settings/
    theme/Theme.kt
    error/StartupErrorScreen.kt
deployment/
  android-upload-signing.json  ← PLACEHOLDER — must be replaced before release
  deploy-play-store.ts         ← Play Store upload script
  notify-deploy.ts             ← SES deploy notification
.github/workflows/build.yml    ← GitHub CI: compile / lint / test / debug-apk
.gitlab-ci.yml                 ← GitLab CI: validate + signed-AAB release pipeline
```

## Data source

The app fetches directly from `https://transport.opendata.ch/` — a public,
no-auth Swiss transport open-data API. Main endpoints:
- `GET v1/connections?from=&to=` — journey planner
- `GET v1/stationboard?station=` — live departures
- `GET v1/locations?query=` — station/stop search

Rate limit: ~1 000 req/day, 3 route queries/min (per IP). Sufficient for a client
app. For official real-time data (OJP 2.0) use `opentransportdata.swiss` — needs a
free API token (see `todo.md`).
