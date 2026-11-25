package com.example.greta

import android.content.*
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = c.getSharedPreferences("jv", 0)
        if (p.getBoolean("on", false) && p.getString("key", "").orEmpty().isNotBlank()) {
            val s = Intent(c, GretaService::class.java)
            // Android 14+ may block mic foreground services from boot; if so, just open the app once.
            try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s) else c.startService(s) } catch (_: Exception) {}
        }
    }
}
