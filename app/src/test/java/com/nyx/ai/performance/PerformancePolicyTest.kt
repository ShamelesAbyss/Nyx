package com.nyx.ai.performance

import com.nyx.ai.autonomy.DeviceResources
import com.nyx.ai.autonomy.ResourcePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformancePolicyTest {

    @Test
    fun `SoC classification recognizes Tensor and Snapdragon families without becoming accelerator evidence`() {
        assertEquals(SocFamily.TENSOR_G5, SocClassifier.classify("Google", "Tensor G5", "Google"))
        assertEquals(SocFamily.TENSOR_G4, SocClassifier.classify("Google", "Tensor G4", "Google"))
        assertEquals(SocFamily.SNAPDRAGON_8_ELITE_GEN5, SocClassifier.classify("Qualcomm", "SM8850", "OnePlus"))
        assertEquals(SocFamily.SNAPDRAGON_8_ELITE_GEN6, SocClassifier.classify("Qualcomm", "Snapdragon 8 Elite Gen 6", "Xiaomi"))
        assertEquals(SocFamily.SNAPDRAGON_8_ELITE, SocClassifier.classify("Qualcomm", "SM8750", "Samsung"))
        assertEquals(SocFamily.OTHER_ARM64, SocClassifier.classify("MediaTek", "Dimensity 9300", "vivo"))
        assertEquals(SocFamily.UNKNOWN, SocClassifier.classify("", "", ""))

        val tensor = capabilities(socManufacturer = "Google", socModel = "Tensor G5", nnapi = false)
        val generic = capabilities(socManufacturer = "Example", socModel = "Generic ARM64", nnapi = false)
        assertEquals(
            PerformancePolicy.tuning(tensor, OperatingMode.BALANCED, PerformanceLevel.BALANCED, null, false),
            PerformancePolicy.tuning(generic, OperatingMode.BALANCED, PerformanceLevel.BALANCED, null, false),
        )
    }

    @Test
    fun `fixed modes select bounded thread and concurrency profiles`() {
        val device = capabilities(cores = 8, performanceCores = 4)
        val eco = PerformancePolicy.tuning(device, OperatingMode.ECO, PerformanceLevel.ECO, null, false)
        val balanced = PerformancePolicy.tuning(device, OperatingMode.BALANCED, PerformanceLevel.BALANCED, null, false)
        val performance = PerformancePolicy.tuning(device, OperatingMode.PERFORMANCE, PerformanceLevel.PERFORMANCE, null, false)

        assertEquals(2, eco.nativeThreads)
        assertEquals(4, balanced.nativeThreads)
        assertEquals(4, performance.nativeThreads)
        assertEquals(1, eco.autonomyParallelism)
        assertEquals(2, balanced.autonomyParallelism)
        assertEquals(4, performance.autonomyParallelism)
        assertEquals(120L, eco.streamPublishIntervalMs)
        assertEquals(60L, balanced.streamPublishIntervalMs)
        assertEquals(33L, performance.streamPublishIntervalMs)
        assertEquals(10, eco.idleUnloadMinutes)
        assertEquals(0, balanced.idleUnloadMinutes)
        assertFalse(eco.calibrated)
    }

    @Test
    fun `thermal and battery ceilings override a performance request`() {
        val hot = capabilities(thermal = 4)
        assertEquals(PerformanceLevel.ECO, PerformancePolicy.effectiveLevel(OperatingMode.PERFORMANCE, hot))
        assertTrue(PerformancePolicy.tuning(hot, OperatingMode.PERFORMANCE, PerformanceLevel.ECO, null, false).nativeThreads <= 2)

        val warm = capabilities(thermal = 3)
        assertEquals(PerformanceLevel.BALANCED, PerformancePolicy.effectiveLevel(OperatingMode.PERFORMANCE, warm))

        val empty = capabilities(battery = 5, charging = false)
        assertEquals(PerformanceLevel.ECO, PerformancePolicy.effectiveLevel(OperatingMode.PERFORMANCE, empty))

        val low = capabilities(battery = 10, charging = false)
        assertEquals(PerformanceLevel.BALANCED, PerformancePolicy.effectiveLevel(OperatingMode.PERFORMANCE, low))

        val charging = capabilities(battery = 5, charging = true)
        assertEquals(PerformanceLevel.PERFORMANCE, PerformancePolicy.effectiveLevel(OperatingMode.PERFORMANCE, charging))
    }

    @Test
    fun `adaptive mode uses memory and core capability`() {
        assertEquals(PerformanceLevel.PERFORMANCE, PerformancePolicy.desiredLevel(OperatingMode.ADAPTIVE, capabilities(ram = 8192, cores = 8)))
        assertEquals(PerformanceLevel.BALANCED, PerformancePolicy.desiredLevel(OperatingMode.ADAPTIVE, capabilities(ram = 4096, cores = 8)))
        assertEquals(PerformanceLevel.ECO, PerformancePolicy.desiredLevel(OperatingMode.ADAPTIVE, capabilities(ram = 4096, lowRam = true)))
        assertEquals(PerformanceLevel.ECO, PerformancePolicy.desiredLevel(OperatingMode.ADAPTIVE, capabilities(ram = 2048, cores = 8)))
    }

    @Test
    fun `governor derates immediately and requires two samples to recover`() {
        val governor = TuningGovernor()
        assertEquals(2, governor.sample(2))
        assertEquals(0, governor.sample(0))
        assertEquals(0, governor.sample(2))
        assertEquals(2, governor.sample(2))
        assertEquals(2, governor.sample(2))
        assertEquals(1, governor.sample(1))
        assertEquals(1, governor.sample(2))
        assertEquals(2, governor.sample(2))
        assertEquals(2, governor.sample(2))
    }

    @Test
    fun `calibration validity is tied to model runtime and device fingerprint`() {
        val result = calibration(bestThreads = 4)
        assertTrue(result.isValidFor("/models/a.gguf", 1000, "0.8.4", "device-a"))
        assertFalse(result.isValidFor("/models/b.gguf", 1000, "0.8.4", "device-a"))
        assertFalse(result.isValidFor("/models/a.gguf", 1001, "0.8.4", "device-a"))
        assertFalse(result.isValidFor("/models/a.gguf", 1000, "0.8.5", "device-a"))
        assertFalse(result.isValidFor("/models/a.gguf", 1000, "0.8.4", "device-b"))
        assertFalse(calibration(thermal = 3).degraded.not())
        assertTrue(calibration(thermal = 3).degraded)
        assertTrue(calibration(battery = 10, charging = false).degraded)
        assertFalse(calibration(battery = 10, charging = true).degraded)
    }

    @Test
    fun `valid calibration is used within the mode thread cap`() {
        val device = capabilities(cores = 8, performanceCores = 4)
        val result = calibration(bestThreads = 8)
        val eco = PerformancePolicy.tuning(device, OperatingMode.ECO, PerformanceLevel.ECO, result, true)
        val balanced = PerformancePolicy.tuning(device, OperatingMode.BALANCED, PerformanceLevel.BALANCED, result, true)
        val performance = PerformancePolicy.tuning(device, OperatingMode.PERFORMANCE, PerformanceLevel.PERFORMANCE, result, true)
        assertEquals(2, eco.nativeThreads)
        assertEquals(6, balanced.nativeThreads)
        assertEquals(8, performance.nativeThreads)
        assertTrue(performance.calibrated)

        val degraded = calibration(bestThreads = 8, thermal = 3)
        val ignored = PerformancePolicy.tuning(device, OperatingMode.PERFORMANCE, PerformanceLevel.PERFORMANCE, degraded, false)
        assertFalse(ignored.calibrated)
        assertEquals(4, ignored.nativeThreads)
    }

    @Test
    fun `calibration candidates are bounded deduplicated and core-safe`() {
        val eight = capabilities(cores = 8, performanceCores = 4)
        assertEquals(listOf(4, 6), CalibrationPlanner.candidateThreads(eight, currentThreads = 4))
        assertEquals(listOf(1, 3), CalibrationPlanner.candidateThreads(capabilities(cores = 4, performanceCores = 1), currentThreads = 1))
        assertEquals(listOf(1), CalibrationPlanner.candidateThreads(capabilities(cores = 1, performanceCores = 1), currentThreads = 8))
        assertTrue(CalibrationPlanner.candidateThreads(eight, 32).all { it in 1..8 })
    }

    @Test
    fun `calibration gate blocks unsafe or busy states`() {
        val healthy = capabilities(thermal = 0, battery = 80)
        assertFalse(CalibrationPlanner.gate(healthy, modelLoaded = false, objectiveRunning = false).allowed)
        assertFalse(CalibrationPlanner.gate(healthy, modelLoaded = true, objectiveRunning = true).allowed)
        assertFalse(CalibrationPlanner.gate(healthy, modelLoaded = true, objectiveRunning = false, activeWork = true).allowed)
        assertFalse(CalibrationPlanner.gate(capabilities(thermal = 3), true, false).allowed)
        assertFalse(CalibrationPlanner.gate(capabilities(battery = 15, charging = false), true, false).allowed)
        assertTrue(CalibrationPlanner.gate(capabilities(battery = 15, charging = true), true, false).allowed)
        assertTrue(CalibrationPlanner.gate(healthy, modelLoaded = true, objectiveRunning = false).allowed)
        assertNotNull(CalibrationPlanner.gate(capabilities(thermal = 3), true, false).reason)
    }

    @Test
    fun `best sample ignores unusable results and prefers fewer threads on a tie`() {
        assertNull(CalibrationPlanner.selectBest(emptyList()))
        assertNull(CalibrationPlanner.selectBest(listOf(CalibrationSample(4, 0.0, 10, 0))))
        val best = CalibrationPlanner.selectBest(
            listOf(
                CalibrationSample(4, 8.0, 100, 8),
                CalibrationSample(6, 11.0, 120, 8),
                CalibrationSample(8, 11.0, 130, 8),
            )
        )
        assertEquals(6, best?.threads)
        assertEquals(11.0, best?.tokensPerSecond ?: 0.0, 0.001)
    }

    @Test
    fun `resource policy honors the performance concurrency cap`() {
        val resources = DeviceResources(
            totalMemMb = 8192,
            availMemMb = 4096,
            lowMemory = false,
            thermalStatus = 0,
            batteryPercent = 80,
            charging = false,
            availableStorageMb = 4096,
        )
        assertEquals(4, ResourcePolicy.profile(resources).policy.maxTotal)
        assertEquals(1, ResourcePolicy.profile(resources, parallelismCap = 1).policy.maxTotal)
        assertEquals(2, ResourcePolicy.profile(resources, requestedParallelism = 3, parallelismCap = 2).policy.maxTotal)
    }

    private fun capabilities(
        socManufacturer: String = "Google",
        socModel: String = "Tensor G5",
        manufacturer: String = "Google",
        cores: Int = 8,
        performanceCores: Int = 4,
        ram: Long = 8192,
        lowRam: Boolean = false,
        thermal: Int = 0,
        battery: Int = 80,
        charging: Boolean = false,
        nnapi: Boolean = false,
    ) = DeviceCapabilities(
        manufacturer = manufacturer,
        model = "Test device",
        socManufacturer = socManufacturer,
        socModel = socModel,
        hardware = "test",
        androidRelease = "17",
        sdkInt = 37,
        supportedAbis = listOf("arm64-v8a"),
        cpu = CpuTopology(
            logicalCores = cores,
            performanceCores = performanceCores,
            efficiencyCores = (cores - performanceCores).takeIf { it > 0 },
            maxFrequencyKhz = 2_900_000,
            features = setOf("asimd", "dotprod", "i8mm"),
        ),
        totalRamMb = ram,
        availableRamMb = ram / 2,
        memoryClassMb = 512,
        lowRamDevice = lowRam,
        thermalStatus = thermal,
        batteryPercent = battery,
        charging = charging,
        displayWidthPx = 1080,
        displayHeightPx = 2400,
        densityDpi = 420,
        smallestWidthDp = 411,
        hingeSensor = false,
        mediaPerformanceClass = 35,
        nnapiFeature = nnapi,
        vulkanFeatureVersion = 420,
    )

    private fun calibration(
        bestThreads: Int = 4,
        thermal: Int = 0,
        battery: Int = 80,
        charging: Boolean = false,
    ) = CalibrationResult(
        modelPath = "/models/a.gguf",
        modelSizeBytes = 1000,
        runtimeVersion = "0.8.4",
        deviceFingerprint = "device-a",
        measuredAtEpochMs = 1_700_000_000_000,
        bestThreads = bestThreads,
        bestTokensPerSecond = 9.5,
        samples = listOf(CalibrationSample(bestThreads, 9.5, 250, 8)),
        thermalStatus = thermal,
        batteryPercent = battery,
        charging = charging,
    )
}
