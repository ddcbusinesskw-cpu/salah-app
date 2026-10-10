package com.ddcbusiness.noor;

import android.os.Bundle;
import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.getcapacitor.BridgeActivity;
import io.capawesome.capacitorjs.plugins.firebase.authentication.FirebaseAuthenticationPlugin;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FirebaseAuthenticationPlugin.class);
        registerPlugin(ProximitySensorPlugin.class);
        registerPlugin(AudioRecorderPlugin.class);
        registerPlugin(KeepAwakePlugin.class);
        registerPlugin(WhisperNativePlugin.class);
        super.onCreate(savedInstanceState);
        // API 36 / أندرويد 16: edge-to-edge إجباري (windowOptOutEdgeToEdgeEnforcement
        // يُتجاهَل) — نُبقي الـWebView بين شريط الحالة وشريط التنقّل.
        View wv = (View) getBridge().getWebView().getParent();
        ViewCompat.setOnApplyWindowInsetsListener(wv, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }
}
