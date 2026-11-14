package com.nyx.ai.performance

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class PerformanceSnapshot(
    val capabilities: DeviceCapabilities? = null,
    val mode: OperatingMode = OperatingMode.ADAPTIVE,
    val level: PerformanceLevel = PerformanceLevel.BALANCED,
    val tuning: RuntimeTuning = RuntimeTuning(
        level = PerformanceLevel.BALANCED,
        nativeThreads = 4,
        autonomyParallelism = 2,
        streamPublishIntervalMs = 60,
        idleUnloadMinutes = 0,
        calibrated = false,
        reason = "Performance state has not been sampled yet.",
    ),
    val adaptiveThreads: Boolean = true,
    val calibration: CalibrationResult? = null,
    val calibrationValid: Boolean = false,
    val calibrationRunning: Boolean = false,
    val message: String = "",
    val refreshedAtEpochMs: Long = 0,
)

/**
 * Persists the selected operating mode and the latest bounded calibration. All tuning is
 * recalculated from observed device state; no fixed per-chipset performance promise is stored.
 */
object PerformanceController {
    private const val PREFS = "nyx_performance"
    private const val KEY_MODE = "operating_mode"
    private const val KEY_ADAPTIVE_THREADS = "adaptive_threads"
    private const val KEY_CALIBRATION = "calibration_json"

    private var appContext: Context? = null
    private val governor = TuningGovernor()
    private val mutableState = MutableStateFlow(PerformanceSnapshot())
    val state: StateFlow<PerformanceSnapshot> = mutableState.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val mode = runCatching {
            OperatingMode.valueOf(prefs().getString(KEY_MODE, OperatingMode.ADAPTIVE.name) ?: OperatingMode.ADAPTIVE.name)
        }.getOrDefault(OperatingMode.ADAPTIVE)
        mutableState.update {
            it.copy(mode = mode, adaptiveThreads = prefs().getBoolean(KEY_ADAPTIVE_THREADS, true))
        }
        restoreCalibration()
        refresh()
    }

    @Synchronized
    fun refresh(activeModelPath: String? = null, activeModelSizeBytes: Long? = null): PerformanceSnapshot {
        val context = appContext ?: return mutableState.value
        val capabilities = runCatching { DeviceIntelligence.read(context) }.getOrNull()
            ?: return mutableState.value.copy(message = "Device capability read failed.")
        val current = mutableState.value
        val desired = PerformancePolicy.effectiveLevel(current.mode, capabilities)
        val level = PerformanceLevel.entries.first { it.index == governor.sample(desired.index) }
        val calibration = current.calibration
        val valid = calibration != null &&
            activeModelPath != null &&
            activeModelSizeBytes != null &&
            calibration.isValidFor(activeModelPath, activeModelSizeBytes, runtimeVersion(), capabilities.fingerprint) &&
            !calibration.degraded
        val tuning = PerformancePolicy.tuning(capabilities, current.mode, level, calibration, valid)
        val snapshot = current.copy(
            capabilities = capabilities,
            level = level,
            tuning = tuning,
            calibrationValid = valid,
            refreshedAtEpochMs = System.currentTimeMillis(),
            message = "",
        )
        mutableState.value = snapshot
        return snapshot
    }

    @Synchronized
    fun setMode(mode: OperatingMode): PerformanceSnapshot {
        prefs().edit().putString(KEY_MODE, mode.name).apply()
        governor.reset()
        mutableState.update { it.copy(mode = mode) }
        return refresh()
    }

    fun setAdaptiveThreads(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_ADAPTIVE_THREADS, enabled).apply()
        mutableState.update { it.copy(adaptiveThreads = enabled) }
    }

    fun setCalibrationRunning(running: Boolean, message: String = "") {
        mutableState.update { it.copy(calibrationRunning = running, message = message) }
    }

    fun saveCalibration(result: CalibrationResult) {
        prefs().edit().putString(KEY_CALIBRATION, encode(result).toString()).apply()
        mutableState.update { it.copy(calibration = result, message = "Calibration saved for this model and runtime.") }
    }

    fun clearCalibration() {
        prefs().edit().remove(KEY_CALIBRATION).apply()
        mutableState.update { it.copy(calibration = null, calibrationValid = false, message = "Calibration cleared.") }
    }

    fun calibration(): CalibrationResult? = mutableState.value.calibration

    fun tuning(): RuntimeTuning = mutableState.value.tuning

    fun mode(): OperatingMode = mutableState.value.mode

    fun adaptiveThreads(): Boolean = mutableState.value.adaptiveThreads

    fun runtimeVersion(): String {
        val context = appContext ?: return "unknown"
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "unknown" }
    }

    private fun prefs() = requireNotNull(appContext) { "PerformanceController.initialize(context) must be called first" }
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encode(result: CalibrationResult): JSONObject = JSONObject().apply {
        put("modelPath", result.modelPath)
        put("modelSizeBytes", result.modelSizeBytes)
        put("runtimeVersion", result.runtimeVersion)
        put("deviceFingerprint", result.deviceFingerprint)
        put("measuredAtEpochMs", result.measuredAtEpochMs)
        put("bestThreads", result.bestThreads)
        put("bestTokensPerSecond", result.bestTokensPerSecond)
        put("thermalStatus", result.thermalStatus)
        put("batteryPercent", result.batteryPercent)
        put("charging", result.charging)
        put("samples", JSONArray().apply {
            result.samples.forEach { sample ->
                put(JSONObject().apply {
                    put("threads", sample.threads)
                    put("tokensPerSecond", sample.tokensPerSecond)
                    put("loadMillis", sample.loadMillis)
                    put("generatedTokens", sample.generatedTokens)
                })
            }
        })
    }

    private fun decode(raw: String): CalibrationResult? = runCatching {
        val json = JSONObject(raw)
        val samples = json.optJSONArray("samples") ?: JSONArray()
        val parsed = buildList {
            for (index in 0 until samples.length()) {
                val sample = samples.optJSONObject(index) ?: continue
                add(
                    CalibrationSample(
                        threads = sample.optInt("threads"),
                        tokensPerSecond = sample.optDouble("tokensPerSecond"),
                        loadMillis = sample.optLong("loadMillis"),
                        generatedTokens = sample.optInt("generatedTokens"),
                    )
                )
            }
        }
        CalibrationResult(
            modelPath = json.getString("modelPath"),
            modelSizeBytes = json.getLong("modelSizeBytes"),
            runtimeVersion = json.getString("runtimeVersion"),
            deviceFingerprint = json.getString("deviceFingerprint"),
            measuredAtEpochMs = json.getLong("measuredAtEpochMs"),
            bestThreads = json.getInt("bestThreads"),
            bestTokensPerSecond = json.getDouble("bestTokensPerSecond"),
            samples = parsed,
            thermalStatus = json.optInt("thermalStatus"),
            batteryPercent = json.optInt("batteryPercent"),
            charging = json.optBoolean("charging"),
        )
    }.getOrNull()

    /** Loads persisted calibration without requiring a device refresh. */
    fun restoreCalibration() {
        val stored = prefs().getString(KEY_CALIBRATION, null) ?: return
        decode(stored)?.let { result -> mutableState.update { it.copy(calibration = result) } }
    }
}
