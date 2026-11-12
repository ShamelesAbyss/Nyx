package com.nyx.ai.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives only the explicit mutable PendingIntent sent by Termux for an in-flight request. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_RESULT) return
        val requestId = intent.data?.lastPathSegment
        val resultBundle = intent.getBundleExtra(TermuxProtocol.RESULT_BUNDLE)
        TermuxIpcAdapter.receive(requestId, resultBundle)
    }

    companion object {
        const val ACTION_RESULT = "com.nyx.ai.terminal.TERMUX_RESULT"
    }
}
