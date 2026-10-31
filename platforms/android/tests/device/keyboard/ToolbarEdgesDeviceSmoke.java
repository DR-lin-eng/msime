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
                // 语音按钮曾被当作退役按钮从工具栏上拆下，却仍被设成可见（#10142）；它要挂在中间区，跟着排序移动。
                Button voice = (Button) field(type, "voiceShortcutButton").get(service);
                check(voice.getParent() == bar, "voice button is not one of the toolbar tools");
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
                    "platform.android.toolbar_order", "voice,text_edit,floating,scheme,skin,clipboard,phrase,emoji")));
                Object toolbar = field(type, "imeToolbar").get(service);
                method(toolbar.getClass(), "applyShortcutOrder").invoke(toolbar);
                check(bar.getChildAt(0) == result, "calculator lost first position");
                check(bar.getChildAt(1) == voice, "voice button did not follow the saved order");
                for (int index = 0; index < buttons.length; index++)
                    check(bar.getChildAt(index + 2) == buttons[buttons.length - index - 1],
                        "custom order changed tool identity");
                for (int index = 1; index < buttons.length; index++) buttons[index].setVisibility(View.GONE);
                voice.setVisibility(View.GONE);
                result.setVisibility(View.GONE);
                layout(row, Math.round(320 * density), Math.round(64 * density));
                check(scroll.getScrollX() == 0, "hidden tools left stale scroll offset");
                check(buttons[0].getWidth() >= scroll.getWidth() - 2 * Math.round(density),
                    "single visible tool did not fill available width");
                checkLongSpaceGestureAfterRecreation(type, service, loader);
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
            ? "MSIME_DEVICE_SMOKE_PASSED: toolbar fixed edges, 240/320/600dp, calculator, order with voice, hidden tools, resize and sticky clipboard header\n"
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

    /** 同一服务重建键盘视图后，新逗号键仍要接收下滑；用合成编辑器收集实际提交字符。长空格是中文 26 键的底行样式（共享偏好 touch_twenty_six_key_style），这里直接换上服务持有的样式，英文 26 键不受它影响，所以用中文模式。 */
    private void checkLongSpaceGestureAfterRecreation(Class<?> type, Object service, ClassLoader loader)
            throws Exception {
        Class<?> styles = loader.loadClass("app.msime.android.TouchKeyStyle$Styles");
        Object longSpace = styles.getMethod("parse", String.class, String.class, String.class)
            .invoke(null, "long_space", null, null);
        Field stylesField = field(type, "touchKeyStyles");
        Object previous = stylesField.get(service);
        boolean previousEnglish = field(type, "dedicatedEnglish").getBoolean(service);
        StringBuilder committed = new StringBuilder();
        android.view.inputmethod.InputConnection connection =
            (android.view.inputmethod.InputConnection) java.lang.reflect.Proxy.newProxyInstance(
                android.view.inputmethod.InputConnection.class.getClassLoader(),
                new Class<?>[] {android.view.inputmethod.InputConnection.class}, (proxy, call, args) -> {
                    if (call.getName().equals("commitText")) committed.append((CharSequence) args[0]);
                    if (call.getReturnType() == boolean.class) return true;
                    if (call.getReturnType() == int.class) return 0;
                    return null;
                });
        try {
            for (int generation = 0; generation < 2; generation++) {
                if (generation > 0) type.getMethod("onCreateInputView").invoke(service);
                stylesField.set(service, longSpace);
                field(type, "dedicatedEnglish").setBoolean(service, false);
                field(type, "editorInputType").setInt(service, android.text.InputType.TYPE_CLASS_TEXT);
                field(type, "touchLayoutHint").setInt(service, 0);
                field(type, "connection").set(service, connection);
                method(type, "updateQuickPunctuation").invoke(service);
                Button comma = (Button) field(type, "quickPunctuationButton").get(service);
                comma.setEnabled(true);
                comma.layout(0, 0, 200, 200);
                long down = android.os.SystemClock.uptimeMillis();
                float density = getTargetContext().getResources().getDisplayMetrics().density;
                // 门槛是字母键滑动输入符号的 14 dp；滑过它再留余量，另取一段不到门槛的上移当作点按时的手指漂移。
                int distance = Math.round(14 * density) + 20;
                int drift = Math.round(8 * density);
                int[] actions = {android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE,
                    android.view.MotionEvent.ACTION_UP};
                // 下滑是 #9881 要的方向；上滑与字母键滑动输入符号的默认方向一致，两个方向都要提交句号。
                for (int direction : new int[] {1, -1}) {
                    committed.setLength(0);
                    for (int index = 0; index < actions.length; index++) {
                        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down + index * 20,
                            actions[index], 40, index == 0 ? 100 : 100 + direction * distance, 0);
                        try { comma.dispatchTouchEvent(event); }
                        finally { event.recycle(); }
                    }
                    // 没有会话时句点原样上屏，有会话时引擎按中文标点给出「。」。
                    check(committed.toString().equals(".") || committed.toString().equals("。"),
                        "long-space comma " + (direction > 0 ? "down" : "up") + " swipe lost after input-view generation "
                            + generation + ": " + committed);
                    down = android.os.SystemClock.uptimeMillis();
                }
                // 点逗号时手指往上带一点（不到门槛）不是滑动，不能出句号。
                committed.setLength(0);
                for (int index = 0; index < actions.length; index++) {
                    android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down + index * 20,
                        actions[index], 40, index == 0 ? 100 : 100 - drift, 0);
                    try { comma.dispatchTouchEvent(event); }
                    finally { event.recycle(); }
                }
                check(!committed.toString().contains(".") && !committed.toString().contains("。"),
                    "long-space comma tap drift became a period after input-view generation " + generation + ": " + committed);
                down = android.os.SystemClock.uptimeMillis();
            }
        } finally {
            stylesField.set(service, previous);
            field(type, "dedicatedEnglish").setBoolean(service, previousEnglish);
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
        Field connection = field(type, "connection");
        Object originalConnection = connection.get(service);
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
                method(panels.getClass(), "updateClipboardBackToTop").invoke(panels);
                Button backToTop = (Button) field(type, "clipboardBackToTop").get(service);
                check(backToTop.getVisibility() == View.GONE, "back to top is visible before scrolling");
                check(header.getChildCount() > 0 && cards.getChildCount() > 1,
                    "clipboard renderer did not create header and synthetic cards");
                Rect fixed = bounds(surface, header);
                Rect first = bounds(surface, cards.getChildAt(0));
                check(first.top >= fixed.bottom, "clipboard cards overlap fixed header");
                scroll.scrollTo(0, cards.getHeight());
                check(scroll.getScrollY() > 0, "synthetic clipboard history did not overflow");
                method(panels.getClass(), "updateClipboardBackToTop").invoke(panels);
                check(backToTop.getVisibility() == View.VISIBLE, "back to top did not appear after scrolling");
                check(fixed.equals(bounds(surface, header)), "clipboard scrolling moved header");
                check(bounds(surface, cards.getChildAt(0)).top < first.top,
                    "clipboard scrolling did not move history cards");
                check(backToTop.performClick(), "back to top action was not handled");
                check(scroll.getScrollY() == 0, "back to top did not reset history position");
                method(panels.getClass(), "updateClipboardBackToTop").invoke(panels);
                check(backToTop.getVisibility() == View.GONE, "back to top stayed visible at top");
            }
            checkClipboardClearConfirm(panels, header, surface, density);
            checkClipboardMoreMenu(type, service, panels, header, cards, surface, density);
            checkClipboardUndoFloating(type, service, panels, header, surface, scroll, cards, density);
            checkClipboardSingleDeleteUndo(type, service, panels, cards);
            check((Boolean) method(panels.getClass(), "clipboardPanelOpen").invoke(panels),
                "visible clipboard surface is not reported open");
            method(type, "render").invoke(service);
            Object bottomBar = field(type, "imeBottomBar").get(service);
            Button bottomClipboard = (Button) field(bottomBar.getClass(), "clipboardButton").get(bottomBar);
            Button topClipboard = (Button) field(type, "clipboardShortcutButton").get(service);
            check(bottomClipboard.isSelected(), "clipboard bottom bar button is not selected while panel is open");
            check(topClipboard.isSelected(), "clipboard toolbar button is not selected while panel is open");
            // 用真实编辑器接收插入；调用返回后立即检查，不补刷新或模拟下一次按键。
            android.widget.EditText editor = new android.widget.EditText(getTargetContext());
            editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            android.view.inputmethod.InputConnection editorConnection =
                editor.onCreateInputConnection(new android.view.inputmethod.EditorInfo());
            check(editorConnection != null, "synthetic clipboard editor has no input connection");
            connection.set(service, editorConnection);
            String inserted = "合成剪贴板插入";
            method(type, "insertClipboardText", String.class).invoke(service, inserted);
            check(editor.getText().toString().equals(inserted), "clipboard insertion did not reach the editor");
            check(surface.getVisibility() == View.GONE
                    && !(Boolean) method(panels.getClass(), "clipboardPanelOpen").invoke(panels),
                "clipboard insertion did not close the panel");
            check(!topClipboard.isSelected(), "clipboard toolbar button stayed selected after insertion closed panel");
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
            connection.set(service, originalConnection);
            try (var paths = Files.walk(directory.toPath())) {
                for (var path : paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.delete(path);
            }
        }
    }

    /**
     * 确认清空的顶行在窄键盘上也要整行落在键盘宽度以内：问句、「取消」「清空」都有宽高，按钮不出右边界（#10030）。
     *
     * <p>顶行原来套在 HorizontalScrollView 里按 UNSPECIFIED 宽度量，整行约 270dp；240dp 宽时「清空」被挤到右侧屏外，要横滑才点得到。
     */
    private static void checkClipboardClearConfirm(Object panels, ViewGroup header, ViewGroup surface,
            float density) throws Exception {
        Field pending = field(panels.getClass(), "clipboardClearPending");
        Method render = method(panels.getClass(), "renderClipboardHistory");
        pending.setBoolean(panels, true);
        try {
            render.invoke(panels);
            for (int widthDp : new int[] {240, 320, 600}) {
                layout(surface, Math.round(widthDp * density), Math.round(300 * density));
                View question = descendant(header, view -> view instanceof android.widget.TextView
                    && !(view instanceof Button)
                    && "确认清空全部记录（含固定项）？".contentEquals(((android.widget.TextView) view).getText()));
                View cancel = descendant(header, view -> view instanceof Button
                    && "取消".contentEquals(((Button) view).getText()));
                // 「取消」等按钮没有无障碍描述，getContentDescription() 是 null，`String.contentEquals(null)` 会抛 NPE，要用可比 null 的比较。
                View confirm = descendant(header, view -> view instanceof Button
                    && describedAs(view, "确认清空剪贴板历史"));
                check(question != null && cancel != null && confirm != null,
                    "clipboard clear confirm row is missing its question or buttons at " + widthDp + "dp");
                int right = header.getWidth() - header.getPaddingRight();
                for (View part : new View[] {question, cancel, confirm}) {
                    Rect area = bounds(header, part);
                    check(area.width() > 0 && area.height() > 0,
                        "clipboard clear confirm part is not drawn at " + widthDp + "dp: " + area.toShortString());
                    check(area.left >= header.getPaddingLeft() && area.right <= right,
                        "clipboard clear confirm part leaves the keyboard width at " + widthDp + "dp: "
                            + area.toShortString() + " outside " + header.getPaddingLeft() + ".." + right);
                }
                check(bounds(header, cancel).right <= bounds(header, confirm).left,
                    "clipboard clear cancel and confirm overlap at " + widthDp + "dp");
            }
            // 「提示明显」的另一半：确认按钮必须是回车键的强调键帽，「取消」仍是普通文字按钮。只量几何时，去掉 RETURN 也照样通过。
            View cancel = descendant(header, view -> view instanceof Button
                && "取消".contentEquals(((Button) view).getText()));
            View confirm = descendant(header, view -> view instanceof Button
                && describedAs(view, "确认清空剪贴板历史"));
            Class<?> press = confirm.getClass().getClassLoader().loadClass("app.msime.android.KeyboardPressButton");
            Method role = method(press, "keyboardRole");
            check(press.isInstance(confirm) && "RETURN".equals(String.valueOf(role.invoke(confirm))),
                "clipboard clear confirm is not drawn as the emphasized return key cap");
            check(press.isInstance(cancel) && !"RETURN".equals(String.valueOf(role.invoke(cancel))),
                "clipboard clear cancel took the emphasized return key cap");
        } finally {
            pending.setBoolean(panels, false);
            render.invoke(panels);
        }
    }
    /**
     * 顶行只留本机、云端、计数、搜索、多选和「更多」，常见宽度下一行放得下、不用横滑；分类、导出、清空在「更多」展开的菜单里（#10037）。
     *
     * <p>菜单项必须是普通键帽，字色就是当前皮肤的按键前景色：深色模式下菜单文字和底色融成一片是报告里特别提醒的问题，只查几何时把菜单项换成别的样式也会通过。
     */
    private static void checkClipboardMoreMenu(Class<?> type, Object service, Object panels, ViewGroup header,
            ViewGroup cards, ViewGroup surface, float density) throws Exception {
        Field open = field(panels.getClass(), "clipboardMoreOpen");
        Field pending = field(panels.getClass(), "clipboardClearPending");
        Method render = method(panels.getClass(), "renderClipboardHistory");
        try {
            open.setBoolean(panels, false);
            render.invoke(panels);
            for (int widthDp : new int[] {360, 600}) {
                layout(surface, Math.round(widthDp * density), Math.round(300 * density));
                for (String moved : new String[] {"管理剪贴板分类", "导出全部剪贴板历史", "清空剪贴板历史"})
                    check(descendant(surface, view -> describedAs(view, moved)) == null,
                        "clipboard " + moved + " is still drawn while the more menu is closed");
                View row = descendant(header, view -> view instanceof android.widget.HorizontalScrollView);
                ViewGroup scroll = (ViewGroup) row;
                check(scroll != null && scroll.getChildAt(0).getWidth() <= scroll.getWidth(),
                    "clipboard header still needs horizontal scrolling at " + widthDp + "dp");
                View more = descendant(header, view -> view instanceof Button
                    && describedAs(view, "更多剪贴板操作"));
                check(more != null, "clipboard header has no more button at " + widthDp + "dp");
                Rect area = bounds(header, more);
                check(area.width() > 0 && area.height() > 0 && area.right <= header.getWidth() - header.getPaddingRight(),
                    "clipboard more button leaves the keyboard width at " + widthDp + "dp: " + area.toShortString());
            }
            checkClipboardMorePinned(panels, header, surface, density);
            View more = descendant(header, view -> view instanceof Button
                && describedAs(view, "更多剪贴板操作"));
            check(more.performClick() && open.getBoolean(panels), "clipboard more button did not open the menu");
            layout(surface, Math.round(360 * density), Math.round(300 * density));
            // 菜单容器没有无障碍说明（免得 TalkBack 多停一个不能操作的节点），按菜单里「分类」那一项找回容器。
            View category = descendant(cards, view -> describedAs(view, "管理剪贴板分类"));
            check(category != null && category.getParent() instanceof ViewGroup,
                "clipboard more menu is not drawn in the panel");
            View menu = (View) category.getParent();
            check(menu.getContentDescription() == null,
                "clipboard more menu container has its own accessibility stop");
            Rect menuArea = bounds(surface, menu);
            Rect moreArea = bounds(surface, descendant(header, view -> view instanceof Button
                && describedAs(view, "更多剪贴板操作")));
            check(menuArea.top >= moreArea.bottom && menuArea.right <= surface.getWidth(),
                "clipboard more menu is not below the more button: " + menuArea.toShortString());
            Object skin = field(type, "skin").get(service);
            int foreground = android.graphics.Color.parseColor(
                (String) skin.getClass().getMethod("keyForeground").invoke(skin));
            // 菜单容器是系统的 LinearLayout，它的类加载器是启动类加载器，找不到应用类，会抛 ClassNotFoundException；要用加载服务类的那个加载器。
            Class<?> press = type.getClassLoader().loadClass("app.msime.android.KeyboardPressButton");
            Method role = method(press, "keyboardRole");
            for (String item : new String[] {"管理剪贴板分类", "导出全部剪贴板历史", "清空剪贴板历史"}) {
                View button = descendant((ViewGroup) menu, view -> describedAs(view, item));
                check(button != null, "clipboard more menu has no " + item);
                Rect itemArea = bounds(surface, button);
                check(itemArea.width() > 0 && itemArea.height() > 0 && itemArea.right <= surface.getWidth(),
                    "clipboard more menu item is not drawn inside the keyboard: " + item + " " + itemArea.toShortString());
                check(press.isInstance(button) && "KEY".equals(String.valueOf(role.invoke(button))),
                    "clipboard more menu item is not a plain key cap: " + item);
                check(((Button) button).getCurrentTextColor() == foreground,
                    "clipboard more menu item does not use the skin's key label colour: " + item);
            }
            // 系统深浅色切换时 onConfigurationChanged 只换皮肤再走 applySkin，不重画剪贴板面板；外框要由换肤那一遍补画，不能只在 renderClipboardHistory 末尾画一次。
            menu.setBackground(null);
            Object styler = field(type, "imeStyler").get(service);
            method(styler.getClass(), "applySkin").invoke(styler);
            Object restyled = field(type, "skin").get(service);
            check(menu.getBackground() instanceof android.graphics.drawable.GradientDrawable frame
                    && frame.getColor() != null
                    && frame.getColor().getDefaultColor() == android.graphics.Color.parseColor(
                        (String) restyled.getClass().getMethod("background").invoke(restyled)),
                "clipboard more menu frame is not restyled by the skin pass");
            View clear = descendant((ViewGroup) menu, view -> describedAs(view, "清空剪贴板历史"));
            check(clear.performClick() && pending.getBoolean(panels) && !open.getBoolean(panels),
                "clipboard more menu clear did not close the menu and ask for confirmation");
            check(descendant(cards, view -> describedAs(view, "管理剪贴板分类")) == null,
                "clipboard more menu stayed open after choosing clear");
            pending.setBoolean(panels, false);
            checkClipboardMoreDetailReturn(type, service, panels, cards, surface, density);
        } finally {
            open.setBoolean(panels, false);
            pending.setBoolean(panels, false);
            render.invoke(panels);
        }
    }
    /**
     * 菜单开着时点一条的 ⓘ 看详情，再返回：菜单已收起、列表整体上移了菜单那一截，返回后刚看的那条要停在离开时的位置（#10037）。
     *
     * <p>原来记的是菜单还在时的 `getScrollY()`，返回后列表往下错开约 140 dp，刚看的那条可能被滚出顶部。
     */
    private static void checkClipboardMoreDetailReturn(Class<?> type, Object service, Object panels,
            ViewGroup cards, ViewGroup surface, float density) throws Exception {
        Field open = field(panels.getClass(), "clipboardMoreOpen");
        Field saved = field(panels.getClass(), "clipboardDetailScrollY");
        ScrollView scroll = (ScrollView) field(type, "clipboardScroll").get(service);
        int width = Math.round(360 * density);
        int height = Math.round(300 * density);
        int target = 5;
        try {
            open.setBoolean(panels, true);
            method(panels.getClass(), "renderClipboardHistory").invoke(panels);
            layout(surface, width, height);
            check(descendant(cards, view -> describedAs(view, "管理剪贴板分类")) != null,
                "clipboard more menu is not open before opening a record's details");
            java.util.List<View> details = describedViews(cards, "查看这条剪贴板记录详情");
            check(details.size() > target, "synthetic clipboard history has too few detail buttons");
            View detail = details.get(target);
            scroll.scrollTo(0, bounds(cards, detail).top - Math.round(40 * density));
            check(scroll.getScrollY() > 0, "clipboard history did not scroll before opening details");
            int before = bounds(surface, detail).top;
            check(detail.performClick() && !open.getBoolean(panels),
                "clipboard detail button did not close the more menu");
            int restored = saved.getInt(panels);
            method(panels.getClass(), "returnFromClipboardDetail").invoke(panels);
            layout(surface, width, height);
            scroll.scrollTo(0, restored);
            java.util.List<View> returned = describedViews(cards, "查看这条剪贴板记录详情");
            check(returned.size() > target, "clipboard history did not come back after details");
            int after = bounds(surface, returned.get(target)).top;
            check(after == before, "clipboard record moved after returning from details with the more menu open: "
                + before + " -> " + after);
        } finally {
            method(panels.getClass(), "clearClipboardDetail").invoke(panels);
        }
    }
    private static java.util.List<View> describedViews(ViewGroup root, String text) {
        java.util.List<View> result = new java.util.ArrayList<>();
        for (int index = 0; index < root.getChildCount(); index++) {
            View child = root.getChildAt(index);
            if (describedAs(child, text)) result.add(child);
            if (child instanceof ViewGroup group) result.addAll(describedViews(group, text));
        }
        return result;
    }
    /**
     * 键盘窄到顶行内容比键盘宽、左边那些按钮要横滑时，「更多」钉在横向滚动外面，仍整个落在键盘宽度以内（#10037）。
     *
     * <p>只在 360dp 量「顶行不需横滑」，恰好避开了会超宽的情形：「更多」原来是横向滚动里最后一个，最先被挤出屏外。删除后的「撤销」原来也在顶行里，240dp 时靠它把顶行撑宽；它改成浮在面板右下角以后（#10073），从 240dp 往下收窄键盘，直到顶行确实超宽再量。
     */
    private static void checkClipboardMorePinned(Object panels, ViewGroup header, ViewGroup surface, float density)
            throws Exception {
        Method render = method(panels.getClass(), "renderClipboardHistory");
        try {
            render.invoke(panels);
            ViewGroup scroll = null;
            int widthDp = 240;
            for (; widthDp >= 120; widthDp -= 20) {
                layout(surface, Math.round(widthDp * density), Math.round(300 * density));
                scroll = (ViewGroup) descendant(header, view -> view instanceof android.widget.HorizontalScrollView);
                check(scroll != null, "clipboard header has no horizontal scroll at " + widthDp + "dp");
                if (scroll.getChildAt(0).getWidth() > scroll.getWidth()) break;
            }
            check(widthDp >= 120,
                "clipboard header never overflows down to 120dp; the pinned more button is untested");
            View more = descendant(header, view -> view instanceof Button && describedAs(view, "更多剪贴板操作"));
            check(more != null && !inside(more, scroll), "clipboard more button scrolls with the header row");
            Rect area = bounds(header, more);
            check(area.width() > 0 && area.height() > 0 && area.left >= header.getPaddingLeft()
                    && area.right <= header.getWidth() - header.getPaddingRight(),
                "clipboard more button leaves the keyboard width at " + widthDp + "dp: " + area.toShortString());
        } finally {
            render.invoke(panels);
        }
    }

    /**
     * 多选删除后的「撤销」浮在剪贴板面板右下角、不在顶行里，按钮上从设置的秒数开始倒数，点了恢复记录，到期收起；和「返回顶部」同时出现时两者不重叠（#10073）。
     *
     * <p>整段跑在 `runOnMainSync` 里，删除时投递的倒数回调不会在断言之间执行；到期用改截止时刻再手动走一次倒数来模拟。
     */
    private static void checkClipboardUndoFloating(Class<?> type, Object service, Object panels, ViewGroup header,
            ViewGroup surface, ScrollView scroll, ViewGroup cards, float density) throws Exception {
        Field seconds = field(type, "clipboardUndoSeconds");
        int previousSeconds = seconds.getInt(service);
        Object history = field(type, "clipboardHistory").get(service);
        Method load = history.getClass().getMethod("load");
        Method remove = method(type, "removeClipboardItems", java.util.List.class);
        Method hasUndo = method(type, "hasClipboardUndo");
        Method updateBackToTop = method(panels.getClass(), "updateClipboardBackToTop");
        Button undo = (Button) field(type, "clipboardUndoButton").get(service);
        Button backToTop = (Button) field(type, "clipboardBackToTop").get(service);
        String target = "合成剪贴板记录 3";
        try {
            check(undo.getVisibility() == View.GONE, "clipboard undo is visible before any deletion");
            seconds.setInt(service, 7);
            remove.invoke(service, java.util.List.of(target));
            check(!containsText(load.invoke(history), target), "clipboard deletion did not remove the record");
            check(undo.getVisibility() == View.VISIBLE, "clipboard undo did not appear after a deletion");
            check("撤销（7 秒）".contentEquals(undo.getText()),
                "clipboard undo does not count down from the configured 7 seconds: " + undo.getText());
            check(undo.getParent() == surface && !inside(undo, header) && !inside(undo, scroll),
                "clipboard undo is not floating on the panel surface");
            check(descendant(header, view -> describedAs(view, "撤销刚才删除的剪贴板记录")) == null,
                "clipboard header still has the old undo button");
            // 「撤销」要用回车键的强调键帽，字色是当前皮肤的回车键前景色，并且由换肤那一遍重新上色（深浅色切换时不重建按钮）；只量几何时换成普通键帽或漏了换肤也照样通过。
            Class<?> press = type.getClassLoader().loadClass("app.msime.android.KeyboardPressButton");
            check(press.isInstance(undo) && "RETURN".equals(String.valueOf(method(press, "keyboardRole").invoke(undo))),
                "clipboard undo is not drawn as the emphasized return key cap");
            undo.setTextColor(android.graphics.Color.MAGENTA);
            Object styler = field(type, "imeStyler").get(service);
            method(styler.getClass(), "applySkin").invoke(styler);
            Object skin = field(type, "skin").get(service);
            check(undo.getCurrentTextColor() == android.graphics.Color.parseColor(
                    (String) skin.getClass().getMethod("returnForeground").invoke(skin)),
                "clipboard undo is not restyled with the skin's return key label colour");
            for (int widthDp : new int[] {320, 600}) {
                int width = Math.round(widthDp * density);
                int height = Math.round(300 * density);
                layout(surface, width, height);
                scroll.scrollTo(0, cards.getHeight());
                updateBackToTop.invoke(panels);
                // 「返回顶部」的位置随「撤销」改了边距，要重新量一次。
                layout(surface, width, height);
                check(backToTop.getVisibility() == View.VISIBLE, "back to top did not appear while undo is shown");
                Rect undoArea = bounds(surface, undo);
                Rect topArea = bounds(surface, backToTop);
                check(undoArea.height() >= Math.round(48 * density) - 1
                        && undoArea.right <= width && undoArea.bottom <= height
                        && undoArea.right >= width - Math.round(16 * density)
                        && undoArea.bottom >= height - Math.round(16 * density),
                    "clipboard undo is not a large button in the lower-right corner at " + widthDp + "dp: "
                        + undoArea.toShortString());
                check(!Rect.intersects(undoArea, topArea) && topArea.bottom <= undoArea.top,
                    "back to top overlaps clipboard undo at " + widthDp + "dp: " + topArea.toShortString()
                        + " vs " + undoArea.toShortString());
                // 列表底部给「撤销」让出位置：滚到底时最后一行卡片整个落在按钮上方，点得到、横滑也够得着。
                Rect last = bounds(surface, cards.getChildAt(cards.getChildCount() - 1));
                check(last.bottom <= undoArea.top,
                    "clipboard undo covers the last card when scrolled to the bottom at " + widthDp + "dp: "
                        + last.toShortString() + " vs " + undoArea.toShortString());
                scroll.scrollTo(0, 0);
                updateBackToTop.invoke(panels);
            }
            check(undo.performClick(), "clipboard undo did not handle the click");
            check(containsText(load.invoke(history), target), "clipboard undo did not restore the deleted record");
            check(undo.getVisibility() == View.GONE && !(Boolean) hasUndo.invoke(service),
                "clipboard undo stayed after restoring");

            remove.invoke(service, java.util.List.of(target));
            check(undo.getVisibility() == View.VISIBLE, "clipboard undo did not appear after the second deletion");
            field(type, "clipboardUndoDeadline").setLong(service, android.os.SystemClock.uptimeMillis() - 1);
            method(type, "tickClipboardUndo").invoke(service);
            check(undo.getVisibility() == View.GONE && !(Boolean) hasUndo.invoke(service),
                "clipboard undo did not disappear when the countdown reached zero");
            check(!containsText(load.invoke(history), target), "an expired undo restored the record");
            check(cards.getPaddingBottom() == cards.getPaddingTop(),
                "clipboard list kept the extra bottom room after undo expired: " + cards.getPaddingBottom());
            layout(surface, Math.round(320 * density), Math.round(300 * density));
            scroll.scrollTo(0, cards.getHeight());
            updateBackToTop.invoke(panels);
            layout(surface, Math.round(320 * density), Math.round(300 * density));
            Rect corner = bounds(surface, backToTop);
            check(corner.bottom >= surface.getHeight() - Math.round(16 * density),
                "back to top did not return to the corner after undo expired: " + corner.toShortString());
            scroll.scrollTo(0, 0);
            history.getClass().getMethod("add", String.class).invoke(history, target);
        } finally {
            seconds.setInt(service, previousSeconds);
            field(type, "clipboardUndoTexts").set(service, null);
            android.os.Handler main = (android.os.Handler) field(type, "main").get(service);
            main.removeCallbacks((Runnable) field(type, "clipboardUndoTick").get(service));
            method(panels.getClass(), "renderClipboardHistory").invoke(panels);
        }
    }

    /**
     * 单条删除也能撤销（#10080）：左滑露出的垃圾桶和长按操作行里的「删除」各删一次，浮动「撤销」都出现，点了恢复原来的时间戳和固定状态；到期后撤销不再出现，记录也不会回来。
     *
     * <p>两个入口都从真实渲染出的控件点下去，不直接调 `removeClipboardItem`，入口换成别的删除路径时这里会失败。被删的那条先固定，排在列表最前面，也顺带核对撤销保留了固定状态。
     */
    private static void checkClipboardSingleDeleteUndo(Class<?> type, Object service, Object panels, ViewGroup cards)
            throws Exception {
        Object history = field(type, "clipboardHistory").get(service);
        Method load = history.getClass().getMethod("load");
        Method setPinned = history.getClass().getMethod("setPinned", String.class, boolean.class);
        Method render = method(panels.getClass(), "renderClipboardHistory");
        Method hasUndo = method(type, "hasClipboardUndo");
        Field swiped = field(panels.getClass(), "clipboardSwipedText");
        Field managed = field(panels.getClass(), "clipboardActionText");
        Button undo = (Button) field(type, "clipboardUndoButton").get(service);
        String target = "合成剪贴板记录 5";
        try {
            setPinned.invoke(history, target, true);
            Object before = item(load.invoke(history), target);
            check(before != null && (Boolean) before.getClass().getMethod("pinned").invoke(before),
                "the synthetic clipboard record was not pinned before deletion");

            // 左滑垃圾桶：渲染时按 clipboardSwipedText 把这一条画成打开着，只有打开的那一格垃圾桶可见。
            swiped.set(panels, target);
            render.invoke(panels);
            View trash = descendant(cards, view -> view.getVisibility() == View.VISIBLE
                && describedAs(view, "删除这条剪贴板记录"));
            check(trash != null, "the swiped clipboard card shows no trash button");
            check(trash.performClick(), "clipboard trash did not handle the click");
            check(item(load.invoke(history), target) == null, "swipe deletion did not remove the record");
            check(undo.getVisibility() == View.VISIBLE, "clipboard undo did not appear after a swipe deletion");
            check(undo.performClick(), "clipboard undo did not handle the click after a swipe deletion");
            check(sameItem(before, item(load.invoke(history), target)),
                "undo after a swipe deletion did not restore the timestamp and pin");
            check(undo.getVisibility() == View.GONE && !(Boolean) hasUndo.invoke(service),
                "clipboard undo stayed after restoring a swipe deletion");

            // 长按后的操作行：渲染时按 clipboardActionText 在这一条下面画出操作行，点其中的「删除」。
            managed.set(panels, target);
            render.invoke(panels);
            View delete = actionRowDelete(cards);
            check(delete != null, "the clipboard action row has no delete button");
            check(delete.performClick(), "clipboard action row delete did not handle the click");
            check(item(load.invoke(history), target) == null, "action row deletion did not remove the record");
            check(undo.getVisibility() == View.VISIBLE, "clipboard undo did not appear after an action row deletion");
            check(undo.performClick(), "clipboard undo did not handle the click after an action row deletion");
            check(sameItem(before, item(load.invoke(history), target)),
                "undo after an action row deletion did not restore the timestamp and pin");
            check(undo.getVisibility() == View.GONE && !(Boolean) hasUndo.invoke(service),
                "clipboard undo stayed after restoring an action row deletion");

            // 到期：再删一次，把截止时刻改到过去后走一次倒数，按钮收起，记录不回来。
            managed.set(panels, target);
            render.invoke(panels);
            check(actionRowDelete(cards).performClick(), "clipboard action row delete did not handle the second click");
            check(undo.getVisibility() == View.VISIBLE, "clipboard undo did not appear after the second single deletion");
            field(type, "clipboardUndoDeadline").setLong(service, android.os.SystemClock.uptimeMillis() - 1);
            method(type, "tickClipboardUndo").invoke(service);
            check(undo.getVisibility() == View.GONE && !(Boolean) hasUndo.invoke(service),
                "clipboard undo did not disappear when a single deletion's countdown reached zero");
            check(item(load.invoke(history), target) == null, "an expired single-deletion undo restored the record");
        } finally {
            swiped.set(panels, null);
            managed.set(panels, null);
            field(type, "clipboardUndoTexts").set(service, null);
            android.os.Handler main = (android.os.Handler) field(type, "main").get(service);
            main.removeCallbacks((Runnable) field(type, "clipboardUndoTick").get(service));
            if (item(load.invoke(history), target) == null)
                history.getClass().getMethod("add", String.class).invoke(history, target);
            setPinned.invoke(history, target, false);
            render.invoke(panels);
        }
    }

    /** 操作行（读屏说明「剪贴板记录操作」）里的「删除」按钮；没有画出操作行时为 null。 */
    private static View actionRowDelete(ViewGroup cards) {
        View row = descendant(cards, view -> describedAs(view, "剪贴板记录操作"));
        if (!(row instanceof ViewGroup actions)) return null;
        return descendant(actions, view -> view instanceof Button button && "删除".contentEquals(button.getText()));
    }

    /** 历史里文字为 `text` 的那一条；没有时为 null。 */
    private static Object item(Object items, String text) throws Exception {
        for (Object item : (java.util.List<?>) items)
            if (text.equals(item.getClass().getMethod("text").invoke(item))) return item;
        return null;
    }

    /** 两条记录的文字、时间戳和固定状态都相同。 */
    private static boolean sameItem(Object expected, Object actual) throws Exception {
        if (expected == null || actual == null) return false;
        for (String getter : new String[] {"text", "timestamp", "pinned"}) {
            Method read = expected.getClass().getMethod(getter);
            if (!java.util.Objects.equals(read.invoke(expected), read.invoke(actual))) return false;
        }
        return true;
    }

    private static boolean containsText(Object items, String text) throws Exception {
        for (Object item : (java.util.List<?>) items)
            if (text.equals(item.getClass().getMethod("text").invoke(item))) return true;
        return false;
    }
    /** 无障碍说明等于 `text` 的视图；容器没有说明，`String.contentEquals(null)` 会抛空指针。 */
    private static boolean describedAs(View view, String text) {
        CharSequence description = view.getContentDescription();
        return description != null && text.contentEquals(description);
    }
    private static View descendant(ViewGroup root, java.util.function.Predicate<View> match) {
        for (int index = 0; index < root.getChildCount(); index++) {
            View child = root.getChildAt(index);
            if (match.test(child)) return child;
            if (child instanceof ViewGroup group) {
                View found = descendant(group, match);
                if (found != null) return found;
            }
        }
        return null;
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
