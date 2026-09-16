"use strict";

import { useState, useEffect, useCallback, useMemo } from 'react';
import { NativeEventEmitter } from 'react-native';
import BackgroundLocationModule from "../NativeBackgroundLocation.js";
// Check if native module is available
const isNativeModuleAvailable = () => {
  try {
    if (typeof BackgroundLocationModule?.isTracking !== 'function') {
      return false;
    }
    return true;
  } catch {
    return false;
  }
};

/**
 * Hook exposing the native bulk-upload sync layer's live status.
 *
 * Seeds `pending` via `getPendingCount()` on mount, then keeps `pending`,
 * `lastResult`, `lastError`, and `authBlocked` up to date by listening for
 * the native `onSyncStatusChanged` event - fired after every completed
 * sync attempt (from any of the native triggers, or `forceSync()`), and
 * also when `setAuthToken()` clears an existing auth-block without a new
 * sync attempt running.
 *
 * `lastResult` / `lastError` / `authBlocked` have no seed call (the
 * TurboModule spec intentionally has no "get current status" method - see
 * `NativeBackgroundLocation.ts`), so they start at their defaults (`null` /
 * `null` / `false`) until the first event arrives.
 *
 * @example
 * ```tsx
 * function SyncPanel() {
 *   const { pending, lastResult, lastError, authBlocked, refresh } = useSyncStatus();
 *
 *   return (
 *     <View>
 *       <Text>Pending: {pending ?? '...'}</Text>
 *       {authBlocked && <Text>Auth blocked - update the token</Text>}
 *       {lastError && <Text>Last error: {lastError}</Text>}
 *       <Button title="Refresh" onPress={refresh} />
 *     </View>
 *   );
 * }
 * ```
 */
export function useSyncStatus() {
  const [pending, setPending] = useState(null);
  const [lastResult, setLastResult] = useState(null);
  const [lastError, setLastError] = useState(null);
  const [authBlocked, setAuthBlocked] = useState(false);

  /**
   * Re-fetches `pending` from native. Exposed to callers as a manual
   * refresh; also used internally to seed `pending` on mount.
   */
  const refresh = useCallback(async () => {
    if (!isNativeModuleAvailable()) {
      console.warn('BackgroundLocation not available - running in simulator or module not linked?');
      return;
    }
    try {
      const count = await BackgroundLocationModule.getPendingCount();
      setPending(count);
    } catch (err) {
      console.error('useSyncStatus: failed to fetch pending count', err);
    }
  }, []);

  // Seed `pending` on mount.
  useEffect(() => {
    refresh();
  }, [refresh]);

  // Listen for live status updates.
  useEffect(() => {
    if (!isNativeModuleAvailable()) {
      return;
    }

    // Pass native module to NativeEventEmitter (required on iOS, optional on Android)
    const eventEmitter = new NativeEventEmitter(BackgroundLocationModule);
    const subscription = eventEmitter.addListener('onSyncStatusChanged', event => {
      const statusEvent = event;
      const {
        pending: pendingCount,
        authBlocked: blocked,
        ...result
      } = statusEvent;
      setLastResult(result);
      setLastError(result.error ?? null);
      setAuthBlocked(blocked);
      setPending(pendingCount);
    });
    return () => {
      subscription.remove();
    };
  }, []);
  return useMemo(() => ({
    pending,
    lastResult,
    lastError,
    authBlocked,
    refresh
  }), [pending, lastResult, lastError, authBlocked, refresh]);
}
//# sourceMappingURL=useSyncStatus.js.map