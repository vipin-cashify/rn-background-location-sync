import type { GeofenceTransitionEvent, GeofenceTransitionType } from '../types';
/**
 * Configuration options for the useGeofenceEvents hook
 */
export interface UseGeofenceEventsOptions {
    /** Callback invoked when a geofence transition is detected (after filters are applied) */
    onTransition?: (event: GeofenceTransitionEvent) => void;
    /** Only emit events matching these transition types. If omitted, all transitions are emitted. */
    filter?: GeofenceTransitionType[];
    /** Only emit events for this specific geofence identifier. If omitted, all geofences are emitted. */
    geofenceId?: string;
}
/**
 * Hook to listen for geofence transition events in real-time
 *
 * Subscribes to native geofence transition events via NativeEventEmitter
 * and applies optional filters before invoking the callback.
 *
 * @param options - Configuration options with callback and optional filters
 *
 * @example
 * ```tsx
 * function GeofenceMonitor() {
 *   useGeofenceEvents({
 *     onTransition: (event) => {
 *       console.log(`${event.transitionType} geofence ${event.geofenceId}`);
 *     },
 *     filter: [GeofenceTransitionType.ENTER, GeofenceTransitionType.EXIT],
 *   });
 *
 *   return <Text>Monitoring geofence transitions...</Text>;
 * }
 *
 * @example
 * // Filter by specific geofence
 * useGeofenceEvents({
 *   onTransition: (event) => {
 *     console.log('Office transition:', event.transitionType);
 *   },
 *   geofenceId: 'office-hq',
 * });
 * ```
 */
export declare function useGeofenceEvents(options?: UseGeofenceEventsOptions): void;
//# sourceMappingURL=useGeofenceEvents.d.ts.map