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
import com.nyx.ai.autonomy.Preflight
import com.nyx.ai.autonomy.PreflightInput
import com.nyx.ai.autonomy.PreflightResult
import com.nyx.ai.service.ObjectiveExecutionService
import com.nyx.ai.terminal.CommandPolicy
import com.nyx.ai.terminal.CapabilityState
import com.nyx.ai.terminal.EnvironmentCapabilities
import com.nyx.ai.terminal.EnvironmentProfile
import com.nyx.ai.terminal.HostShell
import com.nyx.ai.terminal.TerminalCommandResult
import com.nyx.ai.terminal.TerminalEnvironment
import com.nyx.ai.terminal.TerminalRepository
import com.nyx.ai.terminal.TerminalResultKind
import com.nyx.ai.terminal.WorkspaceCheck
import com.nyx.ai.terminal.DependencyInstaller
import com.nyx.ai.terminal.DependencyPlan
import com.nyx.ai.terminal.DependencyPlanner
import com.nyx.ai.terminal.ExecutableOrigin
import com.nyx.ai.terminal.InstallationReport
import com.nyx.ai.terminal.PackageCandidate
import com.nyx.ai.terminal.ProjectKind
import com.nyx.ai.terminal.ToolState
import com.nyx.ai.terminal.WorkspaceProvisionRequest
import com.nyx.ai.terminal.WorkspaceProvisionResult
import com.nyx.ai.terminal.WorkspaceProvisioner
import com.nyx.ai.autonomy.policy.ActionKind
import com.nyx.ai.autonomy.policy.ActionRequest
import com.nyx.ai.autonomy.policy.AutonomyMode
import com.nyx.ai.autonomy.policy.AutonomyRuntime
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
import com.nyx.ai.settings.EnvironmentProfileStore
import com.nyx.ai.settings.InferenceClientFactory
import com.nyx.ai.network.InferenceProvider
import com.nyx.ai.nativeinference.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
    val nativeModels: List<NativeModel> = emptyList(),
    val nativeStatus: NativeRuntimeStatus = NativeRuntimeStatus(),
    val nativeImporting: Boolean = false,
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
    val guestAlias: String = "debian",
    val environmentCapabilities: EnvironmentCapabilities? = null,
    val workspaceCheck: WorkspaceCheck? = null,
    val preflight: PreflightResult? = null,
    val autonomyMode: com.nyx.ai.autonomy.policy.AutonomyMode = com.nyx.ai.autonomy.policy.AutonomyMode.MANUAL,
    val autonomyStopped: Boolean = false,
    val dependencyPlan: DependencyPlan? = null,
    val installationReport: InstallationReport? = null,
    val pendingInstallApproval: List<PackageCandidate> = emptyList(),
    val workspaceProvisioning: Boolean = false,
    val policyMessage: String = "",
    val temperature: Float = 0.7f,
    val contextLength: Int = 4096,
    val cpuThreads: Int = 4,
    val error: String = ""
) { val nativeMode: Boolean get() = endpoint == "native://local" }

class NyxViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val db = NyxDatabase.getInstance(application)
    private val credentialVault = CredentialVault(application)
    private val nativeRepository = NativeModelRepository(application)
    private val providerFactory = InferenceClientFactory(db, credentialVault, application)
    private val prefs = application.getSharedPreferences("nyx_settings", 0)
    private val environmentProfiles = EnvironmentProfileStore(application)
    private val _state = MutableStateFlow(
        NyxState(
            endpoint = prefs.getString("endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT,
            externalConsent = prefs.getBoolean("external_consent", false),
            selectedModel = prefs.getString("selected_model", "") ?: "",
            temperature = prefs.getFloat("temperature", 0.7f),
            contextLength = prefs.getInt("context_length", 4096),
            cpuThreads = prefs.getInt("cpu_threads", 4),
            terminalToolsEnabled = prefs.getBoolean(ObjectiveExecutionService.KEY_TERMINAL_ENABLED, false),
            terminalEnvironment = runCatching { TerminalEnvironment.valueOf(prefs.getString("terminal_environment", "TERMUX_HOST") ?: "TERMUX_HOST") }.getOrDefault(TerminalEnvironment.TERMUX_HOST),
            terminalWorkspace = prefs.getString(ObjectiveExecutionService.KEY_TERMINAL_WORKSPACE, "") ?: "",
            guestAlias = prefs.getString("terminal_guest_alias", "debian") ?: "debian",
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
    @Volatile private var objectiveReservation = false

    private fun reserveInference(): Boolean = synchronized(inferenceReservationLock) {
        if (inferenceReserved || ExecutionMonitor.state.value.running) false else { inferenceReserved = true; true }
    }

    private fun releaseInference() = synchronized(inferenceReservationLock) { inferenceReserved = false }

    init {
        AutonomyRuntime.initialize(app)
        _state.update {
            it.copy(autonomyMode = AutonomyRuntime.mode(), autonomyStopped = AutonomyRuntime.policy().stopped)
        }
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
        viewModelScope.launch { NativeRuntime.status.collect { status -> _state.update { it.copy(nativeStatus = status) } } }
        refreshNativeModels()
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
                val models = inferenceProvider(endpoint).listModels()
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
        if (ExecutionMonitor.state.value.running || _state.value.isGenerating || _state.value.agentRunning || _state.value.workflowRunning) { _state.update { it.copy(error = "Stop active inference before changing providers.") }; return }
        val normalized = endpoint.trim().trimEnd('/')
        val valid = runCatching {
            val uri = Uri.parse(normalized)
            CredentialTransportPolicy.validEndpoint(normalized)
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
        if (endpoint == "native://local") return true
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

    private suspend fun inferenceProvider(endpoint: String): InferenceProvider {
        persistEndpointNow(_state.value.copy(endpoint = endpoint))
        return providerFactory.providerFor(endpointRecordId(endpoint)).provider
    }

    fun selectModel(model: String) {
        if (_state.value.isGenerating || _state.value.agentRunning || _state.value.workflowRunning || ExecutionMonitor.state.value.running) { _state.update { it.copy(error = "Stop active work before changing the model.") }; return }
        prefs.edit().putString("selected_model", model).apply()
        _state.update { it.copy(selectedModel = model) }
        persistOllamaEndpoint()
    }

    fun setTemperature(value: Float) { prefs.edit().putFloat("temperature", value).apply(); _state.update { it.copy(temperature = value) } }
    fun setContextLength(value: Int) { prefs.edit().putInt("context_length", value).apply(); _state.update { it.copy(contextLength = value) } }
    fun setCpuThreads(value: Int) { prefs.edit().putInt("cpu_threads", value).apply(); _state.update { it.copy(cpuThreads = value) } }
    private fun options() = InferenceOptions(_state.value.temperature.toDouble(), _state.value.contextLength, _state.value.cpuThreads)

    private fun persistOllamaEndpoint() {
        val snapshot = _state.value
        viewModelScope.launch { persistEndpointNow(snapshot) }
    }
    private suspend fun persistEndpointNow(s: NyxState) {
        val id = endpointRecordId(s.endpoint)
        val previous = db.modelEndpointDao().getById(id)
        val provider = if (s.nativeMode) "native-gguf" else if (isOpenAiEndpoint(s.endpoint)) "OpenAI-compatible" else "Ollama"
        db.modelEndpointDao().upsert(ModelEndpointEntity(id = id, displayName = if (s.nativeMode) "Native GGUF on this device" else "$provider on ${Uri.parse(s.endpoint).host}",
            provider = provider, baseUrl = s.endpoint, modelId = s.selectedModel, credentialReference = if (s.nativeMode) null else previous?.credentialReference,
            configurationJson = JSONObject().put("temperature", s.temperature).put("contextLength", s.contextLength).put("cpuThreads", s.cpuThreads).toString(),
            createdAt = previous?.createdAt ?: System.currentTimeMillis()))
    }
    private suspend fun snapshotObjective(id: String, s: NyxState) {
        persistEndpointNow(s)
        val snapshot = JSONObject().put("endpointId", endpointRecordId(s.endpoint)).put("modelId", s.selectedModel)
            .put("options", JSONObject().put("temperature", s.temperature).put("contextLength", s.contextLength).put("cpuThreads", s.cpuThreads))
            .put("terminalEnabled", s.terminalToolsEnabled).put("workspace", s.terminalWorkspace)
            .put("environment", s.terminalEnvironment.name).put("guestAlias", s.guestAlias)
        db.objectiveDao().appendEvent(ObjectiveEventEntity(UUID.randomUUID().toString(), id, null, "execution_config", snapshot.toString(), System.currentTimeMillis()))
    }

    fun refreshNativeModels() {
        viewModelScope.launch(Dispatchers.IO) { _state.update { it.copy(nativeModels = nativeRepository.models()) } }
    }
    fun useNativeProvider() {
        if (_state.value.isGenerating || _state.value.agentRunning || _state.value.workflowRunning || ExecutionMonitor.state.value.running) return
        prefs.edit().putString("endpoint", "native://local").putString("selected_model", "").apply()
        _state.update { it.copy(endpoint = "native://local", selectedModel = "", credentialConfigured = false) }
        refreshNativeModels(); refreshModels()
    }
    fun loadNativeModel(model: NativeModel) {
        if (_state.value.isGenerating || _state.value.agentRunning || _state.value.workflowRunning || ExecutionMonitor.state.value.running) return
        viewModelScope.launch {
            try {
                _state.update { it.copy(endpoint = "native://local", selectedModel = model.path) }
                prefs.edit().putString("endpoint", "native://local").putString("selected_model", model.path).apply()
                NativeRuntime.load(model.path, options())
                refreshModels()
            } catch (cancel: CancellationException) { throw cancel } catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Native model initialization failed.") } }
        }
    }
    fun unloadNativeModel() {
        if (ExecutionMonitor.state.value.running) pauseObjective()
        stopGeneration(); stopAgent(); stopWorkflow()
        viewModelScope.launch { NativeRuntime.unload() }
    }
    fun deleteNativeModel(model: NativeModel) {
        if (_state.value.isGenerating || _state.value.agentRunning || _state.value.workflowRunning || ExecutionMonitor.state.value.running) return
        viewModelScope.launch {
            try { nativeRepository.delete(model.id); refreshNativeModels(); if (_state.value.nativeMode) refreshModels() }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Could not remove model.") } }
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
            if (!s.connected || s.selectedModel.isBlank()) { _state.update { it.copy(error = "Select an available inference provider and model first.") }; return@launch }
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
                val result = inferenceProvider(s.endpoint).chat(s.selectedModel, past, tools = null, options = options()) { token ->
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
                if (partial.isNotBlank()) withContext(NonCancellable) { dao.upsertMessage(MessageEntity(conversationId = conversation.id, role = "assistant", content = "$partial\n\n[Generation stopped]", modelId = s.selectedModel)) }
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
        if (_state.value.nativeImporting) return
        viewModelScope.launch {
            _state.update { it.copy(nativeImporting = true, error = "") }
            try {
                nativeRepository.import(uri)
                refreshNativeModels()
                if (_state.value.nativeMode) refreshModels()
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "GGUF import failed.") } }
            finally { _state.update { it.copy(nativeImporting = false) } }
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
    fun createObjective(goal: String, development: Boolean = false) {
        val clean = goal.trim().take(8_000)
        if (clean.isBlank()) return
        if (development && !_state.value.terminalToolsEnabled) { _state.update { it.copy(error = "Enable an approved development workspace first.") }; return }
        if (development) {
            val pre = preflightDevelopment()
            if (!pre.ready) {
                _state.update {
                    it.copy(error = "Preflight blocked: " + pre.blockers.joinToString("; ") { blocker -> "${blocker.code} — ${blocker.message}" })
                }
                return
            }
        }
        val s = _state.value
        val agent = s.agents.firstOrNull { it.id == s.selectedAgentId }
        if (agent == null) { _state.update { it.copy(error = "Select an enabled agent first.") }; return }
        if (s.selectedModel.isBlank() || !s.connected) { _state.update { it.copy(error = "Connect to an inference model before planning an objective.") }; return }
        if (!endpointAllowed(s.endpoint)) { _state.update { it.copy(error = "External endpoint consent is required.") }; return }
        if (!reserveInference()) { _state.update { it.copy(error = "Another inference task is running. Stop it before starting an objective.") }; return }
        viewModelScope.launch {
            val row = ObjectiveEntity(title = clean.take(72), goal = clean, status = "planning", agentId = agent.id, modelId = s.selectedModel)
            db.objectiveDao().upsertObjective(row)
            snapshotObjective(row.id, s)
            if (development) {
                require(s.terminalToolsEnabled) { "Enable an approved development workspace first." }
                db.objectiveDao().upsertTask(ObjectiveTaskEntity(objectiveId = row.id, ordinal = 0, title = "Inspect, establish baseline, repair and retest", instructions = clean, workerKind = "development", maxAttempts = 1, evidenceJson = "[]"))
            }
            objectiveReservation = true
            selectObjective(row.id)
            _state.update { it.copy(objectiveRunning = true, objectiveStreaming = "", error = "") }
            try { ObjectiveExecutionService.start(app, row.id) } catch (e: Exception) {
                objectiveReservation = false; releaseInference()
                db.objectiveDao().upsertObjective(row.copy(status = "needs_attention", error = "Service start rejected: ${e.javaClass.simpleName}"))
                _state.update { it.copy(objectiveRunning = false, error = "Android rejected service start. Open the app, grant notifications and try again.") }
                return@launch
            }
            delay(8_000)
            if (!ExecutionMonitor.state.value.running && db.objectiveDao().getObjective(row.id)?.status == "planning") {
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
            snapshotObjective(id, s)
            objectiveReservation = true
            try { ObjectiveExecutionService.start(app, id) } catch (e: Exception) {
                objectiveReservation = false; releaseInference()
                _state.update { it.copy(objectiveRunning = false, error = "Android rejected service start: ${e.javaClass.simpleName}") }
                return@launch
            }
            delay(8_000)
            if (!ExecutionMonitor.state.value.running && db.objectiveDao().getObjective(id)?.status in setOf("planning", "running")) {
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
        if (ExecutionMonitor.state.value.objectiveId == id && ExecutionMonitor.state.value.running) ObjectiveExecutionService.cancel(app)
        else viewModelScope.launch { db.objectiveDao().getObjective(id)?.let { db.objectiveDao().upsertObjective(it.copy(status = "cancelled", updatedAt = System.currentTimeMillis())) } }
    }

    private suspend fun recoverInterruptedObjectives() {
        runCatching { ExecutionRecovery.recover(db) }
    }

    init {
        viewModelScope.launch {
            ExecutionMonitor.state.collect { snapshot ->
                _state.update { it.copy(objectiveRunning = snapshot.running, objectivePhase = snapshot.phase) }
                if (!snapshot.running && objectiveReservation) { objectiveReservation = false; releaseInference() }
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
            val environment = _state.value.terminalEnvironment
            val alias = _state.value.guestAlias
            val guestVerified = profile.verifiedDistros.any { it.equals(alias, ignoreCase = true) }
            val capabilities = if (environment == TerminalEnvironment.TERMUX_HOST || guestVerified) {
                withContext(Dispatchers.IO) {
                    runCatching { terminalRepository.probeCapabilities(environment, alias) }.getOrNull()
                }
            } else null
            capabilities?.let { environmentProfiles.save(it) }
            val workspace = _state.value.terminalWorkspace
            val check = if (workspace.isNotBlank() && (environment == TerminalEnvironment.TERMUX_HOST || guestVerified)) {
                withContext(Dispatchers.IO) {
                    runCatching { terminalRepository.validateWorkspace(environment, workspace, alias) }.getOrNull()
                }
            } else null
            _state.update {
                it.copy(
                    terminalDiscovering = false,
                    terminalProfile = profile,
                    environmentCapabilities = capabilities ?: it.environmentCapabilities,
                    workspaceCheck = check ?: it.workspaceCheck,
                    terminalMessage = profile.detail ?: if (profile.hostAvailable) "Termux host is ready." else "Termux is unavailable.",
                )
            }
        }
    }

    /** Refreshes only the capability profile, keeping the existing discovery result. */
    fun refreshEnvironmentCapabilities() {
        if (_state.value.terminalDiscovering) return
        viewModelScope.launch {
            val s = _state.value
            val guestVerified = s.terminalProfile?.verifiedDistros?.any { it.equals(s.guestAlias, ignoreCase = true) } == true
            if (s.terminalEnvironment == TerminalEnvironment.DEBIAN_PROOT && !guestVerified) {
                _state.update { it.copy(terminalMessage = "Verify the ${s.guestAlias} guest before probing its tools.") }
                return@launch
            }
            _state.update { it.copy(terminalDiscovering = true, terminalMessage = "Probing environment capabilities…") }
            val capabilities = withContext(Dispatchers.IO) {
                runCatching { terminalRepository.probeCapabilities(s.terminalEnvironment, s.guestAlias) }.getOrNull()
            }
            capabilities?.let { environmentProfiles.save(it) }
            _state.update {
                it.copy(
                    terminalDiscovering = false,
                    environmentCapabilities = capabilities ?: it.environmentCapabilities,
                    terminalMessage = capabilities?.let { profile ->
                        "Capabilities refreshed: ${profile.tools.count { tool -> tool.value.state == CapabilityState.VERIFIED }} verified."
                    } ?: "Capability probe did not return a result.",
                )
            }
        }
    }

    fun setGuestAlias(alias: String) {
        if (!HostShell.isSafeAlias(alias)) return
        prefs.edit().putString("terminal_guest_alias", alias).apply()
        _state.update { it.copy(guestAlias = alias, environmentCapabilities = null, workspaceCheck = null) }
    }

    /**
     * Deterministic preflight for a development objective. It never calls a model and reports the
     * exact blocker so the user can fix the environment instead of looping on failed commands.
     */
    fun preflightDevelopment(): PreflightResult {
        val s = _state.value
        val guestVerified = s.terminalProfile?.verifiedDistros?.any { it.equals(s.guestAlias, ignoreCase = true) } == true
        val environmentVerified = when (s.terminalEnvironment) {
            TerminalEnvironment.TERMUX_HOST -> s.terminalProfile?.isolatedHost == true
            TerminalEnvironment.DEBIAN_PROOT -> guestVerified
        }
        val capabilities = s.environmentCapabilities?.tools?.mapValues { it.value.state }.orEmpty()
        val required = when (s.terminalEnvironment) {
            TerminalEnvironment.TERMUX_HOST -> setOf("sh", "realpath", "sha256sum")
            TerminalEnvironment.DEBIAN_PROOT -> setOf("sh")
        }
        val result = Preflight.evaluate(
            PreflightInput(
                providerConfigured = s.endpoint.isNotBlank(),
                modelSelected = s.selectedModel.isNotBlank() && s.connected,
                requiresTerminal = true,
                environmentVerified = environmentVerified,
                environmentKind = if (s.terminalEnvironment == TerminalEnvironment.TERMUX_HOST) "termux_host" else "proot:${s.guestAlias}",
                workspaceAuthorized = s.terminalToolsEnabled,
                workspaceAccessible = s.workspaceCheck is WorkspaceCheck.Valid,
                requiredTools = required,
                capabilities = capabilities,
                availableMemoryMb = availableMemoryMb(),
                requiresDevelopment = true,
            )
        )
        _state.update { it.copy(preflight = result) }
        return result
    }

    private fun availableMemoryMb(): Long? = try {
        val manager = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        info.availMem / (1024L * 1024L)
    } catch (_: Exception) {
        null
    }

    fun requestTerminalCommand(command: String) {
        if (command.trim().length > CommandPolicy.MAX_COMMAND_CHARS) { _state.update { it.copy(terminalMessage = "Command too long; no partial command will be executed.") }; return }
        val clean = command.trim()
        if (clean.isBlank()) return
        val assessment = CommandPolicy.assess(clean)
        if (assessment.blocked) {
            _state.update { it.copy(terminalMessage = assessment.summary) }
            return
        }
        _state.update { it.copy(terminalPendingCommand = clean, terminalMessage = assessment.summary) }
    }

    fun confirmTerminalCommand() {
        val pending = _state.value.terminalPendingCommand ?: return
        executeTerminal(pending, approved = true)
    }

    fun dismissTerminalCommand() {
        _state.update { it.copy(terminalPendingCommand = null, terminalMessage = "") }
    }

    fun setTerminalEnvironment(environment: TerminalEnvironment) {
        prefs.edit().putString("terminal_environment", environment.name).apply()
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
                    workingDirectory = _state.value.terminalWorkspace.takeIf { it.startsWith("/") },
                    timeoutSeconds = 120,
                    approvedByUser = approved,
                    useTimeoutBinary = _state.value.terminalProfile?.timeoutAvailable == true,
                    guestAlias = _state.value.guestAlias,
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
        val raw = path.trim().trimEnd('/')
        val clean = if (raw == "~" || raw.startsWith("~/")) _state.value.terminalProfile?.home?.let { it + raw.removePrefix("~") } ?: raw else raw
        if (clean.isBlank()) { _state.update { it.copy(error = "Enter an absolute workspace path.") }; return }
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(terminalMessage = "Verifying workspace in the selected environment…") }
            val check = withContext(Dispatchers.IO) {
                runCatching { terminalRepository.validateWorkspace(s.terminalEnvironment, clean, s.guestAlias) }
                    .getOrElse { WorkspaceCheck.Invalid("probe_failed", it.message?.take(200) ?: "Workspace probe failed", "Run discovery and retry.") }
            }
            if (check is WorkspaceCheck.Valid) {
                prefs.edit().putString(ObjectiveExecutionService.KEY_TERMINAL_WORKSPACE, check.normalizedPath).apply()
                _state.update {
                    it.copy(
                        terminalWorkspace = check.normalizedPath,
                        workspaceCheck = check,
                        terminalMessage = "Workspace verified: ${check.normalizedPath}",
                        error = "",
                    )
                }
            } else {
                val invalid = check as WorkspaceCheck.Invalid
                _state.update { it.copy(workspaceCheck = invalid, error = invalid.message) }
            }
        }
    }

    /** Switches the one shared policy mode and creates the routine grants implied by that choice. */
    fun setAutonomyMode(mode: AutonomyMode) {
        AutonomyRuntime.setMode(app, mode)
        if (mode != AutonomyMode.MANUAL) {
            AutonomyRuntime.grantRoutineScope(
                app,
                policyEnvironmentId(),
                _state.value.terminalWorkspace.takeIf { it.startsWith('/') },
            )
        }
        _state.update {
            it.copy(
                autonomyMode = mode,
                autonomyStopped = AutonomyRuntime.policy().stopped,
                policyMessage = when (mode) {
                    AutonomyMode.MANUAL -> "Manual approval: every consequential action asks first."
                    AutonomyMode.SMART -> "Smart approval: routine authorized work runs; higher-risk actions ask."
                    AutonomyMode.FULL -> "Full autonomy inside the granted scope. Android permissions and privacy stops still apply."
                },
            )
        }
    }

    fun emergencyStopAutonomy() {
        AutonomyRuntime.emergencyStop(app)
        terminalRepository.cancelAll()
        _state.update {
            it.copy(
                autonomyStopped = true,
                policyMessage = "Emergency stop active. New consequential actions are denied until you resume.",
            )
        }
    }

    fun resumeAutonomy() {
        AutonomyRuntime.resetStop(app)
        _state.update {
            it.copy(autonomyStopped = false, policyMessage = "Autonomy resumed. Existing grants remain in effect.")
        }
    }

    fun revokeAutonomyGrants() {
        AutonomyRuntime.clearGrants(app)
        _state.update {
            it.copy(
                autonomyMode = AutonomyMode.MANUAL,
                autonomyStopped = false,
                policyMessage = "All grants revoked. Manual approval restored.",
            )
        }
    }

    private fun policyEnvironmentId(): String =
        if (_state.value.terminalEnvironment == TerminalEnvironment.TERMUX_HOST) "termux_host"
        else "proot:${_state.value.guestAlias}"

    /** Builds a real dependency plan from the last capability probe; never guesses tool availability. */
    fun prepareEnvironment(projectKind: ProjectKind = ProjectKind.KOTLIN_JVM) {
        if (_state.value.autonomyStopped) {
            _state.update { it.copy(policyMessage = "Emergency stop is active; environment work is blocked.") }
            return
        }
        val capabilities = _state.value.environmentCapabilities
        if (capabilities == null) {
            _state.update { it.copy(policyMessage = "Run Detect environment before preparing the toolchain.") }
            return
        }
        val host = _state.value.terminalEnvironment == TerminalEnvironment.TERMUX_HOST
        val tools = capabilities.tools.mapValues { (_, tool) ->
            ToolState(
                present = tool.state != CapabilityState.MISSING && tool.state != CapabilityState.NOT_CHECKED,
                usable = tool.state == CapabilityState.VERIFIED &&
                    (host || tool.origin == ExecutableOrigin.GUEST_NATIVE),
                origin = tool.origin,
                version = tool.version,
            )
        }
        val plan = DependencyPlanner.plan(projectKind, policyEnvironmentId(), tools)
        _state.update {
            it.copy(
                dependencyPlan = plan,
                installationReport = null,
                pendingInstallApproval = emptyList(),
                policyMessage = when {
                    plan.blockers.isNotEmpty() -> plan.blockers.first()
                    plan.candidates.isEmpty() -> "All required tools are already verified."
                    else -> "${plan.candidates.size} package(s) required for ${projectKind.name.replace('_', ' ').lowercase()}."
                },
            )
        }
        if (plan.canProceed && plan.candidates.isNotEmpty() &&
            (_state.value.autonomyMode == AutonomyMode.FULL || _state.value.autonomyMode == AutonomyMode.SMART)
        ) {
            installDependencies(userApproved = false)
        }
    }

    fun approveDependencies() = installDependencies(userApproved = true)

    private fun installDependencies(userApproved: Boolean) {
        val plan = _state.value.dependencyPlan ?: return
        if (plan.candidates.isEmpty()) return
        viewModelScope.launch {
            _state.update {
                it.copy(policyMessage = "Installing ${plan.candidates.size} package(s)…", pendingInstallApproval = emptyList())
            }
            val report = withContext(Dispatchers.IO) {
                DependencyInstaller(terminalRepository).install(
                    plan = plan,
                    environment = _state.value.terminalEnvironment,
                    guestAlias = _state.value.guestAlias,
                    authorize = { candidate: PackageCandidate ->
                        val decision = AutonomyRuntime.authorize(
                            app,
                            ActionRequest(
                                kind = ActionKind.INSTALL_PACKAGE,
                                target = candidate.packageName,
                                environmentId = policyEnvironmentId(),
                                trustedSource = candidate.trusted,
                            ),
                        )
                        decision.allowed || (decision.requiresApproval && userApproved)
                    },
                )
            }
            _state.update {
                it.copy(
                    installationReport = report,
                    pendingInstallApproval = report.needsApproval,
                    policyMessage = report.message,
                )
            }
            if (report.succeeded) refreshEnvironmentCapabilities()
        }
    }

    /** Creates a verified project workspace without requiring the user to run mkdir. */
    fun createWorkspace(projectName: String) {
        val s = _state.value
        if (s.autonomyStopped) {
            _state.update { it.copy(policyMessage = "Emergency stop is active; workspace creation is blocked.") }
            return
        }
        if (s.environmentCapabilities == null) {
            _state.update { it.copy(policyMessage = "Run Detect environment before creating a workspace.") }
            return
        }
        val base = WorkspaceProvisioner.defaultBase(s.terminalEnvironment, s.terminalProfile?.home)
        if (base == null) {
            _state.update { it.copy(policyMessage = "Environment HOME is unknown; run Detect environment again.") }
            return
        }
        val target = WorkspaceProvisioner.planPath(base, projectName)
        val decision = AutonomyRuntime.authorize(
            app,
            ActionRequest(
                kind = ActionKind.WRITE_WORKSPACE,
                target = base,
                environmentId = policyEnvironmentId(),
                insideAuthorizedWorkspace = true,
            ),
        )
        if (!decision.allowed && !decision.requiresApproval) {
            _state.update { it.copy(policyMessage = "Workspace creation was denied: ${decision.reason}") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(workspaceProvisioning = true, policyMessage = "Creating $target…") }
            val result = withContext(Dispatchers.IO) {
                WorkspaceProvisioner.provision(
                    repository = terminalRepository,
                    request = WorkspaceProvisionRequest(
                        environment = s.terminalEnvironment,
                        baseDirectory = base,
                        projectName = projectName,
                        guestAlias = s.guestAlias,
                    ),
                )
            }
            when (result) {
                is WorkspaceProvisionResult.Created -> {
                    prefs.edit().putString(ObjectiveExecutionService.KEY_TERMINAL_WORKSPACE, result.path).apply()
                    _state.update {
                        it.copy(
                            workspaceProvisioning = false,
                            terminalWorkspace = result.path,
                            workspaceCheck = WorkspaceCheck.Valid(result.path),
                            policyMessage = "Workspace ready: ${result.path}",
                            error = "",
                        )
                    }
                }

                is WorkspaceProvisionResult.Failed -> _state.update {
                    it.copy(workspaceProvisioning = false, policyMessage = result.message, error = result.message)
                }
            }
        }
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
        if (s.selectedModel.isBlank() || !s.connected) { _state.update { it.copy(error = "Connect to a model before running agents.") }; return }
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
                val outcome = AgentRunner(app, inferenceProvider(s.endpoint)).run(spec, s.agentTask, options = options(), onEvent = ::appendAgentEvent) { token ->
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
        if (!s.connected || s.selectedModel.isBlank()) { _state.update { it.copy(error = "Connect to a model before running workflows.") }; return }
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
                val runner = AgentRunner(app, inferenceProvider(s.endpoint))
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
