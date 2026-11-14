# NYX User Guide — v0.8.8 Project Insight

This legacy filename is preserved for existing links. Current documentation is in [USER_GUIDE.md](USER_GUIDE.md); see the [official release](https://github.com/ShamelesAbyss/Nyx/releases/tag/v0.8.8), [release notes](RELEASE_NOTES_v0.8.8.md), [implementation report](V088_IMPLEMENTATION_REPORT.md) and [test results](TEST_RESULTS.md).

v0.8.8 chooses a planning workload from observed model capabilities. Direct mode starts short text-only goals without a planning call; incremental mode requests at most two validated steps and extends only after local evidence passes; guided mode is inference-only. Reasoning streams are reported as progress and never mixed into answers or plans. Package com.nyx.ai, versionCode 21 / versionName 0.8.8; ARM64 debug-signed, min API 26, target 37. 934 JVM tests across 96 classes passed; physical-device and real-provider validation remain outstanding.
