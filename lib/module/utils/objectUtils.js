"use strict";

/**
 * Utility functions for object manipulation
 */

/**
 * Extracts all defined properties from a source object into a new object
 * Only includes properties that are not undefined
 * @param source - Source object to extract properties from
 * @returns Object containing all defined properties (both required and optional)
 */
export function extractDefinedProperties(source) {
  const result = {};
  for (const key in source) {
    if (Object.prototype.hasOwnProperty.call(source, key) && source[key] !== undefined) {
      result[key] = source[key];
    }
  }
  return result;
}
//# sourceMappingURL=objectUtils.js.map