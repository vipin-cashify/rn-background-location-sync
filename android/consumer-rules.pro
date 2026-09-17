# Consumer ProGuard/R8 rules for @cashify/rn-background-location-sync.
#
# These are applied automatically to any app that minifies (release builds).
# Without them, R8 obfuscates/strips this package and:
#   - Room's reflective <Database>_Impl lookup (Class.forName(db.name + "_Impl"))
#     fails once LocationDatabase is renamed → the native store can't open;
#   - the TurboModule (BackgroundLocationModule/Package) and the foreground
#     LocationService entry points can be stripped,
# which silently disables background location tracking in release builds while
# debug (unminified) works fine.
#
# Keeping the whole package covers the module, service, sync layer, Room
# entities/DAOs, and the Room-generated *_Impl classes (same package).
-keep class com.backgroundlocation.** { *; }
-dontwarn com.backgroundlocation.**
