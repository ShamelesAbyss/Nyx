package com.nyx.ai.terminal

import com.nyx.ai.development.DevelopmentPaths

/** Validated argv, never the original model shell text. This is not an OS sandbox. */
data class StructuredTerminalCommand(val executable: String, val arguments: List<String>, val operands: List<String> = emptyList()) {
    fun resolvedArguments(directory: String): List<String> = arguments.dropLast(operands.size) + operands.map { "$directory/$it" }
    fun script(environment: TerminalEnvironment, workspace: String, directory: String): String {
        val path = ControlledShell.executable(environment, executable)
        val guards = operands.joinToString("") { operand ->
            val target = "$directory/$operand"
            WorkspacePathGuard.regularFile(workspace, target)
        }
        return WorkspacePathGuard.directory(workspace, directory) + guards + "cd -- ${ShellCommandBuilder.quote(directory)} || exit 72; " +
            (listOf(path) + resolvedArguments(directory)).joinToString(" ", transform = ShellCommandBuilder::quote)
    }
}

/** Small explicit grammar; no option fall-through, interpreter, Git, mutation or executable override. */
object StructuredTerminalCommands {
    private const val MAX_OUTPUT = 16_000
    fun parse(command: String): StructuredTerminalCommand? {
        if (command.isBlank() || command.length > CommandPolicy.MAX_COMMAND_CHARS || ModelShellSyntax.rejection(command) != null) return null
        val argv = CommandPolicy.tokenize(command)
        val name = argv.firstOrNull() ?: return null
        val args = argv.drop(1)
        if (!name.matches(Regex("[a-z0-9]+")) || args.size > 32 || args.any { it.length > 512 || '\u0000' in it }) return null
        fun files(values: List<String>): Boolean = values.isNotEmpty() && values.size <= 8 && values.all {
            !it.startsWith('-') && WorkspaceSourcePaths.isSafeRelative(it) && it.split('/').all { part -> part.matches(Regex("[A-Za-z0-9._+@ -]+")) }
        }
        return when (name) {
            "pwd" -> if (args.isEmpty()) StructuredTerminalCommand("pwd", listOf("-P")) else null
            "echo" -> StructuredTerminalCommand("printf", listOf("%s\\n") + args)
            // A literal, not a model-supplied printf format (which can contain %n or escape directives).
            "printf" -> if (args.size == 1) StructuredTerminalCommand("printf", listOf("%s") + args) else null
            "ls" -> if (args.all { it in setOf("-l", "-a", "-la", "-al", "-1") })
                StructuredTerminalCommand("ls", args + listOf("--", ".")) else null
            "cat" -> if (files(args)) StructuredTerminalCommand("head", listOf("-c", MAX_OUTPUT.toString(), "--") + args, args) else null
            "wc" -> {
                val flag = args.firstOrNull()?.takeIf { it in setOf("-c", "-l", "-w") }
                val paths = if (flag == null) args else args.drop(1)
                if (files(paths)) StructuredTerminalCommand("wc", listOfNotNull(flag) + "--" + paths, paths) else null
            }
            "sha256sum" -> if (files(args)) StructuredTerminalCommand("sha256sum", listOf("--") + args, args) else null
            "head", "tail" -> {
                val mode = args.firstOrNull()
                val count = args.getOrNull(1)?.takeIf { it.matches(Regex("[1-9][0-9]{0,4}")) }?.toIntOrNull()
                val paths = args.drop(2)
                if (mode in setOf("-n", "-c") && count != null && count <= (if (mode == "-c") MAX_OUTPUT else 200) && files(paths))
                    StructuredTerminalCommand(name, listOf(mode!!, count.toString(), "--") + paths, paths)
                else if (files(args)) StructuredTerminalCommand(name, listOf("-n", "20", "--") + args, args) else null
            }
            "grep" -> {
                // Literal search of explicit files only; -r/-f/--include/output/regex forms are absent.
                var index = 0
                val flags = mutableListOf<String>()
                while (index < args.size && args[index] in setOf("-F", "-n", "-i")) { flags += args[index++]; if (flags.size > 3) return null }
                val pattern = args.getOrNull(index++) ?: return null
                val paths = args.drop(index)
                if (pattern.isNotEmpty() && pattern.length <= 200 && files(paths))
                    StructuredTerminalCommand("grep", listOf("-F") + flags.distinct() + listOf("--", pattern) + paths, paths) else null
            }
            else -> null
        }
    }
}

/** Real-path and link-count checks immediately precede opens; not race-proof against the same UID. */
object WorkspacePathGuard {
    private fun q(value: String) = ShellCommandBuilder.quote(value)
    fun directory(root: String, target: String = root): String {
        require(target == root || target.startsWith("$root/"))
        return "r=\$(realpath -e -- ${q(root)}) || exit 71; [ \"\$r\" = ${q(root)} ] && [ ! -L ${q(root)} ] && [ -d \"\$r\" ] || exit 71; " +
            "[ \"\$(realpath -e -- ${q(target)})\" = ${q(target)} ] && [ ! -L ${q(target)} ] && [ -d ${q(target)} ] || exit 72; cd -- ${q(target)} || exit 72; "
    }
    fun regularFile(root: String, target: String, allowMissing: Boolean = false): String {
        require(target.startsWith("$root/"))
        val parent = target.substringBeforeLast('/')
        val check = "[ ! -L ${q(target)} ] && [ \"\$(realpath -e -- ${q(target)})\" = ${q(target)} ] && [ -f ${q(target)} ] && [ \"\$(stat -c %h -- ${q(target)})\" = 1 ]"
        return directory(root) + "[ \"\$(realpath -e -- ${q(parent)})\" = ${q(parent)} ] && [ -d ${q(parent)} ] || exit 72; " +
            if (allowMissing) "if [ -e ${q(target)} ] || [ -L ${q(target)} ]; then $check || exit 72; fi; " else "$check || exit 72; "
    }
}
