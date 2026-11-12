package com.nyx.ai.nativeinference

import java.io.File
import java.util.concurrent.CancellationException

/** Explicit opt-in host executable, NOT a default JUnit pass and NOT Android execution evidence. */
object NativeHostJniSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Pass an external real instruct GGUF absolute path" }
        val file = File(args[0]).canonicalFile
        val metadata = GgufMetadataParser.read(file)
        println("REAL_MODEL name=${metadata.name} architecture=${metadata.architecture} weights=${metadata.tensorBytes}")
        NativeBindings.ensureLibrary()
        NativeBindings.beginOperation()
        val context = NativeBindings.load(file.path.toByteArray(Charsets.UTF_8), 512, 4)
        require(context >= 512)
        try {
            val roles = arrayOf("system", "user").map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()
            val contents = arrayOf("You are a helpful assistant.", "What is the capital of France?").map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()
            val streamed = StringBuilder()
            val sink = NativeTokenSink { streamed.append(it); print(it); System.out.flush() }
            NativeBindings.beginOperation()
            val generated = NativeBindings.generate(roles, contents, 0f, 32, 42, sink)
            val completed = sink.finish()
            require(generated in 1..32 && completed.isNotBlank()) { "No real model token output" }
            require(completed == streamed.toString()) { "Streaming and final output differ" }
            println("\nREAL_JNI_GENERATION_OK tokens=$generated chars=${completed.length}")
            // Abort after first streamed piece, then reuse the SAME loaded native context.
            NativeBindings.beginOperation()
            var cancelled = false
            try {
                NativeBindings.generate(roles, contents, 0f, 512, 42, NativeTokenSink { NativeBindings.cancel() })
            } catch (_: CancellationException) { cancelled = true }
            require(cancelled) { "Atomic abort did not propagate" }
            println("REAL_JNI_CANCELLATION_OK")
            NativeBindings.beginOperation()
            val replay = NativeTokenSink { }
            val replayCount = NativeBindings.generate(roles, contents, 0f, 32, 42, replay)
            require(replayCount == generated && replay.finish() == completed) { "Aborted state polluted reused session" }
            println("REAL_JNI_SESSION_REUSE_OK")
            // A callback exception must cross JNI unchanged and leave the context safe.
            NativeBindings.beginOperation()
            var propagated = false
            try {
                NativeBindings.generate(roles, contents, 0f, 32, 42, NativeTokenSink { throw IllegalStateException("callback-test") })
            } catch (e: IllegalStateException) { propagated = e.message == "callback-test" }
            require(propagated)
            println("REAL_JNI_CALLBACK_EXCEPTION_OK")
        } finally { NativeBindings.unload(); NativeBindings.unload() }
        println("REAL_JNI_SAFE_UNLOAD_OK (host x86_64, not Android)")
    }
}
