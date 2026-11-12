package com.fanqie.focus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/**
 * 专注进行中的前台服务。
 *
 * 为什么需要它：番茄的进度是跟着 WebView 里的音轨走的，一旦 App 退到后台，
 * 荣耀这类机器的省电策略会很快把进程回收掉，音频断、计时也跟着丢。
 * 前台服务把进程提到「用户可见的服务」这一档，锁屏和切后台都不再被清。
 *
 * 顺带挂一个 MediaSession：媒体类前台服务在 Android 15 起要求能证明自己在放媒体，
 * 有活跃的 MediaSession 才能稳定保住这个类型。
 */
public class FocusService extends Service {

    private static final String TAG = "FocusService";
    private static final String CHANNEL_ID = "focus_running";
    private static final int NOTICE_ID = 4201;

    private static final String EXTRA_PLAYING = "playing";

    private MediaSession session;

    /** 服务是否在跑、上次的播放状态。授权通知权限后要用它重建一次通知。 */
    private static volatile boolean running = false;
    private static volatile boolean lastPlaying = true;

    // ---------------------------------------------------------------- 对外入口

    /**
     * 开始/更新专注状态。playing=false 表示已暂停，通知文案会跟着变，但服务不退出
     * （暂停期间用户随时会继续，反复起停服务反而容易被系统限流）。
     */
    public static void start(Context ctx, boolean playing) {
        if (ctx == null) return;
        lastPlaying = playing;
        Intent i = new Intent(ctx, FocusService.class);
        i.putExtra(EXTRA_PLAYING, playing);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            // 后台启动前台服务被系统拒绝时不要崩，最多是这次没保活
            android.util.Log.w(TAG, "start failed", e);
        }
    }

    /**
     * 用户刚授予通知权限时调一次：重发 startForeground 让常驻通知真正显示出来。
     * 服务没跑就什么也不做。
     */
    public static void refresh(Context ctx) {
        if (!running) return;
        start(ctx, lastPlaying);
    }

    /** 结束专注：停服务、撤通知。 */
    public static void stop(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.stopService(new Intent(ctx, FocusService.class));
        } catch (Exception e) {
            android.util.Log.w(TAG, "stop failed", e);
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean playing = intent == null || intent.getBooleanExtra(EXTRA_PLAYING, true);
        running = true;
        lastPlaying = playing;

        try {
            Notification n = buildNotification(playing);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTICE_ID, n);
            }
        } catch (Exception e) {
            // 起不来就立刻结束自己：startForegroundService() 之后 5 秒内没转成前台，
            // 系统会按 ANR 把整个 App 崩掉，stopSelf() 能避开这条路。
            // 代价只是这一次没保活，计时和音频照常在页面里跑。
            android.util.Log.w(TAG, "startForeground failed", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        updateSession(playing);
        // 不要系统自动重建：状态在页面里，重建一个空服务没有意义
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopForeground(true);
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (Exception ignored) {
            }
            session = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 通知与会话

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "专注进行中", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("专注计时和音轨播放期间常驻，避免被系统清理");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(boolean playing) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_stat_focus)
                .setContentTitle("番茄专注")
                .setContentText(playing ? "专注中 · 锁屏也会继续计时" : "已暂停 · 计时停在这里")
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_LOW);

        return b.build();
    }

    private void updateSession(boolean playing) {
        try {
            if (session == null) {
                session = new MediaSession(this, "PotatoFocus");
                session.setActive(true);
            }
            PlaybackState state = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(
                            playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                            1f)
                    .build();
            session.setPlaybackState(state);
        } catch (Exception e) {
            android.util.Log.w(TAG, "media session unavailable", e);
            session = null;
        }
    }
}
