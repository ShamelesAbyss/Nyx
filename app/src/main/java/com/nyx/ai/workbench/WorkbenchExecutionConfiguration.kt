package com.nyx.ai.workbench

import com.nyx.ai.terminal.TerminalEnvironment
import org.json.JSONObject

/** Configuration construction is local authority, not planner-controlled data. */
object WorkbenchExecutionConfiguration {
    fun scope(snapshot: JSONObject, approval: WorkbenchAuthorization, objectiveId: String, terminalEnabled: Boolean,
              environment: TerminalEnvironment, guestAlias: String, workspace: String, previous: JSONObject? = null): JSONObject {
        approval.validate()
        val combined = approval.combinedDevelopment
        when {
            combined != null -> {
                combined.terminalApproval(objectiveId)
                require(terminalEnabled) { "Combined development is revoked while global terminal approval is off. Re-enable it to resume the original project; current UI selection cannot redirect it." }
                snapshot.put("terminalEnabled", true).put("workspace", combined.workspaceRoot)
                    .put("environment", combined.environment.name).put("guestAlias", combined.guestAlias)
            }
            approval.enabled -> snapshot.put("terminalEnabled", false).put("workspace", "")
                .put("environment", environment.name).put("guestAlias", guestAlias)
            previous != null -> snapshot.put("terminalEnabled", terminalEnabled && previous.optBoolean("terminalEnabled"))
                .put("workspace", previous.getString("workspace")).put("environment", previous.getString("environment"))
                .put("guestAlias", previous.getString("guestAlias"))
            else -> snapshot.put("terminalEnabled", terminalEnabled).put("workspace", workspace)
                .put("environment", environment.name).put("guestAlias", guestAlias)
        }
        val sequence = previous?.optLong("configurationSequence", 0) ?: 0
        require(sequence in 0 until Long.MAX_VALUE) { "Invalid configuration sequence." }
        return snapshot.put("configurationSequence", sequence + 1).put(WorkbenchAuthorization.SNAPSHOT_KEY, approval.toJson())
    }
    fun latest(snapshots: List<JSONObject>): JSONObject? = snapshots.withIndex().maxWithOrNull(
        compareBy<IndexedValue<JSONObject>> { it.value.optLong("configurationSequence", 0) }.thenBy { it.index })?.value
}
