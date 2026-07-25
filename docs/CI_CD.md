# CI/CD Pipeline

GitLab CI runs validation on every push and MR, with manual build jobs and automatic deployment, defined in `.gitlab-ci.yml`.

---

## Stages

| Stage | Jobs | Trigger |
|-------|------|---------|
| validate | `compile`, `lint` | Every push and MR |
| test | `test` | Every push and MR |
| build | `build-debug`, `build-release` | Manual (see below) |
| deploy | `deploy-release` | Automatic after `build-release` |

Workflow rules prevent double-running when a branch has an open MR.

---

## Job: `compile` — validate stage

Runs `./gradlew compileDebugKotlin` to catch compilation errors early.

## Job: `lint` — validate stage

Runs `./gradlew lintDebug` for Android lint checks.

## Job: `test` — test stage

Runs `./gradlew testDebugUnitTest` for the unit test suite.

All three jobs use `cimg/android:2024.01` (JDK 17 + Android SDK) with branch-keyed Gradle caching.

---

## Job: `build-debug` — MRs only, manual trigger

Builds an unsigned debug APK via `./gradlew assembleDebug`.
Artifact retention: **7 days**.

---

## Job: `build-release` — main branch only, manual trigger

Builds a signed release AAB (Android App Bundle) ready for Play Store upload.

1. Assumes AWS IAM role via OIDC
2. Decodes keystore from `deployment/android-upload-signing.json`
3. Decrypts keystore password via `aws kms decrypt`
4. Runs `./gradlew bundleRelease` with `-Pandroid.injected.signing.*` properties

Artifact retention: **30 days**.

See [SIGNING.md](./SIGNING.md) for how signing works.

---

## Job: `deploy-release` — automatic after `build-release`

Runs automatically after a successful `build-release` on main. Uploads the signed AAB to the Google Play Internal Testing track for `ch.rhosys.sbb`.

Uses `node:24` image, installs dependencies via `npm ci`, then runs `npm run deploy:play-store`.

### GCP authentication flow

1. Write `GITLAB_OIDC_TOKEN` to `/tmp/gcp-oidc.jwt`
2. Write a GCP external account credential JSON referencing the OIDC token, WIF pool/provider, and service account
3. Set `GOOGLE_APPLICATION_CREDENTIALS` to the credential JSON path
4. Google Auth library handles the token exchange transparently
5. Deploy script uploads AAB to the `internal` track

---

## AWS OIDC Configuration

The `build-release` job uses GitLab OIDC to assume an AWS IAM role for KMS decryption.

| Property | Value |
|----------|-------|
| Account ID | `<ACCOUNT_ID>` |
| Role | `GitLabRunnerRole` |
| Role ARN | `arn:aws:iam::<ACCOUNT_ID>:role/GitLabRunnerRole` |
| Region | `eu-west-1` |
| Permission | `kms:Decrypt` on `alias/deployment-encryption-key` |
| Auth method | `GITLAB_OIDC_TOKEN` written to `$AWS_WEB_IDENTITY_TOKEN_FILE` |

The only CI/CD variable needed in GitLab project settings: `AWS_ACCOUNT_ID` = `<ACCOUNT_ID>`.

---

## GCP Workload Identity Federation

The `deploy-release` job uses GCP WIF to authenticate to the Play Store API without stored credentials.

| Property | Value |
|----------|-------|
| GCP Project | `rhosys-apps` (project number `454629444494`) |
| WIF Pool | `gitlab-oidc` |
| WIF Provider | `gitlab-com` |
| Audience | `//iam.googleapis.com/projects/454629444494/locations/global/workloadIdentityPools/gitlab-oidc/providers/gitlab-com` |
| Service Account | `gitlab-play-store@rhosys-apps.iam.gserviceaccount.com` |
| Binding | `attribute.project_path/rhosys/rapid/sbb-ruby-slippers` |

The WIF binding is managed in `_rhosys-apps-infra/gcp/main.tf` as a `google_service_account_iam_member` resource.

---

## Runner Environment

- **Build/validate/test jobs**: `cimg/android:2024.01` — lean Android SDK image with JDK 17
- **Deploy job**: `node:24` — only needs Node.js for the deploy script
- **Default id_tokens**: `GITLAB_OIDC_TOKEN` with audience `https://gitlab.com`

---

## Gradle Caching

Branch-keyed cache on `$CI_PROJECT_DIR/.gradle/` reduces build time after the first run on a branch.

```yaml
cache:
  key: gradle-$CI_COMMIT_REF_SLUG
  paths:
    - .gradle/
```
