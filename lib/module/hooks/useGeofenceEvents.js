"use strict";

import { useEffect, useRef } from 'react';
import { NativeEventEmitter } from 'react-native';
import BackgroundLocationModule from "../NativeBackgroundLocation.js";
// Check if native module is available
const isNativeModuleAvailable = () => {
  try {
    // Optional chaining handles null/undefined module — if the module is missing
    // or isTracking is not a function, we know the native layer is unavailable.
    if (typeof BackgroundLocationModule?.isTracking !== 'function') {
      return false;
    }
    return true;
  } catch {
    return false;
  }
};

/**
 * Configuration options for the useGeofenceEvents hook
 */

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
export function useGeofenceEvents(options) {
  // Keep stable refs to avoid re-subscribing on every render.
  // The effect subscribes once on mount; the handler always reads current
  // values from refs so inline arrays / new callback instances are safe.
  const onTransitionRef = useRef(options?.onTransition);
  onTransitionRef.current = options?.onTransition;
  const filterRef = useRef(options?.filter);
  filterRef.current = options?.filter;
  const geofenceIdRef = useRef(options?.geofenceId);
  geofenceIdRef.current = options?.geofenceId;
  useEffect(() => {
    if (!isNativeModuleAvailable()) {
      return;
    }

    // Pass native module to NativeEventEmitter (required on iOS, optional on Android)
    const eventEmitter = new NativeEventEmitter(BackgroundLocationModule);
    const subscription = eventEmitter.addListener('onGeofenceTransition', event => {
      const transitionEvent = event;

      // Read current values from refs to avoid stale closures
      const currentFilter = filterRef.current;
      const currentGeofenceId = geofenceIdRef.current;

      // Apply transition type filter
      if (currentFilter && currentFilter.length > 0 && !currentFilter.includes(transitionEvent.transitionType)) {
        return;
      }

      // Apply geofence identifier filter
      if (currentGeofenceId && transitionEvent.geofenceId !== currentGeofenceId) {
        return;
      }

      // Invoke callback via ref to avoid stale closures
      onTransitionRef.current?.(transitionEvent);
    });
    return () => {
      subscription.remove();
    };
    // Subscribe once on mount. Filter, geofenceId, and onTransition are
    // accessed via refs so the handler always sees the latest values
    // without needing to re-subscribe.
  }, []);
}
//# sourceMappingURL=useGeofenceEvents.js.map