# Android System vs Sherpa：当前证据

2026-09-02，Physical Memory V1 / 0.2.0。

**Find X8s 实机比较：Insufficient evidence。** 本次开始和最终复查均未检测到 Find X8s USB 设备，因此没有该手机的服务探测、麦克风音频、离线试验或真人短句结果。下面严格区分实现能力和模拟器证据，不给引擎准确率排名。

| Metric | Android System | Sherpa |
|---|---|---|
| Available | Find X8s 未测；AOSP API 36 实测 normal=false、on-device=false、服务列表为空 | Find X8s 未测；ARM64 模拟器真实 native 模型与流式文件解码通过 |
| Offline | Find X8s 未测；普通服务是否联网由提供者决定，不能据 API 名称断言 | 代码路径只使用本地资产/CPU，无 INTERNET；Find X8s 断网麦克风验证待完成 |
| Model size | 提供者模型体积未测，不能当作 0 | 模型+词表 26,355,706 bytes（25.13 MiB） |
| Init time | 无可用模拟器服务；Find X8s 未测 | 模拟器官方 WAV 两次模型加载 2671 / 2858 ms；非手机结果 |
| First Partial | Find X8s 未测 | 文件流式解码观察到 8 次不同非空 Partial；实时首字延迟未测 |
| Final latency | Find X8s 未测 | 文件解码 658 / 747 ms（不含加载）；不是用户说话到 Final 的实时延迟 |
| Speech end → Final | onEndOfSpeech 回调时间可记录；Find X8s 未测 | 麦克风路径记录最后词元时间估计，明确标识 token_timestamp_estimate；真实声学结束延迟未测 |
| Memory | App/provider 的 Find X8s PSS 未测 | 模拟器 instrumentation 进程加载期间 PSS 220,914 / 224,778 KiB；含 UI、测试与 Room，不是模型增量 |
| Chinese short-sentence result | 指定 8 句真人样本全部未测 | 指定 8 句真人样本全部未测；官方 WAV 只证明原生解码可运行 |
| Errors | AOSP 服务缺失正确显示不可用，保留文本/Sherpa | 文件加载/Partial/endpoint/Final 未出现错误；实机麦克风错误尚未观测 |
| APK | 两引擎共用 APK，不能从 APK 总大小推算系统模型大小 | V0 12,066,592 → V1 63,979,312 bytes（11.51 → 61.02 MiB） |

官方 WAV 的两次识别输出一致：

> 对我做了介绍那么我想说的是呢大家如果对我的研究感兴趣呢

该文本是实际模型输出，不是本项目 8 句真人测试的结果。文件来自官方模型包的 test_wavs/0.wav，音频长 5,611 ms，按每块 100 ms PCM 送入解码器，不进行实时等待。记录到 native endpoint=true、31 个词元时间戳。FakeSpeechInput 的合成状态测试也不计入 ASR 准确率或性能样本。

## 下一轮实机执行

1. USB 连接 Find X8s，开启调试并授权，使用明确 serial 收集 Android/ABI/RAM/服务信息。
2. 覆盖安装当前 APK。先测试系统服务可用性与实际 mode；不可用时保留实现并记录 unavailable。
3. 每个可用引擎按 asr-test-phrases.csv 的 1–8 依次真人说话，可再加第 9 句 XM5。
4. 每次对齐 sessionId 与实际原话，记录网络条件、错误、命令类型、物品实体、位置实体；不要由识别输出猜原话。
5. 对可用引擎补充断网复测和应用/provider 内存采样。系统不可用的单元格记录 unavailable，不能捏造对应延迟。
6. 导出 JSONL / CSV 后再决定默认引擎。目前保留手动切换，暂不做性能推荐。

参见 [完整报告](physical_memory_v1_asr_report.md)、[测试句](asr-test-phrases.csv)、[原始 native 证据](../../physical-memory-v1-validation/sherpa-native-probe.json)。

模拟器耗时有明显波动：较早一次实测加载 425/354 ms、解码 96/95 ms，最终复测为上表/正文数字。见 [timing history](../../physical-memory-v1-validation/native-timing-history.json)。未控制宿主负载和调度，不能将这些数字当成手机性能或稳定性结论。
