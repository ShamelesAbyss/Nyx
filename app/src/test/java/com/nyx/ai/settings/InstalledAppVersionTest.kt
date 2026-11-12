package com.nyx.ai.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppVersionTest {
    @Test
    fun formatsPackageVersionNameAndLongVersionCode() {
        val packageMetadata = InstalledAppVersion(versionName = "0.3.0", longVersionCode = 3L)

        assertEquals("NYX 0.3.0 · version code 3", formatInstalledAppVersion(packageMetadata))
    }
}
