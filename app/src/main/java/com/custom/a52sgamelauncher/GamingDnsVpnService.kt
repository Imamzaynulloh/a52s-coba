package com.custom.a52sgamelauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat

class GamingDnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_START = "com.custom.a52sgamelauncher.START_VPN"
        const val ACTION_STOP = "com.custom.a52sgamelauncher.STOP_VPN"
        private const val CHANNEL_ID = "gaming_dns_channel"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        startLocalDnsVpn()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gaming Low-Latency DNS",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gaming Network Optimizer Aktif")
            .setContentText("DNS Gaming (1.1.1.1) & Low-Latency Wi-Fi aktif")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
    }

    private fun startLocalDnsVpn() {
        if (vpnInterface != null) return

        try {
            val builder = Builder()
                .setSession("A52s Gaming DNS")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("1.0.0.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            builder.addRoute("1.1.1.1", 32)
            builder.addRoute("1.0.0.1", 32)
            builder.addRoute("8.8.8.8", 32)

            vpnInterface = builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopVpn() {
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
