# Qwen3-1.7B Android 实机运行报告

2026-09-02。Qwen3-1.7B **Q8_0 已在小米手机本地 ARM64 CPU 运行**，没有降级量化、云端服务或 NPU/GPU 依赖。最终包覆盖安装，模型与原有用户数据库保留。

## 实际设备

| 项目 | adb / Android API 实测 |
|---|---|
| Manufacturer / Model | Xiaomi / 23078RKD5C |
| serial | ZTSCJJCM4DZD7HRW |
| Android / API | 15 / 35 |
| ABI | arm64-v8a；设备也宣告 armeabi-v7a / armeabi 兼容 |
| SoC / 核数 | MT6985 / 8 |
| Total RAM | 11,894,435,840 bytes；/proc/meminfo 11,615,660 KiB，约 11.08 GiB |
| Available RAM | 探测时 5,317,701,632 bytes，约 4.95 GiB |
| Storage total / available | 241,807,884,288 / 71,543,500,800 bytes |

原始证据：[Android API](../../physical-memory-v2-validation/device-api.json)、[adb](../../physical-memory-v2-validation/device-adb.txt)。RAM 与空闲空间随时变化；以上是当前测试机，不把它等同于所有最终 12 GB 设备。测试时 USB 供电，系统后台负载和温度未严格控制，未为测试修改用户全局设备设置。

## 模型与 Runtime

- [官方 Qwen3-1.7B GGUF](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF)，revision `90862c4b9d2787eaed51d12237eafdfe7c5f6077`，Apache-2.0。
- Q8_0 文件 `Qwen3-1.7B-Q8_0.gguf`，1,834,426,016 bytes。SHA-256 `061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a`，主机与 app UID 手机校验一致。无 Q4_K_M 降级。
- [llama.cpp Android 实现依据](https://github.com/ggml-org/llama.cpp/blob/c1d0e7a004015f23bc0233470b747b596f29b264/docs/android.md)：bundled v0.3.0、commit `c1d0e7a004015f23bc0233470b747b596f29b264`、MIT。初始发布归档与该提交定址归档的文件树完全一致，归档 SHA 记录于 [provenance](../../physical-memory-v2-validation/llama-version.json)。
- NDK 28.2.13676358 / CMake 3.31.6；C/C++ 内核启用 O3；ARMv8.2-a+dotprod，4 线程，context 2048、batch 512、microbatch 128、mmap 模型、CPU 推理。旧 ARM64 CPU 不一定支持该指令目标，本报告只验证本机 MT6985。
- 无 GPU layers、OpenMP、NNAPI、NPU 或网络 runtime。GBNF 对 token 采样加硬约束，Kotlin 严格验证 JSON。非 thinking 默认输出 384 token / 60 秒，thinking 对照 512 token / 90 秒。
- 模型在 app-private `files/nlu_models`，ASR 模型仍在 `files/asr_models`；均不塞进 APK。脚本 `scripts/setup-qwen3-nlu.py` 固定版本下载、SHA 校验、app UID 写入 partial 后原子重命名。新设备还需单独安装已有 ASR 模型。

## RAM 与两个模型共存

| 场景 | 进程 PSS（KiB） | GiB |
|---|---:|---:|
| coexist 测试基线 | 99,441 | 0.09 |
| ASR only，真实官方 WAV 解码后 | 1,896,468 | 1.81 |
| NLU only，155 条基准结束后 | 3,420,684 | 3.26 |
| NLU only，基准采样峰值 | 4,025,529 | 3.84 |
| ASR + NLU 均已真实执行并驻留 | 5,735,191 | 5.47 |
| coexist 采样峰值 | 5,759,951 | 5.49 |
| coexist 两个模型释放后 | 215,643 | 0.21 |

[coexist 原始测量](../../physical-memory-v2-validation/coexist.json)、[NLU 单独测量](../../physical-memory-v2-validation/nlu-final-v4-memory.json)。采样间隔 200 ms，PSS 是共享页摊分后的进程指标，不等于设备总占用；不保证捕获每个瞬时峰值。不同进程实验不能直接相加。本轮共存、连续调用与 E2E 未出现 OOM；这不构成所有低内存/多任务场景的保证。

## Cold / warm / 后台前台 / 连续十次

最终安装包上运行 NluLifecycleTest：cold warmUp 为 **3,861 ms**，随后 10 次调用全部 reused=true、modelLoadMs=0。第 5 次后回到桌面约 1.2 秒再返回，后续继续复用。

| 次数 | totalNluMs | cachedPromptTokens | 调用后 PSS KiB |
|---:|---:|---:|---:|
| 1 | 12859 | 0 | 4105995 |
| 2 | 5430 | 647 | 4103270 |
| 3 | 2994 | 647 | 4105430 |
| 4 | 5992 | 647 | 4103538 |
| 5 | 3861 | 647 | 4105772 |
| 6 | 5545 | 647 | 4099731 |
| 7 | 4133 | 647 | 4103162 |
| 8 | 6824 | 647 | 4100868 |
| 9 | 3794 | 647 | 4103168 |
| 10 | 7070 | 647 | 4099979 |

第一条 warmUp 后推理仍须完整预填充，所以为 12,859 ms；后续精确前缀缓存复用后约 3–7 秒。这里测试的是空闲模型在后台/前台的保留，不把它称为正在生成时的实机取消压力测试。取消和迟到结果隔离另有 JVM 回归。

选择：一个 ViewModel 持有缓存的 ASR / NLU 引擎，推理在后台线程串行执行；切后台取消活跃会话但保留模型，ViewModel 清理时等待原生工作退出后释放。系统杀进程后正常重新加载，不承诺跨进程保留未确认草稿。[10 次原始记录](../../physical-memory-v2-validation/nlu-lifecycle.json)。

155 条 NLU 的总耗时中位数 7,725 ms、P95 12,453 ms、最大 23,799 ms（首次含加载）。prefill/TTFT/decode/token 的完整统计见 [基准报告](qwen3_1_7b_nlu_benchmark.md)。cold 指新 model/context，OS 文件缓存可能已经热，未声称清空缓存后的闪存性能。TTFT 自 native generation 开始，不含 modelLoadMs；thinking 的 TTFT 为首个思考 token。

## 真实 ASR → NLU → 草稿

最终包在本机重放此前用户已授权保存的 2,880 ms、16 kHz 单声道录音。使用真实 Qwen3-ASR 解码、真实 Qwen3-1.7B 抽取及实际 InventoryViewModel/DraftFactory，数据写入测试用隔离内存库。

ASR 输出“钥匙放在玄关柜。”，草稿得到 item=钥匙、location=玄关柜；未确认时库存库无写入。**本次是已保存真实人声 WAV 回放，不是重新采集麦克风。** 录音结束边界是读取完最后一段 PCM 并触发停止，记为 saved_wav_replay_stop，不是检测声学结束。

下列时间为同一 System.nanoTime 单调时钟的毫秒值，不是墙上时间：

| 时间点 | ms |
|---|---:|
| speechEnd | 9264953 |
| asrFinal | 9266294 |
| nluStart | 9266299 |
| nluFinal | 9284290 |
| draftReady | 9284299 |

- ASR latency：1,341 ms（asrFinal − speechEnd）。
- NLU latency：17,991 ms（nluFinal − nluStart），其中模型加载 3,297 ms，prefill 10,110 ms，TTFT 10,161 ms，decode 4,562 ms；662 prompt / 34 generated tokens。
- ASR Final 到 NLU 开始调度：5 ms；Draft latency：9 ms。
- speechEndToDraftReady：**19,346 ms**，包含本次首次 NLU 加载。ASR 的 5,942 ms 模型加载发生在录音开始前，不包含在结束到草稿这段时延。

[完整 pipeline 数据](../../physical-memory-v2-validation/v2-speech-pipeline.json)、[执行日志](../../physical-memory-v2-validation/logs/pipeline-final.log)。一条录音只能证明该样本链路通过，不能据此声称全面 ASR 准确率或稳定声学端点检测。

## 最终构建与安装

`./gradlew clean test lint assembleDebug build assembleDebugAndroidTest` 成功，125 项 JVM 测试通过（0 failure/error/skip），lint 通过（0 error、10 warning：依赖升级提示、ARM64-only ChromeOS 提示和 KTX 建议），debug/release 均构建成功。调试 APK 70,207,953 bytes，versionName 0.4.0 / versionCode 7。

最终 APK 与设备已安装 base.apk 的 SHA-256 完全一致：
`a0a17246b9d65dc7e97261e2f0656595bc5c94b6d5f6d5f4d2a33cdd8d31d0fc`。

155 条基准及 thinking 选择阶段使用的已安装 APK SHA：`e78dd3ee74fbd062bb2517bbc44d4ff8ce91b58aa04825b70c1c2b03bd75cacc`。之后只增加原始文本来源清理、日期歧义草稿防护和测试截图同步；Prompt v4 / NLU Schema / native runtime 未改动。最终 APK 已另跑十次生命周期、语音链路和 UI/Room 回归。区分两个构建是为避免把最终二进制与此前基准混为一谈。

[APK 对照记录](../../physical-memory-v2-validation/apk-provenance.json)、[构建日志](../../physical-memory-v2-validation/logs/final-build.log)、[JVM 数量](../../physical-memory-v2-validation/jvm-tests-summary.json)。合并后的 manifest 只有 RECORD_AUDIO 和应用自有非导出 receiver 权限，**无 INTERNET**，见 [权限证据](../../physical-memory-v2-validation/manifest-permissions.json)。
