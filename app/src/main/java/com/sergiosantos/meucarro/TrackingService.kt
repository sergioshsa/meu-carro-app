package com.sergiosantos.meucarro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Servico em primeiro plano que escuta o GPS e acumula a distancia percorrida
 * no modo atual (Pessoal ou Uber). Continua rodando mesmo com o app fechado
 * ou a tela apagada, com uma notificacao fixa.
 */
class TrackingService : Service(), LocationListener {

    private var locationManager: LocationManager? = null
    private var lastLocation: Location? = null
    private var mode: String = Storage.MODE_PESSOAL

    companion object {
        const val ACTION_START = "com.sergiosantos.meucarro.START"
        const val ACTION_STOP = "com.sergiosantos.meucarro.STOP"
        const val EXTRA_MODE = "mode"
        const val CHANNEL_ID = "meucarro_tracking"
        const val NOTIF_ID = 42

        const val MIN_TIME_MS = 2000L
        const val MIN_DIST_M = 5f
        const val MAX_ACCURACY_M = 40f      // ignora fixes ruins
        const val MAX_JUMP_M = 300.0        // ignora saltos irreais entre 2 fixes
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                mode = intent?.getStringExtra(EXTRA_MODE) ?: Storage.MODE_PESSOAL
                Storage.setMode(this, mode)
                startForeground(NOTIF_ID, buildNotification())
                startTracking()
            }
        }
        return START_STICKY
    }

    private fun startTracking() {
        lastLocation = null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DIST_M, this
                )
            }
        } catch (e: SecurityException) {
            // sem permissao de localizacao: para o servico
            stopSelf()
        }
    }

    private fun stopTracking() {
        try { locationManager?.removeUpdates(this) } catch (e: Exception) {}
        locationManager = null
        Storage.setMode(this, Storage.MODE_NONE)
    }

    override fun onLocationChanged(location: Location) {
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        val prev = lastLocation
        if (prev != null) {
            val d = prev.distanceTo(location).toDouble()
            if (d in 0.5..MAX_JUMP_M) {
                Storage.addMeters(this, mode, d)
                updateNotification()
            }
        }
        lastLocation = location
    }

    // callbacks antigos exigidos por algumas versoes
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun buildNotification(): Notification {
        createChannel()
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val modeLabel = if (mode == Storage.MODE_UBER) "UBER" else "PESSOAL"
        val km = if (mode == Storage.MODE_UBER)
            Storage.getMetersUber(this) / 1000.0
        else
            Storage.getMetersPessoal(this) / 1000.0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Meu Carro - Modo $modeLabel")
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
                    CHANNEL_ID, "Registro de quilometragem",
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.description = "Notificacao ativa enquanto o app registra os km."
                nm.createNotificationChannel(ch)
            }
        }
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }
}
