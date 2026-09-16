"use strict";

import { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { addGeofence as addGeofenceApi, addGeofences as addGeofencesApi, removeGeofence as removeGeofenceApi, removeGeofences as removeGeofencesApi, removeAllGeofences as removeAllGeofencesApi, getActiveGeofences, getMaxGeofences, configureGeofenceNotifications } from "../index.js";

/**
 * Configuration options for the useGeofencing hook
 */

/**
 * Return type for the useGeofencing hook
 */

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
export function useGeofencing(options = {}) {
  const {
    autoLoad = true,
    notificationOptions
  } = options;
  const [geofences, setGeofences] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const [maxGeofences, setMaxGeofences] = useState(null);

  // Deep comparison for notification options to prevent unnecessary native calls
  const notificationOptionsJson = JSON.stringify(notificationOptions);

  // Ref to avoid closing over stale notificationOptions in the effect
  const notificationOptionsRef = useRef(notificationOptions);
  notificationOptionsRef.current = notificationOptions;

  /**
   * Clear error state
   */
  const clearError = useCallback(() => {
    setError(null);
  }, []);

  /**
   * Load active geofences and platform limit from native
   */
  const refresh = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const [activeGeofences, limit] = await Promise.all([getActiveGeofences(), getMaxGeofences()]);
      setGeofences(activeGeofences);
      setMaxGeofences(limit);
    } catch (err) {
      const loadError = err instanceof Error ? err : new Error('Failed to load geofences');
      setError(loadError);
    } finally {
      setIsLoading(false);
    }
  }, []);

  /**
   * Auto-load geofences on mount if enabled
   */
  useEffect(() => {
    if (autoLoad) {
      refresh();
    }
  }, [autoLoad, refresh]);

  /**
   * Configure geofence notifications when options are provided
   */
  useEffect(() => {
    if (notificationOptionsRef.current) {
      configureGeofenceNotifications(notificationOptionsRef.current).catch(err => {
        const configError = err instanceof Error ? err : new Error('Failed to configure geofence notifications');
        setError(configError);
      });
    }
  }, [notificationOptionsJson]);

  /**
   * Register a single geofence region
   */
  const addGeofence = useCallback(async region => {
    setError(null);
    try {
      await addGeofenceApi(region);
      await refresh();
    } catch (err) {
      const addError = err instanceof Error ? err : new Error('Failed to add geofence');
      setError(addError);
      throw addError;
    }
  }, [refresh]);

  /**
   * Register multiple geofence regions atomically
   */
  const addGeofences = useCallback(async regions => {
    setError(null);
    try {
      await addGeofencesApi(regions);
      await refresh();
    } catch (err) {
      const addError = err instanceof Error ? err : new Error('Failed to add geofences');
      setError(addError);
      throw addError;
    }
  }, [refresh]);

  /**
   * Remove a single geofence by identifier
   */
  const removeGeofence = useCallback(async identifier => {
    setError(null);
    try {
      await removeGeofenceApi(identifier);
      await refresh();
    } catch (err) {
      const removeError = err instanceof Error ? err : new Error('Failed to remove geofence');
      setError(removeError);
      throw removeError;
    }
  }, [refresh]);

  /**
   * Remove multiple geofences by identifiers
   */
  const removeGeofences = useCallback(async identifiers => {
    setError(null);
    try {
      await removeGeofencesApi(identifiers);
      await refresh();
    } catch (err) {
      const removeError = err instanceof Error ? err : new Error('Failed to remove geofences');
      setError(removeError);
      throw removeError;
    }
  }, [refresh]);

  /**
   * Remove all registered geofences
   */
  const removeAllGeofences = useCallback(async () => {
    setError(null);
    try {
      await removeAllGeofencesApi();
      await refresh();
    } catch (err) {
      const removeError = err instanceof Error ? err : new Error('Failed to remove all geofences');
      setError(removeError);
      throw removeError;
    }
  }, [refresh]);
  return useMemo(() => ({
    geofences,
    isLoading,
    error,
    addGeofence,
    addGeofences,
    removeGeofence,
    removeGeofences,
    removeAllGeofences,
    maxGeofences,
    refresh,
    clearError
  }), [geofences, isLoading, error, addGeofence, addGeofences, removeGeofence, removeGeofences, removeAllGeofences, maxGeofences, refresh, clearError]);
}
//# sourceMappingURL=useGeofencing.js.map