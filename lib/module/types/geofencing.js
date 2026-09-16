"use strict";

/**
 * Geofencing types for react-native-background-location
 */

/**
 * Type of geofence transition detected
 */
export let GeofenceTransitionType = /*#__PURE__*/function (GeofenceTransitionType) {
  /** Device entered the geofence region */
  GeofenceTransitionType["ENTER"] = "ENTER";
  /** Device exited the geofence region */
  GeofenceTransitionType["EXIT"] = "EXIT";
  /** Device dwelled in the geofence region for the configured duration */
  GeofenceTransitionType["DWELL"] = "DWELL";
  return GeofenceTransitionType;
}({});

/**
 * Error codes specific to geofencing operations
 */
export let GeofenceErrorCode = /*#__PURE__*/function (GeofenceErrorCode) {
  /** Invalid region parameters (coordinates or radius) */
  GeofenceErrorCode["INVALID_REGION"] = "INVALID_REGION";
  /** Geofence identifier already registered */
  GeofenceErrorCode["DUPLICATE_IDENTIFIER"] = "DUPLICATE_IDENTIFIER";
  /** Platform geofence limit exceeded */
  GeofenceErrorCode["LIMIT_EXCEEDED"] = "LIMIT_EXCEEDED";
  /** Native monitoring failed to start */
  GeofenceErrorCode["MONITORING_FAILED"] = "MONITORING_FAILED";
  /** Native module not available */
  GeofenceErrorCode["NOT_AVAILABLE"] = "NOT_AVAILABLE";
  /** Insufficient location permissions */
  GeofenceErrorCode["PERMISSION_DENIED"] = "PERMISSION_DENIED";
  /** Google Play Services unavailable (Android only) */
  GeofenceErrorCode["PLAY_SERVICES_UNAVAILABLE"] = "PLAY_SERVICES_UNAVAILABLE";
  return GeofenceErrorCode;
}({});

/**
 * Defines a circular geofence region
 */

/**
 * Event emitted when a geofence transition is detected
 */
//# sourceMappingURL=geofencing.js.map