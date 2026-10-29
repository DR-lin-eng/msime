# Agent Note: 符号模式分组表改由 Unicode 字符数据库重建

Status: implemented

## Problem

符号模式（Shift+S）的分组表 `crates/engine/src/local/symbol_groups.tsv` 原先由 `scripts/build_symbol_groups.py` 从 rime-wanxiang 的 `wanxiang_symbols.yaml`（CC BY 4.0）生成，取的是「分组码 → 码位集合」以及每组的码位顺序，见[符号模式](2026-10-21-symbol-mode.md)的「分组码来源与许可判断」。上游文件头写明它的分组最初抄自 Rime 的 `symbols.yaml`，而 rime-prelude 仓库是 LGPL-3.0，这条授权链从没审过。

引擎准备改以 MPL-2.0 作为可嵌入的库发布（设计笔记在 PR #10167，待拍板第 5 条）。维护者已定：这条链按 LGPL-3.0 对待。LGPL-3.0 的数据静态编进 MPL 库，对 App Store 采用方不可接受（LGPL-3.0 §4 要求能替换库），所以引擎不能再内嵌由 rime-prelude 派生的分组与顺序。

## Decision

- **组员只按 Unicode 字符数据库的事实挑选。** 输入是 UCD 17.0.0 的 `UnicodeData.txt`（字符名、通用类别、分解映射、大写映射）、`Blocks.txt`（区块）和 `EquivalentUnifiedIdeograph.txt`（部首到统一汉字的等价映射），许可是 Unicode License V3。`resources/symbol-groups.lock.json` 按 unicode.org 的地址固定三份文件的长度与 sha256，并记下 `resources/licenses/Unicode-License-V3.txt` 的摘要。生成器不调用 Python 自带的 `unicodedata`，结果只取决于锁定的输入。
- **分组码、中文组名和组的先后不变**，仍是 87 组。分组码是 Windows 正式版出厂配置点名过的界面（`xl`、`sx`、`szq`、`jt`），中文组名一直是本项目写的。变的是每组收哪些字、按什么顺序。
- **取字规则写在生成器的 `GROUPS` 里，由本项目编写。** 规则的写法有四种：整个区块（几何图形、方块、麻将、六十四卦），字符名的正则（带圈数字、罗马数字、电报月份），类别加区块（夹注符号取标点区块里的 Ps/Pe/Pi/Pf，货币取基本多文种平面里的全部 Sc），以及分解映射（上标、下标取 `<super>`、`<sub>`，康熙部首取兼容分解出的统一汉字，单位取 `<square>` 分解全是拉丁字母的方块字）。需要点名个别字时写 Unicode 字符名，例如单位里的 DEGREE CELSIUS、声调里的 MODIFIER LETTER MACRON。拼音一组按《汉语拼音方案》的字母与四声生成，没有预组合字时给两个码位的序列（m̄、ê̄）。
- **组内按码位排序。** 只有三处例外，排序键都由数据导出：上标、下标按分解出的基本字符排；俄文字母里带变音的字母（ё）跟在基本字母后面；数学字母数字各字体按区里的排法（大写 A–Z、小写 a–z、数字），区里的空位由字母类符号区里 `<font>` 分解与字体名相符的字补上（ℎ ℬ ℂ）。旧表把大小写交错排（A a B b），这个顺序不再沿用。
- **每组不超过 200 个符号**（`SYMBOL_MODE_LIMIT`，整串分组码一次最多出这么多行）。康熙部首是唯一的例外，它是 214 个部首的完整集合，旧表同样如此。数学符号一组为此去掉了带 WITH 的修饰形、SMALL 小号形、有兼容分解的连写形和有规范分解的否定形，否定形只留 ≠ ≮ ≯，得 197 个。常用符号一组收杂项符号区里没被其他组收走的字，得 195 个。生成器和 `symbol_groups.rs` 的单测都检查这个上限。
- **rime-wanxiang 的署名链路整条拿掉。** `resources/licenses/rime-wanxiang-CC-BY-4.0.txt` 只为这张表存在，所以删掉了，十二个声明渠道里的引用一并删除。Unicode License V3 早已因拆字表的 Unihan 数据接进了这些渠道，这次只把它的说明扩到符号模式。
- **CI 逐字节核对。** `ci-platforms.yml` 的 Rust engine 作业按锁的摘要缓存 `target/symbol-groups-cache`，再以 `MSIME_FETCH=1` 跑 `scripts/test-symbol-groups.py`，做法与拆字表相同。锁文件改动也会触发引擎作业。此前文档写着 CI 会核对这张表，但工作流里其实没有这一步。

### 数据放在哪

[符号数据统一由词库仓保管](../process/2026-10-25-symbol-data-in-dictionary-repo.md)管的是第三方符号表与本项目自写的名称表，它们要有一处保管、一处署名。UCD 是 Unicode 联盟按版本发布的标准数据，仓库里的 Unihan（拆字表、字释义）一直按锁直接从 unicode.org 取。所以这张表也直接从 unicode.org 取，不进词库仓。取字规则是代码，写在生成器里。

## Alternatives considered

- **保留 rime-wanxiang 的表，按 LGPL-3.0 处理。** 这条路最强的理由是零行为变化。没有采用，因为 LGPL-3.0 §4 要求库可以替换，而静态链接、上架 App Store 的采用方做不到。设计笔记里已经把这条判为不可接受。
- **把分组表改成运行时数据，不编进引擎。** 这条路最强的理由是引擎库可以干净地以 MPL-2.0 发布，数据保留原样。没有采用，因为 LGPL 链没有消失，只是转给了分发数据的那一方，六个平台的宿主还要各自加载和分发这份数据。待拍板第 5 条已定为按 Unicode 事实重建。
- **在 UCD 之外再引 NamesList.txt 的小标题做分组**（例如「ISO/IEC 9995-7 键盘符号」「星号与雪花」这类小节）。这条路最强的理由是组员会更贴近 Unicode 码表的分节。没有采用，因为三个输入文件已经够用。电脑按键一组直接写了 U+2380–U+2395 这一段，并在注释里注明它是 ISO/IEC 9995-7 键盘符号所在的码位段。
- **按 GB 2312 第 1 区挑常用数学符号**，旧表的 45 个数学符号大致就是这一区。这条路最强的理由是选出的符号更「常用」。没有采用，因为 GB 2312 不在 UCD 里，要多引一份标准的码表。改为数学运算符区加四条按 UCD 字段写的排除规则。

## Consequences

- **收益**：
  - 引擎内嵌数据里不再有 rime 系的分组或顺序，以 MPL-2.0 发布时少了一份要单独声明的数据许可。
  - 规则按 UCD 版本可重放：升级 Unicode 版本时只改锁，新增的字按规则自动进组，样例数的变化由 `test-symbol-groups.py` 报出来。
- **行为变化**（87 组共 4 530 个符号，旧表是 3 929 个，两表共有 3 646 个；40 组成员与旧表相同，其中 22 组顺序也相同）：
  - 变化最大的几组：`sx` 从 45 个增到 197 个，开头是 ¬ ± × ÷ ∀ ∁，不再有全角的 ＋ － ＜ ＝ ＞。`yb` 从 48 个英语音标（含 i: tʃ 这类双字母组合）变为 IPA 扩展区的 96 个字母加 æ ð ŋ θ ː，两表共有的只有 11 个。`dn` 从 45 个变为 75 个（杂项技术区里的按键符号、ISO/IEC 9995-7 段和控制图形区），共有 11 个。`pp` 改为部首补充区按等价映射换成的统一汉字，共有 33 个。`hw` 从 14 个辅音扩到兼容字母区的 93 个。
  - 首个候选变了的组：`szq` 从 ⓪ 变为 ①，`jt` 从 → 变为 ←，`sb` 从 ⁰ 变为 ⁽。
  - 变小的组：`xb` 从 84 个降到 38 个，旧表混入的小型大写字母（ᴀ ʙ）不是下标，不再收。`bdz` 从 41 个降到 33 个，`bd` 从 61 个降到 57 个。
  - 还有一些组多出了 Unicode 新版加的字，例如 `bh` 多了 ㇤ ㇥，`jg` 多了 ⿼–⿿ 和 ㇯。
- **代价**：
  - 依赖旧顺序的肌肉记忆会失效，比如 `jt` 的第一个候选、数学字母的 A a 交错排法。新顺序由码位决定，以后不会因为某个符号表的维护而漂移。
  - `kx` 仍有 214 个，最后 14 个部首用整串分组码打不出来，与旧表相同。这次没有调整 `SYMBOL_MODE_LIMIT`，因为它与 Windows 正式版的上限一致。
  - 网页引擎的声明（`web-engine-NOTICE.md`）原本既没有 rime-wanxiang 也没有 Unicode License V3。这次没有补，留给统一重写声明文档的那一步。
- 已检索的相关笔记：[符号模式](2026-10-21-symbol-mode.md)的「分组码来源与许可判断」一节被本篇取代，其余决定（入口、查询顺序、来源复用 Emoji、默认开）照旧，那篇已加指向本篇的说明；[符号数据统一由词库仓保管](../process/2026-10-25-symbol-data-in-dictionary-repo.md)里分组表锁改从词库仓下载的那一条不再成立，那篇同样加了说明；[补齐希腊字母与数学符号名称](2026-10-25-math-greek-symbol-names.md)只用到 LaTeX 命令名表，与本篇无关。

## Verification

- 生成器从锁定的三份文件生成表，`--check` 逐字节一致。`scripts/test-symbol-groups.py` 在有缓存和无缓存（打印 skip）两种情况下都通过。
- 在构建机上跑 `cargo test -p msime-engine --lib -- symbol`，70 项通过，其中有 `local::symbol_groups` 的 7 项和会话层符号模式的 5 项。`cargo clippy -p msime-engine --lib --tests -- -D warnings` 通过。
- 受影响的声明渠道检查都通过：`test-chaizi-table.py`、`test-language-data-notices.py`、`test-macos-package-resources.py`，以及 iOS 的 `ProjectConfigurationTests`（26 项）。各平台安装包没有重新构建，macOS `bundle_contents.py` 和 Windows `collect_notices.ps1` 只改了清单，没有实际跑过。
