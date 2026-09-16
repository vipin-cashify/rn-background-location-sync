export interface UseLocationTrackingResult {
    /**
     * Whether location tracking is currently active
     */
    isTracking: boolean;
    /**
     * Current trip ID if tracking is active
     */
    tripId: string | null;
    /**
     * Refresh tracking status
     */
    refresh: () => Promise<void>;
    /**
     * Whether status is being checked
     */
    isLoading: boolean;
}
/**
 * Hook to observe location tracking status
 *
 * Lightweight hook that only monitors if tracking is active.
 * Use this when you don't need full tracking management.
 *
 * @param autoRefresh - Whether to automatically check status on mount
 *
 * @example
 * ```tsx
 * function StatusBadge() {
 *   const { isTracking, tripId } = useLocationTracking();
 *
 *   return (
 *     <View>
 *       <Text>Status: {isTracking ? 'Tracking' : 'Stopped'}</Text>
 *       {tripId && <Text>Trip: {tripId}</Text>}
 *     </View>
 *   );
 * }
 * ```
 */
export declare function useLocationTracking(autoRefresh?: boolean): UseLocationTrackingResult;
//# sourceMappingURL=useLocationTracking.d.ts.map