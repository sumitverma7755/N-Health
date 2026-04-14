package com.nhealth.watchstatus

import android.content.Context
import com.nhealth.watchstatus.ble.BLEManager
import com.nhealth.watchstatus.ble.ConnectionManager
import com.nhealth.watchstatus.data.BLERepository
import com.nhealth.watchstatus.data.local.BleDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database: BleDatabase by lazy {
        BleDatabase.create(appContext)
    }

    private val bleManager: BLEManager by lazy {
        BLEManager(appContext)
    }

    private val connectionManager: ConnectionManager by lazy {
        ConnectionManager(bleManager, appScope)
    }

    val bleRepository: BLERepository by lazy {
        BLERepository(
            appContext = appContext,
            bleManager = bleManager,
            connectionManager = connectionManager,
            gattDao = database.gattDao(),
            appScope = appScope
        )
    }
}
