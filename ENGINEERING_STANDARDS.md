# NYX engineering standards

## Android release baseline

For version 0.6.0, keep `versionName = "0.6.0"`, `versionCode = 6`, and `minSdk = 26`. Compile and target API 37 because its base platform and supported tools are available. API 36 is the absolute submission floor, not the default target. Never lower target below 36, compile below target, or raise minimum SDK as an expedient build repair. Run `python3 tools/check_sdk_policy.py` and its `--self-test` in CI; `:app:preBuild` also runs independent Gradle and Python guards.

The verified build baseline is AGP 9.1.1, Gradle 9.3.1, built-in Kotlin/Compose compiler 2.2.10, KSP 2.2.10-2.0.2, JDK 17+, SDK Build Tools 36.0.0, NDK 28.2.13676358, and CMake 3.22.1. Java/Kotlin bytecode stays at JVM 17. Use the checksum-pinned complete Gradle wrapper, never an unversioned system Gradle or unsupported compile-SDK suppression.

Re-evaluate the [official Google Play target policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en) before each release; last checked **2026-11-12 UTC**. Record the source, date, applicable category, and deadlines in `docs/ANDROID_COMPATIBILITY.md`. A fallback to API 36 requires reproducible failure to obtain the actual SDK 37 package or necessary supported tools, not a guessed package name, old AGP incompatibility, or transient download error. Change both guards and the documentation only after review. Treat future policy changes as an explicit engineering update, not a build-time network dependency.

## Native inference integration

Compile the vendored llama.cpp implementation as the `nyx_llama` CMake target, using C++17, NDK r28c, CMake 3.22.1, and the `arm64-v8a` ABI. Do not add x86_64 to production builds merely to increase the test matrix; a separately justified emulator flavor can be reviewed later. Package the executable code with the app and use normal JNI loading. Model weights, workspace contents, and skill documents are data, never downloaded executable plugins.

The native boundary must validate paths, model metadata, resource limits, handles, and request lengths before allocation. Keep context/batch/thread/output budgets explicit; admit models conservatively using native plus managed memory estimates. Run only the supported number of sessions and provide cooperative cancellation. Release model/context and transient buffers on every success, cancellation, timeout, and error path; avoid indefinite native calls that prevent service shutdown. Never silently replace a failed native run with fabricated output.

AGP 9.1.1 and NDK r28 provide 16 KB ZIP/ELF build alignment. Preserve uncompressed JNI packaging and verify every actual packaged shared object plus the APK's ZIP alignment. Query real page size instead of assuming 4096. Treat alignment checks and on-device 16 KB inference as separate test claims.

## Android lifecycle, permissions, and memory

Request API 37 `ACCESS_LOCAL_NETWORK` only when a user-selected LAN feature actually needs it, with API gating and graceful denial/revocation. Local JNI CPU work does not need a LAN grant. Keep HTTPS Internet access and LAN/cleartext behavior distinct. Never bypass denied access or turn an unrestricted network permission into an unrestricted tool capability.

Native CPU inference is not automatically a `dataSync` or `mediaProcessing` foreground service. Choose a type that describes the real work. A `specialUse` declaration requires an honest subtype explanation and is subject to Google Play review, not guaranteed approval. Use an app-level bounded deadline, explicit user initiation, a visible notification, cancellation, and prompt service stop on every terminal path. Handle platform timeouts; do not invent an OS guarantee of unlimited runtime.

Android 17 memory limiter diagnostics use `ApplicationExitInfo.REASON_OTHER` plus a description containing `MemoryLimiter:AnonSwap`. SDK-gate historical exits at API 30. System free/available RAM and Java memory class are advisory inputs, not a public value for the platform's native app cap. Keep recovery state bounded and tolerate OS process death without corrupting model metadata or workspace edits.

## Workspace and tool safety

Treat repository files, logs, model prompts, and skill/project documents as untrusted inputs. Workspace tools must use canonical paths and enforce the selected project root. Mutating operations require a planned, bounded scope and must not edit unrelated files, escape the project, write credentials, or execute source instructions as privileged commands. Avoid symlink escapes and partial unsafe file replacement.

Inspection, planning, patching, testing, and repair must be observable phases with step/time/output/resource budgets. Verify a patch against its expected original content before applying it. Retain enough provenance to explain edits and stale-source refusals without storing secrets or unrestricted raw logs. Retry only a concrete failure with a finite repair budget; stop with the actual diagnostic when it cannot be safely repaired.

## Validation and release evidence

Serialize Gradle app builds in a shared workspace. Do not run competing builds against the same `.gradle`, `.cxx`, native output, or APK directories. A wrapper task in a separate empty bootstrap project is not an app compilation. Prefer bounded worker/native parallelism and record exact commands and exit codes.

Run policy tests, JVM/unit tests, application assembly, native artifact/alignment inspection, and device checks as separate stages. A compile success does not prove JNI runtime success; a JNI smoke test does not establish throughput, thermal endurance, permissions, lifecycle survival, or Play acceptance. Tests must disclose exact ABI, device/OS, page size, model/version, configuration, and limitations where relevant.

Never claim a hardware or emulator test, native inference result, performance figure, security property, or store-policy acceptance unless the corresponding evidence exists. Do not label static configuration as validated device support. Release notes must distinguish completed checks from pending tests and known blockers. Official compatibility facts and implementation obligations are in [Android compatibility](docs/ANDROID_COMPATIBILITY.md).
