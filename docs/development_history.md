# Physical Memory Android · OPPO 主设备 0.5.5

0.5.5：“调整信息”页可同时新增库存、逐份调整到期日期、点选删除库存。显示调整后数量，删除需逐份确认且可撤销；最后“保存调整”统一生效，取消不写库。零库存物品也能直接新增并选择日期。见[调整库存验证](../physical-memory-oppo-validation/item-edit-stock/README.md)。

0.5.4：底部新增“物品”页，列出全部已存物品（含零库存物品）。点击打开共用信息卡，可调整名称、位置和逐份到期日期，添加库存或逐份删除。调整名称保留原物品/库存 ID，重名会阻止保存。见[物品页面说明](docs/items_page.md)。

0.5.3：修复“冰箱里放了三袋牛奶”被当成只改位置的问题。带数量的存放语句生成库存草稿，数量可改、到期日期逐份选择；位置草稿也可开启“同时添加库存”手动补填。仅修改位置继续保留原库存。见 [OPPO 修复与验证](../physical-memory-oppo-validation/quantity-fix/README.md)。

0.5.2：以 OPPO Find X8s 为唯一开发与验收设备；保留底部按住说话，修复大字体与键盘同时显示时的输入区空间。在本机大字体设置下，日期页使用年/月选择与完整日期列表，避免七列日历裁切。

0.5.1：录音按钮固定在首页底部导航上方；每份库存的到期日期通过独立日历选择页设置，支持确认、返回取消和清空。见 [本次调整与验证](../physical-memory-ui-validation/v0.5.1/README.md)。

在既有 V2 离线链路上完成页面重构：**首页输入 / 历史 / 独立草稿编辑页 / 共用物品详情卡片**。首页只保留文本与语音输入；语音改为按住说话、松开发送、上滑取消（96 dp，滑回可恢复），不再点击切换录音。首次需要准备语音模型，等待按钮显示“按住说话”。

查询和历史都打开同一个 ItemDetailSheet；删除一份需要二次确认，删除后卡片原地刷新。草稿必须由用户确认后保存。历史从本版开始记录已完成操作，旧物品不会被伪装成历史。核心模型、NLU Prompt/Schema、Item/InventoryUnit 和库存规则保持不变。

当前唯一主开发与验收设备为 **OPPO Find X8s**。设备序列号由 adb 动态发现，旧小米与模拟器报告仅作为历史记录。本阶段不执行 Git 操作或其他设备兼容性测试。已有用户数据必须保留：覆盖安装，不卸载、不 pm clear，不对用户手机运行 AGP connectedDebugAndroidTest 的卸载收尾。

- [UI/UX 实现报告](docs/ui_ux_refactor_implementation_report.md)
- [实机测试与截图](docs/ui_ux_refactor_test_report.md)
- [页面架构](docs/ui_ux_refactor_architecture.md)
- [长按语音状态机](docs/hold_to_talk_interaction.md)
- [OPPO 设备档案](docs/oppo_find_x8s_device_profile.md)
- [OPPO 主设备验收](docs/oppo_find_x8s_primary_validation.md)
- [OPPO 内存报告](docs/oppo_find_x8s_memory_report.md)
- [OPPO 性能报告](docs/oppo_find_x8s_performance_report.md)

```sh
source ./env.sh
./gradlew clean test lint assembleDebug build
./scripts/install-and-launch.sh
# 选定隔离测试与真实模型回放（不会清空正式数据库）：
./scripts/run-ui-validation.sh
```

下面保留 V2 及更早版本的技术说明；其中旧版单页布局、点击开始/停止等交互已由本次重构替代。

---

# Physical Memory Android V2

V2 已接通本地 Qwen3-ASR 0.6B → Qwen3-1.7B Q8_0 → 可编辑草稿 → 用户确认 → Room。语音识别后先核对草稿，确认后才保存。每个 Item 共享唯一位置，每份库存独立记录到期日期；删除选定实例必须二次确认，库存归零保留 Item。

小米实机已安装并打开，原有 10 条物品完整保留。125 项 JVM、7 项 Room 与真实 Qwen UI A–D 均通过；155 条基准完整结果准确率 92.26%，JSON 合法率 100%。详细错误与时延见以下 V2 报告。

Development phone: Xiaomi 23078RKD5C, serial `ZTSCJJCM4DZD7HRW`. Do not uninstall or clear the main application. Existing v1 records are migrated in place with zero inferred stock. The V2 flow uses exact names and allows manual name correction; the old fuzzy parser is not in the active path.

```sh
source ./env.sh
./gradlew clean test lint assembleDebug build
adb -s ZTSCJJCM4DZD7HRW install -r app/build/outputs/apk/debug/app-debug.apk
python3 scripts/setup-qwen3-nlu.py --directory ../../work/v2/model --serial ZTSCJJCM4DZD7HRW
adb -s ZTSCJJCM4DZD7HRW shell am start -n dev.local.physicalmemory/.MainActivity
```

The model is separate from the APK. Existing ASR model deployment is preserved; see `scripts/setup-qwen3-model.py` and `scripts/deploy-qwen3-model.py`. The native build uses the bundled, pinned llama.cpp source and SDK-local NDK 28.2.13676358 / CMake 3.31.6. No cloud endpoint or runtime internet permission is needed.

Read `docs/physical_memory_v2_implementation_report.md` for completed acceptance results, `docs/qwen3_1_7b_nlu_benchmark.md` for accuracy, and `docs/qwen3_1_7b_android_runtime_report.md` for device limits and measured performance. Native and UI instrumented tests use explicitly selected classes and an isolated database; do not run AGP connected-test teardown on a phone containing user records.

---

The remainder is historical V0/V1 documentation. Its automatic-write behavior and former active fuzzy lookup do not describe V2.

# 物品记忆 · Physical Memory（Qwen3-ASR 0.3.1）

单页 Android 本地物品记忆应用。输入 `钥匙放在玄关柜` 记录位置，输入 `钥匙在哪` 查询位置；同名更新保留 ID 和创建时间，首页展示最近 20 件物品。已有模糊查询行为保持不变。

0.3.1 默认使用 **Qwen3-ASR 0.6B INT8 离线识别**，保留 Android 系统 ASR。点击开始，等待“正在听…”后说话，**再次点击结束**，等待“正在识别…”得到 Final；Final 自动进入原有记录/查询流程一次。目录仍为 physical-memory-v0，包名与数据库不变。

## 语音与模型部署

Qwen3 模型独立部署，约 1 GB，不包含在约 61 MiB 的 APK 中。首次安装/新设备按下列顺序执行；当前模拟器已经部署并打开，可直接测试。

```sh
source ./env.sh
./gradlew assembleDebug
adb devices -l
# 模拟器未运行时执行；不要重复启动
./scripts/start-voice-emulator.sh -memory 4096
# 另开终端，source ./env.sh，等待模拟器启动完成后执行
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
python3 scripts/setup-qwen3-model.py
python3 scripts/deploy-qwen3-model.py --serial emulator-5554
adb -s emulator-5554 emu avd hostmicon
adb -s emulator-5554 shell am start -W -n dev.local.physicalmemory/.MainActivity
```

setup 校验指定官方归档 SHA-256；deploy 默认通过 run-as 写入应用私有目录，并以应用身份逐文件校验后写完成标记及 sync。本次实机目标已由用户改为 Xiaomi 23078RKD5C，serial `ZTSCJJCM4DZD7HRW`；手机部署时将 emulator-5554 换为该 serial，不执行 emulator/hostmicon 命令。主应用只覆盖安装，不卸载或清除数据。新部署的离线模型与官方测试 WAV 位于应用私有 `files/asr_models/`。旧模拟器外部目录中的同一模型仍兼容。小米手机对 adb 创建的外部目录拒绝应用访问，不能只以 adb 可读判定部署成功。

首次点击才加载一次模型，等待“正在听…”再开口；后续录音复用模型。再次点击停止，或录满 30 秒自动结束。Qwen3 没有 Partial，不会一边说一边显示文字。取消、切后台或切引擎会丢弃该会话，文字输入仍可用。拒绝权限后可继续文字输入，应用不会反复自动请求。

展开“ASR 调试信息”可查看 Model/Decode/RTF/内存。在 Debug 包显式开启“保存本机测试录音”才保存 `files/asr_debug/<sessionId>.wav`；默认关闭，release 包不能开启，关闭开关不会删除已有 WAV。

```sh
python3 scripts/collect-qwen3-debug.py --serial emulator-5554 --out ../physical-memory-qwen3-validation/manual-test
```

Mac 模拟器使用原有 CoreAudio 进程级清理补丁，限定 Apple Silicon 与 Emulator 37.1.11 / build 15917651。它不改模拟器原文件或系统安全设置，详情见 [麦克风排查](docs/emulator_microphone_fix.md)。Android RECORD_AUDIO 与 macOS 麦克风授权分别需要允许。单独 hostmicon 不能修复旧 Emulator 反复录音的回调错误；当前 AOSP 模拟器没有可用的系统识别服务。

用户已将实机测试目标改为 **Xiaomi 23078RKD5C**，当前手机测试状态和逐句结果见 [Xiaomi 测试表](docs/qwen3_asr_xiaomi_test.md)；实现及模拟器历史证据见 [本阶段报告](docs/physical_memory_qwen3_asr_report.md)。

## 构建

```sh
source ./env.sh
./gradlew clean
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew build
```

共用工具链：`/Users/bia/Documents/Codex/android-toolchain`。无需 Android Studio；新 shell 中重新 source，不改系统 Java。Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 安装运行

```sh
source ./env.sh
emulator -list-avds
./scripts/start-voice-emulator.sh -memory 4096 # 本机语音测试使用此脚本
adb -s emulator-5554 shell getprop sys.boot_completed
# 上条输出 1 后
./scripts/install-and-launch.sh
```

不要重复启动已有模拟器。安装脚本默认只操作 `emulator-5554`；真机需明确设置 `ANDROID_SERIAL`。

## 输入规则

- 记录：`放在`、`放到`、`放进`、`在`、`放`。按最早分隔位置切分，同一位置长词优先；支持 `连花清瘟在放药的柜子里`。
- 查询：`在哪`、`在哪里`、`在哪儿`、`放哪了`，也支持 `放在哪`、`放在哪里`、`放在哪儿`。
- 查询先于记录匹配；完整句尾匹配。去除外围引号、常用标点和空白；折叠重复空格与汉字间多余空格，保留英文单词边界。
- 一次一个命令；空物品、空位置、多个指令/内部句读、过长字段返回 Unknown，不写数据库。未支持的疑问句也不会误保存成位置。
- 上限：输入 512 字符，物品名 80 字符，位置 200 字符。记录仍按精确名称更新，区分英文大小写；查询优先精确匹配，未命中才模糊检索全部物品。
- 位置里的 `放药的柜子`、`存放杂物的箱子` 等定语会完整保留，不当成第二条命令。
- 仍是窄范围规则语法；名字包含“在/放”或更复杂的嵌套句时可能无法识别。V0 不理解任意自然语言。

## 代码

`domain/PhysicalMemory` 是设备无关的业务入口；`CommandParser` 解析文本，`ItemRepository` 是可替换的存储契约，`RoomItemRepository` 操作事务 DAO。`HomeViewModel` 对外暴露 StateFlow，Compose 使用生命周期感知订阅。手动构造依赖，没有 DI 框架。

`domain/matching/ItemMatcher` 是可替换的名称检索接口，返回未找到、单个匹配或待确认候选。当前 `FuzzyItemMatcher` 在后台使用字符编辑距离（含相邻字颠倒），兼容大小写、全半角和空白；不理解同义词、拼音或语义。候选只包含已有物品 ID 和名称，位置始终按 ID 从数据库读取。将来可注入语义检索实现，保留精确查询、候选确认与数据库取值流程。查询不会修改名称、位置或时间，也不会自动把相近名称合并。

数据库位于应用私有目录 `databases/physical-memory.db`；表 `items` 包含 `id / name / location / createdAt / updatedAt`。Room schema 位于 `app/schemas/`。未启用 destructive migration；未来改表必须显式增加数据库版本和迁移。

`voice/SpeechInput.kt` 是带 sessionId 的 StateFlow 输入契约，Android / Qwen3 / 旧 Sherpa / Fake 共享它。HomeViewModel 的统一 submitCommand(text) 处理手动文本和 Final；两层会话校验防止迟到与重复回调。业务层仍不知道具体 ASR 引擎。

应用请求 RECORD_AUDIO，配置 RecognitionService queries；没有添加 INTERNET，没有 TTS、热词、语义纠错、账号或云同步。普通系统识别服务是否联网取决于设备上的服务提供者；“System 可用”不等于“离线可用”。卸载应用或清除数据会删除本地记录。调试转写与实体/时间指标仅保存在本机 `files/asr/events.jsonl`；Qwen3 原始 WAV 只有 Debug 显式开启保存时才写入本机。

## 测试

```sh
source ./env.sh
./gradlew test
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

114 项 JVM 测试覆盖 Parser、模糊匹配器、业务匹配契约、语音状态、离线 Qwen3 状态/资源释放和 ViewModel。设备测试包含隔离 Room/Compose、Fake 双引擎输入、真实 Sherpa 官方文件解码、系统能力 probe，以及拒绝麦克风权限后的文字输入和 Activity 重建。

UI 验收测试会写入/更新 `钥匙` 并假定 `护照` 未记录。本机 AGP 的 connected 测试收尾会卸载被测应用和测试包（连同应用数据），请只在专用模拟器测试安装中运行，不要用于有个人记录的设备。Room 测试本身使用隔离内存数据库及单独的临时测试库。测试没有自动清空正式数据库的代码。

对当前有记录的模拟器复测模糊查询，使用覆盖安装和定向 instrumentation，不运行 connected 测试收尾：

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class dev.local.physicalmemory.RoomRepositoryTest,dev.local.physicalmemory.FuzzyChoiceUiTest,dev.local.physicalmemory.FuzzyLookupRegressionTest dev.local.physicalmemory.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am start -n dev.local.physicalmemory/.MainActivity
```

`FuzzyLookupRegressionTest` 要求正式库中已经存在“连花清瘟”，只查询并核对全库记录未变化；其他两个类使用隔离数据库。不要用旧的写入验收类替代此命令中的类名。

主要页面有 `@Preview` 假数据，便于后续单独重做 UI。

## 旧 V1 日志与 benchmark（历史工具）

```sh
source ./env.sh
adb devices -l
# 明确选择用户确认的手机 serial，不能省略 -s
adb -s PHONE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s PHONE_SERIAL shell am start -n dev.local.physicalmemory/.MainActivity
python3 scripts/collect-asr.py --serial PHONE_SERIAL --out ../physical-memory-v1-validation/find-x8s
python3 scripts/asr-benchmark.py --events ../physical-memory-v1-validation/find-x8s/events.jsonl --labels docs/asr-session-labels.csv --out ../physical-memory-v1-validation/find-x8s/benchmark
```

测试句在 `docs/asr-test-phrases.csv`。由人确认每次实际说的句子并在 labels CSV 填写 sessionId、referenceId、网络状态。导出脚本不会从识别结果反推原话；逐项比较命令类型、物品实体和位置实体。无标签/无观测数据保留为空，不生成虚构准确率。仅当设备实际断网测试后才标记离线成功。

旧 Sherpa 依赖和小模型已包含，用于同 WAV 对照。Qwen3 请使用前述独立部署脚本。重取时运行 `python3 scripts/setup-asr-assets.py`，脚本校验固定 SHA-256。来源与模型授权状态见 `third-party/NOTICE.md`。

## 验证报告

当前 Qwen3 报告：`docs/physical_memory_qwen3_asr_report.md`；Xiaomi 表格：`docs/qwen3_asr_xiaomi_test.md`（原 Find X8s 路径保留跳转）；证据：`../physical-memory-qwen3-validation/`。

V0 历史验收在 `../docs/physical_memory_v0_report.md`、`../docs/physical_memory_v0_parser_fix.md` 与 `../docs/physical_memory_v0_fuzzy_matching.md`。V1 报告在本项目 `docs/`，日志与截图在 `../physical-memory-v1-validation/`。
