package com.battery.wattflow

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var etWattage: EditText
    private lateinit var etCapacity: EditText
    private lateinit var seekInterval: SeekBar
    private lateinit var tvIntervalLabel: TextView
    private lateinit var seekDischargeAvg: SeekBar
    private lateinit var tvDischargeAvgLabel: TextView
    private lateinit var tvSearchHint: TextView
    private lateinit var btnSearch: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnTheme: ImageView

    private var searchQuery: String = ""
    private var isDarkMode: Boolean = false

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("WattShowerPrefs", Context.MODE_PRIVATE)
        val dark = prefs.getBoolean("dark_mode", false)
        val config = Configuration(newBase.resources.configuration)
        config.uiMode =
            if (dark) Configuration.UI_MODE_NIGHT_YES
            else Configuration.UI_MODE_NIGHT_NO
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etWattage = findViewById(R.id.etWattage)
        etCapacity = findViewById(R.id.etCapacity)
        seekInterval = findViewById(R.id.seekInterval)
        tvIntervalLabel = findViewById(R.id.tvIntervalLabel)
        seekDischargeAvg = findViewById(R.id.seekDischargeAvg)
        tvDischargeAvgLabel = findViewById(R.id.tvDischargeAvgLabel)
        tvSearchHint = findViewById(R.id.tvSearchHint)
        btnSearch = findViewById(R.id.btnSearch)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnTheme = findViewById(R.id.btnTheme)

        val prefs = getSharedPreferences("WattShowerPrefs", Context.MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("dark_mode", false)

        updateThemeIcon()

        btnTheme.setOnClickListener {
            isDarkMode = !isDarkMode
            prefs.edit().putBoolean("dark_mode", isDarkMode).apply()
            recreate()
        }

        val manufacturer = Build.MANUFACTURER
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val model = Build.MODEL
        val phoneName = "$manufacturer $model".trim()
        searchQuery = "$phoneName charging watt speed"

        tvSearchHint.text = "🔍 $searchQuery"

        tvSearchHint.setOnClickListener {
            copyToClipboard(searchQuery, "Search query")
        }

        btnSearch.setOnClickListener {
            val url = "https://www.google.com/search?q=" + Uri.encode(searchQuery)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
            }
        }

        // ---- Load saved wattage (no default preload) ----
        val savedWattage = prefs.getInt("max_wattage", 0)
        if (savedWattage > 0) {
            etWattage.setText(savedWattage.toString())
        }

        // ---- Battery capacity: show detected value as gray hint ----
        val defaultCapacity = prefs.getInt("battery_capacity_default_mah", 0)
        val userCapacity = prefs.getInt("battery_capacity_mah", 0)

        if (defaultCapacity > 0) {
            etCapacity.hint = defaultCapacity.toString()
        } else {
            etCapacity.hint = "Auto-detect"
        }

        if (userCapacity > 0) {
            etCapacity.setText(userCapacity.toString())
        }

        // ---- Update interval ----
        val savedIntervalSec = prefs.getInt("update_interval_sec", 3)
        seekInterval.progress = (savedIntervalSec - 1).coerceIn(0, 4)
        tvIntervalLabel.text = labelForSeconds(savedIntervalSec)

        seekInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvIntervalLabel.text = labelForSeconds(progress + 1)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ---- Discharge window ----
        val savedDischargeMin = prefs.getInt("discharge_avg_min", 1)
        seekDischargeAvg.progress = (savedDischargeMin - 1).coerceIn(0, 9)
        tvDischargeAvgLabel.text = labelForDischargeMinutes(savedDischargeMin)

        seekDischargeAvg.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvDischargeAvgLabel.text = labelForDischargeMinutes(progress + 1)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnStart.setOnClickListener {
            val wattageText = etWattage.text.toString().trim()
            if (wattageText.isEmpty()) {
                Toast.makeText(this, "Please enter max charging speed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val wattage = wattageText.toInt()
            val intervalSec = seekInterval.progress + 1
            val dischargeMin = seekDischargeAvg.progress + 1

            // ---- Battery capacity override (0 = use auto-detected) ----
            val capacityText = etCapacity.text.toString().trim()
            val capacityOverride =
                if (capacityText.isEmpty()) 0
                else capacityText.toIntOrNull() ?: 0

            prefs.edit()
                .putInt("max_wattage", wattage)
                .putInt("update_interval_sec", intervalSec)
                .putInt("discharge_avg_min", dischargeMin)
                .putInt("battery_capacity_mah", capacityOverride)
                .apply()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        101
                    )
                }
            }

            requestIgnoreBatteryOptimizations()

            val serviceIntent = Intent(this, BatteryService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(this, "Monitoring Started!", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, BatteryService::class.java))
            Toast.makeText(this, "Monitoring Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateThemeIcon() {
        btnTheme.setImageResource(
            if (isDarkMode) R.drawable.ic_light_mode
            else R.drawable.ic_dark_mode
        )
    }

    private fun copyToClipboard(text: String, label: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    private fun labelForSeconds(seconds: Int): String = when (seconds) {
        1 -> "1s (HIGH battery usage)"
        2 -> "2s (Little high battery usage)"
        3 -> "3s (Mid battery usage)"
        4 -> "4s (Little low battery usage)"
        5 -> "5s (Low battery usage)"
        else -> "${seconds}s"
    }

    private fun labelForDischargeMinutes(minutes: Int): String = when (minutes) {
        1  -> "1 min — less accurate (fast response)"
        2  -> "2 min — less accurate"
        3  -> "3 min — moderate accuracy"
        4  -> "4 min — moderate accuracy"
        5  -> "5 min — good accuracy (gaming)"
        6  -> "6 min — good accuracy"
        7  -> "7 min — high accuracy"
        8  -> "8 min — high accuracy"
        9  -> "9 min — very high accuracy"
        10 -> "10 min — most accurate (gaming)"
        else -> "$minutes min"
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent().apply {
                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                data = Uri.parse("package:$packageName")
            }
            try { startActivity(intent) } catch (e: Exception) { e.printStackTrace() }
        }
    }
}