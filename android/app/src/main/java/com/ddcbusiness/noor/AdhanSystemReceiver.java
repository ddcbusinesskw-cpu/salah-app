package com.ddcbusiness.noor;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;
import androidx.core.app.NotificationCompat;
import com.capacitorjs.plugins.localnotifications.LocalNotification;
import com.capacitorjs.plugins.localnotifications.LocalNotificationRestoreReceiver;
import com.capacitorjs.plugins.localnotifications.NotificationStorage;
import java.util.Date;
import org.json.JSONObject;

/**
 * N06 (اقتراح): يحلّ محلّ LocalNotificationRestoreReceiver التابع للإضافة (يُزال من الـmanifest المدمج بـ tools:node="remove")
 * ويستدعيه بنفسه، فيصير الترتيب مضموناً:
 *  - بعد الإقلاع: يحذف أولاً إشعارات الأذان (2000–2189) والتذكيرات (4000–4299) التي فات وقتها بأكثر من ١٠ دقائق، ثم يترك الإضافة تعيد تسليح الباقي.
 *    بدونه تُطلق الإضافة كل أذان فات والهاتف مطفأ دفعة واحدة بعد ١٥ ث من الإقلاع (حتى ~٥ أذانات لكل يوم إطفاء).
 *  - عند تغيّر المنطقة الزمنية (سفر) والتطبيق مغلق: الأذان مجدول بلحظات مطلقة لمدينة الجدولة ولا يمكن إعادة الحساب هنا
 *    (المحرّك في JS)، فنُنبّه بفتح التطبيق. النصّ بلغة الواجهة: يكتبه native-bridge.js في extra.nbTzB لإشعارات الأذان المخزّنة.
 * لا MY_PACKAGE_REPLACED: منبّهات AlarmManager تبقى بعد تحديث التطبيق، وإعادة التسليح هناك كانت تُطلق المتأخّر منها.
 */
public class AdhanSystemReceiver extends BroadcastReceiver {
    /** معرّف تنبيه المنطقة الزمنية: داخل مدى cancelAdhanNotifications (2000–2199) عمداً —
     *  فتح التطبيق وإعادة جدولة الأذان يزيلان هذا التنبيه تلقائياً. لا يستعمله الجسر لغير ذلك (الأذان 2000–2189، التجديد 2199). */
    static final int TZ_NOTICE_ID = 2198;
    static final int ADHAN_ID0 = 2000, ADHAN_ID_END = 2190; /* [2000, 2190) */
    /** fix2 lead: تذكيرات worship2 (أذكار الصباح/المساء، قبل الأذان، النوم، المراجعة، الورد، الكهف) — فائتها بعد إطفاء الهاتف لا يُطلق دفعة واحدة */
    static final int REM_ID0 = 4000, REM_ID_END = 4300; /* [4000, 4300) */
    static final long STALE_MS = 10L * 60L * 1000L;
    static final String AR_T = "🔄 المجدّد";
    static final String AR_B = "تغيّرت المنطقة الزمنية — افتح التطبيق لتحديث مواقيت الأذان لموقعك الجديد";
    static final String AR_C = "تجديد مواقيت الأذان";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        if (a == null) return;
        /* الأذان الكامل: منبّه AdhanScheduler الدقيق (explicit intent؛ لا يحتاج intent-filter ولا تغييراً في البيان) */
        if (AdhanScheduler.ACTION_ALARM.equals(a)) {
            try { AdhanScheduler.onAlarm(ctx, intent); } catch (Exception ignored) {}
            return;
        }
        if (Intent.ACTION_TIMEZONE_CHANGED.equals(a)) {
            if (unlocked(ctx)) tzNotice(ctx);
            return;
        }
        /* LOCKED_BOOT_COMPLETED / BOOT_COMPLETED / QUICKBOOT_POWERON — نفس أفعال مستقبِل الإضافة */
        if (unlocked(ctx)) {
            try { pruneStaleAdhan(ctx); } catch (Exception ignored) {}
        }
        /* الأذان الكامل: منبّهات AlarmManager لا تصمد لإعادة التشغيل — تُعاد من مخزن AdhanScheduler (محميّ بالجهاز، يُقرأ قبل فتح القفل)
           بعد حذف الفائت منها */
        try { AdhanScheduler.rearm(ctx); } catch (Exception ignored) {}
        try { new LocalNotificationRestoreReceiver().onReceive(ctx, intent); } catch (Exception ignored) {}
    }

    /** مخزن الإضافة (SharedPreferences) محميّ ببيانات الاعتماد: لا يُقرأ قبل فتح القفل الأول */
    private static boolean unlocked(Context ctx) {
        if (Build.VERSION.SDK_INT < 24) return true;
        UserManager um = ctx.getSystemService(UserManager.class);
        return um != null && um.isUserUnlocked();
    }

    /** أذان أو تذكير فات وقته بأكثر من ١٠ دقائق لا قيمة لإطلاقه الآن */
    static int pruneStaleAdhan(Context ctx) {
        NotificationStorage st = new NotificationStorage(ctx);
        long cut = System.currentTimeMillis() - STALE_MS;
        int n = 0;
        for (String id : st.getSavedNotificationIds()) {
            int iid;
            try { iid = Integer.parseInt(id); } catch (NumberFormatException e) { continue; }
            boolean adhan = iid >= ADHAN_ID0 && iid < ADHAN_ID_END, rem = iid >= REM_ID0 && iid < REM_ID_END;
            if (!adhan && !rem) continue;
            LocalNotification ln = st.getSavedNotification(id);
            if (ln == null || ln.getSchedule() == null) continue;
            Date at = ln.getSchedule().getAt();
            if (at != null && at.getTime() < cut) { st.deleteNotification(id); n++; }
        }
        return n;
    }

    /** أول إشعار أذان مخزّن يحمل extra.nbTzB (نصّ المنطقة الزمنية بلغة الواجهة) — أو null إن لا أذان مجدول */
    private static JSONObject storedAdhanExtra(Context ctx, boolean[] any) {
        NotificationStorage st = new NotificationStorage(ctx);
        JSONObject found = null;
        for (String id : st.getSavedNotificationIds()) {
            int iid;
            try { iid = Integer.parseInt(id); } catch (NumberFormatException e) { continue; }
            if (iid < ADHAN_ID0 || iid >= ADHAN_ID_END) continue;
            any[0] = true;
            JSONObject o = st.getSavedNotificationAsJSObject(id);
            JSONObject ex = o != null ? o.optJSONObject("extra") : null;
            if (ex != null && ex.optString("nbTzB", "").length() > 0) { found = ex; break; }
        }
        return found;
    }

    private static void tzNotice(Context ctx) {
        boolean[] any = { false };
        JSONObject ex = storedAdhanExtra(ctx, any);
        /* الأذان الكامل: الأذانات في مخزن AdhanScheduler لا في مخزن الإضافة — والنصوص بلغة الواجهة هناك أيضاً */
        boolean full = AdhanScheduler.hasItems(ctx);
        if (!any[0] && !full) return; /* الأذان غير مفعّل (لا إشعارات أذان مخزّنة) */
        String t = ex != null ? ex.optString("nbTzT", AR_T) : AdhanScheduler.prefs(ctx).getString(AdhanScheduler.K_TZT, AR_T);
        String b = ex != null ? ex.optString("nbTzB", AR_B) : AdhanScheduler.prefs(ctx).getString(AdhanScheduler.K_TZB, AR_B);
        String c = ex != null ? ex.optString("nbTzC", AR_C) : AdhanScheduler.prefs(ctx).getString(AdhanScheduler.K_TZC, AR_C);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        /* القناة ينشئها الجسر عند كل فتح باسم لغة الواجهة؛ هنا احتياط فقط إن لم توجد */
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("adhan_renew") == null) {
            nm.createNotificationChannel(new NotificationChannel("adhan_renew", c, NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = open != null ? PendingIntent.getActivity(ctx, TZ_NOTICE_ID, open, fl) : null;
        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, "adhan_renew")
            .setSmallIcon(R.drawable.ic_stat_noor)
            .setContentTitle(t)
            .setContentText(b)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(b))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true);
        if (pi != null) nb.setContentIntent(pi);
        try { nm.notify(TZ_NOTICE_ID, nb.build()); } catch (SecurityException ignored) {}
    }
}
