/**
 * Types for the bulk-upload sync layer (`LocationSyncManager`, Phase 3b/3c).
 *
 * See `modules/rn-background-location-sync/.superpowers/.../task-6-report.md`
 * (native layer) for the authoritative field semantics this mirrors.
 */

/**
 * Configuration for the sync layer's upload endpoint and batching/retention
 * behavior. Passed to `configureSync()`.
 */
export interface SyncConfig {
  /**
   * Bulk-upload endpoint. Required - the native layer no-ops (with a logged
   * warning) until this is set.
   */
  syncUrl: string;
  /**
   * Max rows read from the pending queue per upload request.
   * @default 500 (native default)
   */
  batchSize?: number;
  /**
   * Hard cap on total queued rows (oldest rows are trimmed past this).
   * @default native default (10,000)
   */
  maxQueueRows?: number;
  /**
   * Age (days) after which a synced row becomes eligible for deletion.
   * @default native default (7)
   */
  maxAgeDays?: number;
  /**
   * Extra headers sent on every bulk-upload request, in addition to the
   * auth headers set via `setAuthToken` (`x-authorization`) / `setSsoToken`
   * (`x-sso-token`) and `Content-Type`. Do not put either auth header here.
   */
  headers?: Record<string, string>;
}

/**
 * Outcome of one sync attempt - the resolved value of `forceSync()`, and
 * (flattened together with a couple of live-status fields) the payload of
 * the `onSyncStatusChanged` event - see {@link SyncStatusEvent}.
 *
 * **Codegen note:** `forceSync(): Promise<SyncResult>` in
 * `NativeBackgroundLocation.ts` imports this interface rather than
 * declaring it inline (unlike `SyncConfigSpec`/`PermissionStatusResult`,
 * which the Spec file's own comments flag as "must be defined inline for
 * Codegen compatibility"). Because of that, RN Codegen does not actually
 * resolve `SyncResult`'s field shape here - Android's generated
 * `NativeBackgroundLocationSpec.java` (confirmed by inspecting
 * `android/build/generated/source/codegen/schema.json`) just declares
 * `forceSync(Promise promise)` with no compile-time-checked return shape,
 * exactly like every other Promise-returning method in this Spec whose
 * declared type is imported rather than inline (e.g. `Coords`,
 * `TrackingStatus`). The real bridging is hand-written (see
 * `BackgroundLocationModule.kt`'s `Arguments.createMap()` calls), so this
 * interface is a JS-consumer-facing accuracy contract only - changing a
 * field's nullability here (as below) is safe with respect to Codegen,
 * since Codegen was never validating it in the first place. This is a
 * pre-existing Codegen limitation of the whole Spec file, not something
 * introduced or fixed by this comment.
 */
export interface SyncResult {
  /** Rows successfully uploaded in this run. */
  uploaded: number;
  /**
   * True if the run stopped early due to a network/HTTP error or an auth
   * block. False for a clean drain, including the "nothing to do" no-op
   * cases (no `syncUrl` configured, backoff window active).
   */
  failed: boolean;
  /**
   * HTTP status code that caused the run to stop (401, or a failing
   * 4xx/5xx). `null` on a clean drain or when no HTTP request was made -
   * the native side (Android) always includes this key, explicitly writing
   * `null` rather than omitting it, so a strict `=== null` check is valid;
   * still declared optional (`?`) too, purely for the convenience of
   * hand-authored test fixtures/partial objects that don't care about this
   * field one way or the other.
   */
  httpCode?: number | null;
  /**
   * Short machine-readable reason when nothing was uploaded or the run
   * stopped early (e.g. `"NO_SYNC_URL"`, `"AUTH_BLOCKED"`,
   * `"BACKOFF_ACTIVE"`, `"HTTP_401"`, `"HTTP_500"`). `null` on a clean
   * drain - see `httpCode`'s doc above for why this is `| null` (not just
   * optional) and still optional at the same time.
   */
  error?: string | null;
  /** Not-yet-uploaded row count, measured at the end of this run. */
  pendingAfter: number;
  /** Epoch millis when the run finished. */
  timestamp: number;
}

/**
 * Payload of the native `onSyncStatusChanged` event: a {@link SyncResult}
 * plus the current pending count (same value as `pendingAfter` - a
 * friendlier name for a live-status field) and the current auth-block
 * state. `authBlocked` is included separately from `error` because it can
 * change (e.g. cleared by `setAuthToken`) without a new sync attempt ever
 * running, so it would otherwise go stale between events.
 */
export interface SyncStatusEvent extends SyncResult {
  /** Current pending (not-yet-uploaded) row count. */
  pending: number;
  /** Whether the sync layer is currently blocked on a 401 (see `setAuthToken`). */
  authBlocked: boolean;
}
