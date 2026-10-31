# Agent Note: 分叉 develop 保留本机能力并同步上游

Status: implemented

## Problem

分叉 `develop` 在固定基线 `fd4ada8bc2c6b736b4a027b00b82c30710948bf1` 保留神经排序、候选画布缓存、充电夜间学习、跨平台验证码入口、搜狗词库订阅及 Android 触点概率纠错。上游祖先最初固定在 `ac76aeca3357ad142a6803f15a8df50d1106bc80`，依次前进到 `c8c3c883c5a768f4409b75984a3cb07622f88c4a`、`a33e619f80b83d01b98d4ce4820910bd119dbe06`（2026-10-25 审计合入 431 个提交）、`4618b12010` 与 `2e851f3c0f`（2026-10-29 上半天合入 164 个提交、15 项主线 PR），本轮再前进到当前上游 `de5d51623a`，新增 193 个上游提交；总同步范围相对上一版再前进到该固定提交。同步期间分叉侧自 PR #19 合并后没有新的功能分支，本轮只做上游追赶。直接选择任一侧会丢掉另一侧的排序、原生画布或生命周期能力。

## Decision

先独立合入本机功能，再从已更新的 develop 集成固定上游提交，保留双方祖先，后续同步只处理新的上游范围。上一轮的 39 个冲突文件已按既有边界固定；本轮追到 `2e851f3c0f` 又出现 7 个内容冲突，按下述边界继续合并：

- **逐键重排**：本机 `rerank_plan` 与上游 `rerank_pick` 共享同一 `choose_rerank` 决策，前者多排次选，是后者的超集，保留本机版本并继续喂 `prediction_context`；吸收上游的光标后编辑门槛（`caret_edit_suffix`）、触点支持元数据列（`candidate_touch_supported` 随全排列同步移动）和换引擎时的 `set_key_geometry` 重发。上游 AI 上下文继续经 QueryOptions 走云端管线，不占引擎的重排上下文槽。
- **触点纠错双轨**：会话里本机 `touch_points` 非空时走本机概率纠错（中心门控 + 替换代价），否则上游的按键触点 + 键位几何计价路径照常启用，两份数据都没有时走平面表。全拼自动纠错在本机中心门控之外吸收上游「两字母错键落在输入末尾按简拼尾读」的跳过条件。宿主 FFI 两套入口并存：`msime_client_character_with_touch`（52 浮点中心）与 `msime_client_set_key_geometry` + `msime_client_touch_character`（注册几何 + geometry_id）。
- **偏好文档**：读取放宽到默认值的契约（上游）取代本机旧的严格读取断言；`LOCAL_SECTIONS` 取并集到 18 项——本机的输入辅助开关（`charging_night_learning`、`verification_code_suggestions`）加上游的云剪贴板 WebDAV、词库同步 WebDAV 与更新通道。
- **Android**：上游按实测网格高度的 `pickerGrid` 重设计取代本机按预测键区收紧行距的做法（上游注释记录了预测在 85%–90% 档位算错的真实缺陷）；键盘设置页在「布局」分页中保留本机「尺寸」分组与上游「按键布局」子页、「组字时保留 Shift」开关；触点纠错的 JNI 入口保留。方案面板、隐私页清理、词库同步入口取并集。
- **Windows**：上游 #10012 的选词重构（所有选词回复由 Server 决定、两会话分叉时用 Server 完整文本兜底，收进 `tsf/HostServerSelection.h`）取代本机对同一函数的结构性重构，也覆盖了本仓库 PR #12 的「最终选词丢失已选词段」缺陷——**该 PR 关闭，不再单独合入**；词库页 `pump` 保留本机的阶段标注并采用上游的 30 秒预算；账号夹具沿用上游共享的 `account_provider_names`。
- **宿主 API**：上游把派发本体拆成返回 `Result<Value>` 的 `dispatch_value` 供进程内打字复用，本机触点参数成为它的第四个入参，`dispatch_call_with_touch` 照旧委托。
- **导出清单**：`verify-native.sh` 取上游全集后补回 `msime_client_character_with_touch` 与 `characterWithTouchRaw`。

本轮（上游 `de5d51623a`，193 个提交）在既有边界上继续，23 个文件冲突全部按上述规则融合，没有新开边界：

- **引擎**：`lib.rs` 同时保留本机 `adaptive` 模块与上游的 `access` 模块和 `deny(clippy::print_stderr)` 属性；`ordering.rs` 文档同时写明本机 `rerank_plan` 超集与上游新增的「产品胶水不入文档化 API」段落；`quanpin/dictionary.rs` 引入上游粗俗词与全辅音简拼整词的 `has_corrected_cut`、`lead_literal_abbreviation_phrases`，本机 `resolve_series_query` 仍是 `#[cfg(test)]` 专用（合并后正文里非触点版只出现在测试辅助函数里），`collect_typo_edges` 导入不带回（正文里只剩全限定名的 `_with_touch` 变体）；`clear_online_candidates` 同时做上游的 `candidates_may_change()` 与本机云预测的 `clear_cloud()`。
- **「增强」只挂 runtime（上游 #10197）**：host-api 不再把 `neural_keyboard` 传给引擎（引擎内键盘模型重排从「增强」路径摘除，省九键按键 p95 6–7 ms 与约 40 MB 内存），runtime 逐键重排器照偏好挂载。与本机能力正交：本机 `adaptive` 预计算投影是模型执行的效率优化，不改变模型的开关；负载降级、商业云预测、后台次选重排都挂在 runtime 层，不受影响。按原样吸收，无需重构。
- **统计批写（上游 #10280）**：选词位置改为进程内批次（`TypingStatisticsStore::queue_selections`，独立短锁），输入线程不再取文件锁、整份读写并 fsync；与负载降级互补（一个挪走可选模型工作，一个挪走统计 I/O），无交互冲突。
- **键盘预览（上游 #10245）**：上游让 Android/iOS/共享设置页的键盘预览跟随 `KeyLayoutStylesPolicy`（跨行回车、九键符号栏栈、数字行按真实布局画），结构重做整文件取上游；本机预览的 `setGeometry`（宽度百分比、键距/行距、高度调整）按新结构重新叠加：Android 预览把几何量放进上游的画法（`keyWidth`/`keyLeft`、`rowGap` 分离），iOS `KeyboardSkinPreview` 同时携带本机 `widthPercent` 与上游 `keyLayout`/`nineKeyZero`。
- **单手侧栏数字键盘（上游 #10234/#10203）**：Android `ImeFrame` 的本机宽度百分比并入上游的 `applied` 缓存串（`mode:blockDp:pad:widthPercent`），`setWeightSum` 保留——核对过与上游三个分支都兼容（大屏 pad 分支的键列与侧栏是固定像素、spacer 是唯一带权子视图）；iOS 键盘控制器保留本机 `applyCustomKeyboardWidth()`（挂进上游的 `applyOneHanded` 排布），键宽计算保留本机比例并传上游新增的 `formFactor`。偏好与测试各自取并集（本机宽度键 + 上游 `oneHandedDockedTablet` 升级标记）。
- **九键符号栏与设置搜索（上游 #10247）**：上游把九键/数字键盘左侧符号的编辑搬进「按键布局」子页并重组关键词（旧名留作别名）；Android `PageId` 关键词表取并集——本机的「数字行」「尺寸」「按键区域宽度」「恢复默认宽度与间距」与上游的新关键词共存，主列表里上游已迁走的「九键左侧符号」不再保留。
- **词库与许可证**：上游把跨进程词库维护锁搬进引擎（`access.rs` 大幅收缩）、同步失败后文件检查退避（#10265）、清空学习数据后丢弃过期读（#10255）、更新请求未决时等待再轮询（#10250）、时钟回拨后有界调度（#10281）随自动合并进入，搜狗订阅模块（`dictionary/sogou.rs`）不在上游改动范围。引擎以 MPL-2.0 再许可（#10193），macOS/Windows 的声明渠道清单在取上游全集的同时保留本机 `adaptive-inference-NOTICES.txt`；engine-capi（#10240 系列）是新增 crate，不触碰本机 host-api 导出。
- **CI**：`ci-platforms.yml` 保留本机的 runner 回退表达式（fork 无 Depot runner），吸收上游更新的注释；上游 Android KVM 初始化修复（#10289）只涉上游自设环境。上游新增的 `release-engine-capi.yml`（#10240 系列）四处裸 `runs-on` 与矩阵三处 Depot 标签按同一规范补上 `${{ github.repository_owner == 'metasequoiaime' && 'depot-…' || '同架构 GitHub 托管' }}` 回退——不加的后果在本轮 CI 上实际发生了：`version` 与三个 Engine C ABI 作业在 fork 上永远排队（Depot 标签无对应 runner）。矩阵项改块式写法，因为流式映射 `{...}` 里内嵌 `${{ }}` 不是合法 YAML。

本轮新增边界：商业云预测沿用显式权限、最多三条后缀和生成代次校验；普通云候选仍是单条，在线结果不绕过隐私开关。共享 Rust 热路径采用上游的内存快照、WAL、文件身份与 `data_version` 失效判断，候选条沿用 `candidate_range`，没有再叠加第二套缓存或全量复制。Android 触点接线保留旧 `characterWithTouch` 兼容入口，同时使用上游 `setKeyGeometry`/`touchCharacter`；键盘空隙路由、触点清理和滑出点击恢复合并为同一生命周期。

**上游开放 PR 差异台账**（只合上游默认分支固定提交，开放 PR 不合；已落地项关闭台账，避免下轮重复分析）：#10135（26 键双拼按下点与键位几何定稿）已随上游 `8e73e3fd0e` 合入，并与本机中心坐标入口保留双轨；#10064（共享 Rust 层去掉每次上屏/建会话/空闲的整份读写与 fsync）已随上游 `50bab97f3a` 合入，已对照本机闲置会话上限并保留各自边界；#10084（安卓 9/14/18 键辅助码筛选）仍为 draft/open experiment，本轮不合；#10048（崩溃采集与构建符号）仍 open，本轮不合。

## Existing decisions

[后台重排](../bug-fix/2026-10-21-async-settled-rerank.md)、[次选次序](../feature/2026-10-21-rerank-scored-runner-ups.md)、[画布缓存](../feature/2026-10-20-linux-canvas-animation.md)、[触点纠错](../feature/2026-10-23-mobile-touch-correction.md)、[偏好容忍未知字段](../architecture/2026-10-15-preferences-keep-unknown-fields.md)、[移动端键盘尺寸](../feature/2026-10-24-mobile-keyboard-dimensions.md) 与[负载降级](../bug-fix/2026-10-28-input-load-shedding.md) 部分重叠，本轮在其边界内合并：预计算投影与画布行缓存原样保留，Linux 画布在缓存行上重设上游的淡化属性；偏好的放宽读取由上游反序列化器承担。没有翻转独立决定。

## Alternatives considered

- **把 4 个开放上游 PR 一并合入**：能提前拿到 fsync 优化等功能，但它们还没进上游主线、随时可能被上游改写或否掉，合入后下次同步会在同一批文件上再冲突一次；维持「只合固定提交」的既有规范，改为记录台账。
- **触点纠错只留上游的几何计价路径**：一套系统更简单，但本机 Android 宿主上报的是 `touch_points` 且按设备验收过，切换意味着改六端宿主的数据通路；双轨让两边各自的数据激活各自路径，行为零回退。
- **逐提交挑拣上游选词重构**：可以避开 `_HandleCandidateFinalize` 的结构冲突，但 #10012 是函数级重写（新签名、新头文件、新测试夹具），挑拣会漏掉配套改动；整文件取上游并放弃本机纯结构性重构，语义无损。

## Consequences

两套触点纠错并存：本机宿主走概率路径，上报几何的宿主走计价 + 学习路径，两者都写同一份学习档案但互不读取对方的偏好；后续若统一，以先落地 #10135 定稿参数的那套为准。**上游 PR 差异台账（2026-10-29 重审）**：#10135（26 键双拼按下点定稿）与 #10064（共享 Rust 层去掉整份读写与 fsync）已随上游主线合入，差异关闭，不再重复实现；#10084（安卓 9/14/18 键辅助码筛选）与 #10048（崩溃采集与构建符号）经查仍为上游 open draft，本轮不合，下轮继续按台账重审。本轮范围内上游落地的性能改动（#10197 摘除引擎层键盘模型、#10280 统计批写、#10250/#10265 词库同步有界等待与退避）经审查均为纯收益或与本机能力正交，不需要在本侧重构或重设计；键盘预览一类上游结构重做按「整文件取上游、本机语义叠加」处理，不留双实现。PR #12 关闭后，QQ 复现场景的最终确认依赖 #10012 的 windows-shuangpin-paged-selection 回归与真机复测。逐键重排多同步一列元数据（每次 O(n)，与既有八列同阶）。

## Verification

- Linux aarch64 Docker（`Dockerfile.desktop-check` 镜像）：`cargo test --locked --workspace --no-fail-fast` 82 个套件通过；仅剩的 8 项失败（引擎 4、client-providers 2、mcp-server 2）全部是符号链接拒绝类，在合并前提交 `7f70172def` 的独立检出上逐字节复现同样失败（`opening_a_journal_rejects_a_symlinked_parent`：0 通过 / 1 失败），判定为容器文件系统的既有环境问题，不是本次合并引入；`cargo clippy --locked --workspace --all-targets -- -D warnings` 与 `cargo fmt --all -- --check` 通过。
- UI（browser-gate 镜像）：`tsc --noEmit` 通过，872 个文件 / 5651 项测试全部通过；生产构建由 CI 的 desktop 工作流覆盖。
- `verify-local.sh` 全量跑完其余阶段：Linux 原生宿主 112/112 CTest 通过；本机可跑的 Windows 原生测试 135 项通过；约 80 项 Android/iOS 契约检查通过。三处 FAIL 均为环境缺口而非回归：两处本机没有 Java Runtime，一处 iOS Swift 测试需要完整 Xcode 的 XCTest（与[移动端键盘尺寸](../feature/2026-10-24-mobile-keyboard-dimensions.md)验证小节记录的是同一工具链问题）；apple-bridge 编译门禁缺本机 cmake，由 PR 的 macOS CI 覆盖。宿主机 `cargo test` 全体 abort 是[负载降级](../bug-fix/2026-10-28-input-load-shedding.md)笔记已记录的 Homebrew dylib 损坏，权威 Rust 结果以 Docker 为准。
- `git diff --check` 仅剩上游自带的表情插件夹具与许可证尾随空白，非本次解决引入。上游 #10012 的新测试（windows-shuangpin-paged-selection）随整文件合入；本机触点 FFI 测试与上游重命名的 eighteen-key 测试共存于同一套件。
- Windows TIP、Android 真机、iOS/macOS 原生界面的设备验收不在本轮声明范围内，以远端 CI 与各平台工作流为准。


本轮最新验证（固定上游 `2e851f3c0fc5710d07addbeea197fca01f949796` 与 fork `origin/develop` 合并结果）：

- 固定 Docker 桌面镜像中 `cargo fmt --all -- --check`、`cargo check --locked --workspace --all-targets --exclude msime-desktop --message-format short` 通过；共享 workspace 编译覆盖 `msime-engine`、`msime-input-runtime`、`msime-host-api`、client crates、WASM 与原生 host crates。
- Docker `cargo test -p msime-input-runtime --no-fail-fast`：418 项通过、1 项按设计忽略，doc-tests 通过；商业云候选 `client-core cloud::candidates` 15 项通过；Host API `cloud_prediction` 3 项通过。
- Docker `cargo test -p msime-engine --lib --no-fail-fast`：2837 项通过、57 项忽略、4 项失败；4 项均是 root 绕过普通权限的 symlink/只读夹具（包括 Wubi 副本只读索引夹具）；用 `runuser -u nobody` 重跑完整 `user_dictionary::` 200 项全部通过，证明无需改产品权限边界。并发链接曾被 Docker OOM 杀掉，随后用 `-j2` 单独重跑 Host API 并通过。
- `scripts/test-input-load-docker.sh` 通过：基础降级 2 项、真实 cgroup 系统压力探针 1 项通过。`scripts/test-commercial-prediction-docker.sh` 通过：云候选 15 项、预测 21 项、FFI 3 项；合成事务输出本地后续词与云后缀，实际只提交选中的后缀。
- 未在本机声明 Android 真机、Apple 原生宿主或 HarmonyOS HAP 验收；本轮把 Android host 契约、JNI 导出和设备工作流交给远端 CI，构建产物 workflow 不带发布开关。


最终整合验证使用独立记录保存命令、输入、完整 stdout/stderr、退出状态和哈希：

- 固定 Docker 的 workspace 全目标严格 Clippy 通过，覆盖 `pulldown-cmark 0.13.5`、`zip 9.0.5` 两项补丁升级。workspace 测试只有两个 Host API 相对目录夹具因 `/source` 只读且普通用户无可写祖先而失败；在 `/tmp` 中以同一普通用户重跑两项通过，未改变权限判定或测试断言。补丁升级后的 providers 与 services 库测试再次通过，services 为 1457 项通过、2 项按设计忽略。
- 共享设置页生产构建通过；872 个文件、5654 项 UI 测试全部通过。
- Android Docker 的 JNI C++ 编译、桌面 JVM 回调测试、Java/API 和清单资源契约通过，305 项 JVM 冒烟及 255 份设备套件源码编译通过。设备夹具复用本项目既有 PR #14 的触点数组签名和系统输入焦点修正，见[设备夹具契约](../testing/2026-10-24-android-device-fixture-contracts.md)，不重复设计。
- Linux Docker 原生 IBus/Fcitx5 构建和 CTest 通过：111 项实际执行、1 项按环境跳过，测试套件总数 112；这不替代真实桌面输入入口验收。
- 源码归档补丁重建后逐文件与修改版一致。回滚脚本在独立副本恢复原始归档字节及 SHA-256，基线、修改版、回滚版相同 `load_shedding` 输入均为 2 项通过、退出 0。旧源码采用独立源码挂载路径，防止 Git 归档旧时间戳错误复用修改版 Cargo 指纹；构建缓存仍为同一个规范目录。

远端 CI 与最终构建工作流绑定实际提交 SHA；归档和本地行为结果不能代替后续真机测试。构建只上传测试产物，`publish`、TestFlight、AppGallery 与 npm 发布保持关闭。


本轮（固定上游 `de5d51623a` 与 fork `origin/develop` 合并结果）验证，全部在 Docker 内完成，宿主机 cargo 仍按[负载降级](../bug-fix/2026-10-28-input-load-shedding.md)笔记记录的 Homebrew dylib 损坏不可用：

- `Dockerfile.desktop-check` 镜像：`cargo fmt --all -- --check` 通过；`cargo check --locked --workspace --all-targets --exclude msime-desktop` 退出码 0；`cargo clippy --locked --workspace --all-targets --exclude msime-desktop -- -D warnings` 无告警。
- Docker `cargo test -p msime-input-runtime`：421 项通过、1 项按设计忽略，doc-tests 通过。Docker `cargo test -p msime-engine --lib`：2908 项通过、57 项忽略、3 项失败，均为 root 容器绕过普通权限的 symlink/只读夹具（含上一轮逐字节复现过的 `opening_a_journal_rejects_a_symlinked_parent`）；按同一 `runuser -u nobody` 行程重跑 `user_dictionary::` 全量 207 项通过，判定与上一轮相同的容器环境问题，不是本次合并引入。
- `scripts/test-input-load-docker.sh` 通过（退出 0）：基础降级用例与真实 cgroup 系统压力探针通过。`scripts/test-commercial-prediction-docker.sh` 通过（退出 0）：云候选、预测、Host API FFI 测试通过，合成事务探针输出本地后续词「饭」与云后缀「米饭」，只提交选中的后缀。
- UI（browser-gate 镜像）：`tsc --noEmit` 通过，`vitest run` 876 个测试文件全部通过（退出 0）。
- Android（msime-android-docker 镜像 + 既有 gradle 缓存卷）：`:app` 的 `compileFullDebugJavaWithJavac` BUILD SUCCESSFUL。首跑报 1 个错误——本机「尺寸」组的 `setKeyboard` 旧签名（九键布尔参数）在上游 #10245 的 `KeyLayoutStylesPolicy.Preview` 重做后已不存在，改用上游静态 `KeyboardPreview.layout(context, preferences)`（同一存储方案推导，涵盖旧布尔参数）后通过；ImeFrame 宽度并入上游 `applied` 缓存串的融合由该编译验证。
- 笔记门禁（`pnpm run verify-notes` 的三个 node 脚本）通过：归档笔记封印与格式校验 ok。
- 未在本机声明 Android 真机、Apple 原生宿主或 HarmonyOS HAP 验收；Android host 契约、设备套件与 iOS Swift 测试交给远端 CI，构建产物 workflow 不带发布开关。
