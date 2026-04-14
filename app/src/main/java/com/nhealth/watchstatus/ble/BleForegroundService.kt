package com.nhealth.watchstatus.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nhealth.watchstatus.NHealthApplication
import com.nhealth.watchstatus.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BleForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.nhealth.watchstatus.ble.action.START"
        const val ACTION_STOP = "com.nhealth.watchstatus.ble.action.STOP"
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"

        private const val NOTIFICATION_CHANNEL_ID = "ble_companion_channel"
        private const val NOTIFICATION_ID = 3801

        fun start(context: Context, deviceAddress: String?) {
            val intent = Intent(context, BleForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification())

                val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (!address.isNullOrBlank()) {
                    val repository = (application as NHealthApplication).appContainer.bleRepository
                    scope.launch {
                        repository.setAutoReconnect(true)
                        repository.connect(address)
                    }
                }
            }
        }

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.background_reconnect_active))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) {
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.ble_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }
}
