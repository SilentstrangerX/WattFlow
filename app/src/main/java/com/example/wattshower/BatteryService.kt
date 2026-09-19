package com.battery.wattflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class BatteryService : Service() {

    companion object {
        private const val CHANNEL_ID = "battery_monitor_channel"
        private const val CHANNEL_NAME = "Battery Monitor"
        private const val NOTIFICATION_ID = 1001

        private const val RATE_WINDOW_MS = 3 * 60 * 1000L
        private const val RATE_WINDOW_DISCHARGE_MS = 10 * 60 * 1000L
        private const val RATE_WINDOW_CHARGING_MID_MS = 6 * 60 * 1000L
        private const val RATE_WINDOW_CHARGING_TOP_MS = 10 * 60 * 1000L

        private const val RATE_RECALC_MS = 60_000L
        private const val EMA_ALPHA = 0.4

        private const val TOPPING_OFF_PERCENT = 90
        private const val TOPPING_OFF_RATE = 0.02

        private const val ROUNDING_PERCENT = 90
        private const val ROUNDING_STEP_MIN = 5

        private const val MAX_WINDOW_SAMPLES = 120
        private const val POWER_AVG_SAMPLES = 60

        private var BATTERY_CAPACITY_MAH = 5000.0
    }

    private lateinit var batteryManager: BatteryManager
    private lateinit var notificationManager: NotificationManager
    private val handler = Handler(Looper.getMainLooper())

    private var voltageMv: Int = 0
    private var currentUa: Int = Int.MIN_VALUE

    private var level: Int = 0
    private var scale: Int = 100
    private var charging: Boolean = false

    private var maxWattage: Int = 25

    private var capacityCalibrated: Boolean = false

    private data class Sample(
        val timeMs: Long,
        val percent: Double
    )

    private val window = ArrayDeque<Sample>()

    private var emaRatePerMin: Double? = null
    private var hasMeasuredRate: Boolean = false
    private var lastRateCalcMs: Long = 0L

    private var learningStartMs: Long = 0L

    private var receiverRegistered = false

    private var updateIntervalMs = 1000L

    private val powerSamples = ArrayDeque<Double>()

    private data class PowerSample(
        val timeMs: Long,
        val watts: Double
    )

    private val dischargePowerSamples = ArrayDeque<PowerSample>()

    private var dischargeWindowMs: Long = 60_000L

    private var dischargeSessionStartMs: Long = 0L

    private var lockedDischargeAverageText: String? = null

    private var lockedDischargeWindowIndex: Long = -1L

    private var lockedDischargeSessionStartMs: Long = 0L

    private val ticker = object : Runnable {
        override fun run() {
            pollAndUpdate()
            handler.postDelayed(this, updateIntervalMs)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            ingestBatteryIntent(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()

        learningStartMs = System.currentTimeMillis()

        batteryManager =
            getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            buildNotification()
        )

        registerBatteryReceiver()

        handler.post(ticker)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val prefs = getSharedPreferences(
            "WattShowerPrefs",
            Context.MODE_PRIVATE
        )

        updateIntervalMs =
            prefs.getInt(
                "update_interval_sec",
                3
            ) * 1000L

        maxWattage =
            prefs.getInt(
                "max_wattage",
                25
            )

        val dischargeMin =
            prefs.getInt(
                "discharge_avg_min",
                1
            )

        dischargeWindowMs =
            dischargeMin * 60_000L

        // ---- CHANGED: prefer user override, fall back to auto-detected default ----
        val userOverride = prefs.getInt("battery_capacity_mah", 0)
        val autoDefault = prefs.getInt("battery_capacity_default_mah", 0)

        val effectiveCapacity =
            if (userOverride > 0) userOverride
            else autoDefault

        if (effectiveCapacity > 0) {
            BATTERY_CAPACITY_MAH = effectiveCapacity.toDouble()
            capacityCalibrated = true
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)

        if (receiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {
            }

            receiverRegistered = false
        }

        super.onDestroy()
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(
            Intent.ACTION_BATTERY_CHANGED
        )

        val sticky = registerReceiver(
            batteryReceiver,
            filter
        )

        receiverRegistered = true

        sticky?.let {
            ingestBatteryIntent(it)
        }
    }

    private fun ingestBatteryIntent(intent: Intent) {
        val lvl = intent.getIntExtra(
            BatteryManager.EXTRA_LEVEL,
            -1
        )

        val sc = intent.getIntExtra(
            BatteryManager.EXTRA_SCALE,
            100
        )

        val volt = intent.getIntExtra(
            BatteryManager.EXTRA_VOLTAGE,
            -1
        )

        val status = intent.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            -1
        )

        if (lvl >= 0 && sc > 0) {
            level = lvl
            scale = sc
        }

        if (volt > 0) {
            voltageMv = volt
        }

        val nowCharging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

        if (nowCharging != charging) {
            resetRateState()
        }

        charging = nowCharging

        recordPercentSample(
            System.currentTimeMillis(),
            currentPercent()
        )
    }

    private fun pollAndUpdate() {
        val nowUa =
            batteryManager.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CURRENT_NOW
            )

        if (nowUa != Int.MIN_VALUE) {
            currentUa = nowUa
        }

        val sampleW = computeWatts()

        if (
            sampleW.isFinite() &&
            sampleW > 0.0
        ) {
            powerSamples.addLast(sampleW)

            while (
                powerSamples.size > POWER_AVG_SAMPLES
            ) {
                powerSamples.removeFirst()
            }

            if (!charging) {
                val now = System.currentTimeMillis()

                if (dischargeSessionStartMs == 0L) {
                    dischargeSessionStartMs = now
                }

                dischargePowerSamples.addLast(
                    PowerSample(
                        now,
                        sampleW
                    )
                )

                while (
                    dischargePowerSamples.isNotEmpty() &&
                    now - dischargePowerSamples.first().timeMs >
                    dischargeWindowMs
                ) {
                    dischargePowerSamples.removeFirst()
                }
            } else {
                dischargePowerSamples.clear()
                dischargeSessionStartMs = 0L
            }
        }

        if (!capacityCalibrated) {
            calibrateCapacity()
        }

        recordPercentSample(
            System.currentTimeMillis(),
            currentPercent()
        )

        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification()
        )
    }

    private fun currentPercent(): Double =
        level * 100.0 / scale

    private fun windowMsFor(
        percent: Double
    ): Long =
        when {
            charging && percent >= 90.0 ->
                RATE_WINDOW_CHARGING_TOP_MS

            charging && percent >= 80.0 ->
                RATE_WINDOW_CHARGING_MID_MS

            charging ->
                RATE_WINDOW_MS

            else ->
                RATE_WINDOW_DISCHARGE_MS
        }

    private fun recordPercentSample(
        nowMs: Long,
        percent: Double
    ) {
        val last = window.lastOrNull()

        if (
            last == null ||
            last.percent != percent
        ) {
            window.addLast(
                Sample(
                    nowMs,
                    percent
                )
            )

            trimWindow(
                nowMs,
                percent
            )

            recomputeRate(nowMs)

        } else if (
            nowMs - lastRateCalcMs >= RATE_RECALC_MS
        ) {
            trimWindow(
                nowMs,
                percent
            )

            recomputeRate(nowMs)
        }
    }

    private fun trimWindow(
        nowMs: Long,
        percent: Double
    ) {
        val maxAgeMs =
            windowMsFor(percent)

        while (
            window.size > 2 &&
            nowMs - window.first().timeMs > maxAgeMs
        ) {
            window.removeFirst()
        }

        while (
            window.size > MAX_WINDOW_SAMPLES
        ) {
            window.removeFirst()
        }
    }

    private fun recomputeRate(
        nowMs: Long
    ) {
        lastRateCalcMs = nowMs

        if (window.size < 2) {
            return
        }

        val oldest = window.first()
        val newest = window.last()

        val elapsedMin =
            (
                    newest.timeMs -
                            oldest.timeMs
                    ) / 60_000.0

        if (elapsedMin <= 0.0) {
            return
        }

        val rawRatePerMin =
            (
                    newest.percent -
                            oldest.percent
                    ) / elapsedMin

        val previous = emaRatePerMin

        emaRatePerMin =
            if (previous == null) {
                rawRatePerMin
            } else {
                EMA_ALPHA * rawRatePerMin +
                        (1.0 - EMA_ALPHA) * previous
            }

        hasMeasuredRate = true
    }

    private fun resetRateState() {
        window.clear()
        powerSamples.clear()

        dischargePowerSamples.clear()
        dischargeSessionStartMs = 0L

        lockedDischargeAverageText = null
        lockedDischargeWindowIndex = -1L
        lockedDischargeSessionStartMs = 0L

        emaRatePerMin = null
        hasMeasuredRate = false
        lastRateCalcMs = 0L

        learningStartMs =
            System.currentTimeMillis()
    }

    // =====================================================================
    // Calibration: only writes to battery_capacity_default_mah.
    // Never touches battery_capacity_mah (the user's override).
    // =====================================================================
    private fun calibrateCapacity() {
        val counterUah =
            batteryManager.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
            )

        if (counterUah <= 0) return

        val percent = currentPercent()

        if (percent < 20.0 || percent > 80.0) return

        val estimated =
            (counterUah / 1000.0) / (percent / 100.0)

        if (estimated < 2000.0 || estimated > 10000.0) return

        BATTERY_CAPACITY_MAH = estimated
        capacityCalibrated = true

        // CHANGED: save to default key only, so user override survives
        getSharedPreferences("WattShowerPrefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("battery_capacity_default_mah", estimated.toInt())
            .apply()
    }

    private fun chargingSpeedLabel(watts: Double): String {
        if (maxWattage <= 0) return ""
        val ratio = watts / maxWattage
        return when {
            ratio < 0.10 -> "Very Slow"
            ratio < 0.30 -> "Slow"
            ratio < 0.55 -> "Normal"
            ratio < 0.80 -> "Fast"
            else         -> "Super Fast"
        }
    }

    private fun dischargingSpeedLabel(watts: Double): String = when {
        watts < 0.5 -> "Very Low"
        watts < 1.5 -> "Low"
        watts < 3.0 -> "Normal"
        watts < 5.0 -> "High"
        else -> "Very High"
    }

    private fun buildNotification(): Notification {
        val watts = computeWatts()

        val title =
            if (charging) {
                String.format(
                    Locale.US,
                    "⚡ Charging • %.1f W (%s)",
                    watts,
                    chargingSpeedLabel(watts)
                )
            } else {
                String.format(
                    Locale.US,
                    "🔋 Discharging • %.1f W (%s)",
                    watts,
                    dischargingSpeedLabel(watts)
                )
            }

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(title)
            .setContentText(
                buildStatusText()
            )
            .setSmallIcon(
                R.drawable.ic_notification
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .build()
    }

    private fun computeWatts(): Double {
        if (
            currentUa == Int.MIN_VALUE ||
            voltageMv <= 0
        ) {
            return 0.0
        }

        return abs(currentUa).toDouble() *
                voltageMv /
                1e9
    }

    private fun buildStatusText(): String {
        if (charging) {
            if (powerSamples.isNotEmpty()) {
                val estimate =
                    estimateFromAveragePower()

                if (estimate != null) {
                    return estimate
                }
            }

            val rate = emaRatePerMin

            if (
                hasMeasuredRate &&
                rate != null
            ) {
                return formatFromMeasuredRate(rate)
            }

            return "calculating…"
        }

        if (dischargeSessionStartMs == 0L) {
            dischargeSessionStartMs =
                System.currentTimeMillis()
        }

        val dischargeElapsedMs =
            System.currentTimeMillis() -
                    dischargeSessionStartMs

        if (
            dischargeElapsedMs <
            dischargeWindowMs
        ) {
            val realtime =
                estimateFromAveragePower()

            if (realtime != null) {
                return realtime
            }

            val rate = emaRatePerMin

            if (
                hasMeasuredRate &&
                rate != null
            ) {
                return formatFromMeasuredRate(rate)
            }

            val remaining =
                (
                        dischargeWindowMs -
                                dischargeElapsedMs
                        ) / 1000

            return "calculating… (${remaining}s)"
        }

        val averageEstimate =
            estimateFromDischargeAverage()

        if (averageEstimate != null) {
            return averageEstimate
        }

        val realtime =
            estimateFromAveragePower()

        if (realtime != null) {
            return realtime
        }

        val rate = emaRatePerMin

        if (
            hasMeasuredRate &&
            rate != null
        ) {
            return formatFromMeasuredRate(rate)
        }

        return "calculating…"
    }

    private fun estimateFromAveragePower(): String? {
        val powerW =
            powerSamples.lastOrNull()
                ?: return null

        if (
            !powerW.isFinite() ||
            powerW < 0.05
        ) {
            return null
        }

        if (voltageMv <= 0) {
            return null
        }

        val percent =
            currentPercent()

        if (
            !percent.isFinite() ||
            percent <= 0.0 ||
            percent > 100.0
        ) {
            return null
        }

        val currentMa =
            (
                    powerW *
                            1_000_000.0
                    ) /
                    voltageMv.toDouble()

        if (
            !currentMa.isFinite() ||
            currentMa <= 0.0
        ) {
            return null
        }

        val rateMagnitudePerMin =
            (
                    currentMa /
                            BATTERY_CAPACITY_MAH
                    ) *
                    100.0 /
                    60.0

        if (
            !rateMagnitudePerMin.isFinite() ||
            rateMagnitudePerMin <= 0.0
        ) {
            return null
        }

        val ratePerMin =
            if (charging) {
                rateMagnitudePerMin
            } else {
                -rateMagnitudePerMin
            }

        val minutes =
            if (charging) {
                val remainingPercent =
                    100.0 - percent

                if (remainingPercent <= 0.0) {
                    return null
                }

                remainingPercent /
                        rateMagnitudePerMin

            } else {
                percent /
                        rateMagnitudePerMin
            }

        if (
            !minutes.isFinite() ||
            minutes <= 0.0 ||
            minutes > 6000.0
        ) {
            return null
        }

        val rateStr =
            String.format(
                Locale.US,
                "≈ %+.2f%%/min",
                ratePerMin
            )

        val timeStr =
            formatDuration(minutes)

        return if (charging) {
            "$rateStr • ~$timeStr to full (est.)"
        } else {
            "$rateStr • ~$timeStr remaining (est.)"
        }
    }

    private fun estimateFromDischargeAverage(): String? {
        val sessionStart =
            dischargeSessionStartMs

        if (
            sessionStart == 0L ||
            dischargeWindowMs <= 0L
        ) {
            return null
        }

        if (
            lockedDischargeSessionStartMs !=
            sessionStart
        ) {
            lockedDischargeSessionStartMs =
                sessionStart

            lockedDischargeWindowIndex = -1L
            lockedDischargeAverageText = null
        }

        val now =
            System.currentTimeMillis()

        val elapsedMs =
            now - sessionStart

        if (
            elapsedMs <
            dischargeWindowMs
        ) {
            return null
        }

        val completedWindows =
            elapsedMs /
                    dischargeWindowMs

        val currentWindowIndex =
            completedWindows - 1L

        if (
            currentWindowIndex ==
            lockedDischargeWindowIndex
        ) {
            return lockedDischargeAverageText
        }

        val windowStartMs =
            sessionStart +
                    (
                            currentWindowIndex *
                                    dischargeWindowMs
                            )

        val windowEndMs =
            windowStartMs +
                    dischargeWindowMs

        var sum = 0.0
        var sampleCount = 0

        for (sample in dischargePowerSamples) {
            if (
                sample.timeMs >= windowStartMs &&
                sample.timeMs <= windowEndMs
            ) {
                sum += sample.watts
                sampleCount++
            }
        }

        if (sampleCount < 5) {
            return null
        }

        val avgPowerW =
            sum /
                    sampleCount

        if (
            !avgPowerW.isFinite() ||
            avgPowerW < 0.05
        ) {
            return null
        }

        if (voltageMv <= 0) {
            return null
        }

        val percent =
            currentPercent()

        if (
            !percent.isFinite() ||
            percent <= 0.0 ||
            percent > 100.0
        ) {
            return null
        }

        val avgCurrentMa =
            (
                    avgPowerW *
                            1_000_000.0
                    ) /
                    voltageMv.toDouble()

        if (
            !avgCurrentMa.isFinite() ||
            avgCurrentMa <= 0.0
        ) {
            return null
        }

        val avgRateMagnitude =
            (
                    avgCurrentMa /
                            BATTERY_CAPACITY_MAH
                    ) *
                    100.0 /
                    60.0

        if (
            !avgRateMagnitude.isFinite() ||
            avgRateMagnitude <= 0.0
        ) {
            return null
        }

        val avgRatePerMin =
            -avgRateMagnitude

        val minutes =
            percent /
                    avgRateMagnitude

        if (
            !minutes.isFinite() ||
            minutes <= 0.0 ||
            minutes > 6000.0
        ) {
            return null
        }

        val rateStr =
            String.format(
                Locale.US,
                "≈ %+.2f%%/min",
                avgRatePerMin
            )

        val timeStr =
            formatDuration(minutes)

        val lockedText =
            "$rateStr • ~$timeStr remaining (avg hour)"

        lockedDischargeWindowIndex =
            currentWindowIndex

        lockedDischargeAverageText =
            lockedText

        return lockedText
    }

    private fun formatFromMeasuredRate(
        rate: Double
    ): String {
        val percent =
            currentPercent()

        val rateStr =
            String.format(
                Locale.US,
                "%+.2f%%/min",
                rate
            )

        if (charging) {
            if (
                percent >= TOPPING_OFF_PERCENT &&
                rate < TOPPING_OFF_RATE
            ) {
                return "$rateStr • topping off"
            }

            if (rate <= 0.0) {
                return "calculating…"
            }

            val minutesToFull =
                (100.0 - percent) /
                        rate

            val displayMinutes =
                if (
                    percent >=
                    ROUNDING_PERCENT
                ) {
                    val rounded =
                        (
                                minutesToFull /
                                        ROUNDING_STEP_MIN
                                ).roundToInt() *
                                ROUNDING_STEP_MIN

                    rounded.toDouble()
                } else {
                    minutesToFull
                }

            return "$rateStr • ~${formatDuration(displayMinutes)} to full"

        } else {
            if (rate >= 0.0) {
                return "calculating…"
            }

            val minutesRemaining =
                percent /
                        (-rate)

            return "$rateStr • ~${formatDuration(minutesRemaining)} remaining"
        }
    }

    private fun formatDuration(
        minutes: Double
    ): String {
        val total =
            minutes
                .roundToInt()
                .coerceAtLeast(0)

        val h =
            total / 60

        val m =
            total % 60

        return if (h > 0) {
            "${h}h ${m}m"
        } else {
            "${m}m"
        }
    }

    private fun createNotificationChannel() {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description =
                        "Live battery charging status"

                    setShowBadge(false)
                }

            notificationManager.createNotificationChannel(
                channel
            )
        }
    }
}