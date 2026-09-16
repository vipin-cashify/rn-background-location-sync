"use strict";

import BackgroundLocationModule from "./NativeBackgroundLocation.js";
import { LocationPermissionStatus as LocationPermissionStatusEnum, LocationAccuracy as LocationAccuracyEnum, LocationActivityType as LocationActivityTypeEnum, NotificationPriority as NotificationPriorityEnum, NotificationPermissionStatus as NotificationPermissionStatusEnum } from "./types/enums.js";
import { GeofenceErrorCode } from "./types/geofencing.js";
import { assertNativeModuleAvailable, isNativeModuleAvailable, toTrackingOptionsSpec, validateGeofenceRegion, prepareGeofenceRegion, serializeGeofenceRegion } from "./utils/index.js";
import { GeofenceError } from "./errors/index.js";

// Export types

// Sync type exports

// Geofencing type exports

export { GeofenceTransitionType, GeofenceErrorCode } from "./types/index.js";

// Notification type exports

export { GEOFENCE_TEMPLATE_VARS } from "./types/index.js";

// Export enums (as values + companion types for type annotations)
// Import and re-export as named exports to ensure they're available at runtime
export const LocationPermissionStatus = LocationPermissionStatusEnum;
export const LocationAccuracy = LocationAccuracyEnum;
export const LocationActivityType = LocationActivityTypeEnum;
export const NotificationPriority = NotificationPriorityEnum;
export const NotificationPermissionStatus = NotificationPermissionStatusEnum;
// Export errors
export { GeofenceError } from "./errors/index.js";

// Export hooks
export { useLocationPermissions, useBackgroundLocation, useLocationTracking, useLocationUpdates, useGeofencing, useGeofenceEvents, useGeofencePermissions, useSyncStatus } from "./hooks/index.js";

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
export function startTracking(tripIdOrOptions, options) {
  if (!isNativeModuleAvailable()) {
    console.warn('BackgroundLocation not available - running in simulator or module not linked?');
    const fallbackTripId = typeof tripIdOrOptions === 'string' ? tripIdOrOptions : `simulator-trip-${Date.now()}`;
    return Promise.resolve(fallbackTripId);
  }

  // Handle overload: startTracking(options?) or startTracking(tripId?, options?)
  let tripId;
  let trackingOptions;
  if (typeof tripIdOrOptions === 'object') {
    // Called as startTracking(options)
    tripId = undefined;
    trackingOptions = tripIdOrOptions;
  } else {
    // Called as startTracking(tripId?, options?)
    tripId = tripIdOrOptions;
    trackingOptions = options;
  }
  return BackgroundLocationModule.startTracking(tripId, toTrackingOptionsSpec(trackingOptions));
}

/**
 * Stops all location tracking and terminates the background service.
 * @returns Promise that resolves when tracking is stopped
 */
export function stopTracking() {
  if (!isNativeModuleAvailable()) {
    console.warn('BackgroundLocation not available - running in simulator or module not linked?');
    return Promise.resolve();
  }
  return BackgroundLocationModule.stopTracking();
}

/**
 * Checks if location tracking is currently active.
 * @returns Promise resolving to object with active status and current tripId if tracking
 */
export function isTracking() {
  if (!isNativeModuleAvailable()) {
    console.warn('BackgroundLocation not available - running in simulator or module not linked?');
    return Promise.resolve({
      active: false
    });
  }
  return BackgroundLocationModule.isTracking();
}

/**
 * Retrieves all stored location points for a specific trip.
 * @param tripId The trip identifier
 * @returns Promise resolving to array of location coordinates with extended location data
 */
export function getLocations(tripId) {
  if (!isNativeModuleAvailable()) {
    console.warn('BackgroundLocation not available - running in simulator or module not linked?');
    return Promise.resolve([]);
  }
  return BackgroundLocationModule.getLocations(tripId);
}

/**
 * Clears all stored location data for a specific trip.
 * @param tripId The trip identifier to clear
 * @returns Promise that resolves when data is cleared
 */
export function clearTrip(tripId) {
  if (!isNativeModuleAvailable()) {
    console.warn('BackgroundLocation not available - running in simulator or module not linked?');
    return Promise.resolve();
  }
  return BackgroundLocationModule.clearTrip(tripId);
}

/**
 * Updates the notification content while tracking is active.
 * Dynamic updates are transient and do not persist across service restarts.
 * @param title New notification title
 * @param text New notification text
 * @returns Promise that resolves when notification is updated
 */
export function updateNotification(title, text) {
  if (!isNativeModuleAvailable()) {
    console.warn('BackgroundLocation not available - running in simulator or module not linked?');
    return Promise.resolve();
  }
  return BackgroundLocationModule.updateNotification(title, text);
}

// --- Geofence Notification Configuration ---

/**
 * Configures global notification options for geofence transitions.
 * Configuration persists across app restarts (SharedPreferences/UserDefaults).
 * Applies to all future transitions. Already-fired transitions are unaffected.
 */
export async function configureGeofenceNotifications(options) {
  assertNativeModuleAvailable();
  const json = JSON.stringify(options);
  return BackgroundLocationModule.configureGeofenceNotifications(json);
}

/**
 * Retrieves the current geofence notification configuration.
 * Returns an empty object if no configuration has been set.
 */
export async function getGeofenceNotificationConfig() {
  assertNativeModuleAvailable();
  const json = await BackgroundLocationModule.getGeofenceNotificationConfig();
  return JSON.parse(json);
}

// --- Geofencing ---

/**
 * Registers a single geofence region for monitoring
 * @param region The geofence region to register
 * @throws {GeofenceError} If a geofence with the same identifier already exists
 */
export async function addGeofence(region) {
  assertNativeModuleAvailable();
  validateGeofenceRegion(region);
  const active = await getActiveGeofences();
  if (active.some(g => g.identifier === region.identifier)) {
    throw new GeofenceError(GeofenceErrorCode.DUPLICATE_IDENTIFIER, `Geofence with identifier "${region.identifier}" already exists`);
  }
  const json = serializeGeofenceRegion(region);
  return BackgroundLocationModule.addGeofence(json);
}

/**
 * Registers multiple geofence regions atomically (all-or-nothing)
 * @param regions Array of geofence regions to register
 * @throws {GeofenceError} If duplicate identifiers are found within the batch
 */
export async function addGeofences(regions) {
  assertNativeModuleAvailable();
  regions.forEach(validateGeofenceRegion);
  // Check for duplicate identifiers within the batch
  const identifiers = regions.map(r => r.identifier);
  const duplicates = identifiers.filter((id, idx) => identifiers.indexOf(id) !== idx);
  if (duplicates.length > 0) {
    throw new GeofenceError(GeofenceErrorCode.DUPLICATE_IDENTIFIER, `Duplicate identifiers in batch: ${[...new Set(duplicates)].join(', ')}`);
  }
  const json = JSON.stringify(regions.map(prepareGeofenceRegion));
  return BackgroundLocationModule.addGeofences(json);
}

/**
 * Removes a single geofence by identifier
 * @param identifier The geofence identifier to remove
 */
export async function removeGeofence(identifier) {
  assertNativeModuleAvailable();
  return BackgroundLocationModule.removeGeofence(identifier);
}

/**
 * Removes multiple geofences by identifiers
 * @param identifiers Array of geofence identifiers to remove
 */
export async function removeGeofences(identifiers) {
  assertNativeModuleAvailable();
  return BackgroundLocationModule.removeGeofences(JSON.stringify(identifiers));
}

/**
 * Removes all registered geofences
 */
export async function removeAllGeofences() {
  assertNativeModuleAvailable();
  return BackgroundLocationModule.removeAllGeofences();
}

/**
 * Returns all currently active geofences
 * @returns Array of active geofence regions
 */
export async function getActiveGeofences() {
  assertNativeModuleAvailable();
  const json = await BackgroundLocationModule.getActiveGeofences();
  return JSON.parse(json);
}

/**
 * Returns the maximum number of geofences supported by the platform
 * @returns Platform limit (Android: 100, iOS: 20)
 */
export async function getMaxGeofences() {
  assertNativeModuleAvailable();
  return BackgroundLocationModule.getMaxGeofences();
}

/**
 * Retrieves stored geofence transition events
 * @param identifier Optional geofence identifier to filter by. If omitted, returns all transitions.
 * @returns Array of geofence transition events
 */
export async function getGeofenceTransitions(identifier) {
  assertNativeModuleAvailable();
  const json = await BackgroundLocationModule.getGeofenceTransitions(identifier);
  return JSON.parse(json);
}

/**
 * Clears stored geofence transition events
 * @param identifier Optional geofence identifier to clear. If omitted, clears all transitions.
 */
export async function clearGeofenceTransitions(identifier) {
  assertNativeModuleAvailable();
  return BackgroundLocationModule.clearGeofenceTransitions(identifier);
}

// --- Sync ---

/**
 * Configures the bulk-upload sync endpoint and batching/retention behavior.
 * `headers`, if provided, is JSON-serialized for the native side (Codegen
 * cannot express maps - see `SyncConfigSpec` in `NativeBackgroundLocation.ts`).
 * @param config Sync configuration
 * @throws If the native module is not available
 */
export async function configureSync(config) {
  assertNativeModuleAvailable();
  return BackgroundLocationModule.configureSync({
    syncUrl: config.syncUrl,
    batchSize: config.batchSize,
    maxQueueRows: config.maxQueueRows,
    maxAgeDays: config.maxAgeDays,
    headersJson: config.headers ? JSON.stringify(config.headers) : undefined
  });
}

/**
 * Sets (or clears, with an empty string) the bearer token used for sync
 * uploads. A non-blank token also clears any existing 401 auth-block (see
 * Task 6's `LocationSyncManager.setToken`).
 * @param token The bearer token, or an empty string to clear it.
 * @throws If the native module is not available
 */
export async function setAuthToken(token) {
  assertNativeModuleAvailable();
  return BackgroundLocationModule.setAuthToken(token);
}

/**
 * Sets (or clears, with an empty string) the `x-sso-token` value used for
 * sync uploads (the raw user SSO JWT, sent without a "Bearer" prefix,
 * alongside the `x-authorization` bearer token from {@link setAuthToken}).
 * A non-blank value also clears any existing 401 auth-block.
 * @param token The SSO token, or an empty string to clear it.
 * @throws If the native module is not available
 */
export async function setSsoToken(token) {
  assertNativeModuleAvailable();
  return BackgroundLocationModule.setSsoToken(token);
}

/**
 * Returns the number of not-yet-uploaded rows currently queued for sync.
 * @throws If the native module is not available
 */
export async function getPendingCount() {
  assertNativeModuleAvailable();
  return BackgroundLocationModule.getPendingCount();
}

/**
 * Triggers an immediate sync attempt (bypassing the location-insert/
 * connectivity/worker triggers) and returns its outcome once it completes.
 * @throws If the native module is not available
 */
export async function forceSync() {
  assertNativeModuleAvailable();
  return BackgroundLocationModule.forceSync();
}
//# sourceMappingURL=index.js.map