package com.nyx.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyx.ai.Surface1
import com.nyx.ai.TextMain
import com.nyx.ai.TextMuted
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.autonomy.ExecutionTimeoutProfile

@Composable
internal fun ExecutionTimeoutProfileCard(
    policy: ExecutionTimeoutPolicy,
    onSelect: (ExecutionTimeoutProfile, Int?, Boolean) -> Unit,
) {
    var pending by rememberSaveable { mutableStateOf<ExecutionTimeoutProfile?>(null) }
    var minutes by rememberSaveable { mutableStateOf((policy.overallAllowanceMs / 60_000L).toString()) }
    var approved by rememberSaveable { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("EXECUTION TIMEOUT PROFILE", color = TextMuted, fontSize = 10.sp)
            Text("New objectives snapshot these limits. New agent/workflow requests use them; changing this setting does not extend a running objective or alter its checkpoints. Chat and the 30-second model diagnostic retain their existing limits.", color = TextMuted, fontSize = 11.sp)
            ExecutionTimeoutProfile.entries.forEach { profile ->
                val title = when (profile) {
                    ExecutionTimeoutProfile.QUICK -> "Quick · 5 minutes"
                    ExecutionTimeoutProfile.STANDARD -> "Standard · 30 minutes (mobile default)"
                    ExecutionTimeoutProfile.PATIENT -> "Patient · 60 minutes"
                    ExecutionTimeoutProfile.EXTENDED -> "Extended · 120 minutes · approval required"
                    ExecutionTimeoutProfile.CUSTOM -> "Custom · 1–720 checkpointed minutes · approval required"
                }
                val choose = {
                    if (profile == ExecutionTimeoutProfile.EXTENDED || profile == ExecutionTimeoutProfile.CUSTOM) {
                        approved = false
                        minutes = (policy.overallAllowanceMs / 60_000L).toString()
                        pending = profile
                    } else onSelect(profile, null, false)
                }
                Row(Modifier.fillMaxWidth().clickable(onClick = choose), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = policy.profile == profile, onClick = choose)
                    Text(title, color = TextMain, fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
            }
            Text("Selected: ${policy.profile.name.lowercase()} · overall ${policy.overallAllowanceMs / 60_000L} min · service segment ≤${policy.serviceSegmentMs / 60_000L} min", color = TextMain, fontSize = 12.sp)
            Text("First text ≤${policy.firstOutputMs / 1_000L}s · text inactivity ≤${policy.inactivityMs / 1_000L}s · request ≤${policy.requestMs / 1_000L}s\nBuild ≤${policy.buildMs / 1_000L}s · terminal ≤${policy.terminalMs / 1_000L}s", color = TextMuted, fontSize = 11.sp)
            Text("Ceilings are not speed estimates or permission to run indefinitely. Resource/Endurance guards, user stops and workspace/Android permissions still apply. Native cancellation is cooperative, not guaranteed hard preemption.", color = TextMuted, fontSize = 11.sp)
        }
    }
    pending?.let { profile ->
        val custom = if (profile == ExecutionTimeoutProfile.CUSTOM) minutes.toIntOrNull()?.takeIf { it in 1..720 } else null
        AlertDialog(
            onDismissRequest = { pending = null }, containerColor = Surface1,
            title = { Text("Approve ${profile.name.lowercase()} execution limits", color = TextMain) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (profile == ExecutionTimeoutProfile.CUSTOM) OutlinedTextField(
                        value = minutes, onValueChange = { minutes = it.take(3) },
                        label = { Text("Overall allowance in minutes (1–720)") },
                        singleLine = true, isError = custom == null, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text("An overall allowance above two hours spans checkpointed segments and requires explicit review/resume. It is not a 12-hour continuous foreground-service entitlement. Resource guards may pause earlier.", color = TextMuted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = approved, onCheckedChange = { approved = it })
                        Text("I approve these bounded long-run limits and understand checkpoint/review and resource stops still apply.", color = TextMain, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(
                enabled = approved && (profile != ExecutionTimeoutProfile.CUSTOM || custom != null),
                onClick = { onSelect(profile, custom, true); pending = null },
            ) { Text("Use profile") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}
