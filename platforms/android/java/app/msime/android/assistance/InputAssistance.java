package app.msime.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import app.msime.android.policy.HostOptionsPolicy;
import org.json.JSONObject;

/** 本机开关与系统能力适配；规则和验证码识别由共享 Rust 层提供。 */
public final class InputAssistance {
    public static final String STORE = "msime_input_assistance";
    public static final String SMS = "sms_code_enabled";
    public static final String NIGHT = "charging_night_learning";
    private static final OtpSuggestion suggestion = new OtpSuggestion();
    private static volatile Runnable listener;
    private static volatile boolean privateInput;
    private static volatile boolean fromSms;
    private InputAssistance() {}
    public static boolean nightEnabled(Context context) { return AndroidLocalSettings.load(context).bool(AndroidLocalSettings.CHARGING_NIGHT_LEARNING); }
    public static boolean smsEnabled(Context context) {
        return AndroidLocalSettings.load(context).bool(AndroidLocalSettings.SMS_CODE_PROMPTS)
            && context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }
    public static void smsEnabled(Context context, boolean enabled) {
        try { AndroidLocalSettings.put(context,AndroidLocalSettings.SMS_CODE_PROMPTS,enabled); }
        catch (java.io.IOException unavailable) { enabled=false; }
        if (!enabled) clear();
    }
    public static void setListener(Runnable value) { listener=value; }
    public static void privateInput(boolean blocked) { privateInput=blocked; if (blocked) clear(); }
    public static String code(Context context, boolean clipboardEnabled) {
        if (privateInput || (fromSms ? !smsEnabled(context) : !clipboardEnabled)) clear();
        return suggestion.code(SystemClock.elapsedRealtime());
    }
    public static long remaining() { return suggestion.remaining(SystemClock.elapsedRealtime()); }
    public static void clear() { suggestion.clear(); }

    /** 必须在工作线程调用；只把结果代码留在进程内存，不写日志、历史或学习记录。 */
    public static void offer(Context context, String message) { offer(context,message,false); }
    public static void offer(Context context, String message, boolean sms) {
        if (privateInput || message == null || message.length() > 4096) return;
        try {
            JSONObject options = new JSONObject(HostOptionsPolicy.readRuntimeOptions(context.getFilesDir()));
            JSONObject action = new JSONObject().put("operation","verification_code").put("text",message);
            JSONObject response = new JSONObject(NativeClient.dictionary(new JSONObject().put("options",options).put("action",action).toString()));
            if (!Boolean.TRUE.equals(response.opt("ok"))) return;
            JSONObject value=response.optJSONObject("value");
            String code=value == null || value.isNull("code") ? null : value.optString("code",null);
            if (privateInput || code == null) return;
            if (sms && !smsEnabled(context)) return;
            fromSms=sms;
            suggestion.offer(code,SystemClock.elapsedRealtime());
            new Handler(Looper.getMainLooper()).post(() -> { Runnable current=listener; if (current != null) current.run(); });
        } catch (Exception | LinkageError unavailable) { /* 缺配置或识别失败时保留正常输入。 */ }
    }
}
