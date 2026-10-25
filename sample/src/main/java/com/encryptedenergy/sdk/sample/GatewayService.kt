package com.encryptedenergy.sdk.sample

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.encryptedenergy.sdk.EEConfig
import com.encryptedenergy.sdk.EEGateway

/**
 * The reference foreground service (plan §5 C7) — the pattern the C10 guide
 * quotes. A real fleet app already has one of these; the only lines it adds
 * are the two EEGateway calls. The SDK never runs a service of its own
 * (plan §3): this is the host's lifecycle, driving the SDK's.
 */
class GatewayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Configure HERE, not only in the Activity: a START_STICKY restart
        // after process death reaches this line with no UI having run, and
        // configure() is idempotent so the double call from a normal launch
        // is free. A fleet app reads its token from wherever its backend
        // put it; the sample reads the pasted one.
        val token = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_TOKEN, null)
        if (token.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        EEGateway.configure(applicationContext, EEConfig.GatewayToken(token))
        startInForeground()
        EEGateway.start()
        return START_STICKY
    }

    override fun onDestroy() {
        EEGateway.stop()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "EE Gateway", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("EE gateway running")
            .setContentText("Relaying nearby beacons")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            // Declare only the types this process qualifies for right now:
            // location is guaranteed (MainActivity gates service start on the
            // fine-location grant — API 34 throws otherwise), connectedDevice
            // only once BLUETOOTH_SCAN is granted (API 34 enforces that too).
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "ee_sample_gateway"
        private const val NOTIFICATION_ID = 1
    }
}
