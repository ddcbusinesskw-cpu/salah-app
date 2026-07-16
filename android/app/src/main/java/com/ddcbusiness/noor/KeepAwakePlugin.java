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

    /** استثناء تحسين البطارية (لضمان وصول الأذان على أجهزة OEM):
     *  حوار النظام ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — الاستخدام
     *  الفعلي لإذن REQUEST_IGNORE_BATTERY_OPTIMIZATIONS المعلَن بالمانيفست. */
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
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            r.put("granted", false);
            r.put("requested", true);
            call.resolve(r);
        } catch (Throwable t) {
            call.reject("batt-exempt: " + t.getMessage());
        }
    }
}
