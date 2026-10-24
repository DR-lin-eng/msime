# Agent Note: 用 HID 实体事件补足 Caps Lock 按住状态

Status: implemented

## Problem

#9094 的外接蓝牙键盘在 macOS 26.4.1 上报告 `NX_ALPHASHIFT_STATELESS_MASK` 始终为零，`0x39` 与 `0xff` 事件交替，系统锁定位约半秒后仍会被改写。现有控制器只能看到逻辑锁定状态，因而无法可靠区分轻点和长按；仅靠 `CGEventSourceFlagsState` 会把短按误判成长按，保留大写锁定。

## Decision

在 macOS 输入法进程中建立只读 `IOHIDManager` 监听，只匹配键盘页的 `Caps Lock`（`0x39`）和 locking Caps Lock（`0x82`）输入元素。输入值回调按设备记录当前按住状态，设备移除时清除记录；读取实体状态时优先使用 HID 结果，管理器无法打开或尚未得到结果时回退到 `NX_ALPHASHIFT_STATELESS_MASK`。监听只接入主运行循环，不抢占设备、不写入 HID 状态，也不把系统键码或逻辑锁定位当作实体松开证据。

为保持单测不触碰真实键盘，抽出带 `known` 标记的 HID 状态函数指针；测试替换该边界并继续验证生产的 Caps Lock 轻点判定。管理器初始化失败、权限不足、设备没有匹配元素和回调丢失都安全回退为保留系统大写锁定。

## Alternatives considered

- **继续只读 `NX_ALPHASHIFT_STATELESS_MASK`**：实现简单且没有新的监听边界，但报告设备已经证明该位不能作为外接键盘的实体状态来源，短按仍会被误判。
- **继续使用键码 `0x39` 或逻辑锁定位**：能在部分键盘上得到值，但两者都随锁定状态保持，无法证明实体松开，会重新引入已记录的缺陷。
- **按事件立即切换并撤回锁定**：不需要读取松开状态；但长按 Caps Lock 会失去保留大写锁定的行为，且撤回失败时会留下错误的英文模式，不符合既有契约。

## Verification

- `InputController.mm` 与 `ShortcutTest.mm` 使用 Xcode 27 macOS SDK 通过 `clang++ -fsyntax-only`；`git diff --check` 通过。
- 单测边界新增 HID 状态已知且分别为按下、松开，以及未知时回退 stateless 位；既有 `shortcut-test --caps-lock` 用例继续替换原生查询边界，不初始化真实 HID 管理器。设备表的匹配、输入值更新和拔出清理由同一实现路径维护，未在没有 HID 设备的本机单独伪造回调。
- 本机没有可用于复现 #9094 的外接蓝牙键盘现场，尚未在报告人的 macOS 26.4.1 环境验证 `IOHIDManager` 回调；不能据此宣称所有外接设备均已修复。

## Consequences

HID 可用时，短按判定读取实体按下和松开，不再依赖外接键盘可能缺失的 Quartz stateless 位；多个键盘并存和设备拔出不会把另一台设备的状态误留在表中。HID 不可用时仍保留原先的安全回退，代价是该设备只能保留系统大写锁定，不能冒险切换。

`IOHIDManagerOpen` 在不同 macOS 版本或权限状态下可能不可用，真实外接键盘验收仍需现场设备；若 HID 回调延迟，短按只能安全地保留大写锁定，不能保证每次都切换。
