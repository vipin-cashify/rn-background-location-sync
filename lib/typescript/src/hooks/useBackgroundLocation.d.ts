import type { UseBackgroundLocationResult, UseLocationTrackingOptions } from '../types';
/**
 * Hook to manage background location tracking
 *
 * Provides a complete interface for starting/stopping tracking,
 * managing trip data, and handling locations.
 *
 * @param options - Configuration options
 *
 * @example
 * ```tsx
 * function TrackingScreen() {
 *   const {
 *     isTracking,
 *     tripId,
 *     locations,
 *     startTracking,
 *     stopTracking,
 *     error
 *   } = useBackgroundLocation({
 *     autoStart: false,
 *     onTrackingStart: (id) => console.log('Started:', id),
 *     onError: (err) => console.error(err),
 *   });
 *
 *   return (
 *     <View>
 *       <Button onPress={startTracking}>
 *         {isTracking ? 'Stop' : 'Start'} Tracking
 *       </Button>
 *       <Text>Locations: {locations.length}</Text>
 *     </View>
 *   );
 * }
 * ```
 */
export declare function useBackgroundLocation(options?: UseLocationTrackingOptions): UseBackgroundLocationResult;
//# sourceMappingURL=useBackgroundLocation.d.ts.map