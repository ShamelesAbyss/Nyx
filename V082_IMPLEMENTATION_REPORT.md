# v0.8.2 Project Unshackle — Implementation Report

**Release status:** Published as GitHub release v0.8.2 “NYX v0.8.2 — Project Unshackle.” Physical-device autonomy validation remains pending.

## Scope delivered

The dependency gate was removed from the pre-planning portion of `ObjectiveExecutionService`. `ObjectiveExecutionEngine` now calls an injected pre-execution gate after it persists a new task graph and again before resuming a persisted graph. When preparation is denied or unsuccessful, the service records a dependency event and leaves the objective in `needs_attention`; a resumed graph is not reset before the check.

`ObjectiveDependencyScope` examines only persisted `terminal` and `development` tasks, using their title, instructions, and verification text. It skips dependency bootstrap for ordinary shell tasks, inference-only work, verification-only work, and generic/negated toolchain mentions. `DependencyPlanner.inferProjectKind` recognizes affirmative project terms while excluding nearby negated mentions. Affirmative Android application/APK development still selects `ANDROID_APP`. The planner now treats Git as optional for local work; Debian JDK discovery is performed only for project kinds whose planned tools need Java.

Regression tests cover shell automation, explicit negated instructions, Android development, bypass of the bootstrap callback for shell tasks, resumed planned Kotlin work, JDK-probe avoidance for non-JVM projects, and existing dependency-plan behavior. `.gitattributes` classifies only the bundled llama.cpp subtree as vendored; its sources and licenses are unchanged.

## Preserved boundaries

The application ID remains `com.nyx.ai`; versionCode is 15 and versionName is 0.8.2. Existing signing identity, Termux/PRoot environment and workspace validation, install authorization, trusted package planning, post-install verification, checkpoints, and recovery semantics remain intact. No user data, credentials, models, or workspaces were deleted or modified. No unrelated functionality, AAB, or Play submission was performed. The debug APK, instrumentation APK, clean source archive, and SHA-256 manifest were published as release assets.

## Verification

The final clean build, test count, package/version/ABI identity, signer certificate, alignment, artifact SHA-256 values, and source-archive checks are recorded in `TEST_RESULTS.md`. The instrumentation APK is compiled only; it was not run. No physical Android-device verification is claimed. See `RELEASE_NOTES_v0.8.2.md` for the release summary and `BUILD.md` for reproduction instructions.
