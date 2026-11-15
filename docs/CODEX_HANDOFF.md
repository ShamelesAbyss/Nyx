# NYX Codex Handoff

This document is the permanent operating guide for Codex and other coding agents working on NYX. It replaces reliance on a particular Manus session. The build is ordinary Gradle/Android tooling; the release path is a manual, protected GitHub Actions workflow.

Read [AGENTS.md](../AGENTS.md) first. The short version is: keep `com.nyx.ai`, keep the established signing certificate, never publish without explicit owner approval, and never put signing material or tokens in the repository or chat.

## 1. Current baseline and scope

| Item | Value |
| :--- | :--- |
| Repository | <https://github.com/ShamelesAbyss/Nyx> |
| Default branch | `main` |
| Current published baseline | NYX v0.9.3 — Project PRISM |
| Application ID | `com.nyx.ai` |
| versionName / versionCode | `0.9.3` / `27` |
| Established signing certificate SHA-256 | `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55` |
| Current APK | Debug-signed ARM64 APK; not a Play/App Bundle build |
| Release workflow | `.github/workflows/release.yml` |
| Ordinary verification | `.github/workflows/verify.yml` |

The v0.9.3 public release and all historical tags and assets are already published. Do not move, delete, or replace them. The next version is whatever `app/build.gradle.kts` declares after an authorized version bump; do not reuse versionCode 27 or an earlier code.

This handoff does not add application features. UI, provider support, execution-engine behavior, vendored llama.cpp source, and user data are outside the scope of infrastructure changes.

## 2. Toolchain

Use the repository's pinned versions, not whatever happens to be installed:

| Component | Required value |
| :--- | :--- |
| Host | Linux x86_64 (Ubuntu 24.04 recommended) |
| JDK | 21 |
| Gradle | wrapper 9.3.1 (`./gradlew`) |
| Android Gradle Plugin | 9.1.1 |
| Kotlin | 2.2.10 (AGP built-in for the app; host compiler for release tooling) |
| KSP | 2.3.6 |
| Android SDK platform | `platforms;android-37.0` |
| Build Tools | `36.0.0` |
| NDK | `28.2.13676358` (r28c) |
| CMake | `3.22.1` |
| Python | 3.12 or newer, standard library only for release checks |
| ABI | `arm64-v8a` only |

Recommended capacity: at least 6 vCPU, 16 GiB RAM, and 25–30 GB free disk. The Android SDK alone is roughly 17 GB and Gradle caches can exceed 3 GB. A low-disk build fails in native linking or packaging, not necessarily with a clear “disk full” message.

## 3. Fresh setup

### 3.1 Install the Android packages

```bash
export SDK_ROOT="$HOME/Android/Sdk"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  'platforms;android-37.0' \
  'build-tools;36.0.0' \
  'ndk;28.2.13676358' \
  'cmake;3.22.1'

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
```

The platform package is `platforms;android-37.0`, not `platforms;android-37`. Accept SDK licenses if the command asks. The hosted GitHub runner already exposes `ANDROID_HOME`; the release workflow installs the same four packages explicitly.

### 3.2 JDK and Kotlin

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

cd /path/to/Nyx
bash tools/setup_ci_kotlin.sh "$HOME/nyx-kotlin-2.2.10"
export PATH="$HOME/nyx-kotlin-2.2.10/kotlinc/bin:$PATH"
export NYX_KOTLINC="$HOME/nyx-kotlin-2.2.10/kotlinc/bin/kotlinc"
```

The setup script downloads Kotlin 2.2.10 over HTTPS and verifies SHA-256 `302d1d8e671e5c3207e6ed62ff11fb555462a628e22a1158254dcaaf7e7394bc` before unpacking. A real `kotlinc` on `PATH` is required by the release preflight; fixture skips are not compiler acceptance.

### 3.3 Network

The first build needs network access to Google Maven, Maven Central, Gradle services, and GitHub. llama.cpp is vendored and needs no source download. Model weights are never build dependencies or APK assets.

## 4. Build and test

The coordinated driver is the preferred path:

```bash
cd /path/to/Nyx

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$HOME/nyx-kotlin-2.2.10/kotlinc/bin:$JAVA_HOME/bin:$PATH"
export NYX_REAL_NETWORK_TEST=1

BUILD_OUT="$HOME/nyx-build-$(date +%s)"
bash tools/build_release.sh --build "$BUILD_OUT"
```

`--build` performs:

1. SDK-policy self-tests and JSON check.
2. Release-checker self-tests and repository identity/source/notice gates.
3. A clean `testDebugUnitTest assembleDebug assembleDebugAndroidTest` build.
4. Independent APK checks: package/version, min/target SDK, exact signer, ARM64 native libraries, 16 KB ZIP alignment, and 16 KB ELF LOAD alignment.
5. A source-archive correspondence check.

The build log is written to `"$BUILD_OUT/build-and-dependencies.log"`. Artifact reports are written to `"$BUILD_OUT/artifact-checks/"`.

`NYX_REAL_NETWORK_TEST=1` enables the two opt-in public-HTTPS acceptance tests. Without it, those two tests are intentionally skipped; a zero-skip claim requires the variable.

### 4.1 Direct Gradle command

The driver ultimately runs:

```bash
./gradlew --no-daemon --max-workers=2 --console=plain \
  clean testDebugUnitTest assembleDebug assembleDebugAndroidTest \
  :app:dependencies --configuration debugRuntimeClasspath
```

`assembleDebugAndroidTest` compiles the instrumentation APK; it does not run physical-device instrumentation. Do not describe a compiled instrumentation APK as executed device acceptance.

### 4.2 Native build

No separate CMake command is required. `assembleDebug` invokes AGP's external-native build with the SDK's CMake 3.22.1 and NDK 28.2.13676358. The native output is `lib/arm64-v8a/libnyx_llama.so` plus `libc++_shared.so`. llama.cpp is pinned in `app/src/main/cpp/UPSTREAM.md`; do not modify the vendored source during infrastructure work.

## 5. Signing identity

### 5.1 Current identity

The current distribution APK is signed with the Android debug identity stored at:

```text
$HOME/.android/debug.keystore
```

On the original Manus host this was `/home/ubuntu/.android/debug.keystore`. The certificate SHA-256 is the established value in section 1. The keystore is not tracked in Git and is excluded from the source archive by `tools/check_release_integrity.py`.

The debug variant has no explicit `signingConfig` block in `app/build.gradle.kts`; AGP's default debug signing configuration reads this file. The release workflow decodes the protected secret into the same path, so the existing build path produces the established signature.

### 5.2 Do not generate a replacement key casually

Android permits an in-place update only when the package name, signing certificate, and non-decreasing versionCode match. Generating a new debug key creates a different certificate and breaks updates. Do not “fix” a signing error by deleting the keystore. If the key cannot be recovered, stop and report that update continuity is broken rather than shipping a new identity silently.

### 5.3 Protected automation secret

The release workflow expects one protected environment secret:

```text
NYX_KEYSTORE_BASE64
```

Its value is the base64 encoding of the exact keystore. Do not put it in a repository variable, a workflow file, a release asset, an issue, or chat. The handoff session's GitHub credential could create the protected environment but could not list or set Actions secrets; the repository owner must add this secret in GitHub:

1. Open **Settings → Environments → release → Environment secrets**.
2. Add `NYX_KEYSTORE_BASE64`.
3. Paste the base64 value only into the secret field.
4. Save, then delete any temporary base64 file.

A safe owner-side command, using a token that has Actions-secrets permission, is:

```bash
umask 077
base64 -w0 "$HOME/.android/debug.keystore" > /tmp/nyx-keystore.b64
gh secret set NYX_KEYSTORE_BASE64 --env release < /tmp/nyx-keystore.b64
shred -u /tmp/nyx-keystore.b64
```

If `gh secret set` returns 403, use the GitHub web UI. Do not retry by putting the key in a different, less-protected location.

### 5.4 Private backup

GitHub Actions secrets are for automation, not the only backup. The owner should keep an encrypted copy in an owner-controlled secrets manager or offline vault:

```bash
# Example: age prompts for a passphrase; do not pass a passphrase on the command line.
age -p -o nyx-debug-keystore.age "$HOME/.android/debug.keystore"

# Restore only into a protected temporary path.
age -d -o /tmp/debug.keystore nyx-debug-keystore.age
chmod 600 /tmp/debug.keystore
```

Equivalent GPG or secrets-manager encryption is acceptable. Never email, chat, commit, or attach the keystore or its password. If a secure transfer needs the owner’s passphrase or a private channel, stop and ask the owner for that channel; do not improvise an unencrypted transfer.

## 6. Verification commands

After a build:

```bash
BT="$ANDROID_HOME/build-tools/36.0.0"

"$BT/aapt2" dump badging app/build/outputs/apk/debug/app-debug.apk | sed -n '1,6p'
"$BT/apksigner" verify --print-certs app/build/outputs/apk/debug/app-debug.apk
"$BT/zipalign" -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
```

The expected results are:

- package `com.nyx.ai`;
- versionName/versionCode matching `app/build.gradle.kts`;
- minSdk 26, targetSdk 37;
- exactly one signer with the established certificate;
- `zipalign` exit code 0;
- ARM64 native libraries present and 16 KB aligned.

The checker provides the combined gate:

```bash
python3 tools/check_release_integrity.py --verify-artifacts \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --android-test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --sdk-root "$ANDROID_HOME" \
  --report-dir "$HOME/nyx-artifact-checks"

python3 tools/check_release_integrity.py --verify-delivery-dir "$DELIVERY" \
  --extra-delivery-file "NYX-${TAG}-RELEASE_NOTES.md" \
  --extra-delivery-file "NYX-${TAG}-VERIFICATION.zip"
```

Do not pass `--ci-ephemeral-signer` for a distribution candidate. That flag exists only for read-only CI verification and cannot package distribution files.

## 7. Packaging

```bash
DELIVERY="$HOME/nyx-delivery-$(date +%s)"
export NYX_IMPLEMENTATION_REPORT=path/to/current-implementation-report.md   # optional
bash tools/build_release.sh --package "$DELIVERY"
```

`--package` does not rebuild. It verifies the existing APKs, then creates:

```text
NYX-v<version>.apk
NYX-v<version>-androidTest.apk
NYX-Source-v<version>.zip
NYX-v<version>-RELEASE_NOTES.md
NYX-v<version>-IMPLEMENTATION_REPORT.md   # only when configured
NYX-v<version>-VERIFICATION.zip
NYX-v<version>-SHA256SUMS.txt
```

The release notes default to `RELEASE_NOTES_v<version>.md`; override with `NYX_RELEASE_NOTES` only when the repository convention changes. The source ZIP contains a generated `SOURCE-MANIFEST.sha256` inventory at its root; it is not a tracked root bundle and is verified against the current working tree.

Verify the manifest:

```bash
cd "$DELIVERY"
sha256sum -c "NYX-v${VERSION}-SHA256SUMS.txt"
```

## 8. Release workflow

### 8.1 Trigger and protection

`.github/workflows/release.yml` is manual only:

```text
workflow_dispatch
```

It has no `push`, `pull_request`, or schedule trigger. It validates an existing annotated tag, requires the literal input `PUBLISH`, and uses the protected GitHub environment `release` for both the build and publish jobs. The environment was created with the repository owner as a required reviewer and administrator bypass disabled.

The owner must still confirm the environment protection in **Settings → Environments → release**. The owner may be asked to approve each gated job. If the environment has no required reviewer, stop and fix the environment before running the workflow.

### 8.2 Job permissions

| Job | Environment | Permissions | Purpose |
| :--- | :--- | :--- | :--- |
| `validate` | none | `contents: read` | Tag/version/notes/confirmation checks |
| `build` | `release` | `contents: read` | SDK setup, signing-secret decode, build, test, package, verify |
| `publish` | `release` | `contents: write` | Download verified artifact, create release, upload binary assets |

Only the publish job receives write permission. The ordinary `Verify` workflow remains read-only and keeps using `--ci-ephemeral-signer`.

### 8.3 Inputs

| Input | Required | Meaning |
| :--- | :--- | :--- |
| `tag` | yes | Existing annotated tag, for example `v0.9.4`; must equal `v` plus Gradle versionName |
| `confirm_publish` | yes | Literal `PUBLISH`; anything else fails validation |
| `implementation_report` | no | Repository-relative report path; validated and bundled if present |

### 8.4 Release procedure

1. Update `app/build.gradle.kts` to the new versionName/versionCode. Keep `com.nyx.ai`.
2. Update `CHANGELOG.md`, `BUILD.md`, `README.md`, and the new `RELEASE_NOTES_v<version>.md`; the checker requires the first versioned heading in the release docs to match the current version.
3. Run the clean build and packaging locally; verify the established certificate and checksums.
4. Commit and push the source and documentation. Do not tag yet.
5. Wait for the exact-commit `Verify` workflow to pass.
6. Create and push an annotated tag on that exact commit.
7. In GitHub, open **Actions → Release → Run workflow**, select the tag, enter `PUBLISH`, and optionally provide the implementation-report path.
8. Approve the protected `release` environment deployment as the repository owner.
9. The workflow builds, signs, packages, verifies, creates the GitHub Release, uploads the binary assets, and re-downloads them to check the manifest.
10. Independently inspect the release URL, tag target, asset list, and checksums.

Do not create the release manually before the workflow. Do not use `--clobber` to replace published assets unless the owner explicitly authorizes a correction.

### 8.5 Manual equivalent

The workflow's final publication command is:

```bash
gh release create "$TAG" \
  --verify-tag \
  --latest \
  --title "NYX $TAG" \
  --notes-file "$DELIVERY/NYX-${TAG}-RELEASE_NOTES.md" \
  "$DELIVERY/NYX-${TAG}.apk" \
  "$DELIVERY/NYX-${TAG}-androidTest.apk" \
  "$DELIVERY/NYX-Source-${TAG}.zip" \
  "$DELIVERY/NYX-${TAG}-SHA256SUMS.txt" \
  "$DELIVERY/NYX-${TAG}-RELEASE_NOTES.md" \
  "$DELIVERY/NYX-${TAG}-VERIFICATION.zip"
```

Add the implementation-report asset when the report exists. `gh` uploads binary files directly; do not base64-encode assets for release upload.

## 9. Troubleshooting

### `ANDROID_HOME` is missing or the SDK is incomplete

Set `ANDROID_HOME` and `ANDROID_SDK_ROOT`, then install the exact packages from section 3.1. Do not lower compileSdk/targetSdk to make an old SDK work.

### `kotlinc` missing or fixture tests skip

Run `bash tools/setup_ci_kotlin.sh "$HOME/nyx-kotlin-2.2.10"` and put its `kotlinc/bin` on `PATH`. A skipped compiler test is not a pass.

### `SDK processing. This version only understands SDK XML versions up to 3...`

This warning can appear when command-line tools and platform packages are from different release trains. It is not a failure by itself; the build must still pass the SDK-policy and artifact gates.

### Version/tag mismatch

The release workflow requires `tag == "v" + versionName`. If Gradle says `0.9.4` and the tag is `v0.9.4.1`, validation fails. Do not edit the workflow to accept a mismatch; fix the tag or the Gradle version.

### Missing release notes

Create `RELEASE_NOTES_v<version>.md` or set `NYX_RELEASE_NOTES` for a local package. The release workflow intentionally refuses to publish without notes.

### Signer mismatch

The checker reports `signer continuity failure`. The build used a different keystore or a new debug key. Do not bypass the check. Restore the exact keystore, rebuild, and verify the certificate. A new signing identity breaks updates.

### Missing `NYX_KEYSTORE_BASE64`

The build job fails before compilation. Add the secret to the `release` environment; do not put the key in the repository. If `gh secret list` returns 403, use the GitHub web UI with an owner account that has Actions-secrets permission.

### Environment does not pause for approval

Open **Settings → Environments → release**. Require the owner as a reviewer and disable administrator bypass. If the environment is missing or unprotected, do not run the publish workflow.

### `gh release create` fails with tag not found

Push the annotated tag first. The workflow checks out the tag and uses `--verify-tag`; it will not create a release against a branch name.

### Asset upload fails

Confirm the publish job has `contents: write`, the tag exists, and the asset files exist in the downloaded artifact. Do not retry with a different token pasted into a workflow. Use the protected `GITHUB_TOKEN` or a properly scoped owner token.

### Disk or memory pressure

Free disk before rebuilding. Do not delete the signing key, source tree, or release evidence. Remove stale build outputs and Gradle caches only when they are reproducible.

## 10. Remaining device-testing requirements

The build and release workflow can prove package identity, signature, ABI, alignment, and host tests. They cannot prove physical-device behavior. Before claiming a release is device-ready, test on real ARM64 Android hardware:

- Fresh install and in-place upgrade over the current published release, with the same certificate and a higher versionCode.
- App-private data retention across upgrade; chat, memory, settings, imported model copies, and credentials.
- Foldable posture changes, keyboard/IME insets, rotation, and large-font layouts.
- Termux/PRoot session lifecycle, Ollama start/stop/recovery, and persistent process ownership.
- Real GGUF import, native load, streaming, cancellation, unload, and memory-pressure refusal.
- SAF/document-picker grants, permission denial and recovery, and share recipients.
- 16 KB page-size device behavior.
- Notification, foreground-service, and background-interruption behavior.

Record the device model, Android build, page size, exact APK hash, certificate, and test result. An emulator or host test is not a physical-device claim.

## 11. Security checklist

- Keystore: encrypted owner backup plus protected GitHub environment secret.
- No keystore, password, token, `.env`, `local.properties`, or `keystore.properties` in Git or source ZIP.
- No signing material in chat, issues, release notes, or artifacts.
- Revoke the old Manus/session GitHub token after handoff; create an owner-controlled token or GitHub App with the minimum required permissions.
- Keep `contents: write` confined to the publish job.
- Keep the `release` environment reviewer requirement and administrator bypass disabled.
- Verify the APK certificate on every release; do not trust a green build alone.
- Do not force-push, move tags, delete releases, or replace assets without explicit owner authorization.

## 12. Short Codex takeover prompt

```text
Read AGENTS.md and docs/CODEX_HANDOFF.md first. NYX is at the published v0.9.3 baseline (com.nyx.ai, versionCode 27) unless app/build.gradle.kts says otherwise. Preserve the established signing certificate aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55; never commit, print, or attach the keystore, passwords, or tokens. Do not modify application features, UI, providers, execution engine, vendored llama.cpp, or user data for infrastructure work. Use JDK 21, SDK 37.0, Build Tools 36.0.0, NDK 28.2.13676358, CMake 3.22.1, and the Gradle wrapper. Build and verify with tools/build_release.sh and tools/check_release_integrity.py. Publication is only the manual, protected Release workflow, which requires owner approval and the protected NYX_KEYSTORE_BASE64 secret; never publish on a commit or pull request. Run non-publishing checks, report actual evidence, and do not create a release without explicit owner authorization.
```
