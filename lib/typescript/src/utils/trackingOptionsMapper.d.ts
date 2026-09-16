import type { TrackingOptions } from '../types';
import type { TrackingOptionsSpec } from '../NativeBackgroundLocation';
/**
 * Converts the public {@link TrackingOptions} (with TypeScript enums and
 * structured `notificationOptions`) to the {@link TrackingOptionsSpec} shape
 * expected by the TurboModule Codegen contract (strings + JSON-stringified
 * notification options).
 *
 * @internal
 */
export declare function toTrackingOptionsSpec(options?: TrackingOptions | null): TrackingOptionsSpec;
//# sourceMappingURL=trackingOptionsMapper.d.ts.map