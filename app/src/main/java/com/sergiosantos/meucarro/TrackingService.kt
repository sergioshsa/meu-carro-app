package com.sergiosantos.meucarro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Serviço em primeiro plano: usa o FusedLocationProvider (GPS + wifi + sensores)
 * para medir a distância com mais precisão. Só acumula quilômetro quando o carro
 * está de fato em movimento (pela velocidade), evitando "km fantasma" parado.
 * No modo AUTOMÁTICO decide Uber x Pessoal pelo uso recente do app Uber Driver.
 */
class TrackingService : Service() {

    private var fused: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null
    private var lastLocation: Location? = null
    private var fixedMode: String = Storage.MODE_PESSOAL
    private var autoMode: Boolean = false
    private var lastEffectiveMode: String = Storage.MODE_PESSOAL

    companion object {
        const val ACTION_START = "com.sergiosantos.meucarro.START"
        const val ACTION_STOP = "com.sergiosantos.meucarro.STOP"
        const val EXTRA_MODE = "mode"
        const val CHANNEL_ID = "meucarro_tracking"
        const val NOTIF_ID = 42

        const val UPDATE_MS = 2000L
        const val MIN_UPDATE_MS = 1000L
        const val MAX_ACCURACY_M = 30f      // ignora fixes ruins
        const val MAX_JUMP_M = 300.0        // ignora saltos irreais
        const val MIN_SPEED_MS = 0.6f       // ~2,2 km/h: abaixo disso considera parado
        const val MIN_DIST_IF_NO_SPEED = 8.0 // sem velocidade, só conta se andou > 8 m

        const val UBER_PACKAGE = "com.ubercab.driver"
        const val UBER_ACTIVE_WINDOW_MS = 10 * 60 * 1000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                Storage.setAutoEnabled(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val requested = intent?.getStringExtra(EXTRA_MODE) ?: Storage.MODE_AUTO
                autoMode = (requested == Storage.MODE_AUTO)
                if (autoMode) {
                    Storage.setAutoEnabled(this, true)
                    lastEffectiveMode = detectMode()
                } else {
                    fixedMode = requested
                    lastEffectiveMode = requested
                }
                Storage.setMode(this, lastEffectiveMode)
                startForeground(NOTIF_ID, buildNotification())
                startTracking()
            }
        }
        return START_STICKY
    }

    private fun startTracking() {
        lastLocation = null
        val client = LocationServices.getFusedLocationProviderClient(this)
        fused = client
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_MS)
            .setMinUpdateDistanceMeters(0f)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) handleLocation(loc)
            }
        }
        callback = cb
        try {
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun stopTracking() {
        try { callback?.let { fused?.removeLocationUpdates(it) } } catch (e: Exception) {}
        callback = null
        fused = null
        Storage.setMode(this, Storage.MODE_NONE)
    }

    private fun handleLocation(location: Location) {
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        val mode = if (autoMode) detectMode() else fixedMode
        if (mode != lastEffectiveMode) {
            lastEffectiveMode = mode
            Storage.setMode(this, mode)
        }
        val prev = lastLocation
        if (prev != null) {
            val d = prev.distanceTo(location).toDouble()
            val moving = location.hasSpeed() && location.speed > MIN_SPEED_MS
            if (d in 0.5..MAX_JUMP_M && (moving || d > MIN_DIST_IF_NO_SPEED)) {
                Storage.addMeters(this, mode, d)
                updateNotification()
            }
        }
        lastLocation = location
    }

    private fun detectMode(): String {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 24L * 60 * 60 * 1000, now
            )
            var uberLast = 0L
            if (stats != null) {
                for (s in stats) {
                    if (s.packageName == UBER_PACKAGE && s.lastTimeUsed > uberLast) uberLast = s.lastTimeUsed
                }
            }
            if (uberLast > 0 && (now - uberLast) <= UBER_ACTIVE_WINDOW_MS)
                Storage.MODE_UBER else Storage.MODE_PESSOAL
        } catch (e: Exception) { Storage.MODE_PESSOAL }
    }

    private fun buildNotification(): Notification {
        createChannel()
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val mode = lastEffectiveMode
        val modeLabel = if (mode == Storage.MODE_UBER) "UBER" else "PESSOAL"
        val km = if (mode == Storage.MODE_UBER)
            Storage.getMetersUber(this) / 1000.0 else Storage.getMetersPessoal(this) / 1000.0
        val title = if (autoMode) "Meu Carro - Automático ($modeLabel)" else "Meu Carro - Modo $modeLabel"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(String.format("Registrando: %.2f km", km))
            .setSmallIcon(R.drawable.ic_car)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Parar", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Registro de quilometragem", NotificationManager.IMPORTANCE_LOW
                )
                ch.description = "Notificação ativa enquanto o app registra os km."
                nm.createNotificationChannel(ch)
            }
        }
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }
}
