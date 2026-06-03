# Android Signing

The signing keystore lives in `deployment/android-upload-signing.json`.
Generated once with `generate-android-keystore --alias sbb` from the `_tools` repo — never needs regenerating unless the Play Store upload key is revoked.

## Generating the keystore

```bash
generate-android-keystore --alias sbb > deployment/android-upload-signing.json
```

This produces a JSON file containing:
- An RSA 4096-bit key pair with 30-year validity (10950 days), PKCS12 format
- DN: `CN=rhosys.ch, O=Rhosys AG, OU=Mobile, L=Unknown, ST=Unknown, C=CH`
- A cryptographically random password (32 bytes, base64url-encoded)
- The password encrypted with AWS KMS before being written to the file

The tool cleans up all temporary plaintext files on exit (success or failure).

## What's in the file

```json
{
  "keystore": "<base64-encoded PKCS12>",
  "passwordCiphertext": "<base64-encoded KMS ciphertext of the keystore password>"
}
```

- **keystore** — RSA 4096, PKCS12, 30-year validity, alias `sbb`. Password-protected; safe to commit.
- **passwordCiphertext** — KMS-encrypted with `alias/deployment-encryption-key` (eu-west-1). Decryptable only by the GitLab runner's IAM role.

## CI signs automatically

The `build-release` job in `.gitlab-ci.yml` handles everything:

1. Decodes the keystore (`keystore` field, plain base64 → PKCS12 file)
2. Calls `aws kms decrypt` on `passwordCiphertext` to recover the password
3. Passes both to Gradle via `-Pandroid.injected.signing.*` properties for `bundleRelease`

No CI variables needed for signing — everything is in the repo.

### KMS decryption pattern

```bash
# 1. Write OIDC token for AWS web identity
echo "${GITLAB_OIDC_TOKEN}" > "${AWS_WEB_IDENTITY_TOKEN_FILE}"

# 2. Extract and decode the keystore to a temp file
jq -r '.keystore' deployment/android-upload-signing.json | base64 --decode > app/android-upload-signing.keystore

# 3. Decrypt the password (plaintext exists only in memory/shell variable)
STORE_PASSWORD=$(jq -r '.passwordCiphertext' deployment/android-upload-signing.json \
  | base64 --decode \
  | aws kms decrypt --ciphertext-blob fileb:///dev/stdin --region eu-west-1 --output text --query Plaintext \
  | base64 --decode)

# 4. Build with signing properties
./gradlew bundleRelease \
  -Pandroid.injected.signing.store.file="$CI_PROJECT_DIR/app/android-upload-signing.keystore" \
  -Pandroid.injected.signing.store.password="$STORE_PASSWORD" \
  -Pandroid.injected.signing.key.alias=sbb \
  -Pandroid.injected.signing.key.password="$STORE_PASSWORD"
```

Key alias is `sbb`. Store password and key password are the same value (PKCS12 uses one password). Plaintext exists only for the duration of the CI job.

## OIDC role configuration

The GitLab runner assumes `GitLabRunnerRole` via OIDC federation. The role requires:

| Property | Value |
|----------|-------|
| Role ARN | `arn:aws:iam::${AWS_ACCOUNT_ID}:role/GitLabRunnerRole` |
| Region | `eu-west-1` |
| Permission | `kms:Decrypt` on `alias/deployment-encryption-key` |
| Auth method | GitLab OIDC token → `AWS_WEB_IDENTITY_TOKEN_FILE` |
| CI variable | `AWS_ACCOUNT_ID` = `981461567584` (set in GitLab project settings) |

No other CI/CD secrets are required. The OIDC trust policy on the IAM role restricts access to the `rhosys/rapid/sbb-ruby-slippers` project path.

## Local release build

```bash
# Requires RhosysEngineer SSO access (kms:Decrypt on alias/deployment-encryption-key)
STORE_PASSWORD=$(jq -r '.passwordCiphertext' deployment/android-upload-signing.json | \
  base64 --decode | \
  aws kms decrypt --ciphertext-blob fileb:///dev/stdin --region eu-west-1 --output text --query Plaintext | \
  base64 --decode)

jq -r '.keystore' deployment/android-upload-signing.json | base64 --decode > app/android-upload-signing.keystore

./gradlew bundleRelease \
  -Pandroid.injected.signing.store.file="$(pwd)/app/android-upload-signing.keystore" \
  -Pandroid.injected.signing.store.password="$STORE_PASSWORD" \
  -Pandroid.injected.signing.key.alias=sbb \
  -Pandroid.injected.signing.key.password="$STORE_PASSWORD"

# AAB: app/build/outputs/bundle/release/app-release.aab
rm -f app/android-upload-signing.keystore
```

## Key rotation

Google Play uses **Play App Signing** — the upload key (what we hold) is separate from the app signing key (held by Google). Rotation applies to the upload key only.

**When to rotate:**
- Upload key compromised or leaked
- Team member with access leaves without credential rotation
- Google revokes the upload key due to policy violation

**Steps to rotate:**

1. Generate a new keystore: `generate-android-keystore --alias sbb > deployment/android-upload-signing.json`
2. Export the new upload certificate: `keytool -export -alias sbb -keystore <decoded-keystore> -rfc > upload_cert.pem`
3. In Google Play Console → App integrity → Upload key → Request upload key reset
4. Upload `upload_cert.pem` when prompted
5. Wait for Google to approve the reset (typically 2–5 business days)
6. Commit the updated `deployment/android-upload-signing.json` to the repo
7. Verify the next `build-release` + `deploy-release` pipeline succeeds

The old key becomes invalid once Google approves the reset. All future uploads must use the new key.
