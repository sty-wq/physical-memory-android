# V2 implementation plan — 2026-09-02

Authoritative request: attachment 93aff626-ffad-4712-ae30-6492d35309cf.

## Audit

- Single `:app`, package `dev.local.physicalmemory`; AGP 9.1.1, Gradle 9.3.1, Java 17 bytecode, JDK 21 toolchain, minSdk 26 / targetSdk 36, ARM64 only.
- Room v1 contains only `items(id,name,location,createdAt,updatedAt)` with unique names. No inventory or draft implementation exists. Legacy DAO upsert always changes updatedAt on conflict.
- MainActivity → HomeViewModel → PhysicalMemory → CommandParser currently executes writes immediately. Legacy fuzzy lookup is active there. V2 will use a separate NLU/draft path without fuzzy correction. Legacy classes remain only for regression coverage.
- Qwen3-ASR 0.6B INT8 uses sherpa-onnx 1.13.7 AAR, cached recognizer, serialized inference/release, manual-stop boundary and opt-in debug WAV. Preserve this implementation. No NDK/CMake dependency currently exists.
- Existing JVM tests cover parser, fuzzy lookup and ASR controllers; isolated Room and Compose instrumentation exists. Main-database fixtures and AGP connected-test teardown are unsafe on the user's phone and will not be used.
- Xiaomi probe: 23078RKD5C / Android 15 / API 35 / MT6985 / arm64-v8a / 8 cores; MemTotal 11,615,660 KiB, MemAvailable 5,181,044 KiB at audit; /data about 68 GiB free. Capture Android API values and load-time memory separately.

## Implementation order

1. Back up current phone database (including WAL), pin official Qwen3-1.7B Q8_0 and llama.cpp sources, install local NDK/CMake, build a minimal CPU JNI runtime. Model lives in app-private files, outside APK, and is checked as app UID.
2. Add four disjoint NLU types, Draft 2020-12 oneOf schema, hard GBNF decoding, strict Kotlin decoding, NluEngine/Fake/Qwen implementation and timing. Benchmark thinking on/off before selecting production mode.
3. Migrate Room v1 → v2 without destructive fallback. Keep Item identity/location/timestamps, add lowStockThreshold and InventoryUnit with nullable expiry and no location. Existing items start with zero units because v1 never recorded quantity. Empty Item.location means unrecorded; KEEP preserves it. Quantity is always derived from unit rows.
4. Implement read-only DraftFactory, independently editable drafts, deterministic validation and transactional confirmation. Check the current snapshot to reject stale drafts; confirmations are serialized and idempotent. No-op location changes do not UPDATE. Single-unit deletion is a separate confirmed UI action.
5. Replace MainActivity's active screen/ViewModel with V2 text/ASR → editable draft/detail flow. Raw text reparses; structured edits refresh exact item lookup without running NLU. Show whole-item location changes and every unit expiry/delete button.
6. Add meaningful JVM, migration/Room and UI tests; benchmark at least 150 labeled inputs on the actual Xiaomi, capture lifecycle/10-call reuse, NLU/ASR/both RAM and pipeline timings. UI fixtures must use an isolated database, never clear user data.
7. Run clean/test/lint/assembleDebug/build, update-install and launch on Xiaomi, complete real-device E2E, and write architecture/schema/benchmark/runtime/implementation reports with raw evidence and any failures or limitations.

The new user request explicitly replaces the previous ASR-only scope in AGENTS.md. No cloud, automatic correction, unit location, bulk delete or Item delete will be added.
