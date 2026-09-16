"use strict";

import { TurboModuleRegistry } from 'react-native';

/**
 * Permission status result from native permission check/request
 * Must be defined inline for Codegen compatibility
 */

/**
 * Sync configuration for the bulk-upload layer (`configureSync`).
 * Must be defined inline for Codegen compatibility: `headersJson` is a
 * JSON-serialized `Record<string, string>` since Codegen cannot express
 * maps (mirrors `notificationOptions` on {@link TrackingOptionsSpec}).
 */

/**
 * Tracking options interface for TurboModule spec
 * Must be defined inline for Codegen compatibility
 */

export default TurboModuleRegistry.getEnforcing('BackgroundLocation');
//# sourceMappingURL=NativeBackgroundLocation.js.map