# OPPO Find X8s 内存报告

> 版本说明：本页主体为 0.5.2 / prompt v4 的阶段记录。后续用户反馈触发了 0.5.3 数量/到期日期修复（prompt v5），见[新版本修复验证](../../physical-memory-oppo-validation/quantity-fix/README.md)。旧版 NLU 正确率与时延不能直接当作新版结论；真人 8 句和连续 10 轮仍待完成。

2026-09-02；本机 Android 16 / API 36，APK 0.5.2 debug。以下全部为 OPPO dumpsys meminfo 实测，单位 MiB（原始输出 KiB 除以 1024）。模型：Qwen3-ASR 0.6B INT8 + Qwen3-1.7B Q8_0。

| 阶段 | PSS | RSS | Native Heap | Java Heap | Graphics | Code | Stack | Swap PSS |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A App only | 146.1 | 276.0 | 17.3 | 18.0 | 9.1 | 7.5 | 0.7 | 0.6 |
| B ASR loaded | 1404.8 | 1530.1 | 1265.8 | 18.6 | 9.0 | 16.6 | 0.8 | 0.1 |
| C NLU after ASR release | 2671.7 | 2745.1 | 2030.3 | 16.2 | 9.0 | 4.3 | 0.7 | 3.9 |
| D Both loaded | 3769.1 | 3668.8 | 3163.1 | 8.7 | 9.0 | 15.1 | 0.4 | 172.6 |
| E ASR inference sampled peak | 4163.5 | 3740.0 | 3530.2 | 12.2 | 9.0 | 10.2 | 0.7 | 496.6 |
| F NLU inference sampled peak | 4158.1 | 3809.5 | 3475.4 | 12.5 | 9.0 | 17.1 | 0.7 | 424.0 |
| G Native checks complete | 4018.1 | 3767.2 | 2984.8 | 16.1 | 9.0 | 1.8 | 0.6 | 323.1 |

## 测量边界

- A 为独立验证 Activity 加 instrumentation 的进程基线，模型尚未创建；含测试框架开销，并非剥离框架后的正式包净值。
- B 为 ASR 单独加载；关闭 ASR 后再加载 NLU 得到 C，C 可能含 native allocator 保留内存，不能当作全新进程的纯 NLU 增量。
- D 重建 ASR、与 NLU 同时驻留。E/F 为同一进程、两模型常驻时的采样峰值，分别使用官方 ASR WAV 和第一条文本 NLU。dumpsys 每次完成后再间隔 250 ms，不是无损连续峰值；采样本身也有开销。
- Native Heap、Java Heap、Graphics、Code、Stack 使用 App Summary 的 PSS 栏，避免混入 Heap Alloc 或 RSS。
- ColorOS 原始输出某些 TOTAL PSS 高于 TOTAL RSS，同时有 Swap PSS；保留厂商输出，不进行人为修正。不能仅凭这里的 PSS 与 RSS 大小关系推断泄漏。
- G 是官方 WAV + 14 条文本检查结束，不代替真人一轮完整语音交互。真人/同机 WAV 回放的 G、H 行仅在取得相应原始文件后追加。
- 10 轮后内存与增长结论待真人录音及 10 轮回放完成；单轮样本不足以证明长期无泄漏。

[逐项原始来源与 KiB 数据](../../physical-memory-oppo-validation/memory-summary.json) · [原始采样目录](../../physical-memory-oppo-validation/probe/)
