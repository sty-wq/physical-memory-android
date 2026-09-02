# Physical Memory Android V2 实现报告

**已完成 V2 实现、构建和小米实机验证，并在手机打开正式应用。** Qwen3-ASR 0.6B INT8 → Qwen3-1.7B Q8_0 → 可编辑草稿 → 用户确认 → Room 的完整本地链路已接通。当前包为 0.4.0 / code 7，仍沿用 physical-memory-v0 项目目录。

正式数据库已从 v1 迁移至 v2。原有 10 条物品的 ID、名称、位置、createdAt、updatedAt 与迁移前逐项一致，integrity_check=ok；没有推测旧物品数量，因此它们当前各有 0 份库存。既有 ASR 模型和测试录音也保留。当前前台为 MainActivity，截图和当前进程日志未发现崩溃。

## 功能与边界

- 本地 Qwen3-1.7B Q8_0 通过 llama.cpp ARM64 CPU JNI 运行，无云端依赖、无 INTERNET 权限，无量化降级。模型、Runtime 和业务 NluEngine 解耦，提供 FakeNluEngine 用于隔离测试。
- 只保留四个 NLU action。正式 Draft 2020-12 oneOf Schema、采样时 GBNF、严格 Kotlin 解码分别限制结构、生成和接收边界。NLU 不引用 Repository、DAO 或 Entity，不接收或产生数据库 ID。
- UPSERT / ADD 先精确读库，再产生 Create / Update / Add 草稿。原文可以修改后重解析；物品名、位置、数量、量词、每份到期日期可以直接修改。名称修改会重新查库；确认前不会保存。
- Item.location 是唯一位置来源。修改位置只更新 Item，所有旧、新实例共享它；UI 明确显示 old → new 和影响范围。相同位置不重复 UPDATE，也不改变时间戳。
- InventoryUnit 只有 id、itemId、expiryDate、createdAt、updatedAt，数量由实例行数推导。每份日期可以独立修改或清空；复杂逐份自然语言日期不自动分配，AMBIGUOUS_DATE 时默认日期全部留空供填写。
- 确认时校验字段与当前数据库快照，事务执行位置更新和实例添加。草稿 ID 与内容指纹回执防止重复添加，过期草稿不能覆盖新状态。
- OPEN_ITEM 统一展示名称、位置、数量、全部实例、各自日期与删除按钮。用户选中具体实例后必须二次确认，删除恰好一行，最后一份删除后仍保留 Item。没有模型删除、整件删除或批量删除入口。

## 验证结果

| 验证 | 结果 | 可复核证据 |
|---|---|---|
| clean / test / lint / assembleDebug / build | PASS；debug/release 均成功 | [完整构建日志](../../physical-memory-v2-validation/logs/final-build.log) |
| JVM 回归 | 125 项通过，0 failure/error/skip | [数量汇总](../../physical-memory-v2-validation/jvm-tests-summary.json) |
| Lint | 0 error；10 warning（依赖更新提示、ARM64-only ChromeOS 提示、KTX 建议） | [Lint 报告](../../physical-memory-v2-validation/lint-results-debug.html) |
| 最终包 Room 实机测试 | 7 项通过，0.740 秒 | [日志](../../physical-memory-v2-validation/logs/room-final.log) |
| Fake NLU 界面隔离测试 | A–D + 单份删除通过 | [日志](../../physical-memory-v2-validation/logs/ui-fake-v2.log) |
| 最终包真实 Qwen NLU + UI | A–D + 单份删除通过，48.119 秒 | [日志](../../physical-memory-v2-validation/logs/ui-real-final.log)、[机器结果](../../physical-memory-v2-validation/v2-ui-real.json) |
| 连续十次、后台/前台复用 | 10/10 成功，均无重复模型加载 | [记录](../../physical-memory-v2-validation/nlu-lifecycle.json) |
| ASR + NLU 共存 | 通过，峰值 PSS 5.49 GiB | [记录](../../physical-memory-v2-validation/coexist.json) |
| 保存的人声 WAV → ASR → NLU → 草稿 | 通过；未确认写入为 0 | [记录](../../physical-memory-v2-validation/v2-speech-pipeline.json) |
| 真实正式库迁移 | 10/10 原记录完整保留，库存 0 行，结构 v2 | [逐项核验](../../physical-memory-v2-validation/migration-verification.json) |
| 最终安装与启动 | 手机 APK SHA 与构建一致，MainActivity 位于前台 | [APK 核验](../../physical-memory-v2-validation/apk-provenance.json)、[启动证据](../../physical-memory-v2-validation/app-ready.json) |

UI A–D 使用真实 Qwen3-1.7B 和独立的内存数据库，并未清空正式库：

1. A：空库输入“R8放在防潮箱”，核对未确认时不存在 R8，点击确认后保存。
2. B：预置牛奶在冰箱、3 份库存，输入“牛奶在桌子上”，显示冰箱 → 桌子上；确认前数据库不变，确认后原来三份的 ID、日期、时间戳全部不变。
3. C：输入“增加三袋牛奶”，继承桌子上，逐份填入 2026-09-05、2026-09-08、空，确认后共 6 份。
4. D：输入“牛奶在哪”，展示桌子上、6 份及全部实例。点击选定实例删除、弹出确认，此时仍 6 份；确认后恰好剩 5 份，其余实例逐项不变。

Room 测试另外覆盖了已有 2 份再加 3 份仍继承冰箱、已有 2 份再加 3 份且整体改为桌子上、同值 no-op、回执重放、失效草稿、非法日期、最后一份删除后保留 Item、v1 测试库迁移等。

真实语音链路使用此前用户保存的 2.88 秒人声“钥匙放在玄关柜”，并非本次新采集麦克风。最终 ASR 文本“钥匙放在玄关柜。”，正确形成钥匙/玄关柜草稿，录音回放停止到草稿 19.346 秒（含首次 NLU 加载）。没有把它包装成声学结束检测或全面 ASR 准确率测量。

## 模型准确率与性能

155 条预先编写的合成语句全部在小米执行，无重试替换失败：JSON 合法率 100%，Action 96.13%，**Full Result 143/155 = 92.26%**。135 条留出样本 Full Result 125/135 = 92.59%。20 条 v3 对照中 thinking off 比 on 更快且本次准确率更高，因此默认关闭 thinking；v4 最终基准另跑全量 off。

NLU 中位数 7.725 秒，P95 12.453 秒；首条含加载为 23.799 秒。ASR + NLU 共存采样峰值 5.49 GiB。所有分项指标、12 条错误、样本划分及测量限制见 [NLU 基准报告](qwen3_1_7b_nlu_benchmark.md) 和 [Runtime 报告](qwen3_1_7b_android_runtime_report.md)。

型号中的数字、相对日期和少数意图仍有真实错误，例如“AD两百”可能被当作 200 份、“下周五”可能生成错误日期、“增加牛奶”可能猜数量。日期/数量格式合法不代表符合原句。应用始终要求用户核对；超出 1–100 的数量不能确认。未通过的模型样本都保留，没有列作通过。

## 产品选择与未扩展内容

按 V2 要求，当前使用精确名称查找，不做模糊、别名、ASR 自动纠错或 embedding；“AD两百”和“AD200”需要用户手动修改成相同名称。历史 fuzzy/parser 代码保留用于旧回归，已退出 MainActivity 实际入口。

新 Item 未记录位置用空字符串表示“未记录”；已有位置不能清空。unit_label 是可编辑草稿中的自然语言量词，入库后详情统一按“份”显示，不持久化包装换算。每批新增最多 100 份；草稿不跨进程持久化。没有新增账号、同步、图片、条码、NFC/BLE、RAG/Agent 框架、云端或 NPU 专项优化。

三个依赖旧版自动写入/自动模糊匹配的 MainActivity 验收脚本已存档至 docs/legacy-tests，替换为 V2 对应验收，不把旧行为计为 V2 PASS。真实 UI 验收注入的 ASR 为空以隔离 NLU，因此其截图显示“语音未接入”；正式 MainActivity 已连接真实 Qwen3-ASR，见[当前应用截图](../../physical-memory-v2-validation/app-ready.png)。

## 现在如何测试

手机上已打开正式应用：

1. 输入“R8放在防潮箱”，点击“解析 / 重新解析”，检查并修改草稿，点击“确认保存”。
2. 输入“增加三袋牛奶”，检查位置、数量，逐份填写或留空日期，再点击“确认添加”。
3. 输入“牛奶在哪”，查看完整详情。每份“删除”都有二次确认，取消不会减少库存。
4. 语音点击“点击说话”，看到“正在听…”后开口，再点击停止；ASR Final 自动解析为草稿，仍需人工确认保存。首次会加载模型，请等待解析结束。

## 交付文件

- [源码入口与部署说明](../README.md)
- [架构](qwen3_1_7b_nlu_architecture.md) / [Schema 说明](nlu_schema_v1.md) / [正式 Schema](nlu_schema_v1.json)
- [155 条测试集](nlu_benchmark_cases.json) / [基准报告](qwen3_1_7b_nlu_benchmark.md) / [Runtime 报告](qwen3_1_7b_android_runtime_report.md)
- [Debug APK](../app/build/outputs/apk/debug/app-debug.apk)（模型独立部署）
- [实机验证证据索引](../../physical-memory-v2-validation/README.md)
