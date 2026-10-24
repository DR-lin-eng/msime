package app.msime.android;

/** 验证码只在内存保留五分钟，使用单调时钟，不保存短信正文。 */
public final class OtpSuggestion {
    public static final long LIFETIME_MS = 300_000;
    private String code;
    private long offeredAt;
    public synchronized void offer(String value, long now) {
        if (value == null || !value.matches("[0-9]{4,8}")) return;
        code = value; offeredAt = now;
    }
    public synchronized String code(long now) {
        if (now < offeredAt || now - offeredAt >= LIFETIME_MS) clear();
        return code;
    }
    public synchronized long remaining(long now) { return code(now) == null ? 0 : LIFETIME_MS - (now - offeredAt); }
    public synchronized void clear() { code = null; offeredAt = 0; }
}
