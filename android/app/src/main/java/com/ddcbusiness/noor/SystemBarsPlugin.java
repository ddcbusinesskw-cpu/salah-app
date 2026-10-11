package com.ddcbusiness.noor;

import android.graphics.Color;
import android.view.View;
import android.view.Window;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * لون ما خلف شريطي الحالة والتنقّل يتبع سِمة الواجهة (داكن/فاتح).
 * منذ targetSdk 36 الـWebView مُحاط بـpadding بقدر الأشرطة (MainActivity)،
 * فالمساحة خلفها تُظهر خلفية الحاوية — نلوّنها بلون --bg للسِمة الحالية،
 * ونضبط لون أيقونات الأشرطة (داكنة على الفاتح). على الإصدارات الأقدم غير
 * edge-to-edge نلوّن الأشرطة نفسها أيضاً.
 */
@CapacitorPlugin(name = "SystemBars")
public class SystemBarsPlugin extends Plugin {

    @PluginMethod
    public void setColors(PluginCall call) {
        final String bg = call.getString("bg");
        final boolean light = Boolean.TRUE.equals(call.getBoolean("light", false));
        final int color;
        try {
            color = Color.parseColor(bg);
        } catch (Exception e) {
            call.reject("bad-color");
            return;
        }
        getActivity().runOnUiThread(() -> {
            try {
                Window w = getActivity().getWindow();
                w.getDecorView().setBackgroundColor(color);
                View container = (View) getBridge().getWebView().getParent();
                if (container != null) container.setBackgroundColor(color);
                // بلا أثر حين يُفرض edge-to-edge (أندرويد 15+/16)، وفعّال على الأقدم
                w.setStatusBarColor(color);
                w.setNavigationBarColor(color);
                WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
                c.setAppearanceLightStatusBars(light);
                c.setAppearanceLightNavigationBars(light);
            } catch (Exception ignored) {}
            call.resolve();
        });
    }
}
