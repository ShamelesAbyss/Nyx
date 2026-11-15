# NYX Agent Instructions

This file is the entry point for coding agents working on NYX. Read it before changing the repository, then use [docs/CODEX_HANDOFF.md](docs/CODEX_HANDOFF.md) for the full build, signing, verification, and release procedure.

## Non-negotiable repository boundaries

- Application ID is `com.nyx.ai`. Do not rename it.
- The established signing certificate is `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`. A distributable APK must use that exact certificate and a versionCode greater than the previously published version.
- Never commit, print, attach, or transmit a keystore, keystore password, GitHub token, or private key. The signing file belongs in a protected secret store and an encrypted owner-controlled backup.
- Do not create or publish a GitHub Release, tag, or public artifact unless the repository owner explicitly authorizes that specific release.
- Ordinary commits and pull requests must never publish. Publication is only the manual `Release` workflow, which is gated by the protected `release` environment and owner approval.
- Keep the existing `Verify` workflow read-only. Do not move signing secrets or `contents: write` into ordinary verification jobs.
- Do not modify application features, UI, provider support, the execution engine, vendored llama.cpp source, or user data as part of infrastructure or release work.
- Do not weaken, skip, or fabricate tests, integrity checks, signing checks, or security controls to make a build pass.

## Current release baseline

The current published baseline is NYX v0.9.3 — Project PRISM: versionName `0.9.3`, versionCode `27`, package `com.nyx.ai`. The public release is <https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.9.3>. Treat the repository's `app/build.gradle.kts` as the source of truth for the next version; do not reuse a published versionCode.

## Build and verify

Use JDK 21, Android SDK platform `platforms;android-37.0`, Build Tools `36.0.0`, NDK `28.2.13676358`, CMake `3.22.1`, and the repository Gradle wrapper 9.3.1. The detailed setup is in [docs/CODEX_HANDOFF.md](docs/CODEX_HANDOFF.md).

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
bash tools/setup_ci_kotlin.sh "$HOME/nyx-kotlin-2.2.10"
export PATH="$HOME/nyx-kotlin-2.2.10/kotlinc/bin:$JAVA_HOME/bin:$PATH"

bash tools/build_release.sh --preflight
bash tools/build_release.sh --build "$HOME/nyx-build"
bash tools/build_release.sh --package "$HOME/nyx-delivery"
```

`--build` runs the clean JVM suite, assembles the app and instrumentation APKs, and verifies the established signer. `--package` does not rebuild; it copies the verified APKs, creates the source ZIP, release notes, implementation report when configured, verification evidence, and checksum manifest.

## Release path

The only publication path is `.github/workflows/release.yml`, triggered manually with an existing annotated tag and the literal confirmation `PUBLISH`. The `build` and `publish` jobs use the protected `release` environment; the owner must approve the deployment. The signing material is supplied only as the protected environment secret `NYX_KEYSTORE_BASE64`. The publish job alone receives `contents: write`; it uses `gh release create` to upload the binary assets.

Before triggering it, the owner must:

1. Add `NYX_KEYSTORE_BASE64` to the `release` environment secret store.
2. Keep an encrypted private backup of the exact keystore; GitHub is automation storage, not the only backup.
3. Confirm the `release` environment still has the owner as a required reviewer and administrator bypass disabled.
4. Create the annotated tag only after the source and documentation are committed and the exact-commit verification is green.

## Remaining device work

Host tests and emulator checks do not prove physical-device behavior. A release candidate still needs real ARM64 Android hardware acceptance for installation/upgrade over the current release, app-data retention, foldable/keyboard/IME behavior, Termux/PRoot, Ollama, native GGUF inference, SAF/permission recovery, and 16 KB page-size behavior. Record actual evidence; do not convert a host or emulator result into a physical-device claim.

## Where to look

- [docs/CODEX_HANDOFF.md](docs/CODEX_HANDOFF.md) — full setup, build, signing, release, troubleshooting, and device checklist.
- [BUILD.md](BUILD.md) — historical and current build notes.
- [tools/build_release.sh](tools/build_release.sh) — coordinated build and package driver.
- [tools/check_release_integrity.py](tools/check_release_integrity.py) — repository, APK, source, and delivery gates.
- [.github/workflows/verify.yml](.github/workflows/verify.yml) — read-only ordinary verification.
- [.github/workflows/release.yml](.github/workflows/release.yml) — manual, protected publication.
