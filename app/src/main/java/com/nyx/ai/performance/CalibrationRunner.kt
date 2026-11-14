package com.nyx.ai.performance

import android.content.Context
import com.nyx.ai.nativeinference.NativeGenerationOptions
import com.nyx.ai.nativeinference.NativePhase
import com.nyx.ai.nativeinference.NativeRuntime
import com.nyx.ai.network.ApiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opt-in, bounded calibration against the currently loaded native model. It measures actual
 * wall-clock load and generation time for a few candidate thread counts. It never runs at
 * startup, never runs during an objective, and restores the original thread configuration.
 *
 * "Tokens/s" here is end-to-end generation throughput for a fixed short prompt. It includes
 * prompt processing and is not presented as a synthetic decode-only benchmark.
 */
class CalibrationRunner(private val context: Context) {
    suspend fun run(onProgress: (String) -> Unit = {}): CalibrationResult = withContext(Dispatchers.IO) {
        val status = NativeRuntime.status.value
        val path = status.modelPath
        require(status.loaded && path != null) { "Load a native GGUF model before calibrating." }
        require(status.phase == NativePhase.READY) { "Wait for the active native generation to finish before calibrating." }
        val original = NativeRuntime.currentOptions ?: error("The native session options are unavailable.")
        val capabilities = DeviceIntelligence.read(context)
        val gate = CalibrationPlanner.gate(capabilities, modelLoaded = true, objectiveRunning = false)
        require(gate.allowed) { gate.reason ?: "Calibration is not allowed in the current device state." }

        val candidates = CalibrationPlanner.candidateThreads(capabilities, original.cpuThreads)
        val samples = mutableListOf<CalibrationSample>()
        val startedAt = System.currentTimeMillis()
        try {
            for (threads in candidates) {
                if (System.currentTimeMillis() - startedAt >= CalibrationPlanner.TOTAL_BUDGET_MILLIS) break
                val observed = DeviceIntelligence.read(context)
                if (observed.thermalStatus >= 3) break
                onProgress("Measuring $threads native threads…")
                val measured = runCatching {
                    val loadStarted = System.nanoTime()
                    NativeRuntime.load(path, original.copy(cpuThreads = threads))
                    val loadMillis = (System.nanoTime() - loadStarted) / 1_000_000L
                    val generateStarted = System.nanoTime()
                    NativeRuntime.generate(
                        modelPath = path,
                        messages = listOf(ApiMessage("user", "Reply with the single word READY.")),
                        options = original.copy(cpuThreads = threads),
                        generation = NativeGenerationOptions(maxOutputTokens = CalibrationPlanner.MAX_OUTPUT_TOKENS, seed = 42),
                    ) { }
                    val elapsedMillis = (System.nanoTime() - generateStarted) / 1_000_000L
                    val tokens = NativeRuntime.status.value.generatedTokens ?: 0
                    if (tokens <= 0 || elapsedMillis <= 0) {
                        null
                    } else {
                        CalibrationSample(
                            threads = threads,
                            tokensPerSecond = tokens * 1000.0 / elapsedMillis,
                            loadMillis = loadMillis,
                            generatedTokens = tokens,
                        )
                    }
                }.getOrNull()
                if (measured != null) samples += measured
            }
        } finally {
            // Restoration must survive ViewModel/app cancellation; otherwise the last measured
            // thread count could remain resident after the screen goes away.
            withContext(NonCancellable) {
                runCatching { NativeRuntime.load(path, original) }
            }
        }

        val best = CalibrationPlanner.selectBest(samples)
            ?: error("Calibration produced no usable native samples; the model and runtime were left unchanged.")
        val finalState = DeviceIntelligence.read(context)
        CalibrationResult(
            modelPath = path,
            modelSizeBytes = File(path).length(),
            runtimeVersion = PerformanceController.runtimeVersion(),
            deviceFingerprint = capabilities.fingerprint,
            measuredAtEpochMs = System.currentTimeMillis(),
            bestThreads = best.threads,
            bestTokensPerSecond = best.tokensPerSecond,
            samples = samples,
            thermalStatus = finalState.thermalStatus,
            batteryPercent = finalState.batteryPercent,
            charging = finalState.charging,
        )
    }
}
