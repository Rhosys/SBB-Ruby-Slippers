#!/usr/bin/env tsx
/**
 * deploy-play-store — uploads the signed phone AAB to the Google Play Internal Testing track,
 * and the Wear OS AAB (same package name) to the Wear OS form factor's internal track (wear:qa).
 *
 * Release name is derived from the VERSION environment variable or defaults to the versionName
 * in app/build.gradle.kts.
 *
 * GCP credentials come from Application Default Credentials (ADC):
 *   - In CI: GOOGLE_APPLICATION_CREDENTIALS points to the workload identity JSON,
 *     which exchanges the GitLab OIDC token at /tmp/gcp-oidc.jwt for short-lived GCP tokens.
 *   - Locally: gcloud auth application-default login
 */

import { createHash } from 'node:crypto';
import { createReadStream, readFileSync } from 'node:fs';
import { androidpublisher } from '@googleapis/androidpublisher';
import { GoogleAuth } from 'google-auth-library';

export const PACKAGE_NAME = 'ch.rhosys.sbb';
export const DEFAULT_AAB_PATH = 'app/build/outputs/bundle/release/app-release.aab';
export const WEAR_AAB_PATH = 'wear/build/outputs/bundle/release/wear-release.aab';
export const TRACK = 'internal';
// Form factor tracks are "[prefix]:defaultTrackName", and the default name of internal
// testing there is "qa" (Play Developer API, "APKs and Tracks"): wear:production,
// wear:beta, wear:qa.
export const WEAR_TRACK = 'wear:qa';

export interface TrackRelease {
  status: string | null;
  name: string | null;
  versionCodes: string[];
}

export interface PublisherClient {
  createEdit(packageName: string): Promise<string>;
  uploadBundle(packageName: string, editId: string, aabPath: string): Promise<number>;
  createRelease(
    packageName: string,
    editId: string,
    track: string,
    versionCode: number,
    status: string,
    releaseName: string
  ): Promise<void>;
  listTracks(
    packageName: string,
    editId: string
  ): Promise<Array<{ track: string | null; releases: TrackRelease[] }>>;
  listBundles(packageName: string, editId: string): Promise<UploadedBundle[]>;
  deleteEdit(packageName: string, editId: string): Promise<void>;
}

export interface UploadedBundle {
  versionCode: number;
  sha256: string | null;
}

export function sha256File(path: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha256');
    createReadStream(path)
      .on('error', reject)
      .on('data', (chunk) => hash.update(chunk))
      .on('end', () => resolve(hash.digest('hex')));
  });
}

const VERSION_CODE_USED = /Version code (\d+) has already been used/i;

function usedVersionCode(err: unknown): number | null {
  const msg = err instanceof Error ? err.message : String(err);
  const match = msg.match(VERSION_CODE_USED);
  return match ? Number(match[1]) : null;
}

/**
 * Play rejected the upload because the version code exists. Re-running a job (e.g. after the
 * phone release landed but the watch upload failed) is expected to hit this, so it is fine only
 * when Play's copy is byte-identical to the local AAB; anything else is a real conflict.
 */
async function resolveExistingBundle(
  client: PublisherClient,
  editId: string,
  aabPath: string,
  versionCode: number,
  hashFile: (path: string) => Promise<string>
): Promise<void> {
  const [bundles, localSha256] = await Promise.all([
    client.listBundles(PACKAGE_NAME, editId),
    hashFile(aabPath),
  ]);
  const existing = bundles.find((b) => b.versionCode === versionCode);
  if (!existing) {
    throw new Error(
      `Version code ${versionCode} has already been used, but Play lists no bundle with it ` +
        `(it may belong to a deleted or APK upload). Bump the versionCode and rebuild.`
    );
  }
  if (existing.sha256?.toLowerCase() !== localSha256.toLowerCase()) {
    throw new Error(
      `Version code ${versionCode} is already on Play with a DIFFERENT bundle ` +
        `(Play sha256 ${existing.sha256 ?? 'unknown'}, local ${localSha256}). ` +
        `Bump the versionCode and rebuild — Play never accepts two bundles with one version code.`
    );
  }
}

export function getVersionName(): string {
  if (process.env.VERSION) {
    return process.env.VERSION;
  }
  const gradle = readFileSync('app/build.gradle.kts', 'utf8');
  const match = gradle.match(/versionName\s*=\s*"([^"]+)"/);
  if (!match) {
    throw new Error('Could not read versionName from app/build.gradle.kts');
  }
  return match[1];
}

export function getTargetSdk(): string {
  const gradle = readFileSync('app/build.gradle.kts', 'utf8');
  const match = gradle.match(/targetSdk\s*=\s*(\d+)/);
  if (!match) {
    throw new Error('Could not read targetSdk from app/build.gradle.kts');
  }
  return match[1];
}

function isDraftAppError(err: unknown): boolean {
  const msg = err instanceof Error ? err.message : String(err);
  return msg.toLowerCase().includes('draft app');
}

export async function deployToPlayStore(
  aabPath: string,
  track: string,
  releaseName: string,
  client: PublisherClient,
  print: (msg: string) => void,
  hashFile: (path: string) => Promise<string> = sha256File
): Promise<void> {
  print(`Creating edit for ${PACKAGE_NAME}...`);
  const editId = await client.createEdit(PACKAGE_NAME);

  try {
    print(`Uploading AAB: ${aabPath}...`);
    let versionCode: number;
    try {
      versionCode = await client.uploadBundle(PACKAGE_NAME, editId, aabPath);
    } catch (err) {
      const used = usedVersionCode(err);
      if (used === null) throw err;
      versionCode = used;
      print(`Version code ${used} is already on Play — checking it matches ${aabPath}...`);
      await resolveExistingBundle(client, editId, aabPath, used, hashFile);
      print(`Identical bundle already uploaded (sha256 matches) — reusing it.`);

      const tracks = await client.listTracks(PACKAGE_NAME, editId);
      const onTrack = tracks
        .find((t) => t.track === track)
        ?.releases.find((r) => r.versionCodes.includes(String(used)));
      if (onTrack) {
        print(
          `Already released: version ${used} is on the '${track}' track (status: ${onTrack.status ?? 'unknown'}) — nothing to do.`
        );
        await client.deleteEdit(PACKAGE_NAME, editId).catch(() => undefined);
        return;
      }
    }

    let releaseStatus = 'completed';
    print(`Creating release ${versionCode} (${releaseName}) on track '${track}'...`);
    try {
      await client.createRelease(
        PACKAGE_NAME,
        editId,
        track,
        versionCode,
        'completed',
        releaseName
      );
    } catch (err) {
      if (!isDraftAppError(err)) throw err;
      print(`App is in draft state — retrying as draft (promote manually in Play Console)`);
      releaseStatus = 'draft';
      await client.createRelease(PACKAGE_NAME, editId, track, versionCode, 'draft', releaseName);
    }

    print(
      `Done — version ${versionCode} (${releaseName}) is on the '${track}' track (status: ${releaseStatus})`
    );
  } catch (err) {
    await client.deleteEdit(PACKAGE_NAME, editId).catch(() => undefined);
    throw err;
  }
}

async function fetchTrackReleases(
  client: PublisherClient,
  packageName: string
): Promise<Array<{ track: string | null; releases: TrackRelease[] }> | null> {
  let editId: string | null = null;
  try {
    editId = await client.createEdit(packageName);
    const tracks = await client.listTracks(packageName, editId);
    return tracks;
  } catch {
    return null;
  } finally {
    if (editId !== null) {
      await client.deleteEdit(packageName, editId).catch(() => undefined);
    }
  }
}

function flattenReleases(
  tracks: Array<{ track: string | null; releases: TrackRelease[] }>
): Array<{ track: string | null } & TrackRelease> {
  return tracks.flatMap((t) => t.releases.map((r) => ({ track: t.track, ...r })));
}

function printReleaseTable(releases: Array<{ track: string | null } & TrackRelease>): void {
  for (const r of releases) {
    const versionCodes = r.versionCodes.length > 0 ? r.versionCodes.join(', ') : '(none)';
    process.stderr.write(
      `  track '${r.track ?? 'unknown'}' [${r.status ?? 'unknown'}] versionCode(s): ${versionCodes} — ${r.name ?? '(unnamed)'}\n`
    );
  }
}

export function makePublisherClient(): PublisherClient {
  const auth = new GoogleAuth({
    scopes: ['https://www.googleapis.com/auth/androidpublisher'],
  });
  const publisher = androidpublisher({ version: 'v3', auth });

  return {
    async createEdit(packageName) {
      const { data } = await publisher.edits.insert({ packageName });
      return data.id!;
    },
    async uploadBundle(packageName, editId, aabPath) {
      const { data } = await publisher.edits.bundles.upload({
        packageName,
        editId,
        media: { mimeType: 'application/octet-stream', body: createReadStream(aabPath) },
      });
      return data.versionCode!;
    },
    async createRelease(packageName, editId, track, versionCode, status, releaseName) {
      await publisher.edits.tracks.update({
        packageName,
        editId,
        track,
        requestBody: {
          track,
          releases: [{ versionCodes: [String(versionCode)], status, name: releaseName }],
        },
      });
      await publisher.edits.commit({ packageName, editId });
    },
    async listTracks(packageName, editId) {
      const { data } = await publisher.edits.tracks.list({ packageName, editId });
      return (data.tracks ?? []).map((t) => ({
        track: t.track ?? null,
        releases: (t.releases ?? []).map((r) => ({
          status: r.status ?? null,
          name: r.name ?? null,
          versionCodes: r.versionCodes ?? [],
        })),
      }));
    },
    async listBundles(packageName, editId) {
      const { data } = await publisher.edits.bundles.list({ packageName, editId });
      return (data.bundles ?? []).map((b) => ({
        versionCode: b.versionCode ?? -1,
        sha256: b.sha256 ?? null,
      }));
    },
    async deleteEdit(packageName, editId) {
      await publisher.edits.delete({ packageName, editId });
    },
  };
}

const FORM_FACTOR_NAMES: Record<string, string> = {
  wear: 'Wear OS',
  automotive: 'Android Automotive OS',
  tv: 'Android TV',
  android_xr: 'Android XR',
};

/**
 * A "Track not found: <prefix>:<name>" means the app exists but Play has no track for that form
 * factor — almost always because the form factor was never added in Play Console. The Play
 * Developer API cannot add a form factor (edits.tracks.create only makes closed-testing tracks
 * for one that is already enabled), so this verifies against Play's own track list and tells the
 * developer exactly what to click.
 */
export async function diagnoseMissingFormFactorTrack(
  msg: string,
  prefix: string,
  track: string,
  client: PublisherClient
): Promise<never> {
  const name = FORM_FACTOR_NAMES[prefix] ?? prefix;
  const err = (line = '') => process.stderr.write(line + '\n');
  err(`Error: ${msg}`);
  err();
  err(`Checking Play's track list for ${PACKAGE_NAME}...`);
  const tracks = await fetchTrackReleases(client, PACKAGE_NAME);
  if (tracks === null) {
    err(`Could not fetch the track list to verify — check Play Console manually.`);
  } else {
    const ids = tracks.map((t) => t.track ?? '(unnamed)');
    err(`Tracks Play reports: ${ids.length > 0 ? ids.join(', ') : '(none)'}`);
    const formFactorTracks = ids.filter((id) => id.startsWith(`${prefix}:`));
    if (formFactorTracks.length > 0) {
      err();
      err(`The ${name} form factor IS enabled (tracks: ${formFactorTracks.join(', ')}), but '${track}'`);
      err(`is not one of them. Point the deploy script at an existing ${prefix}: track or create it in`);
      err(`Play Console → Test and release → Testing.`);
      process.exit(1);
    }
  }
  const bar = '='.repeat(78);
  err();
  err(bar);
  err(`  ACTION REQUIRED: the ${name} form factor is not enabled for ${PACKAGE_NAME}`);
  err(bar);
  err(`  This project builds a ${name} bundle, but Play has no '${prefix}:' tracks yet.`);
  err(`  The Play Developer API cannot enable a form factor — do this once by hand:`);
  err();
  err(`    1. Play Console → select ${PACKAGE_NAME}`);
  err(`    2. Test and release → Advanced settings → Form factors`);
  err(`    3. "+ Add form factor" → ${name}`);
  err(`    4. Accept the ${name} review / distribution terms and save`);
  err(`    5. Re-run this CI job — '${track}' will then exist and the upload will succeed.`);
  err();
  err(`  Releases already committed earlier in this job (e.g. the phone bundle) are kept.`);
  err(bar);
  process.exit(1);
}

export async function diagnoseError(err: unknown, client: PublisherClient): Promise<never> {
  const status =
    (err as { status?: number; code?: number }).status ??
    (err as { status?: number; code?: number }).code;
  const msg = err instanceof Error ? err.message : String(err);

  const missingTrack = msg.match(/Track not found:\s*([\w-]+):([\w-]+)/i);
  if (missingTrack) {
    await diagnoseMissingFormFactorTrack(msg, missingTrack[1], `${missingTrack[1]}:${missingTrack[2]}`, client);
  }

  if (msg.toLowerCase().includes('package not found') || status === 404) {
    process.stderr.write(`Error: ${msg}\n\n`);
    process.stderr.write(`The app does not exist in Google Play Console yet.\n`);
    process.stderr.write(`CI deploys require the app to be created and the first AAB\n`);
    process.stderr.write(`uploaded manually before the API can accept automated uploads.\n\n`);
    process.stderr.write(`Next steps:\n`);
    process.stderr.write(`  1. Open Play Console → All apps → Create app\n`);
    process.stderr.write(`     Package name: ${PACKAGE_NAME}\n`);
    process.stderr.write(
      `  2. Upload the AAB manually via Internal Testing → Create new release\n`
    );
    process.stderr.write(`  3. Re-run this CI job — automated deploys will work from then on.\n`);
    process.exit(1);
  }

  if (VERSION_CODE_USED.test(msg) || msg.includes('Bump the versionCode')) {
    process.stderr.write(`Error: ${msg}\n\n`);
    process.stderr.write(`Not a permissions problem: the version code collides with an existing upload.\n`);
    process.stderr.write(`Bump versionCode (root build.gradle.kts) and rebuild.\n`);
    process.exit(1);
  }

  if (status === 401 || status === 403) {
    process.stderr.write(`Error: Play Store API access denied (HTTP ${status})\n${msg}\n\n`);

    if (msg.includes('iam.serviceAccounts.getAccessToken')) {
      process.stderr.write(`Root cause: GCP Workload Identity Federation impersonation denied.\n`);
      process.stderr.write(`The CI runner's GitLab project path is not authorized to impersonate\n`);
      process.stderr.write(`the Play Store service account.\n\n`);
      process.stderr.write(`Fix: Add an explicit WIF binding in the shared GCP infrastructure repo's gcp/main.tf:\n\n`);
      process.stderr.write(`  resource "google_service_account_iam_member" "play_store_wif_<appname>" {\n`);
      process.stderr.write(`    service_account_id = google_service_account.play_store.name\n`);
      process.stderr.write(`    role               = "roles/iam.workloadIdentityUser"\n`);
      process.stderr.write(`    member             = "principalSet://iam.googleapis.com/\${pool}/attribute.project_path/<gitlab-group>/<project>"\n`);
      process.stderr.write(`  }\n\n`);
      process.stderr.write(`Note: CEL conditions and wildcards do NOT work for SA impersonation.\n`);
      process.stderr.write(`Each app must have an explicit principalSet binding with the full attribute path.\n`);
      process.stderr.write(`After adding, run \`tofu apply\` in the GCP infrastructure root.\n`);
      process.exit(1);
    }

    if (msg.toLowerCase().includes('target sdk')) {
      process.stderr.write(
        `Root cause: not a permissions issue — Play rejected the artifact's target SDK.\n`
      );
      process.stderr.write(`This repo's app/build.gradle.kts currently sets targetSdk = ${getTargetSdk()}.\n\n`);

      process.stderr.write(`Cross-checking against Play's own release list (not just trusting this error string)...\n`);
      const tracks = await fetchTrackReleases(client, PACKAGE_NAME);
      if (tracks === null) {
        process.stderr.write(
          `Could not fetch existing track releases to verify independently — check Play Console\n`
        );
        process.stderr.write(`→ Release overview manually for a release below the required target API level.\n`);
      } else {
        const releases = flattenReleases(tracks);
        if (releases.length === 0) {
          process.stderr.write(`Play reports no releases on any track.\n`);
        } else {
          process.stderr.write(`Releases Play currently has on file:\n`);
          printReleaseTable(releases);
          process.stderr.write(
            `\nThis build's versionCode is printed above ("Creating release N ..."); it is not\n`
          );
          process.stderr.write(
            `in this table because the upload was rejected before a release could be created.\n`
          );
          process.stderr.write(
            `Play's target-SDK policy is enforced per app, not per release — the low target SDK\n`
          );
          process.stderr.write(
            `it is citing almost always belongs to whichever release above has the lowest\n`
          );
          process.stderr.write(
            `versionCode (often the manually-uploaded bootstrap AAB used to create the app).\n`
          );
          process.stderr.write(`Check that release in Play Console → Release overview and replace it.\n`);
        }
      }
      process.exit(1);
    }

    if (msg.includes('does not have permission') || msg.includes('forbidden')) {
      process.stderr.write(`Root cause: Play Console has not granted this app to the service account.\n\n`);
      process.stderr.write(`Fix: Go to Play Console → Users and permissions → select the service account\n`);
      process.stderr.write(`→ add this app to the account's app list with default permissions.\n\n`);
      process.stderr.write(`The service account exists and WIF works, but the specific app\n`);
      process.stderr.write(`hasn't been added to its permitted apps in Play Console.\n`);
      process.exit(1);
    }

    const tracks = await fetchTrackReleases(client, PACKAGE_NAME);
    if (tracks !== null) {
      const allReleases = tracks.flatMap((t) => t.releases.map((r) => ({ track: t.track, ...r })));
      const draftReleases = allReleases.filter((r) => r.status === 'draft');
      if (draftReleases.length > 0) {
        process.stderr.write(
          `Found ${draftReleases.length} draft release(s) waiting for promotion:\n`
        );
        for (const r of draftReleases) {
          const label = r.name ?? r.versionCodes.join(', ') ?? '(unknown)';
          process.stderr.write(`  Track '${r.track ?? 'unknown'}': ${label}\n`);
        }
        process.stderr.write(`\n`);
        process.stderr.write(`The Play Store API requires at least one promoted release before\n`);
        process.stderr.write(
          `it accepts automated uploads. Go to Play Console → Internal Testing\n`
        );
        process.stderr.write(`→ review release → Start rollout to Internal testing.\n`);
      } else if (allReleases.length === 0) {
        process.stderr.write(`No releases found on any track.\n`);
        process.stderr.write(
          `Upload the first AAB manually via Play Console → Internal Testing.\n`
        );
      } else {
        process.stderr.write(`Check that the service account has the "Release Manager" role:\n`);
        process.stderr.write(`  Play Console → Users and permissions → find the service account\n`);
      }
    } else {
      process.stderr.write(`Check that the service account has the "Release Manager" role:\n`);
      process.stderr.write(`  Play Console → Users and permissions → find the service account\n`);
    }
    process.exit(1);
  }

  process.stderr.write(`Error: ${msg}\n`);
  process.exit(1);
}

export async function main(client: PublisherClient): Promise<void> {
  const print = (msg: string) => process.stderr.write(msg + '\n');
  const releaseName = getVersionName();
  await deployToPlayStore(DEFAULT_AAB_PATH, TRACK, releaseName, client, print);
  await deployToPlayStore(WEAR_AAB_PATH, WEAR_TRACK, releaseName, client, print);
}

if (require.main === module) {
  const client = makePublisherClient();
  main(client).catch((err: unknown) => diagnoseError(err, client));
}
