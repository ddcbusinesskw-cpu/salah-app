package com.ddcbusiness.noor;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * AppSettings — أداتان صغيرتان للواجهة:
 *  open({target}): يفتح صفحة هذا التطبيق في إعدادات النظام (الأذونات: المايكروفون/الموقع/الإشعارات) حين رفض المستخدم
 *    إذناً ولم يعد النظام يعرض سؤاله. target='notifications' يفتح إعدادات إشعارات التطبيق مباشرة (أندرويد 8+).
 *    target='exactAlarm' يفتح «المنبّهات والتذكيرات» للتطبيق (أندرويد 12+) — للخطة ب في docs/N37_EXACT_ALARM_PLAN_B.md.
 *  network(): نوع الاتصال الحالي (wifi | cellular | none | other) وهل هو محسوب الكلفة — لتنبيه «أنت على بيانات الجوال»
 *    قبل تنزيل كبير. يحتاج إذن ACCESS_NETWORK_STATE (إذن عادي بلا سؤال)؛ بدونه يُعاد {known:false} والواجهة تعتمد
 *    navigator.connection كما كانت.
 */
@CapacitorPlugin(name = "AppSettings")
public class AppSettingsPlugin extends Plugin {

    @PluginMethod
    public void open(PluginCall call) {
        final String target = call.getString("target", "app");
        final Context ctx = getContext();
        final String pkg = ctx.getPackageName();
        getActivity().runOnUiThread(() -> {
            JSObject r = new JSObject();
            try {
                Intent i;
                String opened = "app";
                if ("notifications".equals(target) && Build.VERSION.SDK_INT >= 26) {
                    i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    i.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
                    opened = "notifications";
                } else if ("exactAlarm".equals(target) && Build.VERSION.SDK_INT >= 31) {
                    /* N37 الخطة ب (SCHEDULE_EXACT_ALARM): صفحة «المنبّهات والتذكيرات» لهذا التطبيق. في الخطة أ تُطلب فقط على
                       أندرويد 12/12L إن سحب المستخدم الإذن (معلَن هناك بـmaxSdkVersion=32)؛ على 13+ الإذن ممنوح دائماً فلا تُطلب.
                       على AOSP يُحَلّ النشاط ولا يرمي حتى لو لم يُعلَن الإذن (قد تُعرض الصفحة بمفتاح معطَّل) — الرجوع إلى صفحة
                       التطبيق أدناه شبكة أمان فقط للأنظمة المعدّلة التي لا تملك هذا النشاط (ActivityNotFoundException) */
                    i = new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM", Uri.fromParts("package", pkg, null));
                    opened = "exactAlarm";
                } else {
                    i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null));
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getActivity().startActivity(i);
                r.put("ok", true);
                r.put("target", opened);
                call.resolve(r);
                return;
            } catch (Exception e) {
                // بعض الأنظمة المعدّلة تمنع الصفحة المحدّدة — صفحة تفاصيل التطبيق ثم الإعدادات العامة
            }
            try {
                Intent d = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null));
                d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getActivity().startActivity(d);
                r.put("ok", true); r.put("target", "app");
                call.resolve(r);
            } catch (Exception e2) {
                try {
                    Intent s = new Intent(Settings.ACTION_SETTINGS);
                    s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getActivity().startActivity(s);
                    r.put("ok", true); r.put("target", "settings");
                    call.resolve(r);
                } catch (Exception e3) {
                    call.reject("no-settings: " + e3.getMessage());
                }
            }
        });
    }

    @PluginMethod
    public void network(PluginCall call) {
        JSObject r = new JSObject();
        try {
            Context ctx = getContext();
            if (ctx.checkCallingOrSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
                r.put("known", false); r.put("why", "no-permission");
                call.resolve(r);
                return;
            }
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) { r.put("known", false); call.resolve(r); return; }
            String type = "none";
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
                if (nc != null) {
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = "wifi";
                    else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = "cellular";
                    else type = "other";
                }
            } else {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.isConnected()) {
                    int t = ni.getType();
                    type = (t == ConnectivityManager.TYPE_WIFI || t == ConnectivityManager.TYPE_ETHERNET) ? "wifi"
                         : (t == ConnectivityManager.TYPE_MOBILE ? "cellular" : "other");
                }
            }
            r.put("known", true);
            r.put("type", type);
            r.put("metered", cm.isActiveNetworkMetered());
            call.resolve(r);
        } catch (Exception e) {
            r.put("known", false); r.put("why", String.valueOf(e.getMessage()));
            call.resolve(r);
        }
    }
}
