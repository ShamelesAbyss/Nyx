package com.nyx.ai.settings

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstalledAppVersionInstrumentedTest {
    @Test
    @Suppress("DEPRECATION")
    fun formatterUsesInstalledPackageMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actual = installedAppVersion(context)
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val expectedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }

        assertEquals(packageInfo.versionName, actual.versionName)
        assertEquals(expectedCode, actual.longVersionCode)
        assertEquals(
            "NYX ${packageInfo.versionName} · version code $expectedCode",
            formatInstalledAppVersion(actual)
        )
    }
}
