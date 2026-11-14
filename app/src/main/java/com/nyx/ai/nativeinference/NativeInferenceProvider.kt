package com.nyx.ai.nativeinference

import android.content.Context
import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.ChatResult
import com.nyx.ai.network.InferenceOptions
import com.nyx.ai.network.InferenceProvider
import com.nyx.ai.network.LocalModel
import com.nyx.ai.network.ProviderCapabilities
import com.nyx.ai.network.ToolCallingUnsupportedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class NativeInferenceProvider(
    context: Context,
    override val id: String = "native-local",
    override val displayName: String = "On-device GGUF",
    private val generationOptions: NativeGenerationOptions = NativeGenerationOptions(),
) : InferenceProvider {
    private val repository = NativeModelRepository(context)
    init { NativeRuntime.initialize(context) }
    override val kind = "native-gguf"
    override val local = true
    override suspend fun capabilities(): ProviderCapabilities = withContext(Dispatchers.IO) {
        val state = NativeRuntime.status.value
        ProviderCapabilities(toolCalling = false, streaming = true, contextLength = state.contextLength,
            maxConcurrentRequests = 1, local = true,
            healthy = state.phase != NativePhase.ERROR,
            modelCount = repository.models().size,
            detail = state.detail ?: "CPU ARM64; user-imported GGUF; text generation only; model compatibility verified on load")
    }
    override suspend fun listModels(): List<LocalModel> = withContext(Dispatchers.IO) {
        // Model selection contract uses the absolute path as LocalModel.name, not a display title.
        repository.models().map { LocalModel(it.id, it.sizeBytes, it.importedAt.toString()) }
    }
    override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
        if ((tools?.length() ?: 0) > 0 || messages.any { it.role == "tool" || it.toolCalls != null || it.toolCallId != null || it.toolName != null })
            throw ToolCallingUnsupportedException("Native GGUF does not provide native function calls. Use the parent orchestrator's validated text-action protocol.")
        val selected = withContext(Dispatchers.IO) { repository.find(model) }
            ?: throw java.io.IOException("Native model must be an imported private repository file")
        val boundedGeneration = generationOptions.copy(maxOutputTokens = options.nativeOutputTokenLimit(generationOptions.maxOutputTokens))
        // Preserve request context/thread settings and the configured generation seed. No schema grammar API exists here.
        return ChatResult(content = NativeRuntime.generate(selected.path, messages, options, boundedGeneration, onToken), done = true)
    }
    /** Runtime ownership is process-wide. Closing an adapter must not asynchronously unload another owner's session. */
    override fun close() { /* Explicit NativeRuntime.cancel()/unload() are owned by the application. */ }
}
