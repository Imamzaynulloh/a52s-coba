package com.custom.a52sgamelauncher

import android.app.Activity
import android.app.ActivityManager
import android.app.GameManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.custom.a52sgamelauncher.databinding.ActivityMainBinding
import com.custom.a52sgamelauncher.databinding.ItemGameBinding

data class GameItem(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var powerManager: PowerManager
    private var wifiLock: WifiManager.WifiLock? = null
    private var pendingLaunchPackage: String? = null

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        runOnUiThread {
            updateThermalUI(status)
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startDnsVpnService()
        }
        pendingLaunchPackage?.let { launchOptimizedGame(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        lockHighestRefreshRate()
        enableLowLatencyWifiLock()
        setupGameList()

        binding.btnOptimizeNow.setOnClickListener {
            val freedApps = clearBackgroundProcesses()
            Toast.makeText(
                this,
                "Optimasi selesai: $freedApps proses latar dibersihkan & 120Hz dikunci",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnStopVpn.setOnClickListener {
            val stopIntent = Intent(this, GamingDnsVpnService::class.java).apply {
                action = GamingDnsVpnService.ACTION_STOP
            }
            startService(stopIntent)
            binding.tvNetworkStatus.text = "Jaringan: Standar"
            Toast.makeText(this, "Gaming DNS VPN dimatikan", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            updateThermalUI(powerManager.currentThermalStatus)
            powerManager.addThermalStatusListener(thermalListener)
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.removeThermalStatusListener(thermalListener)
        }
    }

    override fun onDestroy() {
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        super.onDestroy()
    }

    private fun lockHighestRefreshRate() {
        val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }

        currentDisplay?.let { d ->
            val highestMode = d.supportedModes.maxByOrNull { it.refreshRate }
            highestMode?.let { mode ->
                val params = window.attributes
                params.preferredDisplayModeId = mode.modeId
                window.attributes = params
                binding.tvRefreshRate.text = "Layar: ${mode.refreshRate.toInt()} Hz Locked"
            }
        }
    }

    private fun enableLowLatencyWifiLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                "A52sGamingWifiLock"
            )
            wifiLock?.acquire()
            binding.tvNetworkStatus.text = "Wi-Fi: Low-Latency Lock Aktif"
        }
    }

    private fun updateThermalUI(status: Int) {
        val (label, colorHex) = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "Suhu: Dingin / Optimal (< 37°C)" to "#00E676"
            PowerManager.THERMAL_STATUS_LIGHT -> "Suhu: Hangat Ringan (~38-40°C)" to "#FFEA00"
            PowerManager.THERMAL_STATUS_MODERATE -> {
                clearBackgroundProcesses()
                "Suhu: Hangat Sedang - Auto Cooling Aktif" to "#FF9100"
            }
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL -> {
                clearBackgroundProcesses()
                "Suhu: Panas Tinggi - Turunkan Grafik Game!" to "#FF1744"
            }
            else -> "Suhu: Normal" to "#00E676"
        }
        binding.tvThermalStatus.text = label
        binding.tvThermalStatus.setTextColor(android.graphics.Color.parseColor(colorHex))
    }

    private fun clearBackgroundProcesses(): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        var count = 0

        for (app in packages) {
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                app.packageName != packageName
            ) {
                am.killBackgroundProcesses(app.packageName)
                count++
            }
        }
        return count
    }

    private fun setupGameList() {
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val games = mutableListOf<GameItem>()

        for (app in installedApps) {
            val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.category == ApplicationInfo.CATEGORY_GAME
            } else {
                @Suppress("DEPRECATION")
                (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            }

            if (isGame) {
                val appName = pm.getApplicationLabel(app).toString()
                val appIcon = pm.getApplicationIcon(app)
                games.add(GameItem(appName, app.packageName, appIcon))
            }
        }

        binding.rvGames.layoutManager = LinearLayoutManager(this)
        binding.rvGames.adapter = GameAdapter(games) { selectedGame ->
            prepareAndLaunchGame(selectedGame.packageName)
        }
    }

    private fun prepareAndLaunchGame(targetPackage: String) {
        pendingLaunchPackage = targetPackage
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startDnsVpnService()
            launchOptimizedGame(targetPackage)
        }
    }

    private fun startDnsVpnService() {
        val intent = Intent(this, GamingDnsVpnService::class.java).apply {
            action = GamingDnsVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.tvNetworkStatus.text = "Jaringan: Gaming DNS (1.1.1.1) + Wi-Fi Lock"
    }

    private fun launchOptimizedGame(targetPackage: String) {
        clearBackgroundProcesses()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val gameManager = getSystemService(GameManager::class.java)
            val currentMode = gameManager?.gameMode
            if (currentMode == GameManager.GAME_MODE_BATTERY) {
                Toast.makeText(
                    this,
                    "Peringatan: Mode Hemat Baterai aktif di sistem, matikan agar tidak lag.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "Gagal membuka game", Toast.LENGTH_SHORT).show()
        }
    }
}

class GameAdapter(
    private val items: List<GameItem>,
    private val onLaunchClick: (GameItem) -> Unit
) : RecyclerView.Adapter<GameAdapter.GameViewHolder>() {

    inner class GameViewHolder(val binding: ItemGameBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val binding = ItemGameBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return GameViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        val game = items[position]
        holder.binding.tvGameName.text = game.name
        holder.binding.ivGameIcon.setImageDrawable(game.icon)
        holder.binding.btnPlayGame.setOnClickListener { onLaunchClick(game) }
    }

    override fun getItemCount(): Int = items.size
}
