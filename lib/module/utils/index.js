"use strict";

/**
 * Barrel export for internal utility helpers used across the public API.
 *
 * Keeping these in a single module lets `src/index.tsx` stay a lean facade
 * over the TurboModule bridge while still sharing logic with tests and
 * hooks.
 */

export { assertNativeModuleAvailable } from "./moduleCheck.js";
export { isNativeModuleAvailable } from "./isNativeModuleAvailable.js";
export { extractDefinedProperties } from "./objectUtils.js";
export { toTrackingOptionsSpec } from "./trackingOptionsMapper.js";
export { validateGeofenceRegion } from "./geofenceValidation.js";
export { prepareGeofenceRegion, serializeGeofenceRegion } from "./geofenceSerialization.js";
export { resolveRationale } from "./resolveRationale.js";
//# sourceMappingURL=index.js.map