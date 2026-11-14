# Bedrock build and verification contract

## Environment and ownership

Use the root Gradle 9.3.1 wrapper, AGP 9.1.1, built-in Kotlin/Compose compiler 2.2.10, KSP 2.3.6, Room 2.8.5, Java 21 (JVM 17 bytecode), Python 3 for existing build-side checks, and a real standalone `kotlinc` on PATH. Android SDK components are exactly `platforms;android-37.0`, `build-tools;36.0.0`, `ndk;28.2.13676358` and `cmake;3.22.1`. SDK 37 package failure is a blocker, not permission to downgrade. Do not install or edit shared SDKs/toolchains as a build workaround.

Only the coordinating release owner runs Gradle after every source owner finishes. No concurrent Gradle, CMake output sharing, publication, installation, AAB, remote settings, credential change, or signer replacement. Combined development/Workbench owners supply their own Kotlin regression contracts; this workflow builds/tests the resulting app but does not implement a second engine or establish Android end-to-end acceptance.

## Local commands

Set Java/SDK/compiler paths for your actual host. In the campaign sandbox they are:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/Android/Sdk
export PATH=/tmp/nyx-toolchain-tests/kotlin-home/bin:$JAVA_HOME/bin:$PATH
export PYTHONDONTWRITEBYTECODE=1
bash tools/build_release.sh --preflight
# Only after the shared-workspace build barrier opens:
bash tools/build_release.sh --build /absolute/new/verification-directory
# Alternatively the parent may perform its own one coordinated clean build:
./gradlew --no-daemon --max-workers=2 --console=plain clean testDebugUnitTest assembleDebug assembleDebugAndroidTest \
  :app:dependencies --configuration debugRuntimeClasspath
bash tools/build_release.sh --verify-built /absolute/new/verification-directory
# After actual successful build and regression acceptance, package locally:
bash tools/build_release.sh --package /absolute/new/delivery-directory
```

All output directories must be new; no helper overwrites a release directory. A failed partially written directory remains failure evidence, not a completed delivery. Never put external output under a baseline tree, model storage or a signing directory. Use an ignored `verification-output/` child for in-repository scratch outputs, or a new external path. Packaging after source changes must be repeated into another new directory. The package mode compares every archive member with the current safe working-tree source and an internal complete `SOURCE-MANIFEST.sha256`; it is not a tagged-tree or byte-identical-APK claim. Existing Git history is not bundled or rewritten. A source checkout with Git metadata is required by this source inventory/packaging gate.

The first-party checker composes these modes independently:

```bash
python3 tools/check_sdk_policy.py --self-test
python3 tools/check_sdk_policy.py --json
python3 tools/check_release_integrity.py --self-test
python3 tools/check_release_integrity.py --check-repository
python3 tools/check_release_integrity.py --verify-artifacts \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --android-test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --sdk-root "$ANDROID_HOME" --report-dir /absolute/new/artifact-checks
python3 tools/check_release_integrity.py --verify-source /absolute/delivery/NYX-Source-v0.9.0.zip
python3 tools/check_release_integrity.py --verify-delivery-dir /absolute/delivery
```

If a parent adds a separately generated `NYX-v0.9.0-acceptance.zip`, include its digest in the final checksum manifest and pass `--extra-delivery-file NYX-v0.9.0-acceptance.zip` to the delivery check. Otherwise the manifest covers exactly the primary APK, instrumentation APK and source ZIP. It never hashes itself. Versioned delivery bytes must match the built/verified APKs; packaging enforces that copy path, while independent artifact verification may also be rerun on delivered APKs.

## Enforced evidence and limits

Preflight validates the root wrapper JAR and pinned official distribution checksum, literal candidate identity/code/name, SDK guards, retired root-bundle absence, source-path/private-key/GitHub-token markers, funding JSON/license and exact notice assets. Current first-party content is scanned narrowly; vendor content and Git history are not a full secret audit. The one retained public vendor `.env.example` is an explicit example exception, not a blanket credential exemption. Public maintainer contact and the upstream Keynote `.key` document are not classified as leaked signing keys.

After actual assembly, `aapt2` verifies the primary APK independently as `com.nyx.ai`, code 23/name 0.9.0, min 26/target 37. Instrumentation identity is `com.nyx.ai.test` and its manifest must bind to `com.nyx.ai`; test-package version values are not assumed to inherit the application's values. `apksigner` verifies both signatures and equality against the established local debug certificate SHA-256 `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`. The existing sandbox key is `/home/ubuntu/.android/debug.keystore`; it remains outside the repository/archive and is never bundled.

The primary APK must contain uncompressed ARM64 `libnyx_llama.so` and `libc++_shared.so`, no other native ABI, and exact full notice assets. Every packaged `.so` is checked for ELF64/AArch64, valid LOAD headers and at least 16 KB power-of-two segment alignment with matching offset/address congruence. Real NDK `llvm-objdump -p` output is retained. Real `zipalign -c -P 16 4` must succeed for both APKs. A ZIP check alone is not ELF validation; neither alignment check proves Android 16 KB runtime success.

The existing metadata checker is retained and still accepts `--apk` or `--no-apk` with delivery names/release documents; basename agreement alone is not artifact integrity. The extended self-test has explicitly synthetic APK/ELF and tool-output parser cases plus a real nonzero subprocess failure. They prove fail-closed gate behavior, not signature generation, Android installation or real inference. The clean suite provides actual Kotlin compilation/native assembly. No emulator, model download or real public-network opt-in is run in deterministic CI; device handoff/upgrade/permissions/lifecycle and real providers remain separate acceptance procedures.

Dependency resolution is retained as `debugRuntimeClasspath` evidence. Direct pins, full native license inputs and Apache-2.0 are documented in `THIRD_PARTY_NOTICES.md`; this is not exhaustive transitive license clearance, dependency checksum bootstrap or vulnerability scanning. Source reproducibility means recorded pinned inputs and byte-compared source archives, not byte-identical outputs across hosts/signing identities.

## Prepared GitHub Actions and later maintainer setup

`.github/workflows/verify.yml` has one `ubuntu-24.04` job named **Bedrock verification**, no matrix/schedule/emulator/publication. It uses `contents: read`, nonpersistent checkout credentials, superseded-ref cancellation, a 45-minute ceiling, basic Gradle caching with main-only writes, no dependency submission or Build Scan, and seven-day test/artifact-check reports. It deliberately does not upload APKs or source bundles. CI uses an ephemeral debug signer only; `--ci-ephemeral-signer` still requires valid signatures and app/test signer equality, but it is not a distribution/upgrade gate. Package mode rejects that flag.

Action commit IDs were resolved from official repositories and the pinned action definitions validated in the source audit on 2026-11-14: checkout v6 `d23441a48e516b6c34aea4fa41551a30e30af803`, setup-java v5 `b6effb05e454b25005698d916606bdc6ffcbf961`, gradle setup v6 `8da46b539cd8fe6ab27ca75128919a18d757604c`, upload-artifact v5 `330a01c490aca151604b8cf639adc76d48f6c5d4`. Their supported input names are used; setup-gradle validates wrappers before any Gradle execution. The root wrapper checksum is also checked locally, not only trusted to the action.

`tools/setup_ci_kotlin.sh` installs official Kotlin compiler 2.2.10 into a new runner-temp directory. Its hard-pinned ZIP SHA-256 `302d1d8e671e5c3207e6ed62ff11fb555462a628e22a1158254dcaaf7e7394bc` was retrieved from JetBrains' official release checksum. The local cached compiler fixture is not assumed to exist on hosted CI. First setup needs HTTPS access to official SDK/Gradle/JetBrains/Maven repositories; unavailable required downloads fail rather than invent passing tests.

GitHub-hosted execution is **UNVERIFIED** until an authorized future run. Local shell/YAML validation and helper regressions do not establish a hosted pass. A maintainer must later enable Actions, permit the pinned actions/current runner runtime, run the workflow on a trusted change, confirm its actual check context, then configure a main-branch ruleset/protection requiring **Bedrock verification**, pull-request review and up-to-date checks as appropriate. Do not configure required checks based only on this unexecuted workflow name. No repository setting, credential, remote, push, tag or release was changed by preparation.

## Official references

[1]: https://gradle.org/release-checksums/ "Official Gradle checksums"
[2]: https://developer.android.com/build/releases/agp-9-1-0-release-notes "AGP 9.1.1 toolchain compatibility"
[3]: https://developer.android.com/guide/practices/page-sizes "Android 16 KB ZIP and ELF validation"
[4]: https://github.com/gradle/actions/blob/8da46b539cd8fe6ab27ca75128919a18d757604c/setup-gradle/action.yml "Pinned Gradle action definition"
[5]: https://github.com/actions/checkout/blob/d23441a48e516b6c34aea4fa41551a30e30af803/action.yml "Pinned checkout definition"
[6]: https://github.com/actions/setup-java/blob/b6effb05e454b25005698d916606bdc6ffcbf961/action.yml "Pinned Java setup definition"
[7]: https://github.com/actions/upload-artifact/blob/330a01c490aca151604b8cf639adc76d48f6c5d4/action.yml "Pinned artifact action definition"
[8]: https://github.com/JetBrains/kotlin/releases/tag/v2.2.10 "Official Kotlin compiler release"
[9]: https://github.com/JetBrains/kotlin/releases/download/v2.2.10/kotlin-compiler-2.2.10.zip.sha256 "Official Kotlin compiler checksum"
[10]: https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions "GitHub secure workflow guidance"
