import type { TrackingOptions, TrackingStatus, Coords, SyncConfig, SyncResult } from './types';
import { LocationPermissionStatus as LocationPermissionStatusEnum, LocationAccuracy as LocationAccuracyEnum, LocationActivityType as LocationActivityTypeEnum, NotificationPriority as NotificationPriorityEnum, NotificationPermissionStatus as NotificationPermissionStatusEnum } from './types/enums';
import type { GeofenceRegion, GeofenceTransitionEvent } from './types/geofencing';
import type { NotificationOptions } from './types/notifications';
export type { Coords, TrackingStatus, LocationUpdateEvent, TrackingOptions, } from './NativeBackgroundLocation';
export type { LocationPermissionState, NotificationPermissionState, PermissionState, UseLocationPermissionsResult, UseBackgroundLocationResult, UseLocationTrackingOptions, UseLocationUpdatesOptions, UseLocationUpdatesResult, LocationWarningEvent, LocationWarningType, NotificationAction, NotificationActionEvent, PermissionRationale, RequestPermissionsOptions, } from './types';
export type { UseLocationTrackingResult } from './hooks/useLocationTracking';
export type { UseGeofencingOptions, UseGeofencingReturn } from './hooks';
export type { UseGeofenceEventsOptions } from './hooks';
export type { UseSyncStatusResult } from './hooks';
export type { SyncConfig, SyncResult, SyncStatusEvent } from './types';
export type { GeofenceRegion, GeofenceTransitionEvent } from './types';
export { GeofenceTransitionType, GeofenceErrorCode } from './types';
export type { NotificationOptions } from './types';
export { GEOFENCE_TEMPLATE_VARS } from './types';
export declare const LocationPermissionStatus: typeof LocationPermissionStatusEnum;
export type LocationPermissionStatus = LocationPermissionStatusEnum;
export declare const LocationAccuracy: typeof LocationAccuracyEnum;
export type LocationAccuracy = LocationAccuracyEnum;
export declare const LocationActivityType: typeof LocationActivityTypeEnum;
export type LocationActivityType = LocationActivityTypeEnum;
export declare const NotificationPriority: typeof NotificationPriorityEnum;
export type NotificationPriority = NotificationPriorityEnum;
export declare const NotificationPermissionStatus: typeof NotificationPermissionStatusEnum;
export type NotificationPermissionStatus = NotificationPermissionStatusEnum;
export { GeofenceError } from './errors';
export { useLocationPermissions, useBackgroundLocation, useLocationTracking, useLocationUpdates, useGeofencing, useGeofenceEvents, useGeofencePermissions, useSyncStatus, } from './hooks';
/**
 * Starts location tracking in background for a specific trip.
 *
 * When the native module is not available (e.g. running in a simulator
 * without the module linked), logs a warning and returns a fallback tripId
 * without crashing.
 *
 * @param tripIdOrOptions Optional trip identifier or tracking options. If omitted, a new tripId will be generated
 * @param options Optional tracking configuration options (only used when first param is tripId)
 * @returns Promise resolving to the effective tripId (received or generated)
 */
export declare function startTracking(tripIdOrOptions?: string | TrackingOptions, options?: TrackingOptions): Promise<string>;
/**
 * Stops all location tracking and terminates the background service.
 * @returns Promise that resolves when tracking is stopped
 */
export declare function stopTracking(): Promise<void>;
/**
 * Checks if location tracking is currently active.
 * @returns Promise resolving to object with active status and current tripId if tracking
 */
export declare function isTracking(): Promise<TrackingStatus>;
/**
 * Retrieves all stored location points for a specific trip.
 * @param tripId The trip identifier
 * @returns Promise resolving to array of location coordinates with extended location data
 */
export declare function getLocations(tripId: string): Promise<Coords[]>;
/**
 * Clears all stored location data for a specific trip.
 * @param tripId The trip identifier to clear
 * @returns Promise that resolves when data is cleared
 */
export declare function clearTrip(tripId: string): Promise<void>;
/**
 * Updates the notification content while tracking is active.
 * Dynamic updates are transient and do not persist across service restarts.
 * @param title New notification title
 * @param text New notification text
 * @returns Promise that resolves when notification is updated
 */
export declare function updateNotification(title: string, text: string): Promise<void>;
/**
 * Configures global notification options for geofence transitions.
 * Configuration persists across app restarts (SharedPreferences/UserDefaults).
 * Applies to all future transitions. Already-fired transitions are unaffected.
 */
export declare function configureGeofenceNotifications(options: NotificationOptions): Promise<void>;
/**
 * Retrieves the current geofence notification configuration.
 * Returns an empty object if no configuration has been set.
 */
export declare function getGeofenceNotificationConfig(): Promise<NotificationOptions>;
/**
 * Registers a single geofence region for monitoring
 * @param region The geofence region to register
 * @throws {GeofenceError} If a geofence with the same identifier already exists
 */
export declare function addGeofence(region: GeofenceRegion): Promise<void>;
/**
 * Registers multiple geofence regions atomically (all-or-nothing)
 * @param regions Array of geofence regions to register
 * @throws {GeofenceError} If duplicate identifiers are found within the batch
 */
export declare function addGeofences(regions: GeofenceRegion[]): Promise<void>;
/**
 * Removes a single geofence by identifier
 * @param identifier The geofence identifier to remove
 */
export declare function removeGeofence(identifier: string): Promise<void>;
/**
 * Removes multiple geofences by identifiers
 * @param identifiers Array of geofence identifiers to remove
 */
export declare function removeGeofences(identifiers: string[]): Promise<void>;
/**
 * Removes all registered geofences
 */
export declare function removeAllGeofences(): Promise<void>;
/**
 * Returns all currently active geofences
 * @returns Array of active geofence regions
 */
export declare function getActiveGeofences(): Promise<GeofenceRegion[]>;
/**
 * Returns the maximum number of geofences supported by the platform
 * @returns Platform limit (Android: 100, iOS: 20)
 */
export declare function getMaxGeofences(): Promise<number>;
/**
 * Retrieves stored geofence transition events
 * @param identifier Optional geofence identifier to filter by. If omitted, returns all transitions.
 * @returns Array of geofence transition events
 */
export declare function getGeofenceTransitions(identifier?: string): Promise<GeofenceTransitionEvent[]>;
/**
 * Clears stored geofence transition events
 * @param identifier Optional geofence identifier to clear. If omitted, clears all transitions.
 */
export declare function clearGeofenceTransitions(identifier?: string): Promise<void>;
/**
 * Configures the bulk-upload sync endpoint and batching/retention behavior.
 * `headers`, if provided, is JSON-serialized for the native side (Codegen
 * cannot express maps - see `SyncConfigSpec` in `NativeBackgroundLocation.ts`).
 * @param config Sync configuration
 * @throws If the native module is not available
 */
export declare function configureSync(config: SyncConfig): Promise<void>;
/**
 * Sets (or clears, with an empty string) the bearer token used for sync
 * uploads. A non-blank token also clears any existing 401 auth-block (see
 * Task 6's `LocationSyncManager.setToken`).
 * @param token The bearer token, or an empty string to clear it.
 * @throws If the native module is not available
 */
export declare function setAuthToken(token: string): Promise<void>;
/**
 * Sets (or clears, with an empty string) the `x-sso-token` value used for
 * sync uploads (the raw user SSO JWT, sent without a "Bearer" prefix,
 * alongside the `x-authorization` bearer token from {@link setAuthToken}).
 * A non-blank value also clears any existing 401 auth-block.
 * @param token The SSO token, or an empty string to clear it.
 * @throws If the native module is not available
 */
export declare function setSsoToken(token: string): Promise<void>;
/**
 * Returns the number of not-yet-uploaded rows currently queued for sync.
 * @throws If the native module is not available
 */
export declare function getPendingCount(): Promise<number>;
/**
 * Triggers an immediate sync attempt (bypassing the location-insert/
 * connectivity/worker triggers) and returns its outcome once it completes.
 * @throws If the native module is not available
 */
export declare function forceSync(): Promise<SyncResult>;
//# sourceMappingURL=index.d.ts.map