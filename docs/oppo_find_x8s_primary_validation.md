# OPPO Find X8s Primary Validation

> 版本说明：本页主体为 0.5.2 / prompt v4 的阶段记录。后续用户反馈触发了 0.5.3 数量/到期日期修复（prompt v5），见[新版本修复验证](../../physical-memory-oppo-validation/quantity-fix/README.md)。旧版 NLU 正确率与时延不能直接当作新版结论；真人 8 句和连续 10 轮仍待完成。

状态：进行中；尚未达到全部验收条件。真人语音、10 轮、锁屏/解锁完成后更新。本轮已发现 NLU 对“牛奶少了一袋”的语义错误，不能标记所有行为通过。

## Device / Android / ColorOS / CPU / RAM

唯一设备 OPPO Find X8s（OPPO PKT110 / OP5DCBL1），Android 16 / API 36，ColorOS 显示版本 16.0.10，ROM V16.1.0。MT6991，8 核，内核 RAM 11.12 GiB。[完整设备档案](oppo_find_x8s_device_profile.md)。未测试小米、模拟器或其他手机；未运行 Git 命令、未新建项目。

## Build / Install / Data

沿用 physical-memory-v0，包名取自项目 dev.local.physicalmemory，版本 0.5.2 / code 10。开始阶段与再次 clean/test/lint/assembleDebug/build 均成功，后者耗时 1m33s；Debug 135 项 JVM 测试均通过，Lint 0 错误/12 警告。随后只更新真人测试夹具的失败记录/重试处理，并重新构建测试 APK。Release 仍未配置正式签名，build 产生的 unsigned APK 不作为可发布安装包。

OPPO 初始没有安装本应用。通过 install -r 安装 debug APK 和测试组件；ColorOS 增强安装保护由用户正常确认。RECORD_AUDIO 通过系统“使用时允许”授予，未修改安全策略；App 不需要通知权限。模型复制到 App 私有 files 目录，UI 升级均覆盖安装，没有卸载或清空。

测试使用 in-memory 或明确命名的 oppo-restart-* 数据库，不写生产库存。未迁移其他设备的数据。单个 InventoryUnit 删除确认/历史/位置共享仍由原业务代码负责。

## ASR Model / NLU Model

ASR：既有 sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25，24 文件共 1,000,089,677 字节（含官方测试 WAV）；部署逐文件 App UID SHA-256 校验。NLU：既有 Qwen3-1.7B-Q8_0.gguf，1,834,426,016 字节，SHA-256 061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a。均来自 Mac 已存在文件，没有联网下载。模型不进入 APK。

## Model Load / Memory

真实加载 ASR 3438 ms、NLU 5045 ms；同进程同时驻留成功，Both loaded PSS 3.68 GiB。已测 A–G；10 轮 H 尚待录音回放。见[内存报告](oppo_find_x8s_memory_report.md)。基线含 instrumentation；C 在释放 ASR 后测量，有 allocator 残留可能。

## ASR Accuracy Observations

官方 WAV 解码已运行；该结果不代表 OPPO 真人声学输入准确率。首轮测试录到 0.4 s 音频，峰值 30/32768、RMS 9.56/32768，模型返回 NO_MATCH；原始 WAV 和失败日志保存在 live-attempt-1，未当作有效语音。之后普通页面日志观察到“AD二百放在器材柜儿。”，但未确认实际说法且不在本轮完整计时夹具中，不能计入准确率。已暂停，请用户准备后重做 8 句指定语音。夹具现会保留失败记录，并允许原题重录，成功样本与失败次数分开统计。不加 alias、hotword、fuzzy 或 LLM correction。

## NLU Accuracy Observations

14 条真实本地推理的 JSON/Schema 均合法，13 条 action/slots 符合期望；“牛奶少了一袋”输出 UNKNOWN，应为 OPEN_ITEM。其余 5 种牛奶查询/删除意图均 OPEN_ITEM。具体 raw JSON 与各阶段计时见[性能报告](oppo_find_x8s_performance_report.md)。测试 runner OK 仅表示技术断言成功，不能掩盖语义失败。

## Full Pipeline Latency

待真人麦克风与同机 WAV 的 10 轮记录。记录松手、录音结束、ASR final、NLU start/final、draft/result ready，查询结果不冒充草稿。

## Hold-to-talk

最终 UiUxRefactorTest 3 项通过，实际 Compose 指针输入验证录音/取消/滑回/松手/短按/多指/Processing；该组使用计数 speech adapter，并非真人 ASR。阈值保持 96 dp；自动大幅上滑 140 dp 可触发，滑回恢复。真人操作舒适度待反馈。

## UI

最终 UI 3 项全部通过（37.815 s），真实 NLU UI + Room/History 9 项通过（38.125 s）。首页独立输入、Draft 独立导航、Item 更新 old→new、三个库存独立到期日、详情完整显示、历史打开当前状态、逐个删除二次确认、原地刷新、库存归零保留 Item 均覆盖。

本机 1216×2640，density override 620，fontScale 1.6（sw314dp）。保留用户设置，修复键盘弹出时 footer 挤掉输入区；录音错误移入可滚动区，IME 显示时隐藏重复说明。按钮固定底部，Recording 文案缩短为“松开发送”，避免按下后区域膨胀。

七列日历在此字体下右列裁切，改为年/月选择 + 可滚动完整日期列表；已验证月末日期可见/选择、确认/取消/返回/清空、各库存互不影响、确认前不写库。当前截图可见长日期换行，触控目标完整。

现有系统是三按钮导航；已验证底部导航、键盘、Sheet 与按住拖动区域。边缘返回手势冲突未验证，因为用户没有开启该模式；未修改系统导航配置。

[实机截图](../../physical-memory-oppo-validation/screenshots/)。Processing 实际模型截图待回放采集。最初 UI 测试因键盘布局失败后由本次工具主动终止，日志尾“Process crashed”为主动 force-stop 的后果，不计为自发 native crash；最终修复后的测试另存 ui-final.log。

## Background Behavior / Process Restart

进程重启测试已通过 create 与 verify 两阶段（不同 PID）：命名隔离 Room 数据库中 Item 与三份库存所有字段保持一致，两个模型重载并完成 ASR/NLU。verify 运行期间通过正常 am start 将验证 Activity 带回前台；该 109.635 s 测试总时长不作为冷启动性能。真实麦克风上滑取消、打开 Settings、HOME 后返回测试通过（11.774 s）：录音器启动/释放各 3 次，ASR decode / NLU / 保存 WAV / 历史均为 0。锁屏因设备有凭证，留待单独测试与用户正常解锁。见 real-microphone-cancel.json。

## Stability / Thermal Observation

连续 10 轮尚待当前 OPPO 真人录音。不能以 14 条文本 NLU 代替 Speech→ASR→NLU→Draft 的 10 轮。开始状态 USB 充电，电池 35.2°C；thermal HAL 当前 CPU 42.117°C、SKIN 36.239°C，global thermal status=1。原始 cached temperature 与 Current temperatures from HAL 分开，最终温升/时延退化待相邻采样。无手触反馈时不声称“摸起来不热”。

## Known Problems

1. “牛奶少了一袋”被本地 NLU 判 UNKNOWN，尚未满足该项 OPEN_ITEM 行为要求；本轮保持现有模型/提示词，保留错误证据。
2. 已加载两模型不代表第一条 NLU 足够快，首条文本约 11.6 s，后续约 2.0–3.8 s。
3. 两模型驻留 PSS 约 3.68 GiB，采样峰值约 4.07 GiB；只能说明本机本次运行可用，长期驻留和泄漏需结合 10 轮结果。
4. 真人语音准确率、完整时延、10 轮 H、锁屏解锁尚待完成。
