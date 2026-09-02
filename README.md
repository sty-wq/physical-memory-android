# Physical Memory · 物品记忆

在 Android 手机上离线记录物品的位置、库存和到期日期。当前版本 **0.5.5**，主开发及验收设备为 **OPPO Find X8s（ARM64）**。

## 功能

- 长按说话、松开发送、上滑取消，也支持文字输入。
- Qwen3-ASR 0.6B INT8 在本地把语音转为文字，Qwen3-1.7B Q8_0 在本地生成结构化草稿。
- 用户确认后才写入 Room 数据库。Item 保存物品和位置，每份 InventoryUnit 独立保存到期日期。
- 首页、全部物品列表、历史记录，以及共用物品信息卡。
- “调整信息”可修改名称、位置、新增库存数量和逐份到期日期；删除选中的库存需要二次确认，支持撤销，最后统一保存。
- 库存归零保留物品。日期通过选择页填写，无需手动输入日期字符串。

## 开发环境

已验证 JDK 21、Android SDK / API 36、Build Tools 36.0.0、NDK 28.2.13676358、CMake 3.31.6。Gradle Wrapper 及 Android 插件版本已固定在仓库内。

1. 使用 Android Studio / SDK Manager 安装上述 SDK、NDK、CMake 和 Platform Tools。
2. 设置 `JAVA_HOME`、`ANDROID_HOME`；复制 `env.example.sh` 为 `env.sh` 并按本机环境调整，然后执行 `source ./env.sh`。本机路径不会提交到 Git。
3. 恢复已固定版本、带 SHA-256 校验的构建依赖：

```sh
python3 scripts/setup-dependencies.py
./gradlew test lint assembleDebug
```

依赖脚本下载 sherpa-onnx 1.13.7 AAR 和固定 commit 的 llama.cpp 源码。已有文件会校验，已有 llama.cpp 修改不会被覆盖。缓存保存在被忽略的 `.gradle/dependency-downloads/`。

## 在 OPPO 上安装及准备模型

开启 USB 调试并授权 Mac，确认 `adb devices` 显示设备。开发脚本会验证目标为 OPPO Find X8s。

```sh
source ./env.sh
./scripts/install-and-launch.sh
python3 scripts/setup-qwen3-model.py
pm_device=$(python3 scripts/select-primary-device.py)
python3 scripts/deploy-qwen3-model.py --serial "$pm_device"
python3 scripts/setup-qwen3-nlu.py --directory models/nlu --serial "$pm_device"
```

ASR 下载约 838 MiB；NLU GGUF 约 1.71 GiB。脚本包含固定来源和校验值；首次下载需要网络，部署后日常语音、理解、查询和存储在手机本地完成。应用首次使用语音时需要麦克风权限。

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。本仓库提供开发安装方式；这些 `run-as` 部署脚本面向 debuggable APK，不能直接视为普通用户 release 安装器。更新使用 `adb install -r`，避免卸载造成物品、历史和模型丢失。

## 验证

0.5.5 已通过 138 项 JVM 测试和 OPPO 上 6 项指定 Room/Compose 测试，包含全量列表、身份保持、增删库存、日期调整、取消/撤销、重名和过期草稿拒绝。

```sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
pm_device=$(python3 scripts/select-primary-device.py)
adb -s "$pm_device" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$pm_device" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$pm_device" shell am instrument -w -r \
  -e class dev.local.physicalmemory.StoredItemsRepositoryTest,dev.local.physicalmemory.StoredItemsUiTest \
  dev.local.physicalmemory.test/androidx.test.runner.AndroidJUnitRunner
```

上述测试使用隔离数据库。不要对已有用户数据的手机运行 `connectedDebugAndroidTest`，其卸载收尾可能清空应用数据。完整真人语音与连续多轮设备验收尚未全部完成，详见设备报告；UI 测试通过不代表该部分已经完成。

## 仓库内容与依赖

提交应用源码、测试源码、Room schema、模型清单、构建/部署脚本和设计文档。Git 不保存模型权重、AAR 下载、llama.cpp 下载、APK、构建缓存、个人录音、设备数据库或本机环境文件。`.gitignore` 不会删除这些本地文件。

旧版 Sherpa streaming ASR 仅保留开发代码；如果需要对应模型，可运行 `scripts/setup-asr-assets.py` 下载历史资产。旧原生测试的官方 WAV fixture 也需从上游模型包另外准备。当前默认链路为 Qwen3-ASR。第三方代码许可和固定来源见 [third-party/NOTICE.md](third-party/NOTICE.md)；模型许可需单独遵守上游条款，本仓库不重新分发权重。

## 文档

- [物品列表与调整流程](docs/items_page.md)
- [NLU 架构](docs/qwen3_1_7b_nlu_architecture.md)
- [数据与确认规则](docs/v2_implementation_plan.md)
- [长按语音状态机](docs/hold_to_talk_interaction.md)
- [OPPO 验收范围与待完成项目](docs/oppo_find_x8s_primary_validation.md)
- [离线安装方式说明](docs/offline_installation_review.md)

`docs/` 中的早期报告保留历史环境和阶段性结果；部分原始证据保存在开发机本地，不随源码上传。
