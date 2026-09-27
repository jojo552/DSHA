package com.deepseekharness.app;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import com.deepseekharness.app.core.ClaudeSession;
import com.deepseekharness.app.ui.ClaudeActivity;
import com.deepseekharness.app.util.UiText;

/** 用户发起的 Claude 任务前台保活；系统回收后不自动重放请求。 */
public final class ClaudeService extends Service {
    private static final String CHANNEL = "dsha_claude";
    public static void start(Context context) {
        Intent intent = new Intent(context, ClaudeService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }
    public static void stop(Context context) { context.stopService(new Intent(context, ClaudeService.class)); }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Claude Code", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 1901, new Intent(this, ClaudeActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1902, new Intent(this, ClaudeService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Claude Code").setContentText(UiText.choose("任务正在运行，点击返回对话", "Task running. Tap to open conversation"))
                .setContentIntent(open).setOngoing(true).addAction(0, UiText.choose("停止", "Stop"), stop).build();
        startForeground(1901, notification);
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "stop".equals(intent.getAction())) ClaudeSession.get(this).stop();
        if (!ClaudeSession.get(this).busy()) stopSelf();
        return START_NOT_STICKY;
    }
    @Override public void onTaskRemoved(Intent intent) { ClaudeSession.get(this).stop(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
