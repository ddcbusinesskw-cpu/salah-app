package com.ddcbusiness.noor;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** محرّك التسميع على الجهاز: AudioRecord خاص 16kHz أحادي → تعرّف صوتي متدفّق (CTC، أبجدية
 *  صوتية قرآنية، بلا نموذج لغوي يصحّح الخطأ) → أحداث asr_partial تراكمية لكل مقطع كلام.
 *  يقطع المقاطع عند صمت ≥0.9ث (VAD متكيّف بأرضية ضجيج لا تصعد إلى مستوى الكلام). ينزّل ملفاته ويتحقّق من sha256
 *  لكل ملف ويكتبها بنقل ذرّي. فشل المكتبة الأصلية لا يكسر التطبيق — JS يسقط لمحرّك whisper.
 *  الصوت لا يغادر الجهاز ولا يُخزَّن. الميزة مجانية دائماً (شرط الترخيص). */
@CapacitorPlugin(name = "QuranAsr", permissions = { @Permission(strings = {Manifest.permission.RECORD_AUDIO}, alias = "microphone") })
public class QuranAsrPlugin extends Plugin {

    private static final String TAG = "QuranAsr";
    private static final int SR = 16000;
    private static final int BLOCK = 1600;                  // 100ms لكل كتلة
    private static final int QUEUE_CAP = 600;               // 60ث تأخّر أقصى قبل الإسقاط
    private static final String DIR_VER = "v1";
    private static final String MODEL = "zipformer_p_arabic_v3.1.int8.onnx";
    private static final String TOKENS = "tokens.txt";
    private static final String MARKER = "verified.json";   // {"<name>":"<sha256>"} بعد التحقّق
    private static final long MB = 1024L * 1024L;
    private static final Pattern NAME_RE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$");
    private static final Pattern SHA_RE = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CRANGE_RE = Pattern.compile("bytes\\s+(\\d+)-");

    /* ── ثوابت التقطيع (معيارية — حزمة الاختبار تحاكيها حرفياً) ── */
    private static final double VOICE_MIN = 260.0;          // أدنى عتبة صوت (rms16)
    private static final double SIL_MIN = 200.0;            // أدنى عتبة صمت
    private static final int UTT_SIL_MS = 900;              // صمت يقطع المقطع
    private static final int TOK_AGE_MS = 700;              // لا قطع بالصمت ما دام آخر رمز أحدث من هذا (الكلام مستمرّ)
    private static final int FLOOR_AVG_BLK = 5;             // أرضية الضجيج: متوسط متحرّك 0.5ث…
    private static final int FLOOR_WIN_BLK = 300;           // …وأدناه خلال آخر 30ث
    private static final int UTT_SOFT_MS = 40000;           // قطع ليّن: بعد 40ث عند أول توقّف قصير…
    private static final int UTT_SOFT_SIL_MS = 300;         // …(≥300ms تحت عتبة الصمت: نَفَس بين جملتين، لا وسط كلمة)
    private static final int UTT_SOFT2_MS = 50000;          // بعد 50ث بلا توقّف: عند أي هبوط تحت عتبة الصوت
    private static final int UTT_HARD_MS = 60000;           // قطع صلب
    private static final float[] TAIL_CUT = new float[12800];  // 0.8ث أصفار عند القطع (تُخرج آخر كلمة كاملة حتى في القطع الليّن)
    private static final float[] TAIL_END = new float[12800];  // 0.8ث أصفار عند الإيقاف

    /* ── فحص المكتبة الأصلية مرّة واحدة (كسولاً): Class.forName يشغّل المُهيّئ الساكن
       الذي يحمّل المكتبة، فيظهر خطأ الربط هنا لا عند أول استعمال ── */
    private static volatile Boolean libOk = null; private static volatile String libErr = "";
    private static boolean libOk() { if (libOk == null) { try { Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer"); libOk = true; } catch (Throwable t) { libOk = false; libErr = String.valueOf(t); } } return libOk; }

    /* منفّذ أحادي للتنزيل/التحقّق/التحميل/القراءة/الحذف — مهمة بعد الإغلاق تُسقط بصمت */
    private final ExecutorService ioExec = single();
    private static ExecutorService single() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), new ThreadPoolExecutor.DiscardPolicy());
    }

    // ── المُعرِّف: كل وصول له/للتيار تحت lock ──
    private final Object lock = new Object();
    private volatile OnlineRecognizer recognizer = null;
    private Thread activeDec = null;                 // خيط الفكّ المالك لتيار (تحت lock)
    private final ArrayList<OnlineRecognizer> pendingRelease = new ArrayList<>();  // تحرير مؤجَّل حتى يخرج خيط الفكّ (تحت lock)
    private volatile int loadedThreads = 0;
    private volatile long loadMs = 0;
    private volatile boolean loading = false;

    // ── التنزيل ──
    private volatile boolean downloading = false;
    private volatile boolean cancelReq = false;
    private volatile HttpURLConnection curConn = null;

    // ── الجلسة الحيّة ──
    private final Object sessLock = new Object();    // يحمي تبديل حقول الجلسة أدناه
    private AudioRecord recorder = null;
    private Thread capThread = null, decThread = null, stopHelper = null;
    private ArrayBlockingQueue<Block> queue = null;
    private volatile StopReq pendingStop = null;
    private volatile boolean capturing = false;
    private volatile boolean listening = false;
    /* جيل الجلسة: الخيوط تحمل جيلها وتخرج عند تغيّره (كالمحرّك القائم) */
    private volatile int gen = 0;
    private volatile int jsSess = 0;
    private volatile int decDeadGen = -1;            // جيل خيط فكّ انتهى (خطأ/إيقاف)
    private AudioManager.OnAudioFocusChangeListener focusListener = null;

    // ── عدّادات الجلسة (كاتب واحد لكل حقل؛ تُقرأ من stats) ──
    private volatile int seq = 0, utt = 0, dropped = 0;
    private volatile long fedSamples = 0, decodeNs = 0, maxBacklogMs = 0, uttStartMs = 0;
    private volatile String lastText = "";
    private volatile boolean backlogWarned = false;
    private volatile int rawPeak = 0;
    private volatile double rawRms = 0, noiseFloor = VOICE_MIN;
    private int sentTok = 0;                         // رموز أُرسلت في المقطع الحالي (خيط الفكّ فقط)

    /** كتلة صوت ملتقَطة: PCM ±1.0 + وقت الالتقاط + rms16 الخام */
    private static final class Block {
        final float[] pcm; final long tCap; final double rms;
        Block(float[] p, long t, double r) { pcm = p; tCap = t; rms = r; }
    }
    private static final Block EOS = new Block(new float[0], 0, 0);   // حارس نهاية الجلسة

    /** طلب إيقاف: يُحلّ مرّة واحدة بالضبط (من خيط الفكّ أو من المراقب بعد المهلة) */
    private static final class StopReq {
        final int gen, js; private PluginCall call; private boolean done = false;
        StopReq(int g, int j, PluginCall c) { gen = g; js = j; call = c; }
        synchronized boolean attach(PluginCall c) { if (done || call != null) return false; call = c; return true; }
        synchronized void finish(JSObject res) {
            if (done) return;
            done = true; PluginCall c = call; call = null;
            if (c != null) { try { c.resolve(res); } catch (Throwable ignored) {} }
        }
    }

    /** وصف ملف مطلوب: الاسم + sha256 + الحجم */
    private static final class FileSpec {
        String name, sha; long size; int state;      // state: 0 ينقص · 1 موجود بالحجم (يُعاد هاشه) · 2 موثَّق
    }

    private static final class CancelledIO extends IOException { CancelledIO() { super("cancelled"); } }

    private static int cores() { return Math.max(1, Runtime.getRuntime().availableProcessors()); }

    // ═══ الحالة ═══

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        try {
            boolean lib = libOk();
            r.put("lib", lib);
            r.put("libErr", libErr == null ? "" : libErr);
            r.put("ready", recognizer != null);
            r.put("loading", loading);
            r.put("listening", listening);
            r.put("downloading", downloading);
            File dir = asrDir(false);
            JSONObject mk = readMarker(dir);
            List<FileSpec> req = parseFiles(call.getArray("files"), null);
            JSObject files = new JSObject();
            boolean verified; long bytes = 0;
            if (req != null && !req.isEmpty()) {
                verified = true;
                for (FileSpec f : req) {
                    File fin = new File(dir, f.name);
                    long len = fin.isFile() ? fin.length() : 0;
                    boolean ok = len == f.size && f.sha.equals(mk.optString(f.name, ""));
                    if (!ok) verified = false;
                    bytes += len;
                    JSObject fo = new JSObject();
                    fo.put("bytes", len); fo.put("ok", ok);
                    File part = new File(dir, f.name + ".part");
                    if (part.isFile()) fo.put("part", part.length());
                    files.put(f.name, fo);
                }
            } else {
                verified = new File(dir, MARKER).isFile();
                File[] kids = dir.listFiles();
                if (kids != null) for (File k : kids) {
                    String n = k.getName();
                    if (!k.isFile() || n.endsWith(".part") || n.startsWith(MARKER)) continue;
                    bytes += k.length();
                    JSObject fo = new JSObject();
                    fo.put("bytes", k.length()); fo.put("ok", mk.has(n));
                    files.put(n, fo);
                }
            }
            r.put("verified", verified);
            r.put("bytes", bytes);
            File fs = dir.exists() ? dir : getContext().getFilesDir();
            r.put("free", fs.getUsableSpace());
            r.put("files", files);
            r.put("threads", loadedThreads > 0 ? loadedThreads : Math.min(2, cores()));
            r.put("cores", cores());
            ActivityManager.MemoryInfo mi = memInfo();
            r.put("availMem", mi != null ? mi.availMem : -1);
            r.put("totalMem", mi != null ? mi.totalMem : -1);
            r.put("abi", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
        } catch (Throwable t) {
            r.put("err", String.valueOf(t));   // status لا يرفض أبداً
        }
        call.resolve(r);
    }

    // ═══ التنزيل والتحقّق ═══

    @PluginMethod
    public void ensureModel(PluginCall call) {
        final List<String> mirrors = new ArrayList<>();
        final StringBuilder err = new StringBuilder();
        JSArray ma = call.getArray("mirrors");
        if (ma != null) for (int i = 0; i < ma.length() && i < 8; i++) {
            String m = ma.optString(i, "");
            if (m == null || !m.startsWith("https://") || m.length() > 400) { err.append("mirror ").append(i); break; }
            mirrors.add(m.endsWith("/") ? m : m + "/");
        }
        if (err.length() == 0 && mirrors.isEmpty()) err.append("mirrors");
        final List<FileSpec> files = err.length() == 0 ? parseFiles(call.getArray("files"), err) : null;
        if (err.length() > 0 || files == null) { call.reject("bad-args: " + err); return; }
        if (downloading) { call.reject("busy"); return; }
        downloading = true; cancelReq = false;
        ioExec.execute(() -> {
            try { doEnsure(call, mirrors, files); }
            catch (Throwable t) { safeReject(call, "net: " + t.getMessage()); }
            finally { downloading = false; curConn = null; }
        });
    }

    private void doEnsure(PluginCall call, List<String> mirrors, List<FileSpec> files) {
        File dir = asrDir(true);
        if (!dir.isDirectory()) { safeReject(call, "disk: تعذّر إنشاء مجلد المحرّك"); return; }
        JSONObject mk = readMarker(dir);
        long allTotal = 0, need = 0;
        for (FileSpec f : files) {
            allTotal += f.size;
            File fin = new File(dir, f.name);
            boolean sized = fin.isFile() && fin.length() == f.size;
            f.state = sized && f.sha.equals(mk.optString(f.name, "")) ? 2 : (sized ? 1 : 0);
            if (f.state == 0) {
                File part = new File(dir, f.name + ".part");
                long have = part.isFile() ? Math.min(part.length(), f.size) : 0;
                need += f.size - have;
            }
        }
        long free = dir.getUsableSpace();
        if (need > 0 && free < need + 50L * MB) {
            Log.w(TAG, "ensure: disk free=" + (free / MB) + "MB need=" + (need / MB) + "MB");
            safeReject(call, "disk: " + (free / MB) + "MB free, need ~" + ((need + 50L * MB) / MB) + "MB");
            return;
        }
        Log.i(TAG, "ensure: files=" + files.size() + " need=" + (need / MB) + "MB mirrors=" + mirrors.size());
        JSArray got = new JSArray();
        long allDone = 0;
        try {
            for (FileSpec f : files) {
                checkCancel();
                File fin = new File(dir, f.name);
                if (f.state == 2) { allDone += f.size; continue; }
                if (f.state == 1) {
                    // ملف نهائي بالحجم الصحيح بلا علامة مطابقة: أعد هاشه قبل أي تنزيل
                    emitPhase("verify", f.name, allDone, allTotal);
                    long t0 = SystemClock.elapsedRealtime();
                    String h = sha256File(fin);
                    if (f.sha.equals(h)) {
                        mk.put(f.name, f.sha); allDone += f.size;
                        Log.i(TAG, "verify ok " + f.name + " " + (SystemClock.elapsedRealtime() - t0) + "ms");
                        continue;
                    }
                    Log.w(TAG, "verify mismatch " + f.name + " — re-download");
                    fin.delete();
                }
                String e = "net: no-mirror";
                for (int i = 0; i < mirrors.size(); i++) {
                    try {
                        e = fetchOne(dir, f, mirrors.get(i), i, allDone, allTotal);
                    } catch (CancelledIO ce) {
                        throw ce;
                    } catch (IOException io) {
                        if (cancelReq) throw new CancelledIO();
                        e = "net: " + io.getMessage();   // الجزئي يبقى للاستئناف
                    }
                    if (e == null || e.startsWith("disk")) break;
                    Log.w(TAG, "mirror " + i + " " + f.name + ": " + e);
                }
                if (e != null) { Log.w(TAG, "ensure failed: " + e); safeReject(call, e); return; }
                got.put(f.name); allDone += f.size;
                mk.put(f.name, f.sha);
            }
            if (!writeMarker(dir, mk)) { safeReject(call, "disk: تعذّر كتابة علامة التحقّق"); return; }
        } catch (CancelledIO ce) {
            Log.i(TAG, "ensure cancelled");
            safeReject(call, "cancelled");
            return;
        } catch (Throwable t) {
            safeReject(call, "net: " + t.getMessage());
            return;
        }
        JSObject done = new JSObject(); done.put("phase", "done");
        emit("asr_progress", done);
        long bytes = 0;
        for (FileSpec f : files) { File fin = new File(dir, f.name); if (fin.isFile()) bytes += fin.length(); }
        Log.i(TAG, "ensure done: downloaded=" + got.length() + " bytes=" + bytes);
        JSObject r = new JSObject();
        r.put("ok", true); r.put("downloaded", got); r.put("bytes", bytes);
        safeResolve(call, r);
    }

    /** محاولة مرآة واحدة لملف واحد. null = نجح ونُقل ذرّياً؛ وإلا رمز الرفض.
     *  IOException = خطأ شبكة (الجزئي يبقى للاستئناف). */
    private String fetchOne(File dir, FileSpec f, String mirror, int mi, long allBase, long allTotal) throws IOException {
        File part = new File(dir, f.name + ".part");
        File fin = new File(dir, f.name);
        MessageDigest md = newSha();
        long have = part.isFile() ? part.length() : 0;
        if (have > f.size) { part.delete(); have = 0; }
        if (have > 0 && !hashFile(part, md)) { part.delete(); have = 0; md.reset(); }
        if (have == f.size) {
            // جزئي مكتمل (انقطع قبل النقل): تحقّق بلا شبكة
            if (hex(md.digest()).equals(f.sha)) return promote(part, fin) ? null : "disk: تعذّر حفظ " + f.name;
            part.delete(); have = 0; md.reset();
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            checkCancel();
            HttpURLConnection c = (HttpURLConnection) new URL(mirror + f.name).openConnection();
            curConn = c;
            try {
                c.setConnectTimeout(30000); c.setReadTimeout(60000);
                c.setInstanceFollowRedirects(true); c.setUseCaches(false);
                c.setRequestProperty("Accept-Encoding", "identity");   // بايتات الملف كما هي (لا gzip شفاف)
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                int code = c.getResponseCode();
                boolean append;
                if (have > 0 && code == 206 && rangeStarts(c, have)) {
                    append = true;
                } else if (code == 200) {
                    append = false;
                    if (have > 0) { have = 0; md.reset(); }          // الخادم تجاهل Range: من الصفر
                } else if (have > 0 && (code == 206 || code == 416)) {
                    part.delete(); have = 0; md.reset();              // مدى غير متّسق: أعد على نفس المرآة
                    continue;
                } else {
                    return "http-" + code;
                }
                // طول معلن مختلف = ملف آخر على المرآة: لا تنزّله أصلاً
                long declared = declaredTotal(c, code);
                if (declared >= 0 && declared != f.size) {
                    Log.w(TAG, "declared size " + declared + " != " + f.size + " for " + f.name);
                    part.delete();
                    return "size-" + f.name;
                }
                Log.i(TAG, "dl " + f.name + " mirror=" + mi + " from=" + have + " code=" + code);
                long done = have, lastB = have, lastT = 0;
                boolean over = false;
                byte[] b = new byte[131072];
                int rd;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, append)) {
                    while ((rd = in.read(b)) > 0) {
                        checkCancel();
                        if (done + rd > f.size) { over = true; break; }
                        out.write(b, 0, rd); md.update(b, 0, rd); done += rd;
                        long now = SystemClock.elapsedRealtime();
                        if ((done - lastB >= 512L * 1024L && now - lastT >= 100) || now - lastT >= 300) {
                            lastB = done; lastT = now;
                            emitDl(f, done, allBase + done, allTotal, mi);
                        }
                    }
                    out.flush();
                    try { out.getFD().sync(); } catch (Throwable ignored) {}
                }
                emitDl(f, done, allBase + done, allTotal, mi);
                if (over) {
                    Log.w(TAG, "size mismatch " + f.name + " got>" + f.size);
                    part.delete();
                    return "size-" + f.name;
                }
                /* نهاية مبكرة بلا خطأ = اتصال انقطع (الطول المعلن صحيح): الجزئي يبقى للاستئناف
                   كخطأ شبكة — لا تُهدر عشرات MB على بيانات الجوال؛ sha يحسم السلامة في النهاية */
                if (done < f.size) throw new IOException("truncated " + done + "/" + f.size);
                if (!hex(md.digest()).equals(f.sha)) {
                    Log.w(TAG, "sha mismatch " + f.name);
                    part.delete();
                    return "sha-" + f.name;
                }
                return promote(part, fin) ? null : "disk: تعذّر حفظ " + f.name;
            } finally {
                curConn = null;
                try { c.disconnect(); } catch (Throwable ignored) {}
            }
        }
        return "http-416";
    }

    @PluginMethod
    public void cancelDownload(PluginCall call) {
        cancelReq = true;
        disconnectCur();   // يقطع قراءة معلّقة فوراً بدل انتظار مهلة القراءة
        JSObject r = new JSObject(); r.put("ok", true);
        call.resolve(r);
    }

    // ═══ التحميل ═══

    @PluginMethod
    public void load(PluginCall call) {
        if (!libOk()) { call.reject("lib: " + libErr); return; }
        if (loading || listening) { call.reject("busy"); return; }
        Integer reqT = call.getInt("threads", 2);
        final int threads = Math.max(1, Math.min(reqT == null ? 2 : reqT, Math.min(2, cores())));
        loading = true;
        ioExec.execute(() -> {
            try { doLoad(call, threads); }
            catch (Throwable t) { safeReject(call, "init: " + t.getMessage()); }
            finally { loading = false; }
        });
    }

    private void doLoad(PluginCall call, int threads) throws Exception {
        File dir = asrDir(false);
        File model = new File(dir, MODEL), tokens = new File(dir, TOKENS);
        JSONObject mk = readMarker(dir);
        if (!model.isFile() || !tokens.isFile()
                || !SHA_RE.matcher(mk.optString(MODEL, "")).matches()
                || !SHA_RE.matcher(mk.optString(TOKENS, "")).matches()) {
            safeReject(call, "not-verified");
            return;
        }
        if (recognizer != null && loadedThreads == threads) {
            JSObject r = new JSObject();
            r.put("ok", true); r.put("ms", 0); r.put("threads", threads); r.put("cached", true);
            safeResolve(call, r);
            return;
        }
        if (listening) { safeReject(call, "busy"); return; }
        releaseRecognizer();   // حرّر القديم قبل بناء الجديد — لا نسختان في الذاكرة
        ActivityManager.MemoryInfo mi = memInfo();
        if (mi != null && (mi.lowMemory || mi.availMem < 200L * MB)) {
            Log.w(TAG, "load: low-mem avail=" + (mi.availMem / MB) + "MB low=" + mi.lowMemory);
            safeReject(call, "low-mem: " + (mi.availMem / MB));
            return;
        }
        long t0 = SystemClock.elapsedRealtime();
        OnlineRecognizer rec;
        try {
            OnlineRecognizerConfig cfg = new OnlineRecognizerConfig();
            cfg.setFeatConfig(new FeatureConfig(SR, 80, 0f));            // kaldi fbank 80، بلا dither
            OnlineModelConfig mc = new OnlineModelConfig();
            mc.setZipformer2Ctc(new OnlineZipformer2CtcModelConfig(model.getAbsolutePath()));
            mc.setTokens(tokens.getAbsolutePath());
            mc.setNumThreads(threads);
            mc.setDebug(false); mc.setProvider("cpu");                   // modelType يبقى "" (كالمسار المُتحقَّق)
            cfg.setModelConfig(mc);
            cfg.setEnableEndpoint(false);                                 // التقطيع عندنا (VAD أدناه)
            cfg.setDecodingMethod("greedy_search");
            rec = new OnlineRecognizer(null, cfg);                        // null AssetManager → من ملف
        } catch (OutOfMemoryError oom) {
            Log.w(TAG, "load: oom");
            safeReject(call, "low-mem: oom");
            return;
        } catch (Throwable t) {
            Log.w(TAG, "load: init failed " + t);
            dropMarker(dir, MODEL, TOKENS);   // ensureModel التالي يعيد الهاش
            safeReject(call, "init: " + t.getMessage());
            return;
        }
        long ms = SystemClock.elapsedRealtime() - t0;
        /* تحقّق إعداد مرفوض في الطبقة الأصلية يعيد مؤشراً صفرياً بلا استثناء — أي استدعاء
           بعده ينهار؛ افحص المؤشر (انعكاساً) وارفض بدل الانهيار */
        if (nativePtr(rec) == 0L) {
            Log.w(TAG, "load: native config rejected");
            dropMarker(dir, MODEL, TOKENS);
            safeReject(call, "init: config rejected");
            return;
        }
        // تسخين: نصف ثانية صمت — يحجز الذاكرة ويثبت سلامة المُعرِّف قبل أول جلسة
        long w0 = SystemClock.elapsedRealtime();
        try {
            OnlineStream ws = rec.createStream("");
            try {
                ws.acceptWaveform(new float[SR / 2], SR);
                ws.inputFinished();
                while (rec.isReady(ws)) rec.decode(ws);
                rec.getResult(ws);
            } finally { ws.release(); }
        } catch (Throwable t) {
            try { rec.release(); } catch (Throwable ignored) {}
            Log.w(TAG, "load: warmup failed " + t);
            dropMarker(dir, MODEL, TOKENS);
            safeReject(call, "init: warmup " + t.getMessage());
            return;
        }
        long warmMs = SystemClock.elapsedRealtime() - w0;
        synchronized (lock) {
            // أُغلق المنفّذ (تدمير النشاط) أثناء البناء → لا تثبّت مُعرِّفاً لن يُحرَّر
            if (ioExec.isShutdown()) { try { rec.release(); } catch (Throwable ignored) {} return; }
            recognizer = rec; loadedThreads = threads; loadMs = ms;
        }
        Log.i(TAG, "load ok ms=" + ms + " warm=" + warmMs + " threads=" + threads + " cores=" + cores());
        JSObject r = new JSObject();
        r.put("ok", true); r.put("ms", ms); r.put("threads", threads); r.put("warmMs", warmMs);
        safeResolve(call, r);
    }

    @PluginMethod
    public void readPhon(PluginCall call) {
        final String name = call.getString("name", "hafs_phon_v1.json.gz");
        if (name == null || !NAME_RE.matcher(name).matches()) { call.reject("missing"); return; }
        ioExec.execute(() -> {
            File f = new File(asrDir(false), name);
            if (!f.isFile()) { safeReject(call, "missing"); return; }
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(f), 65536), 65536)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream(4 * 1024 * 1024);
                byte[] b = new byte[65536]; int rd;
                while ((rd = in.read(b)) > 0) {
                    bo.write(b, 0, rd);
                    if (bo.size() > 48 * 1024 * 1024) throw new IOException("too-large");
                }
                JSObject r = new JSObject();
                r.put("json", bo.toString("UTF-8"));
                r.put("bytes", bo.size());
                safeResolve(call, r);
            } catch (Throwable t) {
                safeReject(call, "io: " + t.getMessage());
            }
        });
    }

    // ═══ الالتقاط الحي ═══

    /* رد نداء الإذن: يُعاد البدء بعد قرار المستخدم */
    @PermissionCallback
    private void micPermCallback(PluginCall call) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) {
            start(call);
        } else {
            call.reject("mic-permission-denied: إذن المايكروفون مرفوض — فعّله من إعدادات التطبيق");
        }
    }

    @PluginMethod
    public void start(PluginCall call) {
        final OnlineRecognizer rec = recognizer;
        if (rec == null) { call.reject("not-loaded"); return; }
        if (listening) {
            JSObject r = new JSObject(); r.put("ok", true); r.put("sess", jsSess); r.put("sampleRate", SR);
            call.resolve(r); return;
        }
        if (loading) { call.reject("busy"); return; }
        /* إذن RECORD_AUDIO وقت التشغيل قبل فتح AudioRecord */
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "micPermCallback");
            return;
        }
        if (!quiesce()) { call.reject("busy"); return; }

        int minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = SR;
        final int recBuf = Math.max(minBuf, SR) * 2;
        final AudioRecord ar;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                try { ar.release(); } catch (Throwable ignored) {}
                call.reject("audiorecord-init-failed: تعذّر فتح المايك (قد يكون مستخدَماً من تطبيق آخر أو الإذن مرفوض)");
                return;
            }
        } catch (SecurityException se) {
            call.reject("mic-permission-denied: إذن المايكروفون غير ممنوح (" + se.getMessage() + ")");
            return;
        } catch (Throwable t) {
            call.reject("audiorecord-init-failed: " + t.getMessage());
            return;
        }
        final OnlineStream st;
        try {
            synchronized (lock) {
                if (recognizer != rec) throw new IllegalStateException("unloaded");
                st = rec.createStream("");
            }
        } catch (Throwable t) {
            try { ar.release(); } catch (Throwable ignored) {}
            call.reject("not-loaded: " + t.getMessage());
            return;
        }
        requestFocus();
        try { ar.startRecording(); } catch (Throwable ignored) {}
        /* المايك محجوز (تطبيق آخر/مكالمة): startRecording يفشل بصمت — ارفض صراحةً */
        if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            abandonFocus();
            try { ar.release(); } catch (Throwable ignored) {}
            synchronized (lock) { try { st.release(); } catch (Throwable ignored) {} }
            call.reject("mic-busy: المايك مستخدَم من تطبيق آخر");
            return;
        }
        Integer js0 = call.getInt("sess", 0);
        final int js = js0 == null ? 0 : js0;
        final ArrayBlockingQueue<Block> q = new ArrayBlockingQueue<>(QUEUE_CAP);
        final int g;
        synchronized (sessLock) {
            g = ++gen;
            jsSess = js;
            seq = 0; utt = 0; dropped = 0; sentTok = 0;
            fedSamples = 0; decodeNs = 0; maxBacklogMs = 0; uttStartMs = 0;
            lastText = ""; backlogWarned = false; rawPeak = 0; rawRms = 0; noiseFloor = VOICE_MIN;
            recorder = ar; queue = q; pendingStop = null;
            capturing = true; listening = true;
        }
        Thread dt = new Thread(() -> decLoop(g, js, q, rec, st), "quranasr-decode");
        Thread ct = new Thread(() -> capLoop(g, js, q, ar), "quranasr-capture");
        ct.setPriority(Thread.MAX_PRIORITY);
        synchronized (lock) { activeDec = dt; }
        synchronized (sessLock) { decThread = dt; capThread = ct; stopHelper = null; }
        dt.start();
        ct.start();
        Log.i(TAG, "start sess=" + js + " gen=" + g + " threads=" + loadedThreads + " minBuf=" + minBuf);
        JSObject r = new JSObject();
        r.put("ok", true); r.put("sess", js); r.put("sampleRate", SR);
        call.resolve(r);
    }

    /** انتظر انتهاء بقايا جلسة سابقة (إنهاء متأخّر بعد المهلة). false = ما زالت حيّة. */
    private boolean quiesce() {
        Thread dt, ct, sh;
        synchronized (sessLock) {
            dt = decThread; ct = capThread; sh = stopHelper;
            // نتائج الجلسة السابقة لم تعد تُنتظر: غيّر الجيل فتخرج خيوطها فوراً
            if ((dt != null && dt.isAlive()) || (ct != null && ct.isAlive())) gen++;
        }
        try {
            if (dt != null) dt.join(1500);
            if (ct != null) ct.join(500);
            if (sh != null) sh.join(1500);
        } catch (InterruptedException ignored) {}
        return !((dt != null && dt.isAlive()) || (ct != null && ct.isAlive()));
    }

    /** خيط الالتقاط: كتل 100ms → float ±1.0 → الطابور (يُسقط الأقدم عند الامتلاء) */
    private void capLoop(final int g, final int js, final ArrayBlockingQueue<Block> q, final AudioRecord ar) {
        short[] sb = new short[BLOCK];
        while (capturing && g == gen) {
            int n;
            try { n = ar.read(sb, 0, BLOCK); } catch (Throwable t) { n = -1000; }
            if (n > 0) {
                long tCap = SystemClock.elapsedRealtime();
                float[] f = new float[n];
                long sq = 0; int mn = 32767, mx = -32768;
                for (int i = 0; i < n; i++) {
                    int v = sb[i];
                    f[i] = v / 32768f;
                    sq += (long) v * v;
                    if (v < mn) mn = v;
                    if (v > mx) mx = v;
                }
                double rms = Math.sqrt((double) sq / n);
                int pk = Math.max(Math.abs(mn), Math.abs(mx));
                rawRms = rms;
                if (pk > rawPeak) rawPeak = pk;
                if (decDeadGen == g) continue;   // خيط الفكّ توقّف بخطأ: لا طابور ينمو بلا مستهلك
                Block blk = new Block(f, tCap, rms);
                if (!q.offer(blk)) {
                    dropped++;
                    if (!backlogWarned) {
                        backlogWarned = true;
                        Log.w(TAG, "backlog: queue full, dropping oldest");
                        JSObject ev = new JSObject();
                        ev.put("sess", js); ev.put("code", "backlog");
                        ev.put("msg", "decoder behind by >" + (QUEUE_CAP * BLOCK / SR) + "s");
                        emit("asr_error", ev);
                    }
                    q.poll();
                    q.offer(blk);
                }
            } else if (n < 0) {
                /* خطأ قراءة (audioserver أُعيد تشغيله/تغيّر المسار): أبلغ JS لينهي الجلسة */
                if (capturing && g == gen) {
                    Log.w(TAG, "read error " + n);
                    JSObject ev = new JSObject();
                    ev.put("sess", js); ev.put("reason", "read-error"); ev.put("code", n);
                    emit("asr_interrupt", ev);
                }
                break;
            }
        }
    }

    /** خيط الفكّ: يملك التيار؛ تغذية → فكّ → حدث عند تغيّر النص → VAD → قطع المقطع */
    private void decLoop(final int g, final int js, final ArrayBlockingQueue<Block> q,
                         final OnlineRecognizer rec, final OnlineStream first) {
        OnlineStream st = first;
        boolean ended = false;
        /* أرضية الضجيج = أدنى متوسط متحرّك (0.5ث) خلال آخر 30ث: لا تصعد إلى مستوى الكلام في التلاوة المتصلة
           (وسيط أهدأ 20% من آخر 8ث كان يصعد مع الكلام المتصل فيقطع المقطع وسط الكلمة ويُسقطها) */
        final double[] sm = new double[FLOOR_AVG_BLK];
        int smN = 0, smPos = 0; double smSum = 0;
        final double[] ring = new double[FLOOR_WIN_BLK];
        int ringN = 0, ringPos = 0;
        long lastTokMs = Long.MIN_VALUE / 4;    // زمن آخر رمز (ms صوتية منذ بدء الجلسة)
        double floor = VOICE_MIN;
        boolean hadSpeech = false;
        int silMs = 0;
        long lastTCap = SystemClock.elapsedRealtime(), lastLogMs = 0;
        try {
            while (g == gen) {
                Block b;
                try { b = q.poll(250, TimeUnit.MILLISECONDS); } catch (InterruptedException ie) { break; }
                if (b == null) continue;
                if (b.pcm.length == 0) {
                    // نهاية الجلسة: ذيل 0.8ث ثم إنهاء الإدخال وتفريغ آخر الرموز
                    long t0 = System.nanoTime();
                    OnlineRecognizerResult r;
                    synchronized (lock) {
                        if (g != gen) break;
                        st.acceptWaveform(TAIL_END, SR);
                        st.inputFinished();
                        while (rec.isReady(st)) rec.decode(st);
                        r = rec.getResult(st);
                    }
                    decodeNs += System.nanoTime() - t0;
                    emitPartial(g, js, r, true, SystemClock.elapsedRealtime() - lastTCap);
                    ended = true;
                    break;
                }
                long t0 = System.nanoTime();
                OnlineRecognizerResult r;
                synchronized (lock) {
                    if (g != gen) break;
                    st.acceptWaveform(b.pcm, SR);
                    while (rec.isReady(st)) rec.decode(st);
                    r = rec.getResult(st);
                }
                decodeNs += System.nanoTime() - t0;
                fedSamples += b.pcm.length;
                long lag = SystemClock.elapsedRealtime() - b.tCap;
                lastTCap = b.tCap;
                if (lag > maxBacklogMs) maxBacklogMs = lag;
                if (!clean(r.getText()).equals(lastText)) emitPartial(g, js, r, false, lag);
                float[] tsl = r.getTimestamps();
                if (tsl != null && tsl.length > 0) lastTokMs = uttStartMs + Math.round(tsl[tsl.length - 1] * 1000.0);

                // ── VAD متكيّف: أرضية = أدنى متوسط 0.5ث خلال 30ث (العتبتان كما في محرّك whisper) ──
                smSum += b.rms - sm[smPos]; sm[smPos] = b.rms; smPos = (smPos + 1) % sm.length; if (smN < sm.length) smN++;
                ring[ringPos] = smSum / smN;
                ringPos = (ringPos + 1) % ring.length;
                if (ringN < ring.length) ringN++;
                if (ringN >= 10) {
                    double mn = ring[0];
                    for (int i = 1; i < ringN; i++) if (ring[i] < mn) mn = ring[i];
                    floor = mn;
                    noiseFloor = floor;
                }
                double voiceThr = Math.max(VOICE_MIN, floor * 2.5);
                double silThr = Math.max(SIL_MIN, floor * 1.5);
                int blkMs = (int) (b.pcm.length * 1000L / SR);
                if (b.rms > voiceThr) { hadSpeech = true; silMs = 0; }
                else if (b.rms < silThr) silMs += blkMs;
                else silMs = 0;   // منطقة رمادية: تقطع الصمت المتصل ولا تُعدّ كلاماً

                long fedMs = fedSamples * 1000L / SR;
                long uttMs = fedMs - uttStartMs;
                // القطع بالصمت لا يقع ورموز الكلام حديثة (مدّ/غنّة خافتة تحت العتبة ليست صمتاً)
                // المقطع الطويل جداً (تلاوة متصلة بلا صمت 0.9ث): يُقطع عند نَفَس قصير لا عند أول هبوط —
                // القطع وسط الكلمة يُسقط بقيّتها (التيار الجديد يفقد أول أصواته)
                boolean cut = (hadSpeech && silMs >= UTT_SIL_MS && !lastText.isEmpty() && fedMs - lastTokMs >= TOK_AGE_MS)
                        || (uttMs >= UTT_SOFT_MS && silMs >= UTT_SOFT_SIL_MS)
                        || (uttMs >= UTT_SOFT2_MS && b.rms < voiceThr)
                        || uttMs >= UTT_HARD_MS;
                if (cut) {
                    // قطع المقطع: ذيل 0.8ث أصفار → نهائي → تيار جديد
                    long t1 = System.nanoTime();
                    OnlineRecognizerResult rf;
                    synchronized (lock) {
                        if (g != gen) break;
                        st.acceptWaveform(TAIL_CUT, SR);
                        st.inputFinished();
                        while (rec.isReady(st)) rec.decode(st);
                        rf = rec.getResult(st);
                    }
                    decodeNs += System.nanoTime() - t1;
                    emitPartial(g, js, rf, true, lag);
                    int len = lastText.length();
                    synchronized (lock) {
                        try { st.release(); } catch (Throwable ignored) {}
                        st = null;
                        if (g != gen) break;
                        st = rec.createStream("");
                    }
                    Log.d(TAG, "utt " + utt + " cut at " + fedMs + "ms len=" + uttMs + "ms chars=" + len + " sil=" + silMs);
                    utt++; uttStartMs = fedMs; sentTok = 0; lastText = "";
                    hadSpeech = false; silMs = 0;
                }
                if (fedMs - lastLogMs >= 5000) {
                    lastLogMs = fedMs;
                    long dms = decodeNs / 1000000L;
                    Log.d(TAG, "live t=" + fedMs + "ms rtf=" + (fedMs > 0 ? Math.round(dms * 1000.0 / fedMs) / 1000.0 : 0)
                            + " lag=" + lag + "ms q=" + q.size() + " utt=" + utt + " ev=" + seq
                            + " floor=" + Math.round(floor) + " rms=" + Math.round(b.rms));
                }
            }
        } catch (Throwable t) {
            if (g == gen) {
                Log.e(TAG, "decode error", t);
                JSObject ev = new JSObject();
                ev.put("sess", js);
                ev.put("code", t instanceof OutOfMemoryError ? "oom" : "decode");
                ev.put("msg", String.valueOf(t.getMessage() != null ? t.getMessage() : t));
                emit("asr_error", ev);
            }
        } finally {
            synchronized (lock) {
                if (st != null) { try { st.release(); } catch (Throwable ignored) {} }
                if (activeDec == Thread.currentThread()) activeDec = null;
                if (activeDec == null && !pendingRelease.isEmpty()) {
                    for (OnlineRecognizer pr : pendingRelease) { try { pr.release(); } catch (Throwable ignored) {} }
                    pendingRelease.clear();
                }
            }
            decDeadGen = g;
            if (ended) {
                StopReq p = pendingStop;
                if (p != null && p.gen == g) {
                    JSObject res = sessionStats(p.js, false);
                    Log.i(TAG, "stop " + res.toString());
                    p.finish(res);
                }
            }
        }
    }

    /** حدث asr_partial: النص التراكمي للمقطع + الرموز الجديدة منذ آخر إرسال (from) */
    private void emitPartial(int g, int js, OnlineRecognizerResult r, boolean fin, long lag) {
        String text = clean(r.getText());
        String[] tk = r.getTokens();
        float[] tsa = r.getTimestamps();
        if (tk == null) tk = new String[0];
        if (tsa == null) tsa = new float[0];
        // نص انكمش أو ليس امتداداً للسابق (لم يُرصد): أرسل الكل من 0
        int from = (text.startsWith(lastText) && tk.length >= sentTok) ? sentTok : 0;
        JSArray tok = new JSArray(), ts = new JSArray();
        for (int i = from; i < tk.length; i++) {
            tok.put(tk[i]);
            double v = i < tsa.length ? tsa[i] : 0;
            if (Double.isNaN(v) || Double.isInfinite(v)) v = 0;
            ts.put((Object) Double.valueOf(Math.round(v * 1000.0) / 1000.0));
        }
        seq++;
        JSObject ev = new JSObject();
        ev.put("sess", js);
        ev.put("seq", seq);
        ev.put("utt", utt);
        ev.put("text", text);
        ev.put("from", from);
        ev.put("tok", tok);
        ev.put("ts", ts);
        ev.put("uttStartMs", uttStartMs);
        ev.put("tMs", fedSamples * 1000L / SR);
        ev.put("lagMs", Math.max(0, lag));
        ev.put("final", fin);
        sentTok = tk.length;
        lastText = text;
        if (g == gen) emit("asr_partial", ev);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (stopInternal(call)) return;
        // لا جلسة: إيقاف ما زال يُنهي (بعد onPause) → انتظر نتيجته؛ وإلا حلّ فوراً
        StopReq p = pendingStop;
        if (p != null && p.attach(call)) return;
        JSObject r = new JSObject(); r.put("ok", true);
        call.resolve(r);
    }

    /** إيقاف غير حاجب: الأعلام فوراً، والانضمام/التفريغ/الحلّ على خيط مساعد.
     *  false = لم تكن هناك جلسة. call قد يكون null (onPause). */
    private boolean stopInternal(PluginCall call) {
        final Thread ct, dt; final AudioRecord ar; final ArrayBlockingQueue<Block> q; final StopReq req;
        synchronized (sessLock) {
            if (!listening) return false;
            listening = false; capturing = false;
            ct = capThread; dt = decThread; ar = recorder; q = queue;
            recorder = null; capThread = null;
            req = new StopReq(gen, jsSess, call);
            pendingStop = req;
        }
        abandonFocus();
        Thread h = new Thread(() -> {
            try { if (ct != null) ct.join(1500); } catch (InterruptedException ignored) {}
            try { if (ar != null) ar.stop(); } catch (Throwable ignored) {}
            try { if (ct != null && ct.isAlive()) ct.join(500); } catch (InterruptedException ignored) {}
            try { if (ar != null) ar.release(); } catch (Throwable ignored) {}
            if (q != null && !q.offer(EOS)) { q.poll(); q.offer(EOS); }
            try { if (dt != null) dt.join(4000); } catch (InterruptedException ignored) {}
            boolean to = dt != null && dt.isAlive();
            if (to) Log.w(TAG, "stop: decoder did not finish in 4000ms");
            req.finish(sessionStats(req.js, to));   // لا أثر إن حلّه خيط الفكّ قبلنا
        }, "quranasr-stop");
        synchronized (sessLock) { stopHelper = h; }
        h.start();
        return true;
    }

    /** إيقاف حاجب بلا نتيجة نهائية (unload/الحذف/التدمير): الجيل يتغيّر فتخرج الخيوط */
    private void hardStop() {
        Thread ct, dt; AudioRecord ar;
        synchronized (sessLock) {
            listening = false; capturing = false; gen++;
            ct = capThread; dt = decThread; ar = recorder;
            recorder = null; capThread = null;
        }
        abandonFocus();
        try { if (ct != null) ct.join(1500); } catch (InterruptedException ignored) {}
        try { if (ar != null) ar.stop(); } catch (Throwable ignored) {}
        try { if (ct != null && ct.isAlive()) ct.join(500); } catch (InterruptedException ignored) {}
        try { if (ar != null) ar.release(); } catch (Throwable ignored) {}
        try { if (dt != null) dt.join(2000); } catch (InterruptedException ignored) {}
    }

    private JSObject sessionStats(int js, boolean timeout) {
        JSObject r = new JSObject();
        long audioMs = fedSamples * 1000L / SR;
        long decMs = decodeNs / 1000000L;
        r.put("ok", true);
        r.put("sess", js);
        r.put("audioMs", audioMs);
        r.put("decodeMs", decMs);
        r.put("rtf", audioMs > 0 ? Math.round(decMs * 1000.0 / audioMs) / 1000.0 : 0.0);
        r.put("utts", fedSamples > 0 ? utt + 1 : 0);
        r.put("events", seq);
        r.put("dropped", dropped);
        r.put("maxBacklogMs", maxBacklogMs);
        if (timeout) r.put("timeout", true);
        return r;
    }

    @PluginMethod
    public void stats(PluginCall call) {
        JSObject r = sessionStats(jsSess, false);
        r.put("listening", listening);
        r.put("uttMs", Math.max(0, fedSamples * 1000L / SR - uttStartMs));
        r.put("lastText", lastText);
        r.put("ready", recognizer != null);
        r.put("threads", loadedThreads);
        r.put("loadMs", loadMs);
        r.put("noiseFloor", Math.round(noiseFloor));
        r.put("rms", Math.round(rawRms));
        r.put("rawPeak", rawPeak);
        ArrayBlockingQueue<Block> q = queue;
        r.put("queue", q != null ? q.size() : 0);
        call.resolve(r);
    }

    // ═══ التفريغ والحذف ═══

    @PluginMethod
    public void unload(PluginCall call) {
        ioExec.execute(() -> {
            try { hardStop(); releaseRecognizer(); } catch (Throwable ignored) {}
            Log.i(TAG, "unload");
            JSObject r = new JSObject(); r.put("ok", true);
            safeResolve(call, r);
        });
    }

    @PluginMethod
    public void deleteModel(PluginCall call) {
        cancelReq = true;      // تنزيل جارٍ يتوقّف قبل الحذف (الجزئي يُحذف معه)
        disconnectCur();
        ioExec.execute(() -> {
            try {
                hardStop();
                releaseRecognizer();
                File base = new File(getContext().getFilesDir(), "asr");   // يشمل الإصدارات الأقدم
                long freed = dirSize(base);
                deleteRecursive(base);
                if (base.exists()) { safeReject(call, "io: تعذّر حذف بعض الملفات"); return; }
                Log.i(TAG, "deleteModel freed=" + freed);
                JSObject r = new JSObject(); r.put("ok", true); r.put("freed", freed);
                safeResolve(call, r);
            } catch (Throwable t) {
                safeReject(call, "io: " + t.getMessage());
            }
        });
    }

    /** حرّر المُعرِّف تحت القفل؛ إن كان خيط فكّ ما زال يملك تياراً فالتحرير عند خروجه */
    private void releaseRecognizer() {
        synchronized (lock) {
            OnlineRecognizer r = recognizer;
            recognizer = null; loadedThreads = 0;
            if (r == null) return;
            if (activeDec != null && activeDec.isAlive()) { pendingRelease.add(r); return; }
            activeDec = null;
            try { r.release(); } catch (Throwable ignored) {}
        }
    }

    // ═══ دورة حياة النشاط ═══

    @Override
    protected void handleOnPause() {
        if (listening) {
            int js = jsSess;
            if (stopInternal(null)) {   // لا يُحفظ نداء إيقاف — stop لاحق من JS ينتظر النتيجة
                Log.i(TAG, "pause: session stopped");
                JSObject ev = new JSObject(); ev.put("sess", js); ev.put("reason", "pause");
                emit("asr_interrupt", ev);
            }
        }
        super.handleOnPause();
    }

    /* تحرير كل الموارد عند تدمير النشاط — بلا حجب خيط الواجهة على join */
    @Override
    protected void handleOnDestroy() {
        final Thread ct, dt; final AudioRecord ar;
        synchronized (sessLock) {
            listening = false; capturing = false; gen++;
            ct = capThread; dt = decThread; ar = recorder;
            recorder = null; capThread = null;
        }
        cancelReq = true;
        abandonFocus();
        final HttpURLConnection c = curConn;
        // أوقف المنفّذ أولاً: تحميل جارٍ يرى الإغلاق فيحرّر مُعرِّفه بدل تثبيته
        try { ioExec.shutdownNow(); } catch (Throwable ignored) {}
        try {
            new Thread(() -> {
                try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
                try { if (ct != null) ct.join(500); } catch (InterruptedException ignored) {}
                try { if (ar != null) ar.stop(); } catch (Throwable ignored) {}
                try { if (ct != null && ct.isAlive()) ct.join(300); } catch (InterruptedException ignored) {}
                try { if (ar != null) ar.release(); } catch (Throwable ignored) {}
                try { if (dt != null) dt.join(1500); } catch (InterruptedException ignored) {}
                releaseRecognizer();
            }, "quranasr-destroy").start();
        } catch (Throwable ignored) {}
        super.handleOnDestroy();
    }

    // ═══ أدوات ═══

    private void requestFocus() {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (focusListener == null) {
                /* مكالمة واردة/تطبيق آخر يسحب الصوت → أبلغ JS لينهي الجلسة بملخّص */
                focusListener = fc -> {
                    if ((fc == AudioManager.AUDIOFOCUS_LOSS || fc == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) && listening) {
                        JSObject ev = new JSObject();
                        ev.put("sess", jsSess);
                        ev.put("reason", fc == AudioManager.AUDIOFOCUS_LOSS ? "loss" : "transient");
                        emit("asr_interrupt", ev);
                    }
                };
            }
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        } catch (Throwable ignored) {}
    }

    /* تحرير التركيز الصوتي (آمن إن لم يكن محجوزاً) */
    private void abandonFocus() {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am != null && focusListener != null) am.abandonAudioFocus(focusListener);
        } catch (Throwable ignored) {}
    }

    private void emit(String name, JSObject ev) {
        try { notifyListeners(name, ev); } catch (Throwable ignored) {}
    }

    private void emitDl(FileSpec f, long done, long allDone, long allTotal, int mi) {
        JSObject ev = new JSObject();
        ev.put("phase", "download"); ev.put("file", f.name);
        ev.put("done", done); ev.put("total", f.size);
        JSObject all = new JSObject(); all.put("done", allDone); all.put("total", allTotal);
        ev.put("all", all);
        ev.put("mirror", mi);
        emit("asr_progress", ev);
    }

    private void emitPhase(String phase, String file, long allDone, long allTotal) {
        JSObject ev = new JSObject();
        ev.put("phase", phase); ev.put("file", file);
        JSObject all = new JSObject(); all.put("done", allDone); all.put("total", allTotal);
        ev.put("all", all);
        emit("asr_progress", ev);
    }

    private static void safeResolve(PluginCall c, JSObject r) { try { c.resolve(r); } catch (Throwable ignored) {} }
    private static void safeReject(PluginCall c, String msg) { try { c.reject(msg); } catch (Throwable ignored) {} }

    private void disconnectCur() {
        HttpURLConnection c = curConn;
        if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
    }

    private void checkCancel() throws CancelledIO {
        if (cancelReq || Thread.currentThread().isInterrupted()) throw new CancelledIO();
    }

    private static String clean(String t) {
        if (t == null) return "";
        return t.indexOf(' ') >= 0 ? t.replace(" ", "") : t;
    }

    private File asrDir(boolean create) {
        File d = new File(new File(getContext().getFilesDir(), "asr"), DIR_VER);
        if (create && !d.isDirectory()) d.mkdirs();
        return d;
    }

    private ActivityManager.MemoryInfo memInfo() {
        try {
            ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi;
        } catch (Throwable t) { return null; }
    }

    /** يحلّل [{name,sha256,size}]. err != null: أي مدخل غير صالح يرفض الكل (bad-args)؛
     *  err == null (status): تُتجاهل المدخلات غير الصالحة. */
    private static List<FileSpec> parseFiles(JSArray a, StringBuilder err) {
        List<FileSpec> out = new ArrayList<>();
        if (a == null) { if (err != null) { err.append("files"); return null; } return out; }
        for (int i = 0; i < a.length() && i < 32; i++) {
            JSONObject o = a.optJSONObject(i);
            String name = o == null ? "" : o.optString("name", "");
            String sha = o == null ? "" : o.optString("sha256", "").toLowerCase(Locale.ROOT);
            long size = o == null ? 0 : o.optLong("size", 0);
            boolean ok = name != null && NAME_RE.matcher(name).matches()
                    && !name.endsWith(".part") && !name.startsWith(MARKER)
                    && SHA_RE.matcher(sha).matches() && size > 0 && size <= 1024L * MB;
            if (!ok) {
                if (err != null) { err.append("file ").append(i); return null; }
                continue;
            }
            FileSpec f = new FileSpec();
            f.name = name; f.sha = sha; f.size = size;
            out.add(f);
        }
        if (out.isEmpty() && err != null) { err.append("files"); return null; }
        return out;
    }

    /** الطول الكلي المعلن: 206 من Content-Range، وإلا Content-Length. -1 = غير معلن. */
    private static long declaredTotal(HttpURLConnection c, int code) {
        try {
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr == null) return -1;
                int k = cr.lastIndexOf('/');
                String t = k >= 0 ? cr.substring(k + 1).trim() : "*";
                return "*".equals(t) ? -1 : Long.parseLong(t);
            }
            String cl = c.getHeaderField("Content-Length");
            return cl == null ? -1 : Long.parseLong(cl.trim());
        } catch (Throwable t) { return -1; }
    }

    private static boolean rangeStarts(HttpURLConnection c, long have) {
        try {
            String cr = c.getHeaderField("Content-Range");
            if (cr == null) return false;
            Matcher m = CRANGE_RE.matcher(cr);
            return m.find() && Long.parseLong(m.group(1)) == have;
        } catch (Throwable t) { return false; }
    }

    /** مؤشر المُعرِّف الأصلي (حقل خاص). -1 = تعذّرت القراءة (يُفترض سليماً). */
    private static long nativePtr(OnlineRecognizer r) {
        try {
            java.lang.reflect.Field f = OnlineRecognizer.class.getDeclaredField("ptr");
            f.setAccessible(true);
            return f.getLong(r);
        } catch (Throwable t) { return -1L; }
    }

    private static MessageDigest newSha() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Throwable t) { throw new IOException("sha256-unavailable"); }
    }

    /** يمرّر كامل الملف في md. false = خطأ قراءة. */
    private boolean hashFile(File f, MessageDigest md) throws CancelledIO {
        byte[] b = new byte[131072]; int rd;
        try (InputStream in = new FileInputStream(f)) {
            while ((rd = in.read(b)) > 0) { checkCancel(); md.update(b, 0, rd); }
            return true;
        } catch (CancelledIO c) {
            throw c;
        } catch (Throwable t) {
            return false;
        }
    }

    private String sha256File(File f) throws IOException {
        MessageDigest md = newSha();
        return hashFile(f, md) ? hex(md.digest()) : "";
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    /** نقل ذرّي: احذف الهدف إن وُجد ثم renameTo؛ إن فشل فانسخ ثم احذف المصدر */
    private static boolean promote(File src, File dst) {
        try {
            if (dst.exists() && !dst.delete()) return false;
            if (src.renameTo(dst)) return true;
            long want = src.length();
            byte[] b = new byte[131072]; int rd;
            try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
                while ((rd = in.read(b)) > 0) out.write(b, 0, rd);
                out.flush();
                try { out.getFD().sync(); } catch (Throwable ignored) {}
            }
            boolean ok = dst.length() == want;
            src.delete();
            if (!ok) dst.delete();
            return ok;
        } catch (Throwable t) { return false; }
    }

    private static JSONObject readMarker(File dir) {
        File f = new File(dir, MARKER);
        if (!f.isFile() || f.length() > 65536) return new JSONObject();
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int rd;
            while ((rd = in.read(b)) > 0) bo.write(b, 0, rd);
            return new JSONObject(bo.toString("UTF-8"));
        } catch (Throwable t) { return new JSONObject(); }
    }

    /** العلامة تُكتب .tmp ثم تُنقل — لا علامة نصف مكتوبة أبداً */
    private static boolean writeMarker(File dir, JSONObject mk) {
        File tmp = new File(dir, MARKER + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(mk.toString().getBytes("UTF-8"));
            out.flush();
            try { out.getFD().sync(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            tmp.delete();
            return false;
        }
        return promote(tmp, new File(dir, MARKER));
    }

    /** إسقاط مدخلات من العلامة (فشل التحميل) — ensureModel التالي يعيد هاش الملفات */
    private static void dropMarker(File dir, String... names) {
        try {
            JSONObject mk = readMarker(dir);
            for (String n : names) mk.remove(n);
            JSONArray left = mk.names();
            if (left == null || left.length() == 0) new File(dir, MARKER).delete();
            else writeMarker(dir, mk);
        } catch (Throwable ignored) {}
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long s = 0; File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) s += dirSize(k);
        return s;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        try { f.delete(); } catch (Throwable ignored) {}
    }
}
