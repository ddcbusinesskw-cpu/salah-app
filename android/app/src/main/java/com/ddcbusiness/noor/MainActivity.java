package com.ddcbusiness.noor;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;
import io.capawesome.capacitorjs.plugins.firebase.authentication.FirebaseAuthenticationPlugin;

public class MainActivity extends BridgeActivity {

    // وقت آخر انهيار لمحرّك العرض (ثابت عبر إعادة إنشاء النشاط) — حارس حلقة الانهيار
    private static long lastRenderGoneAt = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FirebaseAuthenticationPlugin.class);
        registerPlugin(ProximitySensorPlugin.class);
        registerPlugin(AudioRecorderPlugin.class);
        registerPlugin(KeepAwakePlugin.class);
        registerPlugin(WhisperNativePlugin.class);
        registerPlugin(QuranAsrPlugin.class);
        registerPlugin(SystemBarsPlugin.class);
        registerPlugin(AppSettingsPlugin.class);
        super.onCreate(savedInstanceState);
        // N38: لوحة المفاتيح تُقصّر مساحة المحتوى بدل إزاحة النافذة (adjustPan) — مع edge-to-edge المفروض في أندرويد 15/16
        // لا يُعاد تحجيم النافذة تلقائياً، فتُطبَّق حافة لوحة المفاتيح (IME) أدناه حشوةً سفلية. يعادل
        // android:windowSoftInputMode="adjustResize" في البيان (مقترح منفصل) ويعمل بدونه.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | (getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE));
        // API 36 / أندرويد 16: edge-to-edge إجباري (windowOptOutEdgeToEdgeEnforcement
        // يُتجاهَل) — نُبقي الـWebView بين شريط الحالة وشريط التنقّل.
        View wv = (View) getBridge().getWebView().getParent();
        ViewCompat.setOnApplyWindowInsetsListener(wv, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            // N38: لوحة المفاتيح ظاهرة → الحافة السفلية = max(شريط التنقّل، لوحة المفاتيح): يصغر الـWebView فوقها
            // فلا تغطّي خانات الكتابة وأزرار الأوراق السفلية (خانة «إضافة تسبيحة» مثلاً). على أندرويد الأقدم
            // (بلا edge-to-edge) تصل الحافتان صفراً هنا لأن النافذة نفسها تُحجَّم — فلا ازدواج.
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(b.left, b.top, b.right, Math.max(b.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        // انهيار/قتل محرّك العرض: أعِد إنشاء النشاط بدل إغلاق التطبيق كله.
        // انهيار ثانٍ خلال ١٠ ثوانٍ = عطل متكرّر → السلوك الافتراضي (لا حلقة إعادة لا تنتهي).
        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                long now = SystemClock.elapsedRealtime();
                if (lastRenderGoneAt != 0 && now - lastRenderGoneAt < 10000L) return false;
                lastRenderGoneAt = now;
                runOnUiThread(MainActivity.this::recreate);
                return true;
            }
        });
    }
}
