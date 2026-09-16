import type { GeofenceRegion, NotificationOptions } from '../types';
/**
 * Configuration options for the useGeofencing hook
 */
export interface UseGeofencingOptions {
    /** Whether to automatically load geofences on mount (default: true) */
    autoLoad?: boolean;
    /**
     * Global notification configuration for geofence transitions.
     * When provided, calls configureGeofenceNotifications() on mount.
     * Changes to this object trigger reconfiguration.
     */
    notificationOptions?: NotificationOptions;
}
/**
 * Return type for the useGeofencing hook
 */
export interface UseGeofencingReturn {
    /** Currently active geofence regions */
    geofences: GeofenceRegion[];
    /** Whether an async operation is in progress */
    isLoading: boolean;
    /** Last error that occurred, or null */
    error: Error | null;
    /** Register a single geofence region */
    addGeofence: (region: GeofenceRegion) => Promise<void>;
    /** Register multiple geofence regions atomically */
    addGeofences: (regions: GeofenceRegion[]) => Promise<void>;
    /** Remove a single geofence by identifier */
    removeGeofence: (identifier: string) => Promise<void>;
    /** Remove multiple geofences by identifiers */
    removeGeofences: (identifiers: string[]) => Promise<void>;
    /** Remove all registered geofences */
    removeAllGeofences: () => Promise<void>;
    /** Maximum number of geofences supported by the platform, or null if not yet loaded */
    maxGeofences: number | null;
    /** Reload active geofences and platform limit from native */
    refresh: () => Promise<void>;
    /** Clear the current error state */
    clearError: () => void;
}
/**
 * Hook to manage geofence regions (CRUD operations)
 *
 * Provides a complete interface for adding, removing, and querying geofences
 * with automatic state management, error handling, and loading indicators.
 *
 * @param options - Configuration options
 *
 * @example
 * ```tsx
 * function GeofenceScreen() {
 *   const {
 *     geofences,
 *     isLoading,
 *     error,
 *     addGeofence,
 *     removeGeofence,
 *     maxGeofences,
 *   } = useGeofencing();
 *
 *   const handleAdd = async () => {
 *     await addGeofence({
 *       identifier: 'office',
 *       latitude: -23.5505,
 *       longitude: -46.6333,
 *       radius: 200,
 *     });
 *   };
 *
 *   return (
 *     <View>
 *       <Text>Active: {geofences.length} / {maxGeofences}</Text>
 *       <Button onPress={handleAdd}>Add Office Geofence</Button>
 *       {error && <Text>Error: {error.message}</Text>}
 *     </View>
 *   );
 * }
 * ```
 */
export declare function useGeofencing(options?: UseGeofencingOptions): UseGeofencingReturn;
//# sourceMappingURL=useGeofencing.d.ts.map