package com.ddcbusiness.noor;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import java.util.List;
import java.util.TimeZone;

/**
 * الأذان الكامل والتطبيق مغلق — المنبّهات الدقيقة ومخزنها (الجانب الأصلي من AdhanLogic).
 *
 * الجسر (native-bridge.js → NoorAdhan.schedule) يرسل قائمة الأذانات القادمة (≤ ١٦٠) مع العناوين بلغة الواجهة؛ تُخزَّن هنا
 * في SharedPreferences «noor_adhan» (مخزن محميّ بالجهاز على أندرويد ٧+ كي يُقرأ بعد الإقلاع قبل فتح القفل) ويُضبط لكل
 * واحدة منبّه AlarmManager دقيق موقظ (setExactAndAllowWhileIdle — نفس ما تفعله إضافة الإشعارات للأذان اليوم) يُرسَل
 * إلى AdhanSystemReceiver (المعلَن أصلاً في البيان) بالفعل ADHAN_ALARM، فيقرّر onAlarm:
 *  PLAY  → تشغيل AdhanPlayerService (خدمة أمامية mediaPlayback) بالصوت المختار؛ إن تعذّر (الخدمة غير معلَنة في البيان
 *          بعد، أو منع النظام بدء الخدمة) → إشعار قصير على قناة «adhan» بالنغمة القصيرة (سلوك اليوم).
 *  SHORT → «كتم اليوم» فعّال: الإشعار القصير فقط.
 *  SKIP_* → فائت/مكرَّر/مبكر: لا شيء.
 * «لا يُؤذَّن مرتين للصلاة نفسها»: مفتاح الصلاة@اليوم يُخزَّن بعد كل تشغيل ويُقارَن قبل أي تشغيل.
 * بعد الإقلاع تُعاد منبّهات المخزن (rearm) بعد حذف الفائت — منبّهات AlarmManager لا تصمد لإعادة التشغيل.
 */
final class AdhanScheduler {
    private AdhanScheduler() {}

    static final String PREFS = "noor_adhan";
    static final String ACTION_ALARM = "com.ddcbusiness.noor.ADHAN_ALARM";
    static final String K_ITEMS = "items", K_LAST = "last_key", K_MUTE = "mute_until", K_STREAM = "stream",
        K_STOP = "l_stop", K_MUTE_L = "l_mute", K_CH = "l_ch", K_CHD = "l_chd", K_TZT = "nbTzT", K_TZB = "nbTzB", K_TZC = "nbTzC";
    /** معرّف الإشعار القصير الاحتياطي = معرّف الأذان نفسه (2000–2189، داخل مدى إلغاء الجسر فيُنظَّف مع إعادة الجدولة) */
    static final String SHORT_CHANNEL = "adhan";

    static SharedPreferences prefs(Context ctx) {
        Context c = ctx;
        if (Build.VERSION.SDK_INT >= 24) {
            try { c = ctx.createDeviceProtectedStorageContext(); } catch (Exception ignored) { c = ctx; }
        }
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** الخدمة معلَنة في البيان (مقترح proposals/adhan_manifest.diff) وأذونات الخدمة الأمامية موجودة */
    static boolean serviceDeclared(Context ctx) {
        try {
            ctx.getPackageManager().getServiceInfo(new ComponentName(ctx, AdhanPlayerService.class), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 28 && !hasPerm(ctx, "android.permission.FOREGROUND_SERVICE")) return false;
        if (Build.VERSION.SDK_INT >= 34 && !hasPerm(ctx, "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK")) return false;
        return true;
    }

    private static boolean hasPerm(Context ctx, String p) {
        try { return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; } catch (Exception e) { return false; }
    }

    static boolean canExact(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        if (Build.VERSION.SDK_INT >= 31) { try { return am.canScheduleExactAlarms(); } catch (Exception e) { return false; } }
        return true;
    }

    /** الجسر: قائمة جديدة كاملة — ما لم يعد فيها يُلغى، والباقي يُضبط من جديد (FLAG_UPDATE_CURRENT) */
    static int schedule(Context ctx, List<AdhanLogic.Item> items, String stream, String stop, String mute, String chName, String chDesc,
                        String tzT, String tzB, String tzC) {
        SharedPreferences p = prefs(ctx);
        List<AdhanLogic.Item> old = AdhanLogic.decodeAll(p.getString(K_ITEMS, ""));
        List<AdhanLogic.Item> now = AdhanLogic.prune(items, System.currentTimeMillis());
        for (int id : AdhanLogic.removedIds(old, now)) cancelAlarm(ctx, id);
        SharedPreferences.Editor ed = p.edit();
        ed.putString(K_ITEMS, AdhanLogic.encodeAll(now));
        ed.putString(K_STREAM, AdhanLogic.normStream(stream));
        if (stop != null) ed.putString(K_STOP, AdhanLogic.clean(stop));
        if (mute != null) ed.putString(K_MUTE_L, AdhanLogic.clean(mute));
        if (chName != null) ed.putString(K_CH, AdhanLogic.clean(chName));
        if (chDesc != null) ed.putString(K_CHD, AdhanLogic.clean(chDesc));
        if (tzT != null) ed.putString(K_TZT, AdhanLogic.clean(tzT));
        if (tzB != null) ed.putString(K_TZB, AdhanLogic.clean(tzB));
        if (tzC != null) ed.putString(K_TZC, AdhanLogic.clean(tzC));
        ed.apply();
        int n = 0;
        for (AdhanLogic.Item it : now) if (arm(ctx, it)) n++;
        return n;
    }

    static void cancelAll(Context ctx) {
        SharedPreferences p = prefs(ctx);
        for (AdhanLogic.Item it : AdhanLogic.decodeAll(p.getString(K_ITEMS, ""))) cancelAlarm(ctx, it.id);
        p.edit().remove(K_ITEMS).apply();
    }

    /** بعد الإقلاع: الفائت يُحذف والباقي يُضبط من جديد */
    static int rearm(Context ctx) {
        SharedPreferences p = prefs(ctx);
        List<AdhanLogic.Item> now = AdhanLogic.prune(AdhanLogic.decodeAll(p.getString(K_ITEMS, "")), System.currentTimeMillis());
        p.edit().putString(K_ITEMS, AdhanLogic.encodeAll(now)).apply();
        int n = 0;
        for (AdhanLogic.Item it : now) if (arm(ctx, it)) n++;
        return n;
    }

    static boolean hasItems(Context ctx) {
        try { return prefs(ctx).getString(K_ITEMS, "").length() > 0; } catch (Exception e) { return false; }
    }

    static int pendingCount(Context ctx) {
        try { return AdhanLogic.prune(AdhanLogic.decodeAll(prefs(ctx).getString(K_ITEMS, "")), System.currentTimeMillis()).size(); }
        catch (Exception e) { return 0; }
    }

    static long muteUntil(Context ctx) { return prefs(ctx).getLong(K_MUTE, 0L); }

    static void muteToday(Context ctx) {
        prefs(ctx).edit().putLong(K_MUTE, AdhanLogic.nextMidnight(System.currentTimeMillis(), TimeZone.getDefault())).apply();
    }

    static void unmute(Context ctx) { prefs(ctx).edit().remove(K_MUTE).apply(); }

    static String lastKey(Context ctx) { return prefs(ctx).getString(K_LAST, ""); }

    private static Intent alarmIntent(Context ctx, int id) {
        Intent i = new Intent(ctx, AdhanSystemReceiver.class);
        i.setAction(ACTION_ALARM);
        /* data فريدة لكل معرّف: لا تصادم مع PendingIntent الإضافة (مكوّن آخر) ولا بين الأذانات */
        i.setData(Uri.parse("noor-adhan://alarm/" + id));
        i.putExtra("id", id);
        return i;
    }

    private static int piFlags(boolean cancel) {
        int f = cancel ? PendingIntent.FLAG_CANCEL_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    static boolean arm(Context ctx, AdhanLogic.Item it) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, it.id, alarmIntent(ctx, it.id), piFlags(false));
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                /* أندرويد ١٢/١٢L بلا إذن المنبّه الدقيق: غير دقيق لكنه موقظ — كإضافة الإشعارات */
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, it.at, pi);
            }
            return true;
        } catch (SecurityException e) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at, pi); return true; } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        return false;
    }

    static void cancelAlarm(Context ctx, int id) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, id, alarmIntent(ctx, id), piFlags(false));
            if (am != null) am.cancel(pi);
            pi.cancel();
        } catch (Exception ignored) {}
    }

    /** المنبّه رنّ (AdhanSystemReceiver) */
    static AdhanLogic.Decision onAlarm(Context ctx, Intent intent) {
        int id = intent.getIntExtra("id", -1);
        if (id < 0 && intent.getData() != null) {
            try { id = Integer.parseInt(intent.getData().getLastPathSegment()); } catch (Exception ignored) {}
        }
        SharedPreferences p = prefs(ctx);
        List<AdhanLogic.Item> items = AdhanLogic.decodeAll(p.getString(K_ITEMS, ""));
        AdhanLogic.Item it = AdhanLogic.find(items, id);
        long now = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        AdhanLogic.Decision d = AdhanLogic.decide(it, now, p.getString(K_LAST, ""), p.getLong(K_MUTE, 0L), tz);
        if (it != null && d != AdhanLogic.Decision.SKIP_EARLY) {
            /* استُهلك: يُحذف من المخزن (المبكر يبقى — المنبّه نفسه سيُعاد ضبطه من الجسر عند الفتح) */
            items.remove(it);
            p.edit().putString(K_ITEMS, AdhanLogic.encodeAll(items)).apply();
        }
        if (d == AdhanLogic.Decision.PLAY || d == AdhanLogic.Decision.SHORT) {
            p.edit().putString(K_LAST, AdhanLogic.playKey(it.prayer, it.at, tz)).apply();
        }
        if (d == AdhanLogic.Decision.PLAY) {
            if (!startPlayer(ctx, it, p)) shortNotify(ctx, it);
        } else if (d == AdhanLogic.Decision.SHORT) {
            shortNotify(ctx, it);
        }
        return d;
    }

    private static boolean startPlayer(Context ctx, AdhanLogic.Item it, SharedPreferences p) {
        if (!serviceDeclared(ctx)) return false;
        try {
            Intent s = new Intent(ctx, AdhanPlayerService.class);
            s.setAction(AdhanPlayerService.ACTION_PLAY);
            s.putExtra(AdhanPlayerService.X_ID, it.id);
            s.putExtra(AdhanPlayerService.X_SOUND, it.sound);
            s.putExtra(AdhanPlayerService.X_PRAYER, it.prayer);
            s.putExtra(AdhanPlayerService.X_TITLE, it.title);
            s.putExtra(AdhanPlayerService.X_BODY, it.body);
            s.putExtra(AdhanPlayerService.X_STREAM, p.getString(K_STREAM, AdhanLogic.STREAM_ALARM));
            s.putExtra(AdhanPlayerService.X_STOP, p.getString(K_STOP, "إيقاف"));
            s.putExtra(AdhanPlayerService.X_MUTE, p.getString(K_MUTE_L, "كتم اليوم"));
            s.putExtra(AdhanPlayerService.X_CH, p.getString(K_CH, "الأذان الكامل"));
            s.putExtra(AdhanPlayerService.X_CHD, p.getString(K_CHD, "تشغيل الأذان كاملاً عند دخول وقت الصلاة"));
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(s); else ctx.startService(s);
            return true;
        } catch (Exception e) {
            /* ForegroundServiceStartNotAllowedException وأمثالها → النغمة القصيرة */
            return false;
        }
    }

    /** سلوك اليوم: إشعار على قناة «adhan» (ينشئها الجسر بصوت adhan_alert.wav؛ احتياطاً تُنشأ هنا بالصوت نفسه) */
    static void shortNotify(Context ctx, AdhanLogic.Item it) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Uri snd = Uri.parse("android.resource://" + ctx.getPackageName() + "/raw/adhan_alert");
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(SHORT_CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(SHORT_CHANNEL, "أذان الصلاة", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(snd, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        PendingIntent pi = open != null ? PendingIntent.getActivity(ctx, it.id, open, piFlags(false)) : null;
        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, SHORT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_noor)
            .setContentTitle(it.title.length() > 0 ? it.title : "🕌")
            .setContentText(it.body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(snd)
            .setAutoCancel(true);
        if (pi != null) nb.setContentIntent(pi);
        try { nm.notify(it.id, nb.build()); } catch (SecurityException ignored) {}
    }
}
