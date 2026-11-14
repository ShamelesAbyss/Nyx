package com.nyx.ai.performance

/**
 * Pure performance policy. This file deliberately contains no Android imports so the
 * mode, thermal, calibration, and hysteresis decisions can be regression-tested on the JVM.
 *
 * SoC family detection is a presentation/thread-heuristic hint only. It is never evidence
 * that a GPU, NPU, or vendor runtime can accelerate GGUF inference.
 */

enum class OperatingMode(val label: String, val description: String) {
    ECO("Eco", "Fewer threads and one autonomous worker; release the native model after a long idle period."),
    BALANCED("Balanced", "Moderate native threading and bounded autonomous concurrency."),
    PERFORMANCE("Performance", "Use more CPU threads and allow more concurrent autonomous work when the device can sustain it."),
    ADAPTIVE("Adaptive", "Choose a level from device capability, battery, and thermal state, with hysteresis to avoid oscillation."),
}

enum class PerformanceLevel(val index: Int, val label: String) {
    ECO(0, "Eco"),
    BALANCED(1, "Balanced"),
    PERFORMANCE(2, "Performance"),
}

enum class SocFamily(val label: String) {
    TENSOR_G5("Google Tensor G5"),
    TENSOR_G4("Google Tensor G4"),
    TENSOR_G3("Google Tensor G3"),
    TENSOR_OTHER("Google Tensor (generation unconfirmed)"),
    SNAPDRAGON_8_ELITE_GEN6("Snapdragon 8 Elite Gen 6"),
    SNAPDRAGON_8_ELITE_GEN5("Snapdragon 8 Elite Gen 5"),
    SNAPDRAGON_8_ELITE("Snapdragon 8 Elite"),
    SNAPDRAGON_FLAGSHIP("Snapdragon flagship"),
    SNAPDRAGON_OTHER("Qualcomm Snapdragon"),
    OTHER_ARM64("Other ARM64"),
    UNKNOWN("Unknown"),
}

object SocClassifier {
    fun classify(socManufacturer: String, socModel: String, manufacturer: String): SocFamily {
        val text = "$socManufacturer $socModel $manufacturer".lowercase()
        return when {
            "tensor g5" in text -> SocFamily.TENSOR_G5
            "tensor g4" in text -> SocFamily.TENSOR_G4
            "tensor g3" in text -> SocFamily.TENSOR_G3
            "sm8850" in text -> SocFamily.SNAPDRAGON_8_ELITE_GEN5
            "sm8950" in text || "8 elite gen 6" in text || "elite gen6" in text -> SocFamily.SNAPDRAGON_8_ELITE_GEN6
            "sm8750" in text || "8 elite" in text -> SocFamily.SNAPDRAGON_8_ELITE
            "sm8650" in text || "sm8550" in text || "sm8475" in text || "snapdragon 8" in text -> SocFamily.SNAPDRAGON_FLAGSHIP
            "tensor" in text -> SocFamily.TENSOR_OTHER
            "qualcomm" in text || "snapdragon" in text || "qcom" in text -> SocFamily.SNAPDRAGON_OTHER
            socModel.isBlank() -> SocFamily.UNKNOWN
            else -> SocFamily.OTHER_ARM64
        }
    }
}

data class CpuTopology(
    val logicalCores: Int,
    val performanceCores: Int?,
    val efficiencyCores: Int?,
    val maxFrequencyKhz: Long?,
    val features: Set<String>,
) {
    val hasDotProduct: Boolean get() = "dotprod" in features || "asimddp" in features
    val hasI8mm: Boolean get() = "i8mm" in features
    val hasBf16: Boolean get() = "bf16" in features
    val hasSve: Boolean get() = features.any { it == "sve" || it.startsWith("sve2") }
    val summary: String get() = buildString {
        append("$logicalCores logical cores")
        performanceCores?.let { append(" · ~$it performance") }
        efficiencyCores?.let { append(" · ~$it efficiency") }
        maxFrequencyKhz?.let { append(" · max ${it / 1000} MHz") }
    }
}

data class DeviceCapabilities(
    val manufacturer: String,
    val model: String,
    val socManufacturer: String,
    val socModel: String,
    val hardware: String,
    val androidRelease: String,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val cpu: CpuTopology,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val memoryClassMb: Int,
    val lowRamDevice: Boolean,
    val thermalStatus: Int,
    val batteryPercent: Int,
    val charging: Boolean,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val densityDpi: Int,
    val smallestWidthDp: Int,
    val hingeSensor: Boolean,
    val mediaPerformanceClass: Int,
    val nnapiFeature: Boolean,
    val vulkanFeatureVersion: Int?,
) {
    val socFamily: SocFamily get() = SocClassifier.classify(socManufacturer, socModel, manufacturer)
    val isArm64: Boolean get() = supportedAbis.any { it == "arm64-v8a" }
    val foldableHint: Boolean get() = hingeSensor || smallestWidthDp >= 600
    val fingerprint: String
        get() = "$socManufacturer|$socModel|$hardware|${cpu.logicalCores}|${cpu.performanceCores ?: -1}|$totalRamMb|$sdkInt"

    val thermalLabel: String
        get() = when (thermalStatus) {
            0 -> "None"
            1 -> "Light"
            2 -> "Moderate"
            3 -> "Severe"
            4 -> "Critical"
            5 -> "Emergency"
            6 -> "Shutdown"
            else -> "Unknown ($thermalStatus)"
        }

    val displaySummary: String
        get() = "$displayWidthPx × $displayHeightPx px · ${densityDpi} dpi · ${smallestWidthDp} dp width"
}

data class CalibrationSample(
    val threads: Int,
    val tokensPerSecond: Double,
    val loadMillis: Long,
    val generatedTokens: Int,
)

data class CalibrationResult(
    val modelPath: String,
    val modelSizeBytes: Long,
    val runtimeVersion: String,
    val deviceFingerprint: String,
    val measuredAtEpochMs: Long,
    val bestThreads: Int,
    val bestTokensPerSecond: Double,
    val samples: List<CalibrationSample>,
    val thermalStatus: Int,
    val batteryPercent: Int,
    val charging: Boolean,
) {
    fun isValidFor(modelPath: String, modelSizeBytes: Long, runtimeVersion: String, deviceFingerprint: String): Boolean =
        this.modelPath == modelPath &&
            this.modelSizeBytes == modelSizeBytes &&
            this.runtimeVersion == runtimeVersion &&
            this.deviceFingerprint == deviceFingerprint

    /** A measurement taken under real thermal or battery pressure is retained for transparency but is not trusted for tuning. */
    val degraded: Boolean get() = thermalStatus >= 3 || (batteryPercent <= 15 && !charging)
}

data class RuntimeTuning(
    val level: PerformanceLevel,
    val nativeThreads: Int,
    val autonomyParallelism: Int,
    val streamPublishIntervalMs: Long,
    val idleUnloadMinutes: Int,
    val calibrated: Boolean,
    val reason: String,
)

object PerformancePolicy {
    fun desiredLevel(mode: OperatingMode, capabilities: DeviceCapabilities): PerformanceLevel = when (mode) {
        OperatingMode.ECO -> PerformanceLevel.ECO
        OperatingMode.BALANCED -> PerformanceLevel.BALANCED
        OperatingMode.PERFORMANCE -> PerformanceLevel.PERFORMANCE
        OperatingMode.ADAPTIVE -> when {
            capabilities.lowRamDevice ||
                capabilities.totalRamMb < 3072L ||
                capabilities.thermalStatus >= 3 ||
                (capabilities.batteryPercent <= 15 && !capabilities.charging) -> PerformanceLevel.ECO
            capabilities.totalRamMb < 6144L || capabilities.cpu.logicalCores <= 6 -> PerformanceLevel.BALANCED
            else -> PerformanceLevel.PERFORMANCE
        }
    }

    /** Safety ceilings apply even to a fixed mode: a hot or nearly empty battery is not a performance request. */
    fun safetyCeiling(capabilities: DeviceCapabilities): PerformanceLevel = when {
        capabilities.thermalStatus >= 4 -> PerformanceLevel.ECO
        capabilities.thermalStatus == 3 -> PerformanceLevel.BALANCED
        capabilities.batteryPercent <= 5 && !capabilities.charging -> PerformanceLevel.ECO
        capabilities.batteryPercent <= 15 && !capabilities.charging -> PerformanceLevel.BALANCED
        else -> PerformanceLevel.PERFORMANCE
    }

    fun effectiveLevel(mode: OperatingMode, capabilities: DeviceCapabilities): PerformanceLevel {
        val desired = desiredLevel(mode, capabilities)
        val ceiling = safetyCeiling(capabilities)
        return if (desired.index <= ceiling.index) desired else ceiling
    }

    fun tuning(
        capabilities: DeviceCapabilities,
        mode: OperatingMode,
        level: PerformanceLevel,
        calibration: CalibrationResult?,
        calibrationValid: Boolean,
    ): RuntimeTuning {
        val cores = capabilities.cpu.logicalCores.coerceIn(1, 32)
        val performanceCores = (capabilities.cpu.performanceCores ?: maxOf(1, cores / 2)).coerceIn(1, cores)
        val cap = when (level) {
            PerformanceLevel.ECO -> 2
            PerformanceLevel.BALANCED -> 6
            PerformanceLevel.PERFORMANCE -> 8
        }
        val heuristic = when (level) {
            PerformanceLevel.ECO -> minOf(2, performanceCores)
            PerformanceLevel.BALANCED -> maxOf(2, performanceCores)
            PerformanceLevel.PERFORMANCE -> maxOf(4, performanceCores)
        }
        val calibrated = calibration != null && calibrationValid && !calibration.degraded
        val requested = if (calibrated) calibration!!.bestThreads else heuristic
        val threads = bounded(requested, 1, cap, cores)

        val baseParallelism = when (level) {
            PerformanceLevel.ECO -> 1
            PerformanceLevel.BALANCED -> 2
            PerformanceLevel.PERFORMANCE -> 4
        }
        val parallelism = minOf(baseParallelism, maxOf(1, cores / 2)).coerceAtLeast(1)
        val streamInterval = when (level) {
            PerformanceLevel.ECO -> 120L
            PerformanceLevel.BALANCED -> 60L
            PerformanceLevel.PERFORMANCE -> 33L
        }
        val idleUnload = if (level == PerformanceLevel.ECO) 10 else 0

        val derated = effectiveLevel(mode, capabilities).index < desiredLevel(mode, capabilities).index
        val reason = buildString {
            if (calibrated) {
                append("Calibrated for this model: ${calibration!!.bestThreads} threads at ")
                append(String.format(java.util.Locale.US, "%.1f", calibration.bestTokensPerSecond))
                append(" tokens/s (end-to-end).")
            } else {
                append("Capability heuristic: $cores logical cores, ~$performanceCores performance cores.")
            }
            if (derated) {
                append(" Safety-limited by ")
                append(
                    when {
                        capabilities.thermalStatus >= 3 -> "thermal status ${capabilities.thermalLabel.lowercase()}"
                        capabilities.batteryPercent <= 15 && !capabilities.charging -> "low battery (${capabilities.batteryPercent}%)"
                        else -> "device memory or core count"
                    }
                )
                append(".")
            }
            if (mode == OperatingMode.ADAPTIVE && !derated) append(" Adaptive mode selected this level from observed device state.")
        }
        return RuntimeTuning(level, threads, parallelism, streamInterval, idleUnload, calibrated, reason)
    }

    private fun bounded(value: Int, minimum: Int, maximum: Int, cores: Int): Int {
        val high = maxOf(1, minOf(maximum, cores))
        val low = minOf(maximum, maxOf(1, minimum)).coerceAtMost(high)
        return value.coerceIn(low, high)
    }
}

/**
 * Immediate derating is safe; recovery waits for two consecutive samples so a transient
 * thermal reading cannot flip the runtime back and forth.
 */
class TuningGovernor {
    private var current: Int? = null
    private var pending: Int? = null
    private var pendingCount = 0

    @Synchronized
    fun sample(desired: Int): Int {
        val last = current
        if (last == null) {
            current = desired
            pending = null
            pendingCount = 0
            return desired
        }
        if (desired == last) {
            pending = null
            pendingCount = 0
            return last
        }
        if (desired < last) {
            current = desired
            pending = null
            pendingCount = 0
            return desired
        }
        if (pending == desired) {
            pendingCount++
        } else {
            pending = desired
            pendingCount = 1
        }
        if (pendingCount >= 2) {
            current = desired
            pending = null
            pendingCount = 0
        }
        return current ?: last
    }

    @Synchronized
    fun reset() {
        current = null
        pending = null
        pendingCount = 0
    }
}

object CalibrationPlanner {
    const val MAX_CONFIGS = 3
    const val MAX_OUTPUT_TOKENS = 8
    const val TOTAL_BUDGET_MILLIS = 120_000L

    fun candidateThreads(capabilities: DeviceCapabilities, currentThreads: Int): List<Int> {
        val cores = capabilities.cpu.logicalCores.coerceIn(1, 32)
        val performanceCores = (capabilities.cpu.performanceCores ?: maxOf(1, cores / 2)).coerceIn(1, cores)
        val values = linkedSetOf(
            currentThreads.coerceIn(1, cores),
            performanceCores,
            (performanceCores + 2).coerceAtMost(cores),
        )
        return values.filter { it in 1..cores }.take(MAX_CONFIGS)
    }

    fun gate(
        capabilities: DeviceCapabilities,
        modelLoaded: Boolean,
        objectiveRunning: Boolean,
        activeWork: Boolean = false,
    ): CalibrationGate {
        val reason = when {
            !modelLoaded -> "Load a native GGUF model first; calibration measures real native inference."
            objectiveRunning -> "An objective is running. Calibrate only at an idle boundary."
            activeWork -> "Stop active chat generation, agents, and workflows before calibrating."
            capabilities.thermalStatus >= 3 -> "Device thermal status is ${capabilities.thermalLabel.lowercase()}; wait for it to cool."
            capabilities.batteryPercent <= 20 && !capabilities.charging -> "Battery is ${capabilities.batteryPercent}% and not charging."
            else -> null
        }
        return CalibrationGate(reason == null, reason)
    }

    fun selectBest(samples: List<CalibrationSample>): CalibrationSample? =
        samples.filter { it.generatedTokens > 0 && it.tokensPerSecond.isFinite() && it.tokensPerSecond > 0.0 }
            .maxWithOrNull(compareBy<CalibrationSample> { it.tokensPerSecond }.thenByDescending { it.threads })
}

data class CalibrationGate(val allowed: Boolean, val reason: String?)
