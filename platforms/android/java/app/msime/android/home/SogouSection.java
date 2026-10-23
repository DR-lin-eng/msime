package app.msime.android.home;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import app.msime.android.LayoutPolicy;
import app.msime.android.NativeClient;
import app.msime.android.ThreadPolicy;
import app.msime.android.policy.HostOptionsPolicy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** 初始化和原生词库页共用；宿主只显示状态，下载与订阅规则在 Rust。 */
final class SogouSection implements DefaultLifecycleObserver {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
        ThreadPolicy.namedDaemonFactory("msime-sogou-settings"));
    private final Context context;
    private final LifecycleOwner owner;
    private final LinearLayout column;
    private final boolean compact;
    private JSONObject status;
    private JSONObject catalog;
    private boolean active = true;
    private boolean busy;
    private int generation;
    private int group = 97;
    private int page = 1;
    private String query = "";
    private String error = "";

    SogouSection(LifecycleOwner owner, Context context, LinearLayout parent, boolean compact) {
        this.owner = owner; this.context = context; this.compact = compact;
        column = LayoutPolicy.column(context);
        parent.addView(column, LayoutPolicy.matchWidthWrapParams());
        owner.getLifecycle().addObserver(this);
        load(action("status"));
    }
    void attach(LinearLayout parent) {
        if (column.getParent() instanceof android.view.ViewGroup previous) previous.removeView(column);
        parent.addView(column, LayoutPolicy.matchWidthWrapParams());
    }
    @Override public void onDestroy(LifecycleOwner owner) { dispose(); }
    void dispose() { active = false; generation++; owner.getLifecycle().removeObserver(this); }

    static JSONObject action(String command, Object... pairs) {
        try {
            JSONObject value = new JSONObject().put("operation", "sogou").put("command", command);
            for (int i = 0; i < pairs.length; i += 2) value.put((String)pairs[i], pairs[i + 1]);
            return value;
        } catch (org.json.JSONException e) { throw new IllegalArgumentException(e); }
    }
    private static JSONObject request(Context context, JSONObject action) {
        try {
            String options = HostOptionsPolicy.readRuntimeOptions(context.getFilesDir());
            if (options.isEmpty()) return null;
            JSONObject reply = new JSONObject(NativeClient.personalDictionaryRequest(new JSONObject()
                .put("options", new JSONObject(options)).put("action", action).toString()));
            return reply.optBoolean("ok") ? reply.optJSONObject("value") : null;
        } catch (Exception | LinkageError e) { return null; }
    }
    private void load(JSONObject action) {
        if (!active || busy) return;
        busy = true; int current = ++generation; render();
        Context application = context.getApplicationContext();
        WORKER.execute(() -> {
            JSONObject reply = request(application, action);
            app.msime.android.MainThreadPolicy.mainHandler().post(() -> {
                if (!active || current != generation) return;
                busy = false;
                if (reply == null) error = "搜狗词库暂不可用；请等待初始化完成后重试。已安装词库保留。";
                else {
                    error = "";
                    if (reply.has("settings")) status = reply; else catalog = reply;
                }
                render();
                JSONObject settings = status == null ? null : status.optJSONObject("settings");
                if (settings != null && ("checking".equals(settings.optString("phase")) || status.optInt("pending_count") > 0)) {
                    column.postDelayed(() -> { if (active && current == generation) load(action("status")); }, 2000);
                }
            });
        });
    }
    private void configure(JSONArray groups, boolean automatic) {
        JSONObject settings = status.optJSONObject("settings");
        load(action("configure", "groups", groups, "dictionaries", settings.optJSONArray("dictionaries"), "automatic", automatic));
    }
    private static boolean contains(JSONArray values, int id) {
        if (values != null) for (int i = 0; i < values.length(); i++) if (values.optInt(i) == id) return true;
        return false;
    }
    private void render() {
        column.removeAllViews();
        GroupCard card = GroupCard.add(column, compact ? "选择兴趣词库（可跳过）" : "搜狗词库与订阅");
        card.note("选择后从搜狗官网下载，仅供本机使用。分组跟踪最新上传的 10 个词库，已安装的继续更新；最多 8 个分组、32 个集合，每库 5 万词。稍后可在词库设置修改。");
        if (!error.isEmpty()) card.note(error);
        JSONObject settings = status == null ? null : status.optJSONObject("settings");
        JSONArray groups = status == null ? null : status.optJSONArray("groups");
        if (settings != null && groups != null) {
            JSONArray selected = settings.optJSONArray("groups");
            boolean automatic = settings.optBoolean("automatic", true);
            for (int i = 0; i < groups.length(); i++) {
                JSONObject item = groups.optJSONObject(i); if (item == null) continue;
                int id = item.optInt("id");
                card.toggle(item.optString("name"), "订阅分组", contains(selected, id), enabled -> {
                    JSONArray next = new JSONArray();
                    for (int j = 0; j < selected.length(); j++) if (selected.optInt(j) != id) next.put(selected.optInt(j));
                    if (enabled) next.put(id);
                    configure(next, automatic);
                }).setEnabled(!busy);
            }
            card.toggle("自动更新已选词库", "每天检查，设置应用运行时下载", automatic,
                enabled -> configure(selected, enabled)).setEnabled(!busy);
            if (!settings.isNull("error")) card.note(settings.optBoolean("limited")
                ? "集合数量已达上限，请移除不需要的词库后重试。" : "部分词库更新失败，原词库保留，请稍后重试。");
            if (status.optInt("pending_count") > 0) card.note("已下载，等待键盘空闲时分批激活。");
        }
        card.nav("刷新状态", null, busy ? "读取中…" : null, () -> load(action("status"))).setEnabled(!busy);
        if (compact) return;
        card.nav("检查订阅更新", null, null, () -> load(action("check"))).setEnabled(!busy);
        if (groups != null) card.nav("浏览官方分组", "按名称选择具体词库", null, () -> {
            PopupMenu menu = new PopupMenu(context, card.view());
            for (int i = 0; i < groups.length(); i++) {
                JSONObject item = groups.optJSONObject(i); if (item == null) continue;
                menu.getMenu().add(0, item.optInt("id"), i, item.optString("name"));
            }
            menu.setOnMenuItemClickListener(item -> { group = item.getItemId(); page = 1; load(action("catalog", "group", group, "page", page)); return true; });
            menu.show();
        }).setEnabled(!busy);
        card.nav("按编号精确下载", "输入搜狗官网词库详情页的编号", null, () -> {
            InputDialog dialog = new InputDialog(context, "下载搜狗词库", "下载并跟踪这个词库的更新");
            dialog.addField("词库编号", "", android.text.InputType.TYPE_CLASS_NUMBER);
            dialog.setValidator(values -> values.get(0).matches("[1-9][0-9]{0,8}"));
            dialog.setPrimary("下载", values -> load(action("install", "id", Integer.parseInt(values.get(0))))).show();
        }).setEnabled(!busy);
        if (catalog != null) {
            card.nav("筛选当前页", null, query, () -> {
                InputDialog dialog = new InputDialog(context, "筛选词库", "按名称或编号筛选当前页");
                dialog.addField("名称或编号", query, 0);
                dialog.setValidator(values -> true).setPrimary("筛选", values -> { query = values.get(0); render(); }).show();
            });
            JSONArray entries = catalog.optJSONArray("entries");
            if (entries != null) for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.optJSONObject(i); if (item == null) continue;
                int id = item.optInt("id"); String name = item.optString("name");
                if (!name.contains(query) && !String.valueOf(id).contains(query)) continue;
                card.nav(name, "编号 " + id, "下载", () -> load(action("install", "id", id))).setEnabled(!busy);
            }
            if (page > 1) card.nav("上一页", null, null, () -> { page--; load(action("catalog", "group", group, "page", page)); }).setEnabled(!busy);
            if (catalog.optBoolean("has_more")) card.nav("下一页", null, null, () -> { page++; load(action("catalog", "group", group, "page", page)); }).setEnabled(!busy);
        }
        JSONArray installed = status == null ? null : status.optJSONArray("installed");
        if (installed != null) for (int i = 0; i < installed.length(); i++) {
            JSONObject item = installed.optJSONObject(i); if (item == null) continue;
            int id = item.optInt("id"); String name = item.optString("name");
            card.toggle(name, item.optInt("entries") + " 词", item.optBoolean("enabled"),
                enabled -> load(action("set_enabled", "id", id, "enabled", enabled))).setEnabled(!busy);
            card.nav("移除 " + name, "停止跟踪并移除本集合，已有个人词条保留", null,
                () -> MessageDialog.confirm(context, "移除词库？", name, "移除", () -> load(action("remove", "id", id)))).setEnabled(!busy);
        }
    }
}
