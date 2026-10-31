package com.ddcbusiness.noor;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.util.HashMap;
import java.util.Map;

/**
 * أداة الشاشة الرئيسية «المجدّد — المواقيت».
 *
 * - البيانات: SharedPreferences «noor_widget» تكتبها الواجهة عبر {@link WidgetPlugin} (NoorWidget.update) كلّما
 *   أعادت حساب المواقيت (الفتح، العودة، تغيير المدينة/الطريقة/اللغة/نظام ١٢ ساعة، منتصف الليل). لا حساب فلكي هنا.
 * - الرسم: حجمان — صغير (٢×١: الصلاة القادمة ووقتها) ومتوسط (٤×٢: الصلوات الخمس + الهجري + المدينة). على أندرويد ١٢+
 *   تُختار التخطيطات بخريطة الأحجام (RemoteViews(Map<SizeF,RemoteViews>))؛ وقبله بعرض/ارتفاع الخلية من خيارات الأداة.
 * - التجديد: النظام كل ٣٠ دقيقة (updatePeriodMillis) + منبّه غير دقيق ولا يوقظ الجهاز (AlarmManager.set(RTC)) بعد
 *   الصلاة القادمة بدقيقة لينتقل التظليل؛ بلا إذن ولا WorkManager، ولا يعمل والهاتف نائم (يُرسم عند الاستيقاظ التالي).
 * - النقر يفتح التطبيق على شاشة المواقيت (extra noor_screen=times → MainActivity → go('times')).
 * - لم يُشغَّل التطبيق بعد (لا بيانات) → «افتح التطبيق لتحديث المواقيت».
 *
 * غائبة من التطبيق حتى يُطبَّق مقترح البيان (receiver + meta-data) — الصنف بلا تسجيل لا أثر له.
 */
public class PrayerWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_REFRESH = "com.ddcbusiness.noor.widget.REFRESH";
    public static final String EXTRA_SCREEN = "noor_screen";
    /** الحدّ الذي يبدأ عنده التخطيط المتوسط (dp) — خلية ٤×٢ على أغلب المشغّلات ≥ ٢٥٠×١١٠ */
    static final int MED_MIN_W = 220, MED_MIN_H = 100;
    static final int GOLD = 0xFFE3C88E, TEXT = 0xFFEEF0FA, MUTED = 0xFF9BA3C9;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        WidgetLogic.Data d = load(ctx);
        for (int id : ids) draw(ctx, mgr, id, d);
        scheduleRefresh(ctx, d);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle opts) {
        draw(ctx, mgr, id, load(ctx));
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) { refreshAll(ctx); return; }
        super.onReceive(ctx, intent);
    }

    @Override
    public void onDisabled(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(refreshIntent(ctx));
        } catch (Exception ignored) {}
    }

    /** إعادة رسم كل نسخ الأداة (يستدعيها WidgetPlugin بعد كل تحديث من الواجهة، والمنبّه الداخلي) */
    public static void refreshAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            if (mgr == null) return;
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, PrayerWidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            WidgetLogic.Data d = load(ctx);
            for (int id : ids) draw(ctx, mgr, id, d);
            scheduleRefresh(ctx, d);
        } catch (Exception ignored) {}
    }

    /** هل للمستخدم نسخة من الأداة على شاشته؟ (الواجهة تدفع البيانات في كل الأحوال — رخيصة) */
    public static boolean hasWidgets(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr == null ? null : mgr.getAppWidgetIds(new ComponentName(ctx, PrayerWidgetProvider.class));
            return ids != null && ids.length > 0;
        } catch (Exception e) { return false; }
    }

    static WidgetLogic.Data load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(WidgetLogic.PREFS, Context.MODE_PRIVATE);
        Map<String, ?> all;
        try { all = sp.getAll(); } catch (Exception e) { all = new HashMap<String, Object>(); }
        return WidgetLogic.read(all);
    }

    static void draw(Context ctx, AppWidgetManager mgr, int id, WidgetLogic.Data d) {
        try {
            RemoteViews views;
            if (Build.VERSION.SDK_INT >= 31) {
                Map<SizeF, RemoteViews> m = new HashMap<SizeF, RemoteViews>();
                m.put(new SizeF(110f, 40f), build(ctx, d, false));
                m.put(new SizeF((float) MED_MIN_W, (float) MED_MIN_H), build(ctx, d, true));
                views = new RemoteViews(m);
            } else {
                boolean med = false;
                try {
                    Bundle o = mgr.getAppWidgetOptions(id);
                    int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0), h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
                    med = w >= MED_MIN_W && h >= MED_MIN_H;
                } catch (Exception ignored) {}
                views = build(ctx, d, med);
            }
            mgr.updateAppWidget(id, views);
        } catch (Exception ignored) {}
    }

    /** بناء التخطيط (صغير/متوسط) من البيانات — كل النصوص بلغة الواجهة المخزّنة */
    static RemoteViews build(Context ctx, WidgetLogic.Data d, boolean medium) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), medium ? R.layout.widget_prayer_medium : R.layout.widget_prayer_small);
        String lang = d.lang;
        boolean rtl = "ar".equals(lang);
        if (Build.VERSION.SDK_INT >= 17) rv.setInt(R.id.wg_root, "setLayoutDirection", rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        rv.setOnClickPendingIntent(R.id.wg_root, openApp(ctx, "times"));

        long now = System.currentTimeMillis();
        boolean ok = d.valid();
        if (!ok) {
            rv.setTextViewText(R.id.wg_label, WidgetLogic.label("app", lang));
            rv.setTextViewText(R.id.wg_next, WidgetLogic.label("open", lang));
            rv.setTextViewText(R.id.wg_sub, "");
            if (medium) rv.setViewVisibility(R.id.wg_row, View.GONE);
            return rv;
        }
        int nowMin = WidgetLogic.nowMin(now, WidgetLogic.zone(d));
        WidgetLogic.Next n = WidgetLogic.next(d, nowMin);
        String nk = n != null ? n.key : "fajr";
        String nt = n != null ? WidgetLogic.time(d.t.get(nk), d.h12, lang) : "";
        rv.setTextViewText(R.id.wg_label, WidgetLogic.label("next", lang));
        rv.setTextViewText(R.id.wg_next, WidgetLogic.name(nk, lang) + "  " + nt);
        String sub = d.hijri;
        if (WidgetLogic.stale(d, now)) sub = WidgetLogic.label("open", lang);
        else if (medium && d.city.length() > 0) sub = d.hijri.length() > 0 ? d.hijri + " · " + d.city : d.city;
        rv.setTextViewText(R.id.wg_sub, sub);
        if (medium) {
            rv.setViewVisibility(R.id.wg_row, View.VISIBLE);
            int[] nameIds = { R.id.wg_n0, R.id.wg_n1, R.id.wg_n2, R.id.wg_n3, R.id.wg_n4 };
            int[] timeIds = { R.id.wg_t0, R.id.wg_t1, R.id.wg_t2, R.id.wg_t3, R.id.wg_t4 };
            for (int i = 0; i < 5; i++) {
                String k = WidgetLogic.ADHAN[i];
                boolean hi = k.equals(nk);
                rv.setTextViewText(nameIds[i], WidgetLogic.name(k, lang));
                rv.setTextViewText(timeIds[i], WidgetLogic.time(d.t.get(k), d.h12, lang));
                rv.setTextColor(nameIds[i], hi ? GOLD : MUTED);
                rv.setTextColor(timeIds[i], hi ? GOLD : TEXT);
            }
        }
        return rv;
    }

    /** فتح التطبيق على شاشة معيّنة: نفس آلية اختصارات التطبيق (extra noor_screen) */
    static PendingIntent openApp(Context ctx, String screen) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setAction(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.putExtra(EXTRA_SCREEN, screen);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(ctx, 3100, i, fl);
    }

    static PendingIntent refreshIntent(Context ctx) {
        Intent i = new Intent(ctx, PrayerWidgetProvider.class).setAction(ACTION_REFRESH);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, 3101, i, fl);
    }

    /** منبّه غير دقيق ولا يوقظ الجهاز بعد الصلاة القادمة بدقيقة — لا إذن مطلوب ورفيق بالبطارية */
    static void scheduleRefresh(Context ctx, WidgetLogic.Data d) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = refreshIntent(ctx);
            if (d == null || !d.valid()) { am.cancel(pi); return; }
            long at = WidgetLogic.nextRefreshAt(d, System.currentTimeMillis());
            am.set(AlarmManager.RTC, at, pi);
        } catch (Exception ignored) {}
    }
}
