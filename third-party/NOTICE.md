# GitHub source distribution

The repository contains application sources and dependency manifests. Downloaded AARs, llama.cpp source archives, legacy ASR assets, Qwen model weights and recordings are excluded from Git. Restore build dependencies with `python3 scripts/setup-dependencies.py`.

- sherpa-onnx v1.13.7: Apache-2.0; see `sherpa-onnx-LICENSE` and `downloads.json`.
- llama.cpp commit `c1d0e7a004015f23bc0233470b747b596f29b264`: MIT; see `llama.cpp-LICENSE` and `llama-version.json`.
- Model sources, versions and checksums are retained in setup scripts and `models/qwen3-manifest.json`. Code licenses do not replace model licensing.

The notes below describe earlier local development artifacts, including files that are now excluded from the Git repository.

---

# ASR artifacts

- Code/library: sherpa-onnx v1.13.7, official release published 2026-09-01. Apache-2.0; upstream license retained as `sherpa-onnx-LICENSE`.
- Official AAR: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.7
- API/configuration reference: https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/sherpa-onnx/kotlin-api/OnlineRecognizer.kt
- Audio pipeline reference: https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/android/SherpaOnnx/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt
- Model: sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01. Official archive includes model.int8.onnx, tokens.txt, bbpe.model and test WAVs. Only model.int8.onnx and tokens.txt are bundled in the main APK; official-0.wav is a separate instrumentation fixture.
- Model documentation: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-ctc/zipformer-ctc-models.html
- Model author repository: https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/tree/a5f60fe00dcfbaf68fcc1c6b5cf53061e144d6da
- No explicit model license was found in the downloaded archive or inspected model/checkpoint cards. Do not infer model redistribution rights from the sherpa-onnx code license. Current integration is a local development evaluation, not a license clearance for publishing an app.

Exact downloads and SHA-256 hashes are recorded in downloads.json. Re-fetch with scripts/setup-asr-assets.py. CPU inference only, arm64-v8a; no QNN/RKNN or other hardware runtime is used.

## Qwen3-ASR development evaluation

- Default local model: sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25, downloaded from the official sherpa-onnx asr-models release. The original archive README attributes the ONNX export to Wasser1462; it is preserved in models/. No local model conversion was performed.
- Archive SHA-256: 393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96. Per-file hashes and exact URL: models/qwen3-manifest.json.
- AAR remains v1.13.7. Pinned Kotlin reference: https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/kotlin-api-examples/test_offline_qwen3_asr.kt
- The Qwen3 weights/tokenizer are separately deployed for local development, not bundled into the APK. This task did not perform a new model redistribution-license audit; the sherpa-onnx code license is not a substitute for model licensing.
