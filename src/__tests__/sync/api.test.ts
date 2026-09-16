import {
  configureSync,
  setAuthToken,
  setSsoToken,
  getPendingCount,
  forceSync,
} from '../../index';
import BackgroundLocationModule from '../../NativeBackgroundLocation';
import type { SyncConfig, SyncResult } from '../../types';

describe('Sync API', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (BackgroundLocationModule.configureSync as jest.Mock).mockResolvedValue(
      undefined
    );
    (BackgroundLocationModule.setAuthToken as jest.Mock).mockResolvedValue(
      undefined
    );
    (BackgroundLocationModule.getPendingCount as jest.Mock).mockResolvedValue(
      0
    );
    (BackgroundLocationModule.forceSync as jest.Mock).mockResolvedValue({
      uploaded: 0,
      failed: false,
      pendingAfter: 0,
      timestamp: 0,
    });
  });

  describe('configureSync', () => {
    it('passes syncUrl and optional fields through unchanged', async () => {
      const config: SyncConfig = {
        syncUrl: 'https://example.com/api/v1/locations/bulk',
        batchSize: 250,
        maxQueueRows: 5000,
        maxAgeDays: 3,
      };

      await configureSync(config);

      expect(BackgroundLocationModule.configureSync).toHaveBeenCalledWith({
        syncUrl: 'https://example.com/api/v1/locations/bulk',
        batchSize: 250,
        maxQueueRows: 5000,
        maxAgeDays: 3,
        headersJson: undefined,
      });
    });

    it('JSON-serializes headers as headersJson', async () => {
      const config: SyncConfig = {
        syncUrl: 'https://example.com/sync',
        headers: { 'X-Tenant': 'acme' },
      };

      await configureSync(config);

      const calledWith = (BackgroundLocationModule.configureSync as jest.Mock)
        .mock.calls[0][0];
      expect(calledWith.headersJson).toBe(
        JSON.stringify({ 'X-Tenant': 'acme' })
      );
    });

    it('omits headersJson when headers is not provided', async () => {
      await configureSync({ syncUrl: 'https://example.com/sync' });

      const calledWith = (BackgroundLocationModule.configureSync as jest.Mock)
        .mock.calls[0][0];
      expect(calledWith.headersJson).toBeUndefined();
    });

    it('propagates errors from native module', async () => {
      const error = new Error('Failed to configure sync');
      (BackgroundLocationModule.configureSync as jest.Mock).mockRejectedValue(
        error
      );

      await expect(
        configureSync({ syncUrl: 'https://example.com/sync' })
      ).rejects.toThrow('Failed to configure sync');
    });
  });

  describe('setAuthToken', () => {
    it('passes the token through to native', async () => {
      await setAuthToken('secret-token');
      expect(BackgroundLocationModule.setAuthToken).toHaveBeenCalledWith(
        'secret-token'
      );
    });

    it('passes an empty string through to clear the token', async () => {
      await setAuthToken('');
      expect(BackgroundLocationModule.setAuthToken).toHaveBeenCalledWith('');
    });

    it('propagates errors from native module', async () => {
      const error = new Error('Failed to set token');
      (BackgroundLocationModule.setAuthToken as jest.Mock).mockRejectedValue(
        error
      );

      await expect(setAuthToken('t')).rejects.toThrow('Failed to set token');
    });
  });

  describe('setSsoToken', () => {
    beforeEach(() => {
      (BackgroundLocationModule.setSsoToken as jest.Mock).mockResolvedValue(
        undefined
      );
    });

    it('passes the sso token through to native', async () => {
      await setSsoToken('sso-secret');
      expect(BackgroundLocationModule.setSsoToken).toHaveBeenCalledWith(
        'sso-secret'
      );
    });

    it('passes an empty string through to clear the sso token', async () => {
      await setSsoToken('');
      expect(BackgroundLocationModule.setSsoToken).toHaveBeenCalledWith('');
    });

    it('propagates errors from native module', async () => {
      (BackgroundLocationModule.setSsoToken as jest.Mock).mockRejectedValue(
        new Error('Failed to set sso token')
      );

      await expect(setSsoToken('t')).rejects.toThrow('Failed to set sso token');
    });
  });

  describe('getPendingCount', () => {
    it('returns the count reported by native', async () => {
      (
        BackgroundLocationModule.getPendingCount as jest.Mock
      ).mockResolvedValue(42);

      const result = await getPendingCount();

      expect(result).toBe(42);
      expect(BackgroundLocationModule.getPendingCount).toHaveBeenCalledTimes(
        1
      );
    });

    it('propagates errors from native module', async () => {
      const error = new Error('DB error');
      (
        BackgroundLocationModule.getPendingCount as jest.Mock
      ).mockRejectedValue(error);

      await expect(getPendingCount()).rejects.toThrow('DB error');
    });
  });

  describe('forceSync', () => {
    it('returns the SyncResult reported by native', async () => {
      const mockResult: SyncResult = {
        uploaded: 10,
        failed: false,
        pendingAfter: 2,
        timestamp: 1700000000000,
      };
      (BackgroundLocationModule.forceSync as jest.Mock).mockResolvedValue(
        mockResult
      );

      const result = await forceSync();

      expect(result).toEqual(mockResult);
      expect(BackgroundLocationModule.forceSync).toHaveBeenCalledTimes(1);
    });

    it('returns a failed result with httpCode/error when the run stops early', async () => {
      const mockResult: SyncResult = {
        uploaded: 0,
        failed: true,
        httpCode: 401,
        error: 'HTTP_401',
        pendingAfter: 5,
        timestamp: 1700000000000,
      };
      (BackgroundLocationModule.forceSync as jest.Mock).mockResolvedValue(
        mockResult
      );

      const result = await forceSync();

      expect(result).toEqual(mockResult);
    });

    it('propagates errors from native module', async () => {
      const error = new Error('Sync crashed');
      (BackgroundLocationModule.forceSync as jest.Mock).mockRejectedValue(
        error
      );

      await expect(forceSync()).rejects.toThrow('Sync crashed');
    });
  });
});
