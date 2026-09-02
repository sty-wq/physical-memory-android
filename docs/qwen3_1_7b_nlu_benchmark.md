# Qwen3-1.7B 本地 NLU 基准报告

2026-09-02，Xiaomi 23078RKD5C / Android 15，官方 Q8_0，llama.cpp ARM64 CPU。最终 Prompt v4、thinking 关闭。155 条全部实际推理完成，唯一 case ID 为 155，无重试替换结果。

**Full Result Accuracy 为 143/155（92.26%）；135 条留出集为 125/135（92.59%）。JSON Valid Rate 为 155/155。** 结构合法已稳定，但型号数字、日期和部分意图仍会出错；此版本适合通过可编辑草稿进行人工确认，不应视为自动记账或无需核对的日期工具。

## 数据与统计口径

[nlu_benchmark_cases.json](nlu_benchmark_cases.json) 是事先编写的 155 条合成测试语句（curated_synthetic），并非真实线上样本。40 条 UPSERT、45 条 ADD、45 条 OPEN、25 条 UNKNOWN；覆盖位置描述、增加库存、默认日期、未知请求、缺失/无效字段以及 AD两百、R吧、七零到两百、XM五等 ASR 风格误差。currentDate 固定为 2026-09-02。

20 条 calibration 用于调试 Prompt；其余 135 条 evaluation 在最终 v4 才统一评估。未根据留出错误改写 gold labels，也未加入 fuzzy/别名/自动纠错。结果不能推导为真实世界总体准确率。最终有一项业务防护：模型标注 AMBIGUOUS_DATE 时，草稿各份日期留空；这不修改原始 NLU 结果，原始错误仍计为失败。

每个字段仅在预期结构存在该字段时进入分母；字段比较包含 null。Location 比较完整 SET/KEEP/value，Expiry 比较 value 和 source_text。FullResult 比较整个 JSON（issues 作为无序集合），额外/缺失字段、误报 issue、日期或量词差异都记错。JSONValid 经过 Draft 2020-12 Schema 和应用严格解码双重检查。不能用 JSONValid 替代语义准确率。

## 最终准确率

| 指标 | 全部 155 条 | 留出 135 条 |
|---|---:|---:|
| Action | 149/155（96.13%） | 131/135（97.04%） |
| Item | 125/130（96.15%） | 111/115（96.52%） |
| Location | 82/85（96.47%） | 73/75（97.33%） |
| Count | 43/45（95.56%） | 38/40（95.00%） |
| UnitLabel | 44/45（97.78%） | 39/40（97.50%） |
| Expiry | 43/45（95.56%） | 38/40（95.00%） |
| JSONValid | 155/155（100.00%） | 135/135（100.00%） |
| FullResult | 143/155（92.26%） | 125/135（92.59%） |

20 条校准集在最终 v4 上的 FullResult 为 18/20（90%），单列在[校准统计](../../physical-memory-v2-validation/nlu-final-v4-calibration-scores.json)。完整输出与逐项差异：[原始 JSONL](../../physical-memory-v2-validation/nlu-final-v4.jsonl)、[全部统计](../../physical-memory-v2-validation/nlu-final-v4-scores.json)、[留出统计](../../physical-memory-v2-validation/nlu-final-v4-heldout-scores.json)。

## 性能

本轮 155 条连续执行约 1,157.6 秒。首条包含模型/context 初始化 3,776 ms、prefill 12,532 ms、总 NLU 23,799 ms；其余 154 条复用模型。进程冷启动不代表 OS 文件页缓存已清空。

| 耗时（ms） | 中位数 | P95 | 最大值 |
|---|---:|---:|---:|
| modelLoadMs | 0 | 0 | 3776 |
| prefillMs | 626 | 843 | 12532 |
| ttftMs | 750 | 922 | 12681 |
| decodeMs | 7034 | 11642 | 15262 |
| totalNluMs | 7725 | 12453 | 23799 |

promptTokens 中位数 661（最大 674），相同前缀缓存中位数 647 token，generatedTokens 中位数 34（最大 75）。预填充加速来自精确前缀缓存；每次删去前次回答和变化的输入后缀，没有跨请求业务记忆。TTFT 不含 modelLoadMs；totalNluMs 包含加载、原生推理、Kotlin 解码。P95 为排序后 nearest rank。

本轮 NLU 单独驻留 PSS 3,420,684 KiB（3.26 GiB），采样峰值 4,025,529 KiB（3.84 GiB）。200 ms 采样不能保证捕捉每个瞬时峰值，温度、后台负载会影响时延。更多 RAM 和全链路数据见 [Runtime 报告](qwen3_1_7b_android_runtime_report.md)。

## Thinking 决策

同一 v3 Prompt、同一 20 条校准语句在同一手机顺序测量；这组对照发生在 v4 最终基准之前，没有随机交错或严格温控。两种模式都使用官方模板；thinking 模式在结束思考后约束 JSON，原始思考文本不保存。

| 模式 | FullResult | JSONValid | 总耗时中位数 | P95 | 限制 |
|---|---:|---:|---:|---:|---|
| thinking off | 16/20（80%） | 20/20 | 5214.0 ms | 9732 ms | 384 输出 token / 60 s |
| thinking on | 13/20（65%） | 18/20 | 32907.0 ms | 90015 ms | 512 输出 token / 90 s |

thinking on 有两次 90 秒超时，全部计入失败；它们只有整体耗时，没有伪造分段指标。thinking on 未显示准确率收益且显著变慢，所以默认 off。此结论限于该样本与预算，不能声称无限预算 thinking 一定更差。v4 增加少量抽取约束后只跑 off 的全量测试，不能把它称为 v4 的配对 thinking 实验。

原始对照：[off](../../physical-memory-v2-validation/calibration-off-v3.jsonl)、[on](../../physical-memory-v2-validation/calibration-on-v3.jsonl)。v1/v2 调试记录也保留在证据目录，不计入最终 155 条统计。采样 top-k=20，temperature off=0.7/on=0.6，top-p off=0.8/on=0.95，seed=42。

## 全部 12 条不完全正确结果

下表保留原始输出。完整预期值及差异可见上面的 scores.json。

| ID | 输入 | 实际输出 |
|---|---|---|
| nlu-011 | 牛奶少了一袋 | `{"schema_version":"1.0","action":"UNKNOWN","issues":[]}` |
| nlu-017 | AD两百放器材柜 | `{"schema_version":"1.0","action":"PROPOSE_ADD_UNITS","item":"AD两百","count":200,"unit_label":"百","location":"器材柜","default_expiry":null,"issues":[]}` |
| nlu-035 | 耳机在电脑包里 | `{"schema_version":"1.0","action":"OPEN_ITEM","item":"耳机","issues":[]}` |
| nlu-047 | 七零到两百在摄影包 | `{"schema_version":"1.0","action":"UPSERT_ITEM_INFO","item":"摄影包","location":{"op":"SET","value":"七零到两百"},"issues":[]}` |
| nlu-081 | 增加一台AD两百 | `{"schema_version":"1.0","action":"PROPOSE_ADD_UNITS","item":"AD","count":200,"unit_label":"台","location":null,"default_expiry":null,"issues":[]}` |
| nlu-091 | 新买一盒饼干，下周五过期 | `{"schema_version":"1.0","action":"PROPOSE_ADD_UNITS","item":"饼干","count":1,"unit_label":"盒","location":null,"default_expiry":{"value":"2026-09-07","source_text":"下周五"},"issues":[]}` |
| nlu-093 | 三袋牛奶，一袋后天过期，两袋下周五过期 | `{"schema_version":"1.0","action":"PROPOSE_ADD_UNITS","item":"牛奶","count":3,"unit_label":"袋","location":null,"default_expiry":{"value":"2026-09-03","source_text":"后天"},"issues":["INVALID_DATE","AMBIGUOUS_DATE","INVALID_COUNT","AMBIGUOUS_ITEM"]}` |
| nlu-117 | 七零到两百还有吗 | `{"schema_version":"1.0","action":"UNKNOWN","issues":[]}` |
| nlu-129 | 打开护照记录 | `{"schema_version":"1.0","action":"UNKNOWN","issues":[]}` |
| nlu-152 | 我的手机没电了 | `{"schema_version":"1.0","action":"OPEN_ITEM","item":"手机","issues":[]}` |
| nlu-154 | 增加牛奶 | `{"schema_version":"1.0","action":"PROPOSE_ADD_UNITS","item":"牛奶","count":1,"unit_label":"瓶","location":null,"default_expiry":null,"issues":[]}` |
| nlu-155 | 增加零袋牛奶 | `{"schema_version":"1.0","action":"PROPOSE_ADD_UNITS","item":"牛奶","count":0,"unit_label":"袋","location":null,"default_expiry":null,"issues":[]}` |

可见限制：

- “AD两百”可能被误当数量 200；确定性每批 1–100 份限制会阻止确认这种结果，但不会替用户把型号改成 AD200。
- “下周五”可能输出错误但格式合法的日期；须在逐份日期输入框核对，日历合法性校验无法证明它符合原句。
- “增加牛奶”被猜成 1 瓶，此类语义缺失不能只依赖模型自报 issues；界面始终要求显式确认。
- “牛奶少了一袋”等少数句子未正确进入 OPEN；本阶段仍没有模型删除路径，因此此错误不会自动扣库存。
- 多份不同日期不自动分配；AMBIGUOUS_DATE 时草稿清空默认日期并要求核对。其余明确给出的单个默认日期可逐份修改或清空。

## 复现

用 `NluNativeTest` 的 benchmark 开关、固定日期、固定 case 文件执行；使用 `scripts/evaluate-nlu.py` 独立校验与评分（Python jsonschema 4.25.1）。手机上的正式数据库不参与纯 NLU 基准。

```sh
source ./env.sh
adb -s ZTSCJJCM4DZD7HRW shell 'run-as dev.local.physicalmemory sh -c "cat > files/nlu-benchmark-cases.json"' < docs/nlu_benchmark_cases.json
adb -s ZTSCJJCM4DZD7HRW shell am instrument -w -r -e class dev.local.physicalmemory.NluNativeTest#benchmark -e nluNative true -e thinking false -e report final-v4 dev.local.physicalmemory.test/androidx.test.runner.AndroidJUnitRunner
adb -s ZTSCJJCM4DZD7HRW exec-out run-as dev.local.physicalmemory cat files/nlu-final-v4.jsonl > results.jsonl
python3 scripts/evaluate-nlu.py results.jsonl --output scores.json
```

实际使用的模型、源码提交、构建与 APK 区别见 Runtime 报告。最后的草稿防护与界面回归修改未改变 v4 Prompt、NLU Schema 或 native runtime；最终安装包另做实机 E2E 复测。
