#!/usr/bin/env tsx
/**
 * notify-deploy — posts a deployment notification to Discord via webhook.
 *
 * Usage:
 *   notify-deploy --version-code <code> --version-name <name>
 *
 * Requires DISCORD_RHOSYS_CI_CD_CHANNEL_WEBHOOK env var (GitLab group-level).
 */

import { PACKAGE_NAME } from './deploy-play-store';

export function buildTestingUrl(packageName: string, versionCode: string): string {
  return `https://play.google.com/apps/test/${packageName}/${versionCode}`;
}

function parseArgs(args: string[]): { versionCode: string; versionName: string } {
  let versionCode = '';
  let versionName = '';

  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--version-code' && args[i + 1]) versionCode = args[++i]!;
    else if (args[i] === '--version-name' && args[i + 1]) versionName = args[++i]!;
  }

  if (!versionCode || !versionName) {
    process.stderr.write('Usage: notify-deploy --version-code <code> --version-name <name>\n');
    process.exit(1);
  }

  return { versionCode, versionName };
}

async function main(): Promise<void> {
  const { versionCode, versionName } = parseArgs(process.argv.slice(2));
  const webhookUrl = process.env.DISCORD_RHOSYS_CI_CD_CHANNEL_WEBHOOK;

  if (!webhookUrl) {
    process.stderr.write('Warning: DISCORD_RHOSYS_CI_CD_CHANNEL_WEBHOOK not set — skipping notification.\n');
    return;
  }

  const testingUrl = buildTestingUrl(PACKAGE_NAME, versionCode);
  const content = [
    `📱 **${PACKAGE_NAME}** v${versionName} (${versionCode}) deployed to Play Store Internal Testing`,
    ``,
    `Install/update: ${testingUrl}`,
  ].join('\n');

  const response = await fetch(webhookUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  });

  if (!response.ok) {
    process.stderr.write(`Warning: Discord webhook returned ${response.status}: ${await response.text()}\n`);
  } else {
    process.stderr.write('✓ Discord notification sent.\n');
  }
}

main().catch((err: unknown) => {
  process.stderr.write(`Warning: Failed to send notification: ${err instanceof Error ? err.message : err}\n`);
  // Non-fatal — don't fail the deploy job over a notification
  process.exit(0);
});
