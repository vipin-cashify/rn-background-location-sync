import type { UseLocationPermissionsResult } from '../types';
/**
 * Hook to manage location and notification permissions for background tracking
 *
 * Provides granular permission state separating location and notification permissions.
 * Notification permission denial is non-blocking — background tracking only requires
 * location permissions. Notification denial only affects geofence visual notifications.
 *
 * Android: Uses PermissionsAndroid for location and notification permissions (API 33+), falling back to native module for notifications on older SDKs.
 * iOS: Uses CLLocationManager authorization (WhenInUse -> Always two-step flow) + native module for notifications.
 *
 * @example
 * ```tsx
 * function App() {
 *   const { permissionStatus, requestPermissions, isRequesting } = useLocationPermissions();
 *
 *   if (!permissionStatus.location.hasPermission) {
 *     return <Button onPress={requestPermissions}>Grant Location Permissions</Button>;
 *   }
 *
 *   if (!permissionStatus.notification.hasPermission) {
 *     return <Text>Notifications disabled - geofence alerts won't appear</Text>;
 *   }
 *
 *   return <TrackingScreen />;
 * }
 * ```
 */
export declare function useLocationPermissions(): UseLocationPermissionsResult;
//# sourceMappingURL=useLocationPermissions.d.ts.map