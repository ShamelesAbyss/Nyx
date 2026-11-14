package com.nyx.ai.terminal

/**
 * Rejects shell syntax that the existing command-risk classifier cannot safely attribute to one
 * executable. This is intentionally narrower than the manual terminal: fixed build/test commands
 * and approved package installs do not pass through this model-directed boundary.
 */
object ModelShellSyntax {
    private val indirectExecutables = setOf(
        "eval", "exec", "source", "builtin", "xargs", "nohup", "setsid",
        "env", "timeout", "nice", "command",
        "sh", "bash", "dash", "zsh", "ksh", "fish",
        "python", "python3", "node", "perl", "ruby", "php",
    )

    /** Returns a human-readable denial, or null when the command has no unsupported construct. */
    fun rejection(command: String): String? {
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < command.length) {
            val ch = command[index]
            when {
                escaped -> escaped = false
                ch == '\\' && quote != '\'' -> escaped = true
                quote != null -> {
                    if (ch == quote) {
                        quote = null
                    } else if (quote == '"' && (ch == '`' || ch == '$' && index + 1 < command.length && command[index + 1] == '(')) {
                        return if (ch == '`') "Backtick command substitution is not supported for model commands." else "Command substitution is not supported for model commands."
                    } else if (quote == '"' && ch == '$') {
                        return "Shell variable expansion is not supported for model commands."
                    }
                }
                ch == '\'' || ch == '"' -> quote = ch
                ch == '`' -> return "Backtick command substitution is not supported for model commands."
                ch == '$' && index + 1 < command.length && command[index + 1] == '(' ->
                    return "Command substitution is not supported for model commands."
                ch == '>' || ch == '<' ->
                    return "Input/output redirection and process substitution are not supported for model commands."
                ch == ';' || ch == '|' || ch == '&' || ch == '\n' || ch == '\r' ->
                    return "Command chaining, pipes, and compound commands are not supported for model commands."
                ch == '$' -> return "Shell variable expansion is not supported for model commands."
            }
            index++
        }
        if (quote != null || escaped) return "Unclosed quote or dangling escape in model command."
        if (command.any { it.isISOControl() && it != '\t' }) return "Control characters are not supported for model commands."
        val tokens = CommandPolicy.tokenize(command)
        val tokenIndex = 0
        val executable = tokens.firstOrNull()
        if (executable == null || '/' in executable || '=' in executable) return "Executable paths and environment assignments are not supported for model commands."
        if (executable in indirectExecutables) {
            return "Indirect command execution is not supported for model commands."
        }
        if (executable == "find" && tokens.drop(tokenIndex + 1).any {
                it.equals("-exec", ignoreCase = true) || it.equals("-execdir", ignoreCase = true)
            }
        ) {
            return "Indirect command execution is not supported for model commands."
        }
        return null
    }
}
