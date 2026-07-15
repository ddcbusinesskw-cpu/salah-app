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

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
    /* نافذة نامية: من نقطة الارتساء (بداية العبارة) حتى الآن — whisper يرى عبارة
       كاملة لا شريحة مقطوعة. تُعاد نقطة الارتساء عند صمت VAD أو تجاوز السقف. */
    private int anchorSample = 0;               // بداية النافذة النامية
    private int slideSamples = (int)(SR * 1.5); // أعد النسخ كل ~1.5ث من الصوت الجديد
    private int maxWinSamples = SR * 7;          // سقف النافذة (~7ث) — تمريرات whisper سريعة
    private int silenceRun = 0;                  // عدّاد السكوت المتصل (عيّنات)
    private boolean hadSpeech = false;           // سُمع كلام منذ آخر ارتساء؟
    private volatile boolean busy = false;       // المحرّك يفرّغ الآن؟ (أسقِط النوافذ المتراكمة)
    private volatile String prompt = "";        // (تعرّف حرّ: يبقى فارغاً في البثّ الحي)
    private int seq = 0;
    /* بثّ شرائح PCM الخام للـJS (المحرّك الهجين) — نفس مصدر المايك الواحد،
       اختياري بخيار emit_pcm في startStream. شريحة ≈128ms ≈ 5.5KB base64. */
    private volatile boolean emitPcm = false;
    /* عتبة السكوت (RMS ~0.008 مطبَّع). 0.45ث سكوت بعد كلام → ارتساء جديد (وقفة آية). */
    private static final double SILENCE_RMS = 260.0;
    private static final int SILENCE_RESET = (int)(SR * 0.45);

    // ── Vosk: تعرّف ستريمنغ لحظي (طبقة فورية تقود التوهّج) ──
    private Model voskModel = null;
    private Recognizer voskRec = null;
    private volatile boolean voskReady = false;
    private volatile boolean voskLoading = false;
    private String voskModelPath = "";
    private final Object voskLock = new Object();   // يحمي إنشاء/إغلاق/تغذية Recognizer
    private String lastVoskPartial = "";            // لا تُرسل نفس الجزئية مرّتين
    // تشخيص Vosk: إثبات وصول الصوت وما يعيده فعلاً
    private volatile long voskSamplesFed = 0;
    private volatile int voskCalls = 0;
    private volatile String lastVoskRaw = "";
    private volatile int vadResets = 0;             // كم مرّة أعاد VAD الارتساء
    /* ── تتبّع مقياس العينات عبر السلسلة (تشخيص مسار الصوت) ──
       (أ) خام AudioRecord PCM16 · (ب) المسلَّم فعلياً لـVosk (int16).
       (ج) دفعات Float32 تُقاس في JS عند إضافتها لحلقة whisper. */
    private volatile int rawMin = 0, rawMax = 0, rawPeakSes = 0;
    private volatile double rawRmsLast = 0;
    private volatile int voskMin = 0, voskMax = 0, voskPeakSes = 0;
    private volatile double voskRmsLast = 0;
    /* لقطة قرار النافذة النامية كل ثانية: voiced/rms/طول النافذة/الارتساءات/السقف */
    private final java.util.ArrayDeque<String> winDiag = new java.util.ArrayDeque<>();
    private int lastSnapAt = 0;

    private static native long nativeInit(String path);
    private static native String nativeTranscribe(long ctx, short[] pcm, String lang, int threads, String prompt);
    private static native void nativeFree(long ctx);
    private static native String nativeModelInfo(long ctx);
    private static native String nativeConfig();

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

        double slideSec = call.getDouble("slide_sec", 1.2);
        double maxSec   = call.getDouble("max_sec", 7.0);
        slideSamples  = Math.max(SR / 2, (int) (slideSec * SR));
        maxWinSamples = Math.max(4 * SR, (int) (maxSec * SR));
        prompt = call.getString("prompt", ""); // تعرّف حرّ: فارغ عادةً
        emitPcm = Boolean.TRUE.equals(call.getBoolean("emit_pcm", false));

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

        synchronized (buf) {
            buf.clear(); total = 0; emittedUpTo = 0; seq = 0;
            anchorSample = 0; silenceRun = 0; hadSpeech = false;
        }
        busy = false;
        voskSamplesFed = 0; voskCalls = 0; lastVoskRaw = ""; lastVoskPartial = ""; vadResets = 0;
        rawMin = 0; rawMax = 0; rawPeakSes = 0; rawRmsLast = 0;
        voskMin = 0; voskMax = 0; voskPeakSes = 0; voskRmsLast = 0;
        lastSnapAt = 0;
        synchronized (winDiag) { winDiag.clear(); }
        listening = true;
        recorder.startRecording();

        readThread = new Thread(() -> {
            short[] block = new short[2048];
            while (listening) {
                int nr = recorder.read(block, 0, block.length);
                if (nr > 0) {
                    short[] chunk = new short[nr];
                    System.arraycopy(block, 0, chunk, 0, nr);
                    // (أ) مقياس الخام: min/max/RMS بمرور واحد — يُعاد استخدامه لـVAD أدناه
                    int mn = 32767, mx = -32768; long bsq = 0;
                    for (int i = 0; i < nr; i++) {
                        int v = block[i];
                        if (v < mn) mn = v; if (v > mx) mx = v;
                        bsq += (long) v * v;
                    }
                    double brms = nr > 0 ? Math.sqrt((double) bsq / nr) : 0;
                    int apk = Math.max(Math.abs(mn), Math.abs(mx));
                    rawMin = mn; rawMax = mx; rawRmsLast = brms;
                    if (apk > rawPeakSes) rawPeakSes = apk;
                    // بثّ الشريحة الخام للمحرّك الهجين في JS (نفس المايك الواحد — لا تدفق ثانٍ)
                    if (emitPcm) {
                        try {
                            ByteBuffer bb = ByteBuffer.allocate(nr * 2).order(ByteOrder.LITTLE_ENDIAN);
                            bb.asShortBuffer().put(chunk);
                            JSObject pev = new JSObject();
                            pev.put("pcm", Base64.encodeToString(bb.array(), Base64.NO_WRAP));
                            pev.put("n", nr);
                            notifyListeners("pcm_chunk", pev);
                        } catch (Throwable ig) {}
                    }
                    // غذِّ Vosk لحظياً (طبقة فورية) — يبثّ الكلمات فور نطقها بلا انتظار نافذة whisper
                    if (voskReady) {
                        synchronized (voskLock) {
                            if (voskRec != null) {
                                try {
                                    boolean vend = voskRec.acceptWaveForm(block, nr);
                                    voskCalls++; voskSamplesFed += nr;
                                    // (ب) مقياس المسلَّم فعلياً لـVosk (نفس الدفعة الخام int16)
                                    voskMin = mn; voskMax = mx; voskRmsLast = brms;
                                    if (apk > voskPeakSes) voskPeakSes = apk;
                                    String vjs = vend ? voskRec.getResult() : voskRec.getPartialResult();
                                    lastVoskRaw = vjs;   // تشخيص: النتيجة الخام كما يعيدها Vosk (ولو فارغة)
                                    String vtxt = "";
                                    try { vtxt = new JSONObject(vjs).optString(vend ? "text" : "partial", ""); } catch (Throwable ig) {}
                                    if (vend || !vtxt.equals(lastVoskPartial)) {
                                        lastVoskPartial = vend ? "" : vtxt;
                                        JSObject vev = new JSObject();
                                        vev.put("text", vtxt);
                                        vev.put("final", vend);
                                        vev.put("raw", vjs);
                                        notifyListeners("vosk_partial", vev);
                                    }
                                } catch (Throwable ig) {}
                            }
                        }
                    }
                    // VAD بـRMS (أمتن من الذروة ضدّ ضجيج عابر) → إعادة الارتساء عند وقفات الآيات
                    // (brms محسوب أعلاه بمرور واحد مع min/max — مقياس int16، العتبة 260 ≈ 0.0079 float)
                    boolean silent = brms < SILENCE_RMS;
                    int nowTotal;
                    synchronized (buf) { buf.add(chunk); total += nr; nowTotal = total; }
                    // ارتساء جديد: سكوت SILENCE_RESET بعد كلام = نهاية عبارة → ابدأ النافذة من هنا
                    if (silent) {
                        silenceRun += nr;
                        if (hadSpeech && silenceRun >= SILENCE_RESET) { anchorSample = nowTotal; hadSpeech = false; vadResets++; }
                    } else {
                        silenceRun = 0; hadSpeech = true;
                    }
                    // سقف صلب: لا تدع النافذة النامية تتجاوز maxWinSamples
                    boolean capped = nowTotal - anchorSample > maxWinSamples;
                    if (capped) anchorSample = nowTotal - maxWinSamples;
                    // لقطة قرار النافذة كل ثانية (تشخيص: voiced/rms/طول النافذة/ارتساءات/سقف)
                    if (nowTotal - lastSnapAt >= SR) {
                        lastSnapAt = nowTotal;
                        int winLen = nowTotal - anchorSample;
                        String snap = "t=" + (nowTotal / SR) + "s v=" + (silent ? 0 : 1)
                                + " rms16=" + Math.round(brms)
                                + " win=" + (Math.round(winLen * 10.0 / (double) SR) / 10.0) + "s"
                                + " resets=" + vadResets + (capped ? " cap=1" : "");
                        synchronized (winDiag) {
                            winDiag.addLast(snap);
                            if (winDiag.size() > 30) winDiag.removeFirst();
                        }
                    }
                    // نافذة نامية [anchorSample, now): أعد النسخ كل slideSamples، وأسقِط إن كان المحرّك مشغولاً
                    if (!busy && nowTotal - emittedUpTo >= slideSamples) {
                        emittedUpTo = nowTotal;
                        dispatchWindow(anchorSample, nowTotal, ++seq, false);
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

    /** تشخيص: معلومات النموذج المحمَّل + معاملات whisper الفعلية. */
    @PluginMethod
    public void modelInfo(PluginCall call) {
        if (!libOk) { call.reject("lib-missing"); return; }
        if (ctx == 0) { call.reject("not-loaded"); return; }
        final long c = ctx;
        exec.execute(() -> {
            try {
                JSObject r = new JSObject();
                r.put("info", nativeModelInfo(c));
                r.put("config", nativeConfig());
                r.put("threads", threadCount());
                call.resolve(r);
            } catch (Throwable t) {
                call.reject("modelinfo-error: " + t.getMessage());
            }
        });
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
        emitPcm = false;
        try { if (readThread != null) readThread.join(1500); } catch (InterruptedException ignored) {}
        try { if (recorder != null) { recorder.stop(); } } catch (Throwable ignored) {}

        final int from, to, mySeq;
        synchronized (buf) { from = Math.max(0, anchorSample); to = total; mySeq = ++seq; }
        // النافذة الأخيرة (العبارة النامية الحالية) ثم النتيجة + PCM الكامل (لـ«استمع للمسجَّل»)
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
        busy = true; // يُصفَّر في finally — يمنع تراكم النوافذ أثناء التفريغ
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
                ev.put("win_sec", Math.round((w.length / (double) SR) * 10) / 10.0);
                ev.put("anchor_sec", Math.round((from / (double) SR) * 10) / 10.0);
                ev.put("peak", Math.round(peak * 1000) / 1000.0);
                ev.put("rms", Math.round(rms * 1000) / 1000.0);
                ev.put("prompt_len", prompt == null ? 0 : prompt.length());
                ev.put("threads", threadCount());
                notifyListeners("partial", ev);
            } catch (Throwable ignored) {}
            finally { busy = false; }
        });
    }

    /** إعادة ضبط نقطة الارتساء يدوياً (JS عند حدود آية) — النافذة النامية تبدأ من الآن. */
    @PluginMethod
    public void resetWindow(PluginCall call) {
        synchronized (buf) { anchorSample = total; hadSpeech = false; silenceRun = 0; }
        call.resolve();
    }

    // ── Vosk: التوفّر/التنزيل/التحميل/إعادة الضبط ──

    @PluginMethod
    public void voskAvailable(PluginCall call) {
        JSObject r = new JSObject();
        boolean lib;
        try { Class.forName("org.vosk.Model"); lib = true; } catch (Throwable t) { lib = false; }
        r.put("lib", lib);
        r.put("ready", voskReady);
        r.put("loading", voskLoading);
        r.put("path", voskModelPath);
        call.resolve(r);
    }

    /** تنزيل zip موديل Vosk (بثّ إلى ملف — بلا تحميل 318MB في الذاكرة) ثم فكّه. */
    @PluginMethod
    public void voskDownload(PluginCall call) {
        final String url = call.getString("url");
        final String name = call.getString("name", "vosk-model");
        if (url == null || url.isEmpty()) { call.reject("no-url"); return; }
        exec.execute(() -> {
            try {
                File base = new File(getContext().getFilesDir(), "vosk");
                if (!base.exists()) base.mkdirs();
                File modelDir = new File(base, name);
                if (voskModelOk(modelDir)) {
                    JSObject r0 = new JSObject(); r0.put("path", modelDir.getAbsolutePath()); r0.put("cached", true);
                    call.resolve(r0); return;
                }
                File zip = new File(base, name + ".zip");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(30000); c.setReadTimeout(60000); c.setInstanceFollowRedirects(true);
                int status = c.getResponseCode();
                if (status < 200 || status >= 300) { call.reject("vosk-http-" + status); return; }
                long total = c.getContentLengthLong();
                byte[] b = new byte[131072]; long done = 0, lastEmit = 0; int rd;
                try (InputStream in = new BufferedInputStream(c.getInputStream());
                     OutputStream out = new FileOutputStream(zip)) {
                    while ((rd = in.read(b)) > 0) {
                        out.write(b, 0, rd); done += rd;
                        if (done - lastEmit > 3_000_000) {
                            lastEmit = done;
                            JSObject ev = new JSObject();
                            ev.put("phase", "download"); ev.put("done", done); ev.put("total", total);
                            notifyListeners("vosk_progress", ev);
                        }
                    }
                }
                JSObject uz = new JSObject(); uz.put("phase", "unzip"); notifyListeners("vosk_progress", uz);
                unzip(zip, base);
                zip.delete();
                if (!voskModelOk(modelDir)) {
                    call.reject("vosk-unzip-bad: ملفات الموديل ناقصة (am/final.mdl أو conf/model.conf أو graph مفقود — قد تكون المساحة نفدت)"); return;
                }
                JSObject r = new JSObject(); r.put("path", modelDir.getAbsolutePath());
                call.resolve(r);
            } catch (Throwable t) {
                call.reject("vosk-download-error: " + t.getMessage());
            }
        });
    }

    /** تحميل الموديل في Recognizer (مرّة) — يبقى محمّلاً عبر الجلسات. */
    @PluginMethod
    public void voskLoad(PluginCall call) {
        final String path = call.getString("path");
        if (path == null || path.isEmpty()) { call.reject("no-path"); return; }
        if (voskLoading) { call.reject("busy"); return; }
        voskLoading = true;
        exec.execute(() -> {
            try {
                synchronized (voskLock) {
                    if (voskRec != null) { voskRec.close(); voskRec = null; }
                    if (voskModel != null) { voskModel.close(); voskModel = null; }
                    voskModel = new Model(path);
                    voskRec = new Recognizer(voskModel, (float) SR);
                    voskRec.setWords(true);
                }
                voskModelPath = path; voskReady = true; voskLoading = false;
                JSObject r = new JSObject(); r.put("ok", true);
                call.resolve(r);
            } catch (Throwable t) {
                voskReady = false; voskLoading = false;
                call.reject("vosk-load-error: " + t.getMessage());
            }
        });
    }

    /** إعادة ضبط مُعرّف Vosk (عند حدود الآية — عبارة جديدة). */
    @PluginMethod
    public void voskReset(PluginCall call) {
        synchronized (voskLock) { if (voskRec != null) { try { voskRec.reset(); } catch (Throwable ignored) {} } }
        lastVoskPartial = "";
        call.resolve();
    }

    /** تشخيص Vosk: يثبت وصول الصوت وما يعيده فعلاً + سلامة ملفات الموديل. */
    @PluginMethod
    public void voskStats(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ready", voskReady);
        r.put("loading", voskLoading);
        r.put("sampleRate", SR);
        r.put("samplesFed", voskSamplesFed);
        r.put("secFed", Math.round((voskSamplesFed / (double) SR) * 10) / 10.0);
        r.put("calls", voskCalls);
        r.put("lastRaw", lastVoskRaw);
        r.put("vadResets", vadResets);
        r.put("modelPath", voskModelPath);
        r.put("listening", listening);
        r.put("emitPcm", emitPcm);
        /* تتبّع المقياس: (أ) خام AudioRecord و(ب) المسلَّم لـVosk — كلاهما int16 */
        r.put("rawMin", rawMin); r.put("rawMax", rawMax);
        r.put("rawRms", Math.round(rawRmsLast)); r.put("rawPeak", rawPeakSes);
        r.put("voskMin", voskMin); r.put("voskMax", voskMax);
        r.put("voskRms", Math.round(voskRmsLast)); r.put("voskPeak", voskPeakSes);
        String wd;
        synchronized (winDiag) { wd = String.join(" | ", winDiag); }
        r.put("winDiag", wd);
        JSObject files = new JSObject();
        if (voskModelPath != null && !voskModelPath.isEmpty()) {
            String[] keys = {"conf/model.conf", "conf/mfcc.conf", "am/final.mdl",
                    "graph/HCLG.fst", "graph/HCLr.fst", "graph/Gr.fst",
                    "graph/phones/word_boundary.int", "ivector/final.ie"};
            for (String k : keys) { File f = new File(voskModelPath, k); files.put(k, f.exists() ? f.length() : -1); }
        }
        r.put("files", files);
        call.resolve(r);
    }

    /** تحقّق سلامة موديل Vosk: الملفات الأساسية موجودة (لا مجرّد وجود المجلد). */
    private boolean voskModelOk(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        if (!new File(dir, "am/final.mdl").exists()) return false;
        if (!new File(dir, "conf/model.conf").exists()) return false;
        return new File(dir, "graph/HCLG.fst").exists()
            || new File(dir, "graph/HCLr.fst").exists()
            || new File(dir, "graph/Gr.fst").exists();
    }

    /** فكّ zip بثّاً إلى مجلد (بحماية zip-slip). */
    private void unzip(File zip, File destDir) throws Exception {
        String root = destDir.getCanonicalPath() + File.separator;
        byte[] buf = new byte[131072];
        long files = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                File f = new File(destDir, e.getName());
                if (!f.getCanonicalPath().startsWith(root)) { zis.closeEntry(); continue; }
                if (e.isDirectory()) { f.mkdirs(); }
                else {
                    File p = f.getParentFile(); if (p != null) p.mkdirs();
                    try (OutputStream os = new FileOutputStream(f)) {
                        int r; while ((r = zis.read(buf)) > 0) os.write(buf, 0, r);
                    }
                    if ((++files % 25) == 0) {
                        JSObject ev = new JSObject(); ev.put("phase", "unzip"); ev.put("files", files);
                        notifyListeners("vosk_progress", ev);
                    }
                }
                zis.closeEntry();
            }
        }
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

    private void closeVosk() {
        synchronized (voskLock) {
            voskReady = false;
            if (voskRec != null) { try { voskRec.close(); } catch (Throwable ignored) {} voskRec = null; }
            if (voskModel != null) { try { voskModel.close(); } catch (Throwable ignored) {} voskModel = null; }
        }
    }

    @PluginMethod
    public void unload(PluginCall call) {
        listening = false;
        exec.execute(() -> {
            releaseRecorder();
            closeVosk();
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
        closeVosk();
        if (ctx != 0) { try { nativeFree(ctx); } catch (Throwable ignored) {} ctx = 0; }
        super.handleOnDestroy();
    }
}
