# NYX v0.9.1 — Project Ignition verification report

**Release status:** Published stable release
**Source commit:** `538b73adb528bd0ddfafbfa17225b7d76ec01d3f`
**Hosted verification:** https://github.com/ShamelesAbyss/Nyx/actions/runs/43631095524 — passed
**Package:** `com.nyx.ai` · versionName `0.9.1` · versionCode 24
**SDK:** min 26 · compile/target 37
**ABI:** `arm64-v8a` only

## Signing continuity

The original NYX debug keystore was found and used for the release build. Its certificate SHA-256 is:

`aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`

This exactly matches the certificate used by previous NYX releases. The primary APK is therefore eligible for in-place updates over builds signed with the same certificate. No signing material is included in the repository or release.

## Verification evidence

- Clean build: **BUILD SUCCESSFUL in 2m 50s**.
- Full JVM suite rerun with real-network acceptance enabled: **BUILD SUCCESSFUL in 2m 6s**.
- Tests: **1,198 passed across 133 suites; 0 failures, 0 errors, 0 skipped**.
- Both the application and instrumentation APKs assembled.
- APK identity, SDK levels, package name, ARM64 JNI packaging and third-party notices verified.
- 16 KB ZIP alignment verified for both APKs.
- Hosted CI passed on the exact source commit.

## Artifact hashes

| Artifact | SHA-256 |
| :--- | :--- |
| `NYX-v0.9.1.apk` | `40680ebfeac10953a41e8142d5a2e5138fcdc3d423ad6cd45fef81235b44200f` |
| `NYX-v0.9.1-androidTest.apk` | `eccbc53bb3839b9f5f2e172421023bce6aeac96b67836ec3e66bfeaa8e85319b` |

The release `NYX-v0.9.1-SHA256SUMS.txt` also covers `NYX-Source-v0.9.1.zip` and this report. The source archive is built from the exact release commit.

## Remaining limitations

Physical Android instrumentation, real-device Ollama/Termux/PRoot execution, foldable rendering, installed-data upgrade, and real-provider acceptance remain **UNVERIFIED**. The instrumentation APK is compiled but not executed on hardware. Application-level permission restrictions are not an operating-system sandbox. This is an ARM64 debug-signed development release, not a production-signed or Google Play artifact. No AAB was generated.
