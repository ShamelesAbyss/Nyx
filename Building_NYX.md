# Building NYX — v0.8.8 Project Insight

This legacy filename is preserved for existing links. Current documentation is in [BUILD.md](BUILD.md); see the [official release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.8), [release notes](RELEASE_NOTES_v0.8.8.md), [implementation report](V088_IMPLEMENTATION_REPORT.md) and [test results](TEST_RESULTS.md).

v0.8.8 is built with Gradle 9.3.1 and Android Gradle Plugin 9.1.1 against compile/target SDK 37. Package com.nyx.ai, versionCode 21 / versionName 0.8.8; ARM64 debug-signed, min API 26. The verified clean command is `./gradlew --no-daemon clean testDebugUnitTest assembleDebug assembleDebugAndroidTest`; it passed in 1m 36s with 934 JVM tests across 96 classes, zero failures and zero errors, one environment-dependent skip. No AAB or Play publication is produced.
