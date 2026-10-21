package app.msime.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/** 只接收授权后新到的短信，不查询历史收件箱。 */
public final class SmsCodeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction()) || !InputAssistance.smsEnabled(context)) return;
        SmsMessage[] messages=Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length > 16) return;
        StringBuilder text=new StringBuilder();
        for (SmsMessage message: messages) {
            String body=message.getMessageBody();
            if (body != null) text.append(body);
            if (text.length()>4096) return;
        }
        String body=text.toString();
        PendingResult pending=goAsync();
        new Thread(() -> {
            try { if (InputAssistance.smsEnabled(context)) InputAssistance.offer(context.getApplicationContext(),body,true); }
            finally { pending.finish(); }
        },"msime-sms-code").start();
    }
}
