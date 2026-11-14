package com.nyx.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyx.ai.Amber
import com.nyx.ai.Green
import com.nyx.ai.NyxState
import com.nyx.ai.NyxViewModel
import com.nyx.ai.Obsidian
import com.nyx.ai.Surface1
import com.nyx.ai.TextMain
import com.nyx.ai.TextMuted
import com.nyx.ai.Violet
import com.nyx.ai.VioletSoft
import com.nyx.ai.performance.CalibrationPlanner
import com.nyx.ai.performance.CalibrationResult
import com.nyx.ai.performance.DeviceCapabilities
import com.nyx.ai.performance.OperatingMode
import com.nyx.ai.performance.PerformanceSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PerformanceScreen(state: NyxState, vm: NyxViewModel) {
    val performance = state.performance
    val capabilities = performance.capabilities
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("DEVICE OPTIMIZATION", color = VioletSoft, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
            Text("Hardware-aware runtime", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Observed capabilities, operating mode, resource state, and opt-in native calibration. No GPU/NPU acceleration is claimed unless a verified backend exists.",
                color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }

        if (capabilities == null) {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
                Text("Reading device capabilities…", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            }
        } else {
            ModeCard(performance, vm)
            CapabilityCard(capabilities)
            LiveStateCard(capabilities)
            TuningCard(state, vm)
            CalibrationCard(state, vm, capabilities)
            AcceleratorCard(capabilities)
        }

        OutlinedButton(
            onClick = vm::refreshPerformance,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Refresh device state") }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ModeCard(performance: PerformanceSnapshot, vm: NyxViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SectionTitle("OPERATING MODE", "Changes are applied at safe execution boundaries")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OperatingMode.entries.take(2).forEach { mode ->
                    ModeButton(mode, performance.mode, vm, Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OperatingMode.entries.drop(2).forEach { mode ->
                    ModeButton(mode, performance.mode, vm, Modifier.weight(1f))
                }
            }
            Text(performance.mode.description, color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
            Text(
                "Effective level: ${performance.level.label}. ${performance.tuning.reason}",
                color = VioletSoft, fontSize = 11.sp, lineHeight = 16.sp,
            )
            Text(
                "Thermal and low-battery safety ceilings apply to every mode. Changes never interrupt a running generation or objective.",
                color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun ModeButton(mode: OperatingMode, selected: OperatingMode, vm: NyxViewModel, modifier: Modifier) {
    if (mode == selected) {
        Button(
            onClick = { vm.setOperatingMode(mode) },
            modifier = modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Obsidian),
            shape = RoundedCornerShape(11.dp),
        ) { Text(mode.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    } else {
        OutlinedButton(
            onClick = { vm.setOperatingMode(mode) },
            modifier = modifier.heightIn(min = 48.dp),
            shape = RoundedCornerShape(11.dp),
        ) { Text(mode.label, fontSize = 12.sp) }
    }
}

@Composable
private fun CapabilityCard(capabilities: DeviceCapabilities) {
    val cpu = capabilities.cpu
    val featureFlags = buildList {
        if (cpu.hasDotProduct) add("dotprod")
        if (cpu.hasI8mm) add("i8mm")
        if (cpu.hasBf16) add("bf16")
        if (cpu.hasSve) add("SVE")
    }.ifEmpty { listOf("no optional flags reported") }.joinToString(" · ")

    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SectionTitle("DEVICE CAPABILITIES", "Read from supported Android and Linux interfaces")
            InfoRow("Device", "${capabilities.manufacturer} ${capabilities.model}")
            InfoRow("SoC", listOf(capabilities.socManufacturer, capabilities.socModel).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Not reported" })
            InfoRow("Family hint", capabilities.socFamily.label)
            InfoRow("CPU", cpu.summary)
            InfoRow("CPU flags", featureFlags)
            InfoRow("Memory", "${capabilities.totalRamMb} MB total · ${capabilities.availableRamMb} MB available · ${capabilities.memoryClassMb} MB heap class")
            InfoRow("Android", "${capabilities.androidRelease} (API ${capabilities.sdkInt}) · ${capabilities.supportedAbis.joinToString()}")
            InfoRow("Display", capabilities.displaySummary)
            InfoRow("Form factor", if (capabilities.foldableHint) "Foldable/large-screen hint present (no hinge-aware layout)" else "Standard app window")
            InfoRow("Performance class", if (capabilities.mediaPerformanceClass > 0) capabilities.mediaPerformanceClass.toString() else "Not reported")
            Text(
                "SoC family is a display and thread heuristic only. It is not proof that a Tensor TPU, Hexagon NPU, or vendor runtime can accelerate GGUF inference.",
                color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun LiveStateCard(capabilities: DeviceCapabilities) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SectionTitle("OBSERVED RESOURCE STATE", "Snapshot from the most recent refresh")
            InfoRow("Battery", "${capabilities.batteryPercent}% · ${if (capabilities.charging) "charging" else "not charging"}")
            InfoRow("Thermal", capabilities.thermalLabel)
            InfoRow("Memory", "${capabilities.availableRamMb} MB available of ${capabilities.totalRamMb} MB")
            InfoRow("Low-RAM flag", if (capabilities.lowRamDevice) "Android reports a low-RAM device" else "Not set")
            InfoRow("Native session", if (capabilities.isArm64) "ARM64 supported" else "ARM64 not reported")
            Text(
                "Thermal status uses PowerManager on Android 10+. Calibration and sustained-workload decisions use these observed values; they are not a battery or temperature history.",
                color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun TuningCard(state: NyxState, vm: NyxViewModel) {
    val performance = state.performance
    val tuning = performance.tuning
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("ADAPTIVE RUNTIME", "Effective values for this device and mode")
            InfoRow("Native threads", "${tuning.nativeThreads}${if (tuning.calibrated) " · calibrated" else " · heuristic"}")
            InfoRow("Autonomy concurrency", "${tuning.autonomyParallelism} worker${if (tuning.autonomyParallelism == 1) "" else "s"} maximum")
            InfoRow("Stream cadence", "${tuning.streamPublishIntervalMs} ms UI coalescing")
            InfoRow("Idle release", if (tuning.idleUnloadMinutes > 0) "Native model after ${tuning.idleUnloadMinutes} min idle" else "Model stays resident")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Adaptive native threading", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Use the mode/calibration thread count before the next model load.", color = TextMuted, fontSize = 10.sp)
                }
                Switch(checked = performance.adaptiveThreads, onCheckedChange = vm::setAdaptiveThreads)
            }
            if (performance.adaptiveThreads) {
                Text(
                    "A resident session keeps its existing thread count to avoid a hidden reload. Apply the new configuration only when idle.",
                    color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp,
                )
            }
            OutlinedButton(
                onClick = vm::applyPerformanceToNative,
                enabled = state.nativeStatus.loaded && !state.isGenerating && !state.agentRunning &&
                    !state.workflowRunning && !state.objectiveRunning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(11.dp),
            ) { Text(if (state.nativeStatus.loaded) "Apply to native session" else "Load a native model to apply") }
            if (performance.message.isNotBlank()) {
                Text(performance.message, color = VioletSoft, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun CalibrationCard(state: NyxState, vm: NyxViewModel, capabilities: DeviceCapabilities) {
    val performance = state.performance
    val calibration = performance.calibration
    var confirm by rememberSaveable { mutableStateOf(false) }
    val busy = state.isGenerating || state.agentRunning || state.workflowRunning
    val gate = CalibrationPlanner.gate(
        capabilities = capabilities,
        modelLoaded = state.nativeStatus.loaded,
        objectiveRunning = state.objectiveRunning,
        activeWork = busy,
    )
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("HARDWARE CALIBRATION", "Opt-in, bounded, and stored locally")
            if (calibration == null) {
                Text("No calibration has been recorded. NYX is using the capability heuristic above.", color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
            } else {
                InfoRow("Model", calibration.modelPath.substringAfterLast('/'))
                InfoRow("Best result", "${calibration.bestThreads} threads · ${formatRate(calibration.bestTokensPerSecond)} tokens/s")
                InfoRow("Measured", formatTime(calibration.measuredAtEpochMs))
                InfoRow(
                    "Validity",
                    if (performance.calibrationValid) "Valid for the resident model and runtime" else "Not used: model, runtime, device, or measurement conditions changed",
                )
                if (calibration.degraded) {
                    Text("This measurement was taken under thermal or battery pressure and is retained for transparency only.", color = Amber, fontSize = 10.sp, lineHeight = 14.sp)
                }
                calibration.samples.sortedByDescending { it.tokensPerSecond }.forEach { sample ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${sample.threads} threads", color = TextMain, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("${formatRate(sample.tokensPerSecond)} tok/s", color = VioletSoft, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(8.dp))
                        Text("load ${sample.loadMillis} ms", color = TextMuted, fontSize = 10.sp)
                    }
                }
                HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))
                Text(
                    "End-to-end throughput for a fixed short prompt; it includes prompt processing and is not a decode-only or synthetic score.",
                    color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp,
                )
            }
            if (performance.calibrationRunning) {
                Text(performance.message.ifBlank { "Calibrating…" }, color = VioletSoft, fontSize = 11.sp)
            } else if (!gate.allowed && gate.reason != null) {
                Text(gate.reason, color = Amber, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { confirm = true },
                    enabled = gate.allowed && !busy && !performance.calibrationRunning,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Obsidian),
                    shape = RoundedCornerShape(11.dp),
                ) { Text(if (calibration == null) "Run calibration" else "Recalibrate", fontSize = 12.sp) }
                if (calibration != null) {
                    OutlinedButton(
                        onClick = vm::refreshPerformance,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(11.dp),
                    ) { Text("Recheck", fontSize = 12.sp) }
                }
            }
            Text(
                "Calibration reloads the model for up to ${CalibrationPlanner.MAX_CONFIGS} thread configurations, generates at most ${CalibrationPlanner.MAX_OUTPUT_TOKENS} tokens each, and stops within ${CalibrationPlanner.TOTAL_BUDGET_MILLIS / 1000} seconds. It never starts automatically.",
                color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp,
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Surface1,
            title = { Text("Run bounded native calibration?") },
            text = {
                Text(
                    "NYX will reload the resident model for a few thread counts and generate a very short fixed response each time. " +
                        "This can take up to two minutes, may warm the device, and temporarily interrupts chat until it finishes. " +
                        "No objective may be running. Results are stored only on this device."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.runCalibration() }) { Text("Start calibration") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AcceleratorCard(capabilities: DeviceCapabilities) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SectionTitle("ACCELERATION STATUS", "Capability evidence, not a compatibility promise")
            InfoRow("NNAPI feature", if (capabilities.nnapiFeature) "Present in system features" else "Not reported")
            InfoRow("Vulkan feature", capabilities.vulkanFeatureVersion?.let { "Reported version $it" } ?: "Not reported")
            InfoRow("GGUF backend", "CPU (llama.cpp) · active")
            Text(
                "No GPU or NPU GGUF backend is bundled or verified. NNAPI/Vulkan feature presence does not mean arbitrary GGUF models are accelerated. " +
                    "NYX keeps the ARM64 CPU path as the reliable fallback and does not require root, Play services, or vendor runtimes.",
                color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = VioletSoft, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = TextMuted, fontSize = 10.sp, lineHeight = 13.sp)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(118.dp))
        Text(value, color = TextMain, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
    }
}

private fun formatRate(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))
