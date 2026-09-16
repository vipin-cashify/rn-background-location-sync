/**
 * Utility functions for object manipulation
 */
/**
 * Extracts all defined properties from a source object into a new object
 * Only includes properties that are not undefined
 * @param source - Source object to extract properties from
 * @returns Object containing all defined properties (both required and optional)
 */
export declare function extractDefinedProperties<T extends Record<string, any>>(source: T): Partial<T>;
//# sourceMappingURL=objectUtils.d.ts.map