package com.nyx.ai.nativeinference

import java.io.File

/** Opt-in real GGUF JNI check; Linux host only, never a default mocked/model compatibility pass. */
object NativeBedrockHostJniSmoke {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Pass an authorized external instruct GGUF" }
        val file = File(args[0]).canonicalFile
        val metadata = GgufMetadataParser.read(file)
        require(metadata.trainingContext >= 513)
        println("REAL_BEDROCK_MODEL architecture=${metadata.architecture} name=${metadata.name}")
        NativeBindings.ensureLibrary()
        fun load() {
            NativeBindings.beginOperation()
            require(NativeBindings.load(file.path.toByteArray(), 513, 4) == 768) { "513-position request must allocate 768 positions" }
        }
        fun generate(user: String): Pair<Int, String> {
            NativeBindings.beginOperation()
            val sink = NativeTokenSink { }
            val count = NativeBindings.generate(arrayOf("user".toByteArray()), arrayOf(user.toByteArray()), 0f, 32, 42, sink)
            return count to sink.finish()
        }
        try {
            load()
            println("REAL_BEDROCK_JNI_ALLOCATION_OK requested=513 allocated=768")
            val prompt = "What is the capital of France?"
            val first = generate(prompt)
            require(first.first in 1..32 && first.second.isNotBlank())
            require(generate(prompt) == first) { "Exact token prefix replay changed greedy result" }
            println("REAL_BEDROCK_JNI_EXACT_PREFIX_OK tokens=${first.first}")
            val changed = "What is the capital of Germany?"
            val reused = generate(changed)
            NativeBindings.unload()
            load()
            require(generate(changed) == reused) { "Changed prefix reuse differs from a clean session" }
            println("REAL_BEDROCK_JNI_CHANGED_PREFIX_OK")
        } finally { NativeBindings.unload(); NativeBindings.unload() }
        println("REAL_BEDROCK_JNI_SAFE_UNLOAD_OK (Linux host, not Android)")
    }
}
