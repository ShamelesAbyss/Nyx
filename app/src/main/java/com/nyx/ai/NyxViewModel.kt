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
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.autonomy.ExecutionTimeoutPolicyStore
import com.nyx.ai.autonomy.ExecutionTimeoutProfile
import com.nyx.ai.autonomy.WorkerActivityBus
import com.nyx.ai.autonomy.WorkerActivityKind
import com.nyx.ai.autonomy.Preflight
import com.nyx.ai.autonomy.PreflightInput
import com.nyx.ai.autonomy.PreflightResult
import com.nyx.ai.service.ObjectiveExecutionService
import com.nyx.ai.service.OllamaHostService
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
import com.nyx.ai.terminal.WorkspaceDeleteResult
import com.nyx.ai.terminal.WorkspaceLifecycle
import com.nyx.ai.terminal.WorkspaceRecord
import com.nyx.ai.terminal.WorkspaceRegistry
import com.nyx.ai.terminal.WorkspaceStartupDecision
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
import com.nyx.ai.skills.ArsenalRepository
import com.nyx.ai.skills.ArsenalSkill
import com.nyx.ai.skills.ArsenalIssue
import com.nyx.ai.gateway.GatewayHealth
import com.nyx.ai.gateway.GatewayRepository
import com.nyx.ai.gateway.GatewayServerConfig
import com.nyx.ai.gateway.GatewayTool
import com.nyx.ai.ollama.OllamaServerManager
import com.nyx.ai.ollama.OllamaServerState
import com.nyx.ai.ollama.OllamaServerStatus
import com.nyx.ai.ollama.OllamaProcess
import com.nyx.ai.network.InferenceProvider
import com.nyx.ai.nativeinference.*
import com.nyx.ai.performance.CalibrationPlanner
import com.nyx.ai.performance.CalibrationRunner
import com.nyx.ai.performance.OperatingMode
import com.nyx.ai.performance.PerformanceController
import com.nyx.ai.performance.PerformanceSnapshot
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
import java.io.File

private const val DEFAULT_ENDPOINT = "http://127.0.0.1:11434"

data class NyxState(
    val endpoint: String = DEFAULT_ENDPOINT,
    val externalConsent: Boolean = false,
    val credentialConfigured: Boolean = false,
    val connected: Boolean = false,
    val models: List<LocalModel> = emptyList(),
    val selectedModel: String = "",
    val diagnosticRunning: Boolean = false,
    val diagnosticSummary: String = "",
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
    val terminalOperations: List<com.nyx.ai.terminal.TerminalOperationEvidence> = emptyList(),
    val requestedTerminalContexts: Int = 3,
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
    val pendingWorkspaceCreation: String? = null,
    val managedWorkspaces: List<WorkspaceRecord> = emptyList(),
    val pendingWorkspaceDeletion: List<WorkspaceRecord> = emptyList(),
    val workspaceLifecycleBusy: Boolean = false,
    val workspaceLifecycleMessage: String = "",
    val policyMessage: String = "",
    val arsenalSkills: List<ArsenalSkill> = emptyList(),
    val arsenalMessage: String = "",
    val gatewayServers: List<GatewayServerConfig> = emptyList(),
    val gatewayHealth: Map<String, GatewayHealth> = emptyMap(),
    val gatewayTools: Map<String, List<GatewayTool>> = emptyMap(),
    val gatewayMessage: String = "",
    val gatewayOutput: String = "",
    val ollamaServer: OllamaServerState = OllamaServerState(),
    val ollamaOperationRunning: Boolean = false,
    val temperature: Float = 0.7f,
    val contextLength: Int = 4096,
    val cpuThreads: Int = 4,
    val performance: PerformanceSnapshot = PerformanceSnapshot(),
    val executionTimeoutPolicy: ExecutionTimeoutPolicy = ExecutionTimeoutPolicy.forProfile(),
    val error: String = ""
) { val nativeMode: Boolean get() = endpoint == "native://local" }

class NyxViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val db = NyxDatabase.getInstance(application)
    private val credentialVault = CredentialVault(application)
    private val nativeRepository = NativeModelRepository(application)
    private val providerFactory = InferenceClientFactory(db, credentialVault, application)
    private val prefs = application.getSharedPreferences("nyx_settings", 0)
    private val timeoutPolicyStore = ExecutionTimeoutPolicyStore(application)
    private val restoredTimeoutPolicy = runCatching { timeoutPolicyStore.selected() }
    private val environmentProfiles = EnvironmentProfileStore(application)
    private val arsenalRepository = ArsenalRepository(application)
    private val gatewayRepository = GatewayRepository(
        root = File(application.filesDir, "gateways"),
        tokenProvider = { reference -> runCatching { credentialVault.read(reference) }.getOrNull() },
    )
    private val terminalBridge = com.nyx.ai.terminal.TerminalBridge(application)
    private val ollamaManager = OllamaServerManager(
        context = application,
        bridge = terminalBridge,
    )
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
            executionTimeoutPolicy = restoredTimeoutPolicy.getOrDefault(ExecutionTimeoutPolicy.forProfile()),
            error = if (restoredTimeoutPolicy.isFailure) "Saved execution timeout profile is invalid; Standard limits are active. Select a valid profile in Settings." else "",
        )
    )
    val state: StateFlow<NyxState> = _state.asStateFlow()
    private val terminalRepository = TerminalRepository(application, db, terminalBridge)
    private val workspaceRegistry = WorkspaceRegistry(application)
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
        com.nyx.ai.autonomy.WorkerActivityBus.initialize(app)
        PerformanceController.initialize(app)
        viewModelScope.launch {
            PerformanceController.state.collect { performance -> _state.update { it.copy(performance = performance) } }
        }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                val native = NativeRuntime.status.value
                PerformanceController.refresh(native.modelPath, native.modelPath?.let { path -> runCatching { File(path).length() }.getOrNull() })
                releaseIdleNativeModelIfNeeded()
            }
        }
        viewModelScope.launch {
            ollamaManager.state.collect { server -> _state.update { it.copy(ollamaServer = server) } }
        }
        _state.update {
            it.copy(
                autonomyMode = AutonomyRuntime.mode(),
                autonomyStopped = AutonomyRuntime.policy().stopped,
                managedWorkspaces = workspaceRegistry.list(),
            )
        }
        viewModelScope.launch { restoreSavedWorkspace() }
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
        refreshArsenal()
        refreshGateways()
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
            refreshModelsNow()
        }
    }

    /** Shared suspend refresh used by the UI and by automatic objective reconnection. */
    private suspend fun refreshModelsNow(): Boolean {
        val endpoint = _state.value.endpoint
        val hasCredential = db.modelEndpointDao().getById(endpointRecordId(endpoint))?.credentialReference != null
        _state.update { it.copy(credentialConfigured = hasCredential) }
        if (!endpointAllowed(endpoint)) {
            _state.update { it.copy(connected = false, models = emptyList(), error = "External endpoints require explicit consent in Settings.") }
            return false
        }
        _state.update { it.copy(error = "") }
        return try {
            val models = inferenceProvider(endpoint).listModels()
            _state.update { old ->
                val selected = old.selectedModel.takeIf { name -> models.any { it.name == name } } ?: models.firstOrNull()?.name.orEmpty()
                prefs.edit().putString("selected_model", selected).apply()
                old.copy(connected = true, models = models, selectedModel = selected, credentialConfigured = hasCredential, error = if (models.isEmpty()) "Inference server is reachable, but no models are installed." else "")
            }
            persistOllamaEndpoint()
            true
        } catch (e: Exception) {
            _state.update { it.copy(connected = false, models = emptyList(), error = e.message ?: "Could not connect to the local inference server.") }
            false
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

    fun diagnoseSelectedModel() {
        val s = _state.value
        if (s.diagnosticRunning || s.isGenerating || s.agentRunning || s.workflowRunning ||
            s.objectiveRunning || ExecutionMonitor.state.value.running || s.performance.calibrationRunning ||
            s.nativeStatus.phase == NativePhase.LOADING || s.selectedModel.isBlank()) {
            _state.update { it.copy(error = "Select a model and stop active work before running the diagnostic.") }
            return
        }
        _state.update { it.copy(diagnosticRunning = true, diagnosticSummary = "Checking selected model; queue/load timing is unknown.") }
        viewModelScope.launch {
            try {
                val result = com.nyx.ai.network.ModelDiagnostic.run(inferenceProvider(s.endpoint), s.selectedModel, options())
                _state.update { it.copy(diagnosticSummary = result.summary) }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _state.update { it.copy(diagnosticSummary = "Diagnostic could not start. Check provider, model and connection permissions.") }
            } finally {
                _state.update { it.copy(diagnosticRunning = false) }
            }
        }
    }

    fun selectModel(model: String) {
        if (_state.value.diagnosticRunning) { _state.update { it.copy(error = "Wait for the model diagnostic to finish.") }; return }
        if (_state.value.isGenerating || _state.value.agentRunning || _state.value.workflowRunning || ExecutionMonitor.state.value.running) { _state.update { it.copy(error = "Stop active work before changing the model.") }; return }
        prefs.edit().putString("selected_model", model).apply()
        _state.update { it.copy(selectedModel = model) }
        persistOllamaEndpoint()
    }

    fun setTemperature(value: Float) { prefs.edit().putFloat("temperature", value).apply(); _state.update { it.copy(temperature = value) } }
    fun setContextLength(value: Int) { prefs.edit().putInt("context_length", value).apply(); _state.update { it.copy(contextLength = value) } }
    fun setCpuThreads(value: Int) {
        prefs.edit().putInt("cpu_threads", value).apply()
        _state.update { it.copy(cpuThreads = value) }
        // A manual thread choice is authoritative; adaptive mode can be re-enabled on Performance.
        if (PerformanceController.adaptiveThreads()) PerformanceController.setAdaptiveThreads(false)
    }

    fun setOperatingMode(mode: OperatingMode) {
        PerformanceController.setMode(mode)
        refreshPerformance()
    }

    fun setAdaptiveThreads(enabled: Boolean) {
        PerformanceController.setAdaptiveThreads(enabled)
    }

    fun refreshPerformance() {
        val native = NativeRuntime.status.value
        PerformanceController.refresh(native.modelPath, native.modelPath?.let { path -> runCatching { File(path).length() }.getOrNull() })
    }

    /** Re-load the resident model with the current tuning. Only valid at an idle, non-objective boundary. */
    fun applyPerformanceToNative() {
        val current = _state.value
        if (current.diagnosticRunning || current.isGenerating || current.agentRunning || current.workflowRunning || current.objectiveRunning ||
            ExecutionMonitor.state.value.running) {
            _state.update { it.copy(error = "Stop active inference or objectives before applying a new native configuration.") }
            return
        }
        val path = current.nativeStatus.modelPath ?: run {
            _state.update { it.copy(error = "Load a native GGUF model before applying performance settings.") }
            return
        }
        viewModelScope.launch {
            try {
                val options = nativeOptions()
                NativeRuntime.unload()
                NativeRuntime.load(path, options)
                _state.update {
                    it.copy(performance = it.performance.copy(message = "Native session reloaded with ${options.cpuThreads} threads at context ${options.contextLength}."))
                }
                refreshPerformance()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Could not apply the native performance configuration.") }
            }
        }
    }

    fun runCalibration() {
        if (_state.value.diagnosticRunning) { _state.update { it.copy(error = "Wait for the model diagnostic to finish.") }; return }
        val current = _state.value
        if (current.performance.calibrationRunning) return
        if (current.isGenerating || current.agentRunning || current.workflowRunning ||
            current.objectiveRunning || ExecutionMonitor.state.value.running) {
            _state.update { it.copy(error = "Stop active inference, agents, workflows, or objectives before calibrating.") }
            return
        }
        val capabilities = current.performance.capabilities
        if (capabilities != null) {
            val gate = CalibrationPlanner.gate(
                capabilities = capabilities,
                modelLoaded = current.nativeStatus.loaded,
                objectiveRunning = current.objectiveRunning || ExecutionMonitor.state.value.running,
                activeWork = current.isGenerating || current.agentRunning || current.workflowRunning,
            )
            if (!gate.allowed) {
                _state.update { it.copy(error = gate.reason ?: "Calibration is not available right now.") }
                return
            }
        }
        viewModelScope.launch {
            PerformanceController.setCalibrationRunning(true, "Calibration started…")
            try {
                val result = CalibrationRunner(app).run { progress ->
                    PerformanceController.setCalibrationRunning(true, progress)
                }
                PerformanceController.saveCalibration(result)
                refreshPerformance()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Calibration failed.") }
                PerformanceController.setCalibrationRunning(false, e.message ?: "Calibration failed.")
            } finally {
                if (PerformanceController.state.value.calibrationRunning) {
                    PerformanceController.setCalibrationRunning(false, PerformanceController.state.value.message)
                }
            }
        }
    }

    private fun effectiveNativeThreads(): Int {
        val current = _state.value
        return if (current.performance.adaptiveThreads) {
            current.performance.tuning.nativeThreads.coerceIn(1, 32)
        } else {
            current.cpuThreads.coerceIn(1, 32)
        }
    }

    private fun nativeOptions() = InferenceOptions(
        _state.value.temperature.toDouble(),
        _state.value.contextLength,
        effectiveNativeThreads(),
    )

    /**
     * If a session is already resident, reuse its exact thread count so a chat turn cannot
     * silently reload the model. Adaptive tuning otherwise applies before the next load.
     */
    private fun options(): InferenceOptions {
        val current = _state.value
        val threads = if (current.nativeStatus.loaded && current.nativeStatus.modelPath == current.selectedModel) {
            NativeRuntime.currentOptions?.cpuThreads ?: current.cpuThreads
        } else {
            effectiveNativeThreads()
        }
        return InferenceOptions(current.temperature.toDouble(), current.contextLength, threads)
    }

    /** Selection applies to new runs; already snapshotted objectives and live runs are unchanged. */
    fun setExecutionTimeoutProfile(profile: ExecutionTimeoutProfile, customMinutes: Int? = null, explicitLongRunApproval: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val selected = timeoutPolicyStore.select(profile, customMinutes, explicitLongRunApproval)
                _state.update { it.copy(executionTimeoutPolicy = selected, error = "") }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message?.take(220) ?: "Could not save the execution timeout profile.") }
            }
        }
    }

    private suspend fun releaseIdleNativeModelIfNeeded() {
        val current = _state.value
        val minutes = current.performance.tuning.idleUnloadMinutes
        if (minutes <= 0 || !current.nativeStatus.loaded || current.nativeStatus.phase != NativePhase.READY ||
            current.isGenerating || current.agentRunning || current.workflowRunning || current.objectiveRunning ||
            ExecutionMonitor.state.value.running) {
            return
        }
        val lastUsed = current.nativeStatus.lastUsedAtEpochMs ?: return
        if (System.currentTimeMillis() - lastUsed < minutes * 60_000L) return
        runCatching { NativeRuntime.unload() }
        _state.update {
            it.copy(performance = it.performance.copy(message = "Native model released after $minutes minutes idle in ${it.performance.level.label} mode."))
        }
    }

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
            .put(ExecutionTimeoutPolicyStore.SNAPSHOT_KEY, timeoutPolicyStore.snapshot(s.executionTimeoutPolicy))
        db.objectiveDao().appendEvent(ObjectiveEventEntity(UUID.randomUUID().toString(), id, null, "execution_config", snapshot.toString(), System.currentTimeMillis()))
    }

    fun refreshNativeModels() {
        viewModelScope.launch(Dispatchers.IO) { _state.update { it.copy(nativeModels = nativeRepository.models()) } }
    }
    fun useNativeProvider() {
        if (_state.value.diagnosticRunning) { _state.update { it.copy(error = "Wait for the model diagnostic to finish.") }; return }
        if (_state.value.isGenerating || _state.value.agentRunning || _state.value.workflowRunning || ExecutionMonitor.state.value.running) return
        prefs.edit().putString("endpoint", "native://local").putString("selected_model", "").apply()
        _state.update { it.copy(endpoint = "native://local", selectedModel = "", credentialConfigured = false) }
        refreshNativeModels(); refreshModels()
    }
    fun loadNativeModel(model: NativeModel) {
        if (_state.value.diagnosticRunning) { _state.update { it.copy(error = "Wait for the model diagnostic to finish.") }; return }
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
        if (_state.value.diagnosticRunning) { _state.update { it.copy(error = "Wait for the model diagnostic to finish.") }; return }
        val prompt = text.trim()
        if (prompt.isBlank() || _state.value.isGenerating) return
        if (!reserveInference()) {
            _state.update { it.copy(error = "Another inference task is already running. Stop it before starting chat generation.") }
            return
        }
        generationJob = viewModelScope.launch {
            var s = _state.value
            if (!s.connected && !s.nativeMode && OllamaProcess.isLoopbackEndpoint(s.endpoint)) {
                _state.update { it.copy(error = "Starting the local Ollama runtime…") }
                val started = runCatching { startManagedOllamaForInference() }.getOrElse { error ->
                    _state.update { it.copy(error = "Local Ollama could not start: ${error.message?.take(220) ?: "unknown error"}") }
                    return@launch
                }
                if (!started.isRunning || !refreshModelsNow()) {
                    _state.update {
                        it.copy(error = started.message.ifBlank { "Ollama started, but Chat could not reconnect to its models." }.take(260))
                    }
                    return@launch
                }
                s = _state.value
            }
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
            val streamPublishInterval = _state.value.performance.tuning.streamPublishIntervalMs
            try {
                val result = inferenceProvider(s.endpoint).chat(s.selectedModel, past, tools = null, options = options()) { token ->
                    streamed.append(token)
                    val now = System.nanoTime() / 1_000_000L
                    if (now - lastPublishAt >= streamPublishInterval) {
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

    private suspend fun startManagedOllamaForInference(): OllamaServerState {
        val snapshot = _state.value
        if (snapshot.terminalEnvironment != TerminalEnvironment.DEBIAN_PROOT) {
            return ollamaManager.start()
        }
        val verified = snapshot.terminalProfile?.verifiedDistros.orEmpty()
        if (snapshot.guestAlias !in verified) {
            return snapshot.ollamaServer.copy(
                status = OllamaServerStatus.ERROR,
                message = "Verify the ${snapshot.guestAlias} guest before starting a managed server.",
            )
        }
        try {
            OllamaHostService.start(app, verified, snapshot.guestAlias, snapshot.terminalEnvironment)
        } catch (e: Exception) {
            return snapshot.ollamaServer.copy(
                status = OllamaServerStatus.ERROR,
                message = "Android rejected the foreground Ollama service: ${e.message?.take(180) ?: e.javaClass.simpleName}",
            )
        }
        var last = snapshot.ollamaServer
        repeat(60) {
            delay(3_000L)
            last = runCatching { ollamaManager.detect() }.getOrDefault(last)
            if (last.isRunning || last.status == OllamaServerStatus.ERROR || last.status == OllamaServerStatus.UNREACHABLE) {
                return last
            }
        }
        return last
    }

    /** Creates and executes a durable objective independently of a chat turn. */
    fun createObjective(goal: String, development: Boolean = false) {
        if (_state.value.diagnosticRunning) { _state.update { it.copy(error = "Wait for the model diagnostic to finish.") }; return }
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
        workspaceProblem(s)?.let { problem -> _state.update { it.copy(error = problem) }; return }
        val agent = s.agents.firstOrNull { it.id == s.selectedAgentId }
        if (agent == null) { _state.update { it.copy(error = "Select an enabled agent first.") }; return }
        if (!s.connected && OllamaProcess.isLoopbackEndpoint(s.endpoint)) {
            _state.update { it.copy(error = "Starting the local Ollama server and reconnecting…") }
            viewModelScope.launch(Dispatchers.IO) {
                val result = runCatching { startManagedOllamaForInference() }.getOrElse { error ->
                    _state.update { it.copy(error = "Local Ollama could not start: ${error.message?.take(220) ?: "unknown error"}") }
                    return@launch
                }
                if (!result.isRunning) {
                    _state.update { it.copy(error = result.message.take(260)) }
                    return@launch
                }
                if (!refreshModelsNow()) {
                    _state.update { it.copy(error = "Ollama started, but Chat could not reconnect to its models.") }
                    return@launch
                }
                createObjective(clean, development)
            }
            return
        }
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
        if (_state.value.diagnosticRunning) { _state.update { it.copy(error = "Wait for the model diagnostic to finish.") }; return }
        val s = _state.value
        workspaceProblem(s)?.let { problem -> _state.update { it.copy(error = problem) }; return }
        val objective = s.objectives.firstOrNull { it.id == id } ?: return
        val agent = s.agents.firstOrNull { it.id == objective.agentId } ?: run { _state.update { it.copy(error = "The objective's agent is unavailable.") }; return }
        if (!s.connected && OllamaProcess.isLoopbackEndpoint(s.endpoint)) {
            _state.update { it.copy(error = "Reconnecting the local Ollama server before resume…") }
            viewModelScope.launch(Dispatchers.IO) {
                val result = runCatching { startManagedOllamaForInference() }.getOrElse { error ->
                    _state.update { it.copy(error = "Local Ollama could not start: ${error.message?.take(220) ?: "unknown error"}") }
                    return@launch
                }
                if (result.isRunning && refreshModelsNow()) {
                    resumeObjective(id, userApprovedRetry)
                } else {
                    _state.update { it.copy(error = result.message.take(260)) }
                }
            }
            return
        }
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
            terminalRepository.observeHistory(60).collect { rows -> _state.update { it.copy(terminalHistory = rows) }; refreshTerminalOperations() }
        }
    }

    fun refreshTerminalOperations() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { terminalBridge.operationHistory() }.onSuccess { rows ->
                val requested = app.getSharedPreferences("terminal_execution", android.content.Context.MODE_PRIVATE).getInt("max_contexts", 3)
                _state.update { it.copy(terminalOperations = rows.sortedByDescending { row -> row.startedAt }.take(60), requestedTerminalContexts = requested) }
            }.onFailure { _state.update { it.copy(terminalMessage = "Operation history needs review; terminal admission remains conservative.") } }
        }
    }
    fun setTerminalContexts(count: Int) {
        terminalBridge.configureCommandContexts(count)
        refreshTerminalOperations()
    }
    fun confirmTerminalOperationStopped(id: String) {
        viewModelScope.launch {
            terminalBridge.acknowledgeReviewedOperation(id, confirmedNoLongerRunning = true)
            refreshTerminalOperations()
        }
    }

    // ---- Terminal -------------------------------------------------------------------------

    fun refreshTerminalEnvironment() {
        refreshTerminalOperations()
        if (_state.value.terminalDiscovering) return
        viewModelScope.launch {
            _state.update { it.copy(terminalDiscovering = true, terminalMessage = "Probing Termux…") }
            val profile = withContext(Dispatchers.IO) { terminalRepository.discover() }
            val environment = _state.value.terminalEnvironment
            val alias = _state.value.guestAlias
            val guestVerified = profile.verifiedDistros.any { it.equals(alias, ignoreCase = true) }
            ollamaManager.configureGuests(profile.verifiedDistros, alias, environment)
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
            when (check) {
                is WorkspaceCheck.Valid -> {
                    registerManagedWorkspace(environment, alias, check.normalizedPath)
                    _state.update { it.copy(managedWorkspaces = workspaceRegistry.list()) }
                    syncWorkspaceAuthority(check.normalizedPath)
                }

                is WorkspaceCheck.Invalid -> syncWorkspaceAuthority(null)
                null -> Unit
            }
            withContext(Dispatchers.IO) { runCatching { ollamaManager.refresh() } }
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
        ollamaManager.configureGuests(
            _state.value.terminalProfile?.verifiedDistros.orEmpty(),
            alias,
            TerminalEnvironment.DEBIAN_PROOT,
        )
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
        ollamaManager.configureGuests(
            _state.value.terminalProfile?.verifiedDistros.orEmpty(),
            _state.value.guestAlias,
            environment,
        )
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

    // ---------------------------------------------------------------- Arsenal

    fun refreshArsenal() {
        viewModelScope.launch(Dispatchers.IO) {
            val skills = runCatching { arsenalRepository.list() }.getOrElse { error ->
                _state.update { it.copy(arsenalMessage = error.message?.take(240) ?: "Arsenal could not be loaded.") }
                return@launch
            }
            _state.update { it.copy(arsenalSkills = skills, arsenalMessage = "") }
        }
    }

    fun setArsenalEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val changed = arsenalRepository.setEnabled(id, enabled)
            refreshArsenal()
            if (!changed) _state.update { it.copy(arsenalMessage = "Skill not found.") }
        }
    }

    fun importArsenal(json: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = arsenalRepository.importSkill(json)
            _state.update {
                it.copy(arsenalMessage = if (result.ok) "Imported ${result.skill?.id} v${result.skill?.version}." else result.issues.joinToString("; ") { issue -> issue.message }.take(400))
            }
            refreshArsenal()
        }
    }

    fun removeArsenal(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            arsenalRepository.remove(id)
            refreshArsenal()
            _state.update { it.copy(arsenalMessage = "Skill removed: $id") }
        }
    }

    fun configureArsenal(id: String, key: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val issue: ArsenalIssue? = arsenalRepository.configure(id, key, value)
            _state.update { it.copy(arsenalMessage = issue?.message ?: "Configuration saved.") }
            refreshArsenal()
        }
    }

    // ---------------------------------------------------------------- Gateways

    fun refreshGateways() {
        viewModelScope.launch(Dispatchers.IO) {
            val servers = runCatching { gatewayRepository.list() }.getOrElse { error ->
                _state.update { it.copy(gatewayMessage = error.message?.take(240) ?: "Gateways could not be loaded.") }
                return@launch
            }
            _state.update { it.copy(gatewayServers = servers) }
        }
    }

    // ------------------------------------------------------------ Managed Ollama

    fun refreshOllamaServer() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { ollamaManager.refresh() }
                .onFailure { error -> _state.update { it.copy(ollamaServer = it.ollamaServer.copy(message = error.message?.take(240) ?: "Ollama refresh failed.")) } }
                .getOrNull()
            if (result?.isRunning == true) refreshModels()
        }
    }

    fun detectOllamaServer() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { ollamaManager.detect() }
                .onFailure { error -> _state.update { it.copy(ollamaServer = it.ollamaServer.copy(message = error.message?.take(240) ?: "Ollama detection failed.")) } }
                .getOrNull()
            if (result?.isRunning == true) refreshModels()
        }
    }

    fun startOllamaServer() {
        if (AutonomyRuntime.policy().stopped) {
            _state.update { it.copy(ollamaServer = it.ollamaServer.copy(message = "Emergency stop is active; server start is blocked.")) }
            return
        }
        val snapshot = _state.value
        if (snapshot.terminalEnvironment == TerminalEnvironment.DEBIAN_PROOT) {
            val verified = snapshot.terminalProfile?.verifiedDistros.orEmpty()
            if (snapshot.guestAlias !in verified) {
                _state.update { it.copy(ollamaServer = it.ollamaServer.copy(message = "Verify the ${snapshot.guestAlias} guest before starting its Ollama server.")) }
                return
            }
            _state.update {
                it.copy(
                    ollamaOperationRunning = true,
                    ollamaServer = it.ollamaServer.copy(message = "Starting Ollama in the verified guest through NYX's foreground service…"),
                )
            }
            OllamaHostService.start(app, verified, snapshot.guestAlias, snapshot.terminalEnvironment)
            pollManagedOllama()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { ollamaManager.start() }.getOrElse { error ->
                _state.update { it.copy(ollamaServer = it.ollamaServer.copy(message = error.message?.take(240) ?: "Ollama could not start.")) }
                return@launch
            }
            WorkerActivityBus.record(
                WorkerActivityKind.INFO,
                "Local Ollama ${if (result.isRunning) "ready" else result.status.name.lowercase()}",
                result.message.take(240),
                toolName = "ollama",
            )
            if (result.isRunning) refreshModels()
        }
    }

    fun stopOllamaServer() {
        val snapshot = _state.value
        if (snapshot.terminalEnvironment == TerminalEnvironment.DEBIAN_PROOT) {
            _state.update {
                it.copy(
                    ollamaOperationRunning = true,
                    ollamaServer = it.ollamaServer.copy(message = "Stopping the managed guest Ollama session…"),
                )
            }
            OllamaHostService.stop(app)
            pollManagedOllama()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ollamaManager.stop() }
                .onFailure { error -> _state.update { it.copy(ollamaServer = it.ollamaServer.copy(message = error.message?.take(240) ?: "Ollama could not stop.")) } }
        }
    }

    fun restartOllamaServer() {
        if (AutonomyRuntime.policy().stopped) {
            _state.update { it.copy(ollamaServer = it.ollamaServer.copy(message = "Emergency stop is active; server restart is blocked.")) }
            return
        }
        val snapshot = _state.value
        if (snapshot.terminalEnvironment == TerminalEnvironment.DEBIAN_PROOT) {
            val verified = snapshot.terminalProfile?.verifiedDistros.orEmpty()
            if (snapshot.guestAlias !in verified) {
                _state.update { it.copy(ollamaServer = it.ollamaServer.copy(message = "Verify the ${snapshot.guestAlias} guest before restarting its Ollama server.")) }
                return
            }
            _state.update {
                it.copy(
                    ollamaOperationRunning = true,
                    ollamaServer = it.ollamaServer.copy(message = "Restarting the managed guest Ollama session…"),
                )
            }
            OllamaHostService.restart(app, verified, snapshot.guestAlias, snapshot.terminalEnvironment)
            pollManagedOllama()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { ollamaManager.restart() }.getOrElse { error ->
                _state.update { it.copy(ollamaServer = it.ollamaServer.copy(message = error.message?.take(240) ?: "Ollama could not restart.")) }
                return@launch
            }
            if (result.isRunning) refreshModels()
        }
    }

    /** The service owns its own manager instance, so reconcile the visible state from persisted evidence. */
    private fun pollManagedOllama() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repeat(60) { attempt ->
                    delay(3_000L)
                    val result = runCatching { ollamaManager.detect() }.getOrNull() ?: return@repeat
                    val terminal = result.status == OllamaServerStatus.ERROR ||
                        result.status == OllamaServerStatus.UNREACHABLE ||
                        result.status == OllamaServerStatus.STOPPED ||
                        result.status == OllamaServerStatus.NOT_INSTALLED
                    if (result.isRunning) {
                        refreshModels()
                        _state.update { it.copy(ollamaOperationRunning = false) }
                        return@launch
                    }
                    // Ignore a transient STOPPED while the service is still creating the session.
                    if (terminal && attempt >= 6) {
                        _state.update { it.copy(ollamaOperationRunning = false) }
                        return@launch
                    }
                }
            } finally {
                _state.update { it.copy(ollamaOperationRunning = false) }
            }
        }
    }

    fun saveGateway(
        existingId: String?,
        name: String,
        kind: com.nyx.ai.gateway.GatewayKind,
        endpoint: String,
        token: String,
        allowedToolsCsv: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val id = existingId ?: "gateway-${UUID.randomUUID().toString().take(8)}"
                val existing = existingId?.let { gatewayRepository.get(it) }
                val reference = if (token.isNotBlank()) "gateway:$id" else existing?.credentialRef
                if (token.isNotBlank()) credentialVault.save("gateway:$id", token)
                val defaults = if (kind == com.nyx.ai.gateway.GatewayKind.GITHUB) {
                    setOf("github_repository", "github_branches", "github_commits", "github_contents", "github_tree", "github_issues", "github_pull_requests", "github_diff")
                } else emptySet()
                val allow = allowedToolsCsv.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet().ifEmpty { defaults }
                val config = GatewayServerConfig(
                    id = id,
                    name = name.trim(),
                    kind = kind,
                    endpoint = endpoint.trim().ifBlank { if (kind == com.nyx.ai.gateway.GatewayKind.GITHUB) "https://api.github.com" else "" },
                    credentialRef = reference,
                    enabled = enabled,
                    allowedTools = allow,
                )
                gatewayRepository.upsert(config)
            }.onSuccess {
                _state.update { it.copy(gatewayMessage = "Gateway saved.") }
                refreshGateways()
            }.onFailure { error ->
                _state.update { it.copy(gatewayMessage = error.message?.take(300) ?: "Gateway could not be saved.") }
            }
        }
    }

    fun setGatewayEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { gatewayRepository.setEnabled(id, enabled) }
                .onSuccess { refreshGateways() }
                .onFailure { error -> _state.update { it.copy(gatewayMessage = error.message?.take(240) ?: "Gateway update failed.") } }
        }
    }

    fun deleteGateway(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { gatewayRepository.delete(id) }
            runCatching { credentialVault.delete("gateway:$id") }
            refreshGateways()
        }
    }

    fun checkGateway(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val health = runCatching { gatewayRepository.health(id) }
                .getOrElse { GatewayHealth(com.nyx.ai.gateway.GatewayHealthState.UNREACHABLE, it.message?.take(240) ?: "Health check failed.") }
            val tools = runCatching { gatewayRepository.listTools(id) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    gatewayHealth = it.gatewayHealth + (id to health),
                    gatewayTools = it.gatewayTools + (id to tools),
                    gatewayMessage = "${health.state}: ${health.detail} · ${tools.size} tool(s)",
                )
            }
            WorkerActivityBus.record(WorkerActivityKind.GATEWAY, "Gateway checked: $id", "${health.state} · ${tools.size} tools", toolName = id)
        }
    }

    fun callGateway(id: String, tool: String, argumentsJson: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (AutonomyRuntime.policy().stopped) {
                _state.update { it.copy(gatewayMessage = "Emergency stop is active; gateway calls are blocked.") }
                return@launch
            }
            val parsed = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }
                .getOrElse {
                    _state.update { it.copy(gatewayMessage = "Arguments must be a JSON object.") }
                    return@launch
                }
            val workspace = _state.value.terminalWorkspace.trimEnd('/')
            val target = if (workspace.startsWith("/") && workspace.length > 1) "$workspace/.nyx/gateway/$id/$tool" else "/nyx/gateway/$id/$tool"
            val writeTool = tool.contains("create") || tool.contains("comment") || tool.contains("publish")
            val decision = AutonomyRuntime.authorize(
                app,
                ActionRequest(
                    kind = if (writeTool) ActionKind.GIT_COMMIT else ActionKind.NETWORK_READ,
                    target = target,
                    environmentId = policyEnvironmentId(),
                    insideAuthorizedWorkspace = true,
                ),
            )
            if (!decision.allowed && !decision.requiresApproval) {
                _state.update { it.copy(gatewayMessage = "Gateway call denied: ${decision.reason}") }
                return@launch
            }
            val result = gatewayRepository.callTool(id, tool, parsed.toString(), runId = "ui-${System.currentTimeMillis()}")
            _state.update {
                it.copy(
                    gatewayOutput = result.content.take(12_000),
                    gatewayMessage = if (result.success) "Tool completed in ${result.latencyMs}ms." else "Tool failed: ${result.content.take(240)}",
                )
            }
            WorkerActivityBus.record(
                WorkerActivityKind.GATEWAY,
                "Gateway tool: $tool",
                if (result.success) "Completed in ${result.latencyMs}ms" else result.content,
                toolName = tool,
            )
        }
    }

    /**
     * Verifies the saved workspace on every process start. A verified folder is reused as-is; an
     * unavailable folder is reported and its routine grants are removed, never silently replaced
     * with a new numbered workspace.
     */
    private suspend fun restoreSavedWorkspace() {
        val snapshot = _state.value
        val saved = snapshot.terminalWorkspace.trim()
        if (saved.isBlank()) {
            syncWorkspaceAuthority(null)
            _state.update { it.copy(managedWorkspaces = workspaceRegistry.list()) }
            return
        }
        _state.update { it.copy(terminalMessage = "Verifying the saved workspace on startup…") }
        val check = withContext(Dispatchers.IO) {
            runCatching {
                terminalRepository.validateWorkspace(snapshot.terminalEnvironment, saved, snapshot.guestAlias)
            }.getOrElse {
                WorkspaceCheck.Invalid(
                    "probe_failed",
                    it.message?.take(200) ?: "Workspace probe failed",
                    "Run Detect environment and select the workspace again.",
                )
            }
        }
        when (val decision = WorkspaceLifecycle.startupDecision(saved, check)) {
            is WorkspaceStartupDecision.Reuse -> {
                prefs.edit().putString(ObjectiveExecutionService.KEY_TERMINAL_WORKSPACE, decision.path).apply()
                registerManagedWorkspace(snapshot.terminalEnvironment, snapshot.guestAlias, decision.path)
                _state.update {
                    it.copy(
                        terminalWorkspace = decision.path,
                        workspaceCheck = check,
                        managedWorkspaces = workspaceRegistry.list(),
                        terminalMessage = "Reused verified workspace: ${decision.path}",
                        workspaceLifecycleMessage = "",
                    )
                }
                syncWorkspaceAuthority(decision.path)
            }

            WorkspaceStartupDecision.Unavailable -> {
                _state.update {
                    it.copy(
                        workspaceCheck = check as? WorkspaceCheck.Invalid,
                        terminalMessage = "Saved workspace was not reused: ${(check as? WorkspaceCheck.Invalid)?.message ?: "probe failed"}",
                        workspaceLifecycleMessage = "Saved workspace is unavailable; no replacement was created.",
                    )
                }
                syncWorkspaceAuthority(null)
            }

            WorkspaceStartupDecision.None -> syncWorkspaceAuthority(null)
        }
    }

    /** Replaces routine grants so the policy scope always follows the verified workspace. */
    private fun syncWorkspaceAuthority(verifiedPath: String?) {
        val check = _state.value.workspaceCheck
        val path = verifiedPath
            ?.takeIf { check is WorkspaceCheck.Valid && check.normalizedPath == it }
            ?.takeIf { _state.value.autonomyMode != AutonomyMode.MANUAL }
        AutonomyRuntime.grantRoutineScope(app, policyEnvironmentId(), path)
    }

    private fun registerManagedWorkspace(environment: TerminalEnvironment, alias: String, path: String) {
        val base = WorkspaceProvisioner.defaultBase(environment, _state.value.terminalProfile?.home)
            ?: inferManagedBase(path)
            ?: return
        if (!WorkspaceLifecycle.isManaged(base, path)) return
        workspaceRegistry.upsert(
            WorkspaceRecord(
                environment = environment,
                guestAlias = alias,
                base = base,
                path = path,
                verifiedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun inferManagedBase(path: String): String? {
        val marker = "/${WorkspaceProvisioner.DEFAULT_HOST_FOLDER}/"
        val index = path.indexOf(marker)
        return if (index > 0) path.substring(0, index + marker.length - 1) else null
    }

    fun refreshManagedWorkspaces() {
        _state.update { it.copy(managedWorkspaces = workspaceRegistry.list()) }
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
                registerManagedWorkspace(s.terminalEnvironment, s.guestAlias, check.normalizedPath)
                _state.update {
                    it.copy(
                        terminalWorkspace = check.normalizedPath,
                        workspaceCheck = check,
                        managedWorkspaces = workspaceRegistry.list(),
                        terminalMessage = "Workspace verified: ${check.normalizedPath}",
                        workspaceLifecycleMessage = "",
                        error = "",
                    )
                }
                syncWorkspaceAuthority(check.normalizedPath)
            } else {
                val invalid = check as WorkspaceCheck.Invalid
                _state.update { it.copy(workspaceCheck = invalid, error = invalid.message) }
                syncWorkspaceAuthority(null)
            }
        }
    }

    /** Switches the one shared policy mode and creates the routine grants implied by that choice. */
    fun setAutonomyMode(mode: AutonomyMode) {
        AutonomyRuntime.setMode(app, mode)
        val check = _state.value.workspaceCheck
        val verified = _state.value.terminalWorkspace.takeIf {
            mode != AutonomyMode.MANUAL && check is WorkspaceCheck.Valid && check.normalizedPath == it
        }
        AutonomyRuntime.grantRoutineScope(app, policyEnvironmentId(), verified)
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

    /**
     * Builds a dependency plan from the last capability probe. On the Termux host the plan is
     * immediate. Inside Debian, the JDK package is resolved from the guest's own APT candidates
     * first; a missing candidate becomes a diagnostic instead of a blind `openjdk-17` install.
     */
    fun prepareEnvironment(projectKind: ProjectKind = ProjectKind.KOTLIN_JVM) {
        val snapshot = _state.value
        if (snapshot.autonomyStopped) {
            _state.update { it.copy(policyMessage = "Emergency stop is active; environment work is blocked.") }
            return
        }
        val capabilities = snapshot.environmentCapabilities
        if (capabilities == null) {
            _state.update { it.copy(policyMessage = "Run Detect environment before preparing the toolchain.") }
            return
        }
        val host = snapshot.terminalEnvironment == TerminalEnvironment.TERMUX_HOST
        val tools = capabilities.tools.mapValues { (_, tool) ->
            ToolState(
                present = tool.state != CapabilityState.MISSING && tool.state != CapabilityState.NOT_CHECKED,
                usable = tool.state == CapabilityState.VERIFIED &&
                    (host || tool.origin == ExecutableOrigin.GUEST_NATIVE),
                origin = tool.origin,
                version = tool.version,
            )
        }
        val environmentId = policyEnvironmentId()
        if (host) {
            applyDependencyPlan(
                projectKind,
                DependencyPlanner.plan(projectKind, environmentId, tools),
            )
            return
        }

        val guestAlias = snapshot.guestAlias
        viewModelScope.launch {
            _state.update {
                it.copy(policyMessage = "Resolving JDK package candidates inside Debian ($guestAlias)…")
            }
            val resolution = withContext(Dispatchers.IO) {
                terminalRepository.resolveGuestJdk(guestAlias)
            }
            // The user may have switched environments while the read-only probe was running.
            if (_state.value.terminalEnvironment != TerminalEnvironment.DEBIAN_PROOT ||
                _state.value.guestAlias != guestAlias
            ) {
                return@launch
            }
            applyDependencyPlan(
                projectKind,
                DependencyPlanner.plan(
                    projectKind,
                    environmentId,
                    tools,
                    guestJdkPackage = resolution.packageName,
                    jdkDiagnostic = resolution.diagnostic,
                ),
            )
        }
    }

    private fun applyDependencyPlan(projectKind: ProjectKind, plan: DependencyPlan) {
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
                        val workspace = _state.value.terminalWorkspace.trimEnd('/')
                        val scopedTarget = if (workspace.startsWith("/") && workspace.length > 1) {
                            "$workspace/.nyx/package/${candidate.packageName}"
                        } else candidate.packageName
                        val decision = AutonomyRuntime.authorize(
                            app,
                            ActionRequest(
                                kind = ActionKind.INSTALL_PACKAGE,
                                target = scopedTarget,
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
    fun createWorkspace(projectName: String) = createWorkspaceInternal(projectName, approvedByUser = false)

    /** Runs the exact pending project creation after a Manual or Smart approval prompt. */
    fun approveWorkspaceCreation() {
        val pending = _state.value.pendingWorkspaceCreation ?: return
        createWorkspaceInternal(pending, approvedByUser = true)
    }

    fun dismissWorkspaceCreation() {
        _state.update {
            it.copy(
                pendingWorkspaceCreation = null,
                policyMessage = "Workspace creation cancelled. Nothing was created.",
            )
        }
    }

    private fun createWorkspaceInternal(projectName: String, approvedByUser: Boolean) {
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
                kind = ActionKind.CREATE_WORKSPACE,
                target = base,
                environmentId = policyEnvironmentId(),
                insideAuthorizedWorkspace = true,
            ),
        )
        if (!decision.allowed && decision.requiresApproval && !approvedByUser) {
            _state.update {
                it.copy(
                    pendingWorkspaceCreation = projectName.take(40),
                    policyMessage = "Approval required: create “${projectName.take(40)}” under $base. " +
                        "Only the new project folder will be created; existing folders are never reused.",
                )
            }
            return
        }
        if (!decision.allowed && !(decision.requiresApproval && approvedByUser)) {
            _state.update {
                it.copy(
                    pendingWorkspaceCreation = null,
                    policyMessage = workspacePolicyMessage(decision.reason),
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    workspaceProvisioning = true,
                    pendingWorkspaceCreation = null,
                    policyMessage = "Creating $target…",
                )
            }
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
                    registerManagedWorkspace(s.terminalEnvironment, s.guestAlias, result.path)
                    _state.update {
                        it.copy(
                            workspaceProvisioning = false,
                            pendingWorkspaceCreation = null,
                            terminalWorkspace = result.path,
                            workspaceCheck = WorkspaceCheck.Valid(result.path),
                            managedWorkspaces = workspaceRegistry.list(),
                            policyMessage = "Workspace ready: ${result.path}",
                            error = "",
                        )
                    }
                    syncWorkspaceAuthority(result.path)
                }

                is WorkspaceProvisionResult.Failed -> _state.update {
                    it.copy(
                        workspaceProvisioning = false,
                        pendingWorkspaceCreation = null,
                        policyMessage = result.message,
                        error = result.message,
                    )
                }
            }
        }
    }

    fun requestDeleteWorkspace(record: WorkspaceRecord) {
        if (!WorkspaceLifecycle.isManaged(record.base, record.path)) {
            _state.update { it.copy(workspaceLifecycleMessage = "Refused: this path is not a direct child of a NYX-managed workspace root.") }
            return
        }
        if (hasActiveObjective()) {
            _state.update {
                it.copy(
                    pendingWorkspaceDeletion = emptyList(),
                    workspaceLifecycleMessage = "Refused: pause or cancel the active objective before deleting a workspace.",
                )
            }
            return
        }
        _state.update { it.copy(pendingWorkspaceDeletion = listOf(record), workspaceLifecycleMessage = "") }
    }

    fun requestDeleteAllWorkspaces() {
        if (hasActiveObjective()) {
            _state.update {
                it.copy(
                    pendingWorkspaceDeletion = emptyList(),
                    workspaceLifecycleMessage = "Refused: pause or cancel the active objective before deleting workspaces.",
                )
            }
            return
        }
        val candidates = _state.value.managedWorkspaces.filter { WorkspaceLifecycle.isManaged(it.base, it.path) }
        _state.update {
            it.copy(
                pendingWorkspaceDeletion = candidates,
                workspaceLifecycleMessage = if (candidates.isEmpty()) "No NYX-managed workspaces to delete." else "",
            )
        }
    }

    fun cancelWorkspaceDeletion() {
        _state.update { it.copy(pendingWorkspaceDeletion = emptyList(), workspaceLifecycleMessage = "Deletion cancelled. Nothing was removed.") }
    }

    fun confirmWorkspaceDeletion() {
        val pending = _state.value.pendingWorkspaceDeletion
        if (pending.isEmpty() || _state.value.workspaceLifecycleBusy) return
        if (hasActiveObjective()) {
            _state.update {
                it.copy(
                    pendingWorkspaceDeletion = emptyList(),
                    workspaceLifecycleMessage = "Refused: an objective became active. Nothing was deleted.",
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    workspaceLifecycleBusy = true,
                    pendingWorkspaceDeletion = emptyList(),
                    workspaceLifecycleMessage = "Deleting ${pending.size} verified workspace(s)…",
                )
            }
            val failures = mutableListOf<String>()
            var deleted = 0
            for (record in pending) {
                if (!WorkspaceLifecycle.isManaged(record.base, record.path)) {
                    failures += "${record.path}: outside the managed root"
                    continue
                }
                val decision = AutonomyRuntime.authorize(
                    app,
                    ActionRequest(
                        kind = ActionKind.DELETE_WORKSPACE,
                        target = record.path,
                        environmentId = if (record.environment == TerminalEnvironment.TERMUX_HOST) "termux_host" else "proot:${record.guestAlias}",
                        insideAuthorizedWorkspace = true,
                    ),
                )
                if (decision.reason == "emergency_stop") {
                    failures += "${record.path}: emergency stop is active"
                    continue
                }
                // The exact path list was confirmed by the user in the UI. The policy decision is
                // recorded for audit, but a missing routine grant for a non-selected managed folder
                // must not make the explicit "delete all test workspaces" action unusable.
                val result = withContext(Dispatchers.IO) {
                    terminalRepository.execute(
                        command = WorkspaceLifecycle.deleteScript(record.base, record.path),
                        environment = record.environment,
                        workingDirectory = null,
                        timeoutSeconds = 60,
                        approvedByUser = true,
                        guestAlias = record.guestAlias,
                        useTimeoutBinary = false,
                    )
                }
                when (val parsed = WorkspaceLifecycle.parseDelete(result.stdout, result.stderr, result.exitCode, record.path)) {
                    is WorkspaceDeleteResult.Deleted -> {
                        deleted++
                        workspaceRegistry.remove(record.path)
                        if (_state.value.terminalWorkspace == record.path) {
                            prefs.edit().remove(ObjectiveExecutionService.KEY_TERMINAL_WORKSPACE).apply()
                            _state.update { it.copy(terminalWorkspace = "", workspaceCheck = null) }
                            syncWorkspaceAuthority(null)
                        }
                    }

                    is WorkspaceDeleteResult.Refused -> failures += "${record.path}: ${parsed.message}"
                }
            }
            _state.update {
                it.copy(
                    workspaceLifecycleBusy = false,
                    managedWorkspaces = workspaceRegistry.list(),
                    workspaceLifecycleMessage = when {
                        failures.isEmpty() -> "Deleted $deleted workspace(s). The parent root and unrelated files were not touched."
                        deleted == 0 -> "Deletion refused: ${failures.first()}"
                        else -> "Deleted $deleted workspace(s); ${failures.size} refused: ${failures.first()}"
                    },
                )
            }
        }
    }

    private fun hasActiveObjective(): Boolean =
        WorkspaceLifecycle.hasActiveObjective(
            running = _state.value.objectiveRunning,
            statuses = _state.value.objectives.map { it.status },
        )

    private fun workspaceProblem(s: NyxState): String? {
        if (!s.terminalToolsEnabled) return null
        val check = s.workspaceCheck
        if (check is WorkspaceCheck.Valid && check.normalizedPath == s.terminalWorkspace) return null
        return "Verify the selected workspace before starting an objective with terminal tools. NYX will not run mutating commands against an unverified or mismatched scope."
    }

    /** Turns stable policy reasons into a user action instead of exposing internal codes. */
    private fun workspacePolicyMessage(reason: String): String = when (reason) {
        "manual_requires_approval", "manual_granted_requires_approval" ->
            "Manual mode needs your approval before NYX creates a project folder."
        "smart_high_requires_approval" ->
            "Smart mode needs approval for this workspace operation."
        "full_elevated_grant_required" ->
            "Full autonomy has no grant for this workspace root. Select the verified workspace again, then retry."
        "emergency_stop" ->
            "Emergency Stop is active. Resume autonomy before creating a workspace."
        "full_critical_denied", "smart_critical_denied", "manual_critical_denied" ->
            "This workspace operation is blocked by the current safety policy."
        else -> "Workspace creation was not authorized. Check the selected environment and workspace root."
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
        if (_state.value.diagnosticRunning) { _state.update { it.copy(error = "Wait for the model diagnostic to finish.") }; return }
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
                val outcome = AgentRunner(app, inferenceProvider(s.endpoint)).run(spec, s.agentTask, options = options().copy(executionTimeouts = s.executionTimeoutPolicy), onEvent = ::appendAgentEvent) { token ->
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
        if (_state.value.diagnosticRunning) { _state.update { it.copy(error = "Wait for the model diagnostic to finish.") }; return }
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
                    val outcome = runner.run(toSpec(agent, s.selectedModel), s.workflowTask, context, options().copy(executionTimeouts = s.executionTimeoutPolicy), ::appendAgentEvent) { token ->
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
    override fun onCleared() {
        runCatching { ollamaManager.close() }
        runCatching { gatewayRepository.close() }
        super.onCleared()
    }
}
