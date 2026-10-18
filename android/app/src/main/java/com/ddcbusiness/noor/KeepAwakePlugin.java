package com.ddcbusiness.noor;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * KeepAwake — بديل أصلي موثوق لـ navigator.wakeLock (الغائب في كثير من
 * إصدارات WebView). يبقي الشاشة مضاءة عبر FLAG_KEEP_SCREEN_ON أثناء جلسات
 * تتبّع الصلاة / عدّاد الركعات / التسميع، ويُلغى عند الإيقاف.
 * FLAG_KEEP_SCREEN_ON يُرفع تلقائياً عند مغادرة النشاط فلا يعلق.
 */
@CapacitorPlugin(name = "KeepAwake")
public class KeepAwakePlugin extends Plugin {

    @PluginMethod
    public void keepAwake(PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            public void run() {
                getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
        call.resolve();
    }

    @PluginMethod
    public void allowSleep(PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            public void run() {
                getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
        call.resolve();
    }

    /** N37 — استثناء تحسين البطارية بلا طلب مباشر: سياسة Google Play تمنع ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     *  (وإذن REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) إلا لفئات محدّدة. الواجهة تشرح السبب أولاً (_battAsk في index.html)،
     *  ثم يفتح هذا قائمة «تحسين البطارية» في الإعدادات ليختار المستخدم «المجدّد» بنفسه؛ وإن منعها النظام فصفحة التطبيق.
     *  {granted:true} إن كان مستثنى أصلاً؛ وإلا {granted:false, opened:'list'|'app'} — الاسم كما هو كي تعمل الواجهة
     *  القديمة والجديدة معاً. */
    @PluginMethod
    public void requestBatteryExemption(PluginCall call) {
        try {
            String pkg = getContext().getPackageName();
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            JSObject r = new JSObject();
            if (pm != null && pm.isIgnoringBatteryOptimizations(pkg)) {
                r.put("granted", true);
                call.resolve(r);
                return;
            }
            r.put("granted", false);
            try {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
                r.put("opened", "list");
            } catch (Exception e) {
                Intent d = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
                d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(d);
                r.put("opened", "app");
            }
            call.resolve(r);
        } catch (Throwable t) {
            call.reject("batt-exempt: " + t.getMessage());
        }
    }
}
