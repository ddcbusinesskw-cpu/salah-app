package com.ddcbusiness.noor;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.getcapacitor.BridgeActivity;
import io.capawesome.capacitorjs.plugins.firebase.authentication.FirebaseAuthenticationPlugin;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "NoorBridge";

    @Override
    public void onCreate(Bundle savedInstanceState) {

        /* ── 1. class probes ── */
        String[] probes = {
            "com.google.firebase.auth.FirebaseAuth",
            "com.google.android.gms.auth.api.signin.GoogleSignIn",
            "com.google.android.gms.auth.api.signin.GoogleSignInOptions",
            "com.facebook.login.LoginManager",
            "io.capawesome.capacitorjs.plugins.firebase.authentication.FirebaseAuthenticationPlugin",
            "io.capawesome.capacitorjs.plugins.firebase.authentication.handlers.GoogleAuthProviderHandler",
            "io.capawesome.capacitorjs.plugins.firebase.authentication.handlers.FacebookAuthProviderHandler",
            "io.capawesome.capacitorjs.plugins.firebase.authentication.handlers.OAuthProviderHandler",
            "io.capawesome.capacitorjs.plugins.firebase.authentication.handlers.PhoneAuthProviderHandler"
        };

        final StringBuilder diag = new StringBuilder("[NoorDiag]\n");
        for (String cls : probes) {
            String label = cls.substring(cls.lastIndexOf('.') + 1);
            try {
                Class.forName(cls);
                diag.append("OK   ").append(label).append("\n");
                Log.i(TAG, "OK: " + cls);
            } catch (ClassNotFoundException e) {
                diag.append("MISS ").append(label).append(" [ClassNotFound]\n");
                Log.e(TAG, "MISS(ClassNotFound): " + cls);
            } catch (Throwable t) {
                diag.append("MISS ").append(label)
                    .append(" [").append(t.getClass().getSimpleName()).append("]\n");
                Log.e(TAG, "MISS: " + cls, t);
            }
        }

        /* ── 2. registerPlugin ── */
        try {
            registerPlugin(FirebaseAuthenticationPlugin.class);
            diag.append("registerPlugin: OK\n");
        } catch (Throwable t) {
            diag.append("registerPlugin FAIL: ")
                .append(t.getClass().getName()).append(": ").append(t.getMessage())
                .append(" | cause=").append(t.getCause()).append("\n");
            Log.e(TAG, "registerPlugin FAILED", t);
        }

        /* ── 3. bridge init — load() called here ── */
        try {
            super.onCreate(savedInstanceState);
            diag.append("super.onCreate: OK\n");
        } catch (Throwable t) {
            diag.append("super.onCreate THREW: ")
                .append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
            Log.e(TAG, "super.onCreate THREW", t);
        }

        /* ── 4. verify plugin in bridge ── */
        try {
            boolean found = getBridge().getPlugin("FirebaseAuthentication") != null;
            diag.append("InBridge: ").append(found ? "YES ✓" : "NO — load() failed silently").append("\n");
        } catch (Throwable t) {
            diag.append("getBridge check threw: ").append(t.getMessage()).append("\n");
        }

        /* ── 5. inject red banner ── */
        final String diagFinal = diag.toString();
        Log.i(TAG, diagFinal);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    String safe = diagFinal
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "")
                        .replace("\t", "  ");

                    String js =
                        "(function(){" +
                        "var d=document.createElement('div');" +
                        "d.style.cssText='position:fixed;top:0;left:0;right:0;" +
                        "z-index:2147483647;background:#c00;color:#fff;" +
                        "padding:10px;font-size:11px;font-family:monospace;" +
                        "white-space:pre-wrap;direction:ltr;';" +
                        "d.textContent=\"" + safe + "\";" +
                        "document.body.appendChild(d);" +
                        "})();";

                    getBridge().getWebView().evaluateJavascript(js, null);
                } catch (Throwable ex) {
                    Log.e(TAG, "Banner inject failed", ex);
                }
            }
        }, 3000);
    }
}
