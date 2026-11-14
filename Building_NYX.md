# Building NYX v0.8.4 — Project Endurance

This legacy filename is retained for compatibility with existing repository links. The canonical build guide is [BUILD.md](BUILD.md); it documents SDK/JDK requirements, the pinned AGP/Gradle/KSP/Room toolchain, reproducible build and test commands, the ARM64 native build, instrumentation limits, release-integrity checks, and debug-signing constraints.

The published app is versionCode 17 / versionName 0.8.4, targeting API 37 with a minimum of API 26. Its ARM64 debug APK, compiled instrumentation APK, clean source archive, and checksum manifest are available from the [official v0.8.4 release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.4). It is debug-signed, not production or store-signed. The release owner reports that the Performance dashboard rendered on a Pixel 10 Pro Fold; native inference, calibration, KV reuse, and Termux/PRoot runtime remain device-validation items.
