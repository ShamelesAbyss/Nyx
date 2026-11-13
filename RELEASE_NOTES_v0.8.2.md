# NYX v0.8.2 — Project Unshackle

**Project Unshackle repairs a failure at the boundary between planning and execution.** NYX could inspect broad objective text too early, infer that unrelated developer toolchains were required, and block ordinary shell work on unavailable Android SDK, Gradle, or package dependencies. v0.8.2 makes dependency preparation follow the persisted tasks NYX actually intends to execute—on both the first run and a reviewed resume.

## Dependency workflow: before and after

**Before:** the objective service could prepare dependencies before a concrete task graph existed. Broad wording or negated mentions of Android, Gradle, or other technologies could be mistaken for a build requirement. The resulting missing tool or package candidate could prevent an otherwise ordinary shell objective from starting.

**After:** NYX plans and persists the task graph first. Immediately before executable terminal/development tasks run, a shared gate inspects their task text and selects only a recognized project toolchain. Shell automation, inference-only work, verification-only work, and explicit negations skip unrelated development dependency checks. If a toolchain is needed, the existing trusted planner and authorization path handles it. A dependency failure preserves the task graph/checkpoints and leaves the objective in `needs_attention`; resuming checks the same persisted work through the same gate before execution continues.

## What changed

- **Corrected project-kind inference.** Nearby negation is respected while affirmative Android, Kotlin/JVM, C/C++, Node, and Python project terms remain recognized. Android application/APK development continues to select the Android toolchain.
- **Task-scoped preparation.** The pre-execution gate runs after planning and also on objective resume. Broad objective prose alone no longer triggers a toolchain bootstrap.
- **Fewer unrelated requirements.** Debian JDK resolution is skipped for non-JVM project kinds. Git is optional for local build/run workflows unless a planned task explicitly needs repository operations.
- **Regression coverage.** New and expanded tests cover shell automation, negated toolchain instructions, Android development, shell-gate bypass, resumed Kotlin work, and avoiding non-JVM JDK probes. The complete JVM suite passes with 702 tests, zero failures, zero errors, and zero skips.
- **Vendored source presentation.** `.gitattributes` labels `app/src/main/cpp/llama.cpp/` as vendored for GitHub language statistics. The bundled llama.cpp source and licenses were not changed.

## Safety and compatibility

The package remains `com.nyx.ai`, versionCode 15, versionName 0.8.2, min API 26, compile/target API 37, with ARM64 native libraries. The established debug signing certificate is retained for compatible in-place upgrades.

Workspace validation, environment checks, install-package authorization, trusted repository candidates, post-install executable verification, task checkpoints, and conservative recovery remain in force. Install commands still come from the trusted dependency planner—not arbitrary model-authored shell text. No root escalation, model/workspace/user-data deletion, credential changes, or unrelated application features were introduced. NYX remains a bounded local-first assistant; this release does not provide unrestricted device access or guarantee autonomous software-engineering success.

## Verification and limitations

The clean build completed successfully: **702 JVM tests passed**; the ARM64 debug APK and instrumentation APK were assembled; APK package identity, version, signer, SHA-256, and 16 KB alignment were verified. The instrumentation APK was compiled but **not executed**. No physical Android device was attached, so actual Android/Termux/PRoot package installation, dependency continuation, and resumed objective execution remain to be confirmed on hardware. Passing JVM tests and a successful APK build do not establish successful on-device autonomy.

This is an ARM64-only, debug-signed development release—not a production-signed Google Play build. No AAB or Google Play submission is included. See [`TEST_RESULTS.md`](TEST_RESULTS.md) for hashes and exact build evidence, and [`V082_IMPLEMENTATION_REPORT.md`](V082_IMPLEMENTATION_REPORT.md) for implementation details.
