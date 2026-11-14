package com.nyx.ai.autonomy

import android.content.Context
import org.json.JSONObject

/** Selection is separate from credentials/models; objective execution uses its immutable event snapshot. */
class ExecutionTimeoutPolicyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("nyx_execution_timeouts", Context.MODE_PRIVATE)

    fun selected(): ExecutionTimeoutPolicy = preferences.getString(KEY_SELECTION, null)?.let {
        decode(JSONObject(it))
    } ?: ExecutionTimeoutPolicy.forProfile()

    fun select(
        profile: ExecutionTimeoutProfile,
        customMinutes: Int? = null,
        explicitLongRunApproval: Boolean = false,
    ): ExecutionTimeoutPolicy {
        val policy = ExecutionTimeoutPolicy.forProfile(profile, customMinutes, explicitLongRunApproval)
        check(preferences.edit().putString(KEY_SELECTION, snapshot(policy).toString()).commit()) { "Could not persist timeout selection." }
        return policy
    }

    fun snapshot(policy: ExecutionTimeoutPolicy = selected()): JSONObject = encode(policy)

    companion object {
        private const val KEY_SELECTION = "selected_profile"
        const val SNAPSHOT_KEY = "timeoutPolicy"

        fun fromSnapshot(config: JSONObject): ExecutionTimeoutPolicy {
            if (!config.has(SNAPSHOT_KEY)) return ExecutionTimeoutPolicy.forProfile()
            return decode(config.getJSONObject(SNAPSHOT_KEY))
        }

        fun encode(policy: ExecutionTimeoutPolicy): JSONObject = JSONObject()
            .put("profile", policy.profile.name)
            .put("explicitLongRunApproval", policy.explicitLongRunApproval)
            .apply { if (policy.profile == ExecutionTimeoutProfile.CUSTOM) put("customMinutes", policy.overallAllowanceMs / 60_000L) }

        private fun decode(snapshot: JSONObject): ExecutionTimeoutPolicy {
            val profile = ExecutionTimeoutProfile.valueOf(snapshot.getString("profile"))
            // Read a Long before narrowing, so malicious/invalid numeric overflow cannot become a valid duration.
            val custom = if (profile == ExecutionTimeoutProfile.CUSTOM) snapshot.get("customMinutes").let { value ->
                require(value is Int || value is Long) { "Custom minutes must be an integer." }
                (value as Number).toLong()
            }.also {
                require(it in 1..720) { "Custom must be between 1 and 720 minutes." }
            }.toInt() else null
            return ExecutionTimeoutPolicy.forProfile(profile, custom, snapshot.optBoolean("explicitLongRunApproval", false))
        }
    }
}
