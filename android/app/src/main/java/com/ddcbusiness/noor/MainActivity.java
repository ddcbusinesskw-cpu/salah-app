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
        /* ── 1. probe for missing runtime classes before registerPlugin ── */
        String diagError = null;
        String[] probeClasses = {
            "com.google.android.gms.auth.api.signin.GoogleSignInOptions",
            "com.google.android.gms.auth.api.signin.GoogleSignIn",
            "com.google.firebase.auth.FirebaseAuth"
        };
        for (String cls : probeClasses) {
            try {
                Class.forName(cls);
            } catch (Throwable t) {
                diagError = t.getClass().getSimpleName() + ": " + cls
                          + " | " + t.getMessage();
                Log.e(TAG, "Missing class: " + diagError);
                break;
            }
        }

        /* ── 2. registerPlugin with try/catch ── */
        try {
            registerPlugin(FirebaseAuthenticationPlugin.class);
        } catch (Throwable t) {
            String regErr = "registerPlugin threw " + t.getClass().getName()
                          + ": " + t.getMessage()
                          + " | cause: " + (t.getCause() != null ? t.getCause() : "none");
            Log.e(TAG, regErr, t);
            if (diagError == null) diagError = regErr;
        }

        super.onCreate(savedInstanceState);

        /* ── 3. surface error to WebView if any ── */
        if (diagError != null) {
            final String msg = diagError
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", " ")
                .replace("\r", "");
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        getBridge().getWebView().evaluateJavascript(
                            "(function(){" +
                            "var m='[java-init-error] " + msg + "';" +
                            "if(typeof _fbShowErr==='function'){" +
                            "  _fbShowErr({code:'java-init-error',message:m});" +
                            "}else{" +
                            "  var d=document.createElement('div');" +
                            "  d.setAttribute('style','position:fixed;top:0;left:0;right:0;" +
                            "z-index:99999;background:#b00;color:#fff;padding:14px;" +
                            "font-size:11px;word-break:break-all;direction:ltr;');" +
                            "  d.textContent=m;" +
                            "  document.body&&document.body.appendChild(d);" +
                            "}" +
                            "})()",
                            null
                        );
                    } catch (Throwable ignored) {
                        Log.e(TAG, "evaluateJavascript failed", ignored);
                    }
                }
            }, 2500);
        }
    }
}
