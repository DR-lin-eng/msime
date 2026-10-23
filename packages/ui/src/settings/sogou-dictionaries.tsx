import { useEffect, useRef, useState } from "react";
import type {
  DictionaryClient,
  SogouAction,
  SogouCatalog,
  SogouStatus,
} from "../dictionary/dictionary-contracts";
import { GroupList } from "../core/platform-controls";
import { SwitchRow } from "./switch-row";
import { SelectRow } from "./select-row";
import { TextInputRow } from "./text-input-row";
import { ActionRow } from "./action-row";
import { SettingsGroupNote } from "./settings-group-note";
import { ErrorAlert } from "../core/error-alert";

/** 初始化与后期设置使用同一个订阅视图；取消挂载后丢弃迟到响应。 */
export function SogouDictionaries({
  request,
  compact = false,
}: {
  request: NonNullable<DictionaryClient["sogou"]>;
  compact?: boolean;
}) {
  const [status, setStatus] = useState<SogouStatus | null>(null);
  const [catalog, setCatalog] = useState<SogouCatalog | null>(null);
  const [group, setGroup] = useState(97);
  const [page, setPage] = useState(1);
  const [id, setId] = useState("");
  const [query, setQuery] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const alive = useRef(false);
  const generation = useRef(0);
  const acting = useRef(false);
  const load = async (action: SogouAction) => {
    if (acting.current) return;
    acting.current = true;
    const current = ++generation.current;
    setBusy(true);
    try {
      const result = await request(action);
      if (!alive.current || current !== generation.current) return;
      if ("settings" in result) setStatus(result);
      else {
        setCatalog(result);
        setPage(result.page);
        setGroup(result.group);
      }
      setError("");
    } catch {
      if (alive.current && current === generation.current)
        setError("搜狗词库暂不可用，请重试；已安装的词库保留。");
    } finally {
      if (alive.current && current === generation.current) {
        acting.current = false;
        setBusy(false);
      }
    }
  };
  useEffect(() => {
    alive.current = true;
    acting.current = false;
    setBusy(false);
    void load({ command: "status" });
    const timer = setInterval(async () => {
      if (acting.current) return;
      const current = generation.current;
      try {
        const result = await request({ command: "status" });
        if (alive.current && current === generation.current && "settings" in result)
          setStatus(result);
      } catch {
        /* 状态轮询失败时保留上次结果，显式操作仍会显示错误。 */
      }
    }, 2000);
    return () => {
      alive.current = false;
      generation.current++;
      clearInterval(timer);
    };
  }, [request]);
  const configure = (patch: Partial<SogouStatus["settings"]>) => {
    if (!status) return;
    void load({
      command: "configure",
      groups: status.settings.groups,
      dictionaries: status.settings.dictionaries,
      automatic: status.settings.automatic,
      ...patch,
    });
  };
  const running = status?.settings.phase === "checking";
  return (
    <GroupList title={compact ? "选择兴趣词库（可跳过）" : "搜狗词库与订阅"}>
      <SettingsGroupNote>
        选择后从搜狗官网下载，仅供本机使用。每个分组跟踪最新上传的 10
        个词库，已安装的继续更新；最多选择 8 个分组，共享 32 个集合、每库 5
        万词的上限。稍后可在词库设置修改。
      </SettingsGroupNote>
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {status?.groups.map((item) => (
        <SwitchRow
          key={item.id}
          title={item.name}
          checked={status.settings.groups.includes(item.id)}
          disabled={busy}
          onChange={(enabled) =>
            configure({
              groups: enabled
                ? [...status.settings.groups, item.id]
                : status.settings.groups.filter((id) => id !== item.id),
            })
          }
        />
      ))}
      <SwitchRow
        title="自动更新已选词库"
        checked={status?.settings.automatic ?? true}
        disabled={!status || busy}
        onChange={(automatic) => configure({ automatic })}
      />
      <ActionRow
        title="检查订阅更新"
        label={running ? "下载中…" : "立即更新"}
        disabled={!status || busy || running}
        action={() => load({ command: "check" })}
      />
      {status?.settings.error && (
        <ErrorAlert>
          {status.settings.limited
            ? "集合数量达到上限，请移除不需要的词库后重试。"
            : "部分词库更新失败，继续保留原词库，请稍后重试。"}
        </ErrorAlert>
      )}
      {!compact && (
        <>
          <SelectRow
            title="浏览分组"
            value={group}
            disabled={busy}
            onChange={(event) => {
              const next = Number(event.target.value);
              setGroup(next);
              setPage(1);
              setCatalog(null);
            }}
          >
            {status?.groups.map((item) => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
          </SelectRow>
          <ActionRow
            title="读取官方词库列表"
            label="浏览"
            disabled={busy}
            action={() => load({ command: "catalog", group, page: 1 })}
          />
          <TextInputRow title="筛选当前页" label="筛选搜狗词库" value={query} onChange={setQuery} />
          {catalog?.entries
            .filter((item) => item.name.includes(query) || String(item.id).includes(query))
            .map((item) => (
              <ActionRow
                key={item.id}
                title={item.name}
                description={`编号 ${item.id}`}
                label={
                  status?.installed.some((i) => i.id === item.id) ? "已下载，检查更新" : "下载"
                }
                disabled={busy || running}
                action={() => load({ command: "install", id: item.id })}
              />
            ))}
          {catalog && (
            <>
              <ActionRow
                title={`第 ${page} 页`}
                label="上一页"
                disabled={busy || page <= 1}
                action={() => load({ command: "catalog", group, page: page - 1 })}
              />
              <ActionRow
                title="更多词库"
                label="下一页"
                disabled={busy || !catalog.has_more}
                action={() => load({ command: "catalog", group, page: page + 1 })}
              />
            </>
          )}
          <TextInputRow
            title="精确下载：搜狗词库编号"
            label="搜狗词库编号"
            value={id}
            inputMode="numeric"
            onChange={setId}
          />
          <ActionRow
            title="按编号下载并跟踪更新"
            label="下载"
            disabled={busy || running || !/^[1-9][0-9]{0,8}$/.test(id)}
            action={() => load({ command: "install", id: Number(id) })}
          />
          {status?.installed.map((item) => (
            <div key={item.id}>
              <SwitchRow
                title={`${item.name} · ${item.entries} 词`}
                checked={item.enabled}
                disabled={busy}
                onChange={(enabled) => void load({ command: "set_enabled", id: item.id, enabled })}
              />
              <ActionRow
                title={`移除 ${item.name}`}
                description="取消跟踪并移除本集合；已有个人词条保留。"
                label="移除"
                disabled={busy}
                action={() => load({ command: "remove", id: item.id })}
              />
            </div>
          ))}
          <SettingsGroupNote>
            下载后由输入法在空闲时分批激活；iOS
            键盘需开启完全访问。移动系统暂停设置应用期间不保证更新。
          </SettingsGroupNote>
        </>
      )}
    </GroupList>
  );
}
