package com.ddcbusiness.noor;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * NoorWidget — جسر صغير بين الواجهة وأداة الشاشة الرئيسية واختصارات التطبيق.
 *
 * update({times:{fajr,sunrise,dhuhr,asr,maghrib,isha}, prev:{maghrib,isha}, next, hijri, lang, city, tz, h12, day})
 *   تكتبه الواجهة (index.html: _wgPush) كلّما أعادت حساب المواقيت؛ يُخزَّن في SharedPreferences «noor_widget» ثم
 *   تُرسَم الأداة من جديد. لا يُخزَّن إلا ما يُعرض (مواقيت اليوم كنصوص، اسم المدينة، التاريخ الهجري، اللغة).
 * getLaunchRoute() → {screen} الشاشة التي طُلب فتحها من اختصار/أداة (extra noor_screen) عند الإطلاق البارد،
 *   وتُمسح بعد القراءة. أثناء التشغيل يصل الطلب حدثاً على window باسم «noorRoute» (MainActivity.onNewIntent).
 * hasWidgets() → {value} هل الأداة مضافة على الشاشة.
 */
@CapacitorPlugin(name = "NoorWidget")
public class WidgetPlugin extends Plugin {

    @PluginMethod
    public void update(PluginCall call) {
        try {
            Context ctx = getContext();
            SharedPreferences.Editor ed = ctx.getSharedPreferences(WidgetLogic.PREFS, Context.MODE_PRIVATE).edit();
            JSObject times = call.getObject("times", new JSObject());
            int n = 0;
            for (String k : WidgetLogic.PRAYERS) {
                String v = times.getString(k);
                if (WidgetLogic.hm(v) >= 0) { ed.putString("t_" + k, v.trim()); n++; } else ed.remove("t_" + k);
            }
            JSObject prev = call.getObject("prev", new JSObject());
            putOrRemove(ed, "p_maghrib", WidgetLogic.hm(prev.getString("maghrib")) >= 0 ? prev.getString("maghrib") : null);
            putOrRemove(ed, "p_isha", WidgetLogic.hm(prev.getString("isha")) >= 0 ? prev.getString("isha") : null);
            ed.putString("lang", WidgetLogic.langOf(call.getString("lang", "ar")));
            ed.putString("city", cut(call.getString("city", ""), 40));
            ed.putString("hijri", cut(call.getString("hijri", ""), 60));
            ed.putString("tz", cut(call.getString("tz", ""), 64));
            ed.putString("day", cut(call.getString("day", ""), 16));
            ed.putString("next", cut(call.getString("next", ""), 10));
            Boolean h12 = call.getBoolean("h12", Boolean.TRUE);
            ed.putString("h12", h12 != null && h12 ? "1" : "0");
            ed.putString("updatedAt", String.valueOf(System.currentTimeMillis()));
            ed.apply();
            PrayerWidgetProvider.refreshAll(ctx);
            JSObject r = new JSObject();
            r.put("stored", n);
            r.put("widgets", PrayerWidgetProvider.hasWidgets(ctx));
            call.resolve(r);
        } catch (Throwable t) {
            call.reject("widget-update: " + t.getMessage());
        }
    }

    @PluginMethod
    public void getLaunchRoute(PluginCall call) {
        JSObject r = new JSObject();
        String s = MainActivity.takePendingScreen();
        r.put("screen", s == null ? "" : s);
        call.resolve(r);
    }

    @PluginMethod
    public void hasWidgets(PluginCall call) {
        JSObject r = new JSObject();
        r.put("value", PrayerWidgetProvider.hasWidgets(getContext()));
        call.resolve(r);
    }

    /** مسح البيانات (عند تسجيل الخروج/حذف البيانات من الإعدادات إن أرادت الواجهة) */
    @PluginMethod
    public void clear(PluginCall call) {
        try {
            Context ctx = getContext();
            ctx.getSharedPreferences(WidgetLogic.PREFS, Context.MODE_PRIVATE).edit().clear().apply();
            PrayerWidgetProvider.refreshAll(ctx);
        } catch (Exception ignored) {}
        call.resolve();
    }

    /** شاشة الاختصار/الأداة من intent (null إن لم تكن قيمة معروفة) — تُستعمل من MainActivity */
    static String screenOf(Intent i) {
        if (i == null) return null;
        String s;
        try { s = i.getStringExtra(PrayerWidgetProvider.EXTRA_SCREEN); } catch (Exception e) { return null; }
        if (s == null) return null;
        s = s.trim();
        if (s.length() == 0 || s.length() > 24) return null;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_')) return null;
        }
        return s;
    }

    private static void putOrRemove(SharedPreferences.Editor ed, String k, String v) {
        if (v == null) ed.remove(k); else ed.putString(k, v.trim());
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
