# Physical Memory UI/UX 重构实现报告

本次在既有 physical-memory-v0 原目录继续开发，版本 0.5.0 / code 8，未创建 Android 项目或独立 Demo。原目录没有 Git 元数据，本次使用源文件基线与 SHA 记录变更范围。

## Before → After

原有首页把输入、识别、草稿、详情、最近物品和 Debug 一并放进 LazyColumn；“最近物品”也不能代表已完成操作历史。

现在仅有两个一级入口：

| 页面 | 职责 |
|---|---|
| HomeScreen | 文本输入、提交、按住说话、必要的处理或错误提示 |
| HistoryScreen | 按日期查看完成操作，点击打开物品当前状态 |
| DraftEditorScreen | 独立编辑页；原文重解析、结构字段编辑、确认或取消 |
| ItemDetailSheet | 共用 Bottom Sheet；显示全部库存信息与逐份删除按钮 |
| DeleteConfirmationDialog | 选中实例的二次删除确认 |

沿用 Material 3，不引入波形、复杂动画、额外 Tab、模型性能面板或新的品牌视觉。

## HoldToTalk

独立组件与无 Android 依赖的 controller 实现 Idle / Recording / CancelArmed / Processing，另有准备与打开麦克风状态。按下立即请求录音，上滑超过 96 dp 进入取消，滑回恢复；正常松手进入已有 ASR → NLU。实际录音少于 400 ms、未就绪就松手、多指、pointer cancel、页面销毁、onPause/onStop 均取消。

取消检查在 WAV 保存和 ASR 解码之前；取消不会产生草稿、历史或库存操作。处理时禁止新录音。录音浮层是 Compose 层中的视觉反馈，不创建抢夺手势的新窗口；文字、图标和颜色一起区分取消状态。文本输入始终保留为无障碍替代入口。

## Navigation 与复用

使用 Navigation Compose 2.9.7 的一个 NavHost：home/history/draft。UPSERT 和 ADD 进入独立草稿页；OPEN_ITEM 使用统一 Sheet，不创建查询专用页。草稿返回或取消不保存；提交成功显示物品卡片。

文本查询、语音查询、历史点击复用同一个 ItemDetailSheet。卡片支持展开、内部滚动和关闭，完整显示 location / quantity / 全部实例 / 每份日期。删除确认后原地更新数量和列表，最后一份删除后仍显示物品与位置。

## History

新增简单、独立的本地完成日志，不改变核心库存数据库。只在成功查询、确认草稿或确认删除后记录；取消和未确认候选不写历史。升级前没有真实日志，所以历史从本版操作开始，不伪造旧记录。点击历史实时按 itemId 查询当前 Item，而不是读旧数量快照。

日志和主库存提交不是跨库原子事务：若日志写入失败，已经确认的库存操作保留，历史页提示未保存日志；极端进程终止可能丢一条历史。没有构建事件溯源系统。

## 保持不变的部分

ASR / NLU 模型、NLU Prompt、JSON Schema、GBNF、native NLU runtime、DraftFactory、Validator、Item / InventoryUnit / 主 Room v2、核心 Repository 的 19 个已有文件 SHA 完全一致。只增加语音准备与取消边界的兼容参数，以及 ViewModel 的页面/历史协调接口。

## 验证与安装

135 项 JVM 与 14 项实机测试均通过，clean / test / lint / assembleDebug / build 成功；lint 0 error、11 warning。真麦克风四次取消（含 Activity 切换、桌面和实际锁屏）均已释放，ASR/NLU 均未调用。真实模型的人声回放验证了草稿、滑回恢复和查询卡片，另有原 A–D 的真实 NLU 库存回归。

已覆盖安装并实际打开新版首页。锁屏专项测试后用户已解锁；再次确认 MainActivity 位于前台，当前进程未发现崩溃，新版可继续测试，见 [运行状态](../../physical-memory-ui-validation/app-ready.json) 和 [解锁后首页](../../physical-memory-ui-validation/screenshots/home-after-unlock.png)。正式库 11 条物品、3 份库存、2 个确认回执全部逐项保留，没有改写旧数据。截图包含首页、录音、取消、历史、草稿、详情、删除、键盘和横屏。


最终构建、测试、截图、当前数据核验与实机证据见 [测试报告](ui_ux_refactor_test_report.md)。详细状态机见 [Hold-to-talk 文档](hold_to_talk_interaction.md)，组件与数据边界见 [架构](ui_ux_refactor_architecture.md)。
