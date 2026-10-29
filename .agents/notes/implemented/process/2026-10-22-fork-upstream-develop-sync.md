# Agent Note: 分叉 develop 保留本机能力并同步上游

Status: implemented

## Problem

分叉 `develop` 在固定基线 `fd4ada8bc2c6b736b4a027b00b82c30710948bf1` 保留神经排序、候选画布缓存、充电夜间学习、跨平台验证码入口、搜狗词库订阅及 Android 触点概率纠错。上游祖先最初固定在 `ac76aeca3357ad142a6803f15a8df50d1106bc80`，依次前进到 `c8c3c883c5a768f4409b75984a3cb07622f88c4a`、`a33e619f80b83d01b98d4ce4820910bd119dbe06`（2026-10-25 审计合入 431 个提交），本轮固定到 `4618b12010`，再新增 486 个上游提交。同步期间分叉继续前进：触点概率纠错、键盘尺寸自定义进入 develop，输入线程负载降级（[高负载下输入线程的可选工作降级](../bug-fix/2026-10-28-input-load-shedding.md)）以独立分支叠加在本次合并之下。直接选择任一侧会丢掉另一侧的排序、原生画布或生命周期能力。

## Decision

先独立合入本机功能，再从已更新的 develop 集成固定上游提交，保留双方祖先，后续同步只处理新的上游范围。本轮 39 个冲突文件全部是内容冲突，按下述边界合并：

- **逐键重排**：本机 `rerank_plan` 与上游 `rerank_pick` 共享同一 `choose_rerank` 决策，前者多排次选，是后者的超集，保留本机版本并继续喂 `prediction_context`；吸收上游的光标后编辑门槛（`caret_edit_suffix`）、触点支持元数据列（`candidate_touch_supported` 随全排列同步移动）和换引擎时的 `set_key_geometry` 重发。上游 AI 上下文继续经 QueryOptions 走云端管线，不占引擎的重排上下文槽。
- **触点纠错双轨**：会话里本机 `touch_points` 非空时走本机概率纠错（中心门控 + 替换代价），否则上游的按键触点 + 键位几何计价路径照常启用，两份数据都没有时走平面表。全拼自动纠错在本机中心门控之外吸收上游「两字母错键落在输入末尾按简拼尾读」的跳过条件。宿主 FFI 两套入口并存：`msime_client_character_with_touch`（52 浮点中心）与 `msime_client_set_key_geometry` + `msime_client_touch_character`（注册几何 + geometry_id）。
- **偏好文档**：读取放宽到默认值的契约（上游）取代本机旧的严格读取断言；`LOCAL_SECTIONS` 取并集到 18 项——本机的输入辅助开关（`charging_night_learning`、`verification_code_suggestions`）加上游的云剪贴板 WebDAV、词库同步 WebDAV 与更新通道。
- **Android**：上游按实测网格高度的 `pickerGrid` 重设计取代本机按预测键区收紧行距的做法（上游注释记录了预测在 85%–90% 档位算错的真实缺陷）；键盘设置页同时保留本机「尺寸」分区与上游「按键布局」子页、「组字时保留 Shift」开关；触点纠错的 JNI 入口保留。方案面板、隐私页清理、词库同步入口取并集。
- **Windows**：上游 #10012 的选词重构（所有选词回复由 Server 决定、两会话分叉时用 Server 完整文本兜底，收进 `tsf/HostServerSelection.h`）取代本机对同一函数的结构性重构，也覆盖了本仓库 PR #12 的「最终选词丢失已选词段」缺陷——**该 PR 关闭，不再单独合入**；词库页 `pump` 保留本机的阶段标注并采用上游的 30 秒预算；账号夹具沿用上游共享的 `account_provider_names`。
- **宿主 API**：上游把派发本体拆成返回 `Result<Value>` 的 `dispatch_value` 供进程内打字复用，本机触点参数成为它的第四个入参，`dispatch_call_with_touch` 照旧委托。
- **导出清单**：`verify-native.sh` 取上游全集后补回 `msime_client_character_with_touch` 与 `characterWithTouchRaw`。

**上游开放 PR 差异台账**（按规范只合上游默认分支的固定提交，开放 PR 不合，记录审查结论避免下轮重复分析）：#10135（26 键双拼按下点与键位几何定稿）与本机触点双轨相关，落地时需重审双轨边界；#10084（安卓 9/14/18 键辅助码筛选）与本机键盘自定义无冲突；#10064（共享 Rust 层去掉每次上屏/建会话/空闲的整份读写与 fsync）落地时与本机 [空闲会话文件上限](../bug-fix/2026-10-23-macos-idle-session-file-limit.md) 对照，避免重复实现；#10048（崩溃采集与构建符号）落地时对照本机遥测会话归属笔记。

## Existing decisions

[后台重排](../bug-fix/2026-10-21-async-settled-rerank.md)、[次选次序](../feature/2026-10-21-rerank-scored-runner-ups.md)、[画布缓存](../feature/2026-10-20-linux-canvas-animation.md)、[触点纠错](../feature/2026-10-23-mobile-touch-correction.md)、[偏好容忍未知字段](../architecture/2026-10-15-preferences-keep-unknown-fields.md)、[移动端键盘尺寸](../feature/2026-10-24-mobile-keyboard-dimensions.md) 与[负载降级](../bug-fix/2026-10-28-input-load-shedding.md) 部分重叠，本轮在其边界内合并：预计算投影与画布行缓存原样保留，Linux 画布在缓存行上重设上游的淡化属性；偏好的放宽读取由上游反序列化器承担。没有翻转独立决定。

## Alternatives considered

- **把 4 个开放上游 PR 一并合入**：能提前拿到 fsync 优化等功能，但它们还没进上游主线、随时可能被上游改写或否掉，合入后下次同步会在同一批文件上再冲突一次；维持「只合固定提交」的既有规范，改为记录台账。
- **触点纠错只留上游的几何计价路径**：一套系统更简单，但本机 Android 宿主上报的是 `touch_points` 且按设备验收过，切换意味着改六端宿主的数据通路；双轨让两边各自的数据激活各自路径，行为零回退。
- **逐提交挑拣上游选词重构**：可以避开 `_HandleCandidateFinalize` 的结构冲突，但 #10012 是函数级重写（新签名、新头文件、新测试夹具），挑拣会漏掉配套改动；整文件取上游并放弃本机纯结构性重构，语义无损。

## Consequences

两套触点纠错并存：本机宿主走概率路径，上报几何的宿主走计价 + 学习路径，两者都写同一份学习档案但互不读取对方的偏好；后续若统一，以先落地 #10135 定稿参数的那套为准。上游开放 PR 落地后的下一轮同步要按台账重审四个入口，尤其 #10064 与本机空闲上限的重叠。PR #12 关闭后，QQ 复现场景的最终确认依赖 #10012 的 windows-shuangpin-paged-selection 回归与真机复测。逐键重排多同步一列元数据（每次 O(n)，与既有八列同阶）。

## Verification

- Linux aarch64 Docker（`Dockerfile.desktop-check` 镜像）：`cargo test --locked --workspace --no-fail-fast` 82 个套件通过；仅剩的 8 项失败（引擎 4、client-providers 2、mcp-server 2）全部是符号链接拒绝类，在合并前提交 `7f70172def` 的独立检出上逐字节复现同样失败（`opening_a_journal_rejects_a_symlinked_parent`：0 通过 / 1 失败），判定为容器文件系统的既有环境问题，不是本次合并引入；`cargo clippy --locked --workspace --all-targets -- -D warnings` 与 `cargo fmt --all -- --check` 通过。
- UI（browser-gate 镜像）：`tsc --noEmit` 通过，872 个文件 / 5651 项测试全部通过；生产构建由 CI 的 desktop 工作流覆盖。
- `verify-local.sh` 全量跑完其余阶段：Linux 原生宿主 112/112 CTest 通过；本机可跑的 Windows 原生测试 135 项通过；约 80 项 Android/iOS 契约检查通过。三处 FAIL 均为环境缺口而非回归：两处本机没有 Java Runtime，一处 iOS Swift 测试需要完整 Xcode 的 XCTest（与[移动端键盘尺寸](../feature/2026-10-24-mobile-keyboard-dimensions.md)验证小节记录的是同一工具链问题）；apple-bridge 编译门禁缺本机 cmake，由 PR 的 macOS CI 覆盖。宿主机 `cargo test` 全体 abort 是[负载降级](../bug-fix/2026-10-28-input-load-shedding.md)笔记已记录的 Homebrew dylib 损坏，权威 Rust 结果以 Docker 为准。
- `git diff --check` 仅剩上游自带的表情插件夹具与许可证尾随空白，非本次解决引入。上游 #10012 的新测试（windows-shuangpin-paged-selection）随整文件合入；本机触点 FFI 测试与上游重命名的 eighteen-key 测试共存于同一套件。
- Windows TIP、Android 真机、iOS/macOS 原生界面的设备验收不在本轮声明范围内，以远端 CI 与各平台工作流为准。
