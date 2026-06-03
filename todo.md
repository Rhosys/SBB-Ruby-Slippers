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
- `AWS_ACCOUNT_ID` = `981461567584`

(AWS infra reuses existing `GitLabRunnerRole` + `alias/deployment-encryption-key` —
no AWS changes needed beyond the keystore in Todo 1.)

### 🔲 5. (Optional) opentransportdata.swiss token
If/when official real-time data (OJP 2.0, delay feeds, etc.) is needed beyond
the free `transport.opendata.ch` API, register at `opentransportdata.swiss` for
a free API token and add it as a GitLab/GitHub secret.

### 🔲 6. (Optional) Branded launcher icon
Replace the placeholder launcher icon with a custom SBB Ruby Slippers icon in
`app/src/main/res/drawable/ic_launcher_*.xml` and `mipmap-anydpi-v26/`.

### 🔲 7. (Optional) PostHog project
Create a dedicated PostHog project for SBB Ruby Slippers at `live.rhosys.ch`
and replace the `phc_D195...` key in `SbbRubySlippersApp.kt` with the new key.
Currently reuses the Lyra (Kinetic-Jewelry) project key.

---

## Feature Todos

### 🔲 Location autocomplete
Wire `TransportApi.getLocations()` to the from/to text fields in
`ConnectionSearchScreen` for typeahead suggestions.

### 🔲 Departure details screen
Navigate from a `StationboardScreen` entry to a detail screen showing
the full pass list for a journey.

### 🔲 Favourite stations
Persist favourite stations using DataStore; show them as quick-pick chips
in the stationboard screen.
