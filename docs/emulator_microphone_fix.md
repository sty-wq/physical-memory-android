# 模拟器麦克风故障与本机兼容补丁

## 当前使用方式（2026-09-02 复发后更新）

先关闭现有模拟器窗口，在项目目录执行 `./scripts/start-voice-emulator.sh`，启动后选择“本地 Sherpa”。专用脚本自动开启 host audio，并在模拟器进程内加载 `tools/macos/coreaudio_listener_cleanup.c` 编译的兼容库。APK 仍为 0.2.0，应用的每次录音结束后仍 stop/release AudioRecord，并释放 Sherpa 模型。

补丁仅验证于这台 Apple Silicon Mac、Emulator 37.1.11 / build 15917651、API 36 AVD。启动脚本检查版本；不替换 SDK 文件，不重签名，不改变麦克风权限或 macOS 安全设置。关闭模拟器后补丁即不再运行；普通 `scripts/start-emulator.sh` 可启动未经补丁的版本，但本机录音故障可能重现。

## 复发原因与对照

上次只观察到一次真人识别成功，不能说明重复录音稳定。之后五次会话再次 NO_MATCH，每次只有 44,800 个 samples，无 Partial。宿主模拟器同时重复报错：

```text
coreaudio: Could not initialize record
coreaudio: Could not set audio format change listener
coreaudio: Reason: kAudioHardwareIllegalOperationError
Failed to create voice `virtio-snd-mic0'
```

Mac 选中内建 MacBook Pro 麦克风，Android 授权正常。隔离的 `MicrophoneCaptureProbe` 在不运行 ASR、不访问物品库的情况下，连续三次创建、录音、关闭并释放 AudioRecord：

| 启动条件 | 三次采样结果 | 结论 |
| --- | --- | --- |
| 37.1.11 + `-allow-host-audio` 冷启动 | 第一次正常变化；后两次完全相同的峰值 32768、RMS -3.006537 dBFS | 重新开启转发和重启不能解决重复录音；后两次有 CoreAudio 错误 |
| 37.1.11 禁用 VirtioSndCard | 三次均为相同异常统计 | 不可作为本机 API 36 的替代方案 |
| 官方 36.1.9 独立安装 | 第一次变化；后两次同样异常及 CoreAudio 错误 | 此次旧版对照没有解决 |
| 37.1.11 + 回调清理补丁（最终脚本） | RMS 分别 -28.024、-26.301、-25.576 dBFS | 三次音频持续变化，三次 remove=0，未出现原 CoreAudio 错误 |

这些是信号和资源清理验证，不等于真人语音准确率。探针的 JUnit 成功只代表 AudioRecord 返回样本；非零样本也不保证有效人声，必须结合统计及宿主日志判断。探针仅接受 `microphoneProbe=true` 显式启用，不保存原始音频。

[AOSP coreaudio.c](https://android.googlesource.com/platform/external/qemu/+/refs/heads/emu-master-dev/audio/coreaudio.c) 的 `coreaudio_init_base` 注册采样率属性回调，而所检查的 `coreaudio_fini_base` 销毁 IOProc 时未配对移除这个回调。本机实验证明：将该回调与紧接着创建的 IOProc 绑定，在销毁 IOProc 前移除它，后续录音可再次正常初始化。兼容库仅处理来自 qemu-system-aarch64 的采样率回调；这是进程级兼容修复，不是官方发布的 Emulator 修复版。

证据位于 [recurrent-microphone](../../physical-memory-v1-validation/recurrent-microphone/)。其中 `emulator-fixed.log` 为最终脚本启动日志，`probe-fixed.json` 为上述三次信号统计。完整 `clean test lint assembleDebug build assembleDebugAndroidTest` 通过，新增 C 代码以 `-Wall -Wextra -Werror` 编译通过。

### 自动测试的有效性与数据保护

早期 UI 外放测试使用的 `say` 输出在沙箱内为零音频包，未实际播放预期短句；期间误收环境语音，触发两条测试记录（ID 6、7）。已按其 ID、名称、位置和时间戳精确限定删除；原 ID 1、3、5 的所有字段与测试前一致，证据为 `records-before.json` 与 `records-after-cleanup.json`。原物品没有被覆盖。

后续识别验证改用不接触业务数据库的独立探针，每轮创建和释放真实 AudioRecord 与 Sherpa，再用 Mac 扬声器播放标准语音。`microphone-decode.json`（短句）和 `microphone-official-mistimed.json`（首次长样本）受到测试端文本日志缓冲的影响，外放晚了一轮，不能用来评估识别准确率。测试工具已改为直接读取日志字节并逐行处理；有效的最终标准样本结果保存在 `microphone-official-decode.json`。

探针调用必须显式指定 `-e microphoneProbe true`；另加 `-e microphoneDecode true` 时每轮采集 8 秒并转写。普通设备测试不自动运行它。只保存统计和文字，不保存麦克风原始音频。这种外放测试仍不能代替用户亲自讲话或 Find X8s 的实机准确率验证。

最终正确同步的三轮均完成转写，采样数均为 128,000，均已释放本轮录音和模型，未出现原 CoreAudio 错误：

1. 对我做了介绍那么我想说的是呢大家如果对我研究感兴趣呢
2. 对我做了介绍那么我想说的是呢大家如果对我的研究感兴趣呢
3. 对我做了介绍那么我想说的是呢大家如果对研究感兴趣呢

与原 WAV 的直接解码结果相比，第 1、3 轮存在漏字，所以这里只确认连续三轮声学输入和识别链路可用，不声称逐字准确。设备测试包已在测试后移除，主应用保留并选中“本地 Sherpa”。最终数据快照为 `records-final.json`。

## 上一次单次真人成功记录（历史证据）

2026-09-02，应用 0.2.0，无需改动或重新安装 APK。

用户页面为 Sherpa / NO_MATCH；Android RECORD_AUDIO 已授予，模型每次成功加载。此前四次会话均读到 44,800 samples（2.8 秒），没有 Partial 或 Final。这些计数只说明 AudioRecord 返回了数据，不能证明收到了有效人声。

执行 `adb -s emulator-5554 emu avd hostmicon` 后返回 OK。macOS 麦克风设置中，启动模拟器的 ChatGPT 应用权限已开启；用户确认允许。模拟器的宿主音频转发与 Android 应用权限是两个独立环节。[Android 官方说明](https://developer.android.com/studio/run/emulator-extended-controls?hl=en) 明确指出，模拟器默认关闭麦克风输入，需要启用 host audio input。

开启后实际观察到会话 ASR-9e68f531-9631-4baf-94c8-a6faacb81b35：

- 识别文本：书包在凳子上。
- 首个非空 Partial：请求后 2643 ms。
- Final：请求后 5243 ms（包含讲话时长）。
- 模型加载：482 ms。
- 无识别错误。
- 业务命令：STORE，item=书包，location=凳子上，outcome=已记住。
- 再读数据库确认“书包 → 凳子上”已保存。

这是 Mac 宿主麦克风经模拟器输入的实际观测，未提供人工原话标注，不能据此计算准确率，也不能代替 Find X8s 实机比较。原 V1 报告是此前的验证快照；本次新增麦克风链路证据见 [host-microphone-fix](../../physical-memory-v1-validation/host-microphone-fix/)。

模拟器真人测试前运行 hostmicon，结束可运行 hostmicoff。没有修改模型、识别阈值、权限数据库或已有物品记录；新增“书包”来自用户这次语音触发的正常保存。
