import type { PermissionRationale } from '../types';
/**
 * Resolved rationale shape consumed by `PermissionsAndroid.request`.
 * Defined explicitly (rather than `Required<PermissionRationale>`) so
 * future optional fields on `PermissionRationale` cannot silently flip
 * to required in this contract.
 *
 * @internal
 */
interface ResolvedPermissionRationale {
    title: string;
    message: string;
    buttonPositive: string;
    buttonNegative: string;
    buttonNeutral: string;
}
/**
 * Merges a partial {@link PermissionRationale} override onto the library
 * defaults, returning a fully populated rationale ready to pass to
 * `PermissionsAndroid.request`.
 *
 * Resolution rule (per field): trim the override value; if the trimmed
 * value is truthy, use the trimmed string; otherwise fall back to the
 * default. Empty strings, whitespace-only strings, `undefined`, and
 * `null` all fall back.
 *
 * Implementation note: each field is read explicitly rather than via
 * iteration. This keeps the function type-safe under
 * `noUncheckedIndexedAccess` and survives future additions to
 * `PermissionRationale` without silently propagating new fields.
 *
 * @internal
 */
export declare function resolveRationale(override?: PermissionRationale): ResolvedPermissionRationale;
export {};
//# sourceMappingURL=resolveRationale.d.ts.map