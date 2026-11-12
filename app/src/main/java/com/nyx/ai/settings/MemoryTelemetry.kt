package com.nyx.ai.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File

data class MemorySnapshot(
    val physicalBytes: Long, val availableBytes: Long, val lowMemory: Boolean,
    val heapClassMb: Int, val managedUsedBytes: Long, val managedMaxBytes: Long,
    val nativeAllocatedBytes: Long, val processPssKb: Int,
    val swapTotalKb: Long?, val swapFreeKb: Long?, val reclaimableKb: Long?,
    val recentExit: String,
)

object MemoryTelemetry {
    fun snapshot(context: Context): MemorySnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val proc = runCatching { File("/proc/meminfo").useLines { it.take(80).associate { row ->
            val key = row.substringBefore(':'); val number = row.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull()
            key to number
        } } }.getOrDefault(emptyMap())
        val own = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val exit = if (Build.VERSION.SDK_INT >= 30) runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 3).firstOrNull()?.let {
                val description = it.description.orEmpty()
                if (description.contains("MemoryLimiter:AnonSwap")) "Android memory limiter termination (REASON_OTHER)"
                else "Reason ${it.reason}: ${description.take(180)}"
            } ?: "No historical exit available"
        }.getOrDefault("Historical exits not accessible") else "Requires Android 11+"
        return MemorySnapshot(memory.totalMem, memory.availMem, memory.lowMemory, am.memoryClass,
            runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), Debug.getNativeHeapAllocatedSize(), own.totalPss,
            proc["SwapTotal"], proc["SwapFree"], proc["SReclaimable"], exit)
    }
    fun describe(snapshot: MemorySnapshot): String = with(snapshot) {
        "System RAM: ${physicalBytes / 1048576} MB · Available: ${availableBytes / 1048576} MB (ActivityManager.MemoryInfo)\n" +
        "Low-memory flag: $lowMemory\n" +
        "Managed heap: ${managedUsedBytes / 1048576}/${managedMaxBytes / 1048576} MB (Runtime); heap class: $heapClassMb MB — NOT device RAM\n" +
        "Native allocator: ${nativeAllocatedBytes / 1048576} MB (Debug); process PSS: ${processPssKb / 1024} MB — snapshots, not peak\n" +
        "System swap total/free: ${swapTotalKb?.div(1024) ?: "unavailable"}/${swapFreeKb?.div(1024) ?: "unavailable"} MB; reclaimable slab: ${reclaimableKb?.div(1024) ?: "unavailable"} MB (/proc/meminfo where permitted)\n" +
        "Recent exit: $recentExit\n" +
        "Model file bytes, resident mapped weights and KV cache are distinct. Other processes, Android17 per-app native limits and true peak residency cannot be inferred from available RAM or heap class. No root or security bypass is used."
    }
}
