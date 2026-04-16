package com.benedykt.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service, który w tle słucha frazy "Hej Benedykt" i po jej wykryciu
 * budzi MainActivity, żeby rozpocząć konwersację.
 */
class WakeWordService : Service() {

    private var detector: WakeWordDetector? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        detector = WakeWordDetector(this) {
            launchAssistant()
        }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        detector?.stop()
        detector = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun launchAssistant() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_FROM_WAKE_WORD, true)
        }
        startActivity(intent)
    }

    private fun startInForeground() {
        val channelId = "benedykt_wake"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                "Benedykt – nasłuch",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Nasłuch frazy Hej Benedykt"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Benedykt słucha")
            .setContentText("Powiedz: Hej Benedykt")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 42
    }
}
