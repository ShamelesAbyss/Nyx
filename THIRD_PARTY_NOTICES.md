# NYX third-party notices

NYX first-party source remains Apache-2.0 under `LICENSE`. The v0.9.0 candidate packages this inventory and full license/notice texts under `assets/third_party/` in the APK; the release gate compares those entries byte-for-byte with the retained inputs. This is an engineering inventory, not exhaustive transitive license clearance or a vulnerability assessment.

## Native code and runtime

The CPU JNI library statically incorporates llama.cpp/ggml, pinned to upstream commit `09338b85e8b230742fa56f93a1d9f0bf006ca2d3`. Its MIT copyright and permission text is retained in `app/src/main/cpp/llama.cpp/LICENSE` and shipped as `LICENSE-llama.cpp.txt`. See `app/src/main/cpp/UPSTREAM.md` for source provenance. Do not edit the vendored tree to update attribution.

NDK r28c `28.2.13676358` provides the packaged `libc++_shared.so` and LLVM toolchain/runtime components. Full, unmodified `NOTICE` and `NOTICE.toolchain` from that installed NDK are retained in `third_party/ndk-r28c/` and shipped as `NDK-NOTICE.txt` and `NDK-NOTICE.toolchain.txt`. They include Apache-2.0 with LLVM exceptions and legacy/third-party notices. They conservatively include tools and components not present in the app; inclusion is not a claim that all listed NDK software is linked.

Upstream nlohmann/json and cpp-httplib licenses are retained and shipped for source completeness. NYX disables upstream common/app/server/tools/examples, network/subprocess components, OpenMP and optional GPU backends. These retained upstream components are not claimed as enabled NYX runtime features. Native notice inputs must be re-reviewed when the source pin, NDK, linkage or backend options change.

## Managed dependencies

Pinned direct production dependencies are declared in `app/build.gradle.kts`: AndroidX Core 1.15.0, Activity 1.10.0, Lifecycle 2.8.7, Navigation 2.8.5, Compose BOM 2024.12.01 and its UI/Material/Foundation modules, Room 2.8.5, DataStore 1.1.1, Kotlin coroutines 1.9.0 and OkHttp 4.12.0. AndroidX is copyright the Android Open Source Project; Kotlin and coroutines are copyright JetBrains and contributors; OkHttp is copyright Square and contributors. Their upstream projects use Apache-2.0. Kotlin standard-library resolution is governed by AGP built-in Kotlin 2.2.10, not a new independent runtime pin. The full Apache-2.0 text is shipped as `LICENSE-Apache-2.0.txt`.

Room compiler/KSP, AGP, Gradle, Compose compiler and the standalone host Kotlin compiler are build tools, not downloaded app executable plugins. JUnit 4.13.2, org.json 20240303 and MockWebServer 4.12.0 are host test dependencies; AndroidX Test 1.2.1 and Espresso 3.6.1 are instrumentation dependencies, not production features. Do not infer their inclusion in the primary APK merely from source references.

The verification build emits `:app:dependencies --configuration debugRuntimeClasspath` to retain the actual resolved transitive inventory. Review upstream POM/license/NOTICE material for changed artifacts before distributing them. This candidate does not bootstrap a complete independently trusted dependency checksum database and does not claim exhaustive transitive copyright, license or security coverage. Existing Gradle metadata-license resource exclusions are not relied on to deliver the native notices.
