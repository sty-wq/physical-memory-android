# 按住说话交互

首页只有文本输入与 HoldToTalkButton，不再使用点击开始 / 再点击停止。模型和 Prompt 沿用 V2。

## 状态与事件

| 当前状态 | 事件 | 下一步 |
|---|---|---|
| Preparing | 缓存模型准备完毕 | Idle，按钮可用 |
| Idle | pointer down | 立即请求 startListening；Starting |
| Starting | AudioRecord 真正开始 | Recording，启动计时 |
| Recording / Starting | 相对初始手指位置 dy < -96 dp | CancelArmed |
| CancelArmed | 滑回 dy ≥ -96 dp | Recording（尚未就绪则 Starting） |
| Recording | 松手，实际录音 ≥ 400 ms | stopListening；Processing |
| CancelArmed | 松手 | cancel，丢弃 PCM，回 Idle |
| Starting / Recording | 未就绪或不足 400 ms 就松手 | cancel，轻提示，不识别 |
| 活跃手势 | 多指、系统 pointer cancel、离开页面、onPause/onStop | cancel，不保留这段录音 |
| Processing | 再次按下 | 忽略，按钮不可用 |
| Processing | ASR Final → NLU 完成 | DraftEditor 或 ItemDetailSheet；失败则首页轻提示 |

按压坐标是 pointer 当前位置相对最初 down 的位移；不依赖按钮在屏幕上的绝对坐标。阈值固定为 96 dp，明显上滑才取消；测试使用 130–140 dp 上滑并验证滑回恢复。

## 组件职责

`HoldToTalkController` 不依赖 Android，保存状态、时间与取消策略，可直接用可控时钟测试。`HoldToTalkButton` 使用 pointerInput / awaitEachGesture / awaitFirstDown，消耗已接管的移动，追踪第一个 pointer，额外手指立即取消；finally 处理协程取消与组件销毁。pointerInput 不以不断改变的 Recording/CancelArmed 状态为 key，避免重组时丢失手势。

`RecordingOverlay` 是当前 Compose 层的状态卡片，不是新 Dialog/window，因此不会抢走正在录音的手势。它显示“正在听… / 松开取消录音”、时长和恢复说明；取消状态同时改变文字与颜色，无波形或复杂动画。

按钮具有 contentDescription、Role.Button、stateDescription 与禁用语义；高度至少 96 dp。无法使用长按手势时，始终可以用文本输入完成同一任务。首页不用模型性能数据挤占空间。

## 音频边界

ASR 新增默认兼容的 warmUp 接口，在首页准备缓存解码器，但不打开麦克风。只有触摸 down 才创建录音会话；计时从 Listening 而非模型准备开始。首次需要权限时，只申请权限；授权弹窗会终止原始手势，授权后需要重新按住，不会在手指已经松开后自动录音。

Qwen3AsrSpeechInput 的 decode 逻辑不变；新增两个 UI 接入参数：minimumRecordDurationMs=400、requireExplicitStop=true。旧调用者默认保持原行为。400 ms 同时由手势时间和采样数校验，防止短按进入 ASR。达到原有 30 秒上限但还没松手时，停止采集并提示重录，不能在仍按住时自动送去识别。

取消检查已移到调试 WAV 保存之前。取消、过短、超时未松手的录音均不保存 WAV、不调用 ASR、不调用 NLU、不建草稿、不追加历史。正常松手之后发生的后台取消会抑制迟到结果；已进入同步原生 decode 的 CPU 工作仍按原有引擎规则完成后释放，不宣称可以中途终止 ASR 原生推理。

onPause 即取消活跃录音；onStop 再次清理是幂等的。AudioRecord.stop 中断阻塞读取，worker 在 finally 中 close/release。模型保持缓存，ViewModel 清理后等待原生工作退出再释放。

## 依据

采用 [Android 官方 pointer gesture API](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)，完整测试结果和实机边界见 [测试报告](ui_ux_refactor_test_report.md)。
