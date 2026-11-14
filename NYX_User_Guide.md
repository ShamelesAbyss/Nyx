# NYX User Guide — v0.9.0 Project Workbench

This legacy filename is preserved for existing links. Current documentation is in [USER_GUIDE.md](USER_GUIDE.md); see the [official release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.9.0), [release notes](RELEASE_NOTES_v0.9.0.md), [implementation report](V089_IMPLEMENTATION_REPORT.md) and [test results](TEST_RESULTS.md).

v0.9.0 chooses a planning workload from observed model capabilities. Direct mode starts short text-only goals without a planning call; incremental mode requests at most two validated steps and extends only after local evidence passes; guided mode is inference-only. Reasoning streams are reported as progress and never mixed into answers or plans. Package com.nyx.ai, versionCode 22 / versionName 0.9.0; ARM64 debug-signed, min API 26, target 37. 1,034 JVM tests across 107 classes passed; physical-device and real-provider validation remain outstanding.
