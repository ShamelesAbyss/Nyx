package com.nyx.ai.autonomy

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs

/** Reads supported Android runtime signals; no vendor or Pixel-specific API is used. */
object DeviceResourceReader {
    fun read(context: Context): DeviceResources {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        val totalMb = memory.totalMem / (1024L * 1024L)
        val availMb = memory.availMem / (1024L * 1024L)

        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            ?: batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it in 0..100 }
            ?: 100
        val charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        } ?: false

        val storageMb = runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes / (1024L * 1024L)
        }.getOrDefault(0L)

        return DeviceResources(
            totalMemMb = totalMb,
            availMemMb = availMb,
            lowMemory = memory.lowMemory,
            thermalStatus = thermal,
            batteryPercent = percent,
            charging = charging,
            availableStorageMb = storageMb,
        )
    }
}
