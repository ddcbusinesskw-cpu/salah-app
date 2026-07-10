package com.ddcbusiness.noor;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** محرّك Whisper أصلي (whisper.cpp) — استدلال والتقاط صوت على المعالج مباشرة (معمارية ترتيل).
 *  الالتقاط عبر Android AudioRecord: PCM 16kHz mono 16-bit من المايك — بلا ضغط ولا فك
 *  ولا WebView ولا إعادة تشكيل JS. الصوت لا يغادر الطبقة الأصلية حتى يصير نصاً.
 *  فشل تحميل المكتبة/الموديل لا يكسر التطبيق — الجانب الجافاسكربتي يسقط لـWASM. */
@CapacitorPlugin(
    name = "WhisperNative",
    permissions = {
        @Permission(strings = {Manifest.permission.RECORD_AUDIO}, alias = "microphone")
    }
)
public class WhisperNativePlugin extends Plugin {

    private static final int SR = 16000;

    private static boolean libOk = false;
    static {
        try { System.loadLibrary("whisper_jni"); libOk = true; }
        catch (Throwable t) { libOk = false; }
    }

    private long ctx = 0;
    /* منفّذ أحادي: يسلسل النوافذ — whisper_full ليس آمناً للتوازي على سياق واحد */
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    // ── حالة الالتقاط الحي ──
    private AudioRecord recorder = null;
    private Thread readThread = null;
    private volatile boolean listening = false;
    private final ArrayList<short[]> buf = new ArrayList<>(); // شرائح PCM بالترتيب
    private int total = 0;          // إجمالي العيّنات الملتقَطة
    private int emittedUpTo = 0;    // آخر عيّنة أُرسلت نافذتها
    private int winSamples = (int)(SR * 2.5);   // طول النافذة المنزلقة (~2.5ث)
    private int slideSamples = SR;              // مقدار الانزلاق (~1ث) — كل 1ث نافذة جديدة
    private volatile String prompt = "";        // النص المتوقّع حول المؤشر (توجيه)
    private int seq = 0;

    private static native long nativeInit(String path);
    private static native String nativeTranscribe(long ctx, short[] pcm, String lang, int threads, String prompt);
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
        r.put("sampleRate", SR);
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

    /** تفريغ مقطع كامل (لحزمة الذهب / التشخيص) — PCM base64 16kHz mono. */
    @PluginMethod
    public void transcribe(PluginCall call) {
        if (!libOk) { call.reject("lib-missing"); return; }
        if (ctx == 0) { call.reject("not-loaded"); return; }
        String b64 = call.getString("pcm");
        final String lang = call.getString("language", "ar");
        final String pr = call.getString("prompt", "");
        if (b64 == null || b64.isEmpty()) { call.reject("no-pcm"); return; }
        final String pcmB64 = b64;
        exec.execute(() -> {
            try {
                byte[] raw = Base64.decode(pcmB64, Base64.DEFAULT);
                short[] pcm = new short[raw.length / 2];
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                int th = threadCount();
                long t0 = System.currentTimeMillis();
                String text = nativeTranscribe(ctx, pcm, lang, th, pr);
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

    // ── الالتقاط الحي: AudioRecord → نوافذ → whisper.cpp → أحداث للـJS ──

    /* أسماء المرحلة ١: startStream/stopStream (مرادفة لـstart/stopListening) */
    @PluginMethod public void startStream(PluginCall call) { startListening(call); }
    @PluginMethod public void stopStream(PluginCall call) { stopListening(call); }

    /* رد نداء الإذن: يُعاد استدعاء البدء بعد قرار المستخدم */
    @PermissionCallback
    private void micPermCallback(PluginCall call) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) {
            startListening(call);
        } else {
            call.reject("mic-permission-denied: إذن المايكروفون مرفوض — فعّله من إعدادات التطبيق");
        }
    }

    @PluginMethod
    public void startListening(PluginCall call) {
        if (!libOk) { call.reject("lib-missing"); return; }
        if (ctx == 0) { call.reject("not-loaded: الموديل لم يُحمّل بعد"); return; }
        if (listening) { call.resolve(); return; }

        /* إذن RECORD_AUDIO وقت التشغيل قبل فتح AudioRecord — بلا إذن يفشل الالتقاط بصمت */
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "micPermCallback");
            return;
        }

        double winSec  = call.getDouble("window_sec", 2.5);
        double slideSec = call.getDouble("slide_sec", 1.0);
        winSamples   = Math.max(SR, (int) (winSec * SR));
        slideSamples = Math.max(SR / 2, (int) (slideSec * SR));
        prompt = call.getString("prompt", "");

        int minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = SR; // احتياط
        final int recBuf = Math.max(minBuf, SR); // ≥1ث تخزين مؤقت

        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf * 2);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release(); recorder = null;
                call.reject("audiorecord-init-failed: تعذّر فتح المايك (قد يكون مستخدَماً من تطبيق آخر أو الإذن مرفوض)");
                return;
            }
        } catch (SecurityException se) {
            recorder = null;
            call.reject("audiorecord-permission: إذن المايكروفون غير ممنوح (" + se.getMessage() + ")");
            return;
        } catch (Throwable t) {
            recorder = null;
            call.reject("audiorecord-error: " + t.getMessage());
            return;
        }

        synchronized (buf) { buf.clear(); total = 0; emittedUpTo = 0; seq = 0; }
        listening = true;
        recorder.startRecording();

        readThread = new Thread(() -> {
            short[] block = new short[2048];
            while (listening) {
                int nr = recorder.read(block, 0, block.length);
                if (nr > 0) {
                    short[] chunk = new short[nr];
                    System.arraycopy(block, 0, chunk, 0, nr);
                    int nowTotal;
                    synchronized (buf) { buf.add(chunk); total += nr; nowTotal = total; }
                    // نافذة منزلقة: كل slideSamples من الصوت الجديد، بطول winSamples
                    if (nowTotal - emittedUpTo >= slideSamples) {
                        final int from = Math.max(0, nowTotal - winSamples);
                        final int to = nowTotal;
                        emittedUpTo = nowTotal;
                        dispatchWindow(from, to, ++seq, false);
                    }
                } else if (nr < 0) {
                    break; // خطأ قراءة
                }
            }
        }, "whisper-audiorecord");
        readThread.setPriority(Thread.MAX_PRIORITY);
        readThread.start();

        JSObject r = new JSObject();
        r.put("ok", true);
        r.put("sampleRate", SR);
        call.resolve(r);
    }

    /** تحديث النص المتوقّع مع تقدّم المؤشر (توجيه لحظي). */
    @PluginMethod
    public void setPrompt(PluginCall call) {
        prompt = call.getString("prompt", "");
        call.resolve();
    }

    @PluginMethod
    public void stopListening(PluginCall call) {
        if (!listening) { call.resolve(); return; }
        listening = false;
        try { if (readThread != null) readThread.join(1500); } catch (InterruptedException ignored) {}
        try { if (recorder != null) { recorder.stop(); } } catch (Throwable ignored) {}

        final int from, to, mySeq;
        synchronized (buf) { from = Math.max(0, total - winSamples); to = total; mySeq = ++seq; }
        // النافذة الأخيرة ثم النتيجة النهائية + PCM الكامل (لـ«استمع للمسجَّل»)
        exec.execute(() -> {
            try {
                if (to > from) {
                    short[] w = slice(from, to);
                    String text = nativeTranscribe(ctx, w, "ar", threadCount(), prompt);
                    JSObject ev = new JSObject();
                    ev.put("seq", mySeq);
                    ev.put("text", text == null ? "" : text);
                    ev.put("final", true);
                    notifyListeners("partial", ev);
                }
            } catch (Throwable ignored) {}
            // بناء PCM الكامل base64 LE
            short[] all = slice(0, total);
            byte[] bytes = new byte[all.length * 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(all);
            JSObject res = new JSObject();
            res.put("pcm", Base64.encodeToString(bytes, Base64.NO_WRAP));
            res.put("sampleRate", SR);
            res.put("samples", total);
            releaseRecorder();
            call.resolve(res);
        });
    }

    private void dispatchWindow(final int from, final int to, final int mySeq, final boolean isFinal) {
        exec.execute(() -> {
            try {
                if (ctx == 0 || to <= from) return;
                short[] w = slice(from, to);
                /* مقاييس السعة (float مطبَّع /32768): تثبت أن النافذة تحمل صوتاً فعلياً لا صمتاً */
                int pk = 0; long sq = 0;
                for (int i = 0; i < w.length; i++) { int a = Math.abs(w[i]); if (a > pk) pk = a; sq += (long) w[i] * w[i]; }
                double peak = pk / 32768.0;
                double rms = w.length > 0 ? Math.sqrt((double) sq / w.length) / 32768.0 : 0;
                long t0 = System.currentTimeMillis();
                String text = nativeTranscribe(ctx, w, "ar", threadCount(), prompt);
                JSObject ev = new JSObject();
                ev.put("seq", mySeq);
                ev.put("text", text == null ? "" : text);
                ev.put("ms", System.currentTimeMillis() - t0);
                ev.put("final", isFinal);
                ev.put("samples", w.length);
                ev.put("peak", Math.round(peak * 1000) / 1000.0);
                ev.put("rms", Math.round(rms * 1000) / 1000.0);
                ev.put("prompt_len", prompt == null ? 0 : prompt.length());
                notifyListeners("partial", ev);
            } catch (Throwable ignored) {}
        });
    }

    /** نسخ العيّنات [from,to) من قائمة الشرائح إلى short[] واحد. */
    private short[] slice(int from, int to) {
        synchronized (buf) {
            int len = Math.max(0, to - from);
            short[] out = new short[len];
            int pos = 0, w = 0;
            for (int i = 0; i < buf.size() && w < len; i++) {
                short[] ch = buf.get(i);
                int cs = pos, ce = pos + ch.length; pos = ce;
                if (ce <= from) continue;
                int a = Math.max(from, cs) - cs;
                int b = Math.min(to, ce) - cs;
                for (int j = a; j < b && w < len; j++) out[w++] = ch[j];
            }
            return out;
        }
    }

    private void releaseRecorder() {
        try { if (recorder != null) { recorder.release(); } } catch (Throwable ignored) {}
        recorder = null; readThread = null;
    }

    @PluginMethod
    public void unload(PluginCall call) {
        listening = false;
        exec.execute(() -> {
            releaseRecorder();
            if (ctx != 0) { try { nativeFree(ctx); } catch (Throwable ignored) {} ctx = 0; }
            call.resolve();
        });
    }

    /* تحرير كل الموارد عند تدمير النشاط — لا تسريب مايك/ذاكرة موديل */
    @Override
    protected void handleOnDestroy() {
        listening = false;
        try { if (readThread != null) readThread.join(500); } catch (InterruptedException ignored) {}
        releaseRecorder();
        if (ctx != 0) { try { nativeFree(ctx); } catch (Throwable ignored) {} ctx = 0; }
        super.handleOnDestroy();
    }
}
