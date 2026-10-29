# Agent Note: Android 设备验收挪到 Depot

Status: implemented

## Problem

`android-device.yml` 是 GitHub 托管 Linux runner 上最大的占用。[改键盘或设置界面的 PR 跑一遍设备验收](2026-10-28-android-device-on-ui-prs.md)之后，每个改到 Android 界面的 PR 都起一台 API 35 模拟器，整套最长 90 分钟。2026-10-29 上午同时有 13 个这样的 job 在 GitHub 托管的 `ubuntu-24.04` 上跑（11 个 PR 的 API 35，加 develop 推送的 API 28 和 35），另有 8 个 `windows-2025`。组织共用的 20 个托管并发名额（macOS 的 5 个也算在里面）被占满，其余托管 job 只能排队。

它留在 GitHub 上的理由是「模拟器要 KVM，Depot 的 runner 不保证有」（[Android 设备门禁](../testing/2026-10-08-android-device-gates.md) 的 Alternatives，[不碰长期凭据的 job 默认跑在 Depot 上](2026-10-27-depot-runners-by-default.md) 沿用了它）。这一条从来没在 Depot 上验证过。macOS 挪到 Depot 之前有 `depot-macos-probe.yml` 实测，KVM 没有。

## Decision

`android-device.yml` 的 job 跑在 `depot-ubuntu-24.04-8`。打开 KVM 权限的 udev 步骤保留：Depot 上 `/dev/kvm` 本来就是 0666，GitHub 托管 runner 上则必须开放权限，以后要临时退回时不用再加。`udevadm trigger --name-match` 传完整设备路径 `/dev/kvm`，不传裸名字 `kvm`；[官方接口](https://freedesktop.org/software/systemd/man/latest/udevadm.html)按设备路径匹配，Depot 的实际作业会拒绝裸名字。失败日志收集先创建输出目录，避免安装工具或启动模拟器之前失败时，重定向也因目录不存在而丢失诊断。

依据是 #10190 里的一次性探测（`depot-android-kvm-probe.yml`，在两种 runner 上做同一件事，结论记下后在同一个 PR 里删掉），2026-10-29 的结果：

| | `ubuntu-24.04` | `depot-ubuntu-24.04-8` |
| --- | --- | --- |
| CPU、内存 | 4 核、15 GB | 8 核、31 GB |
| CPU 虚拟化标志 | 4/4 | 8/8 |
| `/dev/kvm` | 有，打开权限前 0660 | 有，0666 |
| `emulator -accel-check` | KVM (version 12) is installed and usable | 同左 |
| API 35 AVD 开机 | 45 秒 | 21 秒 |

两边镜像里都有 `sdkmanager` 和 `adb`，都没有预装 emulator，由 workflow 自己装，和原来一样。

探测不保留：`android-device.yml` 自己在每次 PR、推送和 nightly 上起模拟器，Depot 哪天没了 KVM，它会在开机这一步超时报红，比手动触发的探测更早发现。

## Alternatives considered

- **留在 GitHub，限制 `android-device-pr` 的触发**（比如打标签才跑）：不占 Depot 额度。不采用：Depot 上跑得更快，限制触发会把 10-28 那篇要解决的「合并后才看见界面验收失败」重新带回来。
- **用 `depot-ubuntu-24.04-4`**：更便宜。不采用：这个 job 要在同一台机器上交叉编译四个 client crate 的单测和 APK，同时跑模拟器，8 核 31 GB 开机快一倍，构建也是 8 核的规格；按 [Depot 默认](2026-10-27-depot-runners-by-default.md) 的规格划分，编 Rust 用 `-8`。

## Consequences

GitHub 托管的 Linux 名额只剩发版、发布和 Pages 这类持有凭据或用 OIDC 的 job。代价是设备验收也依赖 Depot 的可用性，Depot 出故障时 PR 上的 `android-device-pr`（非必需检查）和合并后的 push、nightly、发版前门禁都会卡住；`release-android.yml` 发版前调用的就是这个 workflow。

## Verification

`actionlint` 通过。这个 PR 改到了 `android-device.yml`，`ci-platforms.yml` 的 `android_device` 会点亮，PR 上的 API 35 整套验收就在 Depot 上实跑一遍。API 28 只在合并后的 develop 推送上跑，合并后看第一次的结果。

[实际失败作业](https://github.com/metasequoiaime/msime/actions/runs/40891799388/job/123510811361)在 KVM 初始化阶段报 `Failed to open the device 'kvm': Invalid argument`，尚未安装工具或启动模拟器；原探测只验证 KVM 可用和开机时间，没有覆盖保留的 udev 命令。完整设备路径修正沿用权限规则与触发步骤，新的 runner 验收以修正后的设备作业结果为准，不能把原探测当成整个 workflow 已通过。
