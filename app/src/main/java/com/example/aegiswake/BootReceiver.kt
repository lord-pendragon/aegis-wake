package com.example.aegiswake   // ← must match your package name

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            // Relaunch our always-listening service
            ContextCompat.startForegroundService(
                context,
                Intent(context, MainService::class.java)
            )
        }
    }
}
