# NYX v0.8.9 — Project Workbench

Status: official published v0.8.9 release. Baseline is official v0.8.8 Project Insight. Package `com.nyx.ai`, versionName `0.8.9`, versionCode `22`, ARM64, minimum API 26 and compile/target API 37.

## What the user can do

Open Autonomy → Workbench, select an existing model and agent, describe a deliverable, choose its basename and format, and explicitly authorize app-private artifact tools. Optionally select a text document and approve exact HTTPS research URLs. NYX uses the existing objective execution service, planning strategies, task scheduler and verifier to produce actual disk files. It does not create a second agent engine.

The Workbench view reopens objective manifests, displays retained sources/imports and verified artifacts, and offers read-only previews, Save as through Android's Storage Access Framework, and Share through a narrowly scoped content URI. Reopening is not resuming: interrupted objectives require explicit review. Unknown or conflicting artifact writes cannot silently overwrite prior work.

## Gap audit and implementation

Previously, the agent had isolated workspace writes and guarded page retrieval, but not an objective-specific retained deliverable registry, artifact parsers, provenance records, or safe Android export. Workbench adds these layers while retaining native GGUF, Ollama, model diagnostics, patient execution profiles, existing terminal operation scheduling and authorization.

`WorkbenchStore` owns each app-private objective directory, enforces quotas under process/file locks, writes synced temporary files and atomically commits a manifest. It validates paths, rejects symlinks and reserved names, records digests, and conservatively reconciles interrupted files. Missing or corrupt metadata is not used to adopt untrusted files. No Room schema migration or deletion of existing user data is needed.

Limits include 40 artifacts, 8 MiB per artifact, a shared 32 MiB objective payload budget, 60 retained sources, 20 imported documents and a bounded manifest. Workbench tools limit model-produced content further to one million characters. File write is idempotent for matching filename/format/digest; conflicting contents fail rather than overwrite. Directory fsync is best-effort on unsupported filesystems, not a guarantee against every sudden power loss.

`ResearchEngine` reuses the existing guarded network fetcher with exact selected URL authorization, existing autonomy-mode assessment, HTTPS-only public endpoints, credential refusal, DNS/private-address checks, same-origin selected redirects, active call cancellation, bounded concurrency and per-host pacing. It extracts readable text, title/description and available metadata timestamps, returns candidate links without following them, deduplicates normalized content, and commits source bytes and provenance. Default budgets are six sources, 512 KiB per source, 2 MiB reserved response-body budget and two concurrent requests. Headers/TLS overhead are not counted as retained body bytes.

External pages and selected documents are untrusted data. Marker/role delimiter escaping prevents them from becoming trusted prompt sections. They cannot add URLs to authorization, acquire tools or invoke commands. Research objectives cannot finish with invented retrieval: selected sources must have successful retained bytes and matching checksums. This verifies provenance, not every factual claim or citation's semantic accuracy.

`DocumentImporter` accepts only user-selected content URIs or a trusted selected-stream adapter. It validates names, MIME/extension, size, strict UTF-8 and formats; copies bounded content into private storage with source metadata, and closes streams on all exits. Persistent URI grants are unnecessary after the copy. No phone-wide scan or arbitrary model-selected path exists.

`WorkbenchAgentTools` exposes task-bound write/list/read/research/bundle tools. Task scopes permit mutation only for appropriately authorized filesystem/development work. Successful tool evidence has objective/task ownership and actual disk paths/digests. Completion requires the explicitly requested artifact basenames, current verified bytes and matching successful-task write witnesses. Ordinary inference text, an unsupported guided step or a model's DONE declaration is not a deliverable.

Workbench-only objectives intentionally exclude general terminal/network/gateway tools that could bypass their narrower approval. Non-Workbench development objectives retain their existing Kotlin/JVM and three-operation Termux/Debian paths. A single Workbench objective does not yet combine terminal project compilation and app-private artifact tooling.

## Format support matrix

| Format | Generation and validation | Preview/export |
| --- | --- | --- |
| Markdown / plain text | Strict UTF-8, nonblank, bounded disk payload | Inert selectable text; SAF/share |
| CSV | Quoted-field parsing with consistent columns and header | Text preview; SAF/share |
| JSON | Strict syntax/structure plus actual JSON parser; bounded depth/value count | Text preview; SAF/share |
| HTML | Conservative standalone static subset; balanced markup, no scripts/events/active URLs/external assets | Inert source-text preview, not rendered browsing; SAF/share |
| ZIP | Selected verified artifacts, artifact-only manifest and README; full classic ZIP/CRC/path/expanded-size checks | Metadata preview; SAF/share |
| PDF | Unsupported and rejected, not signature-only validated | No generation/extraction claim |
| XLSX / PPTX | Not implemented | No support claim |

ZIP64, encrypted archives, special files, nested artifact bundles and arbitrary filesystem-path bundles are unsupported. Sources and imports are private inputs, not exportable outputs or implicit ZIP entries.

## Android handoff and recovery

Only a registered VERIFIED artifact record with matching byte size, checksum and full metadata can cross the app boundary. SAF saving uses a user-selected content URI and an immutable checked byte snapshot. Sharing copies checked bytes into a dedicated artifact-only cache directory; the non-exported FileProvider exposes only that directory and grants read access for the chosen content URI. It does not expose workbench sources, imported documents, manifests, models or credentials. Share snapshots are bounded to 16 files/32 MiB and retain fresh shares for 24 hours; old snapshots expire, while full fresh capacity fails safely rather than deleting a recipient’s current file.

Artifact/source/import manifests survive ordinary reopen and interruptions. Completed checkpoints and task evidence use existing Room history; uncertain task state is reviewed rather than auto-replayed. File-operation quotas and exact-name conflict rules prevent accidental duplicate mutation. Android foreground-service/resource limits still apply; no promise of indefinite background work.

## Acceptance evidence

Focused core tests exercise real filesystem mutations, independent JVM quota races, abrupt Runtime.halt recovery, symlinks, traversal, invalid formats, corruption, idempotency and forged export records. Research tests use real MockWebServer responses for redirects, private-network refusal, budgets, rate limits, cancellation, deduplication and injection boundaries. Imports exercise real selected byte streams, metadata, strict format rejection and reopening.

A separate opt-in Linux-host trial retrieved `https://example.com/`, `https://www.iana.org/help/example-domains` and `https://www.rfc-editor.org/rfc/rfc8259.txt` through the production guarded research constructor. It retained and reopened source bytes with matching checksums, generated a readable Markdown excerpt comparison with traceable links, valid CSV/JSON/static HTML files, and verified checked handoff bytes. Its summary is deterministic fixture content, not real-model synthesis. It does not prove physical-device/provider behavior.

The clean build passed in 1m 53s with **1,034 JVM tests across 107 classes, zero failures/errors/skips**, including 100 Workbench tests. The APK SHA-256 is `a258085bbe4f5bbf8bf0d80fcb7a4613512770c7e0c49a2b7a67ff6bc75194bf`; signer SHA-256 remains `aa188be93aff8afda48936af2ddc4ee3e9a02a0cc6b4607addb09c8dd3a6bb55`. Package/version and 16 KB alignment passed. The final test/build facts are recorded in TEST_RESULTS.md. Existing Kotlin/JVM development fixtures remain in the complete regression suite. Compiled instrumentation is not executed without Android hardware.

## Provider compatibility and device procedure

No real Ollama model or Android device is attached to the build host. Deterministic HTTP and inference fixtures verify protocol/engine behavior only. The previously observed slow planning remains unconfirmed resolved. Workbench uses bounded context and existing patience/resource policies; no measured speed, battery or completion-rate improvement is claimed.

For Android validation, install in place with the established signer and back up important data. Confirm Models/Performance/Autonomy/Terminal still operate and text fields remain usable folded/unfolded with the keyboard. Select a representative instruct model with observed constrained JSON support, run Models diagnostic, and request `report.md` using three selected URLs. Inspect the source records, task evidence and actual report; save it to a picked Documents location and share it to a text-capable app. Open the exported files independently and compare digests. Repeat CSV, JSON, HTML and a ZIP of selected verified artifacts.

Then select a second model with weaker or unproven structured-output capabilities, rerun diagnostic under its actual identity/context/thread configuration, and repeat the same objective. Confirm guided/inference-only output does not falsely satisfy required files: unsupported actions should request review or fail clearly. Do not force a model-specific strategy to obtain a pass. Record model identifier, server version, options, prompt latency, measured provider throughput where available and actual outcomes.

Select text, Markdown, CSV and JSON documents with the picker, combine one with authorized URL research, interrupt during fetch and after artifact creation, restart and reopen. Verify prior files remain, missing or uncertain operations require review, conflicting writes fail, and canceling the save picker exports nothing. Test malformed/oversized inputs, unauthorized URLs, tampered artifact bytes and attempted traversal. Finally run an existing authorized Kotlin/JVM development objective independently to verify regression behavior on Termux/Debian.

## Outstanding limitations and 1.0 priorities

1. Physical-device SAF/share, foldable usability, foreground-service interruption and at least two real-model Workbench runs remain release blockers for a broad 1.0 reliability claim.
2. Generated prose/citation accuracy still needs human review. Provenance and structural validation do not prove factual or semantic correctness.
3. URL-driven read-only research is implemented; generic search, robots-aware navigation, JavaScript WebView extraction and authenticated browser automation are not. HTTP fetch cannot handle every modern site.
4. PDF extraction/generation and complex Office formats are deferred until parser-backed Android implementations can be verified safely.
5. Import context is bounded; no semantic chunk index or selective retrieval over very large documents. Picker supports one selected import draft per new objective; UI starts one required output, while the engine supports up to eight required files.
6. Workbench terminal-development consolidation, richer multi-file requests and explicit lifecycle cleanup UX remain future integration work. Current private storage is quota-bound, but there is no automatic destructive cleanup of completed objective data.

No v0.9.0 or v1.0 development was undertaken. This release is a meaningful new artifact foundation, not a claim that every model can complete every requested job.
