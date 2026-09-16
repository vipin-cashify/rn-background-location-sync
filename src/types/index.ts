/**
 * Centralized type exports for react-native-background-location
 */

// Enums
export {
  LocationPermissionStatus,
  LocationAccuracy,
  LocationActivityType,
  NotificationPriority,
  NotificationPermissionStatus,
} from './enums';

// Tracking types
export type {
  Coords,
  TrackingStatus,
  LocationUpdateEvent,
  LocationWarningEvent,
  LocationWarningType,
  TrackingOptions,
  NotificationAction,
  NotificationActionEvent,
} from './tracking';

// Permission types
export type {
  LocationPermissionState,
  NotificationPermissionState,
  PermissionState,
  UseLocationPermissionsResult,
  PermissionRationale,
  RequestPermissionsOptions,
} from './permissions';

// Hook types
export type {
  UseBackgroundLocationResult,
  UseLocationTrackingOptions,
  UseLocationUpdatesOptions,
  UseLocationUpdatesResult,
  UseSyncStatusResult,
} from './hooks';

// Sync types
export type { SyncConfig, SyncResult, SyncStatusEvent } from './sync';

// Notification types
export type { NotificationOptions } from './notifications';
export { GEOFENCE_TEMPLATE_VARS } from './notifications';

// Geofencing types
export { GeofenceTransitionType, GeofenceErrorCode } from './geofencing';

export type { GeofenceRegion, GeofenceTransitionEvent } from './geofencing';
