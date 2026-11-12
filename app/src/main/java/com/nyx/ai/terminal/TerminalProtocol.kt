package com.nyx.ai.terminal

/** Where the command is executed by Termux. */
enum class TerminalEnvironment {
    TERMUX_HOST,
    DEBIAN_PROOT,
}

data class TerminalCommandRequest(
    val environment: TerminalEnvironment,
    val commandPath: String,
    val arguments: List<String> = emptyList(),
    val stdin: String? = null,
    val workingDirectory: String? = null,
    /** PRoot distribution alias for [TerminalEnvironment.DEBIAN_PROOT]; never interpolated unvalidated. */
    val distroAlias: String = DEFAULT_DISTRO_ALIAS,
    /** Must be explicitly set true for this individual command. Defaults to deny. */
    val authorized: Boolean = false,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        const val DEFAULT_DISTRO_ALIAS = "debian"
        const val MAX_TIMEOUT_MILLIS = 10 * 60 * 1000L
        const val MAX_COMMAND_PATH_CHARS = 4_096
        const val MAX_ARGUMENT_COUNT = 256
        const val MAX_ARGUMENT_CHARS = 8_192
        const val MAX_ARGUMENTS_TOTAL_CHARS = 64 * 1024
        const val MAX_STDIN_CHARS = 64 * 1024
        const val MAX_WORKDIR_CHARS = 4_096
    }
}

data class ValidationIssue(val field: String, val message: String)

object TerminalRequestValidator {
    fun validate(request: TerminalCommandRequest): List<ValidationIssue> = buildList {
        fun checkText(field: String, value: String, max: Int, allowEmpty: Boolean = true) {
            if (!allowEmpty && value.isBlank()) add(ValidationIssue(field, "must not be blank"))
            if (value.length > max) add(ValidationIssue(field, "exceeds $max characters"))
            if ('\u0000' in value) add(ValidationIssue(field, "must not contain NUL"))
        }

        checkText("commandPath", request.commandPath, TerminalCommandRequest.MAX_COMMAND_PATH_CHARS, allowEmpty = false)
        if (request.commandPath.isNotEmpty() && !isSupportedCommandPath(request.commandPath)) {
            add(ValidationIssue("commandPath", "must be an absolute path or use ~/ or \$PREFIX/"))
        }
        if (request.arguments.size > TerminalCommandRequest.MAX_ARGUMENT_COUNT) {
            add(ValidationIssue("arguments", "exceeds ${TerminalCommandRequest.MAX_ARGUMENT_COUNT} arguments"))
        }
        var aggregateChars = 0L
        request.arguments.forEachIndexed { index, arg ->
            checkText("arguments[$index]", arg, TerminalCommandRequest.MAX_ARGUMENT_CHARS)
            aggregateChars += arg.length.toLong()
        }
        if (aggregateChars > TerminalCommandRequest.MAX_ARGUMENTS_TOTAL_CHARS) {
            add(ValidationIssue("arguments", "combined arguments exceed ${TerminalCommandRequest.MAX_ARGUMENTS_TOTAL_CHARS} characters"))
        }
        request.stdin?.let { checkText("stdin", it, TerminalCommandRequest.MAX_STDIN_CHARS) }
        request.workingDirectory?.let {
            checkText("workingDirectory", it, TerminalCommandRequest.MAX_WORKDIR_CHARS, allowEmpty = false)
            if (it.isNotEmpty() && !isSupportedCommandPath(it)) {
                add(ValidationIssue("workingDirectory", "must be an absolute path or use ~/ or \$PREFIX/"))
            }
        }
        if (request.timeoutMillis !in 1..TerminalCommandRequest.MAX_TIMEOUT_MILLIS) {
            add(ValidationIssue("timeoutMillis", "must be between 1 and ${TerminalCommandRequest.MAX_TIMEOUT_MILLIS} milliseconds"))
        }
        if (request.environment == TerminalEnvironment.DEBIAN_PROOT && request.workingDirectory != null) {
            add(ValidationIssue("workingDirectory", "Debian proot working directories are not supported by this initial adapter"))
        }
        if (request.environment == TerminalEnvironment.DEBIAN_PROOT && !HostShell.isSafeAlias(request.distroAlias)) {
            add(ValidationIssue("distroAlias", "must be a valid proot-distro alias"))
        }
    }

    private fun isSupportedCommandPath(value: String): Boolean =
        value.startsWith('/') || value.startsWith("~/") || value.startsWith("\$PREFIX/")
}

/** An argv-style invocation. No shell string is constructed. */
data class TerminalCommandDescriptor(
    val executablePath: String,
    val arguments: List<String>,
    val stdin: String?,
    val workingDirectory: String?,
    val background: Boolean = true,
)

/** Pure descriptor construction, with the proot-distro executable supplied only after probing it. */
object TerminalCommandDescriptors {
    fun create(
        request: TerminalCommandRequest,
        probedProotDistroPath: String? = null,
    ): TerminalCommandDescriptor? {
        if (TerminalRequestValidator.validate(request).isNotEmpty()) return null
        return when (request.environment) {
            TerminalEnvironment.TERMUX_HOST -> TerminalCommandDescriptor(
                executablePath = request.commandPath,
                arguments = request.arguments.toList(),
                stdin = request.stdin,
                workingDirectory = request.workingDirectory,
            )
            TerminalEnvironment.DEBIAN_PROOT -> {
                val prootPath = probedProotDistroPath?.takeIf {
                    it.startsWith('/') && '\u0000' !in it && it.length <= TerminalCommandRequest.MAX_COMMAND_PATH_CHARS
                }
                    ?: return null
                TerminalCommandDescriptor(
                    executablePath = prootPath,
                    arguments = listOf("login", request.distroAlias, "--", request.commandPath) + request.arguments,
                    stdin = request.stdin,
                    workingDirectory = null,
                )
            }
        }
    }
}

enum class TerminalResultKind {
    SUCCEEDED,
    PROCESS_EXIT,
    TERMUX_INFRASTRUCTURE_ERROR,
    DISPATCH_ERROR,
    TERMUX_NOT_INSTALLED,
    PERMISSION_REQUIRED,
    PROOT_DISTRO_UNAVAILABLE,
    DENIED,
    POLICY_BLOCKED,
    CONFIRMATION_REQUIRED,
    INVALID_REQUEST,
    TIMEOUT,
    CANCELLED,
    UNKNOWN_RESULT,
}

data class TerminalCommandResult(
    val requestId: String?,
    val kind: TerminalResultKind,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val infrastructureErrorCode: Int? = null,
    val message: String? = null,
) {
    /** Only a real callback with infrastructure success and process exit code 0 is success. */
    val isSuccess: Boolean get() = kind == TerminalResultKind.SUCCEEDED && exitCode == 0
}

data class TermuxCallbackData(
    val infrastructureErrorCode: Int?,
    val exitCode: Int?,
    val stdout: String? = null,
    val stderr: String? = null,
    val errorMessage: String? = null,
)

object TerminalResultClassifier {
    const val MAX_RETURNED_TEXT_CHARS = 32 * 1024
    /** Android Activity.RESULT_OK, used by Termux to signal no infrastructure error. */
    const val TERMUX_INFRASTRUCTURE_OK = -1

    /** Pure classifier used by unit tests and any non-Android adapter. */
    fun classify(requestId: String, data: TermuxCallbackData?): TerminalCommandResult {
        if (data == null || data.infrastructureErrorCode == null) {
            return TerminalCommandResult(requestId, TerminalResultKind.UNKNOWN_RESULT, message = "Termux callback did not include a recognized result bundle")
        }
        val message = data.errorMessage.capped()
        if (data.infrastructureErrorCode != TERMUX_INFRASTRUCTURE_OK) {
            return TerminalCommandResult(
                requestId = requestId,
                kind = TerminalResultKind.TERMUX_INFRASTRUCTURE_ERROR,
                infrastructureErrorCode = data.infrastructureErrorCode,
                message = message,
                stdout = data.stdout.capped(),
                stderr = data.stderr.capped(),
            )
        }
        val exitCode = data.exitCode ?: return TerminalCommandResult(
            requestId,
            TerminalResultKind.UNKNOWN_RESULT,
            message = "Termux callback omitted process exit code",
        )
        return TerminalCommandResult(
            requestId = requestId,
            kind = if (exitCode == 0) TerminalResultKind.SUCCEEDED else TerminalResultKind.PROCESS_EXIT,
            exitCode = exitCode,
            stdout = data.stdout.capped(),
            stderr = data.stderr.capped(),
            message = message,
        )
    }

    /** Reads Termux's documented result Bundle into the pure classifier model. */
    fun fromAndroidBundle(requestId: String, resultBundle: android.os.Bundle?): TerminalCommandResult {
        if (resultBundle == null || !resultBundle.containsKey(TermuxProtocol.RESULT_ERR)) return classify(requestId, null)
        return classify(
            requestId,
            TermuxCallbackData(
                infrastructureErrorCode = resultBundle.getInt(TermuxProtocol.RESULT_ERR),
                exitCode = if (resultBundle.containsKey(TermuxProtocol.RESULT_EXIT_CODE)) resultBundle.getInt(TermuxProtocol.RESULT_EXIT_CODE) else null,
                stdout = resultBundle.getString(TermuxProtocol.RESULT_STDOUT),
                stderr = resultBundle.getString(TermuxProtocol.RESULT_STDERR),
                errorMessage = resultBundle.getString(TermuxProtocol.RESULT_ERRMSG),
            ),
        )
    }

    private fun String?.capped(): String = this.orEmpty().take(MAX_RETURNED_TEXT_CHARS)
}

/** Protocol literals are defined by Termux's official RUN_COMMAND Intent API. */
internal object TermuxProtocol {
    const val PACKAGE = "com.termux"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    const val SERVICE_CLASS = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    const val RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"
}
