package com.nyx.ai.performance

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

/**
 * Reads capability evidence from documented Android/NDK-adjacent sources. CPU topology and
 * features come from /proc/cpuinfo and cpufreq sysfs when readable; no root or hidden API is used.
 */
object DeviceIntelligence {
    fun read(context: Context): DeviceCapabilities {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)

        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
                .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

        val batteryIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPercent = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?: batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it in 0..100 }
            ?: 100
        val charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        } ?: false

        val metrics = context.resources.displayMetrics
        val configuration = context.resources.configuration
        val packageManager = context.packageManager
        val hingeSensor = runCatching {
            packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")
        }.getOrDefault(false)
        val nnapi = runCatching {
            packageManager.hasSystemFeature("android.hardware.neuralnetworks")
        }.getOrDefault(false)
        val vulkan = runCatching {
            packageManager.systemAvailableFeatures
                ?.firstOrNull { it.name == "android.hardware.vulkan.version" }
                ?.version
        }.getOrNull()
        val mediaPerformanceClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.VERSION.MEDIA_PERFORMANCE_CLASS
        } else {
            0
        }

        return DeviceCapabilities(
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Unknown" },
            model = Build.MODEL.orEmpty().ifBlank { "Unknown device" },
            socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.orEmpty() else "",
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else Build.HARDWARE.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            cpu = readCpu(),
            totalRamMb = memory.totalMem / MEBIBYTE,
            availableRamMb = memory.availMem / MEBIBYTE,
            memoryClassMb = activityManager?.memoryClass ?: 0,
            lowRamDevice = activityManager?.isLowRamDevice ?: false,
            thermalStatus = thermal,
            batteryPercent = batteryPercent,
            charging = charging,
            displayWidthPx = metrics.widthPixels,
            displayHeightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            smallestWidthDp = configuration.smallestScreenWidthDp,
            hingeSensor = hingeSensor,
            mediaPerformanceClass = mediaPerformanceClass,
            nnapiFeature = nnapi,
            vulkanFeatureVersion = vulkan,
        )
    }

    private fun readCpu(): CpuTopology {
        val lines = runCatching { File("/proc/cpuinfo").readLines() }.getOrDefault(emptyList())
        val processors = lines.count { it.trimStart().startsWith("processor") }
        val cores = (if (processors > 0) processors else Runtime.getRuntime().availableProcessors()).coerceIn(1, 32)
        val features = lines.firstOrNull { it.trimStart().startsWith("Features", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?.map { it.lowercase() }
            ?.toSet()
            .orEmpty()

        val frequencies = (0 until cores).mapNotNull { index ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$index/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()?.takeIf { it > 0 }
        }
        val maximum = frequencies.maxOrNull()
        val performance = if (maximum != null && frequencies.size == cores) {
            frequencies.count { it >= (maximum * 0.85).toLong() }.coerceIn(1, cores)
        } else {
            null
        }
        val efficiency = performance?.let { (cores - it).takeIf { remaining -> remaining > 0 } }
        return CpuTopology(
            logicalCores = cores,
            performanceCores = performance,
            efficiencyCores = efficiency,
            maxFrequencyKhz = maximum,
            features = features,
        )
    }

    private const val MEBIBYTE = 1024L * 1024L
}
