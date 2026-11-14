package com.nyx.ai.terminal

/** Fixed local inspection only. Git aliases/options/network actions never reach this surface. */
object FixedRepositoryInspection {
    fun script(root: String, environment: TerminalEnvironment): String {
        val q = ShellCommandBuilder::quote
        val metadata = "$root/.git"
        // Worktree .git indirection and metadata symlinks are deliberately unsupported. Helpers may
        // parse repository config but executable settings used by these fixed commands are overridden.
        val git = q(ControlledShell.executable(environment, "git")) + " --no-pager --no-optional-locks " +
            "-c core.fsmonitor=false -c core.hooksPath=/dev/null -c core.pager=cat -c core.untrackedCache=false " +
            "-c core.attributesFile=/dev/null -c diff.external= -c core.bare=false -c core.worktree=${q(root)} " +
            "--git-dir=${q(metadata)} --work-tree=${q(root)}"
        return WorkspacePathGuard.directory(root) +
            "[ ! -L ${q(metadata)} ] && [ -d ${q(metadata)} ] && [ \"\$(realpath -e -- ${q(metadata)})\" = ${q(metadata)} ] || exit 72; " +
            "[ -z \"\$(find ${q(metadata)} -type l -print -quit)\" ] || exit 72; " +
            "export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_TERMINAL_PROMPT=0 GIT_PAGER=cat; " +
            "$git status --short --untracked-files=no && $git log -5 --oneline --no-decorate"
    }
}
