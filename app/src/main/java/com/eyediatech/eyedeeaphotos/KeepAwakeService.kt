package com.eyediatech.eyedeeaphotos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class KeepAwakeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val WAKE_LOCK_TAG = "EyeDeeaPhotos::KeepAwakeService"
    private val NOTIFICATION_CHANNEL_ID = "KeepAwakeChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, WAKE_LOCK_TAG)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("KeepAwakeService", "Service starting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
            Log.d("KeepAwakeService", "WakeLock acquired")
        }
        // If the service is killed, it will be automatically restarted.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d("KeepAwakeService", "WakeLock released")
        }
        Log.d("KeepAwakeService", "Service destroyed")
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Keep Awake Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("EyeDeea Photos is Active")
            .setContentText("Slideshow is running to keep the screen on.")
            .setSmallIcon(R.mipmap.ic_launcher) // Use your app's icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // This is a non-binding service, so we return null.
        return null
    }
}
