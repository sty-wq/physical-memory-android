# 离线安装说明核对：0.5.1

核对日期：2026-09-02。用户提供的说明描述了完整离线发布包的目标体验；当前工程还没有生成该发布包。下面区分已实现行为与发布前缺项，避免把目标步骤当作现有功能。

## 当前实际状态

| 说明内容 | 0.5.1 实际情况 |
|---|---|
| PhysicalMemory-release/ 目录及压缩包 | 尚未生成 |
| app/PhysicalMemory-release.apk | 当前有可安装的 app-debug.apk；Release 输出为 app-release-unsigned.apk，apksigner 验证未通过，未签名 |
| scripts/install-macos.sh | 尚不存在；当前 scripts/install-and-launch.sh 只安装、启动 Debug APK |
| scripts/verify-models.sh、根 checksums.sha256 | 尚不存在；ASR 有 models/qwen3-manifest.json，NLU 校验固定在 setup-qwen3-nlu.py 中 |
| docs/RELEASE_MANIFEST.md | 尚不存在 |
| Release 模型导入 | 尚无用户可操作的导入入口；当前部署脚本用 run-as 写入应用私有目录 |
| 首次启动显示两个模型 Ready | 当前首页只通过语音按钮显示准备状态；没有 ASR/NLU 双状态页；NLU 在首次解析时加载 |
| 应用名称 Physical Memory | 当前系统桌面名称为“物品记忆”，首页标题为“物品助手” |
| APK 与模型分离 | 已实现；当前 Qwen3 ASR 和 NLU 模型不放在 APK 中 |
| 核心功能本地运行 | 当前主链路为本地 Qwen3-ASR → Qwen3 NLU → 草稿 → 用户确认 → Room；已构建 Debug APK 无 INTERNET 权限 |
| 覆盖升级保留数据 | 0.5.0 → 0.5.1 已验证；主库存及历史全部行不变，11 条物品和 3 份库存保留 |

模型版本：ASR 为 sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25；NLU 为 Qwen3-1.7B-Q8_0.gguf。Android 15 / ARM64 是当前小米实测环境；12 GB RAM 可作为建议，尚未进行最低内存门槛测试。

## 发布前需要补齐

1. 确定并保存发行签名，生成已签名的非 Debug APK，记录包名、versionCode、证书摘要与文件哈希。
2. 实现非 Debug 应用可使用的模型导入流程，核对 Mac 源文件及手机最终文件，失败后可重试。不能直接把现有 run-as 部署脚本换名当作 Release 安装器。
3. 打包固定版本模型、安装器、校验器、清单和相关许可说明；明确离线安装是否全程不需要下载。
4. 让安装说明中的状态提示与真实界面一致，或实现所描述的首次安装模型检查界面。
5. 实测首次安装、已有模型跳过传输、同签名覆盖升级、校验失败恢复及断网功能验证，再定稿发布安装指南。

`run-as` 的 Android 实现会拒绝不可调试的应用，见 [AOSP 源码](https://android.googlesource.com/platform/system/core/+/android16-qpr2-release/run-as/run-as.cpp)。普通覆盖更新还要求包名和签名兼容，以及版本号满足系统更新要求；不能仅凭 `adb install -r` 保证任意 APK 可升级，见 [Android 签名说明](https://developer.android.com/studio/publish/app-signing)。当前安装使用 Android Debug 证书，不能直接假定未来的新发行密钥可覆盖它。

## 当前 Mac 与小米可执行的更新步骤

以下适用于本机现有工程和已部署模型的开发测试版，不是独立发布包安装步骤。它们已在 0.5.1 更新时执行；本次文档核对没有重新安装或修改手机。

```bash
cd /Users/bia/Documents/Codex/2026-09-02/files-pasted-by-the-user-task/outputs/physical-memory-v0
source ./env.sh
adb devices
adb -s ZTSCJJCM4DZD7HRW install -r app/build/outputs/apk/debug/app-debug.apk
adb -s ZTSCJJCM4DZD7HRW shell am start -W -n dev.local.physicalmemory/.MainActivity
```

模型未改变时无需再次传输。普通升级保持覆盖安装；卸载或清除应用数据会影响私有数据库和模型。若签名不兼容，先解决签名或数据迁移方案，不以卸载重装绕过。

第一次使用语音时授权麦克风；权限对话框结束后需要重新按住录音。等待按钮显示“按住说话”，按住讲话、松手提交，上滑取消。记录会进入独立草稿页，点击确认后才保存；每份到期日期可通过日历选择。查询显示当前物品信息卡片。

## 核验来源

- [构建配置](../app/build.gradle.kts)：0.5.1 / code 9，ARM64，未配置 Release 签名。
- [现有 APK 安装脚本](../scripts/install-and-launch.sh)。
- [现有 ASR 部署脚本](../scripts/deploy-qwen3-model.py)、[现有 NLU 获取及部署脚本](../scripts/setup-qwen3-nlu.py)。
- [应用入口](../app/src/main/java/dev/local/physicalmemory/MainActivity.kt)、[NLU 加载实现](../app/src/main/java/dev/local/physicalmemory/nlu/Qwen3NluEngine.kt)。
- [0.5.1 安装、数据保留与 UI 验证](../../physical-memory-ui-validation/v0.5.1/README.md)。
