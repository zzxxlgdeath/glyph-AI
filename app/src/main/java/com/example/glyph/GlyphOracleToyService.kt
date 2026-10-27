package com.example.glyph

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.OracleSettingsRepository
import com.nothing.ketchum.GlyphToy

/**
 * Nothing Phone (3) Glyph Toy Service registered in AndroidManifest.xml with
 * `<action android:name="com.nothing.glyph.TOY"/>`, `com.nothing.glyph.toy.longpress = 1`,
 * and `android:foregroundServiceType="microphone"`.
 *
 * Supports both standard on-demand Toy binding (`START_NOT_STICKY`) and an opt-in persistent
 * foreground microphone mode (`backgroundServiceEnabled`) for lock-screen voice capture on Android 14+.
 */
class GlyphOracleToyService : Service() {

    private lateinit var coordinator: GlyphOracleCoordinator

    private val serviceHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                GlyphToy.MSG_GLYPH_TOY -> {
                    val bundle = msg.data
                    val event = bundle?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
                    coordinator.ensureToyActiveFromEvent()
                    when (event) {
                        GlyphToy.EVENT_CHANGE -> {
                            promoteToForegroundMicrophoneService()
                            coordinator.onGlyphButtonLongPress()
                        }
                        GlyphToy.EVENT_AOD -> {
                            coordinator.onAodTick()
                        }
                        GlyphToy.EVENT_ACTION_DOWN -> {
                            // Touch-down on Glyph Button
                        }
                        GlyphToy.EVENT_ACTION_UP -> {
                            // Touch-up on Glyph Button
                        }
                    }
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private val serviceMessenger = Messenger(serviceHandler)

    override fun onCreate() {
        super.onCreate()
        coordinator = GlyphOracleCoordinator.getInstance(applicationContext)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForegroundMicrophoneService()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        promoteToForegroundMicrophoneService()
        coordinator.onToyServiceBind()
        return serviceMessenger.binder
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        promoteToForegroundMicrophoneService()
        coordinator.onToyServiceBind()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        coordinator.onToyServiceUnbind()
        return true
    }

    override fun onDestroy() {
        stopForegroundSafely()
        super.onDestroy()
    }

    private fun promoteToForegroundMicrophoneService() {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) return

        try {
            val notification = buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
            // Ignored if OS restricts foreground promotion in specific background states
        }
    }

    private fun stopForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            runCatching { manager.deleteNotificationChannel("glyph_oracle_mic_channel") }
            val isEn = OracleSettingsRepository
                .getInstance(applicationContext)
                .settings.value.isEnglish
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Glyph AI Oracle",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = if (isEn) {
                    "Microphone access for Nothing Glyph Toy"
                } else {
                    "Доступ к микрофону для Nothing Glyph Toy"
                }
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val isEn = OracleSettingsRepository
            .getInstance(applicationContext)
            .settings.value.isEnglish
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Glyph Oracle")
            .setContentText(
                if (isEn) {
                    "Ready for voice questions via Glyph Button"
                } else {
                    "Готов к голосовым вопросам по кнопке Glyph Button"
                }
            )
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "glyph_oracle_mic_channel_silent"
        private const val NOTIFICATION_ID = 2525

        /**
         * Ensures the foreground microphone service is started while the app has microphone
         * permission so lock-screen Glyph Button voice capture works seamlessly on Android 14+.
         */
        fun syncBackgroundServiceState(context: Context) {
            val appCtx = context.applicationContext
            val hasMicPerm = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMicPerm) return

            val serviceIntent = Intent(appCtx, GlyphOracleToyService::class.java)
            try {
                ContextCompat.startForegroundService(appCtx, serviceIntent)
            } catch (_: Throwable) {
            }
        }
    }
}
