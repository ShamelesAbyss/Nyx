package com.nyx.ai

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyx.ai.agent.AgentEvent
import com.nyx.ai.agent.AgentRunner
import com.nyx.ai.agent.AgentSpec
import com.nyx.ai.agent.AgentTools
import com.nyx.ai.autonomy.ExecutionMonitor
import com.nyx.ai.autonomy.ExecutionRecovery
import com.nyx.ai.service.ObjectiveExecutionService
import com.nyx.ai.terminal.CommandPolicy
import com.nyx.ai.terminal.EnvironmentProfile
import com.nyx.ai.terminal.TerminalCommandResult
import com.nyx.ai.terminal.TerminalEnvironment
import com.nyx.ai.terminal.TerminalRepository
import com.nyx.ai.terminal.TerminalResultKind
import com.nyx.ai.data.TerminalCommandEntity
import com.nyx.ai.data.AgentEntity
import com.nyx.ai.data.ConversationEntity
import com.nyx.ai.data.ExecutionLogEntity
import com.nyx.ai.data.MessageEntity
import com.nyx.ai.data.ModelEndpointEntity
import com.nyx.ai.data.NyxDatabase
import com.nyx.ai.data.ObjectiveEntity
import com.nyx.ai.data.ObjectiveEventEntity
import com.nyx.ai.data.ObjectiveTaskEntity
import com.nyx.ai.data.WorkflowDefinitionEntity
import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.InferenceOptions
import com.nyx.ai.network.LocalModel
import com.nyx.ai.network.OllamaClient
import com.nyx.ai.settings.CredentialTransportPolicy
import com.nyx.ai.settings.CredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.ByteArrayOutputStream

private const val DEFAULT_ENDPOINT = "http://127.0.0.1:11434"

data class NyxState(
    val endpoint: String = DEFAULT_ENDPOINT,
    val externalConsent: Boolean = false,
    val credentialConfigured: Boolean = false,
    val connected: Boolean = false,
    val models: List<LocalModel> = emptyList(),
    val selectedModel: String = "",
    val conversations: List<ConversationEntity> = emptyList(),
    val activeConversationId: String? = null,
    val messages: List<MessageEntity> = emptyList(),
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val agents: List<AgentEntity> = emptyList(),
    val workflows: List<WorkflowDefinitionEntity> = emptyList(),
    val selectedAgentId: String = "",
    val agentTask: String = "",
    val importedDocumentName: String = "",
    val agentOutput: String = "",
    val agentEvents: List<AgentEvent> = emptyList(),
    val agentRunning: Boolean = false,
    val workflowAgentIds: Set<String> = emptySet(),
    val workflowTask: String = "",
    val workflowOutput: String = "",
    val workflowRunning: Boolean = false,
    val registeredGguf: List<ModelEndpointEntity> = emptyList(),
    val objectives: List<ObjectiveEntity> = emptyList(),
    val selectedObjectiveId: String? = null,
    val objectiveTasks: List<ObjectiveTaskEntity> = emptyList(),
    val objectiveEvents: List<ObjectiveEventEntity> = emptyList(),
    val objectiveArtifacts: List<com.nyx.ai.data.ObjectiveArtifactEntity> = emptyList(),
    val objectiveRunning: Boolean = false,
    val objectiveStreaming: String = "",
    val objectivePhase: String = "idle",
    val terminalHistory: List<TerminalCommandEntity> = emptyList(),
    val terminalProfile: EnvironmentProfile? = null,
    val terminalDiscovering: Boolean = false,
    val terminalRunning: Boolean = false,
    val terminalOutput: String = "",
    val terminalMessage: String = "",
    val terminalPendingCommand: String? = null,
    val terminalEnvironment: TerminalEnvironment = TerminalEnvironment.TERMUX_HOST,
    val terminalToolsEnabled: Boolean = false,
    val terminalWorkspace: String = "",
    val temperature: Float = 0.7f,
    val contextLength: Int = 4096,
    val cpuThreads: Int = 4,
    val error: String = ""
)

class NyxViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val db = NyxDatabase.getInstance(application)
    private val credentialVault = CredentialVault(application)
    private val prefs = application.getSharedPreferences("nyx_settings", 0)
    private val _state = MutableStateFlow(
        NyxState(
            endpoint = prefs.getString("endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT,
            externalConsent = prefs.getBoolean("external_consent", false),
            selectedModel = prefs.getString("selected_model", "") ?: "",
            temperature = prefs.getFloat("temperature", 0.7f),
            contextLength = prefs.getInt("context_length", 4096),
            cpuThreads = prefs.getInt("cpu_threads", 4),
            terminalToolsEnabled = prefs.getBoolean(ObjectiveExecutionService.KEY_TERMINAL_ENABLED, false),
            terminalWorkspace = prefs.getString(ObjectiveExecutionService.KEY_TERMINAL_WORKSPACE, "") ?: "",
        )
    )
    val state: StateFlow<NyxState> = _state.asStateFlow()
    private val terminalRepository = TerminalRepository(application, db)
    private var generationJob: Job? = null
    private var agentJob: Job? = null
    private var workflowJob: Job? = null
    private var objectiveJob: Job? = null
    private var objectiveObservation: Job? = null
    private var messageObservation: Job? = null
    private val inferenceReservationLock = Any()
    private var inferenceReserved = false

    private fun reserveInference(): Boolean = synchronized(inferenceReservationLock) {
        if (inferenceReserved) false else { inferenceReserved = true; true }
    }

    private fun releaseInference() = synchronized(inferenceReservationLock) { inferenceReserved = false }

    init {
        viewModelScope.launch {
            ensureStarterRecords()
            db.conversationDao().observeConversations().collect { items -> _state.update { it.copy(conversations = items) } }
        }
        viewModelScope.launch { db.agentDao().observeAll().collect { rows ->
            _state.update { old ->
                val selected = old.selectedAgentId.takeIf { id -> rows.any { it.id == id } } ?: rows.firstOrNull()?.id.orEmpty()
                old.copy(agents = rows, selectedAgentId = selected)
            }
        } }
        viewModelScope.launch { db.workflowDao().observeAll().collect { rows -> _state.update { it.copy(workflows = rows) } } }
        viewModelScope.launch {
            db.objectiveDao().observeAll().collect { rows ->
                val previous = _state.value.selectedObjectiveId
                val selected = previous?.takeIf { id -> rows.any { it.id == id } } ?: rows.firstOrNull()?.id
                _state.update { it.copy(objectives = rows, selectedObjectiveId = selected) }
                if (selected != null && selected != previous) selectObjective(selected)
            }
        }
        viewModelScope.launch { recoverInterruptedObjectives() }
        viewModelScope.launch { db.modelEndpointDao().observeAll().collect { rows -> _state.update { it.copy(registeredGguf = rows.filter { it.provider == "GGUF" }) } } }
        refreshModels()
    }

    private suspend fun ensureStarterRecords() {
        val dao = db.agentDao()
        val existing = dao.observeAll().first()
        if (existing.isEmpty()) {
            val templates = listOf(
                AgentEntity("template-general", "General Assistant", "A practical general-purpose helper.", "You are a thoughtful general assistant. Break complex tasks into clear steps; use tools when they genuinely help.", configurationJson = agentConfig(6, AgentTools.DEFAULT_TOOLS)),
                AgentEntity("template-coding", "Coding Agent", "Helps reason about code and implementation plans.", "You are a careful coding agent. Explain assumptions, propose maintainable solutions, and use the calculator or workspace tools when appropriate.", configurationJson = agentConfig(8, AgentTools.DEFAULT_TOOLS)),
                AgentEntity("template-research", "Research Agent", "Analyzes user-provided context and identifies evidence gaps.", "You are a research agent. Distinguish facts from assumptions. You do not have web access; work only from supplied context and local tools.", configurationJson = agentConfig(6, AgentTools.DEFAULT_TOOLS)),
                AgentEntity("template-planning", "Planning Agent", "Turns goals into ordered, actionable steps.", "You are a planning agent. Create realistic, ordered steps and record structured plans with the plan_task tool.", configurationJson = agentConfig(6, AgentTools.DEFAULT_TOOLS)),
                AgentEntity("template-files", "File Analysis Agent", "Analyzes text explicitly selected or copied into a task.", "You are a file analysis agent. Analyze only content explicitly supplied by the user or present in your sandbox workspace. Never claim access to arbitrary device files.", configurationJson = agentConfig(6, AgentTools.DEFAULT_TOOLS)),
                AgentEntity("template-custom", "Custom Agent", "A starting point for a customized role.", "You are a user-configured local agent. Follow your instructions and use only enabled tools.", configurationJson = agentConfig(6, AgentTools.DEFAULT_TOOLS))
            )
            dao.upsertAll(templates)
        }
        val workflows = db.workflowDao().observeAll().first()
        if (workflows.isEmpty()) {
            db.workflowDao().upsert(WorkflowDefinitionEntity(
                id = "workflow-sequential",
                name = "Sequential agent pipeline",
                description = "Run chosen local agents in order and pass each result forward.",
                definitionJson = "{\"execution\":\"sequential\",\"sharedContext\":true}"
            ))
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            val endpoint = _state.value.endpoint
            val hasCredential = db.modelEndpointDao().getById(endpointRecordId(endpoint))?.credentialReference != null
            _state.update { it.copy(credentialConfigured = hasCredential) }
            if (!endpointAllowed(endpoint)) {
                _state.update { it.copy(connected = false, models = emptyList(), error = "External endpoints require explicit consent in Settings.") }
                return@launch
            }
            _state.update { it.copy(error = "") }
            try {
                val models = inferenceClient(endpoint).listModels()
                _state.update { old ->
                    val selected = old.selectedModel.takeIf { name -> models.any { it.name == name } } ?: models.firstOrNull()?.name.orEmpty()
                    prefs.edit().putString("selected_model", selected).apply()
                    old.copy(connected = true, models = models, selectedModel = selected, credentialConfigured = hasCredential, error = if (models.isEmpty()) "Inference server is reachable, but no models are installed." else "")
                }
                persistOllamaEndpoint()
            } catch (e: Exception) {
                _state.update { it.copy(connected = false, models = emptyList(), error = e.message ?: "Could not connect to the local inference server.") }
            }
        }
    }

    fun clearError() { _state.update { it.copy(error = "") } }

    fun saveEndpoint(endpoint: String, consentExternal: Boolean) {
        val normalized = endpoint.trim().trimEnd('/')
        val valid = runCatching {
            val uri = Uri.parse(normalized)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
        }.getOrDefault(false)
        if (!valid) { _state.update { it.copy(error = "Enter a valid http:// or https:// endpoint URL.") }; return }
        prefs.edit().putString("endpoint", normalized).putBoolean("external_consent", consentExternal).apply()
        _state.update { it.copy(endpoint = normalized, externalConsent = consentExternal, credentialConfigured = false, error = "") }
        viewModelScope.launch {
            val id = endpointRecordId(normalized)
            val existing = db.modelEndpointDao().getById(id)
            db.modelEndpointDao().upsert(existing ?: ModelEndpointEntity(
                id = id, displayName = "Inference on ${Uri.parse(normalized).host ?: "local endpoint"}",
                provider = if (isOpenAiEndpoint(normalized)) "OpenAI-compatible" else "Ollama",
                baseUrl = normalized, modelId = _state.value.selectedModel
            ))
            refreshModels()
        }
    }

    fun setExternalConsent(allow: Boolean) {
        prefs.edit().putBoolean("external_consent", allow).apply()
        _state.update { it.copy(externalConsent = allow) }
    }

    /** Encrypt the key with Android Keystore; Room stores only its opaque credential reference. */
    fun saveApiKey(secret: String): Boolean {
        val endpoint = _state.value.endpoint
        if (!isOpenAiEndpoint(endpoint)) { _state.update { it.copy(error = "API keys are supported only for OpenAI-compatible /v1 endpoints.") }; return false }
        if (secret.isBlank()) { _state.update { it.copy(error = "Enter a non-empty API key.") }; return false }
        if (!credentialTransportAllowed(endpoint)) { _state.update { it.copy(error = "API keys require HTTPS for non-loopback servers. Use HTTPS or a loopback development endpoint.") }; return false }
        viewModelScope.launch {
            val reference = UUID.randomUUID().toString()
            val id = endpointRecordId(endpoint)
            try {
                withContext(Dispatchers.IO) { credentialVault.save(reference, secret) }
                val old = db.modelEndpointDao().getById(id)
                db.modelEndpointDao().upsert(ModelEndpointEntity(
                    id = id,
                    displayName = "OpenAI-compatible on ${Uri.parse(endpoint).host ?: "local endpoint"}",
                    provider = "OpenAI-compatible", baseUrl = endpoint,
                    modelId = _state.value.selectedModel, credentialReference = reference,
                    isEnabled = true, createdAt = old?.createdAt ?: System.currentTimeMillis()
                ))
                old?.credentialReference?.takeIf { it != reference }?.let { previous -> withContext(Dispatchers.IO) { credentialVault.delete(previous) } }
                _state.update { it.copy(credentialConfigured = true, error = "API key encrypted and saved on this device.") }
                refreshModels()
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { runCatching { credentialVault.delete(reference) } }
                _state.update { it.copy(error = "Could not securely save the API key. Check device lock/storage and try again.") }
            }
        }
        return true
    }

    fun removeApiKey() {
        val endpoint = _state.value.endpoint
        viewModelScope.launch {
            val id = endpointRecordId(endpoint)
            val old = db.modelEndpointDao().getById(id)
            old?.credentialReference?.let { reference -> withContext(Dispatchers.IO) { runCatching { credentialVault.delete(reference) } } }
            old?.let { db.modelEndpointDao().upsert(it.copy(credentialReference = null, updatedAt = System.currentTimeMillis())) }
            _state.update { it.copy(credentialConfigured = false, error = "Saved API key removed from this device.") }
        }
    }

    private fun endpointAllowed(endpoint: String): Boolean {
        val uri = Uri.parse(endpoint)
        val host = uri.host?.lowercase().orEmpty()
        val local = host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
        return local || _state.value.externalConsent
    }

    private fun endpointRecordId(endpoint: String) = "inference:$endpoint"
    private fun isOpenAiEndpoint(endpoint: String) = endpoint.substringBefore('?').endsWith("/v1", ignoreCase = true)
    private fun isLoopback(endpoint: String): Boolean {
        val host = Uri.parse(endpoint).host?.lowercase().orEmpty()
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    }
    private fun credentialTransportAllowed(endpoint: String) = CredentialTransportPolicy.allows(endpoint)

    private suspend fun inferenceClient(endpoint: String): OllamaClient {
        if (!isOpenAiEndpoint(endpoint)) return OllamaClient(endpoint)
        val reference = db.modelEndpointDao().getById(endpointRecordId(endpoint))?.credentialReference
        if (reference == null) return OllamaClient(endpoint)
        if (!credentialTransportAllowed(endpoint)) throw IllegalStateException("Saved credentials are blocked on remote HTTP. Switch to HTTPS before connecting.")
        val secret = withContext(Dispatchers.IO) { credentialVault.read(reference) }
            ?: throw IllegalStateException("Saved API key is unavailable. Enter it again in Settings.")
        return OllamaClient(endpoint, secret)
    }

    fun selectModel(model: String) {
        prefs.edit().putString("selected_model", model).apply()
        _state.update { it.copy(selectedModel = model) }
        persistOllamaEndpoint()
    }

    fun setTemperature(value: Float) { prefs.edit().putFloat("temperature", value).apply(); _state.update { it.copy(temperature = value) } }
    fun setContextLength(value: Int) { prefs.edit().putInt("context_length", value).apply(); _state.update { it.copy(contextLength = value) } }
    fun setCpuThreads(value: Int) { prefs.edit().putInt("cpu_threads", value).apply(); _state.update { it.copy(cpuThreads = value) } }
    private fun options() = InferenceOptions(_state.value.temperature.toDouble(), _state.value.contextLength, _state.value.cpuThreads)

    private fun persistOllamaEndpoint() {
        val s = _state.value
        val openAi = isOpenAiEndpoint(s.endpoint)
        viewModelScope.launch {
            val id = endpointRecordId(s.endpoint)
            val previous = db.modelEndpointDao().getById(id)
            db.modelEndpointDao().upsert(ModelEndpointEntity(
                id = id, displayName = "${if (openAi) "OpenAI-compatible" else "Ollama"} on ${Uri.parse(s.endpoint).host ?: "local endpoint"}",
                provider = if (openAi) "OpenAI-compatible" else "Ollama", baseUrl = s.endpoint, modelId = s.selectedModel,
                credentialReference = previous?.credentialReference, isEnabled = true,
                createdAt = previous?.createdAt ?: System.currentTimeMillis()
            ))
        }
    }

    fun newConversation() {
        viewModelScope.launch {
            val item = ConversationEntity(title = "New conversation")
            db.conversationDao().upsertConversation(item)
            openConversation(item.id)
        }
    }

    fun openConversation(id: String) {
        _state.update { it.copy(activeConversationId = id) }
        messageObservation?.cancel()
        messageObservation = viewModelScope.launch {
            db.conversationDao().observeMessages(id).collect { rows -> _state.update { it.copy(messages = rows) } }
        }
    }

    fun deleteConversation(item: ConversationEntity) {
        viewModelScope.launch {
            db.conversationDao().deleteConversationById(item.id)
            if (_state.value.activeConversationId == item.id) _state.update { it.copy(activeConversationId = null, messages = emptyList()) }
        }
    }

    fun renameConversation(item: ConversationEntity, title: String) {
        val cleanTitle = title.trim().take(80)
        if (cleanTitle.isBlank()) return
        viewModelScope.launch {
            db.conversationDao().upsertConversation(item.copy(title = cleanTitle, updatedAt = System.currentTimeMillis()))
        }
    }

    fun sendMessage(text: String) {
        val prompt = text.trim()
        if (prompt.isBlank() || _state.value.isGenerating) return
        if (!reserveInference()) {
            _state.update { it.copy(error = "Another inference task is already running. Stop it before starting chat generation.") }
            return
        }
        generationJob = viewModelScope.launch {
            val s = _state.value
            if (!s.connected || s.selectedModel.isBlank()) { _state.update { it.copy(error = "Connect to Ollama and select an installed model first.") }; return@launch }
            if (!endpointAllowed(s.endpoint)) { _state.update { it.copy(error = "External endpoint consent is required.") }; return@launch }
            val conversation = if (s.activeConversationId == null) ConversationEntity(title = prompt.take(48))
                else db.conversationDao().getConversation(s.activeConversationId) ?: ConversationEntity(title = prompt.take(48))
            val dao = db.conversationDao()
            dao.upsertConversation(conversation.copy(title = if (conversation.title == "New conversation") prompt.take(48) else conversation.title, updatedAt = System.currentTimeMillis()))
            if (_state.value.activeConversationId != conversation.id) openConversation(conversation.id)
            val userMessage = MessageEntity(conversationId = conversation.id, role = "user", content = prompt, modelId = s.selectedModel)
            dao.upsertMessage(userMessage)
            val past = dao.getMessages(conversation.id).takeLast(32).map { ApiMessage(it.role, it.content) }
            _state.update { it.copy(isGenerating = true, streamingText = "", error = "") }
            val streamed = StringBuilder()
            var lastPublishAt = 0L
            try {
                val result = inferenceClient(s.endpoint).chat(s.selectedModel, past, options = options()) { token ->
                    streamed.append(token)
                    val now = System.nanoTime() / 1_000_000L
                    if (now - lastPublishAt >= 45L) {
                        val snapshot = streamed.toString()
                        _state.update { it.copy(streamingText = snapshot) }
                        lastPublishAt = now
                    }
                }
                val assistant = result.content.ifBlank { "The model returned an empty response." }
                dao.upsertMessage(MessageEntity(conversationId = conversation.id, role = "assistant", content = assistant, modelId = s.selectedModel))
                _state.update { it.copy(streamingText = "") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                val partial = streamed.toString()
                if (partial.isNotBlank()) dao.upsertMessage(MessageEntity(conversationId = conversation.id, role = "assistant", content = "$partial\n\n[Generation stopped]", modelId = s.selectedModel))
                _state.update { it.copy(streamingText = "") }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Inference request failed.") }
            } finally { _state.update { it.copy(isGenerating = false, streamingText = "") } }
        }
        generationJob?.invokeOnCompletion { releaseInference() }
    }

    fun stopGeneration() { generationJob?.cancel(); generationJob = null }

    fun regenerateLast() {
        val messages = _state.value.messages
        val lastUser = messages.lastOrNull { it.role == "user" } ?: return
        viewModelScope.launch {
            val lastAssistant = messages.lastOrNull { it.role == "assistant" }
            val convo = db.conversationDao().getConversation(lastUser.conversationId) ?: return@launch
            val withoutLastTurn = messages.filter { it.id != lastAssistant?.id && it.id != lastUser.id }
            db.conversationDao().saveConversationWithMessages(convo, withoutLastTurn)
            sendMessage(lastUser.content)
        }
    }

    fun registerGguf(uri: Uri, displayName: String) {
        viewModelScope.launch {
            try {
                app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { /* Some document providers do not offer persistable permissions. */ }
            val size = runCatching { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
            db.modelEndpointDao().upsert(ModelEndpointEntity(
                id = "gguf:${uri}", displayName = displayName, provider = "GGUF", baseUrl = uri.toString(), modelId = displayName,
                configurationJson = JSONObject().put("sizeBytes", size).put("status", "registered_only_native_backend_not_included").toString()
            ))
        }
    }

    fun createAgent(name: String, instructions: String) {
        if (name.isBlank()) return
        val agent = AgentEntity(name = name.trim().take(64), description = "User-created local agent", instructions = instructions.trim().take(5000), configurationJson = agentConfig(6, AgentTools.DEFAULT_TOOLS))
        viewModelScope.launch { db.agentDao().upsert(agent); _state.update { it.copy(selectedAgentId = agent.id) } }
    }

    fun updateAgent(agent: AgentEntity, name: String, instructions: String) {
        val cleanName = name.trim().take(64)
        if (cleanName.isBlank()) return
        viewModelScope.launch {
            db.agentDao().upsert(agent.copy(name = cleanName, instructions = instructions.trim().take(5000), updatedAt = System.currentTimeMillis()))
        }
    }

    fun selectAgent(id: String) { _state.update { it.copy(selectedAgentId = id) } }
    fun setAgentTask(value: String) { _state.update { it.copy(agentTask = value) } }

    /** Reads only a user-selected, text-like document and appends bounded UTF-8 content to the agent task. */
    fun importTextDocument(uri: Uri) {
        viewModelScope.launch {
            try {
                val resolver = app.contentResolver
                val mime = resolver.getType(uri).orEmpty().lowercase()
                val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "selected document"
                val textExtension = name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "markdown", "csv", "json", "log")
                if (!mime.startsWith("text/") && mime != "application/json" && !textExtension) {
                    _state.update { it.copy(error = "Choose a text, Markdown, CSV, JSON, or log file. PDF and binary imports are not supported yet.") }
                    return@launch
                }
                val content = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 256 * 1024) { "Selected text file is larger than the 256 KB import limit." }
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    } ?: throw IllegalStateException("Could not open the selected document.")
                }
                val current = _state.value.agentTask
                val addition = "\n\n[Imported document: $name]\n$content"
                require((current.length + addition.length) <= 60_000) { "The task and imported text exceed the 60,000-character limit. Shorten the task or choose a smaller file." }
                _state.update { it.copy(agentTask = current + addition, importedDocumentName = name, error = "") }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message?.take(220) ?: "Could not import the selected text file.") }
            }
        }
    }
    fun setWorkflowTask(value: String) { _state.update { it.copy(workflowTask = value) } }
    fun toggleWorkflowAgent(id: String) { _state.update { old -> old.copy(workflowAgentIds = if (id in old.workflowAgentIds) old.workflowAgentIds - id else old.workflowAgentIds + id) } }

    /** Creates and executes a durable objective independently of a chat turn. */
    fun createObjective(goal: String) {
        val clean = goal.trim().take(8_000)
        if (clean.isBlank()) return
        val s = _state.value
        val agent = s.agents.firstOrNull { it.id == s.selectedAgentId }
        if (agent == null) { _state.update { it.copy(error = "Select an enabled agent first.") }; return }
        if (s.selectedModel.isBlank() || !s.connected) { _state.update { it.copy(error = "Connect to an inference model before planning an objective.") }; return }
        if (!endpointAllowed(s.endpoint)) { _state.update { it.copy(error = "External endpoint consent is required.") }; return }
        if (!reserveInference()) { _state.update { it.copy(error = "Another inference task is running. Stop it before starting an objective.") }; return }
        viewModelScope.launch {
            val row = ObjectiveEntity(title = clean.take(72), goal = clean, status = "planning", agentId = agent.id, modelId = s.selectedModel)
            db.objectiveDao().upsertObjective(row)
            selectObjective(row.id)
            _state.update { it.copy(objectiveRunning = true, objectiveStreaming = "", error = "") }
            ObjectiveExecutionService.start(app, row.id)
            delay(8_000)
            if (!ExecutionMonitor.state.value.running) {
                releaseInference()
                _state.update { it.copy(objectiveRunning = false, error = "The execution service did not start. Check notification permission and try again.") }
            }
        }
    }

    fun selectObjective(id: String) {
        _state.update { it.copy(selectedObjectiveId = id) }
        objectiveObservation?.cancel()
        objectiveObservation = viewModelScope.launch {
            val dao = db.objectiveDao()
            kotlinx.coroutines.coroutineScope {
                launch { dao.observeTasks(id).collect { rows -> _state.update { it.copy(objectiveTasks = rows) } } }
                launch { dao.observeEvents(id).collect { rows -> _state.update { it.copy(objectiveEvents = rows) } } }
                launch { dao.observeArtifacts(id).collect { rows -> _state.update { it.copy(objectiveArtifacts = rows) } } }
            }
        }
    }

    /** Re-running failed or interrupted tools is always an explicit user action. */
    fun resumeObjective(id: String, userApprovedRetry: Boolean) {
        val s = _state.value
        val objective = s.objectives.firstOrNull { it.id == id } ?: return
        val agent = s.agents.firstOrNull { it.id == objective.agentId } ?: run { _state.update { it.copy(error = "The objective's agent is unavailable.") }; return }
        if (s.selectedModel.isBlank() || !s.connected || !endpointAllowed(s.endpoint)) { _state.update { it.copy(error = "Connect to an allowed inference endpoint before resuming.") }; return }
        if (!reserveInference()) { _state.update { it.copy(error = "Another inference task is running.") }; return }
        viewModelScope.launch {
            val tasks = db.objectiveDao().getTasks(id)
            if (tasks.isEmpty()) {
                if (!userApprovedRetry && objective.status == "needs_attention") {
                    releaseInference()
                    _state.update { it.copy(error = "Review the objective and explicitly approve restarting its plan.") }
                    return@launch
                }
                db.objectiveDao().upsertObjective(objective.copy(status = "planning", modelId = s.selectedModel, updatedAt = System.currentTimeMillis()))
            } else {
                val uncertain = tasks.any { it.status == "needs_review" || it.status == "failed" }
                if (uncertain && !userApprovedRetry) {
                    releaseInference()
                    _state.update { it.copy(error = "This objective contains a failed or interrupted task. Review it and explicitly approve retry first.") }
                    return@launch
                }
            }
            _state.update { it.copy(objectiveRunning = true, objectiveStreaming = "", error = "") }
            ObjectiveExecutionService.start(app, id)
            delay(8_000)
            if (!ExecutionMonitor.state.value.running) {
                releaseInference()
                _state.update { it.copy(objectiveRunning = false, error = "The execution service did not start. Check notification permission and try again.") }
            }
        }
    }

    fun pauseObjective() {
        ObjectiveExecutionService.pause(app)
        _state.update { it.copy(objectivePhase = "pausing") }
    }

    fun cancelObjective(id: String) {
        ObjectiveExecutionService.cancel(app)
        viewModelScope.launch {
            delay(1_500)
            val current = db.objectiveDao().getObjective(id) ?: return@launch
            if (current.status == "planning" || current.status == "running") {
                db.objectiveDao().upsertObjective(current.copy(status = "cancelled", updatedAt = System.currentTimeMillis(), error = "Cancelled by user."))
                db.objectiveDao().appendEvent(
                    ObjectiveEventEntity(UUID.randomUUID().toString(), id, null, "cancelled", "Cancelled by user; any in-flight tool may require review.", System.currentTimeMillis())
                )
            }
        }
    }

    private suspend fun recoverInterruptedObjectives() {
        runCatching { ExecutionRecovery.recover(db) }
    }

    init {
        viewModelScope.launch {
            ExecutionMonitor.state.collect { snapshot ->
                _state.update { it.copy(objectiveRunning = snapshot.running, objectivePhase = snapshot.phase) }
                if (!snapshot.running) releaseInference()
            }
        }
        viewModelScope.launch {
            terminalRepository.observeHistory(60).collect { rows -> _state.update { it.copy(terminalHistory = rows) } }
        }
    }

    // ---- Terminal -------------------------------------------------------------------------

    fun refreshTerminalEnvironment() {
        if (_state.value.terminalDiscovering) return
        viewModelScope.launch {
            _state.update { it.copy(terminalDiscovering = true, terminalMessage = "Probing Termux…") }
            val profile = withContext(Dispatchers.IO) { terminalRepository.discover() }
            _state.update {
                it.copy(
                    terminalDiscovering = false,
                    terminalProfile = profile,
                    terminalMessage = profile.detail ?: if (profile.hostAvailable) "Termux host is ready." else "Termux is unavailable.",
                )
            }
        }
    }

    fun requestTerminalCommand(command: String) {
        val clean = command.trim().take(CommandPolicy.MAX_COMMAND_CHARS)
        if (clean.isBlank()) return
        val assessment = CommandPolicy.assess(clean)
        if (assessment.blocked) {
            _state.update { it.copy(terminalMessage = assessment.summary) }
            return
        }
        if (assessment.requiresConfirmation) {
            _state.update { it.copy(terminalPendingCommand = clean, terminalMessage = assessment.summary) }
            return
        }
        executeTerminal(clean, approved = false)
    }

    fun confirmTerminalCommand() {
        val pending = _state.value.terminalPendingCommand ?: return
        executeTerminal(pending, approved = true)
    }

    fun dismissTerminalCommand() {
        _state.update { it.copy(terminalPendingCommand = null, terminalMessage = "") }
    }

    fun setTerminalEnvironment(environment: TerminalEnvironment) {
        _state.update { it.copy(terminalEnvironment = environment) }
    }

    private fun executeTerminal(command: String, approved: Boolean) {
        if (_state.value.terminalRunning) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    terminalRunning = true,
                    terminalOutput = "",
                    terminalPendingCommand = null,
                    terminalMessage = "Running…",
                )
            }
            val environment = _state.value.terminalEnvironment
            val result = withContext(Dispatchers.IO) {
                terminalRepository.execute(
                    command = command,
                    environment = environment,
                    workingDirectory = null,
                    timeoutSeconds = 120,
                    approvedByUser = approved,
                )
            }
            _state.update {
                it.copy(
                    terminalRunning = false,
                    terminalOutput = formatTerminalResult(result),
                    terminalMessage = result.message ?: if (result.isSuccess) "Command completed." else "Command did not succeed.",
                )
            }
        }
    }

    fun clearTerminalHistory() {
        viewModelScope.launch { terminalRepository.clearHistory() }
    }

    fun setTerminalToolsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(ObjectiveExecutionService.KEY_TERMINAL_ENABLED, enabled).apply()
        _state.update { it.copy(terminalToolsEnabled = enabled) }
    }

    fun setTerminalWorkspace(path: String) {
        val clean = path.trim().take(4_096)
        prefs.edit().putString(ObjectiveExecutionService.KEY_TERMINAL_WORKSPACE, clean).apply()
        _state.update { it.copy(terminalWorkspace = clean) }
    }

    private fun formatTerminalResult(result: TerminalCommandResult): String = buildString {
        append(result.kind.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
        result.exitCode?.let { append(" · exit ").append(it) }
        append("\n\n")
        if (result.stdout.isNotBlank()) {
            append(result.stdout.take(24_000))
            if (result.stdout.length > 24_000) append("\n[stdout truncated]")
        } else {
            append("(no stdout)")
        }
        if (result.stderr.isNotBlank()) {
            append("\n\nstderr:\n").append(result.stderr.take(8_000))
        }
        result.message?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it.take(600)) }
    }

    fun runAgent() {
        val s = _state.value
        if (s.isGenerating || s.agentRunning || s.workflowRunning) { _state.update { it.copy(error = "Another inference task is already running. Stop it before starting an agent.") }; return }
        val agent = s.agents.firstOrNull { it.id == s.selectedAgentId } ?: run { _state.update { it.copy(error = "Select an agent first.") }; return }
        if (s.selectedModel.isBlank() || !s.connected) { _state.update { it.copy(error = "Connect to an Ollama model before running agents.") }; return }
        if (!endpointAllowed(s.endpoint)) { _state.update { it.copy(error = "External endpoint consent is required before running agents.") }; return }
        if (s.agentTask.isBlank()) return
        if (!reserveInference()) { _state.update { it.copy(error = "Another inference task is already running.") }; return }
        agentJob = viewModelScope.launch {
            _state.update { it.copy(agentRunning = true, agentOutput = "", agentEvents = emptyList(), error = "") }
            val taskId = UUID.randomUUID().toString()
            val started = System.currentTimeMillis()
            val logDao = db.executionLogDao()
            val row = ExecutionLogEntity(id = taskId, taskId = taskId, status = "running", inputJson = JSONObject().put("agentId", agent.id).put("task", s.agentTask).toString(), startedAt = started)
            logDao.upsert(row)
            try {
                val spec = toSpec(agent, s.selectedModel)
                val outcome = AgentRunner(app, inferenceClient(s.endpoint)).run(spec, s.agentTask, options = options(), onEvent = ::appendAgentEvent) { token ->
                    _state.update { it.copy(agentOutput = it.agentOutput + token) }
                }
                _state.update { it.copy(agentOutput = outcome.result) }
                logDao.upsert(row.copy(status = "completed", outputText = outcome.result, finishedAt = System.currentTimeMillis()))
            } catch (e: kotlinx.coroutines.CancellationException) {
                logDao.upsert(row.copy(status = "cancelled", errorMessage = "Cancelled by user", finishedAt = System.currentTimeMillis()))
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(agentOutput = "", error = e.message ?: "Agent failed.") }
                logDao.upsert(row.copy(status = "failed", errorMessage = e.message, finishedAt = System.currentTimeMillis()))
            } finally { _state.update { it.copy(agentRunning = false) } }
        }
        agentJob?.invokeOnCompletion { releaseInference() }
    }

    private fun appendAgentEvent(event: AgentEvent) { _state.update { it.copy(agentEvents = (it.agentEvents + event).takeLast(100)) } }
    fun stopAgent() { agentJob?.cancel(); agentJob = null }

    fun runWorkflow() {
        val s = _state.value
        if (s.isGenerating || s.agentRunning || s.workflowRunning) { _state.update { it.copy(error = "Another inference task is already running. Stop it before starting a workflow.") }; return }
        val chosen = s.agents.filter { it.id in s.workflowAgentIds }
        if (chosen.isEmpty()) { _state.update { it.copy(error = "Choose at least one agent for the sequential workflow.") }; return }
        if (!s.connected || s.selectedModel.isBlank()) { _state.update { it.copy(error = "Connect to an Ollama model before running workflows.") }; return }
        if (!endpointAllowed(s.endpoint)) { _state.update { it.copy(error = "External endpoint consent is required before running workflows.") }; return }
        if (s.workflowTask.isBlank()) return
        if (!reserveInference()) { _state.update { it.copy(error = "Another inference task is already running.") }; return }
        workflowJob = viewModelScope.launch {
            _state.update { it.copy(workflowRunning = true, workflowOutput = "", error = "") }
            val id = UUID.randomUUID().toString(); val start = System.currentTimeMillis()
            val workflow = s.workflows.firstOrNull()?.id
            val log = ExecutionLogEntity(id = id, workflowId = workflow, taskId = id, status = "running", inputJson = JSONObject().put("agentIds", JSONArray(chosen.map { it.id })).put("task", s.workflowTask).toString(), startedAt = start)
            db.executionLogDao().upsert(log)
            var context = s.workflowTask
            val outputs = mutableListOf<String>()
            try {
                val runner = AgentRunner(app, inferenceClient(s.endpoint))
                chosen.forEachIndexed { index, agent ->
                    _state.update { it.copy(workflowOutput = "${it.workflowOutput}\n\n## ${agent.name}\n") }
                    val outcome = runner.run(toSpec(agent, s.selectedModel), s.workflowTask, context, options(), ::appendAgentEvent) { token ->
                        _state.update { it.copy(workflowOutput = it.workflowOutput + token) }
                    }
                    outputs += "${agent.name}:\n${outcome.result}"
                    context = outputs.joinToString("\n\n").take(24_000)
                }
                val final = outputs.joinToString("\n\n")
                _state.update { it.copy(workflowOutput = final) }
                db.executionLogDao().upsert(log.copy(status = "completed", outputText = final, finishedAt = System.currentTimeMillis()))
            } catch (e: kotlinx.coroutines.CancellationException) {
                db.executionLogDao().upsert(log.copy(status = "cancelled", outputText = outputs.joinToString("\n\n"), errorMessage = "Cancelled by user", finishedAt = System.currentTimeMillis()))
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Workflow failed.") }
                db.executionLogDao().upsert(log.copy(status = "failed", outputText = outputs.joinToString("\n\n"), errorMessage = e.message, finishedAt = System.currentTimeMillis()))
            } finally { _state.update { it.copy(workflowRunning = false) } }
        }
        workflowJob?.invokeOnCompletion { releaseInference() }
    }
    fun stopWorkflow() { workflowJob?.cancel(); workflowJob = null }

    private fun toSpec(agent: AgentEntity, model: String): AgentSpec {
        val config = runCatching { JSONObject(agent.configurationJson ?: "{}") }.getOrDefault(JSONObject())
        val rawTools = config.optJSONArray("tools")
        val tools = if (rawTools == null) AgentTools.DEFAULT_TOOLS else (0 until rawTools.length()).map { rawTools.optString(it) }.toSet()
        return AgentSpec(agent.id, agent.name, model, agent.instructions, config.optInt("maxSteps", 6), tools)
    }

    private fun agentConfig(maxSteps: Int, tools: Set<String>) = JSONObject()
        .put("maxSteps", maxSteps)
        .put("tools", JSONArray(tools.sorted()))
        .toString()
}
