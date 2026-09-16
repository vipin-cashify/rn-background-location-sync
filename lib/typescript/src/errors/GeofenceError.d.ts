import { GeofenceErrorCode } from '../types/geofencing';
/**
 * Error class for geofencing-specific failures.
 *
 * Carries a structured {@link GeofenceErrorCode} so callers can branch on
 * the failure reason (e.g. duplicate identifier vs. platform limit reached)
 * without string-matching the message.
 *
 * @example
 * ```ts
 * try {
 *   await addGeofence(region);
 * } catch (err) {
 *   if (err instanceof GeofenceError && err.code === GeofenceErrorCode.DUPLICATE_IDENTIFIER) {
 *     // handle duplicate
 *   }
 * }
 * ```
 */
export declare class GeofenceError extends Error {
    code: GeofenceErrorCode;
    constructor(code: GeofenceErrorCode, message: string);
}
//# sourceMappingURL=GeofenceError.d.ts.map