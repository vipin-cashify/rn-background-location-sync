package com.backgroundlocation.sync

import android.content.Context

/**
 * Singleton holder for [LocationSyncManager], mirroring
 * `com.backgroundlocation.GeofenceManagerHolder`. Gives [SyncWorker], the connectivity callback
 * registered by `LocationService`, and the post-insert trigger in `LocationStorage` a shared
 * instance without needing a React Native module reference.
 */
object LocationSyncManagerHolder {

    @Volatile
    private var instance: LocationSyncManager? = null

    fun getInstance(context: Context): LocationSyncManager {
        return instance ?: synchronized(this) {
            instance ?: LocationSyncManager(context.applicationContext).also { instance = it }
        }
    }

    fun setInstance(manager: LocationSyncManager) {
        synchronized(this) {
            instance = manager
        }
    }

    fun clearInstance() {
        synchronized(this) {
            instance = null
        }
    }
}
