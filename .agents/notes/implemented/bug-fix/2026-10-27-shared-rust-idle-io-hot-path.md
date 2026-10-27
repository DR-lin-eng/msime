# Agent Note: 共享 Rust 层在没有变化时不再整份读写和 fsync

Status: implemented

## Problem

低端手机用户反馈键盘耗电、发热、卡顿。Android 那一侧的每秒偏好轮询已改成只在窗口可见时运行（见 [Android 键盘只在窗口显示时每秒重读偏好](2026-10-27-android-preference-poll-only-while-visible.md)），但三端共用的 Rust 层（`msime-engine`、`msime-client-core`、`msime-client-services`、`msime-host-api`）在按键、上屏、聚焦和空闲轮询路径上还有六处「什么都没变也照样整份读写」的开销，都在宿主的输入线程、主线程或频繁唤醒的工作线程上：

- 打字统计 `record` 每次上屏都在文件锁下整份读入、解析、重写 `typing-statistics.json` 并 fsync（Apple 上是 `F_FULLFSYNC`），英文模式下每个字母一次；文件按天增长，统计关着时也要整份读一遍才知道不记。
- 选词计数、调频、选词转移、整句联想都同步提交到用户日志 `msime_user.db`，SQLite 默认的回滚日志加 `synchronous=FULL` 让每次提交建 `-journal`、fsync 两三次再删掉。低端机 eMMC 上一次 fsync 是几到几十毫秒，选非首位候选时键盘随之卡顿。
- 整句联想的个人 n-gram 模型（最多 20 万条）只要没有会话引用就被注册表丢掉。Android 每进一个输入框都销毁再新建会话，离上次上屏超过约 2 秒时，下一个会话就在主线程上把 `personal_bigram`、`personal_trigram` 整表重读。
- `msime_client_create` 拿空的 `PluginTables` 作对照，每个新会话都重读指令表、短语表、辅助码表包和联系人名单；没填拼音的联系人姓名还要现场扫主词库查读音。
- `personal_dictionary_sync` 在 Android 每次聚焦、HarmonyOS 每次重建会话前都要扫用户日志凑一页，再把 `sync.json` 原样重写并 fsync，即使队列是空的。
- 偏好：Android 键盘可见时每秒 `msime_client_load_preferences` 整读并两遍解析、校验 `preferences.json`，主线程再把同一份快照交给 `msime_client_update_preferences`，后者照样校验、四次整棵比较、跑十几个 setter、重读效果包清单。快照队列的本机词库版本（Android 每次失焦、iOS 每 10 秒、HarmonyOS 每 2 秒）每次都把日志全表排序做 SHA-256，并重写一次 `state.json`。

## Decision

总原则：先用便宜的方式（stat、字节比较、内存里的上一份）判断「来源没变」，没变就沿用上一次的结果、不写盘；判断不了（stat 失败、戳读不出、刚写过）就照旧走完整路径。凡是可能决定数据正确性的地方仍然精确计算。

### 打字统计攒批（`client-services/src/typing_statistics.rs`、`host-api/src/ffi/host.rs`）

- `record` 只在内存里分类计数，按统计目录攒在进程级缓冲里，只存分类计数、日期、小时和时刻，不留原文。写出的时机是维护者定下的跨平台约定「失焦或每 30 秒」：距上次读写该目录满 `COMMIT_FLUSH_INTERVAL_MS`（30 秒）后的下一次 `record`、显式 `{"operation":"flush"}`、或同一目录上的任何其他操作（load、summary、reset、上传……），都先在文件锁内重读文件，再按到达顺序逐条合并后写一次。逐条合并复用原 `record_at` 的逻辑（`TypingStatistics::apply_commit`），活跃时长、连续输入、小时分布与逐次写入完全相同。
- 清空统计换一个随机的清空代号（generation）。攒着的批次记下开始攒时的代号，写出时代号变了就整批丢掉，设置页清空后键盘不会把清空前打的字写回来；别的进程关掉统计时同样丢掉这批，改保留期则合并。代号用随机值不用计数，因为清空次数本身也在说用户。
- 成就里程碑按 `record` 答出的「文件累计 + 进程内未写」总数判断，不再先 load。
- 会话失焦、`msime_client_destroy`、`msime_client_flush_all` 写出全部目录的批次。macOS、Linux 的失焦和移动宿主的失焦与销毁在新开的后台线程上写，不占主线程；Windows 失焦在服务进程的 IPC 线程上当场写；桌面销毁当场写（之后进程可能退出）。Tauri 面板和 Windows 7 的剪贴板、云剪贴板、表情、语音、手写面板没有失焦或退出收尾，记完立刻 `flush`，行为与攒批前相同。头文件 `msime_client.h` 写明 flush 和攒批语义。

### 用户日志进 WAL（`engine/src/user_dictionary/journal.rs`、`ngram_store.rs`、`reset.rs`）

- `open_journal` 缓存的连接和整句联想写线程的连接经 `prefer_write_ahead_log` 切到 WAL，只有真的进了 WAL 才把 `synchronous` 降到 `NORMAL` 并设 `journal_size_limit` 为 4 MiB；切换失败（别的进程占着锁、没有共享内存）时保持原模式和 `FULL`，因为回滚日志配 `NORMAL` 断电可能损坏。
- iOS 不切：键盘扩展与 App 在 App Group 里共用日志，WAL 连接整个生命周期对 `-shm` 持锁，挂起时持锁会被按 `0xdead10cc` 杀掉。
- 主词库工作副本（`msime-pinyin.db`、`msime-english.db`）保留回滚日志：重置学习数据把它们挪作备份时不带旁路文件，旧 `-wal` 会套到新词库上。
- 重置学习数据挪走日志之前先 `wal_checkpoint(TRUNCATE)`。损坏日志隔离本来就连同 `-wal`/`-shm` 一起改名（见 [用户词库日志损坏时挪开](2026-10-13-corrupt-user-journal-quarantine.md)），macOS/Linux 迁移数据目录先停输入法再连同旁路文件复制，都不用改。

### n-gram 模型跨会话保温（`ngram_store.rs`）

打开日志时，正要打开的这条路径上的空闲 store 只要日志和 `-wal` 的（修改时间, 大小）与模型上次和磁盘对齐时一样就复用。对齐点是读完日志和自己写完一批之后，`release_all`（重置、换代次之前）清掉这个戳；空 `-wal` 与没有 `-wal` 同等看待。戳对不上（别的进程学了新词、重置、本进程别的连接写过）就丢掉重建，所以别处的修改照旧在下一个会话读到。其他路径的空闲 store 仍在下次打开日志时回收。`release_idle_personal_models` 接进 `Session::reset_cache` 和 `msime_client_release_process_cache`，内存告警时交还模型和连接。

### 插件表进程缓存（`host-api/src/plugin_tables.rs`）

指令表、短语表、辅助码表包、联系人名单各在进程里留最近一份，键是读它时的来源：插件目录加上会话本来就盖的戳（清单戳、包目录戳、`mentions.json` 戳，仍沿用 [带 inode 的 FileStamp](2026-10-11-plugin-table-stamp-inode.md)），联系人读音另加主词库路径和它的戳（引擎新增 `main_dictionary_path`）。读不出名单、辅助码包载入失败、要查读音的姓名一个也没查到时不记下，下一个会话重试。`release_cached_tables` 在两处清缓存入口清空四个槽位，开着的会话各自持有读进 Engine 的那份，不受影响。

### 个人词库同步（`client-services/src/dictionary/personal.rs`、`engine/src/host/dictionary.rs`）

`PersonalDictionaryStore::update` 状态没变时不写文件。状态新增可选的 `snapshotSource`，记下读这一页时用户日志的来源戳（`dictionary_entries_source`：日志和 `-wal` 的 inode、长度、修改时间、状态改变时间）。本批没有应用请求、宿主没有未确认的页请求、页参数和戳都与已存的一页相同、上次读页没出错时直接沿用这一页。日志最后改动离现在不到 `REVISION_SETTLE`（两秒）时不给戳，调用方照常重查，避开同一时间粒度里长度不变的写入。旧的 `synchronize` 入口不给戳，行为不变。与 [忙碌重试](2026-10-17-personal-dictionary-sync-busy-retry.md) 的维护租约语义不冲突：取戳时持有独占租约。

### 偏好与快照版本（`client-core/src/preferences.rs`、`host-api/src/lib.rs`、`client-core/src/cloud/snapshot_queue.rs`、`engine/src/user_dictionary/state.rs`）

- `PreferencesStore::load_reusing`：照旧加锁读出文件字节，与上一次逐字节相同就沿用上一份快照，跳过两遍解析和校验。文件不存在时每次照常取默认快照。`msime_client_load_preferences` 按目录留最近一份，C ABI 不变。
- `HostSession::update` 认出与上一份相同的快照（且上一次 update 没有中途失败，`requested_settled`）后走 `update_unchanged`：不校验、不比较、不调只有偏好会改的 setter；仍按偏好重设繁简投影、用 `SessionSound::restamp` 给效果包清单盖戳、调 `apply_pending`，回复与完整更新字段相同。
- `cached_dictionary_state_revision` 按日志主文件、`-wal`、`-journal` 的 stat 记下上次修订号，stat 一样就直接返回；最后改动早于两秒前才记缓存（git racy-clean 的做法）。只有空闲预览用它（`snapshot_queue_state`、`process` 认领前、`msime_client_snapshot_version`）；`process` 认领到请求后、prepare 前后、激活后都重新精确计算，缓存过时最多表现为一次可恢复的冲突。
- 快照队列 `update_if_changed`：发布的本机版本没变时不重写 `state.json`。

## Alternatives considered

- **统计去掉 fsync、保留逐次写入**：改动最小，能省下最贵的那一步。但 rename 前不 fsync 在部分文件系统上断电后会留下空文件或半截文件，整份统计没了；而且整读整写和解析的 CPU 仍按每次上屏付。没有采用。
- **统计按条数攒批（如选词位置的 32 次一写）**：实现简单、内存有界。没有采用：维护者定的跨平台约定是「失焦或每 30 秒」，按条数写在慢速打字时会把计数无限期留在内存里，被杀时丢失的量不可控；而时间门槛加失焦写出把上限钉在 30 秒。
- **把一批上屏合成一次大提交写入**：只算一次活跃时长，代码更少。但批内上屏之间的间隔会被抹掉，`daily_active_ms`、最长连续输入都会变，所以按原时刻逐条重放。
- **主词库工作副本也进 WAL、或给它缓存一条可写连接并降 `synchronous`**：调频时主词库的那次提交同样要 fsync，缓存连接还能省掉每次打开。本线没有做：重置学习数据挪走工作副本时不带旁路文件，WAL 会把旧 `-wal` 套到新词库上；缓存可写连接要和换代次、替换词库的关闭时机重新对齐，风险超出这一轮的范围。日志进 WAL 已拿到大部分收益，主词库这一步留给后续单独评估。
- **n-gram store 无条件常驻**：最省事，内存代价与会话期间相同。但别的进程或别的连接改过日志而没有通知本进程时，新会话就读不到改动；用（修改时间, 大小）戳把关，代价只是几次 stat。拼写纠错 profile 没有一起改：它的表很小、重读代价低，常驻会吃掉九键过滤刷新那条分配计数用例原有的余量。
- **偏好按文件戳（mtime、inode）判断、或新增 `msime_client_load_preferences_if_changed` 只 stat 不读**：比逐字节比较更省 I/O。没有采用：mtime 粒度粗、inode 可能被重用、文件可能被原地改写，戳一样内容不一样时会读到旧快照；新增 C ABI 还要四端同时接。偏好文件不大，读字节的成本远小于两遍解析加校验。
- **联系人读音在保存时算好写进 `mentions.json`**：聚焦时完全不用查词库。但改了落盘格式，主词库更新后读音也不会跟着变，没有采用。
- **快照版本的缓存也用于冲突判定**：能把 `process` 认领后的那次全表扫描也省掉。但缓存过时就可能把旧数据当成新数据应用，正确性优先，只给空闲预览用。
- **根治：Android 换输入框时保留会话、只发 focus(false/true)**：n-gram 重读、插件表重读、个人词库同步的大部分触发都来自每次聚焦销毁重建会话。这是 Android 生命周期的改动，影响面大，不在共享 Rust 这一线，本线只让「重建」本身变便宜。

## 旧笔记审计

按打字统计、WAL/journal、n-gram、插件表、`sync.json`、快照队列、偏好轮询、低端机耗电检索活跃笔记：

- [Android 键盘只在窗口显示时每秒重读偏好](2026-10-27-android-preference-poll-only-while-visible.md)：同一批反馈的 Android 侧，本篇处理可见期间那一轮在 Rust 里的开销，部分重叠，互链。
- [用户词库日志损坏时挪开](2026-10-13-corrupt-user-journal-quarantine.md)：隔离已连同 `-wal`/`-shm` 一起改名，WAL 下仍成立，无需修改。
- [插件表的文件戳带上 inode](2026-10-11-plugin-table-stamp-inode.md)：本篇的进程缓存沿用它的 `FileStamp`，该篇事实不变。
- [个人词库同步忙碌重试](2026-10-17-personal-dictionary-sync-busy-retry.md)、[iOS 词库维护移出主线程](2026-10-22-ios-idle-maintenance-off-main.md)：前者持有租约语义，后者是 iOS 宿主侧的空闲轮询，本篇让同一轮在 Rust 里变便宜，部分重叠。
- [输入统计锁绑定目录句柄](2026-10-09-typing-statistics-lock-directory.md)、[关闭上传时串行化云端操作](2026-10-17-typing-statistics-upload-race.md)：攒批写出仍走同一把目录句柄锁，上传前先写出批次，两篇不受影响。打字统计 UI 侧的模型与状态笔记只涉及 React 展示，无关。

## Consequences

- 空闲聚焦、空闲轮询、偏好原样再交、同一个输入框里的连续打字，不再整份读写或 fsync 统计文件、`sync.json`、`state.json`，也不再全表扫日志、重读 n-gram 表和插件表。代价是进程里多留几份数据（n-gram 模型几 MB、联系人名单约 100 KB、上一份偏好快照），内存告警时经清缓存入口放掉。
- 维护者认可的持久性取舍：进程被系统杀掉时最多丢 30 秒的打字统计计数（移动端失焦和销毁已在后台线程写出，实际窗口更小）；用户日志在 WAL + `NORMAL` 下断电最多丢最后几次学习，但不会损坏；把日志 ATTACH 到词库连接上的事务（删词、个人词库编辑、五笔造词）不再由超级日志跨文件绑定，只在每个文件内原子，按 SQLite 的提交顺序崩溃只会留下「日志已记下、词库工作副本回滚」，下次回放补齐，不会出现词库已删、日志没有墓碑。
- 设置页读统计文件时，正在打字的计数最多晚 30 秒出现；设置页的任何统计操作会先把批次写出。
- 新增 `{"operation":"flush"}` 统计操作和 `snapshotSource` 字段（可选，旧文件照读）。旧版本的库不认识 flush 会回错误，宿主忽略即可。
- WAL 让测试进程同时开着的文件从约 100 涨到近 400，超过 macOS launchd 默认的软上限 256；`scripts/verify-local.sh` 在跑 Rust 测试前把软上限抬到 4096。CI 的 Rust 测试在 Linux 容器里跑，不受这个上限影响；在 macOS 上直接 `cargo test` 需要先 `ulimit -n 4096`。
- 主词库那一次调频提交仍是回滚日志加 `FULL`，选非首位候选仍要为它 fsync 一次；Android 每次聚焦仍销毁重建会话、打开并预热词库连接。这两处是下一步的候选。

## Verification

各项改动都带了单元测试：统计的攒批不逐次写文件、flush 写出、30 秒门槛、时钟回拨、攒批与逐条写入得到相同文档、与另一进程的清空和关闭合并、清空代号丢弃旧批次；日志以 WAL/`NORMAL` 提交、检查点后主文件单独拷走仍完整；空 `-wal` 出现又消失后 store 仍复用；插件表缓存命中与主词库或名单变化后重查；空闲同步既不读页也不重写 `sync.json`、各种失效条件下重查、来源戳落定规则；同一份偏好快照再交时回复与第一次相同、效果包跟上、同修订号换内容仍拒绝；偏好文件字节不变时沿用、保存或改坏后重读。多语言「选词不写日志」的断言改为连同 `-wal` 一起比较，否则 WAL 下写入只进 `-wal` 会让断言形同虚设。

在编译机上跑 `cargo test --no-fail-fast -p msime-host-api -p msime-client-services -p msime-client-core -p msime-engine`：client-core 581 项、client-services 1406 项、engine 2699 项全部通过；host-api 1011 项通过、2 项失败（`listing_reports_every_pack_and_checks_the_state_root` 没列 `pronunciations` 包、`a_command_gets_the_local_time_to_the_minute`），两项在本线的基点上单独跑同样失败，与本线无关。`cargo clippy --keep-going --all-targets -- -D warnings` 只报 client-core 和 client-services 测试里两处基点上已有的 `field_reassign_with_default`，本线改到的代码没有告警。第一次全量跑时 engine 的分配预算用例（临时英文候选选词，预算 67）报 68，逐个提交单独跑定位到给 `journal_size_limit` 拼 PRAGMA 的 `format!`；改成字面量后通过。日志首次打开常在选词提交路径上，这条路径上的分配预算要留意。

缺口：没有在任何真机上测过耗电、发热或卡顿。验证办法是在低端 Android 机上对比改动前后 `:ime` 进程的 CPU 时间增量（`adb shell top -p <pid>`、`dumpsys batterystats`）、`/proc/<pid>/io` 的写入量，以及聚焦到可打字的 trace。Windows 7 面板的 flush 改动只在 C++ 源码层面跟随，没有在 Windows 7 上运行；iOS、HarmonyOS 宿主没有改动，也没有装机复测。
