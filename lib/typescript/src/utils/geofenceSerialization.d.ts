import type { GeofenceRegion } from '../types/geofencing';
/**
 * Prepares a geofence region as a plain object ready for JSON serialization
 * by the native layer.
 *
 * Handles:
 *  - `notificationOptions === false` → `{ enabled: false }` override
 *  - `notificationOptions === undefined` → property omitted entirely
 *  - Default `transitionTypes` of `[ENTER, EXIT]` when none supplied
 *  - Default `loiteringDelay` of 30000 ms
 *  - Stringifies `transitionTypes` so the native side can parse them
 *
 * @internal
 */
export declare function prepareGeofenceRegion(region: GeofenceRegion): Record<string, unknown>;
/**
 * Serializes a single geofence region to a JSON string for calls that send
 * one region to the native layer.
 *
 * @internal
 */
export declare function serializeGeofenceRegion(region: GeofenceRegion): string;
//# sourceMappingURL=geofenceSerialization.d.ts.map