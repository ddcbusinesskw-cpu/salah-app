package com.ddcbusiness.noor;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.getcapacitor.BridgeActivity;
import io.capawesome.capacitorjs.plugins.firebase.authentication.FirebaseAuthenticationPlugin;
import java.io.BufferedReader;
import java.io.InputStreamReader;

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

        /* ── 3. bridge init — load() happens here ── */
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
            diag.append("InBridge: ").append(found ? "YES" : "NO—load() silently failed").append("\n");
        } catch (Throwable t) {
            diag.append("getBridge check threw: ").append(t.getMessage()).append("\n");
        }

        Log.i(TAG, diag.toString());

        /* ── 5. read logcat then inject banner ── */
        final String diagSnapshot = diag.toString();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String logLines = captureLogcat();
                        final String full = diagSnapshot + "\n[logcat]\n" + logLines;
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                injectBanner(full);
                            }
                        });
                    }
                }).start();
            }
        }, 4000);
    }

    private String captureLogcat() {
        StringBuilder out = new StringBuilder();
        int lines = 0;
        int trailCount = 0;
        try {
            int pid = android.os.Process.myPid();
            Process proc = Runtime.getRuntime().exec(
                new String[]{"logcat", "-d", "-v", "brief", "--pid=" + pid}
            );
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream())
            );
            String line;
            while ((line = reader.readLine()) != null && lines < 80) {
                boolean match =
                    line.contains("FirebaseAuthentication") ||
                    line.contains("NoorBridge") ||
                    line.contains("Capacitor") ||
                    line.contains("Unable to load") ||
                    line.contains("loadPlugin") ||
                    line.contains("Exception") ||
                    line.contains("Caused by") ||
                    line.contains("firebase") ||
                    (trailCount > 0 && (line.startsWith("\tat ") || line.startsWith("    at ")));

                if (match) {
                    out.append(line).append("\n");
                    lines++;
                    trailCount = line.contains("Exception") || line.contains("Caused by") ? 12 : 0;
                } else if (trailCount > 0) {
                    trailCount--;
                }
            }
            reader.close();
            proc.destroy();
        } catch (Throwable t) {
            out.append("logcat read error: ").append(t.getMessage());
        }
        return out.length() > 0 ? out.toString() : "(no matching lines)";
    }

    private void injectBanner(String text) {
        try {
            // Limit to avoid JS string overflow
            if (text.length() > 5000) text = text.substring(text.length() - 5000);
            String safe = text
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
                "padding:10px;font-size:10px;font-family:monospace;" +
                "white-space:pre-wrap;direction:ltr;max-height:60vh;overflow-y:auto;';" +
                "d.textContent=\"" + safe + "\";" +
                "document.body.appendChild(d);" +
                "})();";
            getBridge().getWebView().evaluateJavascript(js, null);
            Log.i(TAG, "Banner injected, length=" + safe.length());
        } catch (Throwable ex) {
            Log.e(TAG, "Banner inject failed", ex);
        }
    }
}
