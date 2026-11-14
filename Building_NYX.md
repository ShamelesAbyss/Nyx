# Building NYX — v0.9.0 Project Workbench

This legacy filename is preserved for existing links. Current documentation is in [BUILD.md](BUILD.md); see the [official release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.9.0), [release notes](RELEASE_NOTES_v0.9.0.md), [implementation report](V089_IMPLEMENTATION_REPORT.md) and [test results](TEST_RESULTS.md).

v0.9.0 is built with Gradle 9.3.1 and Android Gradle Plugin 9.1.1 against compile/target SDK 37. Package com.nyx.ai, versionCode 22 / versionName 0.9.0; ARM64 debug-signed, min API 26. The verified clean command is `./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest`; it passed in 1m 53s with 1,034 JVM tests across 107 classes, zero failures, errors and skips. No AAB or Play publication is produced.
