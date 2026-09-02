# UI/UX 重构测试报告

2026-09-02，Xiaomi 23078RKD5C（Android 15 / API 35，ARM64），版本 0.5.0 / code 8。**135 项 JVM 测试、14 项选定实机 instrumentation 测试均通过。** 真实麦克风取消、后台/Activity pause、真实锁屏、真实模型回放和页面操作分别保存证据，没有把 Fake 识别结果当成真实模型结果。

## 构建

执行 `./gradlew clean test lint assembleDebug build assembleDebugAndroidTest`：BUILD SUCCESSFUL，1 分 8 秒。新增最后的锁屏测试后，再执行 test/lint/assembleDebug/build/assembleDebugAndroidTest，4 秒通过。最终主 APK 未因测试代码新增而改变哈希。

| 项目 | 结果 |
|---|---|
| JVM | 135 tests；0 failure / error / skip |
| Lint | 0 error；11 warning |
| Debug / Release | 均构建成功 |
| 最终 APK | 70,554,008 bytes；设备已安装 SHA 与构建一致 |
| SHA-256 | `6760363d31ccf558a9ca6b6692d232921ed3916fceb5e00f7fe0f0b51cbaf90d` |

Lint warning 为依赖升级建议、仅 ARM64 的 ChromeOS 提示和 KTX 建议；没有把“通过”写成“零警告”。[构建日志](../../physical-memory-ui-validation/logs/final-build.log)、[最终补充构建](../../physical-memory-ui-validation/logs/final-validation-build.log)、[JVM 汇总](../../physical-memory-ui-validation/jvm-summary.json)、[Lint](../../physical-memory-ui-validation/lint-results-debug.html)、[安装校验](../../physical-memory-ui-validation/apk.json)。

## 实机执行汇总

| 测试 | 实际通过数 | 时间 | 证据 |
|---|---:|---:|---|
| UiUxRefactorTest + HistoryStoreTest + InventoryRepositoryTest | 10 | 37.488 s | [日志](../../physical-memory-ui-validation/logs/ui-room-final.log) |
| UiSpeechDeviceTest：真麦克风与真实模型回放 | 2 | 61.205 s | [日志](../../physical-memory-ui-validation/logs/real-speech-final.log) |
| InventoryUiTest：真实 NLU，A–D 库存回归 | 1 | 44.266 s | [日志](../../physical-memory-ui-validation/logs/real-nlu-ui-final.log) |
| SecureScreenOffTest：真实锁屏 | 1 | 6.408 s | [日志](../../physical-memory-ui-validation/logs/secure-lock-final.log) |

## 状态机与手势

新增 7 项 JVM controller 测试，覆盖按下立即启动、正常松手一次 stop、96 dp 阈值、滑回恢复、取消不 stop、过短/未就绪取消、Processing 禁止重入、多指/系统/生命周期取消、准备失败不可录音。

新增 3 项真实 ASR 控制器的 JVM 边界回归：取消音频即使 opt-in 调试也不保存；400 ms 以下不调用 decoder；达到 30 秒但仍未松手不自动识别。旧解码与复用测试仍通过。

Compose 实机使用实际 pointer down/move/up/cancel 事件验证状态变化：正常松手和取消后滑回各调用一次测试 decoder；上滑取消、短按、多指和系统 cancel 均不调用，未确认时历史仍为空。此部分使用可计数的测试适配器，证据为 [gesture result](../../physical-memory-ui-validation/ui-gesture-result.json)。

## 真麦克风与生命周期

使用真实 AndroidPcmRecorder、已加载的真实 Qwen3-ASR runtime，给 decode/close/save hook 计数：

| 场景 | 麦克风启动 / 释放 | ASR decode | NLU | 音频保存 | 历史 |
|---|---:|---:|---:|---:|---:|
| 上滑取消、切到其它 Activity、回到桌面 | 3 / 3 | 0 | 0 | 0 | 0 |
| 按住录音时实际锁屏 | 1 / 1 | 0 | 0 | 未启用 | 0 |

第一个测试发现设备有安全屏幕锁，其条件分支未执行锁屏。随后单独执行 SecureScreenOffTest：真实打开麦克风、录音中发送 KEYCODE_SLEEP，验证资源释放、回到 Idle、无草稿/历史；唤醒后系统仍处于正常锁屏状态。随后用户手动解锁，已再次核对 deviceLocked=false、MainActivity 位于前台、当前进程未发现崩溃，见 [最终运行状态](../../physical-memory-ui-validation/app-ready.json)。

[真麦克风记录](../../physical-memory-ui-validation/ui-real-microphone.json)、[独立锁屏记录](../../physical-memory-ui-validation/ui-secure-screen-off.json)。取消发生在采集阶段，未先执行 ASR/NLU 再丢弃结果。处理中的原生 ASR 仍按原引擎完成后释放，本报告不宣称它可被中途强制停止。

## 真实语音模型与页面流转

在同一小米手机，通过实际长按手势控制 PcmRecorder 适配器，把此前授权保存的人声 WAV 按采样节奏回放给真实 Qwen3-ASR，再接真实 Qwen3-1.7B 和实际 UI。没有用 Fake NLU 替代此项。

1. 人声“R八放在防潮箱”正常松手后进入 DraftEditor，位置正确。ASR 输出保留“R八”，测试在结构化字段手动改成 R8 后确认，验证现有纠正入口，不增加自动纠错。
2. 同一人声按住后上滑进入取消，再滑回后松手，恰好多一次真实 decode 并进入草稿，取消草稿不追加历史。
3. 人声“钥匙在哪儿”松手后直接打开 ItemDetailSheet，显示当前玄关柜、2 份实例。额外的真实 NLU 文本回归使用“牛奶在哪”及任务 A–D 的原句，验证牛奶完整详情、位置变化不改旧库存、逐份日期和单份删除。

[真实模型回放证据](../../physical-memory-ui-validation/ui-real-replay.json)。这是保存的人声回放，**不是本轮新采集的现场讲话**；真麦克风专项验证用于取消和资源释放。语音查询样本使用已有的“钥匙”录音，牛奶查询另由真实 NLU 文本测试覆盖，未伪造一份“牛奶在哪”的现场录音。

## 页面、历史与删除

- Home 不渲染历史、完整详情或草稿。UPSERT / ADD 进入独立草稿路由，OPEN_ITEM 直接打开共用 Sheet。
- 原文可以修改后重新解析，结构化物品名、位置、数量、量词与每份日期均可编辑。确认前主库存不变。
- 空历史显示说明。完成操作形成历史行，Room 关闭重开后仍在，同操作 key 不重复追加。
- 点击新增 3 份的旧历史，在删除一份后显示当前 5 份；不是操作当时的 6 份快照。
- 每个库存实例都有日期（含“未记录”）与删除按钮。点击删除先弹对话框，取消后数据库逐项相同；确认后仅所选行消失，Sheet 留在原处，数量刷新。
- 继续逐份删除至零后 Item、位置与 Sheet 都保留，显示“暂无库存实例”。
- 找不到物品仅轻提示，不自动新建。返回或关闭 Sheet 不写库存。

[导航与删除机器记录](../../physical-memory-ui-validation/ui-navigation-result.json)。测试数据库是独立内存库或显式命名的 history-test 临时库，没有清空正式数据库。

## 截图检查

已实际查看并检查：

| 场景 | 截图 |
|---|---|
| 正式 Home idle | [解锁后首页](../../physical-memory-ui-validation/screenshots/home-after-unlock.png) |
| Home recording | [录音](../../physical-memory-ui-validation/screenshots/home-recording.png) |
| Home cancel-armed | [取消区域](../../physical-memory-ui-validation/screenshots/home-cancel-armed.png) |
| HistoryScreen | [历史](../../physical-memory-ui-validation/screenshots/history.png) |
| DraftEditorScreen | [逐份编辑](../../physical-memory-ui-validation/screenshots/draft-editor.png) |
| ItemDetailSheet | [完整详情列表](../../physical-memory-ui-validation/screenshots/item-detail.png) |
| DeleteConfirmationDialog | [二次确认](../../physical-memory-ui-validation/screenshots/delete-confirmation.png) |
| 键盘展开时确认按钮 | [滚动后可点击](../../physical-memory-ui-validation/screenshots/draft-keyboard-actions.png) |
| 横屏有限高度 | [滚动详情](../../physical-memory-ui-validation/screenshots/detail-landscape.png)、[删除弹窗](../../physical-memory-ui-validation/screenshots/delete-landscape.png) |

录音浮层最初会遮挡按钮，已移到顶部独立区域；按钮取消文字仍可见。键盘展开后，通过滚动可完整看到并实际触摸确认按钮。横屏时，列表可滚动到最后一份，删除弹窗的确认/取消均在屏幕内。长列表超出首屏是滚动内容，不是不可操作的溢出。没有对所有屏幕尺寸/字体缩放做全面认证；已验证本机竖屏、横屏和键盘遮挡场景。

## 数据与变更范围

重构前与全部测试后比较主库全部列和全部行：11 个 Item、3 个 InventoryUnit、2 个确认回执完全一致；数据库版本仍 2，integrity_check=ok。[数据核验](../../physical-memory-ui-validation/data-verification.json)。历史为新增独立数据库，旧操作不作虚构回填。

19 个已有核心文件 SHA 均一致，覆盖 NLU、Prompt、Schema/GBNF、native runtime、DraftFactory/Validator、主 Room 表/DAO、核心 Repository。[保护范围核验](../../physical-memory-ui-validation/protected-verification.json)。

## 复现与边界

`./scripts/run-ui-validation.sh ZTSCJJCM4DZD7HRW` 构建并执行选定隔离测试；实际输出必须包含 OK，不能把 adb shell 的退出码 0 当作断言成功。不要在有用户数据的手机运行 connectedDebugAndroidTest 的卸载收尾。

锁屏步骤单独放在最后，会要求用户随后正常解锁：

```sh
adb -s ZTSCJJCM4DZD7HRW shell am instrument -w -r \
  -e class dev.local.physicalmemory.SecureScreenOffTest -e uiSecureScreenOff true \
  dev.local.physicalmemory.test/androidx.test.runner.AndroidJUnitRunner
```

历史是简单完成日志，非主库存的原子事务或历史快照。模型性能与语义准确率沿用 V2，本轮未改模型、未重新跑 155 条模型基准，也不把 UI 测试当作准确率提升证据。
