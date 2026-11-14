# Building NYX v0.8.5 — Project Sovereign

This legacy filename is retained for compatibility with existing repository links. The canonical build guide is [BUILD.md](BUILD.md); it documents SDK/JDK requirements, the pinned AGP/Gradle/KSP/Room toolchain, reproducible build and test commands, the ARM64 native build, instrumentation limits, release-integrity checks, and debug-signing constraints.

The published app is versionCode 18 / versionName 0.8.5, targeting API 37 with a minimum of API 26. Its ARM64 debug APK, compiled instrumentation APK, clean source archive, and checksum manifest are available from the [official v0.8.5 release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.5). It is debug-signed, not production or store-signed. The clean build passed 731 JVM tests across 70 classes; instrumentation compiled but was not executed. Native inference, the Sovereign execution loop, calibration, KV reuse, and Termux/PRoot runtime remain device-validation items.
