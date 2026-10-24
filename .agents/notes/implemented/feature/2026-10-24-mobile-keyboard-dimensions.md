# Agent Note: 移动端键区宽度与更大范围的间距自定义

Status: implemented

## Problem

移动端已有高度调整，但按键横向尺寸只能跟随屏幕或单手模式，不能独立微调。键距限于 3–6 dp，行距限于 4–10 dp，也不能选择无间隙布局。

## Decision

共享偏好新增 `touch_keyboard_width_percent`，整数范围 75–100，默认 100；Rust 负责默认值、文档校验和持久化。Android、iOS、HarmonyOS 的设置页提供「按键区域宽度」，将按键区居中收窄，候选与工具栏保留全宽。单手、浮动、分离模式沿用自己的宽度，避免多重缩放导致按键过窄；普通模式自动恢复自定义宽度。

键距允许 0–12 dp，行距允许 0–16 dp，默认仍为 6 和 7 dp，三端实际键盘与设置预览同步采用这些范围。高度沿用现有的平台规则。恢复默认删除宽度覆盖，重新读取缺失字段时回到 100%。

## Alternatives considered

- **只增加单手模式的宽度档位**：复用现有靠边容器，改动较少，但普通双手输入无法独立调整宽度，也不能获得居中布局。
- **任意缩放整块键盘**：一个控件就能一起改变尺寸，但会缩小候选栏、工具栏和文字，并叠加单手模式，操作目标难以控制，因此只调按键区宽度。

## Consequences

旧文档仍按默认尺寸绘制；共享层保存新边界并拒绝越界值。宽度恢复默认清除 App Group 镜像。新增独立宽度控件与两侧留白会占用设置页空间，窄屏和大间距组合会减小键帽。共享 React 的间距消费者采用同一扩大后的有效范围。

## Verification

Android 注音大千保留原有 4 dp 键距上限；设置页静态预览展示标准 26 键或九键。iOS 收窄产生的两侧留白不参与按键触摸扩展，首次布局与旋转重新计算边距；零行距同时去掉手机字母行的额外留白。Android 最低键高和零行距组合把键区缩到 162 dp，方案菜单的两行间隔按剩余空间收紧，避免原先固定 16 dp 时溢出。

- `cargo test -p msime-client-core --locked --lib`：516 项通过；`cargo clippy -p msime-client-core --locked --all-targets -- -D warnings` 通过。
- 共享设置控件、拖动与真实桌面键盘的 64 项相关测试及设置页 10 项集成测试通过；保存、取消恢复、确认恢复和预览几何都覆盖。类型检查与构建通过。
- Android 原生宿主 `:app:compileFullDebugJavaWithJavac` 通过；鸿蒙 `platforms/harmony/tests/run.sh` 通过。
- 使用外盘 Xcode 27.1（`DEVELOPER_DIR` 指向其 `Contents/Developer`）在专用 iOS 26.5 模拟器运行 `KeyboardGeometrySettingsTests`、`OneHandModeTests`、`KeyGapRoutingTests`，30 项通过；原生 `MSIMEApp` 与键盘扩展编译通过。系统默认 Command Line Tools 不含 XCTest，直接用它会让不相关的豆包传输门禁失败；切换本任务环境变量后该套件通过，不修改全局 Xcode 选择。
- Android 只验证了原生 Java 编译和 JVM/设备测试源文件编译，没有装进 Android 模拟器。鸿蒙缺 `ohpm` / `hvigorw`，没有完成 HAP 编译。三端均未进行真机验收。
- 同输入的几何基线、补丁重建与回滚校验保存在任务旁的验证产物中，恢复后的源码哈希必须等于原始源码。

历史笔记审计：[共享几何读取](../simplification/2026-10-14-touch-keyboard-geometry-preferences.md)部分重叠，原地更新范围并互链；[Android 高度范围](2026-10-08-android-keyboard-height-range.md)保留平台高度决定，与本次宽度新增无冲突。
