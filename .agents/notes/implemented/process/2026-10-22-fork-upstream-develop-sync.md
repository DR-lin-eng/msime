# Agent Note: 分叉 develop 保留本机能力并同步上游

Status: implemented

## Problem

本地 develop 已增加神经排序、候选画布缓存、充电夜间学习及跨平台验证码入口；上游从共同基线 `6aed9c73834f2d7e92bfb9692a936b0801dc04af` 前进到 `ac76aeca3357ad142a6803f15a8df50d1106bc80`，共 191 个提交。直接选择任一侧会丢掉另一侧的排序、原生画布或生命周期能力。

## Decision

先独立合入本机功能，再从已更新的 develop 集成固定上游提交，保留双方祖先，后续同步只处理新的上游范围。共享排序资格仍由上游 `rerank_needs_model` 决定；同步和后台评分都传递完整 `RerankPlan`，接续本地次选次序及 512 字符窗口。新画布保留上游布局和动效，文字缓存扩展到 chrome 入口，内容代次变化时失效。iOS 的 audio、processing 模式在同一个 YAML 数组声明，Android 的内联自动填充、验证码监听和夜间调度均按原生命周期注册与清理。

共享资源包卸载函数补齐 C 头文件声明；旧资源列表、AI 服务方接口记忆、算式符号及组字内 `$` 的 ABI 回归按上游已落地契约更新，仍断言完整结果及明确确认后才提交。macOS 色彩夹具把浅色外观固定到窗口，避免重建子视图后继承测试机深色外观。许可证正文不改，只去掉末尾额外空行，Rust 测试格式沿用 rustfmt。

## Existing decisions

[后台重排](../bug-fix/2026-10-21-async-settled-rerank.md)、[次选次序](../feature/2026-10-21-rerank-scored-runner-ups.md)、[画布缓存](../feature/2026-10-20-linux-canvas-animation.md)和[输入辅助](../feature/2026-10-21-charging-learning-and-input-assistance.md)部分重叠，原位补充适配边界；[特殊符号组字](../bug-fix/2026-10-22-pinyin-literal-symbol-composition.md)继续拥有 `$` 在组字内的产品语义。没有翻转或吸收独立决定。

## Alternatives considered

按提交逐个挑拣可以只带入指定功能，但本次要求完整同步 191 个提交，跨平台更改彼此依赖，且丢失上游祖先后会在下次同步重做同一范围；采用保留祖先的 merge。单纯选择上游文件最容易消除文本冲突，但会撤销已验证的本机功能；冲突处适配双方契约并补充行为回归。

## Consequences

后续可以用 Git 祖先直接判断已同步的固定范围。代价是需要同时验证新的后台评分、画布入口和宿主生命周期；Docker 覆盖共享 Rust 与 Linux，Android 与 Apple 原生检查独立记录，不能用容器结果声称真机夜间调度或 NPU/GPU 性能已测。


## Verification

固定 Rust 1.97.1 / Debian 12 Docker 中，七个共享 crate 的单元与集成回归分别通过；继承的 ABI 旧断言修正后，host-api 927 项、input-runtime 367 项通过，新增后台完整排列回归实际执行。七 crate 全部 targets 的 `clippy -D warnings` 和格式检查通过，`--quick` 完成工作区、Linux 设置壳、Linux 原生构建及测试。机械性的测试初始化、冗余 Result 闭包与许可证末尾空行同时修正，没有扩大失败基线。

iOS App/键盘扩展构建、安装、启动及 12 项 UIKit 回归通过；包内同时有 audio 与 processing 后台模式。macOS 原生输入法构建及 shortcut、input-assistance 两项测试通过。Android 256 项 JVM 冒烟、215 个设备用例源码编译，以及 API 28 上四个共享客户端 crate 的整套测试通过；原生库的 ELF/16KB 对齐和开发 APK 构建通过。UI 类型检查、构建和笔记检查独立通过。远端 CI、真实 Apple 短信、系统整夜调度和设备 NPU/GPU 性能不由这些本地结果推断。
