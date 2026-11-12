package com.nyx.ai.terminal

/** Risk classification for a shell command proposed by a user or an autonomous agent. */
enum class CommandRisk {
    READ_ONLY,
    MUTATING,
    NETWORK_MUTATING,
    DESTRUCTIVE,
    PRIVILEGED,
    BLOCKED,
}

data class CommandAssessment(
    val risk: CommandRisk,
    val requiresConfirmation: Boolean,
    val blocked: Boolean,
    val reasons: List<String>,
    val executable: String? = null,
) {
    val summary: String
        get() = when {
            blocked -> "Blocked: ${reasons.joinToString("; ").ifBlank { "unsafe command" }}"
            requiresConfirmation -> "${risk.name.lowercase().replace('_', ' ')} command requires explicit approval"
            else -> "read-only command"
        }
}

/**
 * Conservative, deterministic policy for shell commands executed through Termux.
 *
 * It never grants privilege: root/privilege-escalation commands are blocked outright. Destructive
 * commands and package/network mutations require per-command confirmation. Unknown executables are
 * treated as mutating so that a new tool cannot silently bypass approval.
 */
object CommandPolicy {
    const val MAX_COMMAND_CHARS = 8_000

    private val readOnlyCommands = setOf(
        "ls", "cat", "head", "tail", "wc", "stat", "file", "pwd", "echo", "printf",
        "grep", "egrep", "fgrep", "rg", "find", "fd", "tree", "du", "df", "free",
        "uname", "whoami", "id", "date", "env", "printenv", "which", "type", "command",
        "sha256sum", "sha1sum", "md5sum", "cksum", "basename", "dirname", "realpath",
        "readlink", "sort", "uniq", "cut", "tr", "sed", "awk", "diff", "cmp", "jq",
        "git", "gradle", "npm", "node", "python3", "java", "javac", "kotlinc", "cargo", "rustc", "go",
    )

    private val alwaysBlocked = setOf("su", "sudo", "doas", "pkexec", "setenforce", "reboot", "shutdown", "halt", "poweroff")

    private val blockedPatterns = listOf(
        Regex("""\bpm\s+(grant|revoke|clear|uninstall)\b"""),
        Regex("""\bsettings\s+put\b"""),
        Regex("""\bmount\s+.*\b(rw|remount)\b"""),
        Regex("""\bmkfs(\.\w+)?\b"""),
        Regex("""\bfdisk\b|\bparted\b|\bsgdisk\b"""),
        Regex("""\bdd\b[^\n]*\bof=/dev/(block|sd|mmcblk)"""),
        Regex("""\b(chmod|chown)\s+-R\s+.*\s+/(\s|$)"""),
        Regex(""">\s*/dev/(block|sd|mmcblk)"""),
        Regex("""\bsystemctl\b|\bservice\s+\w+\s+(start|stop|restart)\b"""),
        Regex("""\biptables\b|\bnft\b"""),
    )

    private val destructivePatterns = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*[rR][a-zA-Z]*\s+)?(-[a-zA-Z]*f[a-zA-Z]*\s+)?(/|~|/\*|~/\*|${'$'}HOME|${'$'}HOME/\*)(\s|$)"""),
        Regex("""\brm\s+-[a-zA-Z]*[rR][a-zA-Z]*f?[a-zA-Z]*\s+(\*|\.\*)(\s|$)"""),
        Regex("""\bgit\s+reset\s+--hard\b"""),
        Regex("""\bgit\s+clean\s+-[a-zA-Z]*[fdx]"""),
        Regex("""\bgit\s+push\b[^\n]*--force"""),
        Regex("""\bgit\s+branch\s+-D\b"""),
        Regex("""\btruncate\s+-s\s*0\b"""),
        Regex("""\b(shred|wipefs)\b"""),
        Regex("""\bmv\b[^\n]*\s/dev/null(\s|$)"""),
        Regex("""\bchmod\s+(-R\s+)?777\s+/(\s|$)"""),
        Regex("""\bdropdb\b|\bdrop\s+database\b"""),
    )

    private val networkMutating = setOf("pkg", "apt", "apt-get", "dpkg", "pip", "pip3", "npm", "yarn", "pnpm", "gem", "cargo", "go", "curl", "wget", "git", "ssh", "scp", "rsync")

    private val mutatingCommands = setOf(
        "rm", "mv", "cp", "mkdir", "rmdir", "touch", "ln", "install", "tee", "chmod", "chown", "chgrp",
        "sed", "awk", "tar", "zip", "unzip", "gzip", "gunzip", "patch", "make", "cmake", "gradle",
        "npm", "node", "python3", "java", "javac", "kotlinc", "cargo", "rustc", "go", "git",
    )

    /** Tokenizes a shell command enough for risk analysis; quote-aware and never throws. */
    fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var tokenStarted = false
        for (ch in command) {
            when {
                escaped -> { current.append(ch); escaped = false; tokenStarted = true }
                ch == '\\' && quote != '\'' -> escaped = true
                quote != null -> if (ch == quote) quote = null else current.append(ch)
                ch == '\'' || ch == '"' -> { quote = ch; tokenStarted = true }
                ch.isWhitespace() -> {
                    if (tokenStarted) { tokens += current.toString(); current.setLength(0); tokenStarted = false }
                }
                else -> { current.append(ch); tokenStarted = true }
            }
        }
        if (tokenStarted) tokens += current.toString()
        return tokens
    }

    /** Splits on shell control operators so each segment is assessed. */
    fun segments(command: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var i = 0
        while (i < command.length) {
            val ch = command[i]
            when {
                escaped -> { current.append(ch); escaped = false }
                ch == '\\' && quote != '\'' -> { current.append(ch); escaped = true }
                quote != null -> { current.append(ch); if (ch == quote) quote = null }
                ch == '\'' || ch == '"' -> { current.append(ch); quote = ch }
                ch == ';' || ch == '\n' || ch == '|' || ch == '&' -> {
                    val text = current.toString().trim()
                    if (text.isNotEmpty()) result += text
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            i++
        }
        current.toString().trim().let { if (it.isNotEmpty()) result += it }
        return result
    }

    fun assess(command: String): CommandAssessment {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return CommandAssessment(CommandRisk.BLOCKED, requiresConfirmation = false, blocked = true, reasons = listOf("command is empty"))
        }
        if (trimmed.length > MAX_COMMAND_CHARS) {
            return CommandAssessment(CommandRisk.BLOCKED, false, true, listOf("command exceeds $MAX_COMMAND_CHARS characters"))
        }
        if ('\u0000' in trimmed) {
            return CommandAssessment(CommandRisk.BLOCKED, false, true, listOf("command contains NUL"))
        }

        val reasons = mutableListOf<String>()
        var risk = CommandRisk.READ_ONLY
        var blocked = false
        var executable: String? = null

        val segmentTexts = segments(trimmed)
        if (segmentTexts.isEmpty()) {
            return CommandAssessment(CommandRisk.BLOCKED, false, true, listOf("command has no executable"))
        }

        for (segment in segmentTexts) {
            val tokens = tokenize(segment)
            if (tokens.isEmpty()) continue
            var index = 0
            // Skip leading environment assignments such as FOO=bar cmd.
            while (index < tokens.size && Regex("^[A-Za-z_][A-Za-z0-9_]*=").containsMatchIn(tokens[index])) index++
            if (index >= tokens.size) continue
            val raw = tokens[index]
            val name = raw.substringAfterLast('/').lowercase()
            if (executable == null) executable = name

            if (name in alwaysBlocked) {
                blocked = true
                reasons += "privilege escalation or system power control is not permitted ($name)"
                continue
            }
            if (blockedPatterns.any { it.containsMatchIn(segment) }) {
                blocked = true
                reasons += "command targets protected Android or system state"
                continue
            }
            if (isDestructiveRm(tokens) || destructivePatterns.any { it.containsMatchIn(segment) }) {
                risk = maxRisk(risk, CommandRisk.DESTRUCTIVE)
                reasons += "destructive filesystem or history operation"
                continue
            }
            if (name in networkMutating && isMutationInvocation(name, tokens.drop(index + 1))) {
                risk = maxRisk(risk, CommandRisk.NETWORK_MUTATING)
                reasons += "package/network operation may modify the environment"
                continue
            }
            if (name in mutatingCommands && isMutationInvocation(name, tokens.drop(index + 1))) {
                risk = maxRisk(risk, CommandRisk.MUTATING)
                continue
            }
            if (name in readOnlyCommands && !isMutationInvocation(name, tokens.drop(index + 1))) {
                continue
            }
            if (name !in readOnlyCommands) {
                risk = maxRisk(risk, CommandRisk.MUTATING)
                reasons += "unrecognized executable '$name' is treated as mutating"
            }
        }

        if (blocked) risk = CommandRisk.BLOCKED
        val requiresConfirmation = !blocked && risk != CommandRisk.READ_ONLY
        return CommandAssessment(risk, requiresConfirmation, blocked, reasons.distinct(), executable)
    }

    private fun maxRisk(a: CommandRisk, b: CommandRisk): CommandRisk = if (b.ordinal > a.ordinal) b else a

    private val rmRootTarget = Regex("""^(/|~|~/|\*|\.\*|${'$'}HOME|${'$'}HOME/|${'$'}HOME/\*)$""")

    /** Recursive deletion of a root-like target is destructive even when the regex misses it. */
    private fun isDestructiveRm(tokens: List<String>): Boolean {
        val executable = tokens.firstOrNull()?.substringAfterLast('/')?.lowercase() ?: return false
        if (executable != "rm") return false
        val args = tokens.drop(1)
        val recursive = args.any { arg ->
            arg == "--recursive" || (arg.startsWith("-") && !arg.startsWith("--") && (arg.contains('r') || arg.contains('R')))
        }
        if (!recursive) return false
        return args.filterNot { it.startsWith("-") }.any { rmRootTarget.matches(it.trim()) }
    }

    /** Read-only tools become mutating for known state-changing subcommands or in-place flags. */
    private fun isMutationInvocation(name: String, args: List<String>): Boolean {
        val first = args.firstOrNull { !it.startsWith("-") }?.lowercase()
        return when (name) {
            "git" -> first in setOf("commit", "add", "checkout", "switch", "merge", "rebase", "cherry-pick", "apply", "am", "stash", "tag", "push", "pull", "fetch", "clone", "init", "clean", "reset", "rm", "mv", "restore")
            "gradle" -> true
            "npm" -> first in setOf("install", "i", "ci", "update", "run", "publish", "uninstall", "link")
            "node" -> true
            "python3" -> true
            "java", "javac", "kotlinc", "cargo", "rustc", "go" -> true
            "sed" -> args.any { it == "-i" || it.startsWith("-i") }
            "awk" -> args.any { it.startsWith("inplace") }
            "find" -> args.any { it == "-delete" || it == "-exec" || it == "-execdir" }
            "tar", "zip", "unzip", "gzip", "gunzip", "patch", "make", "cmake" -> true
            "pkg", "apt", "apt-get", "dpkg", "pip", "pip3", "yarn", "pnpm", "gem" -> first in setOf("install", "remove", "uninstall", "update", "upgrade", "purge", "autoremove", "add")
            "curl", "wget" -> args.any { it == "-o" || it == "-O" || it.startsWith("--output") || it == "--remote-name" }
            "ssh", "scp", "rsync" -> true
            else -> false
        }
    }
}
