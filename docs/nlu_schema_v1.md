# NLU Schema v1

正式文件为 `app/src/main/assets/nlu/nlu_schema_v1.json`，docs/nlu_schema_v1.json 为一致镜像。使用 JSON Schema Draft 2020-12，通过 4 个互斥 oneOf 分支描述结果；所有对象 additionalProperties=false，所有键必填。

| action | 除 schema_version/action/issues 外的字段 | 用途 |
|---|---|---|
| UPSERT_ITEM_INFO | item；location {op,value} | 只提出物品信息，程序查库后建立新建或更新草稿 |
| PROPOSE_ADD_UNITS | item；count；unit_label；location；default_expiry | 只提出 N 份可编辑库存实例 |
| OPEN_ITEM | item | 统一打开完整详情，包含查询、减少、消耗和删除语言 |
| UNKNOWN | 无 | 没有可支持的物品操作 |

每份结果固定 schema_version="1.0"，包含 issues。没有 itemId、unitId、databaseId、confidence 或额外动作。

## 值与约束

- item：string/null，最长 80 个 Unicode code point；物品名不做自动纠错。
- location：位置字符串最多 200 字。UPSERT 的嵌套 oneOf 要求 SET 对应非空字符串、KEEP 对应 JSON null。KEEP 表示没提到位置，绝不清空旧位置。ADD 的 location=null 表示复用现有 Item 的位置。
- count：integer/null。抽取阶段允许保留无效数量，确认时必须为 1–100，并且 DraftUnit 数量完全一致。对已抽取为 null、0、负数或超限的数量，业务层不会改成 1；模型仍可能在原句缺少数量时猜出 1，属于基准记录的语义错误，需要用户核对。
- unit_label：string/null，最长 16 字，只用于草稿中的量词。
- default_expiry：null，或必含 value 和 source_text 的对象。value 为 YYYY-MM-DD/null，source_text 为原词/null（最多 80 字）。真实日历合法性由 Validator 校验，未记录日期以 null 保存。

issues 仅允许 MISSING_ITEM、MISSING_COUNT、INVALID_COUNT、INVALID_DATE、AMBIGUOUS_ITEM、AMBIGUOUS_LOCATION、AMBIGUOUS_DATE、UNSUPPORTED_OPERATION；最多 8 项且不重复。出现提示时用户需核对后确认，不使用模型自报置信度。

## 硬约束与验证

GBNF 由固定版本 llama.cpp 的脚本生成并随 APK 打包，在 token 采样前执行。日期正则使用 `[0-9]`，因为该版本转换器不能正确转换 `\d`。语法负责结构、分支和字符/长度边界；Kotlin 再检查严格类型、键、枚举唯一性和 op/value 一致性，DraftValidator 负责日历与业务语义。

```sh
python3 third-party/llama.cpp/examples/json_schema_to_grammar.py \
  app/src/main/assets/nlu/nlu_schema_v1.json > app/src/main/assets/nlu/nlu.gbnf
```

主机使用 jsonschema 4.25.1 校验 Draft 2020-12 Schema 及全部 155 条人工编写的期望值，并按同一 Schema 校验实机结果。不会修补失败 JSON、猜名称、选择数据库 ID 或把解析错误当成可执行操作。
