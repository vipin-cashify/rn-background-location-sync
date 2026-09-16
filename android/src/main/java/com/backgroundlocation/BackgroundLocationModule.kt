package com.backgroundlocation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.backgroundlocation.sync.LocationSyncManagerHolder
import com.backgroundlocation.sync.SyncConfigStore
import com.backgroundlocation.sync.SyncStatus
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * TurboModule for background location tracking
 * Manages location collection sessions by tripId
 */
@ReactModule(name = BackgroundLocationModule.NAME)
class BackgroundLocationModule(reactContext: ReactApplicationContext) :
  NativeBackgroundLocationSpec(reactContext), LifecycleEventListener {

  private val storage: LocationStorage = LocationStorage(reactContext)
  private val geofenceManager: GeofenceManager = GeofenceManager(reactContext)
  private var locationCollectionJob: Job? = null
  private var geofenceCollectionJob: Job? = null
  private var notificationActionCollectionJob: Job? = null
  private var syncStatusCollectionJob: Job? = null

  // Coroutine scope for async operations
  //
  // IMPORTANT (sync layer): this scope backs only this module's own async work
  // (promise handlers, and - see startSyncStatusCollection()/stopSyncStatusCollection()
  // below - the collector that relays LocationSyncManager's statusFlow to JS as
  // onSyncStatusChanged). It must NEVER be passed to, or used to cancel,
  // LocationSyncManager itself. That manager is a process-lifetime singleton
  // (LocationSyncManagerHolder) whose own internal coroutine scope must keep running
  // across TurboModule instance teardown (app backgrounding/foregrounding can
  // invalidate/reinitialize this module while a sync HTTP call is still in flight).
  // Per Task 6's review: cancelling a sync run mid-POST would strand its batch in
  // SYNCING for the rest of the process's life, and the run's `finally` clears its
  // single-flight guard via a suspending `Mutex.withLock` that can wedge under
  // cancellation. moduleScope.cancel() below is safe *only* because it never reaches
  // into the manager - it just stops this module's own JS-event relay.
  private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  init {
    reactContext.addLifecycleEventListener(this)
    // Register the GeofenceManager in the holder for access from system BroadcastReceivers
    GeofenceManagerHolder.setInstance(geofenceManager)
  }

  override fun initialize() {
    super.initialize()
    startLocationEventCollection()
    startGeofenceEventCollection()
    startNotificationActionCollection()
    startSyncStatusCollection()
  }

  override fun invalidate() {
    super.invalidate()
    stopLocationEventCollection()
    stopGeofenceEventCollection()
    stopNotificationActionCollection()
    stopSyncStatusCollection()
    storage.cleanup()
    geofenceManager.cleanup()
    // Safe per the kdoc on moduleScope above: this only cancels this module's own
    // promise/event-relay coroutines, never LocationSyncManagerHolder's singleton
    // instance or its internal scope.
    moduleScope.cancel()
    reactApplicationContext.removeLifecycleEventListener(this)
  }

  // LifecycleEventListener implementation
  override fun onHostResume() {
    startLocationEventCollection()
    startGeofenceEventCollection()
    startNotificationActionCollection()
    startSyncStatusCollection()

    // Only schedule recovery if tracking is actually active
    // This prevents RecoveryWorker from starting SystemForegroundService when no tracking is active
    moduleScope.launch {
      try {
        // Skip recovery entirely if the service is already running.
        // This prevents duplicate onStartCommand() calls which would accumulate
        // location callbacks in the provider.
        if (LocationService.isRunning) {
          android.util.Log.d("BackgroundLocationModule", "Service already running, skipping recovery")
          return@launch
        }

        // Check stop token first - if user explicitly stopped tracking, don't recover
        if (LocationService.isStopTokenSet(reactApplicationContext)) {
          android.util.Log.d("BackgroundLocationModule", "Stop token is set, skipping recovery")
          return@launch
        }

        val trackingState = storage.getTrackingStateAsync()
        if (!trackingState.isActive || trackingState.tripId == null) {
          android.util.Log.d("BackgroundLocationModule", "No active tracking session, skipping recovery")
          return@launch
        }

        // Double-check stop token after reading state (handles race condition)
        if (LocationService.isStopTokenSet(reactApplicationContext)) {
          android.util.Log.d("BackgroundLocationModule", "Stop token set during state check, skipping recovery")
          return@launch
        }

        // Schedule recovery via WorkManager on Android 12+ (safer for background restrictions)
        // On older versions, recover directly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          RecoveryWorker.scheduleRecovery(reactApplicationContext)
        } else {
          // Safe to recover directly on older Android versions
          recoverTrackingSession()
        }
      } catch (e: Exception) {
        android.util.Log.e("BackgroundLocationModule", "Failed to check tracking state for recovery", e)
      }
    }
  }

  override fun onHostPause() {
    // Keep collection active to not miss events
  }

  override fun onHostDestroy() {
    stopLocationEventCollection()
    stopGeofenceEventCollection()
    stopNotificationActionCollection()
    stopSyncStatusCollection()
  }

  // --- SharedFlow Collection (start/stop) ---

  private fun startLocationEventCollection() {
    if (locationCollectionJob?.isActive == true) return
    locationCollectionJob = LocationEventFlow.events
      .onEach { event ->
        when (event) {
          is LocationEvent.Update -> handleLocationUpdate(event)
          is LocationEvent.Error -> handleLocationError(event)
          is LocationEvent.Warning -> handleLocationWarning(event)
        }
      }
      .launchIn(moduleScope)
  }

  private fun stopLocationEventCollection() {
    locationCollectionJob?.cancel()
    locationCollectionJob = null
  }

  private fun startGeofenceEventCollection() {
    if (geofenceCollectionJob?.isActive == true) return
    geofenceCollectionJob = GeofenceEventFlow.events
      .onEach { event ->
        when (event) {
          is GeofenceEvent.Transition -> handleGeofenceTransition(event)
        }
      }
      .launchIn(moduleScope)
  }

  private fun stopGeofenceEventCollection() {
    geofenceCollectionJob?.cancel()
    geofenceCollectionJob = null
  }

  private fun startNotificationActionCollection() {
    if (notificationActionCollectionJob?.isActive == true) return
    notificationActionCollectionJob = NotificationActionFlow.events
      .onEach { event ->
        when (event) {
          is NotificationActionEvent.ActionClicked -> handleNotificationAction(event)
        }
      }
      .launchIn(moduleScope)
  }

  private fun stopNotificationActionCollection() {
    notificationActionCollectionJob?.cancel()
    notificationActionCollectionJob = null
  }

  /**
   * Relays [com.backgroundlocation.sync.LocationSyncManager.statusFlow] to JS as
   * `onSyncStatusChanged`. This collector runs on [moduleScope] (this module's own,
   * JS-lifecycle-bound scope) - it only forwards updates from the shared
   * [LocationSyncManagerHolder] singleton, whose own internal sync-run coroutines live in
   * a separate, longer-lived scope entirely untouched by this collector or by
   * [moduleScope]'s cancellation (see the kdoc on `moduleScope` above). Native sync itself
   * never depends on this collector - or on any JS listener - existing; it only decides
   * whether a `WritableMap` gets emitted to a currently-attached JS instance.
   */
  private fun startSyncStatusCollection() {
    if (syncStatusCollectionJob?.isActive == true) return
    syncStatusCollectionJob = LocationSyncManagerHolder.getInstance(reactApplicationContext)
      .statusFlow
      .onEach { status -> handleSyncStatusChanged(status) }
      .launchIn(moduleScope)
  }

  private fun stopSyncStatusCollection() {
    syncStatusCollectionJob?.cancel()
    syncStatusCollectionJob = null
  }

  // --- Event Handlers ---

  private fun handleLocationUpdate(event: LocationEvent.Update) {
    if (!reactApplicationContext.hasActiveReactInstance()) return

    try {
      val eventData = LocationEventEmitter.bundleToWritableMap(event.tripId, event.locationData)
      reactApplicationContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("onLocationUpdate", eventData)
    } catch (e: Exception) {
      android.util.Log.e("BackgroundLocationModule", "Failed to emit location update", e)
    }
  }

  private fun handleLocationError(event: LocationEvent.Error) {
    if (!reactApplicationContext.hasActiveReactInstance()) return

    try {
      val eventData = Arguments.createMap().apply {
        event.tripId?.let { putString("tripId", it) }
        putString("type", event.errorType)
        putString("message", event.message)
      }
      reactApplicationContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("onLocationError", eventData)
    } catch (e: Exception) {
      android.util.Log.e("BackgroundLocationModule", "Failed to emit location error", e)
    }
  }

  private fun handleLocationWarning(event: LocationEvent.Warning) {
    if (!reactApplicationContext.hasActiveReactInstance()) return

    try {
      val eventData = Arguments.createMap().apply {
        event.tripId?.let { putString("tripId", it) }
        putString("type", event.warningType)
        putString("message", event.message)
      }
      reactApplicationContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("onLocationWarning", eventData)
    } catch (e: Exception) {
      android.util.Log.e("BackgroundLocationModule", "Failed to emit location warning", e)
    }
  }

  private fun handleNotificationAction(event: NotificationActionEvent.ActionClicked) {
    if (!reactApplicationContext.hasActiveReactInstance()) return

    try {
      val eventData = Arguments.createMap().apply {
        putString("tripId", event.tripId)
        putString("actionId", event.actionId)
      }
      reactApplicationContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("onNotificationAction", eventData)
    } catch (e: Exception) {
      android.util.Log.e("BackgroundLocationModule", "Failed to emit notification action", e)
    }
  }

  private fun handleGeofenceTransition(event: GeofenceEvent.Transition) {
    if (!reactApplicationContext.hasActiveReactInstance()) return

    try {
      val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
      }

      val eventData = Arguments.createMap().apply {
        putString("geofenceId", event.geofenceId)
        putString("transitionType", event.transitionType)
        putDouble("latitude", event.latitude)
        putDouble("longitude", event.longitude)
        putString("timestamp", isoFormatter.format(Date(event.timestamp)))
        putDouble("distanceFromCenter", event.distanceFromCenter)

        event.metadata?.let { metadataJson ->
          try {
            val jsonObj = JSONObject(metadataJson)
            val metadataMap = Arguments.createMap()
            val keys = jsonObj.keys()
            while (keys.hasNext()) {
              val key = keys.next()
              when (val value = jsonObj.get(key)) {
                is String -> metadataMap.putString(key, value)
                is Int -> metadataMap.putInt(key, value)
                is Double -> metadataMap.putDouble(key, value)
                is Boolean -> metadataMap.putBoolean(key, value)
                else -> metadataMap.putString(key, value.toString())
              }
            }
            putMap("metadata", metadataMap)
          } catch (_: Exception) {
            // If parsing fails, skip metadata
          }
        }
      }

      reactApplicationContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("onGeofenceTransition", eventData)
    } catch (e: Exception) {
      android.util.Log.e("BackgroundLocationModule", "Failed to emit geofence transition", e)
    }
  }

  /**
   * Emits `onSyncStatusChanged` with the last completed [com.backgroundlocation.sync.SyncResult]
   * (flattened) plus a friendlier `pending` alias of `pendingAfter` and the current
   * `authBlocked` state. `authBlocked` is carried separately from the result's own `error`
   * field because it can change (e.g. cleared by [setAuthToken]) without a new sync attempt
   * ever running, which would otherwise leave JS looking at a stale `error` value.
   *
   * No-ops (without building a payload) while [SyncStatus.lastResult] is still null - i.e.
   * before the very first sync attempt has completed this process. This means a persisted
   * `authBlocked = true` from a previous process, cleared via [setAuthToken] before any sync
   * has run yet this session, is not relayed live (there is no prior result to attach it to,
   * and the spec has no "get current status" query method) - a known, documented limitation
   * of the 4-method spec rather than a bug.
   */
  private fun handleSyncStatusChanged(status: SyncStatus) {
    if (!reactApplicationContext.hasActiveReactInstance()) return
    val result = status.lastResult ?: return

    try {
      val eventData = Arguments.createMap().apply {
        putInt("uploaded", result.uploaded)
        putBoolean("failed", result.failed)
        if (result.httpCode != null) putInt("httpCode", result.httpCode) else putNull("httpCode")
        if (result.error != null) putString("error", result.error) else putNull("error")
        putInt("pendingAfter", result.pendingAfter)
        putDouble("timestamp", result.timestamp.toDouble())
        putInt("pending", result.pendingAfter)
        putBoolean("authBlocked", status.authBlocked)
      }
      reactApplicationContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("onSyncStatusChanged", eventData)
    } catch (e: Exception) {
      android.util.Log.e("BackgroundLocationModule", "Failed to emit sync status changed", e)
    }
  }

  override fun getName(): String = NAME

  // Required by NativeEventEmitter contract (no-op on Android, events use RCTDeviceEventEmitter)
  override fun addListener(eventName: String?) {}
  override fun removeListeners(count: Double) {}

  /**
   * Starts location tracking for a specific trip
   * If tripId is null or empty, generates a new UUID
   * Returns the effective tripId being used
   */
  override fun startTracking(tripId: String?, options: ReadableMap?, promise: Promise) {
    try {
      // Parse options first to check foregroundOnly mode
      val trackingOptions = parseTrackingOptions(options)
      val foregroundOnly = trackingOptions.getForegroundOnlyOrDefault()

      // Check location permissions based on mode
      if (!hasLocationPermissions(foregroundOnly)) {
        val permissionMessage = if (foregroundOnly) {
          "Location permissions are required. Please grant ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION permissions."
        } else {
          "Location permissions are required. Please grant ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, and ACCESS_BACKGROUND_LOCATION permissions."
        }
        promise.reject("PERMISSION_DENIED", permissionMessage)
        return
      }

      // Check notification permission for Android 13+
      if (!hasNotificationPermission()) {
        promise.reject(
          "NOTIFICATION_PERMISSION_DENIED",
          "Notification permission is required for background location tracking on Android 13+. Please grant POST_NOTIFICATIONS permission."
        )
        return
      }

      // Check if already tracking (async)
      moduleScope.launch {
        try {
          val trackingState = storage.getTrackingStateAsync()
          if (trackingState.isActive && trackingState.tripId != null) {
            // Already tracking - return current tripId (idempotent)
            promise.resolve(trackingState.tripId)
            return@launch
          }

          // Continue with tracking start logic
          startTrackingInternal(tripId, trackingOptions, promise)
        } catch (e: Exception) {
          promise.reject("START_TRACKING_ERROR", "Failed to start tracking: ${e.message}", e)
        }
      }
    } catch (e: Exception) {
      promise.reject("START_TRACKING_ERROR", "Failed to start tracking: ${e.message}", e)
    }
  }

  private suspend fun startTrackingInternal(
    tripId: String?,
    trackingOptions: TrackingOptions,
    promise: Promise
  ) {
    // Generate tripId if not provided
    val effectiveTripId = if (tripId.isNullOrBlank()) {
      UUID.randomUUID().toString()
    } else {
      tripId
    }

    // Save tracking state with options for recovery
    storage.saveTrackingState(effectiveTripId, true, trackingOptions)

    // Start the foreground service with options
    withContext(Dispatchers.Main) {
      val context = reactApplicationContext
      LocationService.startService(context, effectiveTripId, trackingOptions)

      // Notify geofence manager that tracking started — stops heartbeat (redundant with active tracking)
      geofenceManager.onTrackingStarted()

      promise.resolve(effectiveTripId)
    }
  }

  /**
   * Parses TrackingOptions from ReadableMap.
   *
   * Notification settings are received as a single JSON string in the
   * `notificationOptions` field (Codegen does not support complex nested objects).
   */
  private fun parseTrackingOptions(options: ReadableMap?): TrackingOptions {
    if (options == null) {
      return TrackingOptions()
    }

    val accuracyString = if (options.hasKey("accuracy")) options.getString("accuracy") else null

    val notificationOptions = if (options.hasKey("notificationOptions") && !options.isNull("notificationOptions")) {
      try {
        val jsonString = options.getString("notificationOptions")
        if (jsonString != null) NotificationOptions.fromJsonString(jsonString) else null
      } catch (e: Exception) {
        android.util.Log.w("BackgroundLocationModule", "Failed to parse notificationOptions", e)
        null
      }
    } else null

    return TrackingOptions(
      updateInterval = if (options.hasKey("updateInterval")) options.getDouble("updateInterval").toLong() else null,
      fastestInterval = if (options.hasKey("fastestInterval")) options.getDouble("fastestInterval").toLong() else null,
      maxWaitTime = if (options.hasKey("maxWaitTime")) options.getDouble("maxWaitTime").toLong() else null,
      accuracy = LocationAccuracy.fromString(accuracyString),
      waitForAccurateLocation = if (options.hasKey("waitForAccurateLocation")) options.getBoolean("waitForAccurateLocation") else null,
      foregroundOnly = if (options.hasKey("foregroundOnly")) options.getBoolean("foregroundOnly") else null,
      distanceFilter = if (options.hasKey("distanceFilter")) options.getDouble("distanceFilter").toFloat() else null,
      notificationOptions = notificationOptions
    )
  }

  /**
   * Stops all location tracking and terminates the service
   * Uses a multi-step approach to ensure immediate cessation of location tracking:
   * 1. Set stop token (prevents RecoveryWorker from restarting)
   * 2. Cancel pending recovery work
   * 3. Immediately stop location updates on active instance
   * 4. Save tracking state synchronously (prevents race condition)
   * 5. Stop the service
   */
  override fun stopTracking(promise: Promise) {
    val context = reactApplicationContext

    // Use coroutine for async database operation
    moduleScope.launch {
      try {
        android.util.Log.d("BackgroundLocationModule", "stopTracking: Starting stop sequence")

        // Step 1: Set stop token FIRST (synchronous via SharedPreferences.commit())
        // This prevents RecoveryWorker from restarting tracking
        LocationService.setStopToken(context)

        // Step 2: Cancel any pending recovery work
        RecoveryWorker.cancelRecovery(context)

        // Step 3: Immediately stop location updates on active service instance
        // This is CRITICAL - stops events immediately without waiting for service destruction
        LocationService.stopLocationUpdatesImmediately(context)

        // Step 4: Save tracking state SYNCHRONOUSLY
        // This ensures the database is updated before RecoveryWorker can read stale state
        storage.saveTrackingStateSync(null, false)

        // Step 5: Now safe to stop the service
        withContext(Dispatchers.Main) {
          LocationService.stopService(context)
        }

        // Step 6: Notify geofence manager that tracking stopped — restarts heartbeat if geofences exist
        geofenceManager.onTrackingStopped()

        android.util.Log.d("BackgroundLocationModule", "stopTracking: Stop sequence completed successfully")
        promise.resolve(null)
      } catch (e: Exception) {
        android.util.Log.e("BackgroundLocationModule", "stopTracking: Failed", e)
        promise.reject("STOP_TRACKING_ERROR", "Failed to stop tracking: ${e.message}", e)
      }
    }
  }

  /**
   * Returns the current tracking state
   */
  override fun isTracking(promise: Promise) {
    moduleScope.launch {
      try {
        val trackingState = storage.getTrackingStateAsync()
        val result = Arguments.createMap().apply {
          putBoolean("active", trackingState.isActive)
          if (trackingState.tripId != null) {
            putString("tripId", trackingState.tripId)
          }
        }
        promise.resolve(result)
      } catch (e: Exception) {
        promise.reject("IS_TRACKING_ERROR", "Failed to get tracking state: ${e.message}", e)
      }
    }
  }

  /**
   * Retrieves all stored locations for a specific trip
   */
  override fun getLocations(tripId: String, promise: Promise) {
    if (tripId.isBlank()) {
      promise.reject("INVALID_TRIP_ID", "Trip ID cannot be empty")
      return
    }

    moduleScope.launch {
      try {
        val entities = storage.getLocationsAsync(tripId)
        val locations = storage.entitiesToWritableArray(entities)
        promise.resolve(locations)
      } catch (e: Exception) {
        promise.reject("GET_LOCATIONS_ERROR", "Failed to get locations: ${e.message}", e)
      }
    }
  }

  /**
   * Clears all stored location data for a specific trip
   */
  override fun clearTrip(tripId: String, promise: Promise) {
    try {
      if (tripId.isBlank()) {
        promise.reject("INVALID_TRIP_ID", "Trip ID cannot be empty")
        return
      }

      storage.clearTrip(tripId)
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("CLEAR_TRIP_ERROR", "Failed to clear trip: ${e.message}", e)
    }
  }

  /**
   * Updates the notification content while tracking is active
   * Dynamic updates are transient and do not persist to database
   */
  override fun updateNotification(title: String, text: String, promise: Promise) {
    if (title.isBlank() || text.isBlank()) {
      promise.reject("INVALID_ARGUMENTS", "Title and text cannot be empty")
      return
    }

    val success = LocationService.updateNotification(title, text)
    if (success) {
      promise.resolve(null)
    } else {
      promise.reject("NO_ACTIVE_SERVICE", "No active location service instance. Is tracking running?")
    }
  }

  /**
   * Checks the current location permission status
   * Returns granted only if background location permission is available
   */
  override fun checkLocationPermission(promise: Promise) {
    try {
      val hasBackground = hasLocationPermissions(false)
      val hasForeground = hasLocationPermissions(true)

      val status = when {
        hasBackground -> "granted"
        hasForeground -> "denied" // Has foreground only, insufficient for background
        else -> "denied"
      }

      val result = Arguments.createMap().apply {
        putString("status", status)
        putBoolean("canRequestAgain", true) // Android always allows re-requesting via PermissionsAndroid
      }
      promise.resolve(result)
    } catch (e: Exception) {
      promise.reject("CHECK_PERMISSION_ERROR", "Failed to check location permission: ${e.message}", e)
    }
  }

  /**
   * Returns the current permission status for location
   * On Android, actual permission requests are handled by PermissionsAndroid in JS
   */
  override fun requestLocationPermission(foregroundOnly: Boolean, promise: Promise) {
    // On Android, permission requests are handled by PermissionsAndroid in JS
    // This method returns the current permission status
    try {
      val hasPermission = hasLocationPermissions(foregroundOnly)

      val result = Arguments.createMap().apply {
        putString("status", if (hasPermission) "granted" else "denied")
        putBoolean("canRequestAgain", true)
      }
      promise.resolve(result)
    } catch (e: Exception) {
      promise.reject("REQUEST_PERMISSION_ERROR", "Failed to check location permission: ${e.message}", e)
    }
  }

  /**
   * Checks the current notification permission status without prompting.
   * On Android 13+ (TIRAMISU), checks POST_NOTIFICATIONS permission.
   * On older versions, notifications are always allowed.
   *
   * @returns "granted" | "denied" | "undetermined"
   */
  override fun checkNotificationPermission(promise: Promise) {
    try {
      val status = if (hasNotificationPermission()) "granted" else "denied"
      promise.resolve(status)
    } catch (e: Exception) {
      promise.reject("CHECK_NOTIFICATION_PERMISSION_ERROR", e.message, e)
    }
  }

  /**
   * Requests notification permission from the user.
   * On Android, the actual permission request is handled by PermissionsAndroid in JS.
   * This method returns the current permission status.
   *
   * @returns "granted" | "denied"
   */
  override fun requestNotificationPermission(promise: Promise) {
    try {
      val status = if (hasNotificationPermission()) "granted" else "denied"
      promise.resolve(status)
    } catch (e: Exception) {
      promise.reject("REQUEST_NOTIFICATION_PERMISSION_ERROR", e.message, e)
    }
  }

  /**
   * Recovers tracking session after app restart/crash
   * Restarts the LocationService if tracking was active
   */
  private fun recoverTrackingSession() {
    moduleScope.launch {
      try {
        // Check stop token first - if user explicitly stopped, don't recover
        if (LocationService.isStopTokenSet(reactApplicationContext)) {
          android.util.Log.d("BackgroundLocationModule", "recoverTrackingSession: Stop token set, aborting recovery")
          return@launch
        }

        val trackingState = storage.getTrackingStateAsync()

        // If tracking was active and we have both tripId and options
        if (trackingState.isActive && trackingState.tripId != null) {
          // Double-check stop token after reading state
          if (LocationService.isStopTokenSet(reactApplicationContext)) {
            android.util.Log.d("BackgroundLocationModule", "recoverTrackingSession: Stop token set during state check, aborting")
            return@launch
          }

          // Check if we still have location permissions
          if (!hasLocationPermissions()) {
            // Clear tracking state if permissions were revoked
            storage.saveTrackingState(null, false)
            return@launch
          }

          // Restart the service with saved options
          val options = trackingState.options ?: TrackingOptions()
          withContext(Dispatchers.Main) {
            val context = reactApplicationContext
            LocationService.startService(context, trackingState.tripId, options)
          }
        }
      } catch (e: Exception) {
        // Log error but don't crash - recovery is best-effort
        e.printStackTrace()

        // Clear tracking state if recovery fails
        try {
          storage.saveTrackingState(null, false)
        } catch (clearError: Exception) {
          clearError.printStackTrace()
        }
      }
    }
  }

  /**
   * Checks if the app has the necessary location permissions
   * @param foregroundOnly If true, only checks for foreground location permissions (no background)
   */
  private fun hasLocationPermissions(foregroundOnly: Boolean = false): Boolean {
    val context = reactApplicationContext
    val fineLocation = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val coarseLocation = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    // Skip background location check if foregroundOnly mode
    if (foregroundOnly) {
      return fineLocation && coarseLocation
    }

    // Check background location permission for Android 10+
    val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true // Not required for Android 9 and below
    }

    return fineLocation && coarseLocation && backgroundLocation
  }

  /**
   * Checks if the app has notification permission (Android 13+)
   * Required for foreground services to show notifications
   */
  private fun hasNotificationPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(
        reactApplicationContext,
        Manifest.permission.POST_NOTIFICATIONS
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true // Not required before Android 13
    }
  }

  // --- Geofencing Methods ---

  override fun addGeofence(regionJson: String, promise: Promise) {
    moduleScope.launch {
      try {
        geofenceManager.addGeofence(regionJson)
        promise.resolve(null)
      } catch (e: GeofenceManager.GeofenceException) {
        promise.reject(e.code, e.message, e)
      } catch (e: Exception) {
        promise.reject("MONITORING_FAILED", "Failed to add geofence: ${e.message}", e)
      }
    }
  }

  override fun addGeofences(regionsJson: String, promise: Promise) {
    moduleScope.launch {
      try {
        geofenceManager.addGeofences(regionsJson)
        promise.resolve(null)
      } catch (e: GeofenceManager.GeofenceException) {
        promise.reject(e.code, e.message, e)
      } catch (e: Exception) {
        promise.reject("MONITORING_FAILED", "Failed to add geofences: ${e.message}", e)
      }
    }
  }

  override fun removeGeofence(identifier: String, promise: Promise) {
    moduleScope.launch {
      try {
        geofenceManager.removeGeofence(identifier)
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("MONITORING_FAILED", "Failed to remove geofence: ${e.message}", e)
      }
    }
  }

  override fun removeGeofences(identifiersJson: String, promise: Promise) {
    moduleScope.launch {
      try {
        geofenceManager.removeGeofences(identifiersJson)
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("MONITORING_FAILED", "Failed to remove geofences: ${e.message}", e)
      }
    }
  }

  override fun removeAllGeofences(promise: Promise) {
    moduleScope.launch {
      try {
        geofenceManager.removeAllGeofences()
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("MONITORING_FAILED", "Failed to remove all geofences: ${e.message}", e)
      }
    }
  }

  override fun getActiveGeofences(promise: Promise) {
    moduleScope.launch {
      try {
        val result = geofenceManager.getActiveGeofences()
        promise.resolve(result)
      } catch (e: Exception) {
        promise.reject("MONITORING_FAILED", "Failed to get active geofences: ${e.message}", e)
      }
    }
  }

  override fun getMaxGeofences(promise: Promise) {
    promise.resolve(geofenceManager.getMaxGeofences().toDouble())
  }

  override fun getGeofenceTransitions(identifier: String?, promise: Promise) {
    moduleScope.launch {
      try {
        val result = geofenceManager.getGeofenceTransitions(identifier)
        promise.resolve(result)
      } catch (e: Exception) {
        promise.reject("MONITORING_FAILED", "Failed to get geofence transitions: ${e.message}", e)
      }
    }
  }

  override fun clearGeofenceTransitions(identifier: String?, promise: Promise) {
    moduleScope.launch {
      try {
        geofenceManager.clearGeofenceTransitions(identifier)
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("MONITORING_FAILED", "Failed to clear geofence transitions: ${e.message}", e)
      }
    }
  }

  // --- Geofence Notification Configuration ---

  override fun configureGeofenceNotifications(configJson: String, promise: Promise) {
    try {
      val config = GeofenceNotificationConfig.fromJsonString(configJson)
      GeofenceNotificationConfigStore.save(reactApplicationContext, config)
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("CONFIG_ERROR", "Failed to configure geofence notifications: ${e.message}", e)
    }
  }

  override fun getGeofenceNotificationConfig(promise: Promise) {
    try {
      val config = GeofenceNotificationConfigStore.load(reactApplicationContext)
      promise.resolve(config.toJsonString())
    } catch (e: Exception) {
      promise.reject("CONFIG_ERROR", "Failed to read geofence notification config: ${e.message}", e)
    }
  }

  // --- Sync Methods ---
  //
  // All four delegate to LocationSyncManagerHolder.getInstance(reactApplicationContext) - the
  // same process-lifetime singleton the location-insert/connectivity/worker triggers use (see
  // LocationService/LocationStorage) - never a module-owned LocationSyncManager instance. This
  // module never constructs its own LocationSyncManager and never cancels the holder's instance
  // or its internal scope; see the kdoc on `moduleScope` above for why that distinction matters.

  override fun configureSync(config: ReadableMap, promise: Promise) {
    try {
      val configStore = SyncConfigStore(reactApplicationContext)
      configStore.syncUrl = if (config.hasKey("syncUrl")) config.getString("syncUrl") else null
      if (config.hasKey("batchSize") && !config.isNull("batchSize")) {
        configStore.batchSize = config.getDouble("batchSize").toInt()
      }
      if (config.hasKey("maxQueueRows") && !config.isNull("maxQueueRows")) {
        configStore.maxQueueRows = config.getDouble("maxQueueRows").toInt()
      }
      if (config.hasKey("maxAgeDays") && !config.isNull("maxAgeDays")) {
        configStore.maxAgeDays = config.getDouble("maxAgeDays").toInt()
      }
      if (config.hasKey("headersJson") && !config.isNull("headersJson")) {
        val headersJson = config.getString("headersJson")
        if (!headersJson.isNullOrBlank()) {
          configStore.extraHeaders = parseHeadersJson(headersJson)
        }
      }
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("CONFIGURE_SYNC_ERROR", "Failed to configure sync: ${e.message}", e)
    }
  }

  override fun setAuthToken(token: String, promise: Promise) {
    // Dispatched on IO to keep the SharedPreferences write off moduleScope's Main dispatcher.
    moduleScope.launch(Dispatchers.IO) {
      try {
        // LocationSyncManager.setToken (not a raw TokenStore) is the recommended entry point
        // per Task 6: an empty string clears the persisted token, and any non-blank token also
        // clears an existing 401 auth-block as a side effect.
        LocationSyncManagerHolder.getInstance(reactApplicationContext).setToken(token)
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("SET_AUTH_TOKEN_ERROR", "Failed to set auth token: ${e.message}", e)
      }
    }
  }

  override fun setSsoToken(token: String, promise: Promise) {
    // Same rationale as setAuthToken: IO dispatcher because the encrypted token store lazily
    // builds a Keystore-backed key on first access.
    moduleScope.launch(Dispatchers.IO) {
      try {
        LocationSyncManagerHolder.getInstance(reactApplicationContext).setSsoToken(token)
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("SET_SSO_TOKEN_ERROR", "Failed to set sso token: ${e.message}", e)
      }
    }
  }

  override fun getPendingCount(promise: Promise) {
    moduleScope.launch {
      try {
        val count = LocationSyncManagerHolder.getInstance(reactApplicationContext).pendingCount()
        promise.resolve(count)
      } catch (e: Exception) {
        promise.reject("GET_PENDING_COUNT_ERROR", "Failed to get pending count: ${e.message}", e)
      }
    }
  }

  override fun forceSync(promise: Promise) {
    moduleScope.launch {
      try {
        val result = LocationSyncManagerHolder.getInstance(reactApplicationContext).syncNow("js-force-sync")
        val map = Arguments.createMap().apply {
          putInt("uploaded", result.uploaded)
          putBoolean("failed", result.failed)
          if (result.httpCode != null) putInt("httpCode", result.httpCode) else putNull("httpCode")
          if (result.error != null) putString("error", result.error) else putNull("error")
          putInt("pendingAfter", result.pendingAfter)
          putDouble("timestamp", result.timestamp.toDouble())
        }
        promise.resolve(map)
      } catch (e: Exception) {
        promise.reject("FORCE_SYNC_ERROR", "Failed to force sync: ${e.message}", e)
      }
    }
  }

  /** Decodes a `configureSync` `headersJson` string into a `Map<String, String>`. */
  private fun parseHeadersJson(json: String): Map<String, String> {
    val obj = JSONObject(json)
    val result = LinkedHashMap<String, String>()
    val keys = obj.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      result[key] = obj.getString(key)
    }
    return result
  }

  companion object {
    const val NAME = "BackgroundLocation"
  }
}

