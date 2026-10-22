package app.msime.android;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.BatteryManager;
import android.os.PowerManager;
import app.msime.android.AndroidLocalSettings;
import app.msime.android.InputAssistance;
import app.msime.android.NativeClient;
import app.msime.android.policy.HostOptionsPolicy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** 系统按充电与空闲条件唤醒；共享层再次核验夜间、学习与无痕条件。 */
public final class NightLearningJobService extends JobService {
    private static final int JOB_ID=0x6d730701;
    private volatile boolean stopped;
    public static void schedule(Context context) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (!InputAssistance.nightEnabled(context)) { scheduler.cancel(JOB_ID); return; }
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_ID,new ComponentName(context,NightLearningJobService.class))
            .setRequiresCharging(true).setRequiresDeviceIdle(true).setRequiresBatteryNotLow(true)
            .setPeriodic(TimeUnit.HOURS.toMillis(1)).setPersisted(true).build());
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        stopped=false;
        if (!InputAssistance.nightEnabled(this) || LocalTime.now().getHour()>=6) return false;
        String day=LocalDate.now().toString();
        if (day.equals(getSharedPreferences(InputAssistance.STORE,0).getString("trained_day",""))) return false;
        new Thread(() -> {
            try {
                BatteryManager battery=getSystemService(BatteryManager.class);
                PowerManager power=getSystemService(PowerManager.class);
                JSONObject options=new JSONObject(HostOptionsPolicy.readRuntimeOptions(getFilesDir()));
                JSONObject preferenceEnvelope=new JSONObject(NativeClient.loadPreferences(options.optString("preferences_directory","")));
                JSONObject snapshot=Boolean.TRUE.equals(preferenceEnvelope.opt("ok")) ? preferenceEnvelope.optJSONObject("value") : null;
                if (snapshot == null || snapshot.optJSONObject("preferences") == null || stopped) return;
                options.put("preferences",snapshot.getJSONObject("preferences"));
                JSONObject action=new JSONObject().put("operation","night_learning")
                    .put("charging",battery != null && battery.isCharging()).put("idle",power != null && !power.isInteractive())
                    .put("local_hour",LocalTime.now().getHour())
                    .put("private",AndroidLocalSettings.load(this).bool(AndroidLocalSettings.INCOGNITO));
                JSONObject envelope=new JSONObject(NativeClient.dictionary(new JSONObject().put("options",options).put("action",action).toString()));
                JSONObject value=envelope.optJSONObject("value");
                if (!stopped && Boolean.TRUE.equals(envelope.opt("ok")) && value != null && "trained".equals(value.optString("status")))
                    getSharedPreferences(InputAssistance.STORE,0).edit().putString("trained_day",day).putString("status","最近完成："+day).apply();
            } catch (Exception | LinkageError unavailable) {
                // 没有可维护日志或引擎忙时保留下一次系统调度，不记录输入内容。
            } finally { if (!stopped) jobFinished(parameters,false); }
        },"msime-night-learning").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        stopped=true;
        new Thread(() -> {
            try {
                JSONObject options=new JSONObject(HostOptionsPolicy.readRuntimeOptions(getFilesDir()));
                NativeClient.dictionary(new JSONObject().put("options",options)
                    .put("action",new JSONObject().put("operation","cancel_night_learning")).toString());
            } catch (Exception | LinkageError unavailable) { /* 停止失败不重试本次任务。 */ }
        },"msime-night-learning-stop").start();
        return true;
    }
}
