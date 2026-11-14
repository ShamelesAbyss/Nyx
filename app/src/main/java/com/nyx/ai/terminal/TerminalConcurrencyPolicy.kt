package com.nyx.ai.terminal

/** Risk approval is not a read-lock proof. Shell compositions/unknown executables stay exclusive. */
object TerminalConcurrencyPolicy {
    private val simpleReaders = setOf("pwd", "echo", "printf", "ls", "cat", "head", "tail", "wc", "stat", "file",
        "uname", "whoami", "id", "date", "grep", "rg", "sha256sum", "cmp", "diff", "sort", "uniq", "cut", "tr")
    private val shellControl = Regex("[;&|><\\$`()\\r\\n]")
    fun canShareWorkspace(command: String): Boolean = command.isNotBlank() && !shellControl.containsMatchIn(command) &&
        command.trim().substringBefore(' ').substringBefore('\t') in simpleReaders
}
