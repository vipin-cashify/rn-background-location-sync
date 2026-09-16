import type { UseLocationUpdatesOptions, UseLocationUpdatesResult } from '../types';
/**
 * Hook to watch location updates in real-time
 *
 * This hook automatically listens for location updates from the background service
 * and provides them as they arrive, without requiring manual refresh.
 *
 * @param options - Configuration options
 *
 * @example
 * ```tsx
 * function LiveTrackingMap() {
 *   const {
 *     locations,
 *     lastLocation,
 *     isTracking
 *   } = useLocationUpdates({
 *     onLocationUpdate: (location) => {
 *       console.log('New location:', location);
 *     },
 *   });
 *
 *   return (
 *     <View>
 *       <Text>Tracking: {isTracking ? 'Active' : 'Inactive'}</Text>
 *       <Text>Locations collected: {locations.length}</Text>
 *       {lastLocation && (
 *         <Text>
 *           Last: {lastLocation.latitude}, {lastLocation.longitude}
 *         </Text>
 *       )}
 *     </View>
 *   );
 * }
 * ```
 */
export declare function useLocationUpdates(options?: UseLocationUpdatesOptions): UseLocationUpdatesResult;
//# sourceMappingURL=useLocationUpdates.d.ts.map