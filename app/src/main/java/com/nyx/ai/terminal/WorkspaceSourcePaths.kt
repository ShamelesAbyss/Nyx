package com.nyx.ai.terminal

import com.nyx.ai.development.DevelopmentPaths

/** Source access does not include credentials, ambient execution config or app staging metadata. */
object WorkspaceSourcePaths {
    private val privateNames = setOf(".netrc", ".npmrc", ".pypirc", ".gitconfig", ".bashrc", ".bash_profile", ".profile", "authorized_keys", "known_hosts")
    fun isSafeRelative(path: String): Boolean = DevelopmentPaths.isSafeRelative(path) && path.split('/').all { part ->
        val name = part.lowercase()
        name !in privateNames && !name.startsWith(".nyx-") && name != ".env" && !name.startsWith(".env.") &&
            !name.contains("secret") && !name.contains("credential") && !name.startsWith("id_") &&
            !name.endsWith(".pem") && !name.endsWith(".key") && !name.endsWith(".keystore") && !name.endsWith(".jks") && !name.endsWith(".p12") && !name.endsWith(".pfx")
    }
}
