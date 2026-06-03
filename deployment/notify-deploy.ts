#!/usr/bin/env tsx
/**
 * notify-deploy — sends a deployment notification email via SES.
 *
 * Usage:
 *   notify-deploy --version-code <code> --version-name <name>
 *
 * AWS credentials come from the environment (OIDC web identity in CI).
 */

import { SESv2Client, SendEmailCommand } from '@aws-sdk/client-sesv2';
import { PACKAGE_NAME } from './deploy-play-store';

const FROM_ADDRESS = 'gitlab-runner@rhosys.cloud';
const TO_ADDRESS = 'developers@rhosys.ch';
const REGION = 'eu-west-1';

export interface NotifyDeps {
  sendEmail: (subject: string, body: string) => Promise<void>;
  print: (msg: string) => void;
}

export function buildTestingUrl(packageName: string, versionCode: string): string {
  return `https://play.google.com/apps/test/${packageName}/${versionCode}`;
}

export async function notifyDeploy(
  packageName: string,
  versionCode: string,
  versionName: string,
  deps: NotifyDeps,
): Promise<void> {
  const testingUrl = buildTestingUrl(packageName, versionCode);
  const subject = `[Deploy] ${packageName} v${versionName} (${versionCode}) published`;
  const body = [
    `A new version of ${packageName} has been published to the Play Store Internal Testing track.`,
    '',
    `Version: ${versionName} (code ${versionCode})`,
    `Testing link: ${testingUrl}`,
    '',
    'Install or update via the link above.',
  ].join('\n');

  deps.print(`Sending deployment notification to ${TO_ADDRESS}...`);
  await deps.sendEmail(subject, body);
  deps.print(`✓ Notification sent.`);
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
  const packageName = PACKAGE_NAME;

  const ses = new SESv2Client({ region: REGION });

  await notifyDeploy(packageName, versionCode, versionName, {
    sendEmail: async (subject, body) => {
      await ses.send(new SendEmailCommand({
        FromEmailAddress: FROM_ADDRESS,
        Destination: { ToAddresses: [TO_ADDRESS] },
        Content: {
          Simple: {
            Subject: { Data: subject },
            Body: { Text: { Data: body } },
          },
        },
      }));
    },
    print: (msg) => process.stderr.write(msg + '\n'),
  });
}

if (require.main === module) {
  main().catch((err: unknown) => {
    process.stderr.write(`Warning: Failed to send notification: ${err instanceof Error ? err.message : err}\n`);
    // Non-fatal — don't fail the deploy job over a notification
    process.exit(0);
  });
}
