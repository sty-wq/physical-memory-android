# Qwen3-ASR · Xiaomi 23078RKD5C 实机测试

日期：2026-09-02。用户已确认把实测目标从 OPPO Find X8s 改为 **Xiaomi 23078RKD5C**，serial `ZTSCJJCM4DZD7HRW`。Android 15 / API 35 / arm64-v8a，MediaTek MT6985，MemTotal 11,615,660 KiB。APK 与指定模型已部署；十轮官方 WAV 解码通过；14 段逐句真人录音和同 WAV 新旧模型对照已完成；首两句原话已确认，其余 12 句等待最终口误/顺序确认。以下待测值不是失败，也不是 0% 正确率。模拟器历史结果另见 [实现与验证报告](physical_memory_qwen3_asr_report.md)。

## 设备与部署状态

| 项目 | Xiaomi 观测 |
|---|---|
| USB 调试 serial / 设备型号确认 | ZTSCJJCM4DZD7HRW / Xiaomi 23078RKD5C；用户已确认 |
| Android / API / ABI / RAM | Android 15 / API 35 / arm64-v8a / MemTotal 11,615,660 KiB |
| APK 安装、启动、麦克风权限 | 0.3.1 覆盖安装、主页面启动成功；RECORD_AUDIO granted |
| 官方 WAV 本机解码 | 20.759 秒绕口令连续 10/10 非空结果；另有 noise2 同音频新旧模型对照 |
| 真实麦克风 → Qwen3 → Final | 14 段逐句录音与 1 段合录均已保存 WAV；14 段逐句回放与 live 输出完全一致 |
| Qwen3 Final → STORE / FIND | 已通过：保存钥匙→玄关柜，再查询成功 |
| 断网时识别 | 未执行；实现的 Qwen3 路径没有网络调用 |
| 10 次连续录音与解码 | 官方 WAV 10/10；14 段逐句真人录音均得到 Final、均复用模型，录音之间麦克风释放，无 JNI crash |
| 后台 / 返回 / 关闭页面 / 重新进入 | 自动生命周期测试在启动 Activity 时停住，未通过；人工验证待完成 |
| modelLoadMs / decodeMs / RTF / stop→Final | 官方 WAV：首次加载 5436 ms，decode 10739–11348 ms，RTF 中位数 0.524；真人 14 段 stop→Final 1106–1992 ms，中位数 1357 ms |
| 加载前 / 加载后 / 解码峰值 PSS、RSS、native heap、Java heap | PSS 105672 / 1173973 / 2355509 KiB；四项完整值见实现报告 |
| 手机安装后代码 / 数据 / 模型占用 | 代码 62559 KiB；私有数据 977814 KiB（其中模型 977675 KiB） |

## 第一轮、第二轮原始结果

每次先记录实际说出的 Ground Truth 和 sessionId；忠实保留模型原文。逐字口径仅忽略首尾句末标点与空白；保留实体内连字符，不纠正词语、数字、字母大小写或实体；同时单独核对 STORE/FIND 的物品和位置。说错或被打断的录音标为无效并重录，不能从模型输出反推 Ground Truth。

| Ground Truth | Qwen3-ASR | Correct | Decode ms |
|---|---|---:|---:|
| 钥匙放在玄关柜 | 钥匙放在玄关柜。 | 是 | 1168 |
| 护照放在卧室书桌第二个抽屉 | 护照放在卧室书桌第二个抽屉。 | 待确认逐句原话 | 1817 |
| 相机电池放在相机包里 | 相机电池放在相机包里。 | 待确认逐句原话 | 1474 |
| 移动硬盘放在黑色背包里面 | 移动硬盘放在黑色背包里面。 | 待确认逐句原话 | 1461 |
| SD卡放在书桌上 | SD卡放在书桌上。 | 待确认逐句原话 | 1220 |
| 钥匙在哪 | 钥匙在哪儿？ | 非逐字一致，多“儿”；查询成功 | 1027 |
| 护照在哪里 | 护照在哪里？ | 待确认逐句原话 | 1029 |
| 移动硬盘放哪了 | 移动硬盘放哪儿了？ | 待确认逐句原话 | 1118 |
| XM5放在床头柜 | X M五在床头柜。 | 待确认逐句原话 | 1622 |
| R8放在防潮箱 | R八放在防潮箱。 | 待确认逐句原话 | 1169 |
| AD200Pro放在器材柜 | AD二百Pro放在器材柜。 | 待确认逐句原话 | 1376 |
| GoPro放在背包里 | GoPro放在背包里。 | 待确认逐句原话 | 1066 |
| MacBook放在书桌上 | MacBook放在书桌上。 | 待确认逐句原话 | 1248 |
| 70-200放在防潮箱 | 七零杠二百，放在防潮箱里。 | 待确认逐句原话 | 1840 |

## 分类统计

| 类别 | 计划样本数 | 已有效实测 | observed accuracy on test set |
|---|---:|---:|---|
| 第一轮中文短句（含 SD卡 一句） | 8 | 2 | 当前两句逐字一致 1/2；完整首轮待测 |
| 查询句 | 3 | 1 | 当前逐字一致 0/1；意图/物品与查询结果正确 |
| 记录句（两轮合计） | 11 | 1 | 当前逐字一致 1/1；其余待测 |
| 第二轮英文 / 数字混合实体 | 6 | 0 | 未测 |

类别存在重叠，不能相加当作独立样本。另记录 SD卡 表现，避免第一轮混合实体被隐藏。小样本只报告逐句观察，不宣称正式 WER。

## 连接后的执行步骤

从项目根目录运行。`ZTSCJJCM4DZD7HRW` 必须替换为 `adb devices -l` 中实际确认属于这台小米手机 的 serial；不可直接使用占位值。

```sh
source ./env.sh
adb devices -l
adb -s ZTSCJJCM4DZD7HRW shell getprop ro.product.manufacturer
adb -s ZTSCJJCM4DZD7HRW shell getprop ro.product.model
adb -s ZTSCJJCM4DZD7HRW install -r app/build/outputs/apk/debug/app-debug.apk
python3 scripts/setup-qwen3-model.py
python3 scripts/deploy-qwen3-model.py --serial ZTSCJJCM4DZD7HRW
adb -s ZTSCJJCM4DZD7HRW install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 官方模型自带 WAV，真实 Kotlin/JNI；不写物品数据库
adb -s ZTSCJJCM4DZD7HRW shell am instrument -w -e qwenNative true -e repetitions 10 -e class dev.local.physicalmemory.Qwen3NativeTest dev.local.physicalmemory.test/androidx.test.runner.AndroidJUnitRunner
adb -s ZTSCJJCM4DZD7HRW exec-out run-as dev.local.physicalmemory cat files/qwen3-native-probe.json > ../physical-memory-qwen3-validation/xiaomi-native.json
adb -s ZTSCJJCM4DZD7HRW exec-out run-as dev.local.physicalmemory cat files/qwen3-native-after-release.json > ../physical-memory-qwen3-validation/xiaomi-after-release.json
adb -s ZTSCJJCM4DZD7HRW shell am start -W -n dev.local.physicalmemory/.MainActivity
```

在手机上授予麦克风权限，默认选择 Qwen3-ASR。展开“ASR 调试信息”，开启“保存本机测试录音（Debug）”。点击说话，等“正在听…”后说一条，点击结束，等“正在识别…”变为最终结果。首次模型加载发生在点击后，加载时尚未开始录音。最长录音 30 秒自动结束。每轮测试记录 sessionId、真实输入、原始输出、是否正确及性能；查询放在对应记录之后。记录句会正常修改手机物品数据。

先测上述 14 句，再以同一普通句连续录音/停止至少 10 次，观察麦克风释放、模型复用、错误与内存。分别在录音中、识别中测试取消和切后台，返回后重新录音；关闭页面再打开也要重试，确认取消结果不提交。首次模型加载耗时与后续复用分开记录。调试音频只在本机，完成后关闭开关；已有 WAV 不会因关闭开关而自动删除。

```sh
python3 scripts/collect-qwen3-debug.py --serial ZTSCJJCM4DZD7HRW --out ../physical-memory-qwen3-validation/xiaomi
# 将 SESSION_1.wav,SESSION_2.wav 换成导出的实际文件名，在设备上重放同一批 PCM
adb -s ZTSCJJCM4DZD7HRW shell am instrument -w -e qwenReplay true -e replayFiles SESSION_1.wav,SESSION_2.wav -e class dev.local.physicalmemory.Qwen3ReplayTest dev.local.physicalmemory.test/androidx.test.runner.AndroidJUnitRunner
adb -s ZTSCJJCM4DZD7HRW exec-out run-as dev.local.physicalmemory cat files/qwen3-replay.json > ../physical-memory-qwen3-validation/xiaomi-replay.json
# 定向测试结束仅删除测试包，不卸载主应用
adb -s ZTSCJJCM4DZD7HRW uninstall dev.local.physicalmemory.test
adb -s ZTSCJJCM4DZD7HRW shell am start -n dev.local.physicalmemory/.MainActivity
```

不能在有个人记录的设备执行 `connectedDebugAndroidTest`：此项目的 AGP 测试收尾会卸载主应用。使用上述明确类名的 instrumentation；不运行会改写正式记录的旧验收 fixture。

## 当前结论

真实麦克风 STORE/FIND 已通过，14 段逐句音频已保存并完成双模型对照，短句停止后 1.106–1.992 秒返回。其余 12 句的原话确认及后台/重开操作确认仍待用户回复；原始输出已全部列出，未用模型输出反推 Ground Truth。英文数字实体仍有明显文本形式不一致，不能据此宣称准确率问题已解决。
