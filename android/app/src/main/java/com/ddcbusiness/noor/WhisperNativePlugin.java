package com.ddcbusiness.noor;

import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** محرّك Whisper أصلي (whisper.cpp) — استدلال على المعالج مباشرة (معمارية ترتيل).
 *  الصوت يُمرَّر PCM 16kHz mono 16-bit LE بترميز base64.
 *  فشل تحميل المكتبة/الموديل لا يكسر التطبيق — الجانب الجافاسكربتي يسقط لـWASM. */
@CapacitorPlugin(name = "WhisperNative")
public class WhisperNativePlugin extends Plugin {

    private static boolean libOk = false;
    static {
        try { System.loadLibrary("whisper_jni"); libOk = true; }
        catch (Throwable t) { libOk = false; }
    }

    private long ctx = 0;
    /* منفّذ أحادي: يسلسل النوافذ — whisper_full ليس آمناً للتوازي على سياق واحد */
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private static native long nativeInit(String path);
    private static native String nativeTranscribe(long ctx, short[] pcm, String lang, int threads);
    private static native void nativeFree(long ctx);

    private static int threadCount() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject r = new JSObject();
        r.put("value", libOk);
        r.put("loaded", ctx != 0);
        r.put("threads", threadCount());
        call.resolve(r);
    }

    @PluginMethod
    public void loadModel(PluginCall call) {
        if (!libOk) { call.reject("lib-missing"); return; }
        String path = call.getString("path");
        if (path == null || path.isEmpty()) { call.reject("no-path"); return; }
        final String p = path.startsWith("file://") ? path.substring(7) : path;
        exec.execute(() -> {
            try {
                if (ctx != 0) { nativeFree(ctx); ctx = 0; }
                long c = nativeInit(p);
                if (c == 0) { call.reject("init-failed"); return; }
                ctx = c;
                JSObject r = new JSObject();
                r.put("ok", true);
                r.put("threads", threadCount());
                call.resolve(r);
            } catch (Throwable t) {
                call.reject("init-error: " + t.getMessage());
            }
        });
    }

    @PluginMethod
    public void transcribe(PluginCall call) {
        if (!libOk) { call.reject("lib-missing"); return; }
        if (ctx == 0) { call.reject("not-loaded"); return; }
        String b64 = call.getString("pcm");
        final String lang = call.getString("language", "ar");
        if (b64 == null || b64.isEmpty()) { call.reject("no-pcm"); return; }
        final String pcmB64 = b64;
        exec.execute(() -> {
            try {
                byte[] raw = Base64.decode(pcmB64, Base64.DEFAULT);
                short[] pcm = new short[raw.length / 2];
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                int th = threadCount();
                long t0 = System.currentTimeMillis();
                String text = nativeTranscribe(ctx, pcm, lang, th);
                if (text == null) { call.reject("transcribe-failed"); return; }
                JSObject r = new JSObject();
                r.put("text", text);
                r.put("ms", System.currentTimeMillis() - t0);
                r.put("threads", th);
                call.resolve(r);
            } catch (Throwable t) {
                call.reject("transcribe-error: " + t.getMessage());
            }
        });
    }

    @PluginMethod
    public void unload(PluginCall call) {
        exec.execute(() -> {
            if (ctx != 0) { try { nativeFree(ctx); } catch (Throwable ignored) {} ctx = 0; }
            call.resolve();
        });
    }

    /* تحرير السياق عند تدمير النشاط — لا تسريب لذاكرة الموديل */
    @Override
    protected void handleOnDestroy() {
        if (ctx != 0) { try { nativeFree(ctx); } catch (Throwable ignored) {} ctx = 0; }
        super.handleOnDestroy();
    }
}
