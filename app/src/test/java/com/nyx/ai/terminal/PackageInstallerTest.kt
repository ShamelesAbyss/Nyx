package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class PackageInstallerTest {
    @Test
    fun `package manager lock is classified separately from repository failure`() {
        assertEquals(
            InstallFailureKind.PACKAGE_MANAGER_LOCK,
            DependencyInstaller.classifyFailure("E: Could not get lock /var/lib/dpkg/lock-frontend", 100),
        )
    }

    @Test
    fun `repository failure is classified`() {
        assertEquals(
            InstallFailureKind.REPOSITORY_UNAVAILABLE,
            DependencyInstaller.classifyFailure("E: Failed to fetch http://deb.debian.org/debian 404 Not Found", 100),
        )
    }

    @Test
    fun `storage exhaustion is classified`() {
        assertEquals(
            InstallFailureKind.STORAGE_EXHAUSTED,
            DependencyInstaller.classifyFailure("E: No space left on device", 100),
        )
    }

    @Test
    fun `incompatible dependency is classified`() {
        assertEquals(
            InstallFailureKind.INCOMPATIBLE_PACKAGE,
            DependencyInstaller.classifyFailure("Depends: libfoo but it is not going to be installed", 100),
        )
    }

    @Test
    fun `network failure is classified`() {
        assertEquals(
            InstallFailureKind.NETWORK,
            DependencyInstaller.classifyFailure("Temporary failure resolving deb.debian.org", 100),
        )
    }

    @Test
    fun `unknown output stays unknown`() {
        assertEquals(
            InstallFailureKind.UNKNOWN,
            DependencyInstaller.classifyFailure("something unexpected happened", 100),
        )
    }
}
