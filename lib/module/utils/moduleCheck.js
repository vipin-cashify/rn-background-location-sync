"use strict";

import NativeModule from "../NativeBackgroundLocation.js";

/**
 * Asserts that the native BackgroundLocation module is available.
 * Throws a descriptive error if the module is not loaded.
 */
export function assertNativeModuleAvailable() {
  if (!NativeModule) {
    throw new Error('BackgroundLocation native module is not available. ' + 'Ensure the library is properly linked and the app has been rebuilt.');
  }
}
//# sourceMappingURL=moduleCheck.js.map