package com.nyx.ai.terminal

import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Safe semantic argv plus digest of dispatched script. Source-write payloads use digest only. */
data class TerminalCommandIdentity(
    val operationKind: String,
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val policyDisposition: String,
    val scriptSha256: String,
) {
    init {
        require(operationKind.matches(Regex("[a-z][a-z0-9_]{0,63}")))
        require(executable.length in 1..1024 && arguments.size <= 64 && arguments.sumOf { it.length } <= 8000)
        require((listOf(executable, workingDirectory, policyDisposition) + arguments).all { '\u0000' !in it && it.length <= 8192 })
        require(workingDirectory.startsWith('/') && policyDisposition.length in 1..64)
        require(scriptSha256.matches(Regex("[0-9a-f]{64}")))
    }
    fun writeTo(properties: Properties) {
        properties.setProperty("command.kind", operationKind)
        properties.setProperty("command.executable", executable)
        properties.setProperty("command.cwd", workingDirectory)
        properties.setProperty("command.policy", policyDisposition)
        properties.setProperty("command.sha256", scriptSha256)
        properties.setProperty("command.argc", arguments.size.toString())
        arguments.forEachIndexed { index, argument -> properties.setProperty("command.argv.$index", argument) }
    }
    companion object {
        fun readFrom(properties: Properties): TerminalCommandIdentity? {
            if (!properties.containsKey("command.kind")) {
                require(properties.stringPropertyNames().none { it.startsWith("command.") }) { "Partial terminal command identity" }
                return null
            }
            val count = properties.getProperty("command.argc").toInt()
            require(count in 0..64)
            fun field(name: String) = requireNotNull(properties.getProperty("command.$name"))
            return TerminalCommandIdentity(field("kind"), field("executable"), (0 until count).map { field("argv.$it") }, field("cwd"), field("policy"), field("sha256"))
        }
        fun digest(command: String): String = MessageDigest.getInstance("SHA-256").digest(command.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

class TerminalCommandDispatchScope(val identity: TerminalCommandIdentity, val operationId: String = UUID.randomUUID().toString()) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TerminalCommandDispatchScope>
}

/** Separate mode from intentional manual/login-shell execution; no model strings enter here. */
suspend fun TerminalBridge.runControlledShell(command: String, environment: TerminalEnvironment, workingDirectory: String,
                                             timeoutSeconds: Long, useTimeoutBinary: Boolean,
                                             guestAlias: String = TerminalCommandRequest.DEFAULT_DISTRO_ALIAS): TerminalCommandResult {
    val request = ControlledShell.request(command, environment, workingDirectory, timeoutSeconds, useTimeoutBinary, guestAlias)
    val scope = currentCoroutineContext()[TerminalCommandDispatchScope]
        ?: return TerminalCommandResult(null, TerminalResultKind.DENIED, message = "Controlled command audit identity required")
    val identity = scope.identity
    require(identity.workingDirectory == workingDirectory)
    val dispatched = identity.copy(scriptSha256 = TerminalCommandIdentity.digest(request.arguments.last()))
    return withContext(TerminalCommandDispatchScope(dispatched, scope.operationId)) {
        execute(request, TerminalOperationContext(operationId = scope.operationId, namespace = if (environment == TerminalEnvironment.TERMUX_HOST) "termux_host" else "proot:$guestAlias",
            workspace = workingDirectory, readOnly = identity.policyDisposition == "structured_readonly"))
    }
}
