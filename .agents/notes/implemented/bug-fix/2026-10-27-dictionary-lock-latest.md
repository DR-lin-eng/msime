# Agent Note: 词库发布锁跟上设备实际在用的词库

Status: implemented

## Problem

`resources/desktop-dictionary.lock.json` 从 2026-10-14 起一直锁 `dict-v2.0.24`（[上一次换锁](2026-10-14-dictionary-v2-0-24-pin.md)）。2026-10-18 六个平台都接入了[词库独立更新](../feature/2026-10-18-dictionary-auto-update.md)，默认每天检查一次，补词不再需要客户端发版，换锁看起来就不必要了，于是没人再动它。到 2026-10-27，`msime-dictionary` 已经发到 `dict-v2.0.39`，锁落后 15 个发布。

锁并没有因为自动更新而失去作用，它还决定两件事：随包基线（新装、自动更新关闭或下载失败、已装代次损坏时用的词库），以及 `verify-local.sh` 与依赖真实词库的评测、测试量的是哪一份词库。后者落后之后，所有评测基线量的都是用户已经不在用的词库。九键音节频度那次回归就是这样漏掉的：同一份代码配随包的 `dict-v2.0.24` 是对的，iPhone 自动更新到 `dict-v2.0.32` 之后「我们今天去哪里」打不出来，只在真机验收时才发现（[九键音节频度取前三个字](2026-10-20-nine-key-syllable-prior-top-three.md)）。

## Decision

客户端锁改到 `dict-v2.0.39`，即换锁时最新的发布，评测基线在新锁上重跑并提交。做法与上一次相同：

- 主锁里十一个来自 `msime-dictionary` 的条目改 URL，SHA-256 和大小取自 `gh release view dict-v2.0.39 --json assets` 的资产摘要，不下载二进制自己算；再与该发布的 `msime-dictionary-manifest.json`、`msime-SHA256SUMS.txt`、`msime-language-dictionaries-SHA256SUMS` 逐项核对，全部一致。`sentence-model.safetensors` 来自 `chinese-ime-lm` 的 `model-v1`，不变。
- `source_commit` 跟随清单的 `source.commit`（`8bb181be2ed36ed9559a87434cc4c9048077ff29`，`dirty: false`，是 develop 上的提交）。清单的 `format_version`、`engine_compatibility.dictionary_format` 仍是 1，日文模型 magic 仍是 `MSJPDT1`。
- 组件锁和版本锁用 `python3 scripts/editions.py gen-locks` 从主锁生成，`resources/language-dictionaries.lock.json` 同步前进（`msime-stroke.db` 自 `dict-v2.0.30` 起从 2.3 MB 变为 3.8 MB，其余语言词库字节不变）。
- full 资源锁的代次从 `7356c4a4…` 变为 `576494c48ac513d15b9920111a73cf149fb79aedcb8cc6947d0799cf36006553`，`crates/client-core/src/edition.rs` 里钉住代次的测试一起更新。已装的用户词库按代次分目录，升级后照常从回放日志重建。
- `resources/eval` 下 `verify-local.sh` 覆盖的十一份基线在新锁上以 `--update-baseline` 重新生成。`baseline-web-articles.json` 的输入不在本仓库（TapTapGo 的文章分句），`voice-baseline.json` 与词库无关，两者不动。

### 两个版本之间换了什么

资产列表没有增减或改名。字节变化的是 `msime-pinyin.db`（73.7→73.9 MB）、`msime-wubi.db`、`msime-english.db`（大小不变）、两张 n-gram 表（大小不变）、清单和 `msime-stroke.db`；日文词典、`msime-others.db`、许可证文本不变。

`msime-wubi.db` 从 14,536,704 字节涨到 94,982,144 字节，86 与 98 两张表各从约 13 万行涨到约 73 万行。这是[五笔多字词覆盖](../feature/2026-10-18-wubi-vocabulary-coverage.md)与[构建时派生五笔词条](../feature/2026-10-18-dictionary-completion.md)的结果，从 `dict-v2.0.29` 起出现。全量下载随之从约 189 MB 变为约 270 MB，五笔版的资源从约 118 MB 变为约 199 MB。

### 评测结果

同一个 release 二进制、同一份评测集，挂 `sentence-model.safetensors`，先在 `dict-v2.0.24`、再在 `dict-v2.0.39` 上跑，所以后两列的差别只来自词库数据。第一列是 develop 上提交的基线：`sentences`、`harvested`、`neutral`、`words`、`nine-key` 五份在 `dict-v2.0.24` 上也已经对不上（基线停在 10-13 到 10-22 的代码），本次一起覆盖。每格是 top-1 / top-5 / found，最后一列是两音节词的 top-1。

| 集合 | 条数 | 原基线 | dict-v2.0.24 | dict-v2.0.39 | 两音节 top-1（v2.0.24→v2.0.39） |
|---|---|---|---|---|---|
| `sentences` | 60 | 0.800 / 0.850 / 0.850 | 0.817 / 0.833 / 0.850 | 0.817 / 0.833 / 0.833 | |
| `harvested` | 312 | 0.324 / 0.817 / 0.939 | 0.503 / 0.827 / 0.939 | 0.494 / 0.772 / 0.885 | |
| `neutral` | 1105 | 0.641 / 0.884 / 0.928 | 0.708 / 0.876 / 0.928 | 0.648 / 0.825 / 0.878 | |
| `words` | 2791 | 0.781 / 0.960 / 0.989 | 0.781 / 0.957 / 0.989 | 0.750 / 0.943 / 0.988 | 0.727 → 0.683 |
| `nine-key` | 2791 | 0.571 / 0.847 / 0.963 | 0.571 / 0.847 / 0.963 | 0.518 / 0.815 / 0.954 | 0.423 → 0.366 |
| `nine-key-sentences` | 81 | 0.642 / 0.667 / 0.728 | 同左 | 0.519 / 0.605 / 0.704 | |
| `fourteen-key` | 2791 | 0.658 / 0.903 / 0.973 | 同左 | 0.619 / 0.888 / 0.972 | 0.535 → 0.487 |
| `fourteen-key-sentences` | 81 | 0.667 / 0.691 / 0.802 | 同左 | 0.580 / 0.654 / 0.728 | |
| `particles` | 103 | 0.680 / 0.971 / 1.000 | 同左 | 0.670 / 0.951 / 1.000 | |
| `particles-nine-key` | 103 | 0.194 / 0.728 / 0.951 | 同左 | 0.233 / 0.767 / 0.990 | |
| `particles-fourteen-key` | 103 | 0.311 / 0.854 / 1.000 | 同左 | 0.359 / 0.883 / 1.000 | |

除三份句末语气词集合外全部下降，九键、14 键最多。原因在权重：`dict-v2.0.30` 起词库按万象词频重调单字权重，多字词的权重在这批发布里也大幅变动。摆脱 199375→80850、拜托 53420→171736，本着 9999→57712、奔着 5555→72395，财力 125580→23681、彩礼 6515→50931；散步 101880→64915、跑步 66055→335220，两者在九键上同码，「我们一起去公园散步吧」于是打成「跑步吧」；来过 66575→31694、开过 34000→56792，同样九键同码，「我以前来过一次」打成「开过一次」。这些不是代码回归：设备经词库自动更新早已在用这批数据，基线现在如实记下了用户手上的排序质量。改善要在词库仓（权重来源）或引擎（对权重的敏感度）上做，不在本次范围内。

## Alternatives considered

- **继续锁 `dict-v2.0.24`，靠自动更新把新词库送到用户手里。** 不动锁最省事，随包基线也最保守：自动更新出问题时用户回到一份评测过的词库。但评测、`verify-local.sh` 和依赖真实词库的测试会继续量一份没人在用的词库，九键那次回归正是这样只在真机上才暴露；锁越久越远，下一次不得不换时要一次吞下的差异越大。所以不采用。
- **CI 与评测自动跟随最新发布，不再手工换锁。** 能保证测量对象永远等于设备上的词库，也省掉换锁这一步。但评测基线是提交进仓库的数字，跟随最新发布意味着任何一次词库发布都会让与之无关的 PR 的评测门禁对不上，而且对不上的原因不在那个 PR 里；随包基线和资源代次也会随构建时间变化，同一个提交两次构建出两种安装包，`edition.rs` 钉住的代次和锁派生关系都失去意义。可复现性比时效性更重要，所以仍然手工换锁，只是把换锁当作词库发布后的例行一步，而不是只在补词时才做。
- **锁 `dict-v2.0.28`，即五笔码表变大之前的最后一个发布。** 能避开下面说的安装包体积门槛，但设备上早已是 `dict-v2.0.3x` 的五笔码表和权重，评测仍然量不到用户手上的东西，问题没解决，所以不采用。

## Consequences

- **收益**：评测基线、`verify-local.sh` 和 `MSIME_EVAL_RESOURCES` 下的真实词库测试量的是设备经自动更新已经在用的词库；新装用户和自动更新不可用的用户拿到的随包词库与其他人一致。
- **代价**：基线数字整体下降（见上表），这是词库数据带来的，记录在案而不是被掩盖；下一个动排序的改动要以新基线为准。
- **安装包体积门槛会挡住下一次发版**。本次没有改这两个门槛，需要另外拍板：
  - Android 的 full 与拼音瘦包（`platforms/android/build-apk.sh`，`MSIME_ANDROID_MAX_APK_BYTES` 默认 135,000,000）带着五笔码表。`android-v0.6.0` 的 full 瘦包已是 133,833,900 字节；`msime-wubi.db` 用 gzip -6 估算从约 7.4 MB 涨到约 44.9 MB，拼音库约多 0.4 MB，瘦包估计约 171.7 MB，超出门槛约 37 MB。2026-10-26 刚为守住这个门槛把读音表改成资源包（[英文音标候选](../feature/2026-10-26-android-candidate-pronunciation.md)），直接抬门槛与那次的取舍相反；另一条路是像 macOS 一样把五笔码表改成按需资源包（[macOS 五笔按需下载](../feature/2026-10-20-macos-on-demand-voice-runtime-and-wubi.md)）。
  - macOS 五笔版随包带五笔码表，`platforms/macos/package-release.sh` 给 `EngineResources` 定的 126000 KiB 预算会被约 194,800 KiB 超出（原约 116,036 KiB，加五笔码表约 +78,560 KiB）。full 与拼音版不带五笔码表，约 106,400 KiB，不受影响。PR 里的 `ci-macos-package.yml` 只打 full，查不出来，`release-macos.yml` 打全部版本时才会失败。
  - Linux 安装说明里「首次约 170 MB」在换锁前就已不准，现在全量下载约 270 MB，本次没有改。
- **重访信号**：词库每发一次版就该评估一次换锁；评测基线与设备上的词库再次相差多个发布时，说明换锁又被跳过了。

## Verification

- `python3 scripts/editions.py gen-locks --check`、`scripts/test-editions.py`、`scripts/test-macos-package-resources.py`、`scripts/test-fetch-language-dictionaries-bounds.py`、`scripts/test-language-data-notices.py`、`scripts/test-linux-setup-download-links.py`、`scripts/test-reference-feature-log.py` 等与锁相关的检查通过。
- 在 Mac Studio 上 `cargo run --release -q -p msime-client-core --example install_resources --locked -- target/resources` 下载并校验了新锁全部资源，目录名即新代次。
- 评测：`convert_eval` 按 `verify-local.sh` 的十一组参数，先以 `--resource-lock` 指向旧锁在 `dict-v2.0.24` 上跑，再在新锁资源上以 `--update-baseline` 跑，结果见上表。完整 25,119 条全拼词级在新锁上 top-1 0.756、top-5 0.945；完整九键词级因构建机过载没有跑完。
- 测试：`cargo test -p msime-engine -p msime-input-runtime -p msime-client-providers -p msime-host-api -p msime-client-core` 不设资源时，client-core（含钉住新代次的 `full_keeps_the_desktop_lock_and_its_generation`）、client-providers、engine、input-runtime 全部通过；host-api 失败的是下面那两项，另有 `a_lookup_keeps_the_users_japanese_mixing`（合成夹具，不读锁）失败一次，单独重跑通过。再以 `MSIME_EVAL_RESOURCES` 分别指向两份词库跑 engine、input-runtime、host-api：两边各 4150 项通过、8 项失败，失败的是同一组，没有一项由换锁引起——四个 golden 真实集和 `japanese::decoder::tests::real_model_loads` 按设计要 `dict-v2.0.1`（golden 记录自 C++ 参考引擎）；`session::tests::real_resources_mix_japanese_conversions` 在两份词库上都给不出 ohayou 的日文候选，而两版的 `msime-japanese.dat` 字节相同；host-api 的资源包清单测试漏了 #9931 新加的 `pronunciations`；脚本插件的本地时间测试与词库无关。被忽略的 `shipped_rime_import_retains_reported_common_character_positions` 以新锁的 `msime-pinyin.db` 显式运行通过。
- 延迟：`rerank_latency` 在新旧词库上交替各跑两轮，构建机负载 60 到 180，挂模型的 p95 旧词库 29.15、15.17 ms，新词库 16.72、97.94 ms，同一词库两轮之间的差远大于两个词库之间的差，这次量不出结论，需要在空闲机器上复测。
