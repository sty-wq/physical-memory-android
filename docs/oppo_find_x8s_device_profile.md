# OPPO Find X8s 设备档案

2026-09-02，通过 adb 读取实际属性。本阶段唯一主开发与验收设备；不使用历史小米或模拟器数据评价本机性能。

| 项目 | 实测 |
|---|---|
| 市场名称 | OPPO Find X8s |
| Manufacturer / Model | OPPO / PKT110 |
| Device | OP5DCBL1 |
| Android / API | 16 / 36 |
| ColorOS 显示版本 | 16.0.10 |
| ROM 属性 | V16.1.0 |
| 系统构建 | PKT110_16.0.10.500(CN01) |
| SoC | Mediatek MT6991；硬件属性 mt6991 |
| ABI | arm64-v8a |
| CPU cores | 8，present=0-7 |
| 内核 MemTotal | 11656580 KiB，11.12 GiB |
| 采集时 MemAvailable | 3122528 KiB，2.98 GiB；随后台应用变化 |
| 屏幕 | Physical size: 1216x2640 |
| 显示密度 | Physical density: 560; Override density: 620 |
| fontScale | 1.6 |
| /data 可用 | 136714828 KiB，约 130.38 GiB |

保持现有显示、字体、网络、电源及系统导航设置。启动前电量 62%，USB 充电；温度分别记录 battery 与 thermalservice 的原始读数，不混用缓存温度与 HAL 即时温度。

USB serial 仅记入原始设备证据，不写入应用代码；当前命令通过 scripts/select-primary-device.py 动态选择并核对市场名称。

[原始设备属性](../../physical-memory-oppo-validation/device-profile.json) · [电池状态](../../physical-memory-oppo-validation/battery-before.txt) · [Thermal 原始输出](../../physical-memory-oppo-validation/thermal-before.txt)
