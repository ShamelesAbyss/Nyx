# v0.6 build compatibility corrections

SDK 37 is installed and remains mandatory for this cycle. AGP 9.1.1 and Gradle 9.3.1 are retained; Kotlin/Compose compiler is 2.2.10, and minSdk remains 26.

The first real integrated configuration using KSP 2.2.10-2.0.2 failed because it added generated directories through the legacy Kotlin sourceSets DSL. The documented temporary `android.disallowKotlinSourceSets=false` setting passed configuration but did not resolve Room 2.6.1's KSP2 compiler failure (`unexpected jvm signature V`). It was removed rather than becoming a permanent suppression.

KSP 2.3.6 explicitly fixes built-in-Kotlin detection and supports the chosen toolchain. Room runtime, KTX and compiler are upgraded together to 2.8.5, whose official release notes establish Kotlin2/KSP2 support. Room database remains version 3 with registered non-destructive 1→2 and 2→3 migrations. Runtime migration preservation still requires the included Android device tests. Build/test outcomes are recorded only in TEST_RESULTS.md once executed.

Sources checked 2026-11-12 UTC:

- https://github.com/google/ksp/issues/2729
- https://github.com/google/ksp/releases/tag/2.3.6
- https://developer.android.com/jetpack/androidx/releases/room
- https://developer.android.com/build/migrate-to-built-in-kotlin

The submitted directive stops mid-sentence under Priority Nine. No installed self-update, executable plugin loading, APK installation, repository push, store submission or release publication is inferred or performed.
