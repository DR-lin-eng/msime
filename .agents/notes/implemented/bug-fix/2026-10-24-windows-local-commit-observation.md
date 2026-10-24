# Agent Note: Windows 回车同步读取宿主原文

Status: implemented

## Problem

宿主会话拥有组字时，旧的键击缓冲为空。回车前的校验消息仍从旧缓冲构造，因此本地已上屏的原文没有传给 Server，Server 的 LocalCommit 校验拒绝，后续两个会话不再同步。

## Decision

回车校验从现有 GetKeyCompositionState 读取同一次当前原文，沿用已选前缀与 128 个 UTF-16 单元的限制。不关闭 Server 的提交观察校验，不改学习或输入方式默认值。

## Existing notes audit

- [宿主候选续接](../../implemented/bug-fix/2026-10-16-windows-candidate-continuation.md)：部分重叠；继续保留独立宿主会话与代次边界，此处修复回车发包的数据来源。
- [Esc 与回车学词](../../implemented/feature/2026-10-21-esc-keeps-selection-and-enter-learns-english.md)：无关设置决定；保持原默认值与学习语义。

## Alternatives considered

直接放宽 Server 的 LocalCommit 校验改动更少，但会把无法证明与本地实际提交一致的状态当成成功，因此保留校验，修正发包来源。

## Verification

从实际生产发包片段编译的合成回归覆盖空旧缓冲、已选前缀、过时旧缓冲、旧路径与长度上限；Docker 基线在六个场景中失败四项，修改后六项通过；同输入回滚重新执行。真实 Windows 编辑器交互仍单独验收。

## Consequences

GetKeyCompositionState 读取失败时沿用现有拒绝，不用旧缓冲猜测宿主状态。
