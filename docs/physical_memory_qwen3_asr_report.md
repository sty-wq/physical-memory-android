# Physical Memory · Qwen3-ASR 0.6B INT8 实现与验证报告

日期：2026-09-02；应用 0.3.1 / versionCode 6（模拟器历史基线为 0.3.0 / 5）；包名 `dev.local.physicalmemory`。

**用户已确认将目标改为 Xiaomi 23078RKD5C；手机已成功完成十轮官方 WAV 解码；真实手机麦克风 STORE/FIND 已成功，完整 14 句评估进行中。** 官方含噪样本中观察到句子内容改善，但三段清晰录音中新旧模型都保留主要内容；现有证据不足以判定项目日常中文指令准确率显著提高。手机实测单独记录，不能以 Mac 模拟器性能替代。

## Xiaomi 实体机结果（0.3.1）

目标已由用户确认改为 Xiaomi 23078RKD5C，Android 15 / API 35，arm64-v8a，MT6985；CPU / 2 threads，Auto language，无 hotwords。模型权重与模拟器完全相同。

### 官方 WAV 连续十轮

官方 `raokouling.wav`，每轮 20,759 ms，真实 Kotlin → native → Qwen3。模型首次进程加载 5436 ms，后续九轮复用同一 recognizer；每轮 stream 独立创建/释放。十轮均返回非空文本，没有 OOM 或 JNI crash。进程冷加载并未清理系统页缓存。

| 轮次 | Decode ms | RTF | 解码后 PSS KiB |
|---|---:|---:|---:|
| 1 | 11348 | 0.547 | 1,891,233 |
| 2 | 11028 | 0.531 | 2,230,351 |
| 3 | 10750 | 0.518 | 2,231,034 |
| 4 | 10815 | 0.521 | 2,232,106 |
| 5 | 10771 | 0.519 | 2,233,170 |
| 6 | 10958 | 0.528 | 2,234,133 |
| 7 | 11003 | 0.530 | 2,235,249 |
| 8 | 10809 | 0.521 | 2,236,329 |
| 9 | 10739 | 0.517 | 2,223,565 |
| 10 | 10923 | 0.526 | 2,224,685 |

Decode 中位数 **10,869 ms**，范围 **10,739–11,348 ms**，RTF 中位数 **0.524**。这只是指定长 WAV 的性能，不能换算成用户真实短句的固定延迟。

| 内存阶段 | PSS KiB | RSS KiB | native heap KiB | Java heap KiB |
|---|---:|---:|---:|---:|
| 加载前 | 105,672 | 213,212 | 12,441 | 9,154 |
| 加载后 | 1,173,973 | 1,281,324 | 1,047,358 | 9,222 |
| 十轮采样分量峰值 | 2,355,509 | 2,460,936 | 2,424,418 | 23,356 |
| 释放 recognizer 后 | 204,809 | 310,880 | 20,472 | 23,500 |

峰值 PSS 约 **2.25 GiB**；释放模型后约 **200 MiB**。第一至第二轮出现约 330 MiB 的缓存增长；之后 PSS 在约 2.12–2.13 GiB 波动，未继续大幅增长。100 ms 采样可能遗漏尖峰，各项峰值不保证同时出现；本次结果不能证明长期无泄漏。完整 [十轮结果](../../physical-memory-qwen3-validation/xiaomi/ten-native-runs.json)、[汇总](../../physical-memory-qwen3-validation/xiaomi/native-summary.json)、[测试日志](../../physical-memory-qwen3-validation/xiaomi/ten-native-runs.log)。

### 真实手机麦克风首批结果

用户确认按提示完成“钥匙放在玄关柜”“钥匙在哪”。首轮未开启 WAV 保存，之后用户开启并重录，已导出两份原始 16 kHz 单声道 PCM16 WAV。正式保存轮的结果为：

| Ground Truth | 原始 Qwen3 输出 | Audio ms | Decode ms | Stop→Final ms | RTF | 业务结果 |
|---|---|---:|---:|---:|---:|---|
| 钥匙放在玄关柜 | 钥匙放在玄关柜。 | 2880 | 1168 | 1253 | 0.406 | STORE 已记住：钥匙→玄关柜 |
| 钥匙在哪 | 钥匙在哪儿？ | 1880 | 1027 | 1106 | 0.546 | FIND 找到啦：钥匙在玄关柜 |

两轮均 modelReused=true，modelLoadMs=0，captureBufferBytes=6400。查询输出比提示原文多“儿”，在只忽略标点/空格的逐字口径下不完全一致；业务查询正确。参考来自预先发给用户的句子与用户完成确认，不从 ASR 输出反推。

另保留首个 1.6 秒录音 NO_MATCH 事件：没有原始 WAV，也没有确认实际说了什么，不能计入标注句子准确率或断言为麦克风故障。其后连续记录/查询成功。其余 12 句已按顺序发给用户。首次前四句被合录为一段 17,160 ms 音频，Qwen3 以 6462 ms 转出四条完整句子，业务按既有单命令规则返回 Unknown，没有保存四条物品。此段单独保留，不伪造每句 decode 或算入逐句统计；已请用户分别重录，等待完成确认。[实机事件与音频](../../physical-memory-qwen3-validation/xiaomi/manual/)。

### 14 段真人录音的实际延迟与复用

14 段逐句 WAV 的采样时长为 1.56–4.02 秒，decode **1027–1840 ms**（中位数 1234 ms），停止后到 Final **1106–1992 ms**（中位数 1357 ms），RTF 中位数 **0.471**。全部得到非空 Final、modelReused=true；14 段回放的 Qwen3 原文与 live 结果 **14/14 完全一致**。这些性能数值不依赖 Ground Truth，因此可直接报告。

该连续会话期间采样峰值 PSS 2,214,329 KiB（约 2.11 GiB）；之前有一段 17.16 秒合录，缓存历史会影响峰值，不能称为每次短句都会新增这么多内存。真人连续录音超过 10 次，均经过独立 AudioRecord start/stop/release；没有 JNI crash 或麦克风持续占用。完整 [真人性能汇总](../../physical-memory-qwen3-validation/xiaomi/manual-performance.json)。

### 同一批手机真人 WAV：Qwen3 与旧 Sherpa

14 段逐句 WAV 分别在同一台小米手机重放给两个模型；不执行任何业务命令。下表“提示原句”来自测试前下发的清单。首两句已经用户确认完成；剩余 12 句等待用户最终确认是否存在口误/跳过，暂不把对应清单自动当成独立人工听写真值。完整 [同音频对照](../../physical-memory-qwen3-validation/xiaomi/saved-wave-comparison.json)及 [回放日志](../../physical-memory-qwen3-validation/xiaomi/saved-wave-comparison-run.log)。

| 提示原句 | Qwen3 原文 | 旧 Sherpa 原文 | Qwen / Old decode ms |
|---|---|---|---:|
| 钥匙放在玄关柜 | 钥匙放在玄关柜。 | 钌匙放在玄关柜 | 1038 / 147 |
| 钥匙在哪 | 钥匙在哪儿？ | 钥匙在哪 | 814 / 109 |
| 护照放在卧室书桌第二个抽屉 | 护照放在卧室书桌第二个抽屉。 | 护照放在卧室书桌第二个抽屉 | 1337 / 183 |
| 相机电池放在相机包里 | 相机电池放在相机包里。 | 相机电池放在相机包里 | 1277 / 183 |
| 移动硬盘放在黑色背包里面 | 移动硬盘放在黑色背包里面。 | 移动硬盘放在黑色背包里面 | 1200 / 158 |
| SD卡放在书桌上 | SD卡放在书桌上。 | &lt;unk&gt; 卡放在书桌上 | 1110 / 132 |
| 护照在哪里 | 护照在哪里？ | 护照在哪里 | 765 / 84 |
| 移动硬盘放哪了 | 移动硬盘放哪儿了？ | 移动硬盘放哪了 | 926 / 111 |
| XM5放在床头柜 | X M五在床头柜。 | &lt;unk&gt; 五在床头柜 | 1282 / 184 |
| R8放在防潮箱 | R八放在防潮箱。 | 八放在房潮箱 | 1024 / 132 |
| AD200Pro放在器材柜 | AD二百Pro放在器材柜。 | &lt;unk&gt; 二百&lt;unk&gt; 放在七材柜 | 1069 / 133 |
| GoPro放在背包里 | GoPro放在背包里。 | 高&lt;unk&gt; 放在背包里 | 956 / 108 |
| MacBook放在书桌上 | MacBook放在书桌上。 | &lt;unk&gt; 放在书桌上 | 1013 / 136 |
| 70-200放在防潮箱 | 七零杠二百，放在防潮箱里。 | 七零到二百放在方朝箱里 | 1327 / 160 |

Qwen3 修复了旧模型的“钌匙”、SD 字母丢失，并保留 GoPro/MacBook，但对“在哪/放哪了”增加儿化文字，对 XM5/R8/AD200Pro 输出中文数字形式。数字形式不一致并不都等于声学听错，却会影响本项目把转写直接作为物品名使用；本轮保留原输出。Qwen3 的原始回放文字与相应真人录音时的文字一致，精确次数在最终标注汇总中核对。

### 手机 UI 测试边界

13 项 Room 测试已逐项运行结束，系统识别能力 probe 1 项通过（系统服务可用，专用 on-device 服务不可用）。组合设备测试在拉起 Compose 辅助 Activity 时停住；随后单独的生命周期测试也在启动 Activity 时停住。日志中启动请求 result code=102；直接通过 `am start` 打开主页面则成功。对停住的 instrumentation 人工结束进程，因此日志中的 `Process crashed` 是这次终止动作的结果，不能算模型原生崩溃。

这两次 UI 测试未通过，不沿用模拟器的通过结果代填。保留 [组合测试日志](../../physical-memory-qwen3-validation/xiaomi/device-tests.log) 和 [生命周期测试日志](../../physical-memory-qwen3-validation/xiaomi/lifecycle-system-tests.log)。当前改在真实主页面由用户操作验证录音、取消和 STORE/FIND，不修改手机全局安全设置。主页面已打开，Qwen3 默认选中，RECORD_AUDIO 已授予；准备阶段 [UI 快照](../../physical-memory-qwen3-validation/xiaomi/ui-ready.xml) 与 [能力/事件导出](../../physical-memory-qwen3-validation/xiaomi/pre-manual/events.jsonl)可核对。

### 官方含噪对照与手机磁盘占用

同一 `noise2.wav`（22,833 ms）在手机上给两个引擎，模型加载不计入以下 decode。Qwen3 用 7935 ms，旧模型用 942 ms。

Qwen3 原文：

> 拨号，请再说一次，请说出你要拨打的号码：幺三五八幺八八七五五七。一三五八二八八八幺八八。七五五。有减速摄像头，减速一百公里。纠正，纠正。九六九。纠正，纠正，不是九六。

旧模型原文：

> 我后请放说次请说说你要播的号码姚三我八幺八八提悟不三八八花八把建像建一买求赠求赠九求求正不是九六

[手机对照原始结果](../../physical-memory-qwen3-validation/xiaomi/official-comparison.json)同时确认 native sherpa 1.13.7 / SHA 574210e0 / ONNX Runtime 1.27.1。Qwen3 的主要句式更完整，但数字错漏、多余内容仍在；有限官方样本不能代表物品指令准确率。

0.3.1 APK 为 64,012,080 bytes（61.05 MiB），SHA-256 `80be09859842d183d36926c00e6349652d5c5cb49609c7404cf88bd954795c6e`。手机安装代码目录 62,559 KiB，私有数据 977,814 KiB（含模型 977,675 KiB）；主要目录总计 1,040,373 KiB，约 0.99 GiB。私有模型通过后已删除本轮失败的外部模型副本，避免重复占用；没有清空主应用数据。[安装与存储快照](../../physical-memory-qwen3-validation/xiaomi/installed-metadata.json)。

### 部署兼容性修复

原方案由 adb shell 在 app-specific 外部目录创建文件，shell 校验通过，但这台小米上的 app UID 无权读取，官方 WAV 首次探测报 `Failed to read wave file`。已改为默认 `run-as dev.local.physicalmemory` 写入应用私有 `files/asr_models/<MODEL_ID>`，并由同一 UID 验证全部 24 个文件；最后写完成标记、sync。0.3.1 优先读取私有部署，兼容旧模拟器的同模型外部部署。测试结果 JSON 也改为私有文件，并通过 collect 脚本导出。

修复前 [失败日志](../../physical-memory-qwen3-validation/xiaomi/external-storage-failure.log)，修复后 [逐文件应用身份校验](../../physical-memory-qwen3-validation/xiaomi/model-deploy-private.log)。没有增加广泛存储权限、修改安全设置或更换模型。构建 `clean test lint assembleDebug build assembleDebugAndroidTest` 重新通过（18 秒），114 项 JVM 测试无失败。[构建日志](../../physical-memory-qwen3-validation/xiaomi/final-build.log)。

人工短句准确率、真实录音时延和 STORE/FIND 结果见 [Xiaomi 逐句测试表](qwen3_asr_xiaomi_test.md)，缺少有效人工录音前不填正确率。

## Model 与 Runtime

模型为指定官方发布包 `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`，从 sherpa-onnx 官方 `asr-models` release 下载。没有自行转换、重新导出或替换模型。下载包原有 README 保留了上游导出者归属。

| 项目 | 已核验值 |
|---|---|
| 现有 Android AAR | sherpa-onnx 1.13.7，无需升级 |
| AAR SHA-256 | `c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8` |
| 真实 native VersionInfo | sherpa 1.13.7 / Git SHA 574210e0 / ONNX Runtime 1.27.1 |
| 实际测试 Android | Android 16 / API 36 / arm64-v8a |
| 推理 | CPU Execution Provider，2 threads |
| Kotlin API | `OfflineQwen3AsrModelConfig`、`OfflineRecognizer`、`OfflineStream` |
| ABI / minSdk | APK 仅 arm64-v8a；minSdk 26 |

兼容性并非根据版本号猜测：提取当前 AAR 的 classes.jar 后用 javap 检查实际配置类及签名，核对 v1.13.7 的 Kotlin API/示例，再通过设备中真实 native 库加载官方模型和 WAV。没有把新版 Kotlin 源码拼入旧 AAR。

使用的官方资料：[Qwen3-ASR 支持说明](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/index.html)、[v1.13.7 Kotlin 示例](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/kotlin-api-examples/test_offline_qwen3_asr.kt)、[固定版本 OfflineRecognizer API](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/sherpa-onnx/kotlin-api/OfflineRecognizer.kt)、[模型发布页](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)。本报告的配置以固定 v1.13.7 API 为准。

### 模型完整性与配置

归档 SHA-256 与官方发布 digest 完全一致：

`393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96`

| 文件 | Bytes |
|---|---:|
| conv_frontend.onnx | 44,148,281 |
| decoder.int8.onnx | 755,914,231 |
| encoder.int8.onnx | 182,491,662 |
| tokenizer/merges.txt | 1,671,853 |
| tokenizer/tokenizer_config.json | 12,487 |
| tokenizer/vocab.json | 2,776,833 |
| 官方压缩包 | 878,702,423 |
| 展开后全部文件（含官方 WAV 与文档） | 1,000,089,677 |

[逐文件 SHA-256 清单](../models/qwen3-manifest.json)记录了 24 个文件；部署后在 Android 逐文件验证，全部一致才写入 `.verified` 标记，并执行 `sync`。重启模拟器后再次核验标记和全部 24 个 SHA-256，均一致。应用检查必要文件和部署完成标记，不在每次录音重新扫描近 1 GB 哈希。

```kotlin
OfflineQwen3AsrModelConfig(
    convFrontend = ".../conv_frontend.onnx",
    encoder = ".../encoder.int8.onnx",
    decoder = ".../decoder.int8.onnx",
    tokenizer = ".../tokenizer",
    maxTotalLen = 512, maxNewTokens = 128,
    temperature = 1e-6f, topP = 0.8f, seed = 42,
    hotwords = "",
)
// OfflineModelConfig: tokens = "", provider = "cpu", numThreads = 2
// OfflineRecognizerConfig: hotwordsFile = ""
```

`tokens=""` 与官方 v1.13.7 Kotlin type 61 示例一致，词表由 tokenizer 目录提供。这一版 Qwen3 Kotlin config 没有显式 language 字段，所以使用 Auto，不能声称已经比较或优化 Mandarin 模式。hotwords 与 hotwordsFile 均为空，没有实体纠错或领域提示。

## Device 与验证边界

用户于 2026-09-02 确认将目标改为 **Xiaomi 23078RKD5C**，serial `ZTSCJJCM4DZD7HRW`，Android 15 / API 35 / ARM64，MediaTek MT6985。系统报告 MemTotal 11,615,660 KiB；测试前可用约 4.72 GiB、存储可用约 69.4 GiB。USB 供电，测试前电量 7%、电池温度 33.9℃，省电模式关闭；这些条件影响性能，不主动修改手机电源或网络设置。实际设备与初始条件见 [device-before.json](../../physical-memory-qwen3-validation/xiaomi/device-before.json) 和 [battery-before.txt](../../physical-memory-qwen3-validation/xiaomi/battery-before.txt)。

历史模拟器：Mac 上 AVD `codex_api36_arm64`，Android 16 ARM64，Emulator 37.1.11，配置 4096 MB guest RAM。Find X8s 没有执行实测。当前手机表格见 [Xiaomi 实机测试](qwen3_asr_xiaomi_test.md)。

原有 Emulator 37.1.11 CoreAudio 麦克风重复开启问题沿用已验证的进程级清理补丁；没有修改 Android 麦克风录音策略来掩盖宿主故障。通过 `scripts/start-voice-emulator.sh -memory 4096` 启动；补丁原理和版本限制见 [麦克风排查](emulator_microphone_fix.md)。当前模拟器已经重新打开首页，Qwen3 为默认引擎，宿主麦克风转发已开启，处于未录音状态。

## Pipeline、状态与生命周期

```text
点击开始 → 首次加载缓存模型 → AudioRecord → Listening
点击停止 → 释放 AudioRecord → 完整 utterance → Recognizing
→ 后台 OfflineRecognizer.decode → Final(text) → 现有 submitCommand(text)
```

新增 `Qwen3AsrSpeechInput`，保留 `SpeechInput`、系统 `AndroidSpeechInput` 和旧 Sherpa 实现。UI 提供系统与 Qwen3 两个选项，默认 Qwen3；旧模型用于同 WAV 对照。Qwen3 不生成 Partial。文本输入、解析器、匹配器、数据库语义与 Final 提交去重保持原有路径。

本次实现 Phase A 手动停止，最长 30 秒自动结束；没有新增 VAD。用户应在“正在听…”出现后说话，加载阶段未采音。`Recognizing` 期间正常按钮禁用、取消按钮可用；文字界面继续响应。

一个引擎实例懒加载一个 OfflineRecognizer，后续 utterance 复用。每句新建并在 finally 中释放 OfflineStream；每次录音创建、停止并释放 AudioRecord。模型加载、decode、release 都受同一 worker Mutex 保护，在 Dispatchers.Default 执行。取消立即作废会话并停止麦克风；同步 JNI decode 不能硬中断，它返回后丢弃迟到结果。页面 ViewModel 清除时等待在途 native 工作返回再释放模型，避免释放后使用。退后台取消会话；普通 Activity 重建可继续复用其 ViewModel。

### 音频与 Debug

AudioRecord 沿用 MIC、16,000 Hz、单声道、PCM16；底层 buffer 为 `max(getMinBufferSize × 2, 6400)` bytes。最终构建的模拟器生命周期日志实测为 **6400 bytes**；每次读取 1600 samples（100 ms）。decode 输入为 PCM16 / 32768 的 float32。官方绕口令 WAV 为 44.1 kHz，以其真实 sampleRate 交给官方 API；对照录音全部为相同 16 kHz PCM，没有给两个引擎不同重采样数据。

Debug 开关默认关闭，每次新进程/新 ViewModel 重新默认关闭。只有 debug 构建且明确开启“保存本机测试录音”才写 `files/asr_debug/<sessionId>.wav`，真实 16-bit mono WAV 可重放。release 构建不能开启原音频保存；普通 JVM 测试验证这个限制。关闭开关不删除已有文件。文本及性能指标仍按既有行为保存在本机 `files/asr/events.jsonl`。没有 INTERNET 权限或 Qwen3 网络调用；系统 ASR 是否联网依赖设备服务，不据此宣称系统引擎离线。

## Performance：模拟器实际结果

### 先验证官方 WAV，再接麦克风

首次官方 `raokouling.wav`（20.759 秒）在 Android Kotlin 路径完成 model load、createStream、acceptWaveform、decode、非空 result。首次进程模型加载 1784 ms；随后正式 10 次循环使用新进程，模型只创建一次，加载 **1931 ms**。这属于进程冷加载，未清理 OS 文件缓存，不能称为冷磁盘读取基准。

10 次循环保持同一 recognizer，完整 WAV 每次新建 stream。以下值来自 [十次原始结果](../../physical-memory-qwen3-validation/ten-native-runs.json)：

| 轮次 | 复用模型 | 音频 ms | Decode ms | RTF | 解码后 PSS KiB |
|---|---|---:|---:|---:|---:|
| 1 | 首次加载 | 20759 | 3770 | 0.182 | 1,894,509 |
| 2 | 是 | 20759 | 3821 | 0.184 | 2,233,233 |
| 3 | 是 | 20759 | 3739 | 0.180 | 2,233,133 |
| 4 | 是 | 20759 | 3768 | 0.182 | 2,219,675 |
| 5 | 是 | 20759 | 3711 | 0.179 | 2,219,361 |
| 6 | 是 | 20759 | 3708 | 0.179 | 2,219,853 |
| 7 | 是 | 20759 | 3723 | 0.179 | 2,219,405 |
| 8 | 是 | 20759 | 3721 | 0.179 | 2,219,213 |
| 9 | 是 | 20759 | 3766 | 0.181 | 2,217,489 |
| 10 | 是 | 20759 | 3736 | 0.180 | 2,217,185 |

Decode 范围 3708–3821 ms，中位数 3737.5 ms，RTF 约 0.18。JSON 中十行重复的 modelLoadMs=1931 是同一次初始化的共享测量值，**不是十次重新加载**；应用正式会话日志则在复用时写 modelLoadMs=0、modelReused=true。

| 内存时间点 | PSS KiB | RSS KiB | native heap KiB | Java heap KiB |
|---|---:|---:|---:|---:|
| 加载前 | 101,095 | 204,368 | 5,609 | 6,737 |
| 加载后 | 1,190,866 | 1,294,880 | 1,040,637 | 6,821 |
| 十轮分量最大值 | 2,352,297 | 2,452,196 | 2,416,430 | 7,361 |
| 第十轮解码后 | 2,217,185 | 2,317,188 | 2,296,907 | 6,340 |
| recognizer 释放后 | 199,981 | 300,064 | 12,245 | 6,480 |

峰值 PSS 2,352,297 KiB，约 **2.24 GiB**，明显高于权重文件大小。native heap 与 PSS/RSS 口径不同，不可相加。每 100 ms 采样，峰值可能漏掉短暂尖峰；各列最大值不保证同时出现。第二轮存在缓存增长，第三至十轮解码后 PSS 稳定在约 2.12 GiB，释放模型后降至约 195 MiB；本次未发生 OOM、JNI crash 或持续单调增长，不能用 10 次观测证明任意长时间均无泄漏。

### 三次真实 AudioRecord 采集

播放现有官方 `official-0.wav`（约 5.61 秒）到 Mac 扬声器，再经 Mac 麦克风 → 模拟器 AudioRecord 采集，每次约 8 秒手动停止。没有直接把文件注入录音器，也没有使用 TTS。这是宿主声学回采，**不是用户在实体手机的真人录音**。三次都得到 Final，第二、三次复用模型，每次麦克风释放；原始录音已导出。

| 轮次 | Model load ms | Audio ms | Decode ms | Stop→Final ms | RTF | 峰值 PSS KiB |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 1899 | 7984 | 969 | 1000 | 0.121 | 1,571,369 |
| 2 | 0 | 8048 | 978 | 1018 | 0.122 | 1,589,620 |
| 3 | 0 | 7800 | 1023 | 1043 | 0.131 | 1,671,104 |

[麦克风原始指标](../../physical-memory-qwen3-validation/microphone.json)与 [保存的三段 WAV](../../physical-memory-qwen3-validation/emulator-debug/audio/)可复核。正式原始内存四项见 [events-final.jsonl](../../physical-memory-qwen3-validation/events-final.jsonl)。

指标定义：recordDurationMs = 实际 PCM samples / 16000；decodeMs 只计模型 decode，排除模型加载和录音；RTF = decodeMs / recordDurationMs。`totalAfterSpeechMs` 当前从录音停止并释放后的时间点到 Final，包含 WAV 保存/结果整理等开销，**不是声学语音结束→Final，也不包含按键回调到 AudioRecord 停止完成之前的时间**。不伪造 speechEndedAt。加载前后、解码后及采样峰值同时记录 PSS/RSS/native/Java heap。

### 磁盘与 APK

模拟器历史部署：`/sdcard/Android/data/dev.local.physicalmemory/files/asr_models/<MODEL_ID>`；0.3.1 新部署默认使用应用私有 `/data/user/0/dev.local.physicalmemory/files/asr_models/<MODEL_ID>`，解决小米外部存储访问限制。不打入 APK；安装主 APK 后再以显式 serial 部署、校验模型。卸载主应用会删除该应用的模型与记录，因此只覆盖安装。

| 项目 | 实测 |
|---|---:|
| Debug APK | 64,012,080 bytes（61.05 MiB） |
| 模型归档 | 878,702,423 bytes（838.00 MiB） |
| 模型展开逻辑大小 | 1,000,089,677 bytes（953.76 MiB） |
| 安装代码目录 du | 62,516 KiB |
| 应用内部数据 du（含已保存测试音频） | 904 KiB |
| 外部 asr_models 目录 du | 976,860 KiB |
| 以上主要安装目录合计 | 1,040,280 KiB，约 0.99 GiB |

目录 du 为当时快照，不等同 Android 设置中包含系统缓存的完整“应用大小”。APK SHA-256：`ac739c9dbf18bf109ca94c861a716ad294cbdfdd496acc3945962cdce263603a`。为最小同音频对照，旧小模型资产仍在 APK 内（约 61 MiB 的主要组成）；Qwen3 近 1 GB 权重独立存储。

## Accuracy observations：保持原始输出

### 官方含噪 16 kHz 音频，同 PCM 对照

音频 `noise2.wav`，22,833 ms。参考文字来自模型包 `test_wavs/transcript.txt`：

> 拨号，请再说一次，请说出您要拨打的号码。幺三五八幺八八七五七。一三五八二八八八幺八八。纠正纠正。九六九。纠正纠正，不是九六。

旧 Sherpa（248 ms）：

> 我后请放说次请说说你要播的号码姚三我八幺八八提悟不三八八花八把建像建一买求赠求赠酒求正求正不是九六

Qwen3（2883 ms）：

> 拨号，请再说一次，请说出你要拨打的号码：幺三五八幺八八七五五七。一三五八二八八八幺八八。七五五。有减速摄像头，减速一百公里。纠正，纠正。九六九。纠正，纠正，不是九六。

观察：Qwen3 恢复了“拨号，请再说一次，请说出你要拨打的号码”等主要句式，旧模型的对应片段严重破碎；但 Qwen3 仍有数字错漏和多余的“减速摄像头”等内容。这里只根据包内参考转写作有限比较，未重新人工逐帧标注噪声中的背景语音；不能把一个样本当成日常物品指令正确率。

### 保存的麦克风 WAV，同 PCM 对照

这三段是同一官方普通话录音的三次声学回采，不是三句不同测试指令。主要内容为“对我做了介绍，那么我想说的是呢，大家如果对我的研究感兴趣呢”，不对“啊”等语气词做修正。两个引擎都保留了主要内容，Qwen3 增加标点但第一段有多余的“：，”。没有证据显示这三个清晰样本存在显著识别改善，Qwen3 的纯解码更慢。下面逐条保留原文：

1. `QWEN-MIC-1788346964015-0.wav`，7984 ms。

   Qwen3（841 ms）：对我做了介绍。啊，那么我想说的是呢：，大家如果对我的研究感兴趣呢。

   旧 Sherpa（90 ms）：对我做了介绍那么我想说的是呢大家如果对我的研究感兴趣呢

2. `QWEN-MIC-1788346974983-1.wav`，8048 ms。

   Qwen3（871 ms）：对我做了介绍啊。那么我想说的是呢：大家如果对我的研究感兴趣呢。

   旧 Sherpa（91 ms）：对我做了介绍那么我想说的是呢大家如果对我的研究感兴趣呢

3. `QWEN-MIC-1788346984143-2.wav`，7800 ms。

   Qwen3（784 ms）：对我做了介绍啊。那么我想说的是呢，大家如果对我的研究感兴趣呢。

   旧 Sherpa（90 ms）：对我做了介绍那么我想说的是呢大家如果对我的研究感兴趣呢

该对照只计 decode，不包含两个引擎加载；旧 streaming 模型按 1600 samples chunk 重放，Qwen3 用完整 utterance。完整结果见 [保存录音对照](../../physical-memory-qwen3-validation/saved-wave-comparison.json)。

### 官方绕口令原始输出

该样本用于验证真实 native 执行与连续释放，非“正确识别”的断言。十轮都返回相同文字：

> 广西壮族自治区爱吃红鲤鱼、绿鲤鱼与驴的出租车司机，拉着苗族土家族自制粥，爱喝自制的刘奶奶榴莲牛奶的古痴，说中症患者遇见别着喇叭的哑巴，打败姚子山前四十四棵死色柿子树的四十四只石狮子之后，碰到年年练牛羊的牛郎，念着灰黑灰化肥发黑会挥发，走出香港官方网站，设置组到广西壮族自治区首府南宁市民族医院就医。

原文存在明显不自然词组，不能把非空结果等同于准确率通过。

### Xiaomi 必测 14 句

[实机逐句表及执行步骤](qwen3_asr_xiaomi_test.md)包含全部用户指定普通句、查询句及英文/数字混合实体。当前 0 条实机观测，四类准确率均“未测”，没有拿上面的 WAV 结果代填。

## Tests 与构建

执行 `./gradlew clean test lint assembleDebug build assembleDebugAndroidTest --console=plain`，BUILD SUCCESSFUL（17 秒）。[完整构建日志](../../physical-memory-qwen3-validation/final-build.log)。

| 验证 | 结果与范围 |
|---|---|
| JVM testDebugUnitTest | **114/114**，0 failure/error/skipped；不加载大模型 |
| Qwen3 controller JVM | 11 项，状态序列/无 Partial、十轮复用、加载/权限/录音/decode 错误、取消/延迟结果、release 顺序、PCM 空输入、Debug opt-in、WAV roundtrip |
| ViewModel 新增回归 | Qwen 默认、Qwen controller Final → 现有 STORE/FIND、一次写入；使用受控解码结果，不冒充真实声学准确率 |
| 选定设备回归 | **18/18**：Room 13、FuzzyChoiceUI 1、FuzzyLookup 1、SpeechUI 1、QwenUI 1、SystemCapability 1 |
| Qwen3 UI 响应 | 受控后台 decoder 阻塞时显示 Recognizing，取消和文字提交可用；迟到 Final 不写库 |
| 官方 native probe | 首次单轮通过，然后同模型连续 **10/10** 非空 Final，stream 每次释放 |
| 官方 noise2 对照 | 真实 Qwen3 与旧模型，同 PCM，通过 |
| 麦克风 | **3/3** 宿主声学回采，真实 AudioRecord/Qwen3，WAV 保存成功 |
| 保存录音重放 | 三段真实保存 WAV 分别给两模型，通过 |
| 生命周期 | **1/1**：真实 Activity / 模型 / 麦克风，切后台、回前台、取消、Activity 重建后再次录音 |
| 模型部署持久性 | sync 后重启，24 个文件 SHA-256 和 verified marker 全部通过 |
| 用户数据 | 正式库全部 id/name/location/createdAt/updatedAt 与修改前完全相同，仍为原有三条记录 |

生命周期测试覆盖后台/返回/Activity 重建；页面终结释放顺序另由控制器 JVM 测试检查。这一历史阶段没有实体手机“关闭页面重新进入”观测，不把这些证据混为同一种测试。设备 UI 测试的 decoder 可控替身只证明 UI/线程/提交契约；真实 native 和麦克风由独立 probe 验证。

主 APK 一直 `install -r`，未执行 AGP connected 测试收尾、未卸载或清空主应用。所有 instrumentation 完成后仅卸载 `dev.local.physicalmemory.test`，重新启动主应用。Parser、名称模糊匹配、实体与 Room 业务行为未改动。

## 实际问题与修复

1. **目标已变更**：用户确认使用 Xiaomi / 23078RKD5C。初次外部目录部署虽然 shell SHA-256 通过，但应用读取官方 WAV 失败，run-as 访问外部模型目录返回 Permission denied。0.3.1 默认以 run-as 写应用私有目录并以应用 UID 校验；native 模型及 probe 输出也使用私有目录。重试已能成功读取官方 WAV 并解码。没有扩大存储权限或修改手机安全设置。
2. **模型磁盘落盘**：初次 adb push 和校验成功后立即关闭模拟器，重启发现编码器/解码器文件截断且 verified marker 未落盘。应用校验阻止了不完整模型进入 JNI。部署脚本加入 Android `sync`，重新部署；本次正常重启后逐文件校验全部通过。
3. **内存成本**：20.76 秒样本峰值约 2.24 GiB PSS；短句虽较低，也超过模型文件大小。没有隐藏该成本或自动换用其他模型；手机是否可接受仍待测。
4. **识别仍有误差**：含噪数字错漏、绕口令词语错误、偶发标点组合异常均保留原输出。没有修改解析器或加词表来掩盖模型问题。
5. **计时边界**：当前只支持手动 stop 指标，真实声学结束时间未观测。首个 probe 的 decode 数值包含不足 100 ms 的采样线程收尾；正式十轮 probe 已将计时结束放在采样收尾之前，报告性能以正式十轮为准。

## 复现与使用

见 [README](../README.md) 的模型部署与手动录音说明、[Xiaomi 测试步骤](qwen3_asr_xiaomi_test.md)。重要文件：`voice/Qwen3Runtime.kt`、`Qwen3AsrSpeechInput.kt`、`PcmRecorder.kt`、`DebugWavStore.kt`；脚本 `setup-qwen3-model.py`、`deploy-qwen3-model.py`、`collect-qwen3-debug.py`。原始结果集中在 `../../physical-memory-qwen3-validation/`。

## Conclusion

**Qwen3-ASR 0.6B INT8 已能在本项目的 Android ARM64 离线路径运行；有限含噪对照显示部分中文内容改善，清晰录音对照没有显示显著优势。是否显著提高本项目日常中文物品指令准确率，目前证据不足。用户已改用小米手机；实机结论待本轮实际性能和人工短句结果完成后更新。**
