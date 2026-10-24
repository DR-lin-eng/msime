# Agent Note: Android 设备夹具跟随触点参数与系统输入焦点

Status: implemented

## Problem

运行 `40095903949` 已通过修正后的共享设置同步测试和六种 APK 构建，但 API 35 工具栏套件反射的 `bindLetterGestures(Button, String)` 已过时：产品入口增加了 `KeyTouchSample[]`。API 28 全文浮层展开后，即使保留原节点，`AccessibilityNodeInfo.refresh()` 仍可能失败；把不可读取等同于编辑器失焦会误报。

## Decision

工具栏连续点击夹具传入真实入口需要的一元素触点数组。全文浮层在展开前后比较系统 `dumpsys input_method` 的当前输入窗口标识；dump 只在专用合成编辑器内存中解析且限制为 1 MiB，不写日志、不读取正文。原无障碍节点仍可刷新时继续严格检查焦点与位置；仅 API 28 允许节点不可用，卡片、操作栏、滚动和浮层像素检查继续执行。

## Alternatives considered

- 直接移除焦点断言可以绕过节点消失，但会漏掉真正抢焦点导致输入丢失的回归，因此改用系统输入窗口作独立证据。
- 给产品保留旧手势重载能让旧反射继续工作，但增加只服务测试的入口；夹具应跟随真实产品签名。

## Consequences

系统输入窗口改变仍会失败。API 28 节点不可读取时不声称已验证编辑器节点边界；其他 API 继续要求可刷新。反射用例仍检查真实按钮、点击生命周期与触点反馈。

## Related notes audit

[全文剪贴板浮层](../feature/2026-10-20-android-clipboard-card-preview.md)部分重叠；其刷新原节点的旧限制在本篇补充，产品不抢焦点的决定保持。[移动端触点纠错](../feature/2026-10-23-mobile-touch-correction.md)定义当前手势参数，本篇不改变触点计算。
