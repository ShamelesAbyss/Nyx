# NYX v0.8.9 — Project Workbench 🌑🛠️

**Local intelligence. Autonomous work. Tangible results.**

NYX is moving beyond answering questions and executing isolated tasks. Project Workbench introduces persistent, verified work products that can be created, retained, inspected, and handed back to the user on Android. This is a new capability layer on top of the existing execution engine—not merely another planning update.

**Published release:** v0.8.9 · versionCode 22 · `com.nyx.ai` · ARM64 · Android 8.0+ · debug-signed with the established certificate.

## 🌑 Introducing Project Workbench

Each approved Workbench objective has its own app-private workspace for selected documents, retrieved source evidence, generated files, and an atomic manifest. Results survive ordinary reopen; interrupted tasks retain the existing checkpoint and review protections. NYX connects **research → retained evidence → file creation → local verification → user-controlled handoff** rather than leaving the deliverable inside a chat transcript.

Open **Autonomy → Workbench**, select an existing agent/model, describe the finished file, choose its basename and format, and authorize private artifact tools for that objective. Optionally select a reference document and approve exact HTTPS URLs. Reopening a previous objective reads its manifest and rechecks files; it does not silently resume execution or expand permission.

## 📁 Real Deliverables

Workbench produces actual **Markdown, plain text, CSV, JSON, and static HTML** files. ZIP bundles include explicitly selected verified artifacts, an artifact-only manifest, and a README—not private imports or source files.

Kotlin validates strict UTF-8, bounded payloads, structured JSON, consistent quoted CSV fields, a conservative static HTML subset, and ZIP paths, sizes and CRCs. Artifacts have objective ownership, byte counts, SHA-256 digests and durable records. Conflicting writes fail instead of silently overwriting earlier work; matching writes are idempotent. Path traversal, symlinks, reserved names and quota violations are rejected.

HTML is intentionally restricted: no scripts, event handlers, active URLs or external resources. PDF, XLSX and PowerPoint support is not implemented. File validity is not factual correctness.

## 🌐 Research With Traceable Sources

Research retrieves multiple explicitly selected public HTTPS URLs through the existing guarded network layer. It retains readable text and provenance: original/final URL, title where available, retrieval time, byte count and checksum. Results can ground reports and structured outputs with traceable references.

Exact URL approval, existing autonomy/network policy, public DNS/private-address checks, same-origin selected redirects, bounded concurrency, body budgets and per-host pacing still apply. Discovered links are candidates only; they are not followed automatically. No credentials, cookies, login sessions or authenticated browser authority are imported.

Retrieved pages and selected documents are **untrusted data, never instructions or grants**. Marker escaping, scope intersection and separate authorization prevent source text from acquiring tools or authorizing new URLs. Selected TXT/Markdown/CSV/JSON documents are copied through Android's picker into bounded private storage; there is no phone-wide document scan.

## 📱 Android Preview, Save & Share

Workbench shows retained files, sources, imports and validation status. Preview is inert selectable text—including HTML source, not an active web page. **Save as…** uses Android's Storage Access Framework. **Share** stages digest-checked artifact bytes through a narrow FileProvider URI with read permission.

The provider exposes only artifact handoff snapshots, never workbench sources, imports, private manifests, models or credentials. Sharing has bounded cache storage and refuses fresh-capacity exhaustion instead of deleting a recipient's current file. Saving and sharing require separate user actions and recheck the registered artifact digest.

These Android interfaces compile and their byte/lifecycle boundaries have JVM tests. Physical-device preview, SAF saving, sharing, foldable behavior and Android interruption recovery remain outstanding.

## 🤖 Autonomous Workflow Integration

Workbench uses the existing **AgentRunner**, model-aware planning, task scheduler, checkpoints and evidence verifier. Native GGUF, Ollama and configured OpenAI-compatible endpoints continue through the common provider layer. Observed capabilities choose Direct, Incremental, Full or Guided strategies; unknown capability does not become permission.

Artifact objectives require safe tool-capable planning. Guided inference-only models cannot execute privileged artifact workflows or satisfy file requests with prose. Workbench-specific objectives deliberately use a narrower tool set; normal authorized Termux/Debian development objectives retain their existing behavior. Combining terminal development and Workbench deliverables within one objective is future integration work.

## 🛡️ Evidence-Backed Completion

**A model saying “done” is insufficient.** Required files must exist as registered, currently verified disk artifacts with matching successful-task write witnesses. The local verifier checks objective/task evidence, paths, bytes and checksums. Research-backed requests also require successful retained bytes for the selected sources.

A malformed action, unsupported format, missing source, stale digest or unverified output is a blocker—not a successful deliverable. Source provenance and citation traceability do not prove every statement or citation is semantically correct; generated work still needs review.

## ⚡ Local-First Performance

Hardware-aware Eco, Balanced, Performance and Adaptive modes, thermal/battery/memory ceilings, patient inference profiles and existing independent terminal-operation scheduling are preserved. At least three independent authorized operations are supported when resource, mode and conflict policies admit them; host tests demonstrate overlapping processes, not Android concurrency.

Workbench uses bounded context, storage and network budgets. No quantitative speed, battery or completion-rate improvement is claimed. The previously observed slow Ollama planning remains unconfirmed resolved on hardware.

## 🧪 Engineering Verification

- **Clean build:** `clean testDebugUnitTest assembleDebug assembleDebugAndroidTest`, completed in **1m 53s**.
- **1,034 JVM tests across 107 classes; zero failures, errors or skips.** Includes 100 Workbench tests and six actual AgentRunner-to-Workbench lifecycle tests using deterministic providers.
- Real filesystem writes, independent JVM quota races, abrupt `Runtime.halt` recovery, reopening, invalid formats, forged exports, HTTP fixtures, cancellation, authorization and source-provenance tests.
- Three public HTTPS sources retrieved on Linux: Example Domain, IANA Example Domains and RFC 8259. Deterministic extraction created a cited Markdown comparison and validated CSV, JSON and static HTML, with retained/reopened checksums.
- Existing Kotlin/JVM project fixtures executed. Instrumentation APK compiled but **not executed**.
- Package `com.nyx.ai`, versionName `0.8.9`, versionCode `22`, minimum API 26, compile/target API 37, ARM64-only.
- Established signing certificate preserved; APK signature and **16 KB ZIP alignment passed**. Vendored llama.cpp remains unchanged.

APK SHA-256: `a258085bbe4f5bbf8bf0d80fcb7a4613512770c7e0c49a2b7a67ff6bc75194bf`.

The acceptance bundle is **host-generated deterministic evidence**, not a demonstration of Android hardware or real-model research synthesis. It contains only generated Markdown/CSV/JSON/static HTML plus their checksum list; public-source excerpts are deliberately short, attributed and traceable. No private source files or imported documents are included.

## 📋 Known Limitations

Real Ollama-driven Workbench execution on Android remains unverified. Public-source acceptance used deterministic extraction on Linux, not model-driven synthesis. Android preview/save/share, folded/unfolded Workbench usability and Android interruption recovery need physical-device trials.

Generic web search, JavaScript browser interaction, authenticated web automation, robots-aware navigation and PDF generation/extraction are not implemented. Guided inference-only models cannot perform artifact workflows. Terminal development and Workbench are not fully combined in one objective. File validity and source checksums do not prove factual accuracy. No quantitative speed or battery gain is demonstrated.

The APK is debug-signed and is **not a production Play Store release**. No AAB or Play publication occurred. Back up important data before installing; existing installations with the established certificate can update in place without an intentional data reset. No destructive Room migration or cleanup of user models, credentials or workspaces was introduced.

## 🧭 Toward 1.0—without claiming readiness

Workbench supplies a meaningful retained-output foundation. The next reliability requirements are physical-device save/share and interruption validation, at least two real-model workflow trials, better citation/semantic review, larger-document retrieval, and safer multi-file/terminal integration. These remain requirements, not completed features or a declaration of 1.0 readiness.

See the [implementation report](https://github.com/ShamelesAbyss/Nyx/blob/v0.8.9/V089_IMPLEMENTATION_REPORT.md), [test results](https://github.com/ShamelesAbyss/Nyx/blob/v0.8.9/TEST_RESULTS.md), [user guide](https://github.com/ShamelesAbyss/Nyx/blob/v0.8.9/USER_GUIDE.md) and [build instructions](https://github.com/ShamelesAbyss/Nyx/blob/v0.8.9/BUILD.md).
