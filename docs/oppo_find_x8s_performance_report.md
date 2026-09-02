# OPPO Find X8s 性能报告

> 版本说明：本页主体为 0.5.2 / prompt v4 的阶段记录。后续用户反馈触发了 0.5.3 数量/到期日期修复（prompt v5），见[新版本修复验证](../../physical-memory-oppo-validation/quantity-fix/README.md)。旧版 NLU 正确率与时延不能直接当作新版结论；真人 8 句和连续 10 轮仍待完成。

2026-09-02；当前版本 0.5.2 debug，Qwen3-ASR 0.6B INT8 与 Qwen3-1.7B Q8_0。本页区分原生模型探针、真人麦克风、同机 WAV 回放；不同输入与缓存条件不能直接比较。

| Metric | Result |
|---|---:|
| ASR cold load | 3438 ms |
| NLU cold load | 5045 ms |
| Both loaded PSS | 3769.1 MiB / 3.68 GiB |
| ASR sampled peak PSS | 4163.5 MiB |
| NLU sampled peak PSS | 4158.1 MiB |
| 官方长 WAV ASR decode | 8650 ms |
| 短文本 NLU 首次推理 | 11614 ms |
| 后续 13 条文本 NLU | 2019–3777 ms |
| 真人 release → ASR Final | 待真人测试 |
| 真人 ASR Final → NLU Final | 待真人测试 |
| 真人 release → Draft Ready | 待真人测试 |
| 10 轮后内存/时延变化 | 待同机录音回放 |

cold load 是构造实际 native runtime 的计时；不由文件大小估算。NLU 首次推理有首次 prompt prefill，已加载不等于已热缓存。首条 prefill 8381 ms、TTFT 8429 ms，随后短文本 prefill 275–433 ms。TTFT 与 prefill/decode 的计时区间重叠，不能将三者相加。NLU 表内 modelLoadMs=0，因为统一加载已单独记录。

## NLU 逐条实测

| 文本 | Action | Item / Slots | Schema | total / prefill / TTFT / decode (ms) |
|---|---|---|---|---|
| R8放在防潮箱 | UPSERT_ITEM_INFO | R8 / {'op': 'SET', 'value': '防潮箱'} | PASS | 11614 / 8381 / 8429 / 3179 |
| AD200放在器材柜 | UPSERT_ITEM_INFO | AD200 / {'op': 'SET', 'value': '器材柜'} | PASS | 3433 / 389 / 425 / 3042 |
| 70-200放在防潮箱 | UPSERT_ITEM_INFO | 70-200 / {'op': 'SET', 'value': '防潮箱'} | PASS | 3735 / 433 / 457 / 3301 |
| XM5放在桌子上 | UPSERT_ITEM_INFO | XM5 / {'op': 'SET', 'value': '桌子上'} | PASS | 3089 / 314 / 336 / 2774 |
| GoPro放在器材柜 | UPSERT_ITEM_INFO | GoPro / {'op': 'SET', 'value': '器材柜'} | PASS | 3285 / 337 / 358 / 2946 |
| 增加三袋牛奶 | PROPOSE_ADD_UNITS | 牛奶 / 3 | PASS | 3777 / 327 / 346 / 3447 |
| 牛奶在冰箱 | UPSERT_ITEM_INFO | 牛奶 / {'op': 'SET', 'value': '冰箱'} | PASS | 3018 / 296 / 313 / 2721 |
| 牛奶在桌子上 | UPSERT_ITEM_INFO | 牛奶 / {'op': 'SET', 'value': '桌子上'} | PASS | 3076 / 277 / 301 / 2797 |
| 牛奶在哪 | OPEN_ITEM | 牛奶 / — | PASS | 2192 / 275 / 302 / 1915 |
| 牛奶还有多少 | OPEN_ITEM | 牛奶 / — | PASS | 2151 / 327 / 350 / 1822 |
| 牛奶什么时候过期 | OPEN_ITEM | 牛奶 / — | PASS | 2100 / 306 / 331 / 1792 |
| 看看牛奶 | OPEN_ITEM | 牛奶 / — | PASS | 2096 / 303 / 325 / 1792 |
| 我要删除牛奶 | OPEN_ITEM | 牛奶 / — | PASS | 2085 / 306 / 327 / 1777 |
| 牛奶少了一袋 | UNKNOWN | — / — | PASS | 2019 / 371 / 398 / 1647 |

JSON / Schema 14/14，通过不代表语义正确；action + slots 为 13/14。“牛奶少了一袋”错误输出 UNKNOWN，应为 OPEN_ITEM。本轮不更改模型、提示词或纠错路径。

## 计时口径

真人与回放记录 fingerReleaseAt（UI stop 回调）、recordingStoppedAt（录音器结束）、asrFinalAt、nluStartAt、nluFinalAt、draftReadyAt；来自同一单调时钟。OPEN_ITEM 使用 resultReadyAt，draftReadyAt 留空，不伪装成草稿。release → result 不包含说话时长，也不是从声学最后一个字开始的延迟。

原生探针 E/F 同时运行 meminfo 采样，可能干扰时延。真人与 10 轮时延不做高频推理中 meminfo；轮次结束采样另计。只报告本机本次数据，不推广到其他设备。

[NLU 原始 JSON/计时](../../physical-memory-oppo-validation/probe/nlu-cases.json) · [加载计时](../../physical-memory-oppo-validation/probe/load-timing.json) · [内存报告](oppo_find_x8s_memory_report.md)
