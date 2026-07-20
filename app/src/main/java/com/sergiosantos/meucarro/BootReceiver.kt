package com.sergiosantos.meucarro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Reinicia o modo automático após o celular ligar, se estava ativo. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && Storage.isAutoEnabled(context)) {
            try {
                val i = Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.ACTION_START)
                    .putExtra(TrackingService.EXTRA_MODE, Storage.MODE_AUTO)
                ContextCompat.startForegroundService(context, i)
            } catch (e: Exception) { /* ignora se o Android bloquear no boot */ }
        }
    }
}
