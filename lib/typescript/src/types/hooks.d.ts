/**
 * Types and interfaces for React hooks
 */
import type { Coords, TrackingOptions, LocationWarningEvent, NotificationActionEvent } from './tracking';
import type { SyncResult } from './sync';
/**
 * Result type for useBackgroundLocation hook
 */
export interface UseBackgroundLocationResult {
    /**
     * Current trip ID if tracking is active
     */
    tripId: string | null;
    /**
     * Whether location tracking is currently active
     */
    isTracking: boolean;
    /**
     * All locations collected for the current trip
     */
    locations: Coords[];
    /**
     * Whether an operation is in progress
     */
    isLoading: boolean;
    /**
     * Last error that occurred
     */
    error: Error | null;
    /**
     * Start tracking with optional custom trip ID and options
     */
    startTracking: (customTripId?: string, options?: TrackingOptions) => Promise<string | null>;
    /**
     * Stop tracking
     */
    stopTracking: () => Promise<void>;
    /**
     * Refresh locations for current trip
     */
    refreshLocations: () => Promise<void>;
    /**
     * Clear all data for current trip
     */
    clearCurrentTrip: () => Promise<void>;
    /**
     * Clear error state
     */
    clearError: () => void;
}
/**
 * Options for useLocationTracking hook
 */
export interface UseLocationTrackingOptions {
    /**
     * Automatically start tracking when component mounts
     * @default false
     */
    autoStart?: boolean;
    /**
     * Custom trip ID to use
     */
    tripId?: string;
    /**
     * Tracking configuration options
     */
    options?: TrackingOptions;
    /**
     * Callback when tracking starts
     */
    onTrackingStart?: (tripId: string) => void;
    /**
     * Callback when tracking stops
     */
    onTrackingStop?: () => void;
    /**
     * Callback when error occurs
     */
    onError?: (error: Error) => void;
}
/**
 * Options for useLocationUpdates hook
 */
export interface UseLocationUpdatesOptions {
    /**
     * Specific trip ID to watch
     * If not provided, watches updates for any active trip
     */
    tripId?: string;
    /**
     * Callback when a new location is received
     */
    onLocationUpdate?: (location: Coords) => void;
    /**
     * Interval in milliseconds to throttle the onLocationUpdate callback execution
     * Locations are still collected and stored, but the callback is only executed at this interval.
     * Useful for syncing to servers without overwhelming the network.
     * @default undefined (callback called on every location update)
     */
    onUpdateInterval?: number;
    /**
     * Callback when a warning is received from the location service
     * Warnings include: SERVICE_TIMEOUT, TASK_REMOVED, LOCATION_UNAVAILABLE
     */
    onLocationWarning?: (warning: LocationWarningEvent) => void;
    /**
     * Callback when a notification action button is pressed
     */
    onNotificationAction?: (event: NotificationActionEvent) => void;
    /**
     * Whether to automatically load existing locations on mount
     * @default true
     */
    autoLoad?: boolean;
}
/**
 * Result type for useLocationUpdates hook
 */
export interface UseLocationUpdatesResult {
    /**
     * Current trip ID being watched
     */
    tripId: string | null;
    /**
     * Whether location tracking is currently active
     */
    isTracking: boolean;
    /**
     * All locations received for the current trip
     * Updates automatically as new locations arrive
     */
    locations: Coords[];
    /**
     * The most recent location received
     */
    lastLocation: Coords | null;
    /**
     * The most recent warning from the location service
     * Includes warnings like SERVICE_TIMEOUT, TASK_REMOVED, LOCATION_UNAVAILABLE
     */
    lastWarning: LocationWarningEvent | null;
    /**
     * Whether data is being loaded
     */
    isLoading: boolean;
    /**
     * Last error that occurred
     */
    error: Error | null;
    /**
     * Clear error state
     */
    clearError: () => void;
    /**
     * Clear all locations for current trip
     */
    clearLocations: () => Promise<void>;
    /**
     * Manually refresh locations from the database.
     * Useful for on-demand sync after returning from background or pull-to-refresh scenarios.
     * No-op if no tripId is currently set.
     */
    refreshLocations: () => Promise<void>;
}
/**
 * Result type for the useSyncStatus hook.
 *
 * `pending` is seeded from `getPendingCount()` on mount. `lastResult`,
 * `lastError`, and `authBlocked` have no seed call (the TurboModule spec
 * has no "get current status" method) - they start at their defaults
 * (`null` / `null` / `false`) until the first `onSyncStatusChanged` event
 * arrives.
 */
export interface UseSyncStatusResult {
    /** Not-yet-uploaded row count. Null until the first `getPendingCount()` resolves. */
    pending: number | null;
    /** Outcome of the last completed sync attempt, or null if none has happened yet. */
    lastResult: SyncResult | null;
    /**
     * `lastResult?.error ?? null` - a convenience accessor so callers don't
     * need to null-check `lastResult` just to show the last error message.
     */
    lastError: string | null;
    /** Whether the sync layer is currently blocked on a 401 (see `setAuthToken`). */
    authBlocked: boolean;
    /** Re-fetches `pending` from native. */
    refresh: () => Promise<void>;
}
//# sourceMappingURL=hooks.d.ts.map