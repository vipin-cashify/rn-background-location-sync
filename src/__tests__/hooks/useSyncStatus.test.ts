import { act, renderHook, waitFor } from '@testing-library/react-native';
import { useSyncStatus } from '../../hooks/useSyncStatus';
import BackgroundLocationModule from '../../NativeBackgroundLocation';

const simulateSyncStatusEvent = (global as any).simulateSyncStatusEvent as (
  data: any
) => void;

describe('useSyncStatus', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (BackgroundLocationModule.getPendingCount as jest.Mock).mockResolvedValue(
      0
    );
  });

  it('seeds pending via getPendingCount on mount', async () => {
    (BackgroundLocationModule.getPendingCount as jest.Mock).mockResolvedValue(
      7
    );

    const { result } = renderHook(() => useSyncStatus());

    await waitFor(() => {
      expect(result.current.pending).toBe(7);
    });
    expect(BackgroundLocationModule.getPendingCount).toHaveBeenCalledTimes(1);
  });

  it('starts with lastResult, lastError, and authBlocked at their defaults', async () => {
    const { result } = renderHook(() => useSyncStatus());

    await waitFor(() => {
      expect(result.current.pending).toBe(0);
    });
    expect(result.current.lastResult).toBeNull();
    expect(result.current.lastError).toBeNull();
    expect(result.current.authBlocked).toBe(false);
  });

  it('updates pending, lastResult, and lastError on onSyncStatusChanged', async () => {
    const { result } = renderHook(() => useSyncStatus());
    await waitFor(() => expect(result.current.pending).toBe(0));

    act(() => {
      simulateSyncStatusEvent({
        uploaded: 5,
        failed: false,
        pendingAfter: 3,
        timestamp: 1700000000000,
        pending: 3,
        authBlocked: false,
      });
    });

    expect(result.current.pending).toBe(3);
    expect(result.current.lastResult).toEqual({
      uploaded: 5,
      failed: false,
      pendingAfter: 3,
      timestamp: 1700000000000,
    });
    expect(result.current.lastError).toBeNull();
    expect(result.current.authBlocked).toBe(false);
  });

  it('surfaces error and authBlocked from a failed (401) event', async () => {
    const { result } = renderHook(() => useSyncStatus());
    await waitFor(() => expect(result.current.pending).toBe(0));

    act(() => {
      simulateSyncStatusEvent({
        uploaded: 0,
        failed: true,
        httpCode: 401,
        error: 'HTTP_401',
        pendingAfter: 10,
        timestamp: 1700000001000,
        pending: 10,
        authBlocked: true,
      });
    });

    expect(result.current.lastError).toBe('HTTP_401');
    expect(result.current.authBlocked).toBe(true);
    expect(result.current.lastResult?.httpCode).toBe(401);
  });

  it('clears authBlocked on a later event without requiring a new lastResult error', async () => {
    const { result } = renderHook(() => useSyncStatus());
    await waitFor(() => expect(result.current.pending).toBe(0));

    act(() => {
      simulateSyncStatusEvent({
        uploaded: 0,
        failed: true,
        httpCode: 401,
        error: 'HTTP_401',
        pendingAfter: 10,
        timestamp: 1700000001000,
        pending: 10,
        authBlocked: true,
      });
    });
    expect(result.current.authBlocked).toBe(true);

    act(() => {
      simulateSyncStatusEvent({
        uploaded: 10,
        failed: false,
        pendingAfter: 0,
        timestamp: 1700000002000,
        pending: 0,
        authBlocked: false,
      });
    });

    expect(result.current.authBlocked).toBe(false);
    expect(result.current.lastError).toBeNull();
  });

  it('refresh() re-fetches pending from native', async () => {
    (BackgroundLocationModule.getPendingCount as jest.Mock)
      .mockResolvedValueOnce(0)
      .mockResolvedValueOnce(9);

    const { result } = renderHook(() => useSyncStatus());
    await waitFor(() => expect(result.current.pending).toBe(0));

    await act(async () => {
      await result.current.refresh();
    });

    expect(result.current.pending).toBe(9);
    expect(BackgroundLocationModule.getPendingCount).toHaveBeenCalledTimes(2);
  });

  it('warns and skips when the native module is not available', async () => {
    const originalIsTracking = BackgroundLocationModule.isTracking;
    Object.defineProperty(BackgroundLocationModule, 'isTracking', {
      value: undefined,
      configurable: true,
      writable: true,
    });

    renderHook(() => useSyncStatus());

    await waitFor(() => {
      expect(console.warn).toHaveBeenCalledWith(
        expect.stringContaining('BackgroundLocation not available')
      );
    });
    expect(BackgroundLocationModule.getPendingCount).not.toHaveBeenCalled();

    Object.defineProperty(BackgroundLocationModule, 'isTracking', {
      value: originalIsTracking,
      configurable: true,
      writable: true,
    });
    (BackgroundLocationModule.isTracking as jest.Mock) = jest.fn();
  });
});
