// Mock ESM-only transitive deps that Jest cannot transform out of the box.
// The injectable deps pattern means these modules are never called in tests.
jest.mock('@googleapis/androidpublisher', () => ({}));
jest.mock('google-auth-library', () => ({ GoogleAuth: jest.fn() }));

import { deployToPlayStore, diagnoseError, PACKAGE_NAME } from './deploy-play-store';

const FAKE_EDIT_ID = 'edit-abc123';
const FAKE_VERSION_CODE = 42;
const FAKE_AAB = '/tmp/app-release.aab';
const FAKE_VERSION_NAME = '1.0.42';
const TRACK = 'internal';

function happyClient() {
  return {
    createEdit: jest.fn().mockResolvedValue(FAKE_EDIT_ID),
    uploadBundle: jest.fn().mockResolvedValue(FAKE_VERSION_CODE),
    createRelease: jest.fn().mockResolvedValue(undefined),
    listTracks: jest.fn().mockResolvedValue([]),
    deleteEdit: jest.fn().mockResolvedValue(undefined),
  };
}

describe('deployToPlayStore', () => {
  describe('happy path', () => {
    it('runs create → upload → release in order', async () => {
      const order: string[] = [];
      const client = happyClient();
      client.createEdit.mockImplementation(async () => {
        order.push('create');
        return FAKE_EDIT_ID;
      });
      client.uploadBundle.mockImplementation(async () => {
        order.push('upload');
        return FAKE_VERSION_CODE;
      });
      client.createRelease.mockImplementation(async () => {
        order.push('release');
      });

      await deployToPlayStore(FAKE_AAB, TRACK, FAKE_VERSION_NAME, client, jest.fn());

      expect(order).toEqual(['create', 'upload', 'release']);
    });

    it('passes package name and AAB path to uploadBundle', async () => {
      const client = happyClient();
      await deployToPlayStore(FAKE_AAB, TRACK, FAKE_VERSION_NAME, client, jest.fn());
      expect(client.uploadBundle).toHaveBeenCalledWith(PACKAGE_NAME, FAKE_EDIT_ID, FAKE_AAB);
    });

    it('passes versionCode, completed status, and release name to createRelease', async () => {
      const client = happyClient();
      await deployToPlayStore(FAKE_AAB, TRACK, FAKE_VERSION_NAME, client, jest.fn());
      expect(client.createRelease).toHaveBeenCalledWith(
        PACKAGE_NAME,
        FAKE_EDIT_ID,
        TRACK,
        FAKE_VERSION_CODE,
        'completed',
        FAKE_VERSION_NAME
      );
    });
  });

  describe('draft app fallback', () => {
    it('retries with draft status when Play API rejects on a draft app', async () => {
      const client = happyClient();
      client.createRelease
        .mockRejectedValueOnce(
          new Error('Only releases with status draft may be created on draft app.')
        )
        .mockResolvedValueOnce(undefined);

      await deployToPlayStore(FAKE_AAB, TRACK, FAKE_VERSION_NAME, client, jest.fn());

      expect(client.createRelease).toHaveBeenCalledTimes(2);
      expect(client.createRelease).toHaveBeenNthCalledWith(
        1,
        PACKAGE_NAME,
        FAKE_EDIT_ID,
        TRACK,
        FAKE_VERSION_CODE,
        'completed',
        FAKE_VERSION_NAME
      );
      expect(client.createRelease).toHaveBeenNthCalledWith(
        2,
        PACKAGE_NAME,
        FAKE_EDIT_ID,
        TRACK,
        FAKE_VERSION_CODE,
        'draft',
        FAKE_VERSION_NAME
      );
    });

    it('does not retry on non-draft errors', async () => {
      const client = {
        ...happyClient(),
        createRelease: jest.fn().mockRejectedValue(new Error('quota exceeded')),
      };
      await expect(
        deployToPlayStore(FAKE_AAB, TRACK, FAKE_VERSION_NAME, client, jest.fn())
      ).rejects.toThrow('quota exceeded');
      expect(client.createRelease).toHaveBeenCalledTimes(1);
    });
  });

  describe('cleanup on failure', () => {
    it('deletes edit when uploadBundle fails', async () => {
      const client = {
        ...happyClient(),
        uploadBundle: jest.fn().mockRejectedValue(new Error('upload failed')),
      };
      await expect(
        deployToPlayStore(FAKE_AAB, TRACK, FAKE_VERSION_NAME, client, jest.fn())
      ).rejects.toThrow('upload failed');
      expect(client.deleteEdit).toHaveBeenCalledWith(PACKAGE_NAME, FAKE_EDIT_ID);
    });

    it('deletes edit when createRelease fails', async () => {
      const client = {
        ...happyClient(),
        createRelease: jest.fn().mockRejectedValue(new Error('quota exceeded')),
      };
      await expect(
        deployToPlayStore(FAKE_AAB, TRACK, FAKE_VERSION_NAME, client, jest.fn())
      ).rejects.toThrow('quota exceeded');
      expect(client.deleteEdit).toHaveBeenCalledWith(PACKAGE_NAME, FAKE_EDIT_ID);
    });

    it('does not throw when deleteEdit itself fails (best-effort cleanup)', async () => {
      const client = {
        ...happyClient(),
        uploadBundle: jest.fn().mockRejectedValue(new Error('upload failed')),
        deleteEdit: jest.fn().mockRejectedValue(new Error('delete also failed')),
      };
      await expect(
        deployToPlayStore(FAKE_AAB, TRACK, FAKE_VERSION_NAME, client, jest.fn())
      ).rejects.toThrow('upload failed');
    });
  });
});

describe('diagnoseError target-SDK cross-check', () => {
  let stderrSpy: jest.SpyInstance;
  let exitSpy: jest.SpyInstance;

  beforeEach(() => {
    stderrSpy = jest.spyOn(process.stderr, 'write').mockImplementation(() => true);
    exitSpy = jest.spyOn(process, 'exit').mockImplementation(((code?: number) => {
      throw new Error(`process.exit(${code})`);
    }) as never);
  });

  afterEach(() => {
    stderrSpy.mockRestore();
    exitSpy.mockRestore();
  });

  function targetSdkError() {
    const err = new Error('Target SDK of artifact is too low: 23.');
    (err as unknown as { status: number }).status = 403;
    return err;
  }

  it('fetches and prints the actual track releases instead of only trusting the error text', async () => {
    const client = {
      ...happyClient(),
      listTracks: jest.fn().mockResolvedValue([
        {
          track: 'internal',
          releases: [{ status: 'completed', name: '1.0.5', versionCodes: ['5'] }],
        },
        {
          track: 'production',
          releases: [{ status: 'completed', name: '1.0.1', versionCodes: ['1'] }],
        },
      ]),
    };

    await expect(diagnoseError(targetSdkError(), client)).rejects.toThrow('process.exit(1)');

    expect(client.createEdit).toHaveBeenCalledWith(PACKAGE_NAME);
    expect(client.listTracks).toHaveBeenCalled();
    expect(client.deleteEdit).toHaveBeenCalled();

    const output = stderrSpy.mock.calls.map((c) => c[0]).join('');
    expect(output).toContain("track 'internal'");
    expect(output).toContain("track 'production'");
    expect(output).toContain('versionCode(s): 5');
    expect(output).toContain('versionCode(s): 1');
  });

  it('says so when the independent fetch itself fails, instead of pretending to have verified', async () => {
    const client = {
      ...happyClient(),
      listTracks: jest.fn().mockRejectedValue(new Error('network error')),
    };

    await expect(diagnoseError(targetSdkError(), client)).rejects.toThrow('process.exit(1)');

    const output = stderrSpy.mock.calls.map((c) => c[0]).join('');
    expect(output).toContain('Could not fetch existing track releases to verify independently');
  });
});
