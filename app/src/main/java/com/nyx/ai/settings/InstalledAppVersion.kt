package com.nyx.ai.settings

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** Version information read from the installed package, not duplicated in UI source. */
internal data class InstalledAppVersion(
    val versionName: String,
    val longVersionCode: Long
)

@Suppress("DEPRECATION")
internal fun installedAppVersion(context: Context): InstalledAppVersion {
    val packageInfo = packageInfo(context.packageManager, context.packageName)
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        packageInfo.versionCode.toLong()
    }
    return InstalledAppVersion(packageInfo.versionName.orEmpty(), versionCode)
}

internal fun formatInstalledAppVersion(version: InstalledAppVersion): String =
    "NYX ${version.versionName} · version code ${version.longVersionCode}"

@Suppress("DEPRECATION")
private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        packageManager.getPackageInfo(packageName, 0)
    }
