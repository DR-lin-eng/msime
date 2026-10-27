package app.msime.android;

import app.msime.android.core.InputViewValuePolicy;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 触屏横向完整候选条；保留全局身份，接近尾部才继续建按钮。 */
final class ImeCandidateStrip {
    private final MSIMEInputService s;
    private JSONObject snapshot;
    private JSONObject sourceView;
    private boolean enabled;
    private LinearLayout list;
    private LinearLayout observedList;
    private HorizontalScrollView scroll;
    private JSONArray entries;
    private int built;
    private int visible;
    private boolean appendPosted;
    private long renderedSession = Long.MIN_VALUE;
    private long renderedGeneration = Long.MIN_VALUE;
    /**
     * 候选条按钮按位置复用。render 前候选行照旧 `removeAllViews`，这里只把同一批按钮按顺序挂回去、换上这一代的内容和点击目标，不再每次整排新建：每键几十个 Button 连同各自的监听、底图和文字测量，在低端机上是一两帧的主线程时间，旧按钮又全成了垃圾。换了键盘视图就整池丢掉。
     */
    private final java.util.ArrayList<KeyboardPressButton> pool = new java.util.ArrayList<>();

    ImeCandidateStrip(MSIMEInputService service) { s = service; }

    /** 先取完整候选再画首帧，防止补齐尾部时换代使按钮身份过期。 */
    JSONObject prepare(boolean enabled) {
        this.enabled = enabled;
        if (!enabled || s.view == null || s.session == 0
                || s.view.optJSONArray("candidates") == null
                || s.view.optJSONArray("candidates").length() == 0) {
            snapshot = null;
            entries = null;
            renderedSession = Long.MIN_VALUE;
            renderedGeneration = Long.MIN_VALUE;
            return null;
        }
        if (sourceView == s.view && MSIMEInputService.sameCandidateVersion(snapshot, s.view)) return snapshot;
        try {
            snapshot = s.currentCandidateSnapshot();
            sourceView = s.view;
            return snapshot;
        } catch (JSONException | LinkageError error) {
            snapshot = null;
            entries = null;
            s.fail(error);
            return null;
        }
    }

    void render(JSONObject current, LinearLayout target, HorizontalScrollView viewport) {
        long nextSession = CandidateGlossPolicy.strictOr(current.opt("session"), Long.MIN_VALUE);
        long nextGeneration = CandidateGlossPolicy.strictOr(current.opt("generation"), -1);
        boolean same = nextSession == renderedSession && nextGeneration == renderedGeneration;
        renderedSession = nextSession;
        renderedGeneration = nextGeneration;
        int previous = built;
        list = target;
        scroll = viewport;
        entries = current.optJSONArray("candidates");
        built = 0;
        visible = 0;
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> appendIfNeeded());
        // 每个键盘视图只装一次布局监听；同代重画仍使用最新的列表和快照。
        if (observedList != list) {
            observedList = list;
            pool.clear();
            list.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> postAppendCheck());
        }
        int total = entries == null ? 0 : entries.length();
        // 换了一代只先建第一批；之前滚远时多出来的按钮留两批备用，其余放掉，不长期占着内存。
        if (!same) {
            int retained = 2 * CandidateGridBatchPolicy.BATCH;
            if (pool.size() > retained) pool.subList(retained, pool.size()).clear();
        }
        add(CandidateGridBatchPolicy.initialCount(total, previous, same), false);
        if (!same) scroll.scrollTo(0, 0);
    }

    private void postAppendCheck() {
        if (appendPosted || scroll == null) return;
        appendPosted = true;
        scroll.post(() -> { appendPosted = false; appendIfNeeded(); });
    }

    private void appendIfNeeded() {
        if (entries == null || list == null || scroll == null || !scroll.isShown()
                || !list.isAttachedToWindow() || !enabled || s.hardwareKeyboardMode
                || !MSIMEInputService.sameCandidateVersion(snapshot, s.view)) return;
        if (!CandidateGridBatchPolicy.shouldAppend(built, entries.length(), scroll.getScrollX(),
                scroll.getWidth(), list.getWidth())) return;
        add(CandidateGridBatchPolicy.nextCount(built, entries.length()), true);
    }

    private void add(int target, boolean style) {
        while (built < target) {
            JSONObject candidate = entries.optJSONObject(built++);
            if (candidate == null || !AiPendingCandidatePolicy.isVisible(
                    InputViewValuePolicy.integer(candidate, "source", -1))) continue;
            if (visible == pool.size()) pool.add(s.imeCandidates.newStripCandidateButton());
            KeyboardPressButton button = pool.get(visible);
            s.imeCandidates.bindStripCandidateButton(button, snapshot, candidate, visible++);
            // render 前候选行已经 removeAllViews，池里的按钮这时都没有父视图；万一还挂在别处，先摘下来再挂。
            if (button.getParent() instanceof android.view.ViewGroup parent) parent.removeView(button);
            list.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            if (style) s.imeStyler.applySkinToView(button, true, s.skin);
        }
    }
}
