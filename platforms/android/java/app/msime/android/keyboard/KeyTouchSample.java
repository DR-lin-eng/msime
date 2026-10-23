package app.msime.android;

import android.view.MotionEvent;
import android.view.View;
import java.util.Arrays;
import java.util.List;

/** 一次离散字母按下的瞬时样本；两个方向统一以命中键宽归一化。 */
final class KeyTouchSample {
    final View key;
    final float x;
    final float y;
    final float[] centers;

    private KeyTouchSample(View key, float x, float y, float[] centers) {
        this.key = key;
        this.x = x;
        this.y = y;
        this.centers = centers;
    }

    static KeyTouchSample capture(View key, MotionEvent event,
            List<ShuangpinHintButton> keys, List<String> inputs) {
        float width = key.getWidth();
        if (width <= 0 || key.getHeight() <= 0 || keys.size() != inputs.size()) return null;
        int[] origin = new int[2];
        key.getLocationInWindow(origin);
        float[] location = {event.getX(), event.getY()};
        KeyboardKeyArea.restoreOriginalTouch(key, event.getPointerId(0), location);
        float x = (location[0] - width / 2f) / width;
        float y = (location[1] - key.getHeight() / 2f) / width;
        if (!Float.isFinite(x) || !Float.isFinite(y) || Math.abs(x) > 2 || Math.abs(y) > 2) return null;
        float[] centers = new float[52];
        Arrays.fill(centers, Float.NaN);
        int[] position = new int[2];
        for (int index = 0; index < keys.size(); index++) {
            View neighbor = keys.get(index);
            String input = inputs.get(index);
            if (input.length() != 1 || input.charAt(0) < 'a' || input.charAt(0) > 'z'
                    || !neighbor.isShown() || neighbor.getWidth() <= 0 || neighbor.getHeight() <= 0) continue;
            neighbor.getLocationInWindow(position);
            int offset = (input.charAt(0) - 'a') * 2;
            float cx = (position[0] + neighbor.getWidth() / 2f - origin[0] - width / 2f) / width;
            float cy = (position[1] + neighbor.getHeight() / 2f - origin[1] - key.getHeight() / 2f) / width;
            // 分离式布局可重复 G 键；同一字母只取离命中键最近的那一份。
            if (Float.isNaN(centers[offset]) || Math.hypot(cx, cy) < Math.hypot(centers[offset], centers[offset + 1])) {
                centers[offset] = cx;
                centers[offset + 1] = cy;
            }
        }
        return new KeyTouchSample(key, x, y, centers);
    }

    boolean accompaniesTouchClick() {
        return key instanceof KeyboardPressButton button && button.touchDownFeedbackPlayed();
    }
}
