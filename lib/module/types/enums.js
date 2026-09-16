"use strict";

/**
 * Enums for react-native-background-location
 */

/**
 * Location permission status
 */
export let LocationPermissionStatus = /*#__PURE__*/function (LocationPermissionStatus) {
  LocationPermissionStatus["GRANTED"] = "granted";
  LocationPermissionStatus["WHEN_IN_USE"] = "whenInUse";
  LocationPermissionStatus["DENIED"] = "denied";
  LocationPermissionStatus["BLOCKED"] = "blocked";
  LocationPermissionStatus["UNDETERMINED"] = "undetermined";
  return LocationPermissionStatus;
}({});

/**
 * Location accuracy priority levels
 */
export let LocationAccuracy = /*#__PURE__*/function (LocationAccuracy) {
  /**
   * Highest accuracy - uses GPS and other sensors
   * Best for navigation and precise tracking
   * Higher battery consumption
   */
  LocationAccuracy["HIGH_ACCURACY"] = "HIGH_ACCURACY";
  /**
   * Balanced accuracy and power consumption
   * Good for most tracking use cases
   */
  LocationAccuracy["BALANCED_POWER_ACCURACY"] = "BALANCED_POWER_ACCURACY";
  /**
   * Low power consumption
   * Uses network-based location
   * Lower accuracy
   */
  LocationAccuracy["LOW_POWER"] = "LOW_POWER";
  /**
   * No power consumption
   * Only receives location updates when other apps request them
   * Very low accuracy
   */
  LocationAccuracy["NO_POWER"] = "NO_POWER";
  /**
   * Passive location updates
   * Receives location updates from other apps
   * No additional power consumption
   */
  LocationAccuracy["PASSIVE"] = "PASSIVE";
  return LocationAccuracy;
}({});

/**
 * Notification priority levels for Android
 */
export let NotificationPriority = /*#__PURE__*/function (NotificationPriority) {
  /**
   * Low priority - minimal notification
   */
  NotificationPriority["LOW"] = "LOW";
  /**
   * Default priority
   */
  NotificationPriority["DEFAULT"] = "DEFAULT";
  /**
   * High priority - more prominent notification
   */
  NotificationPriority["HIGH"] = "HIGH";
  /**
   * Maximum priority - urgent notification
   */
  NotificationPriority["MAX"] = "MAX";
  return NotificationPriority;
}({});

/**
 * Notification permission status
 */
export let NotificationPermissionStatus = /*#__PURE__*/function (NotificationPermissionStatus) {
  NotificationPermissionStatus["GRANTED"] = "granted";
  NotificationPermissionStatus["DENIED"] = "denied";
  NotificationPermissionStatus["UNDETERMINED"] = "undetermined";
  return NotificationPermissionStatus;
}({});

/**
 * iOS activity type hint passed to `CLLocationManager.activityType`.
 * Influences iOS's motion-classification subsystem and decisions about
 * when to auto-pause location updates to save battery.
 *
 * @default LocationActivityType.OTHER
 * @platform iOS
 */
export let LocationActivityType = /*#__PURE__*/function (LocationActivityType) {
  /** Default. General-purpose tracking, no motion-classification bias. Maps to `CLActivityType.other`. */
  LocationActivityType["OTHER"] = "OTHER";
  /** Vehicle navigation (turn-by-turn). Maps to `CLActivityType.automotiveNavigation`. */
  LocationActivityType["AUTOMOTIVE_NAVIGATION"] = "AUTOMOTIVE_NAVIGATION";
  /** Foot-based activity (walking, running, cycling). Maps to `CLActivityType.fitness`. */
  LocationActivityType["FITNESS"] = "FITNESS";
  /** Non-vehicle navigation (trains, boats). Maps to `CLActivityType.otherNavigation`. */
  LocationActivityType["OTHER_NAVIGATION"] = "OTHER_NAVIGATION";
  /** Aerial activity. Maps to `CLActivityType.airborne` (iOS 12+). */
  LocationActivityType["AIRBORNE"] = "AIRBORNE";
  return LocationActivityType;
}({});
//# sourceMappingURL=enums.js.map