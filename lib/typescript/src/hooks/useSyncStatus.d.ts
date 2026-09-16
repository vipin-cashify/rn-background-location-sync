import type { UseSyncStatusResult } from '../types';
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
export declare function useSyncStatus(): UseSyncStatusResult;
//# sourceMappingURL=useSyncStatus.d.ts.map