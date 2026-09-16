"use strict";

/**
 * Notification configuration types for react-native-background-location
 */

/**
 * Unified notification configuration interface.
 *
 * Used across the entire library for any feature that involves notifications:
 * - Background location tracking (foreground service notification)
 * - Geofencing (transition notifications)
 * - Future notification-producing features
 *
 * For geofencing, the `title` and `text` fields support template variables.
 * See {@link GEOFENCE_TEMPLATE_VARS} for available variables.
 */

/**
 * Available template variables for geofence notification title and text fields.
 *
 * Usage: include `{{variableName}}` in your title or text strings.
 * Variables are resolved at notification time on the native side.
 *
 * @example
 * ```typescript
 * import { GEOFENCE_TEMPLATE_VARS } from '@gabriel-sisjr/react-native-background-location';
 * // Use for reference -- autocomplete will show all available variables
 * console.log(GEOFENCE_TEMPLATE_VARS.IDENTIFIER); // '{{identifier}}'
 * ```
 */
export const GEOFENCE_TEMPLATE_VARS = {
  IDENTIFIER: '{{identifier}}',
  TRANSITION_TYPE: '{{transitionType}}',
  LATITUDE: '{{latitude}}',
  LONGITUDE: '{{longitude}}',
  RADIUS: '{{radius}}',
  TIMESTAMP: '{{timestamp}}'
};
//# sourceMappingURL=notifications.js.map