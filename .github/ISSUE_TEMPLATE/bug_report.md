---
name: Bug Report
about: 报告 Winfex 运行时问题
title: "[BUG] "
labels: ["bug"]
assignees: []
---

## 问题描述
<!-- 简要说明遇到了什么问题 -->

## 复现步骤
1.
2.
3.

## 预期行为
<!-- 应该发生什么 -->

## 实际行为
<!-- 实际发生了什么 -->

## 环境
- Winfex 版本：<!-- 从 设置 → 关于 查看 -->
- Android 版本：
- 设备型号：<!-- 例如 小米 14 Pro -->
- SoC：<!-- 骁龙 8 Gen3 / 天玑 9300+ / 其他 -->
- 是否集成 X Server：<!-- 是 / 否（跑了 sync-xserver.sh 没）-->
- 已安装的 .rat 包：<!-- Core / Wine / Box64 / DXVK / VKD3D / VulkanDriver / WineUtils -->

## 日志
<!-- 附上 logcat 输出，重点过滤这些 tag -->
```
adb logcat -s WinfexApp:V winfex-jni:V winfex-xtest:V winfex-exec:V XServerManager:V WineWrapper:V RatPackageManager:V
```

或者从 设置 → 关于 → 日志目录 复制文件。

## 截图 / 录屏
<!-- 如果是 UI 问题，附上截图 -->
