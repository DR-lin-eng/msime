package app.msime.android.test;

import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONObject;

/** 专用模拟器：在真实应用 UID 下验证夜间请求，并准备可恢复的短信和剪贴板开关。 */
public final class InputAssistancePreparation extends Instrumentation {
    private boolean restore;
    @Override public void onCreate(Bundle args) { restore=args != null && "true".equals(args.getString("restore")); start(); }
    private Class<?> nativeClass;
    private Context context;
    private JSONObject nativeValue(String method, String request) throws Exception {
        String response=(String)nativeClass.getMethod(method,String.class).invoke(null,request);
        JSONObject envelope=new JSONObject(response);
        if (!Boolean.TRUE.equals(envelope.opt("ok"))) throw new AssertionError("Native request failed: "+method);
        return envelope.getJSONObject("value");
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            context=getTargetContext();
            nativeClass=Class.forName("app.msime.android.NativeClient",true,context.getClassLoader());
            Class<?> settings=Class.forName("app.msime.android.AndroidLocalSettings",true,context.getClassLoader());
            File backup=new File(context.getCacheDir(),"input-assistance-test-before.json");
            JSONObject options=new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(),"runtime-options.json").toPath()),StandardCharsets.UTF_8));
            String preferencesDirectory=options.getString("preferences_directory");
            JSONObject snapshot=nativeValue("loadPreferences",preferencesDirectory);
            JSONObject prefs=snapshot.getJSONObject("preferences");
            String smsKey=(String)settings.getField("SMS_CODE_PROMPTS").get(null);
            String nightKey=(String)settings.getField("CHARGING_NIGHT_LEARNING").get(null);
            if (restore) {
                JSONObject before=new JSONObject(new String(Files.readAllBytes(backup.toPath()),StandardCharsets.UTF_8));
                settings.getMethod("put",Context.class,String.class,Object.class).invoke(null,context,smsKey,before.getBoolean("sms"));
                settings.getMethod("put",Context.class,String.class,Object.class).invoke(null,context,nightKey,before.getBoolean("night"));
                prefs.put("clipboard_history",before.getBoolean("clipboard"));
                Files.delete(backup.toPath());
            } else {
                Object current=settings.getMethod("load",Context.class).invoke(null,context);
                boolean sms=(Boolean)current.getClass().getMethod("bool",String.class).invoke(current,smsKey);
                boolean night=(Boolean)current.getClass().getMethod("bool",String.class).invoke(current,nightKey);
                Files.write(backup.toPath(),new JSONObject().put("sms",sms).put("night",night).put("clipboard",prefs.getBoolean("clipboard_history")).toString().getBytes(StandardCharsets.UTF_8));
                settings.getMethod("put",Context.class,String.class,Object.class).invoke(null,context,smsKey,true);
                settings.getMethod("put",Context.class,String.class,Object.class).invoke(null,context,nightKey,true);
                prefs.put("clipboard_history",true);
                Class.forName("app.msime.android.NightLearningJobService",true,context.getClassLoader()).getMethod("schedule",Context.class).invoke(null,context);
                boolean scheduled=false;
                for (JobInfo job:context.getSystemService(JobScheduler.class).getAllPendingJobs()) {
                    if (job.getService().getClassName().endsWith("NightLearningJobService")) scheduled=job.isRequireCharging() && job.isRequireDeviceIdle();
                }
                if (!scheduled) throw new AssertionError("Night learning charging/idle constraints not scheduled");
                verifyNight(options);
            }
            String saved=(String)nativeClass.getMethod("savePreferences",String.class,long.class,String.class)
                .invoke(null,preferencesDirectory,snapshot.getLong("revision"),snapshot.toString());
            if (!Boolean.TRUE.equals(new JSONObject(saved).opt("ok"))) throw new AssertionError("Preferences write failed");
            result.putString("stream","MSIME_INPUT_ASSISTANCE_PASSED: "+(restore?"restored":"night trained, cache persisted, gates and job constraints verified"));
            finish(-1,result);
        } catch (Throwable error) { result.putString("stream",error.getClass().getName()+": "+error.getMessage()); finish(0,result); }
    }
    private void verifyNight(JSONObject source) throws Exception {
        File fixture=new File(context.getCacheDir(),"night-learning-fixture");
        JSONObject options=new JSONObject(source.toString());
        for (String key:new String[]{"user_data","cache","dictionaries"}) {
            File folder=new File(fixture,key);if (!folder.isDirectory() && !folder.mkdirs()) throw new AssertionError("Fixture directory failed");
            options.put(key,folder.getAbsolutePath());
        }
        options.getJSONObject("preferences").put("learning",true);
        // 文件名从实际运行时词库目录的宿主约定读取，使用引擎固定 journal 名。
        File journal=new File(options.getString("user_data"),"msime_user.db");
        try (SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(journal,null)) {
            db.execSQL("CREATE TABLE IF NOT EXISTS personal_bigram(previous TEXT,word TEXT,count INTEGER,PRIMARY KEY(previous,word))");
            db.execSQL("CREATE TABLE IF NOT EXISTS personal_trigram(earlier TEXT,previous TEXT,word TEXT,count INTEGER,PRIMARY KEY(earlier,previous,word))");
            db.execSQL("INSERT OR REPLACE INTO personal_bigram VALUES('甲','丁',64),('乙','丙',64)");
        }
        for (boolean charging:new boolean[]{false,true}) {
            JSONObject action=new JSONObject().put("operation","night_learning").put("charging",charging).put("idle",true).put("local_hour",2);
            JSONObject value=nativeValue("dictionary",new JSONObject().put("options",options).put("action",action).toString());
            if (!(charging?"trained":"skipped").equals(value.getString("status"))) throw new AssertionError("Night charging gate mismatch");
        }
    }
}
