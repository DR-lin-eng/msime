package app.msime.android.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Comparator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/** 对安装产品测量工具栏布局、触下反馈时序和真实图标按下像素，输入内容均为合成值。 */
public final class ToolbarEdgesDeviceSmoke extends Instrumentation {
    private final StringBuilder observations = new StringBuilder();
    private boolean requireImmediate;
    private String evidenceLabel;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        requireImmediate = arguments != null && Boolean.parseBoolean(arguments.getString("require-immediate", "false"));
        evidenceLabel = arguments == null ? "observed" : arguments.getString("evidence-label", "observed");
        if (!evidenceLabel.matches("[A-Za-z0-9_-]{1,40}"))
            throw new IllegalArgumentException("Invalid synthetic evidence label");
        start();
    }

    @Override public void onStart() {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            Object service = null;
            try {
                ClassLoader loader = getTargetContext().getClassLoader();
                Class<?> type = loader.loadClass("app.msime.android.MSIMEInputService");
                service = type.getConstructor().newInstance();
                Method attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                attach.setAccessible(true);
                attach.invoke(service, getTargetContext());
                type.getMethod("onCreate").invoke(service);
                type.getMethod("onCreateInputView").invoke(service);
                Button undo = (Button) field(type, "undoShortcutButton").get(service);
                Button redo = (Button) field(type, "redoShortcutButton").get(service);
                check(!undo.isEnabled() && !redo.isEnabled(), "history controls enabled without editor");
                Object historyToolbar = field(type, "imeToolbar").get(service);
                method(historyToolbar.getClass(), "performHistory", boolean.class).invoke(historyToolbar, false);
                method(historyToolbar.getClass(), "performHistory", boolean.class).invoke(historyToolbar, true);
                Button more = (Button) field(type, "moreButton").get(service);
                Button dismiss = (Button) field(type, "dismissShortcutButton").get(service);
                HorizontalScrollView scroll = (HorizontalScrollView) field(type, "shortcutScroll").get(service);
                LinearLayout bar = (LinearLayout) field(type, "shortcutBar").get(service);
                check(!inside(more, scroll) && !inside(dismiss, scroll), "toolbar edges still scroll with tools");
                ViewGroup row = (ViewGroup) scroll.getParent();
                row.setVisibility(View.VISIBLE);
                String[] fields = {"emojiShortcutButton", "phraseShortcutButton", "clipboardShortcutButton",
                    "skinButton", "schemeButton", "floatingShortcutButton", "textEditShortcutButton"};
                Button[] buttons = new Button[fields.length];
                for (int index = 0; index < fields.length; index++)
                    buttons[index] = (Button) field(type, fields[index]).get(service);
                Object calculator = field(type, "imeCalculator").get(service);
                Button result = (Button) method(calculator.getClass(), "chip").invoke(calculator);
                float density = getTargetContext().getResources().getDisplayMetrics().density;
                // 先超宽再收窄，覆盖旧滚动偏移在布局变化后被重新钳制的路径。
                for (int widthDp : new int[] {600, 320, 240, 600}) {
                    for (boolean showResult : new boolean[] {false, true}) {
                        for (Button button : buttons) button.setVisibility(View.VISIBLE);
                        result.setText("= 12345678901234");
                        result.setVisibility(showResult ? View.VISIBLE : View.GONE);
                        layout(row, Math.round(widthDp * density), Math.round(64 * density));
                        scroll.scrollTo(0, 0);
                        Rect left = bounds(row, more), right = bounds(row, dismiss);
                        check(Math.abs(left.width() - 44 * density) <= 1, "brand touch width changed");
                        check(left.left >= 0 && right.right <= row.getWidth(), "toolbar edge clipped");
                        check(Math.abs(right.width() - 44 * density) <= 1, "dismiss touch width changed");
                        for (Button button : buttons)
                            check(button.getWidth() >= Math.round(40 * density), "tool shrank below minimum width");
                        scroll.scrollTo(Math.max(0, bar.getWidth() - scroll.getWidth()), 0);
                        if (widthDp <= 320) check(scroll.getScrollX() > 0, "narrow toolbar cannot scroll");
                        check(left.equals(bounds(row, more)) && right.equals(bounds(row, dismiss)),
                            "scroll moved fixed toolbar edges");
                        Rect last = bounds(row, buttons[buttons.length - 1]);
                        Rect viewport = bounds(row, scroll);
                        check(last.left >= viewport.left && last.right <= viewport.right,
                            "last tool is clipped at scroll end: " + widthDp + "/" + showResult
                                + " last=" + last.toShortString() + " viewport=" + viewport.toShortString()
                                + " scroll=" + scroll.getScrollX() + " content=" + bar.getWidth());
                    }
                }
                checkClipboardHeader(type, service, loader, density);
                // 用真实排序入口移动同一组按钮，检查算式仍是第一项、动作对象未被重建。
                Class<?> snapshot = loader.loadClass("app.msime.android.AndroidLocalSettings$Snapshot");
                Constructor<?> constructor = snapshot.getDeclaredConstructor(Map.class);
                constructor.setAccessible(true);
                field(type, "localSettings").set(service, constructor.newInstance(Map.of(
                    "platform.android.toolbar_order", "text_edit,floating,scheme,skin,clipboard,phrase,emoji")));
                Object toolbar = field(type, "imeToolbar").get(service);
                method(toolbar.getClass(), "applyShortcutOrder").invoke(toolbar);
                check(bar.getChildAt(0) == result, "calculator lost first position");
                for (int index = 0; index < buttons.length; index++)
                    check(bar.getChildAt(index + 1) == buttons[buttons.length - index - 1],
                        "custom order changed tool identity");
                for (int index = 1; index < buttons.length; index++) buttons[index].setVisibility(View.GONE);
                result.setVisibility(View.GONE);
                layout(row, Math.round(320 * density), Math.round(64 * density));
                check(scroll.getScrollX() == 0, "hidden tools left stale scroll offset");
                check(buttons[0].getWidth() >= scroll.getWidth() - 2 * Math.round(density),
                    "single visible tool did not fill available width");
            } catch (Exception | AssertionError error) { failure[0] = error; }
            finally {
                if (service != null) {
                    try { service.getClass().getMethod("onDestroy").invoke(service); }
                    catch (ReflectiveOperationException ignored) { /* 清理隔离服务。 */ }
                    for (Field member : service.getClass().getDeclaredFields()) {
                        if (!ExecutorService.class.isAssignableFrom(member.getType())) continue;
                        try { member.setAccessible(true); ((ExecutorService) member.get(service)).shutdownNow(); }
                        catch (ReflectiveOperationException ignored) { /* 清理测试工作线程。 */ }
                    }
                }
            }
        });
        if (failure[0] == null) {
            try { checkContinuity(); }
            catch (Exception | AssertionError error) { failure[0] = rootCause(error); }
        }
        Bundle result = new Bundle();
        Throwable error = failure[0] == null ? null : rootCause(failure[0]);
        if (error != null && error.getStackTrace().length > 0)
            observations.append("synthetic failure site: ").append(error.getStackTrace()[0]).append("\n");
        result.putString("stream", observations.toString() + (error == null
            ? "MSIME_DEVICE_SMOKE_PASSED: toolbar layout, touch feedback observation, click lifecycle and synthetic toolbar pixels\n"
            : "MSIME_DEVICE_SMOKE_FAILED: toolbar layout (" + error.getClass().getSimpleName() + ")"
                + (error instanceof AssertionError ? " " + error.getMessage() : "") + "\n"));
        finish(error == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    /** 挂到真实窗口后观察框架自己排队的点击，既不提前调用点击，也不写全局输入偏好。 */
    private void checkContinuity() throws Exception {
        Activity activity = startActivitySync(new Intent().setClassName(getTargetContext(),
            "app.msime.android.home.HomeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        PressProbe[] holder = new PressProbe[1];
        try {
            onMain(() -> holder[0] = new PressProbe(activity));
            waitForIdleSync();
            PressProbe probe = holder[0];
            boolean[] laidOut = new boolean[1];
            // 主线程空闲可能早于下一次 Choreographer 布局，等待真实附着和尺寸，不靠固定延时。
            for (int attempt = 0; attempt < 300 && !laidOut[0]; attempt++) {
                onMain(() -> {
                    if (!probe.key.isAttachedToWindow()) return;
                    // 合成触摸用固定几何；Activity 窗口被引导层遮挡时也不能把零宽控件送入手势判断。
                    float density = getTargetContext().getResources().getDisplayMetrics().density;
                    layout(probe.key, Math.round(72 * density), Math.round(64 * density));
                    laidOut[0] = probe.key.getWidth() > 0;
                });
                if (!laidOut[0]) SystemClock.sleep(16);
            }
            onMain(() -> check(laidOut[0], "synthetic key layout: attached="
                + probe.key.isAttachedToWindow() + " width=" + probe.key.getWidth()
                + " parent=" + (probe.key.getParent() != null)));
            for (int index = 0; index < 3; index++) {
                int sequence = index;
                int before = probe.clicks;
                long[] atDown = new long[1];
                long started = SystemClock.uptimeMillis();
                onMain(() -> {
                    probe.pending.setLong(probe.overlay, -1);
                    touch(probe.key, started, MotionEvent.ACTION_DOWN);
                    atDown[0] = probe.pending.getLong(probe.overlay);
                    check(probe.clicks == before, "letter action ran on DOWN");
                    if (requireImmediate) check(atDown[0] >= 0, "letter feedback did not run on DOWN");
                });
                // 两次读取之间跨过时钟的一毫秒，才能区分触下和松手重复写入的反馈时间。
                SystemClock.sleep(20);
                onMain(() -> touch(probe.key, started, MotionEvent.ACTION_UP));
                waitForIdleSync();
                onMain(() -> {
                    long atUp = probe.pending.getLong(probe.overlay);
                    boolean duplicate = atDown[0] >= 0 && atUp != atDown[0];
                    check(probe.clicks == before + 1, "tap did not run exactly one letter action");
                    check(atUp >= 0 && !duplicate, "tap feedback was absent or repeated on UP");
                    check(!probe.key.isPressed(), "tap left the key pressed");
                    observations.append("TOUCH tap_").append(sequence)
                        .append(" down_feedback=").append(atDown[0] >= 0)
                        .append(" action_after_down=0 action_after_up=").append(probe.clicks - before)
                        .append(" duplicate_on_up=").append(duplicate)
                        .append(" pending_before=-1 pending_after_down=").append(atDown[0])
                        .append(" pending_after_up=").append(atUp).append('\n');
                });
            }
            onMain(() -> {
                int before = probe.clicks;
                long started = SystemClock.uptimeMillis();
                probe.pending.setLong(probe.overlay, -1);
                touch(probe.key, started, MotionEvent.ACTION_DOWN);
                boolean downFeedback = probe.pending.getLong(probe.overlay) >= 0;
                touch(probe.key, started, MotionEvent.ACTION_CANCEL);
                check(probe.clicks == before && !probe.key.isPressed(), "cancel committed or left a pressed key");
                // 取消后立刻走读屏使用的 performClick，不能依赖下一拍清理才恢复反馈。
                probe.pending.setLong(probe.overlay, -1);
                check(probe.key.performClick(), "accessibility click was rejected after cancel");
                boolean accessibleFeedback = probe.pending.getLong(probe.overlay) >= 0;
                check(probe.clicks == before + 1 && accessibleFeedback,
                    "cancel suppressed the next accessibility action or feedback");
                observations.append("TOUCH cancel down_feedback=").append(downFeedback)
                    .append(" actions=0 accessibility_after_cancel_feedback=").append(accessibleFeedback)
                    .append(" accessibility_actions=").append(probe.clicks - before).append('\n');
            });
            waitForIdleSync();
            onMain(() -> {
                check(probe.clicks == 4, "cancel left a queued letter action");
                probe.key.setEnabled(false);
                probe.pending.setLong(probe.overlay, -1);
                long started = SystemClock.uptimeMillis();
                touch(probe.key, started, MotionEvent.ACTION_DOWN);
                touch(probe.key, started, MotionEvent.ACTION_UP);
                boolean feedback = probe.pending.getLong(probe.overlay) >= 0;
                check(!feedback, "disabled key produced feedback");
            });
            waitForIdleSync();
            onMain(() -> {
                check(probe.clicks == 4, "disabled key produced a queued action");
                probe.key.setEnabled(true);
                observations.append("TOUCH disabled down_feedback=false actions=0\n");
                for (int index = 0; index < 2; index++) {
                    probe.pending.setLong(probe.overlay, -1);
                    check(probe.key.performClick(), "accessibility click was rejected");
                    check(probe.pending.getLong(probe.overlay) >= 0,
                        "accessibility click lost its feedback");
                }
                check(probe.clicks == 6, "consecutive accessibility clicks were lost or repeated");
                observations.append("TOUCH accessibility feedback=true actions=2\n");
            });
            waitForIdleSync();
            boolean[] rapidDown = new boolean[2];
            onMain(() -> {
                check(probe.clicks == 6, "input lifecycle left a queued duplicate action");
                for (int index = 0; index < rapidDown.length; index++) {
                    probe.pending.setLong(probe.overlay, -1);
                    long started = SystemClock.uptimeMillis();
                    touch(probe.key, started, MotionEvent.ACTION_DOWN);
                    rapidDown[index] = probe.pending.getLong(probe.overlay) >= 0;
                    touch(probe.key, started, MotionEvent.ACTION_UP);
                }
                check(probe.clicks == 6, "rapid letter action ran before queued clicks");
                // 点击尚在队列里，用独立哨兵观察反馈入口是否又执行；不依赖两次反馈跨过一毫秒。
                probe.pending.setLong(probe.overlay, -2);
            });
            waitForIdleSync();
            onMain(() -> {
                boolean lateFeedback = probe.pending.getLong(probe.overlay) != -2;
                check(probe.clicks == 8 && !probe.key.isPressed(), "rapid taps were lost, repeated or left pressed");
                check(!(rapidDown[0] || rapidDown[1]) || !lateFeedback,
                    "rapid queued clicks repeated feedback after DOWN");
                if (requireImmediate) check(rapidDown[0] && rapidDown[1],
                    "rapid taps did not both produce immediate feedback");
                observations.append("TOUCH rapid down_feedback_0=").append(rapidDown[0])
                    .append(" down_feedback_1=").append(rapidDown[1])
                    .append(" actions=2 late_click_feedback=").append(lateFeedback).append('\n');
                checkToolbarPixels(probe);
            });
        } finally {
            onMain(() -> {
                if (holder[0] != null) destroyService(holder[0].service);
                activity.finish();
            });
        }
    }

    /** 只替换当前隔离服务的内存状态，反馈入口仍执行产品实现，声音和马达在测试中关闭。 */
    private final class PressProbe {
        final Object service;
        final Button key;
        final Object overlay;
        final Field pending;
        int clicks;

        PressProbe(Activity activity) throws Exception {
            ClassLoader loader = getTargetContext().getClassLoader();
            Class<?> type = loader.loadClass("app.msime.android.MSIMEInputService");
            service = type.getConstructor().newInstance();
            Method attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            attach.invoke(service, getTargetContext());
            type.getMethod("onCreate").invoke(service);
            type.getMethod("onCreateInputView").invoke(service);
            field(type, "soundEnabled").setBoolean(service, false);
            field(type, "hapticsEnabled").setBoolean(service, false);
            Class<?> snapshot = loader.loadClass("app.msime.android.AndroidLocalSettings$Snapshot");
            Constructor<?> constructor = snapshot.getDeclaredConstructor(Map.class);
            constructor.setAccessible(true);
            field(type, "localSettings").set(service, constructor.newInstance(Map.of(
                "platform.android.key_popup", false, "platform.android.swipe_down_symbols", false,
                "platform.android.key_animation", "none")));
            key = (Button) loader.loadClass("app.msime.android.KeyboardPressButton")
                .getConstructor(Context.class).newInstance((Context) service);
            key.setText("a");
            key.setContentDescription("synthetic-continuity-a");
            // 用独立容器承载真实按钮，避免整块 IME 的可见性和高度策略隐藏合成按键。
            LinearLayout rows = new LinearLayout(activity);
            field(type, "keyRows").set(service, rows);
            rows.setVisibility(View.VISIBLE);
            float density = getTargetContext().getResources().getDisplayMetrics().density;
            rows.addView(key, new ViewGroup.LayoutParams(Math.round(72 * density), Math.round(64 * density)));
            method(type, "bindCountedAction", Button.class, Runnable.class).invoke(service, key,
                (Runnable) () -> clicks++);
            Object letters = field(type, "imeLetterRows").get(service);
            // 真实字母绑定现在携带一次手势的触点槽，测试也传入同形数组。
            Object touch = java.lang.reflect.Array.newInstance(loader.loadClass("app.msime.android.KeyTouchSample"), 1);
            method(letters.getClass(), "bindLetterGestures", Button.class, String.class, touch.getClass())
                .invoke(letters, key, "a", touch);
            overlay = field(type, "imeDebugOverlay").get(service);
            pending = field(overlay.getClass(), "pendingKeyAt");
            activity.setContentView(rows);
        }
    }

    /** 对产品按钮直接绘图，不把缩放动画或截图时机当作图标按下底色。 */
    private void checkToolbarPixels(PressProbe probe) throws Exception {
        ClassLoader loader = getTargetContext().getClassLoader();
        Class<?> type = loader.loadClass("app.msime.android.KeyboardShortcutButton");
        Class<?> iconType = loader.loadClass("app.msime.android.KeyboardShortcutIconPolicy$Icon");
        File directory = new File(new File(getTargetContext().getFilesDir(),
            "synthetic-toolbar-feedback"), evidenceLabel);
        check(directory.isDirectory() || directory.mkdirs(), "synthetic pixel directory unavailable");
        float density = getTargetContext().getResources().getDisplayMetrics().density;
        int side = Math.round(44 * density);
        for (String name : new String[] {"GLOBE", "VOICE", "CLIPBOARD"}) {
            Object icon = null;
            for (Object value : iconType.getEnumConstants()) if (name.equals(value.toString())) icon = value;
            check(icon != null, "missing product toolbar icon");
            Button button = (Button) type.getConstructor(Context.class, iconType)
                .newInstance((Context) probe.service, icon);
            button.setText("synthetic-" + name);
            button.setBackground(null);
            button.setStateListAnimator(null);
            type.getMethod("setActiveFill", int.class).invoke(button, 0xffd6e4f6);
            type.getMethod("setIconColors", int.class, int.class).invoke(button, 0xff58606b, 0xff335d8e);
            layout(button, side, side);
            Bitmap idle = draw(button);
            button.setPressed(true);
            Bitmap pressed = draw(button);
            button.setPressed(false);
            Bitmap released = draw(button);
            button.setSelected(true);
            Bitmap selected = draw(button);
            button.setPressed(true);
            button.setPressed(false);
            Bitmap selectedRestored = draw(button);
            check(button.isSelected(), "press cleared the active toolbar selection");
            button.setSelected(false);
            button.setEnabled(false);
            Bitmap disabled = draw(button);
            button.setPressed(true);
            Bitmap disabledPressed = draw(button);
            button.setPressed(false);
            int pressPixels = changedPixels(idle, pressed);
            int releasePixels = changedPixels(idle, released);
            int selectedPixels = changedPixels(idle, selected);
            int selectedRestoredPixels = changedPixels(selected, selectedRestored);
            int disabledPixels = changedPixels(disabled, disabledPressed);
            check(releasePixels == 0 && selectedPixels > 0 && selectedRestoredPixels == 0
                    && disabledPixels == 0, "toolbar release, selection or disabled pixels changed");
            check(button.getWidth() == side && button.getHeight() == side,
                "toolbar pressed state changed its touch target");
            observations.append("PIXELS ").append(name).append(" pressed_changed=").append(pressPixels)
                .append(" released_changed=").append(releasePixels)
                .append(" selected_changed=").append(selectedPixels)
                .append(" selected_restored_changed=").append(selectedRestoredPixels)
                .append(" disabled_pressed_changed=").append(disabledPixels)
                .append(" target=").append(button.getWidth()).append('x').append(button.getHeight()).append('\n');
            Bitmap[] states = {idle, pressed, released, selected, disabled};
            String[] labels = {"idle", "pressed", "released", "selected", "disabled"};
            for (int index = 0; index < states.length; index++) {
                try (var output = new FileOutputStream(new File(directory,
                        name.toLowerCase(java.util.Locale.ROOT) + "-" + labels[index] + ".png"))) {
                    check(states[index].compress(Bitmap.CompressFormat.PNG, 100, output),
                        "synthetic toolbar PNG write failed");
                }
                states[index].recycle();
            }
            selectedRestored.recycle();
            disabledPressed.recycle();
            if (requireImmediate) check(pressPixels > 0, "toolbar glyph has no immediate pressed pixels: " + name);
        }
        observations.append("PNG_DIRECTORY=").append(directory.getAbsolutePath()).append('\n');
    }

    private static Bitmap draw(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(245, 246, 248));
        view.draw(canvas);
        return bitmap;
    }

    private static int changedPixels(Bitmap first, Bitmap second) {
        int size = first.getWidth() * first.getHeight();
        int[] left = new int[size], right = new int[size];
        first.getPixels(left, 0, first.getWidth(), 0, 0, first.getWidth(), first.getHeight());
        second.getPixels(right, 0, second.getWidth(), 0, 0, second.getWidth(), second.getHeight());
        int changed = 0;
        for (int index = 0; index < size; index++) if (left[index] != right[index]) changed++;
        return changed;
    }

    private static void touch(View key, long started, int action) {
        MotionEvent event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action,
            key.getWidth() / 2f, key.getHeight() / 2f, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { key.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    private interface CheckedAction { void run() throws Exception; }

    private void onMain(CheckedAction action) throws Exception {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try { action.run(); }
            catch (Exception | AssertionError error) { failure[0] = rootCause(error); }
        });
        if (failure[0] instanceof Exception exception) throw exception;
        if (failure[0] instanceof Error error) throw error;
    }

    private static Throwable rootCause(Throwable error) {
        while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null)
            error = error.getCause();
        return error;
    }

    private static void destroyService(Object service) {
        try { service.getClass().getMethod("onDestroy").invoke(service); }
        catch (ReflectiveOperationException ignored) { /* 清理隔离服务。 */ }
        for (Field member : service.getClass().getDeclaredFields()) {
            if (!ExecutorService.class.isAssignableFrom(member.getType())) continue;
            try { member.setAccessible(true); ((ExecutorService) member.get(service)).shutdownNow(); }
            catch (ReflectiveOperationException ignored) { /* 清理测试工作线程。 */ }
        }
    }

    /** 用隔离历史驱动产品的真实渲染，记录卡片滚动时顶栏仍留在原位。 */
    private void checkClipboardHeader(Class<?> type, Object service, ClassLoader loader, float density)
            throws Exception {
        File directory = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),
            "clipboard-sticky-").toFile();
        Field history = field(type, "clipboardHistory");
        Object original = history.get(service);
        boolean enabled = field(type, "clipboardHistoryEnabled").getBoolean(service);
        try {
            Class<?> storeType = loader.loadClass("app.msime.android.ClipboardHistoryStore");
            Object store = storeType.getConstructor(File.class).newInstance(directory);
            for (int index = 0; index < 30; index++)
                storeType.getMethod("add", String.class).invoke(store, "合成剪贴板记录 " + index);
            history.set(service, store);
            field(type, "clipboardHistoryEnabled").setBoolean(service, true);
            Object panels = field(type, "imePanels").get(service);
            method(panels.getClass(), "renderClipboardHistory").invoke(panels);
            ViewGroup surface = (ViewGroup) field(type, "clipboardSurface").get(service);
            ViewGroup header = (ViewGroup) field(type, "clipboardHeader").get(service);
            ScrollView scroll = (ScrollView) field(type, "clipboardScroll").get(service);
            ViewGroup cards = (ViewGroup) field(type, "clipboardPanel").get(service);
            surface.setVisibility(View.VISIBLE);
            for (int widthDp : new int[] {320, 600}) {
                layout(surface, Math.round(widthDp * density), Math.round(300 * density));
                scroll.scrollTo(0, 0);
                check(header.getChildCount() > 0 && cards.getChildCount() > 1,
                    "clipboard renderer did not create header and synthetic cards");
                Rect fixed = bounds(surface, header);
                Rect first = bounds(surface, cards.getChildAt(0));
                check(first.top >= fixed.bottom, "clipboard cards overlap fixed header");
                scroll.scrollTo(0, cards.getHeight());
                check(scroll.getScrollY() > 0, "synthetic clipboard history did not overflow");
                check(fixed.equals(bounds(surface, header)), "clipboard scrolling moved header");
                check(bounds(surface, cards.getChildAt(0)).top < first.top,
                    "clipboard scrolling did not move history cards");
            }
            check((Boolean) method(panels.getClass(), "clipboardPanelOpen").invoke(panels),
                "visible clipboard surface is not reported open");
            method(type, "render").invoke(service);
            Object bottomBar = field(type, "imeBottomBar").get(service);
            Button bottomClipboard = (Button) field(bottomBar.getClass(), "clipboardButton").get(bottomBar);
            check(bottomClipboard.isSelected(), "clipboard bottom bar button is not selected while panel is open");
            method(type, "closeClipboardHistory").invoke(service);
            method(type, "render").invoke(service);
            check(!bottomClipboard.isSelected(), "clipboard bottom bar button stayed selected after insertion closed panel");
            method(panels.getClass(), "showClipboardHistory").invoke(panels);
            method(type, "render").invoke(service);
            check(bottomClipboard.isSelected(), "clipboard bottom bar button did not select after reopening panel");
            check(bottomClipboard.performClick(), "clipboard bottom bar button did not handle the click");
            check(surface.getVisibility() == View.GONE
                    && !(Boolean) method(panels.getClass(), "clipboardPanelOpen").invoke(panels),
                "clipboard bottom bar button did not close the visible panel");
            check(!bottomClipboard.isSelected(), "clipboard bottom bar button stayed selected after closing panel");
            method(type, "closeClipboardHistory").invoke(service);
            check(surface.getVisibility() == View.GONE
                    && !(Boolean) method(panels.getClass(), "clipboardPanelOpen").invoke(panels),
                "clipboard close left fixed header or panel state visible");
        } finally {
            history.set(service, original);
            field(type, "clipboardHistoryEnabled").setBoolean(service, enabled);
            try (var paths = Files.walk(directory.toPath())) {
                for (var path : paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.delete(path);
            }
        }
    }

    private static void layout(View row, int width, int height) {
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        row.layout(0, 0, width, height);
    }
    private static boolean inside(View view, ViewGroup ancestor) {
        for (android.view.ViewParent parent = view.getParent(); parent != null; parent = parent.getParent())
            if (parent == ancestor) return true;
        return false;
    }
    private static Rect bounds(ViewGroup parent, View view) {
        Rect result = new Rect();
        view.getDrawingRect(result);
        parent.offsetDescendantRectToMyCoords(view, result);
        return result;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
