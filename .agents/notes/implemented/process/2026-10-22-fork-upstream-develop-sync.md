# Agent Note: 分叉 develop 保留本机能力并同步上游

Status: implemented

## Problem

分叉 `develop` 在固定基线 `fd4ada8bc2c6b736b4a027b00b82c30710948bf1` 保留神经排序、候选画布缓存、充电夜间学习、跨平台验证码入口、搜狗词库订阅及 Android 触点概率纠错。上次同步的上游祖先是 `ac76aeca3357ad142a6803f15a8df50d1106bc80`，本轮固定到 `c8c3c883c5a768f4409b75984a3cb07622f88c4a`，新增 121 个上游提交。同步过程中分叉继续前进到 `cbac97d6ebb63fa3cbbb48f94a331b19fd31eaac`（PR #5），其 Android 设备焦点与关闭滑动修复也进入最终合并。直接选择任一侧会丢掉另一侧的排序、原生画布或生命周期能力。

## Decision

先独立合入本机功能，再从已更新的 develop 集成固定上游提交，保留双方祖先，后续同步只处理新的上游范围。共享排序资格仍由上游 `rerank_needs_model` 决定；同步和后台评分都传递完整 `RerankPlan`，接续本地次选次序及 512 字符窗口。新画布保留上游布局和动效，文字缓存扩展到 chrome 入口，内容代次变化时失效。iOS 的 audio、processing 模式在同一个 YAML 数组声明，Android 的内联自动填充、验证码监听和夜间调度均按原生命周期注册与清理。

共享资源包卸载函数补齐 C 头文件声明；旧资源列表、AI 服务方接口记忆、算式符号及组字内 `$` 的 ABI 回归按上游已落地契约更新，仍断言完整结果及明确确认后才提交。macOS 色彩夹具把浅色外观固定到窗口，避免重建子视图后继承测试机深色外观。许可证正文不改，只去掉末尾额外空行，Rust 测试格式沿用 rustfmt。

本轮在保留触点数组清理的同时绑定上游双拼辅助码模式；光标插入按实际上游规范化后的 `inserted` 字节同步触点。句中辅助码会从查询串移除标记和辅助码字节，进入该模式就清空尚未做逐字映射的触点，使用原有无触点排序，避免把后续字母的坐标移到别的按键；退出组字后的新输入恢复触点纠错。Android 候选态松手由普通点击消费已采集的触点，只有长按或滑动接管才清除样本。关闭滑动后的超阈值位移沿普通点击回调提交，保留触点且反馈、计数只执行一次。销毁输入法同时释放本机输入辅助监听与上游操作提示；导出校验同时要求触点接口与新增的语音、热键接口。AI 目录验收使用共享预设验证切换结果，并逐项核对 JNI 解析没有丢掉任何服务。

构建从合并后的同一远端提交触发六个原生平台工作流；所有 `publish` 关闭，iOS 的 `testflight` 与 `testflight_external` 关闭。构建接受、构建成功、安装包生成和设备验收分别记录，不能由其中一项推断其余项。

上游的 Depot 标签只在 `metasequoiaime` 组织使用。fork 的 CI 和 Linux、Windows 构建改走同架构 GitHub 托管 runner，Apple arm64 使用带固定 Xcode 的 `macos-26`，Linux arm64 使用 `ubuntu-24.04-arm`。Windows fork 同时使用托管 runner 的 120 分钟构建预算与 GitHub sccache 后端；不放宽验证、签名和发布条件。专门探测 Depot 镜像的诊断工作流仍保留原标签。直接照搬标签能保持文件完全一致，但 fork 实际没有对应 runner，作业会一直排队，因此在同一表达式保留上游标签并提供 fork 回退。

## Existing decisions

[后台重排](../bug-fix/2026-10-21-async-settled-rerank.md)、[次选次序](../feature/2026-10-21-rerank-scored-runner-ups.md)、[画布缓存](../feature/2026-10-20-linux-canvas-animation.md)和[输入辅助](../feature/2026-10-21-charging-learning-and-input-assistance.md)、[触点纠错](../feature/2026-10-23-mobile-touch-correction.md)部分重叠，原位补充适配边界；[特殊符号组字](../bug-fix/2026-10-22-pinyin-literal-symbol-composition.md)继续拥有 `$` 在组字内的产品语义。没有翻转或吸收独立决定。

## Alternatives considered

按提交逐个挑拣可以只带入指定功能，但本次要求完整同步固定范围的 121 个提交，跨平台更改彼此依赖，且丢失上游祖先后会在下次同步重做同一范围；采用保留祖先的 merge。单纯选择上游文件最容易消除文本冲突，但会撤销已验证的本机功能；冲突处适配双方契约并补充行为回归。

## Consequences

后续可以用 Git 祖先直接判断已同步的固定范围。代价是需要同时验证新的后台评分、画布入口和宿主生命周期；Docker 覆盖共享 Rust 与 Linux，Android 与 Apple 原生检查独立记录，不能用容器结果声称真机夜间调度或 NPU/GPU 性能已测。


## Verification

本轮验证命令、固定输入、退出码、原始输出及 baseline / modified / rollback 源码指纹保存在独立事务记录中。提交前运行仓库 quick 门禁与笔记门禁，冲突影响的共享引擎、Host API、Android Java/JNI 和共享设置页按实际可用环境验证。远端工作流与构建产物均绑定最终远端 SHA；尚未执行的设备或真机检查不计为通过。
