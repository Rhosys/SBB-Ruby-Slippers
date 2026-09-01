#!/usr/bin/env tsx
/**
 * deploy-play-store — uploads a signed AAB to the Google Play Internal Testing track.
 *
 * Release name is derived from the VERSION environment variable or defaults to the versionName
 * in app/build.gradle.kts.
 *
 * GCP credentials come from Application Default Credentials (ADC):
 *   - In CI: GOOGLE_APPLICATION_CREDENTIALS points to the workload identity JSON,
 *     which exchanges the GitLab OIDC token at /tmp/gcp-oidc.jwt for short-lived GCP tokens.
 *   - Locally: gcloud auth application-default login
 */

import { createReadStream, readFileSync } from 'node:fs';
import { androidpublisher } from '@googleapis/androidpublisher';
import { GoogleAuth } from 'google-auth-library';

export const PACKAGE_NAME = 'ch.rhosys.sbb';
export const DEFAULT_AAB_PATH = 'app/build/outputs/bundle/release/app-release.aab';

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
  deleteEdit(packageName: string, editId: string): Promise<void>;
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
  print: (msg: string) => void
): Promise<void> {
  print(`Creating edit for ${PACKAGE_NAME}...`);
  const editId = await client.createEdit(PACKAGE_NAME);

  try {
    print(`Uploading AAB: ${aabPath}...`);
    const versionCode = await client.uploadBundle(PACKAGE_NAME, editId, aabPath);

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
    async deleteEdit(packageName, editId) {
      await publisher.edits.delete({ packageName, editId });
    },
  };
}

async function diagnoseError(err: unknown, client: PublisherClient): Promise<never> {
  const status =
    (err as { status?: number; code?: number }).status ??
    (err as { status?: number; code?: number }).code;
  const msg = err instanceof Error ? err.message : String(err);

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
      process.stderr.write(
        `If that's already current, the artifact Play is comparing against is an older\n`
      );
      process.stderr.write(
        `release on some track (often the manually-uploaded bootstrap AAB) — check\n`
      );
      process.stderr.write(`Play Console → Release overview for a release below the required target API level.\n`);
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
  await deployToPlayStore(DEFAULT_AAB_PATH, 'internal', getVersionName(), client, (msg) =>
    process.stderr.write(msg + '\n')
  );
}

if (require.main === module) {
  const client = makePublisherClient();
  main(client).catch((err: unknown) => diagnoseError(err, client));
}
