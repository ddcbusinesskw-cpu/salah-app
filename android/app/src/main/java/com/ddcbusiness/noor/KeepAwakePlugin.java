package com.ddcbusiness.noor;

import android.view.WindowManager;
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
}
