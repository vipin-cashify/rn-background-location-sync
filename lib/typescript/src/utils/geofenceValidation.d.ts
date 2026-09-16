import type { GeofenceRegion } from '../types/geofencing';
/**
 * Validates a geofence region's parameters before sending it to the native
 * layer. Throws a descriptive `Error` if any constraint is violated.
 *
 * Constraints enforced:
 *  - `identifier` must be a non-empty string (after trimming)
 *  - `latitude` in [-90, 90]
 *  - `longitude` in [-180, 180]
 *  - `radius` >= 100 meters
 *  - `loiteringDelay`, when provided, must be non-negative
 *
 * @internal
 */
export declare function validateGeofenceRegion(region: GeofenceRegion): void;
//# sourceMappingURL=geofenceValidation.d.ts.map