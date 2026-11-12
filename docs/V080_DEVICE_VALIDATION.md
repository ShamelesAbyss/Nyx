# NYX v0.8.0 — Physical-Device Evidence & Validation Checklist

Status: implementation and JVM verification complete; publication build. This document separates observed device evidence from checks that still need a physical run.

## Observed device evidence

The owner’s Android 17 device runs Debian 13.7 “Trixie” under Termux/PRoot. During v0.8.0 development, NYX reported:

```text
No APT deb sources were found. APT sources: 0.
```

The guest nevertheless had enabled Deb822 repositories in `/etc/apt/sources.list.d/debian.sources` for `trixie`, `trixie-updates`, and `trixie-security`. `apt-cache policy` reported `openjdk-21-jdk-headless` as `21.0.13+9-1~deb13u2`, `default-jdk-headless` as `2:1.21-76`, and no candidate for `openjdk-17-jdk-headless`.

This is genuine device evidence of the failure and of the repository/candidate state. It is not evidence that the fixed APK completed installation or continued the objective successfully.

## Implemented behavior to validate

| Area | Expected result | Status |
| :--- | :--- | :--- |
| Deb822 recognition | `/etc/apt/sources.list.d/debian.sources` is counted; disabled or non-`deb` stanzas are ignored | Automated regression tests pass |
| JDK resolution | Trixie selects the available `openjdk-21-jdk-headless` candidate | Automated resolver tests pass |
| Install authorization | Only planner-generated, policy-approved packages are installed | Automated policy/bootstrap tests pass |
| Executable verification | `java` and `javac` resolve as guest-native after installation | Logic tested; device run pending |
| Objective continuation | The original objective resumes with its task graph and checkpoints after successful provisioning | Logic tested; device run pending |
| Live Worker Console | Chat and Autonomy open the expandable timeline; pause/resume/cancel work | Build-tested; device run pending |
| Workspace reuse | A saved verified workspace is reused after process restart | Logic tested; device run pending |
| Workspace deletion | Exact direct child is removed; root, symlinks, traversal, active workspace, and unrelated files are refused | Command-generation and parser tests pass; device run pending |

## Physical-device checklist

1. Install `NYX-v0.8.0.apk` over a prior build with the same debug certificate, or perform a clean install after backing up data.
2. Configure Termux `allow-external-apps=true`, restart Termux, and grant `com.termux.permission.RUN_COMMAND`.
3. Verify the Debian guest in **Autonomy → Terminal** and save an existing managed workspace. Restart NYX and confirm the workspace is reused without a new numbered folder.
4. Run **Prepare Kotlin env**. Confirm the plan shows `openjdk-21-jdk-headless`, the approval/policy path is correct, and installation reports real package output.
5. Confirm `java -version` and `javac -version` execute as Debian-native tools, then confirm the original objective continues rather than stopping at the provisioning step.
6. Open **Live Activity** from Chat and Autonomy. Confirm events update, rows expand, long output is bounded, and Pause/Resume/Cancel work.
7. Create a disposable test workspace, confirm **Delete** lists the exact path and removes only that directory. Confirm the parent root, Ollama models, Debian installation, and an unrelated sibling remain.
8. Repeat the fixed install on a fresh Debian guest if possible; record whether APT locks, repository failures, storage limits, or package incompatibilities are classified correctly.
9. Run the instrumentation APK if a device is available. No device execution is claimed in the publication evidence.

## Known limitations

- The successful fixed-build install and objective continuation are not yet device-verified.
- New console rendering, share/export, workspace deletion, and startup reuse are not device-verified.
- End-to-end calculator verification through the full autonomous objective path remains unfinished.
- Native GGUF inference, memory pressure, thermal behavior, Termux IPC lifetime, Keystore, notifications, foreground-service restart, Room migration, and fold/unfold layout remain device checks.
- The release is debug-signed; it is not a Play Store or production-signed artifact.
