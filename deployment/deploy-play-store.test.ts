// Mock ESM-only transitive deps that Jest cannot transform out of the box.
// The injectable deps pattern means these modules are never called in tests.
jest.mock('@googleapis/androidpublisher', () => ({}));
jest.mock('google-auth-library', () => ({ GoogleAuth: jest.fn() }));

import { deployToPlayStore, PACKAGE_NAME } from './deploy-play-store';

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
