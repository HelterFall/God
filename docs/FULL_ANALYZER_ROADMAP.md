# God — Full Unity Intelligence Analyzer

## Mission

Turn God from a lightweight folder scanner into an extensible Android-first analysis workstation for Unity game builds. God reads the selected game folder in place through Android Storage Access Framework (SAF), never executes game binaries, and produces machine-readable and human-readable reports suitable for compatibility research in RamenUnity.

God is an analyzer, not a game runtime, emulator, decompiler UI, or guarantee that every proprietary/stripped asset can be decoded.

## Product principles

1. **No manual archive preparation.** Analyze the selected folder directly. Archives found inside the game folder may be inspected in place when supported; extraction is optional and must never be required for the normal workflow.
2. **Streaming, not loading everything into RAM.** Large files are opened through streams and processed in bounded chunks. Per-format parsers declare their read limits and report truncation.
3. **Evidence over guesses.** Every conclusion has a source, confidence level, and parser status. Distinguish confirmed facts, heuristics, unsupported data, and errors.
4. **Version-aware architecture.** Identify Unity version and build layout first; select format handlers by format/version. Unknown versions should still receive safe generic inventory and signature analysis.
5. **Extensible modules.** Each analyzer is independent, testable, and can be added without rewriting the scanner.
6. **Useful output for RamenUnity.** Reports emphasize runtime backend, managed assemblies, native imports, P/Invoke, scene components, input/audio/graphics/network dependencies, and missing native libraries.
7. **Resumable and transparent.** Long scans show progress, can be cancelled, and preserve completed sections if a later parser fails.
8. **No silent omissions.** Every skipped file, SAF permission failure, size limit, unsupported format, and parse error is summarized.

## Report outputs

Generate a report bundle in the user-selected destination:
- `unity-analysis.txt`: readable full report.
- `unity-analysis.json`: versioned structured report.
- Optional split files for very large sections: `files.jsonl`, `managed.jsonl`, `scenes.jsonl`, `assets.jsonl`.
- `manifest.json`: report schema version, analyzer version, Unity version, platform, timestamps, scan coverage, and hashes of report parts.

Avoid holding the complete report in memory. Stream JSONL/TXT sections to output streams and write the manifest last. Keep the existing compact Markdown/JSON export available during migration.

## Analysis modules

### A. Folder inventory
- Recursive paths, entry type, byte size, extension, timestamps when SAF exposes them.
- Counts and total sizes by extension and directory.
- Detect duplicate content with optional streaming SHA-256; hash large files only when enabled.
- Configurable limits, cancellation, inaccessible-path reporting.
- Detect split/sidecar files and group related Unity files.

### B. Unity build identification
- Unity version from multiple independent sources (globalgamemanagers, data headers, player metadata, version strings).
- Target platform, architecture, scripting backend, API compatibility indicators.
- Build layout classification: Mono, IL2CPP, hybrid/uncertain.
- Record each evidence item; never infer certainty from a single filename.

### C. PE and native binary analysis
- Parse PE headers without arbitrary first-1MiB truncation where safe; seek/stream relevant offsets.
- Architecture, sections, CLR header, data directories, imports, exports, delay imports.
- Native dependency graph and missing DLL candidates.
- Detect dynamic-loading strings and known plugin families as evidence, not proof of use.
- Identify unsupported architectures and Windows-only imports relevant to Android porting.

### D. Managed .NET / Mono analysis
- Enumerate assemblies and AssemblyRef dependencies.
- Metadata: namespaces, types, methods, fields, attributes, interfaces, entry points.
- P/Invoke declarations, native library names, calling conventions.
- IL method-body call-site inventory where parser support exists.
- Separate metadata references from actual call sites; static call sites from runtime reachability.
- Prefer a proven metadata library such as Mono.Cecil in an offline-capable analysis component; evaluate Android-compatible packaging and licensing before adoption.

### E. Unity serialized files and scenes
- Detect serialized file versions and TypeTree availability.
- Enumerate assets, object types, paths/IDs, MonoScript links, GameObjects, components, and scene relationships when decodable.
- Resolve MonoBehaviour script references to managed class names.
- Extract component enabled/active state only when the serialized fields are actually decoded.
- Report missing TypeTrees, unsupported versions, and unresolved script IDs explicitly.
- Support both individual level files and split resource sidecars.

### F. Asset/resource inspection
- Identify textures, meshes, audio, animation, materials, shaders, fonts, text assets, bundles, and resource containers.
- Extract metadata by default; optional payload extraction only on user request.
- Avoid dumping raw binary payloads into text.
- Detect compression and encryption indicators without claiming to decode them when unsupported.

### G. Configuration and runtime behavior clues
- Parse JSON/XML/INI and known Unity configuration files with size limits.
- Extract scripting assembly lists, runtime initialization registrations, plugin manifests, input bindings, graphics settings, and package metadata.
- Scan managed/native strings selectively with encoding and provenance.

### H. Compatibility assessment
- Group findings into Android blockers, likely work items, optional integrations, and unknowns.
- Evidence-backed categories: graphics APIs, input systems, audio, filesystem, threading, networking, native plugins, platform APIs, anti-cheat/DRM indicators (detection only).
- Never claim a feature is unused merely because a string or call was not found.

## App architecture

Separate the current monolithic `MainActivity.kt` into:
- UI and SAF document-tree selection.
- Scan coordinator (background execution, progress, cancellation).
- Streaming file abstraction over `ContentResolver`/SAF URIs.
- Inventory database/index (bounded memory; consider Room only if needed).
- Analyzer interfaces and version-specific modules.
- Report writer (streaming TXT/JSON/JSONL).
- Unit-testable binary readers and parsers.

Suggested analyzer contract:
- Input: read-only file descriptor/stream abstraction, relative path, size, detected format/version context.
- Output: structured findings, evidence records, warnings, and optional child records.
- Must declare whether it needs random access, maximum read window, and whether it supports streaming.
- Must never mutate source files or launch executables.

## Delivery roadmap

### Phase 0 — Baseline and safety
- Preserve current 0.2 behavior and exports.
- Add tests around current inventory, Unity-version detection, and PE parser.
- Record scan coverage and parser limitations in the report.
- Add progress/cancel support before expensive analyzers.

### Phase 1 — Scalable report foundation
- Streaming report writer and schema versioning.
- Configurable scan limits and large-file handling.
- Full file inventory without the current 20,000-line tree truncation in the machine-readable output.
- Export report parts without building multi-megabyte strings in memory.

### Phase 2 — Better build and native analysis
- Multi-source Unity version/platform detection.
- More complete PE parsing with random-access reads.
- Native dependency graph, missing plugin report, and import/export details.

### Phase 3 — Managed code intelligence
- Assembly metadata and dependency graph.
- P/Invoke inventory and IL call-site scan.
- Android packaging strategy for parser dependencies and offline use.

### Phase 4 — Unity scenes and serialized assets
- [x] Bounded header recognition for SerializedFile versions 9–21 and modern 64-bit v22+ layout.
- [x] UnityFS signature/header recognition (container contents are not yet unpacked).
- [x] Read leading metadata fields for SerializedFile v19–v26: Unity version, target platform, TypeTree flag and type count.
- [x] Safely skip bounded type entries and count objects, MonoScript references and external references.
- [ ] Add positional reads for pre-v9 endianness markers and validate against real sample files.
- [x] Parse bounded object-record samples (path ID, data offset, byte size, type index and ClassID) for SerializedFile v19–v26; validate every object range against the data section.
- [x] Extract bounded TypeTree node summaries and embedded type/field names for legacy v19–22 trees and modern mhtt blobs (v23–26); common-string offsets remain unresolved.
- [x] Parse bounded MonoScript reference records (fileID/pathID) and external file reference paths for SerializedFile v19–v26.
- [x] Preserve bounded serialized type identities (ClassID, scriptTypeIndex and MonoBehaviour scriptID) even when TypeTrees are disabled.
- [x] Read SerializeReference type entries for v20–v26; preserve className, namespace and assemblyName for v21+.
- [x] Read the v26 shared TypeTree subtree count; subtree payload resolution is still pending.\n- [x] Bind serialized type scriptTypeIndex to MonoScript fileID/pathID; resolve local targets against up to 1000 ClassID 115 object records and external targets against the external-file table (sample-bounded).
- [x] Decode local MonoScript payloads (name, execution order, class, namespace, assembly) with limits of 64 objects, 64 KiB per object and 64 MiB traversal distance.
- [ ] Scene/GameObject/MonoBehaviour inventory.
- [ ] Decode externally referenced MonoScript payloads and map MonoScript assembly/class names to managed DLL metadata.
- [ ] Resolve serialized type scriptTypeIndex values to MonoScript records, then map MonoScript class identities to managed DLL metadata.
- [ ] TypeTree/version compatibility matrix and graceful partial results.

### Phase 5 — Resource and compatibility intelligence
- Asset metadata and sidecar grouping.
- Config/input/plugin analysis.
- RamenUnity-focused compatibility summary.

### Phase 6 — Validation matrix
- Test against multiple Unity generations and both Mono/IL2CPP builds.
- Keep fixture samples small and legally distributable.
- Compare Android reports with trusted desktop tooling on the same build.
- Publish supported-format matrix and known limitations.

## Acceptance criteria for a useful full scan

A scan is considered complete only when the report states:
- Number of discovered files/directories and total bytes.
- Number successfully read, partially read, skipped, inaccessible, and failed.
- Unity version/platform/backend evidence and confidence.
- Parser/version support status for each major data family.
- Managed assembly and native binary coverage.
- Scene and asset coverage, including unresolved references.
- Every configured limit and every omission.
- Report schema and God analyzer versions.

"Complete scan" means all accessible files were inventoried and every supported analyzer ran; it does not mean every proprietary payload was decoded.

## Current baseline (God 0.2.0)

The current app uses a single `MainActivity.kt` containing the UI, SAF traversal, report generation, and PE parser. It scans up to 100,000 entries and depth 40; PE inspection is limited to 150 EXE/DLL files and a 1 MiB prefix; the text tree is capped at 20,000 lines. It exports a single Markdown and JSON string in memory. These are the first scaling bottlenecks to remove.

## Immediate next implementation slice

1. Validate the new SerializedFile/UnityFS header parser against real Unity samples and expand malformed-header tests.
2. Add positional SAF reads so pre-v9 SerializedFiles can locate the endian marker without loading the file.
3. Parse the first bounded metadata fields (Unity version, target platform, TypeTree flag, type/object counts) with strict count and offset limits.
4. Add UnityFS block-table metadata parsing; keep payload decompression as a separate, explicitly bounded stage.\n5. Decode MonoScript objects using their object TypeTrees and resolve cross-file references through the external-file table.
5. Move report generation to streaming writers and stop retaining complete Markdown/JSON strings for large scans.
