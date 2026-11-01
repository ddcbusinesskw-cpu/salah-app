package com.ddcbusiness.noor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * خدمة أمامية (mediaPlayback) تشغّل الأذان الكامل من res/raw والتطبيق مغلق أو الهاتف مقفل.
 * يبدأها AdhanScheduler.onAlarm من المنبّه الدقيق (معفى من قيود بدء الخدمات الأمامية في الخلفية على أندرويد ١٢+).
 * تحتاج إعلاناً في البيان (proposals/adhan_manifest.diff): بدونه AdhanScheduler.serviceDeclared يعود false ويبقى الإشعار القصير.
 *
 *  - الصوت على مجرى المنبّه (USAGE_ALARM، يُسمع في «عدم الإزعاج» إلا بوضع «صمت تامّ») أو مجرى الإشعار (USAGE_NOTIFICATION،
 *    يخضع لعدم الإزعاج ولمستوى صوت الإشعارات) — إعداد «يُسمع عبر: المنبّه/الإشعار».
 *  - عدم الإزعاج: INTERRUPTION_FILTER_NONE → لا صوت؛ «المنبّهات فقط/الأولوية» ومجرى الإشعار → لا صوت؛ مجرى المنبّه مع
 *    «الأولوية» والمنبّهات غير مسموحة → لا صوت. مكالمة جارية → لا صوت. في كل هذه يبقى الإشعار «الأذان — الفجر».
 *  - تركيز صوتي مؤقّت (GAIN_TRANSIENT): تلاوة/موسيقى أخرى تُخفَض/تتوقّف وتعود بعد الأذان؛ فقدان دائم للتركيز → توقّف.
 *  - إشعار دائم بزرَّين «إيقاف» و«كتم اليوم» (النصوص بلغة الواجهة من الجسر)، وبعد الانتهاء يبقى إشعاراً عادياً بلا أزرار.
 *  - قفل استيقاظ جزئي ومهلة قصوى ٦ دقائق؛ ينتهي تلقائياً مع نهاية الملف.
 */
public class AdhanPlayerService extends Service {
    static final String ACTION_PLAY = "com.ddcbusiness.noor.ADHAN_PLAY";
    static final String ACTION_STOP = "com.ddcbusiness.noor.ADHAN_STOP";
    static final String ACTION_MUTE_TODAY = "com.ddcbusiness.noor.ADHAN_MUTE_TODAY";
    static final String X_ID = "id", X_SOUND = "sound", X_PRAYER = "prayer", X_TITLE = "title", X_BODY = "body", X_STREAM = "stream",
        X_STOP = "stop", X_MUTE = "mute", X_CH = "ch", X_CHD = "chd";
    static final String CHANNEL = "adhan_full";
    /** خارج مدى الجسر (2000–2199 أذان/تجديد، 3000+ ذِكر، 4000+ تذكيرات) فلا يُلغيه cancelAdhanNotifications */
    static final int NOTIF_ID = 2500;
    static final long MAX_MS = 6L * 60L * 1000L;

    private static volatile boolean playing = false;
    private static volatile String playingPrayer = "";

    private MediaPlayer mp;
    private AudioFocusRequest focusReq;
    private AudioManager.OnAudioFocusChangeListener focusL;
    private PowerManager.WakeLock wl;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable timeout = new Runnable() { public void run() { finish(true); } };
    private String title = "", body = "", stopL = "إيقاف", muteL = "كتم اليوم", chName = "الأذان الكامل", chDesc = "";

    static boolean isPlaying() { return playing; }
    static String playingPrayer() { return playingPrayer; }

    /** من الإضافة (الواجهة): أوقف إن كانت تعمل */
    static void stop(Context ctx) {
        if (!playing) return;
        try {
            Intent i = new Intent(ctx, AdhanPlayerService.class);
            i.setAction(ACTION_STOP);
            ctx.startService(i);
        } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(a)) { finish(false); return START_NOT_STICKY; }
        if (ACTION_MUTE_TODAY.equals(a)) { AdhanScheduler.muteToday(this); finish(false); return START_NOT_STICKY; }
        if (!ACTION_PLAY.equals(a)) { stopSelf(); return START_NOT_STICKY; }

        title = s(intent, X_TITLE, "🕌"); body = s(intent, X_BODY, "");
        stopL = s(intent, X_STOP, stopL); muteL = s(intent, X_MUTE, muteL); chName = s(intent, X_CH, chName); chDesc = s(intent, X_CHD, chDesc);
        String sound = s(intent, X_SOUND, AdhanLogic.DEFAULT_SOUND);
        String stream = AdhanLogic.normStream(s(intent, X_STREAM, AdhanLogic.STREAM_ALARM));
        playingPrayer = s(intent, X_PRAYER, "");

        ensureChannel();
        Notification n = build(true);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            /* نوع الخدمة/الإذن غائب (البيان القديم) → لا نستطيع العمل في الخلفية: إشعار عادي بلا صوت ثم خروج */
            try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, build(false)); } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }
        releasePlayer();
        if (!audioAllowed(stream)) { finish(true); return START_NOT_STICKY; }
        acquireWake();
        h.removeCallbacks(timeout);
        h.postDelayed(timeout, MAX_MS);
        if (!startPlayer(sound, stream)) finish(true);
        return START_NOT_STICKY;
    }

    private static String s(Intent i, String k, String d) { String v = i.getStringExtra(k); return v == null || v.length() == 0 ? d : v; }

    /** عدم الإزعاج ومكالمة جارية — قواعد أندرويد */
    private boolean audioAllowed(String stream) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            int mode = am.getMode();
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) return false;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || Build.VERSION.SDK_INT < 23) return true;
        int f;
        try { f = nm.getCurrentInterruptionFilter(); } catch (Exception e) { return true; }
        if (f == NotificationManager.INTERRUPTION_FILTER_NONE) return false;
        boolean alarm = AdhanLogic.STREAM_ALARM.equals(stream);
        if (f == NotificationManager.INTERRUPTION_FILTER_ALARMS) return alarm;
        if (f == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
            if (!alarm) return false;
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    NotificationManager.Policy p = nm.getNotificationPolicy();
                    return p == null || (p.priorityCategories & NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0;
                } catch (SecurityException e) { return true; } /* بلا إذن قراءة السياسة: المنبّهات مسموحة افتراضياً */
            }
        }
        return true;
    }

    private boolean startPlayer(String sound, String stream) {
        int resId = getResources().getIdentifier(AdhanLogic.isBundled(sound) ? sound : AdhanLogic.DEFAULT_SOUND, "raw", getPackageName());
        if (resId == 0) return false;
        boolean alarm = AdhanLogic.STREAM_ALARM.equals(stream);
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(alarm ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
        requestFocus(attrs, alarm);
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            AssetFileDescriptor fd = getResources().openRawResourceFd(resId);
            if (fd == null) return false;
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            mp.setLooping(false);
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { public void onCompletion(MediaPlayer m) { finish(true); } });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() { public boolean onError(MediaPlayer m, int w, int e) { finish(true); return true; } });
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer m) { try { m.start(); playing = true; } catch (Exception e) { finish(true); } }
            });
            mp.prepareAsync();
            playing = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void requestFocus(AudioAttributes attrs, boolean alarm) {
        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        focusL = new AudioManager.OnAudioFocusChangeListener() {
            public void onAudioFocusChange(int c) {
                /* الأذان أولويّة: لا يتوقّف إلا بفقدان دائم (مكالمة واردة مثلاً) */
                if (c == AudioManager.AUDIOFOCUS_LOSS) finish(true);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusReq = new AudioFocusRequest.Builder(alarm ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusL).build();
                am.requestAudioFocus(focusReq);
            } else {
                am.requestAudioFocus(focusL, alarm ? AudioManager.STREAM_ALARM : AudioManager.STREAM_NOTIFICATION, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Exception ignored) {}
    }

    private void abandonFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) { if (focusReq != null) am.abandonAudioFocusRequest(focusReq); }
            else if (focusL != null) am.abandonAudioFocus(focusL);
        } catch (Exception ignored) {}
        focusReq = null; focusL = null;
    }

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noor:adhan");
            wl.acquire(MAX_MS);
        } catch (Exception ignored) { wl = null; }
    }

    private void releasePlayer() {
        if (mp != null) { try { if (mp.isPlaying()) mp.stop(); } catch (Exception ignored) {} try { mp.release(); } catch (Exception ignored) {} mp = null; }
        playing = false;
    }

    /** keepNotice: الانتهاء الطبيعي/الصامت → يبقى إشعار عادي؛ إيقاف/كتم من المستخدم → يُزال الإشعار */
    private void finish(boolean keepNotice) {
        h.removeCallbacks(timeout);
        releasePlayer();
        abandonFocus();
        if (wl != null) { try { if (wl.isHeld()) wl.release(); } catch (Exception ignored) {} wl = null; }
        playingPrayer = "";
        try {
            if (keepNotice) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH);
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, build(false));
            } else {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            }
        } catch (Exception ignored) {}
        stopSelf();
    }

    @Override public void onDestroy() { h.removeCallbacks(timeout); releasePlayer(); abandonFocus(); if (wl != null) { try { if (wl.isHeld()) wl.release(); } catch (Exception ignored) {} } super.onDestroy(); }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        /* بلا صوت: الصوت من MediaPlayer. النداء المتكرّر يحدّث الاسم/الوصف بلغة الواجهة فقط */
        NotificationChannel ch = new NotificationChannel(CHANNEL, chName, NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setDescription(chDesc);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private int piFlags() { return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0); }

    private PendingIntent actionPI(String action, int rc) {
        Intent i = new Intent(this, AdhanPlayerService.class);
        i.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(this, rc, i, piFlags());
        return PendingIntent.getService(this, rc, i, piFlags());
    }

    private Notification build(boolean ongoing) {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_noor)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setShowWhen(true);
        if (open != null) nb.setContentIntent(PendingIntent.getActivity(this, NOTIF_ID, open, piFlags()));
        if (ongoing) {
            nb.addAction(0, stopL, actionPI(ACTION_STOP, NOTIF_ID + 1));
            nb.addAction(0, muteL, actionPI(ACTION_MUTE_TODAY, NOTIF_ID + 2));
        }
        return nb.build();
    }
}
