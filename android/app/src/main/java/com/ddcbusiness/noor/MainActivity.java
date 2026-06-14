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

        /* ── 1. probe every relevant class before touching the plugin ── */
        String[] probes = {
            /* Firebase core */
            "com.google.firebase.auth.FirebaseAuth",
            /* play-services-auth — required by Google + PlayGames handlers */
            "com.google.android.gms.auth.api.signin.GoogleSignIn",
            "com.google.android.gms.auth.api.signin.GoogleSignInOptions",
            /* facebook-login — compileOnly by default, expected MISSING */
            "com.facebook.login.LoginManager",
            /* plugin classes */
            "io.capawesome.capacitorjs.plugins.firebase.authentication.FirebaseAuthenticationPlugin",
            "io.capawesome.capacitorjs.plugins.firebase.authentication.handlers.GoogleAuthProviderHandler",
            "io.capawesome.capacitorjs.plugins.firebase.authentication.handlers.FacebookAuthProviderHandler",
            "io.capawesome.capacitorjs.plugins.firebase.authentication.handlers.PlayGamesAuthProviderHandler",
            "io.capawesome.capacitorjs.plugins.firebase.authentication.handlers.OAuthProviderHandler",
            "io.capawesome.capacitorjs.plugins.firebase.authentication.handlers.PhoneAuthProviderHandler"
        };

        StringBuilder diag = new StringBuilder("[NoorDiag]\n");
        for (String cls : probes) {
            // shorten label for display
            String label = cls.substring(cls.lastIndexOf('.') + 1);
            try {
                Class.forName(cls);
                diag.append("OK   ").append(label).append("\n");
                Log.i(TAG, "OK: " + cls);
            } catch (ClassNotFoundException e) {
                diag.append("MISS ").append(label).append(" [ClassNotFound]\n");
                Log.e(TAG, "MISS(ClassNotFound): " + cls);
            } catch (Throwable t) {
                diag.append("MISS ").append(label).append(" [").append(t.getClass().getSimpleName()).append("]\n");
                Log.e(TAG, "MISS(" + t.getClass().getSimpleName() + "): " + cls);
            }
        }

        /* ── 2. registerPlugin with full Throwable capture ── */
        try {
            registerPlugin(FirebaseAuthenticationPlugin.class);
            diag.append("registerPlugin: OK\n");
        } catch (Throwable t) {
            String err = t.getClass().getName() + ": " + t.getMessage()
                       + " | cause: " + (t.getCause() != null ? t.getCause() : "none");
            diag.append("registerPlugin FAIL: ").append(err).append("\n");
            Log.e(TAG, "registerPlugin FAILED: " + err, t);
        }

        super.onCreate(savedInstanceState);

        /* ── 3. inject red banner directly — no dependency on index.html ── */
        final String diagFinal = diag.toString();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    // JSON-safe escaping for embedding in a JS double-quoted string
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
                    Log.i(TAG, "Banner injected: " + diagFinal);
                } catch (Throwable ex) {
                    Log.e(TAG, "Banner inject failed", ex);
                }
            }
        }, 3000);
    }
}
