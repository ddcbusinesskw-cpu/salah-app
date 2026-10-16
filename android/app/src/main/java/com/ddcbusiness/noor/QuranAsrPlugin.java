package com.ddcbusiness.noor;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import androidx.core.content.FileProvider;

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
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 *  أحداث إضافية: asr_pause (توقّف القارئ ≥400ms) و hold («أعِد الآية»: لا يُغذّى صوت المكبّر للمُعرِّف).
 *  الصوت لا يغادر الجهاز، ولا يُخزَّن إلا إذا فعّل المستخدم «سجّل جلساتي» (start({record})): ملف محلي في
 *  noBackupFilesDir/recordings (خارج النسخ الاحتياطي)، لا يُرفع ولا يُزامَن، ولا يُشارَك إلا بـrecShare الصريح.
 *  الميزة مجانية دائماً (شرط الترخيص). */
@CapacitorPlugin(name = "QuranAsr", permissions = { @Permission(strings = {Manifest.permission.RECORD_AUDIO}, alias = "microphone") })
public class QuranAsrPlugin extends Plugin {

    private static final String TAG = "QuranAsr";
    private static final int SR = 16000;
    private static final int BLOCK = 1600;                  // 100ms: نافذة VAD (تُجمَّع من الكتل الفرعية)
    /* الالتقاط والتغذية بكتل فرعية 20ms: المُعرِّف يفكّ القطعة متى اكتملت عيّناتها بدل انتظار نهاية كتلة 100ms
       (الرموز والطوابع والتقطيع مطابقة حرفياً لتغذية 100ms؛ الحدث يصل أبكر بـ≤80ms). الـVAD يبقى على نوافذ 100ms. */
    private static final int SUB = 320;                     // 20ms لكل كتلة التقاط/تغذية
    private static final int QUEUE_CAP = 3000;              // 60ث (بكتل 20ms) تأخّر أقصى قبل الإسقاط
    private static final String DIR_VER = "v1";
    private static final String MODEL = "zipformer_p_arabic_v3.1.int8.onnx";   // الافتراضي حين لا يسمّي load نموذجاً
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
    private volatile String loadedModel = null;      // اسم ملف النموذج المحمَّل (load({model}))
    private volatile long loadMs = 0;
    private volatile boolean loading = false;
    private volatile int loadGen = 0;                // يُكتب في load() وحده (خيط المكوّن)

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
    /* مؤشّرا تأخّر رخيصان لكل جلسة (JS يحكم بهما على «فترة اختبار» نموذج أثقل): maxQ = أقصى عمق باقٍ للطابور بعد سحب
       كتلة (كتل 20ms)، lagEndMs = تأخّر آخر كتلة غُذّيت (ms بين التقاطها ونهاية فكّها) — يكبر مع الجلسة إن كان الفكّ
       أبطأ من الزمن الحقيقي، ويبقى صغيراً إن لحق */
    private volatile int maxQ = 0;
    private volatile long lagEndMs = 0;
    private volatile String lastText = "";
    private volatile boolean backlogWarned = false;
    private volatile int rawPeak = 0;
    private volatile double rawRms = 0, noiseFloor = VOICE_MIN;
    private int sentTok = 0;                         // رموز أُرسلت في المقطع الحالي (خيط الفكّ فقط)
    private int uttEv = 0;                           // أحداث asr_partial أُرسلت في المقطع الحالي (خيط الفكّ فقط)

    /* ── حدث asr_pause: صمت ≥400ms وآخر رمز أقدم من 400ms — مرّة لكل توقّف (لا يغيّر الفكّ ولا القطع) ── */
    private static final int PAUSE_MS = 400, PAUSE_TOK_MS = 400;

    /* ── «أعِد الآية» (hold): نوافذ بزمن الالتقاط (elapsedRealtime) لا يُغذّى صوتها ولا يُسجَّل ولا يتقدّم به
       الخط الزمني. ما التُقط قبل النافذة يُفكّ عادياً (طابور متأخّر لا يُسقط كلام القارئ)، وما بعدها يُسقط
       حتى ذيل HOLD_TAIL_MS (كمون مكبّر الصوت/الصدى) — فلا يدخل صوت التلاوة المُشغَّلة إلى المُعرِّف أبداً ── */
    private static final int HOLD_TAIL_MS = 200;
    private static final long HOLD_MAX_MS = 300000L;                 // حدّ أمان: نافذة منسيّة تُغلق بعد 5 دقائق (حدث asr_hold)
    private final ArrayDeque<long[]> holdWins = new ArrayDeque<>();   // [t0, t1|MAX] (تحت holdWins)
    private final ArrayList<long[]> recHolds = new ArrayList<>();     // [atMs على خط الجلسة, ms مُسقطة] (تحت holdWins)
    private volatile boolean holding = false;

    /* ── تسجيل الجلسة (اختياري، محلي فقط): noBackupFilesDir/recordings — خارج النسخ الاحتياطي بنظام التشغيل،
       لا يُرفع ولا يُزامَن، ولا يغادر الجهاز إلا بـrecShare الصريح (نسخة مؤقّتة في cache/share تُحذف بعد ساعة) ── */
    private static final Pattern REC_ID_RE = Pattern.compile("^[0-9]{8}-[0-9]{6}(-[0-9]{1,3})?$");
    private static final long LOAD_MIN_AVAIL = 200L * MB;            // ذاكرة متاحة لازمة لبناء مُعرِّف (load)
    private static final long REC_MIN_FREE = 200L * MB;              // شرط بدء التسجيل
    private static final long REC_STOP_FREE = 50L * MB;              // يتوقّف التسجيل (لا الجلسة) تحت هذا
    private static final int REC_DEF_MIN = 60, REC_MAX_MIN = 120;
    private static final long REC_PART_AGE_MS = 60000L;              // .wav.part ليس للجلسة الحيّة وأقدم من هذا = يتيم انهيار يُصلَح
    private static final long SHARE_TTL_MS = 3600000L;               // نسخ المشاركة المؤقّتة
    private static final int REC_READ_MAX = 2 * 1024 * 1024;         // recRead: أقصى قطعة
    private static final long REC_SLICE_MAX_MS = 120000L;            // recSlice: أطول نافذة (≈3.8MB WAV)
    private static final String[] REC_EXT = {".wav.part", ".m4a.part", ".wav", ".m4a"};
    private final Object recFsLock = new Object();                   // كل تعديل لملفات مجلد التسجيلات
    private volatile RecWriter recW = null;                          // كاتب الجلسة الحيّة (خيط الفكّ وحده يكتب)
    private int recGen = -1;                                          // جيل جلسة recW (تحت sessLock)
    private JSObject recRes = null;                                   // نتيجة آخر تسجيل أُنهي…
    private int recResGen = -1;                                       // …وجيل جلسته (تحت sessLock)
    private volatile String encId = null;                            // معرّف يُرمَّز الآن إلى M4A
    private volatile boolean encCancel = false;                      // حُذف أثناء الترميز
    private final ExecutorService recExec = single();                // الترميز وحده (قد يطول) — لا يحجب التنزيل/التحميل
    private final ExecutorService recRdExec = single();              // القائمة/الحذف/القراءة/المشاركة (قصيرة)

    /** كتلة صوت ملتقَطة (20ms): PCM ±1.0 + وقت الالتقاط + مجموع مربّعات int16 الدقيق (نافذة VAD تُجمَّع منه) */
    private static final class Block {
        final float[] pcm; final long tCap; final long sq;
        Block(float[] p, long t, long s) { pcm = p; tCap = t; sq = s; }
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

    /** كاتب WAV لتسجيل الجلسة (PCM 16kHz أحادي 16bit) — بلا اعتماد على أندرويد (يُختبر على JVM).
     *  خيط الفكّ وحده يكتب، وفقط كتلاً حقيقية غُذّيت للمُعرِّف (لا ذيول أصفار، لا نوافذ hold) فتطابق عيّنات الملف
     *  الخطَّ الزمني لأحداث asr_partial (uttStartMs + ts) عيّنةً بعيّنة. مخزن 64KB يُفرَّغ كل ثانية صوت، والرأس
     *  يُرقَّع كل 10ث (الملف صالح للتشغيل بعد انهيار؛ recList يُصلح الباقي من طول الملف). */
    static final class RecWriter {
        static final int HDR = 44, BYTES_PER_SEC = SR * 2, BYTES_PER_MS = BYTES_PER_SEC / 1000;
        static final int FLUSH_BYTES = BYTES_PER_SEC;
        static final long PATCH_BYTES = 10L * BYTES_PER_SEC;
        final String id; final File part, fin; final long maxBytes, stopFree;
        private RandomAccessFile raf;
        private final byte[] buf = new byte[65536];
        private int bn = 0;
        private long lastPatch = 0;
        volatile long dataBytes = 0;                 // عيّنات مقبولة ×2 (في الملف بعد كل تفريغ)
        volatile boolean limited = false, failed = false;
        volatile String err = null;
        boolean limitSent = false, errSent = false;  // خيط الفكّ فقط
        int patches = 0;

        static long maxBytesFor(int maxMin) { return (long) Math.max(1, Math.min(REC_MAX_MIN, maxMin)) * 60L * BYTES_PER_SEC; }

        RecWriter(File dir, String id, long maxBytes, long stopFree) throws IOException {
            this.id = id; this.maxBytes = Math.max(2, maxBytes) & ~1L; this.stopFree = stopFree;
            part = new File(dir, id + ".wav.part"); fin = new File(dir, id + ".wav");
            RandomAccessFile r = new RandomAccessFile(part, "rw");
            try { r.setLength(0); r.write(header(0)); }
            catch (IOException e) { try { r.close(); } catch (Throwable ignored) {} part.delete(); throw e; }
            raf = r;
        }

        static byte[] header(long data) {
            ByteBuffer b = ByteBuffer.allocate(HDR).order(ByteOrder.LITTLE_ENDIAN);
            b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt((int) (36 + data)).put(new byte[]{'W', 'A', 'V', 'E'});
            b.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1)
                    .putInt(SR).putInt(BYTES_PER_SEC).putShort((short) 2).putShort((short) 16);
            b.put(new byte[]{'d', 'a', 't', 'a'}).putInt((int) data);
            return b.array();
        }

        /** يكتب كتلة حقيقية. false = لم تُكتب كاملة أو صار الكاتب متوقّفاً (بلغ الحد / خطأ / مساحة) */
        boolean write(float[] pcm) {
            if (limited || failed || raf == null) return false;
            int n = pcm.length;
            int k = (int) Math.min(n, (maxBytes - dataBytes) / 2);
            try {
                for (int i = 0; i < k; i++) {
                    if (bn + 2 > buf.length) flushBuf();
                    int v = Math.round(pcm[i] * 32768f);             // معكوس دقيق لـ v/32768f عند الالتقاط (بلا فقد)
                    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                    buf[bn++] = (byte) v; buf[bn++] = (byte) (v >> 8);
                }
                dataBytes += 2L * k;
                if (bn >= FLUSH_BYTES) flushBuf();
                if (dataBytes - lastPatch >= PATCH_BYTES) {
                    flushBuf(); patch();
                    long fr = part.getUsableSpace();                 // 0 = تعذّرت القراءة: خطأ الكتابة يحسم
                    if (fr > 0 && fr < stopFree) { failed = true; err = "disk-low"; return false; }
                }
                if (dataBytes >= maxBytes) { flushBuf(); patch(); limited = true; return false; }
            } catch (Throwable t) {
                failed = true; err = msg(t);
                return false;
            }
            return k == n;
        }

        /** المخزن يُصفَّر قبل الكتابة: فشلها لا يُعاد (والطول الحقيقي يُقرأ من الملف عند الإنهاء) */
        private void flushBuf() throws IOException { if (bn > 0) { int n = bn; bn = 0; raf.write(buf, 0, n); } }

        private void patch() throws IOException {
            long p = raf.getFilePointer();
            raf.seek(0); raf.write(header(dataBytes)); raf.seek(p);
            lastPatch = dataBytes; patches++;
        }

        /** تفريغ + رأس نهائي بطول البيانات الفعلي + مزامنة + إغلاق + نقل .part → .wav. true = النهائي موجود */
        boolean finish() {
            if (raf != null) {
                try { flushBuf(); } catch (Throwable t) { if (!failed) { failed = true; err = msg(t); } }
                try {
                    long data = Math.max(0L, Math.min(dataBytes, raf.length() - HDR)) & ~1L;
                    dataBytes = data;
                    raf.setLength(HDR + data);
                    raf.seek(0); raf.write(header(data));
                } catch (Throwable ignored) {}
                try { raf.getFD().sync(); } catch (Throwable ignored) {}
                try { raf.close(); } catch (Throwable ignored) {}
                raf = null;
            }
            return part.isFile() ? promote(part, fin) : fin.isFile();
        }

        long ms() { return dataBytes / BYTES_PER_MS; }

        /** إصلاح .wav.part يتيم (انهيار/قتل): الرأس من طول الملف ثم النقل. ليس WAV منّا أو < 44 بايت → يُحذف */
        static boolean repair(File part, File fin) {
            long len = part.length();
            boolean ours = false;
            if (len >= HDR) {
                try (RandomAccessFile r = new RandomAccessFile(part, "rw")) {
                    byte[] h = new byte[12]; r.readFully(h);
                    ours = h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E';
                    if (ours) {
                        long data = (len - HDR) & ~1L;
                        r.setLength(HDR + data); r.seek(0); r.write(header(data));
                    }
                } catch (Throwable t) { return false; }
            }
            if (!ours) { part.delete(); return false; }
            return promote(part, fin);
        }

        private static String msg(Throwable t) { return String.valueOf(t.getMessage() != null ? t.getMessage() : t); }
    }

    /** مدة M4A (ms) من moov/mvhd بلا مكتبات — يثبت أيضاً أن الملف مكتمل (moov يُكتب عند الإنهاء). -1 = تعذّر */
    static long mp4DurationMs(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long[] moov = mp4Box(r, 0, r.length(), "moov");
            long[] mvhd = moov == null ? null : mp4Box(r, moov[0], moov[1], "mvhd");
            if (mvhd == null || mvhd[1] - mvhd[0] < 24) return -1;
            r.seek(mvhd[0]);
            int ver = r.readUnsignedByte(); r.skipBytes(3);
            long ts, dur;
            if (ver == 1) { r.skipBytes(16); ts = r.readInt() & 0xFFFFFFFFL; dur = r.readLong(); }
            else { r.skipBytes(8); ts = r.readInt() & 0xFFFFFFFFL; dur = r.readInt() & 0xFFFFFFFFL; }
            if (ts <= 0 || dur < 0) return -1;
            return dur * 1000L / ts;
        } catch (Throwable t) { return -1; }
    }

    /** صندوق MP4 من نوع type بين [from,to): {بداية الحمولة، النهاية} أو null */
    private static long[] mp4Box(RandomAccessFile r, long from, long to, String type) throws IOException {
        byte[] want = type.getBytes(StandardCharsets.US_ASCII), t = new byte[4];
        long p = from;
        for (int guard = 0; p + 8 <= to && guard < 4096; guard++) {
            r.seek(p);
            long size = r.readInt() & 0xFFFFFFFFL;
            r.readFully(t);
            long hdr = 8;
            if (size == 1) { size = r.readLong(); hdr = 16; }
            else if (size == 0) size = to - p;
            if (size < hdr || p + size > to) return null;
            if (Arrays.equals(t, want)) return new long[]{p + hdr, p + size};
            p += size;
        }
        return null;
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
            String lm = loadedModel;
            r.put("model", recognizer != null && lm != null ? lm : "");
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
        /* model (اختياري): اسم ملف نموذج مُتحقَّق في مجلد المحرّك — JS يختار الأحدث المُتحقَّق ويبقى على السابق
           حتى يكتمل تنزيل الجديد؛ الإعداد (حجم القطعة…) يُقرأ من بيانات ONNX الوصفية فلا تغيير آخر هنا */
        String mn = call.getString("model", MODEL);
        if (mn == null || mn.isEmpty()) mn = MODEL;
        if (!NAME_RE.matcher(mn).matches() || !mn.endsWith(".onnx")) { call.reject("bad-args: model"); return; }
        final String modelName = mn;
        final int lg = ++loadGen;
        loading = true;
        ioExec.execute(() -> {
            try { doLoad(call, threads, modelName); }
            catch (Throwable t) { loadReject(call, "init: " + t.getMessage()); }
            finally { if (loadGen == lg) loading = false; }   // load أحدث بدأ بعد تسليم النتيجة: علمه له
        });
    }

    /* نتيجة load: «loading» يُصفَّر قبل تسليمها — وإلا فاستدعاء load تالٍ فور وصولها (عودة إلى chunk-24 بعد فشل chunk-16، أو
       إعادة تحميل بعد low-mem) قد يسبق finally فيُرفض «busy» بلا سبب */
    private void loadResolve(PluginCall call, JSObject r) { loading = false; safeResolve(call, r); }
    private void loadReject(PluginCall call, String msg) { loading = false; safeReject(call, msg); }

    private void doLoad(PluginCall call, int threads, String modelName) throws Exception {
        File dir = asrDir(false);
        File model = new File(dir, modelName), tokens = new File(dir, TOKENS);
        JSONObject mk = readMarker(dir);
        if (!model.isFile() || !tokens.isFile()
                || !SHA_RE.matcher(mk.optString(modelName, "")).matches()
                || !SHA_RE.matcher(mk.optString(TOKENS, "")).matches()) {
            loadReject(call, "not-verified");
            return;
        }
        if (recognizer != null && loadedThreads == threads && modelName.equals(loadedModel)) {
            JSObject r = new JSObject();
            r.put("ok", true); r.put("ms", 0); r.put("threads", threads); r.put("cached", true); r.put("model", modelName);
            loadResolve(call, r);
            return;
        }
        if (listening) { loadReject(call, "busy"); return; }
        /* مُعرِّف يعمل (تبديل نموذج، مثل chunk-24 → chunk-16): افحص الذاكرة قبل تحريره. الرفض هنا «low-mem: kept …»
           يُبقي القديم محمَّلاً وجاهزاً (status.model لم يتغيّر) — لا يُحرَّر ثم يُرفض الجديد فيبقى المستخدم بلا محرّك.
           العتبة نفسها والقديم ما زال في الذاكرة: أحوط (بعد تحريره يتوفّر أكثر) */
        if (recognizer != null) {
            ActivityManager.MemoryInfo m0 = memInfo();
            if (m0 != null && (m0.lowMemory || m0.availMem < LOAD_MIN_AVAIL)) {
                Log.w(TAG, "load: low-mem before replacing " + loadedModel + " avail=" + (m0.availMem / MB) + "MB low=" + m0.lowMemory + " — kept");
                loadReject(call, "low-mem: kept " + (m0.availMem / MB));
                return;
            }
        }
        releaseRecognizer();   // حرّر القديم قبل بناء الجديد — لا نسختان في الذاكرة
        ActivityManager.MemoryInfo mi = memInfo();
        if (mi != null && (mi.lowMemory || mi.availMem < LOAD_MIN_AVAIL)) {
            Log.w(TAG, "load: low-mem avail=" + (mi.availMem / MB) + "MB low=" + mi.lowMemory);
            loadReject(call, "low-mem: " + (mi.availMem / MB));
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
            loadReject(call, "low-mem: oom");
            return;
        } catch (Throwable t) {
            Log.w(TAG, "load: init failed " + t);
            dropLoadMarker(dir, modelName);   // ensureModel التالي يعيد الهاش
            loadReject(call, "init: " + t.getMessage());
            return;
        }
        long ms = SystemClock.elapsedRealtime() - t0;
        /* تحقّق إعداد مرفوض في الطبقة الأصلية يعيد مؤشراً صفرياً بلا استثناء — أي استدعاء
           بعده ينهار؛ افحص المؤشر (انعكاساً) وارفض بدل الانهيار */
        if (nativePtr(rec) == 0L) {
            Log.w(TAG, "load: native config rejected");
            dropLoadMarker(dir, modelName);
            loadReject(call, "init: config rejected");
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
            dropLoadMarker(dir, modelName);
            loadReject(call, "init: warmup " + t.getMessage());
            return;
        }
        long warmMs = SystemClock.elapsedRealtime() - w0;
        synchronized (lock) {
            // أُغلق المنفّذ (تدمير النشاط) أثناء البناء → لا تثبّت مُعرِّفاً لن يُحرَّر
            if (ioExec.isShutdown()) { try { rec.release(); } catch (Throwable ignored) {} return; }
            recognizer = rec; loadedThreads = threads; loadMs = ms; loadedModel = modelName;
        }
        Log.i(TAG, "load ok model=" + modelName + " ms=" + ms + " warm=" + warmMs + " threads=" + threads + " cores=" + cores());
        JSObject r = new JSObject();
        r.put("ok", true); r.put("ms", ms); r.put("threads", threads); r.put("warmMs", warmMs); r.put("model", modelName);
        loadResolve(call, r);
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

        /* تسجيل الجلسة (اختياري): فحوص رخيصة قبل فتح المايك. أي رفض rec-* → JS يبدأ بلا تسجيل */
        String recId = null; long recMax = 0;
        JSObject recArg = call.getObject("record");
        if (recArg != null) {
            recId = recArg.getString("id", "");
            if (!recIdOk(recId)) { call.reject("rec-bad-id"); return; }
            recMax = RecWriter.maxBytesFor((int) Math.max(1, Math.min(REC_MAX_MIN, numArg(recArg, "maxMin", REC_DEF_MIN))));
            File rd;
            try { rd = recDir(); } catch (Throwable t) { call.reject("rec-io: " + t.getMessage()); return; }
            if (!rd.isDirectory()) { call.reject("rec-io: mkdir"); return; }
            if (rd.getUsableSpace() < REC_MIN_FREE) { call.reject("rec-disk"); return; }
            if (new File(rd, recId + ".wav").exists() || new File(rd, recId + ".m4a").exists()) { call.reject("rec-exists"); return; }
        }

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
        RecWriter rw0 = null;
        if (recId != null) {
            try {
                // يُعلَن نشطاً تحت قفل الملفات نفسه الذي أنشأه: لا حذف/إصلاح يلمس .part الجديد
                synchronized (recFsLock) { rw0 = new RecWriter(recDir(), recId, recMax, REC_STOP_FREE); recW = rw0; }
            } catch (Throwable t) {
                // كفرع mic-busy: حرّر كل ما فُتح
                abandonFocus();
                try { ar.stop(); } catch (Throwable ignored) {}
                try { ar.release(); } catch (Throwable ignored) {}
                synchronized (lock) { try { st.release(); } catch (Throwable ignored) {} }
                call.reject("rec-io: " + t.getMessage());
                return;
            }
        }
        final RecWriter rw = rw0;
        Integer js0 = call.getInt("sess", 0);
        final int js = js0 == null ? 0 : js0;
        final ArrayBlockingQueue<Block> q = new ArrayBlockingQueue<>(QUEUE_CAP);
        final int g;
        synchronized (sessLock) {
            g = ++gen;
            jsSess = js;
            seq = 0; utt = 0; dropped = 0; sentTok = 0; uttEv = 0;
            fedSamples = 0; decodeNs = 0; maxBacklogMs = 0; uttStartMs = 0; maxQ = 0; lagEndMs = 0;
            lastText = ""; backlogWarned = false; rawPeak = 0; rawRms = 0; noiseFloor = VOICE_MIN;
            recorder = ar; queue = q; pendingStop = null;
            recW = rw; recGen = g; recRes = null; recResGen = -1;
            synchronized (holdWins) { holdWins.clear(); recHolds.clear(); holding = false; }
            capturing = true; listening = true;
        }
        Thread dt = new Thread(() -> decLoop(g, js, q, rec, st, rw), "quranasr-decode");
        Thread ct = new Thread(() -> capLoop(g, js, q, ar), "quranasr-capture");
        ct.setPriority(Thread.MAX_PRIORITY);
        synchronized (lock) { activeDec = dt; }
        synchronized (sessLock) { decThread = dt; capThread = ct; stopHelper = null; }
        dt.start();
        ct.start();
        Log.i(TAG, "start sess=" + js + " gen=" + g + " threads=" + loadedThreads + " minBuf=" + minBuf + (rw != null ? " rec=" + rw.id : ""));
        JSObject r = new JSObject();
        r.put("ok", true); r.put("sess", js); r.put("sampleRate", SR);
        if (rw != null) { JSObject ro = new JSObject(); ro.put("id", rw.id); ro.put("maxMs", rw.maxBytes / RecWriter.BYTES_PER_MS); r.put("rec", ro); }
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

    /** خيط الالتقاط: كتل 20ms → float ±1.0 → الطابور (يُسقط الأقدم عند الامتلاء) */
    private void capLoop(final int g, final int js, final ArrayBlockingQueue<Block> q, final AudioRecord ar) {
        short[] sb = new short[SUB];
        while (capturing && g == gen) {
            int n;
            try { n = ar.read(sb, 0, SUB); } catch (Throwable t) { n = -1000; }
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
                Block blk = new Block(f, tCap, sq);
                if (!q.offer(blk)) {
                    dropped++;
                    if (!backlogWarned) {
                        backlogWarned = true;
                        Log.w(TAG, "backlog: queue full, dropping oldest");
                        JSObject ev = new JSObject();
                        ev.put("sess", js); ev.put("code", "backlog");
                        ev.put("msg", "decoder behind by >" + (QUEUE_CAP * SUB / SR) + "s");
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

    /** خيط الفكّ: يملك التيار؛ تغذية → فكّ → حدث عند تغيّر النص → VAD → توقّف/قطع المقطع.
     *  w (اختياري): كاتب تسجيل الجلسة — يُكتب فيه ما غُذّي فقط، ويُنهى في finally قبل حلّ الإيقاف. */
    private void decLoop(final int g, final int js, final ArrayBlockingQueue<Block> q,
                         final OnlineRecognizer rec, final OnlineStream first, final RecWriter w) {
        final OnlineStream[] cur = {first};     // التيار الحالي (cutUtt يستبدله؛ finally يحرّر ما بقي)
        boolean ended = false;
        /* أرضية الضجيج = أدنى متوسط متحرّك (0.5ث) خلال آخر 30ث: لا تصعد إلى مستوى الكلام في التلاوة المتصلة
           (وسيط أهدأ 20% من آخر 8ث كان يصعد مع الكلام المتصل فيقطع المقطع وسط الكلمة ويُسقطها) */
        final double[] sm = new double[FLOOR_AVG_BLK];
        int smN = 0, smPos = 0; double smSum = 0;
        final double[] ring = new double[FLOOR_WIN_BLK];
        int ringN = 0, ringPos = 0;
        long lastTokMs = Long.MIN_VALUE / 4;    // زمن آخر رمز (ms صوتية منذ بدء الجلسة)
        double floor = VOICE_MIN;
        boolean hadSpeech = false, pauseSent = false, inHold = false;
        int silMs = 0;
        long vSq = 0; int vN = 0;               // نافذة VAD الجارية (100ms) تُجمَّع من الكتل الفرعية المُغذّاة
        long lastTCap = SystemClock.elapsedRealtime(), lastLogMs = 0;
        try {
            while (g == gen) {
                Block b;
                try { b = q.poll(250, TimeUnit.MILLISECONDS); } catch (InterruptedException ie) { break; }
                if (b == null) continue;
                int qn = q.size();                     // الباقي خلف هذه الكتلة (يتراكم إن كان الفكّ أبطأ من المايك)
                if (qn > maxQ) maxQ = qn;
                if (b.pcm.length == 0) {
                    // نهاية الجلسة: ذيل 0.8ث ثم إنهاء الإدخال وتفريغ آخر الرموز
                    long t0 = System.nanoTime();
                    OnlineRecognizerResult r;
                    synchronized (lock) {
                        if (g != gen) break;
                        OnlineStream st = cur[0];
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
                // ── «أعِد الآية»: كتلة داخل نافذة hold تُسقط (لا تغذية، لا تسجيل، لا تقدّم للخط الزمني) ──
                if (heldBlock(b.tCap, b.pcm.length * 1000L / SR, g, js)) {
                    long fedMs = fedSamples * 1000L / SR;
                    if (!inHold) {
                        inHold = true; hadSpeech = false; silMs = 0; pauseSent = false;
                        vSq = 0; vN = 0;        // نافذة VAD الناقصة قبل hold تُهمل (الحالة تُصفَّر على أي حال)
                        holdMark(fedMs, 0);
                        // التيار غُذّي صوتاً منذ آخر قطع: أنهِ المقطع كقطع الصمت (بلا حدث إن لم يكن فيه شيء)
                        if (fedMs > uttStartMs && !cutUtt(g, js, rec, cur, SystemClock.elapsedRealtime() - b.tCap, true, "hold")) break;
                    }
                    holdMark(-1, b.pcm.length * 1000L / SR);
                    lastTCap = b.tCap;
                    continue;
                }
                if (inHold) { inHold = false; hadSpeech = false; silMs = 0; pauseSent = false; }
                long t0 = System.nanoTime();
                OnlineRecognizerResult r = null;
                synchronized (lock) {
                    if (g != gen) break;
                    OnlineStream st = cur[0];
                    st.acceptWaveform(b.pcm, SR);
                    boolean decoded = false;
                    while (rec.isReady(st)) { rec.decode(st); decoded = true; }
                    // النتيجة لا تتغيّر بلا فكّ: لا استدعاء JNI لها في كتل 20ms التي لم تُكمل قطعة
                    if (decoded) r = rec.getResult(st);
                }
                decodeNs += System.nanoTime() - t0;
                fedSamples += b.pcm.length;
                // التسجيل: الكتلة نفسها التي غُذّيت للتوّ — عيّنات الملف = الخط الزمني للأحداث
                if (w != null && !w.limited && !w.failed && !w.write(b.pcm)) recTrouble(g, js, w);
                long lag = SystemClock.elapsedRealtime() - b.tCap;
                lastTCap = b.tCap;
                if (lag > maxBacklogMs) maxBacklogMs = lag;
                lagEndMs = lag;
                if (r != null) {
                    if (!clean(r.getText()).equals(lastText)) emitPartial(g, js, r, false, lag);
                    float[] tsl = r.getTimestamps();
                    if (tsl != null && tsl.length > 0) lastTokMs = uttStartMs + Math.round(tsl[tsl.length - 1] * 1000.0);
                }

                // ── نافذة VAD = 100ms مُغذّاة (5 كتل فرعية): القرار والقطع على حدودها فقط، كما كانا بكتل 100ms ──
                vSq += b.sq; vN += b.pcm.length;
                if (vN < BLOCK) continue;
                final double rms = Math.sqrt((double) vSq / vN);
                int blkMs = (int) (vN * 1000L / SR);
                vSq = 0; vN = 0;

                // ── VAD متكيّف: أرضية = أدنى متوسط 0.5ث خلال 30ث (العتبتان كما في محرّك whisper) ──
                smSum += rms - sm[smPos]; sm[smPos] = rms; smPos = (smPos + 1) % sm.length; if (smN < sm.length) smN++;
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
                if (rms > voiceThr) { hadSpeech = true; silMs = 0; pauseSent = false; }
                else if (rms < silThr) silMs += blkMs;
                else silMs = 0;   // منطقة رمادية: تقطع الصمت المتصل ولا تُعدّ كلاماً

                long fedMs = fedSamples * 1000L / SR;
                long uttMs = fedMs - uttStartMs;
                // توقّف القارئ (أبكر من القطع): JS يُنهي الكلمة الأخيرة ليّناً ويقيّم التجويد — مرّة لكل توقّف
                if (!pauseSent && hadSpeech && silMs >= PAUSE_MS && fedMs - lastTokMs >= PAUSE_TOK_MS && !holding) {
                    pauseSent = true;
                    emitPause(g, js, fedMs, silMs, lastTokMs);
                }
                // القطع بالصمت لا يقع ورموز الكلام حديثة (مدّ/غنّة خافتة تحت العتبة ليست صمتاً)
                // المقطع الطويل جداً (تلاوة متصلة بلا صمت 0.9ث): يُقطع عند نَفَس قصير لا عند أول هبوط —
                // القطع وسط الكلمة يُسقط بقيّتها (التيار الجديد يفقد أول أصواته)
                boolean cut = (hadSpeech && silMs >= UTT_SIL_MS && !lastText.isEmpty() && fedMs - lastTokMs >= TOK_AGE_MS)
                        || (uttMs >= UTT_SOFT_MS && silMs >= UTT_SOFT_SIL_MS)
                        || (uttMs >= UTT_SOFT2_MS && rms < voiceThr)
                        || uttMs >= UTT_HARD_MS;
                if (cut) {
                    // قطع المقطع: ذيل 0.8ث أصفار → نهائي → تيار جديد
                    if (!cutUtt(g, js, rec, cur, lag, false, "sil=" + silMs)) break;
                    hadSpeech = false; silMs = 0;
                }
                if (fedMs - lastLogMs >= 5000) {
                    lastLogMs = fedMs;
                    long dms = decodeNs / 1000000L;
                    Log.d(TAG, "live t=" + fedMs + "ms rtf=" + (fedMs > 0 ? Math.round(dms * 1000.0 / fedMs) / 1000.0 : 0)
                            + " lag=" + lag + "ms q=" + q.size() + " utt=" + utt + " ev=" + seq
                            + " floor=" + Math.round(floor) + " rms=" + Math.round(rms));
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
                if (cur[0] != null) { try { cur[0].release(); } catch (Throwable ignored) {} cur[0] = null; }
                if (activeDec == Thread.currentThread()) activeDec = null;
                if (activeDec == null && !pendingRelease.isEmpty()) {
                    for (OnlineRecognizer pr : pendingRelease) { try { pr.release(); } catch (Throwable ignored) {} }
                    pendingRelease.clear();
                }
            }
            // التسجيل يُنهى هنا في كل نهاية (إيقاف/onPause/خطأ/تفريغ/تدمير) وقبل حلّ الإيقاف فيحمل stop().rec
            if (w != null) {
                JSObject rr = null;
                try { rr = finishRec(w); } catch (Throwable t) { Log.w(TAG, "rec finish " + t); }
                synchronized (sessLock) {
                    if (rr != null) { recRes = rr; recResGen = g; }
                    if (recW == w) recW = null;
                }
                if (rr != null) {
                    JSObject ev = new JSObject();
                    ev.put("sess", js); ev.put("kind", "saved");
                    java.util.Iterator<String> it = rr.keys();
                    while (it.hasNext()) { String k = it.next(); ev.put(k, rr.opt(k)); }
                    emit("rec_event", ev);      // إشعار موثوق حتى إن ضاعت نتيجة stop (onPause/تفريغ)
                }
            }
            decDeadGen = g;
            if (ended) {
                StopReq p = pendingStop;
                if (p != null && p.gen == g) {
                    JSObject res = sessionStats(p.js, false, g);
                    Log.i(TAG, "stop " + res.toString());
                    p.finish(res);
                }
            }
        }
    }

    /** قطع المقطع: ذيل TAIL_CUT أصفار → نهائي (final:true) → تيار جديد، utt++. quiet: مقطع بلا أي حدث ولا
     *  نص بعد الذيل يُستبدل تياره بصمت (لا حدث ولا utt++). false = تغيّر الجيل (يخرج المستدعي). */
    private boolean cutUtt(int g, int js, OnlineRecognizer rec, OnlineStream[] cur, long lag, boolean quiet, String why) {
        long t1 = System.nanoTime();
        OnlineRecognizerResult rf;
        synchronized (lock) {
            if (g != gen) return false;
            OnlineStream st = cur[0];
            st.acceptWaveform(TAIL_CUT, SR);
            st.inputFinished();
            while (rec.isReady(st)) rec.decode(st);
            rf = rec.getResult(st);
        }
        decodeNs += System.nanoTime() - t1;
        boolean silent = quiet && uttEv == 0 && clean(rf.getText()).isEmpty();
        if (!silent) emitPartial(g, js, rf, true, lag);
        int len = lastText.length();
        synchronized (lock) {
            try { cur[0].release(); } catch (Throwable ignored) {}
            cur[0] = null;
            if (g != gen) return false;
            cur[0] = rec.createStream("");
        }
        long fedMs = fedSamples * 1000L / SR;
        Log.d(TAG, "utt " + utt + " cut(" + why + ") at " + fedMs + "ms len=" + (fedMs - uttStartMs) + "ms chars=" + len + (silent ? " silent" : ""));
        if (!silent) utt++;
        uttStartMs = fedMs; sentTok = 0; lastText = ""; uttEv = 0;
        return true;
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
        seq++; uttEv++;
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

    /** حدث asr_pause {sess, utt, seq (آخر asr_partial), tMs, silMs, lastTokMs (-1 = لا رمز بعد)} */
    private void emitPause(int g, int js, long fedMs, int silMs, long lastTokMs) {
        JSObject ev = new JSObject();
        ev.put("sess", js);
        ev.put("utt", utt);
        ev.put("seq", seq);
        ev.put("tMs", fedMs);
        ev.put("silMs", silMs);
        ev.put("lastTokMs", lastTokMs < 0 ? -1 : lastTokMs);
        if (g == gen) emit("asr_pause", ev);
    }

    /** هل الكتلة (نهاية التقاطها tCap، مدتها durMs) داخل نافذة hold؟ الكتل بترتيب الالتقاط، فتُسقط النوافذ المنتهية
     *  (بعد الذيل) من الأمام. نافذة مفتوحة أطول من HOLD_MAX_MS تُغلق (حدث asr_hold reason:timeout). */
    private boolean heldBlock(long tCap, long durMs, int g, int js) {
        boolean expired = false, held = false;
        synchronized (holdWins) {
            long[] w;
            while ((w = holdWins.peekFirst()) != null) {
                if (w[1] == Long.MAX_VALUE && tCap - w[0] > HOLD_MAX_MS) { w[1] = w[0] + HOLD_MAX_MS; holding = false; expired = true; }
                if (w[1] != Long.MAX_VALUE && tCap - durMs >= w[1] + HOLD_TAIL_MS) { holdWins.pollFirst(); continue; }
                held = tCap > w[0];
                break;
            }
        }
        if (expired && g == gen) {
            Log.w(TAG, "hold: timeout → released");
            JSObject ev = new JSObject(); ev.put("sess", js); ev.put("holding", false); ev.put("reason", "timeout");
            emit("asr_hold", ev);
        }
        return held;
    }

    /** سجلّ نوافذ hold على خط الجلسة: at ≥ 0 يفتح مدخلاً [at, 0]؛ at < 0 يضيف ms مُسقطة لآخر مدخل */
    private void holdMark(long at, long ms) {
        synchronized (holdWins) {
            if (at >= 0) recHolds.add(new long[]{at, 0});
            else if (!recHolds.isEmpty()) recHolds.get(recHolds.size() - 1)[1] += ms;
        }
    }

    private JSArray holdsJson() {
        JSArray a = new JSArray();
        synchronized (holdWins) {
            for (long[] h : recHolds) { JSArray x = new JSArray(); x.put(h[0]); x.put(h[1]); a.put(x); }
        }
        return a;
    }

    /** الكاتب توقّف (حد/خطأ/مساحة): حدث rec_event مرّة واحدة لكل نوع؛ الجلسة مستمرّة بلا تسجيل */
    private void recTrouble(int g, int js, RecWriter w) {
        JSObject ev = new JSObject();
        ev.put("sess", js); ev.put("id", w.id); ev.put("ms", w.ms());
        if (w.limited && !w.limitSent) {
            w.limitSent = true; ev.put("kind", "limit");
            Log.i(TAG, "rec limit " + w.id + " ms=" + w.ms());
        } else if (w.failed && !w.errSent) {
            w.errSent = true; ev.put("kind", "error"); ev.put("msg", w.err == null ? "" : w.err);
            Log.w(TAG, "rec error " + w.id + ": " + w.err);
        } else return;
        if (g == gen) emit("rec_event", ev);
    }

    /** يُنهي ملف الجلسة → نتيجة stop().rec */
    private JSObject finishRec(RecWriter w) {
        boolean ok = w.finish();
        JSObject r = recInfo(w, false);
        if (!ok) r.put("err", "rename");
        Log.i(TAG, "rec saved " + w.id + " ms=" + w.ms() + " ok=" + ok + (w.limited ? " limited" : "") + (w.failed ? " failed=" + w.err : ""));
        return r;
    }

    /** {id, file, ms, bytes, holds:[[atMs,ms]…], limited[, failed, err][, pending]} */
    private JSObject recInfo(RecWriter w, boolean pending) {
        JSObject r = new JSObject();
        r.put("id", w.id);
        r.put("file", w.id + ".wav");
        r.put("format", "wav");
        r.put("ms", w.ms());
        r.put("bytes", !pending && w.fin.isFile() ? w.fin.length() : RecWriter.HDR + w.dataBytes);
        r.put("holds", holdsJson());
        r.put("limited", w.limited);
        if (w.failed) { r.put("failed", true); r.put("err", w.err == null ? "" : w.err); }
        if (pending) r.put("pending", true);
        return r;
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
            listening = false; capturing = false; holding = false;   // نوافذ hold تبقى: ما في الطابور منها يُسقط
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
            req.finish(sessionStats(req.js, to, req.gen));   // لا أثر إن حلّه خيط الفكّ قبلنا
        }, "quranasr-stop");
        synchronized (sessLock) { stopHelper = h; }
        h.start();
        return true;
    }

    /** إيقاف حاجب بلا نتيجة نهائية (unload/الحذف/التدمير): الجيل يتغيّر فتخرج الخيوط */
    private void hardStop() {
        Thread ct, dt; AudioRecord ar;
        synchronized (sessLock) {
            listening = false; capturing = false; holding = false; gen++;
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

    /** ملخّص الجلسة g. rec: نتيجة التسجيل المُنهى، أو {…, pending:true} إن كان ما زال يُكتب
     *  (مهلة الإيقاف تحت تأخّر شديد — يصل rec_event{kind:'saved'} حين يُنهى) */
    private JSObject sessionStats(int js, boolean timeout, int g) {
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
        r.put("maxQ", maxQ);
        r.put("lagEndMs", lagEndMs);
        if (timeout) r.put("timeout", true);
        JSObject rr = null; RecWriter aw = null;
        synchronized (sessLock) {
            if (recRes != null && recResGen == g) rr = recRes;
            else if (recW != null && recGen == g) aw = recW;
        }
        if (rr != null) r.put("rec", rr);
        else if (aw != null) r.put("rec", recInfo(aw, true));
        return r;
    }

    @PluginMethod
    public void stats(PluginCall call) {
        JSObject r = sessionStats(jsSess, false, gen);
        r.put("listening", listening);
        r.put("holding", holding);
        r.put("uttMs", Math.max(0, fedSamples * 1000L / SR - uttStartMs));
        r.put("lastText", lastText);
        r.put("ready", recognizer != null);
        String lm = loadedModel;
        r.put("model", recognizer != null && lm != null ? lm : "");
        r.put("threads", loadedThreads);
        r.put("loadMs", loadMs);
        r.put("noiseFloor", Math.round(noiseFloor));
        r.put("rms", Math.round(rawRms));
        r.put("rawPeak", rawPeak);
        ArrayBlockingQueue<Block> q = queue;
        r.put("queue", q != null ? q.size() : 0);
        call.resolve(r);
    }

    // ═══ «أعِد الآية» ═══

    /** hold({on}): on=true → ما يُلتقط من الآن لا يُغذّى للمُعرِّف ولا يُسجَّل (المقطع الجاري يُنهى أولاً)؛
     *  on=false → يُستأنف بعد ذيل HOLD_TAIL_MS. يحلّ {ok, holding}؛ ok:false بلا جلسة (JS لا يشغّل الصوت حينها). */
    @PluginMethod
    public void hold(PluginCall call) {
        Boolean on0 = call.getBoolean("on", Boolean.TRUE);
        final boolean on = on0 == null || on0;
        boolean ok;
        synchronized (sessLock) {
            ok = listening;
            if (ok) {
                long now = SystemClock.elapsedRealtime();
                synchronized (holdWins) {
                    long[] last = holdWins.peekLast();
                    boolean open = last != null && last[1] == Long.MAX_VALUE;
                    if (on && !open) holdWins.addLast(new long[]{now, Long.MAX_VALUE});
                    else if (!on && open) last[1] = now;
                    holding = on;
                }
            }
        }
        if (ok) Log.i(TAG, "hold " + on);
        JSObject r = new JSObject();
        r.put("ok", ok); r.put("holding", ok && on);
        call.resolve(r);
    }

    // ═══ تسجيلات الجلسات (على الجهاز فقط) ═══

    private File recDir() {
        File d = new File(getContext().getNoBackupFilesDir(), "recordings");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static boolean recIdOk(String id) { return id != null && REC_ID_RE.matcher(id).matches(); }

    /** معرّف التسجيل من اسم ملف المجلد (<id>.wav|.m4a|.wav.part|.m4a.part)؛ null لغير ذلك */
    private static String recIdOf(String name) {
        for (String e : REC_EXT) if (name.endsWith(e)) { String id = name.substring(0, name.length() - e.length()); return recIdOk(id) ? id : null; }
        return null;
    }

    private String activeRecId() { RecWriter w = recW; return w != null ? w.id : null; }

    /** الملف النهائي: M4A مكتمل إن وُجد وإلا WAV؛ null = غير موجود (أو ما زال يُسجَّل) */
    private File recFile(String id) {
        if (id.equals(activeRecId())) return null;
        File d = recDir();
        File m = new File(d, id + ".m4a");
        if (m.isFile() && m.length() > 0 && !id.equals(encId)) return m;
        File w = new File(d, id + ".wav");
        if (w.isFile() && w.length() >= RecWriter.HDR) return w;
        if (m.isFile() && m.length() > 0) return m;
        return null;
    }

    private static boolean isM4a(File f) { return f.getName().endsWith(".m4a"); }
    private static String mimeOf(File f) { return isM4a(f) ? "audio/mp4" : "audio/wav"; }
    private static long recMs(File f) { return isM4a(f) ? mp4DurationMs(f) : Math.max(0, f.length() - RecWriter.HDR) / RecWriter.BYTES_PER_MS; }

    private static JSObject recItem(String id, File f) {
        JSObject o = new JSObject();
        o.put("id", id); o.put("file", f.getName()); o.put("format", isM4a(f) ? "m4a" : "wav");
        o.put("bytes", f.length()); o.put("ms", recMs(f)); o.put("mtime", f.lastModified());
        return o;
    }

    private static long numArg(JSONObject o, String k, long def) {
        Object v = o == null ? null : o.opt(k);
        if (v instanceof Number) { double d = ((Number) v).doubleValue(); if (!Double.isNaN(d) && !Double.isInfinite(d)) return (long) d; }
        return def;
    }

    /** صيانة المجلد (تحت recFsLock): إصلاح .wav.part اليتيم (ليس للجلسة الحيّة وأقدم من دقيقة)، حذف .m4a.part
     *  اليتيم، وحذف WAV صار له M4A مكتمل (انقطاع بين النقل والحذف)، وM4A ناقص له WAV */
    private void recHousekeep(File d) {
        long now = System.currentTimeMillis();
        synchronized (recFsLock) {
            String act = activeRecId(), enc = encId;
            File[] kids = d.listFiles();
            if (kids == null) return;
            for (File k : kids) {
                String n = k.getName(), id = recIdOf(n);
                if (id == null || !k.isFile() || id.equals(act) || id.equals(enc)) continue;
                try {
                    if (n.endsWith(".wav.part")) {
                        if (now - k.lastModified() < REC_PART_AGE_MS) continue;
                        File fin = new File(d, id + ".wav");
                        if (fin.exists() || new File(d, id + ".m4a").exists()) { k.delete(); continue; }
                        boolean ok = RecWriter.repair(k, fin);
                        Log.i(TAG, "rec repair " + id + " → " + ok);
                    } else if (n.endsWith(".m4a.part")) {
                        k.delete();
                    } else if (n.endsWith(".wav")) {
                        File m = new File(d, id + ".m4a");
                        if (m.isFile()) {
                            if (mp4DurationMs(m) >= 0) k.delete(); else m.delete();
                        }
                    }
                } catch (Throwable t) { Log.w(TAG, "rec housekeep " + n + ": " + t); }
            }
        }
        purgeShare(false);
    }

    /** نسخ المشاركة المؤقّتة في cache/share: الأقدم من ساعة (أو الكل بعد حذف تسجيل — خصوصية) */
    private void purgeShare(boolean all) {
        try {
            File[] kids = new File(getContext().getCacheDir(), "share").listFiles();
            long now = System.currentTimeMillis();
            if (kids != null) for (File k : kids) if (k.isFile() && (all || now - k.lastModified() > SHARE_TTL_MS)) k.delete();
        } catch (Throwable ignored) {}
    }

    /** recList → {items:[{id,file,format,bytes,ms,mtime}] الأحدث أولاً, bytes, free}. لا يرفض أبداً. */
    @PluginMethod
    public void recList(PluginCall call) {
        recRdExec.execute(() -> {
            JSObject r = new JSObject();
            JSArray items = new JSArray();
            long total = 0;
            try {
                File d = recDir();
                recHousekeep(d);
                File[] kids = d.listFiles();
                Set<String> ids = new HashSet<>();
                if (kids != null) for (File k : kids) {
                    if (!k.isFile()) continue;
                    total += k.length();
                    String id = recIdOf(k.getName());
                    if (id != null && !k.getName().endsWith(".part")) ids.add(id);
                }
                List<String> sorted = new ArrayList<>(ids);
                java.util.Collections.sort(sorted, java.util.Collections.reverseOrder());   // المعرّف = تاريخ ووقت
                for (String id : sorted) {
                    File f = recFile(id);
                    if (f == null) continue;
                    JSObject it = recItem(id, f);
                    if (id.equals(encId)) it.put("encoding", true);
                    items.put(it);
                }
                r.put("free", d.getUsableSpace());
            } catch (Throwable t) {
                r.put("err", String.valueOf(t));
            }
            r.put("items", items); r.put("bytes", total);
            String act = activeRecId();
            if (act != null) r.put("active", act);
            safeResolve(call, r);
        });
    }

    /** recDelete({id}) → {ok, freed}. يحذف <id>.* (ونسخ المشاركة)؛ busy للجلسة الحيّة؛ يلغي ترميزاً جارياً له */
    @PluginMethod
    public void recDelete(PluginCall call) {
        final String id = call.getString("id", "");
        if (!recIdOk(id)) { call.reject("rec-bad-id"); return; }
        recRdExec.execute(() -> {
            try {
                long freed = 0;
                synchronized (recFsLock) {
                    if (id.equals(activeRecId())) { safeReject(call, "busy"); return; }
                    if (id.equals(encId)) encCancel = true;
                    File d = recDir();
                    for (String e : REC_EXT) {
                        File f = new File(d, id + e);
                        if (f.isFile()) { long l = f.length(); if (f.delete()) freed += l; }
                    }
                }
                purgeShare(true);
                Log.i(TAG, "rec delete " + id + " freed=" + freed);
                JSObject r = new JSObject(); r.put("ok", true); r.put("freed", freed);
                safeResolve(call, r);
            } catch (Throwable t) {
                safeReject(call, "io: " + t.getMessage());
            }
        });
    }

    /** recDeleteAll → {ok, freed, n}. لا يمسّ تسجيل الجلسة الحيّة */
    @PluginMethod
    public void recDeleteAll(PluginCall call) {
        recRdExec.execute(() -> {
            try {
                long freed = 0; Set<String> gone = new HashSet<>();
                synchronized (recFsLock) {
                    String act = activeRecId();
                    if (encId != null) encCancel = true;
                    File[] kids = recDir().listFiles();
                    if (kids != null) for (File k : kids) {
                        String id = recIdOf(k.getName());
                        if (id == null || id.equals(act) || !k.isFile()) continue;
                        long l = k.length();
                        if (k.delete()) { freed += l; if (!k.getName().endsWith(".part")) gone.add(id); }
                    }
                }
                purgeShare(true);
                Log.i(TAG, "rec deleteAll n=" + gone.size() + " freed=" + freed);
                JSObject r = new JSObject(); r.put("ok", true); r.put("freed", freed); r.put("n", gone.size());
                safeResolve(call, r);
            } catch (Throwable t) {
                safeReject(call, "io: " + t.getMessage());
            }
        });
    }

    /** recPath({id}) (متزامن) → {path, uri:"file://…", format, mime, bytes, ms} للتشغيل بـconvertFileSrc */
    @PluginMethod
    public void recPath(PluginCall call) {
        String id = call.getString("id", "");
        if (!recIdOk(id)) { call.reject("rec-bad-id"); return; }
        File f;
        try { f = recFile(id); } catch (Throwable t) { f = null; }
        if (f == null) { call.reject("rec-missing"); return; }
        JSObject r = new JSObject();
        r.put("id", id);
        r.put("path", f.getAbsolutePath());
        r.put("uri", "file://" + f.getAbsolutePath());
        r.put("format", isM4a(f) ? "m4a" : "wav");
        r.put("mime", mimeOf(f));
        r.put("bytes", f.length());
        r.put("ms", recMs(f));
        call.resolve(r);
    }

    /** recRead({id, offset, length≤2MB}) → {data: base64, offset, length, total, eof, format, mime}
     *  (مصدر التشغيل في الواجهة: Blob للملف كاملاً ≤15MB، ونوافذ WAV بقراءة بايتاتها — لا Range عبر الخادم المحلي) */
    @PluginMethod
    public void recRead(PluginCall call) {
        final String id = call.getString("id", "");
        if (!recIdOk(id)) { call.reject("rec-bad-id"); return; }
        final long off = Math.max(0, numArg(call.getData(), "offset", 0));
        final int want = (int) Math.max(1, Math.min(REC_READ_MAX, numArg(call.getData(), "length", REC_READ_MAX)));
        recRdExec.execute(() -> {
            File f = recFile(id);
            if (f == null) { safeReject(call, "rec-missing"); return; }
            try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
                long total = in.length();
                int n = (int) Math.max(0, Math.min(want, total - off));
                byte[] b = new byte[n];
                if (n > 0) { in.seek(off); in.readFully(b); }
                JSObject r = new JSObject();
                r.put("id", id); r.put("format", isM4a(f) ? "m4a" : "wav"); r.put("mime", mimeOf(f));
                r.put("offset", off); r.put("length", n); r.put("total", total); r.put("eof", off + n >= total);
                r.put("data", n > 0 ? Base64.encodeToString(b, Base64.NO_WRAP) : "");
                safeResolve(call, r);
            } catch (Throwable t) {
                safeReject(call, "io: " + t.getMessage());
            }
        });
    }

    /** recSlice({id, t0, t1} ms) → {data: base64 WAV (PCM 16-bit), t0, ms, sr, ch, format}: نافذة من التسجيل كملف WAV صغير.
     *  التشغيل في الواجهة لا يعتمد على HTTP Range: خادم Capacitor 6 المحلي يجيب طلب Range بـ206 لكن الجسم من بداية الملف،
     *  فالتقديم داخل الملف يُسمِع موضعاً خاطئاً بلا خطأ. WAV: نسخ بايتات النافذة؛ M4A: MediaExtractor (أقرب إطار سابق) +
     *  MediaCodec إلى PCM ثم قصّ دقيق بالعيّنات على [t0, t1]. النافذة ≤ REC_SLICE_MAX_MS. */
    @PluginMethod
    public void recSlice(PluginCall call) {
        final String id = call.getString("id", "");
        if (!recIdOk(id)) { call.reject("rec-bad-id"); return; }
        final long t0 = Math.max(0, numArg(call.getData(), "t0", 0));
        final long t1 = Math.max(t0 + 1, Math.min(numArg(call.getData(), "t1", t0 + 1000), t0 + REC_SLICE_MAX_MS));
        recRdExec.execute(() -> {
            File f = recFile(id);
            if (f == null) { safeReject(call, "rec-missing"); return; }
            try {
                Slice sl = isM4a(f) ? sliceM4a(f, t0, t1) : sliceWav(f, t0, t1);
                if (sl.pcm.length == 0) { safeReject(call, "rec-empty"); return; }
                JSObject r = new JSObject();
                r.put("id", id); r.put("format", isM4a(f) ? "m4a" : "wav");
                r.put("t0", sl.t0); r.put("ms", sl.ms()); r.put("sr", sl.sr); r.put("ch", sl.ch);
                r.put("data", Base64.encodeToString(sl.wav(), Base64.NO_WRAP));
                safeResolve(call, r);
            } catch (Throwable t) {
                safeReject(call, "io: " + t.getMessage());
            }
        });
    }

    /** نافذة PCM 16-bit مقصوصة: t0 الفعلي (ms) وعيّناتها */
    static final class Slice {
        final long t0; final int sr, ch; final byte[] pcm;
        Slice(long t0, int sr, int ch, byte[] pcm) { this.t0 = t0; this.sr = sr; this.ch = ch; this.pcm = pcm; }
        long ms() { return (long) (pcm.length / (2 * ch)) * 1000L / sr; }
        byte[] wav() {
            ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
            b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + pcm.length).put(new byte[]{'W', 'A', 'V', 'E'});
            b.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) ch)
                    .putInt(sr).putInt(sr * 2 * ch).putShort((short) (2 * ch)).putShort((short) 16);
            b.put(new byte[]{'d', 'a', 't', 'a'}).putInt(pcm.length).put(pcm);
            return b.array();
        }
    }

    /** WAV الجلسة (رأس 44 بايت، 16kHz أحادي): بايتات [t0, t1] مباشرة */
    static Slice sliceWav(File f, long t0, long t1) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            long data = Math.max(0, in.length() - RecWriter.HDR) & ~1L;
            long a = Math.min(data, t0 * RecWriter.BYTES_PER_MS), e = Math.min(data, t1 * RecWriter.BYTES_PER_MS);
            int n = (int) Math.max(0, e - a) & ~1;
            byte[] b = new byte[n];
            if (n > 0) { in.seek(RecWriter.HDR + a); in.readFully(b); }
            return new Slice(a / RecWriter.BYTES_PER_MS, SR, 1, b);
        }
    }

    /** M4A (AAC من encodeM4a): فكّ النافذة فقط. البدء قبل t0 بـ200ms (إحماء المفكّك بعد القفز) ثم قصّ بالعيّنات حسب pts؛
     *  إدخال EOS بعد t1+100ms؛ حارس توقّف 5ث. مخرج المفكّك PCM 16-bit (الافتراضي)، وPCM عائم يُحوَّل إن صرّح به الجهاز. */
    static Slice sliceM4a(File f, long t0, long t1) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null; boolean started = false;
        try {
            ex.setDataSource(f.getAbsolutePath());
            int tr = -1; MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat m = ex.getTrackFormat(i); String mime = m.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { tr = i; fmt = m; break; }
            }
            if (tr < 0) throw new IOException("no-audio-track");
            ex.selectTrack(tr);
            int sr = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : SR;
            int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            boolean flt = false;
            ex.seekTo(Math.max(0, t0 - 200) * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start(); started = true;
            final long t0us = t0 * 1000L, t1us = t1 * 1000L;
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(8 * MB, (t1 - t0) * 32L + 8192));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEos = false, outEos = false; long lastStep = SystemClock.elapsedRealtime(), first = -1;
            while (!outEos) {
                if (SystemClock.elapsedRealtime() - lastStep > 5000) throw new IOException("decoder stalled");
                if (!inEos) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                        long pts = ex.getSampleTime();
                        if (n < 0 || pts < 0 || pts > t1us + 100000L) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true;
                        } else { dec.queueInputBuffer(ii, 0, n, pts, 0); ex.advance(); }
                        lastStep = SystemClock.elapsedRealtime();
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    flt = of.containsKey("pcm-encoding") && of.getInteger("pcm-encoding") == 4;   // AudioFormat.ENCODING_PCM_FLOAT
                    lastStep = SystemClock.elapsedRealtime();
                } else if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0 && sr > 0 && ch > 0) {
                        int bps = flt ? 4 : 2, fr = bps * ch, nFr = info.size / fr;
                        long pts = info.presentationTimeUs;
                        long s0 = pts >= t0us ? 0 : Math.min(nFr, (t0us - pts) * sr / 1000000L);
                        long s1 = Math.min(nFr, Math.max(0, (t1us - pts) * sr / 1000000L));
                        if (s1 > s0) {
                            if (first < 0) first = pts + s0 * 1000000L / sr;
                            ob.limit(info.offset + info.size); ob.position(info.offset + (int) (s0 * fr));
                            int cnt = (int) ((s1 - s0) * ch);
                            byte[] b = new byte[cnt * 2];
                            if (flt) {
                                ByteBuffer fb = ob.slice().order(ByteOrder.nativeOrder());
                                for (int k = 0; k < cnt; k++) {
                                    int v = Math.round(fb.getFloat(k * 4) * 32767f);
                                    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                                    b[2 * k] = (byte) v; b[2 * k + 1] = (byte) (v >> 8);
                                }
                            } else {
                                ByteBuffer sb = ob.slice().order(ByteOrder.nativeOrder());
                                for (int k = 0; k < cnt; k++) { short v = sb.getShort(k * 2); b[2 * k] = (byte) v; b[2 * k + 1] = (byte) (v >> 8); }
                            }
                            out.write(b, 0, b.length);
                        }
                        if (pts + (long) nFr * 1000000L / sr >= t1us) outEos = true;
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                    lastStep = SystemClock.elapsedRealtime();
                }
            }
            return new Slice(first < 0 ? t0 : first / 1000L, sr, ch, out.toByteArray());
        } finally {
            if (dec != null) {
                if (started) { try { dec.stop(); } catch (Throwable ignored) {} }
                try { dec.release(); } catch (Throwable ignored) {}
            }
            try { ex.release(); } catch (Throwable ignored) {}
        }
    }

    /** recQuota → {bytes, n, free} */
    @PluginMethod
    public void recQuota(PluginCall call) {
        recRdExec.execute(() -> {
            JSObject r = new JSObject();
            long bytes = 0; Set<String> ids = new HashSet<>(); long free = -1;
            try {
                File d = recDir();
                File[] kids = d.listFiles();
                if (kids != null) for (File k : kids) {
                    if (!k.isFile()) continue;
                    bytes += k.length();
                    String id = recIdOf(k.getName());
                    if (id != null && !k.getName().endsWith(".part")) ids.add(id);
                }
                free = d.getUsableSpace();
            } catch (Throwable t) { r.put("err", String.valueOf(t)); }
            r.put("bytes", bytes); r.put("n", ids.size()); r.put("free", free);
            safeResolve(call, r);
        });
    }

    /** recShare({id, name, title}): المستخدم وحده يطلبه. نسخة في cache/share (cache-path مُعلن لـFileProvider)
     *  ثم ACTION_SEND عبر منتقي النظام بإذن قراءة مؤقّت. لا شيء يُرسَل تلقائياً. */
    @PluginMethod
    public void recShare(PluginCall call) {
        final String id = call.getString("id", "");
        if (!recIdOk(id)) { call.reject("rec-bad-id"); return; }
        final String name = call.getString("name", "");
        final String title = call.getString("title", "");
        recRdExec.execute(() -> {
            File f = recFile(id);
            if (f == null) { safeReject(call, "rec-missing"); return; }
            try {
                purgeShare(false);
                File sd = new File(getContext().getCacheDir(), "share");
                if (!sd.isDirectory() && !sd.mkdirs()) { safeReject(call, "share: mkdir"); return; }
                File out = new File(sd, shareName(name, id, isM4a(f) ? ".m4a" : ".wav"));
                if (!copyFile(f, out)) { out.delete(); safeReject(call, "share: copy"); return; }
                Context ctx = getContext();
                final Uri u = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", out);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mimeOf(f));
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.setClipData(ClipData.newRawUri("", u));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                final Intent ch = Intent.createChooser(i, title == null ? "" : title);
                ch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                final Activity a = getActivity();
                if (a == null) { safeReject(call, "share: no-activity"); return; }
                a.runOnUiThread(() -> {
                    try {
                        a.startActivity(ch);
                        Log.i(TAG, "rec share " + id);
                        JSObject r = new JSObject(); r.put("ok", true);
                        safeResolve(call, r);
                    } catch (Throwable t) {
                        safeReject(call, "share: " + t.getMessage());
                    }
                });
            } catch (Throwable t) {
                safeReject(call, "share: " + t.getMessage());
            }
        });
    }

    /** اسم آمن لنسخة المشاركة: [A-Za-z0-9._-] فقط، بلا امتداد مُرسَل، والامتداد من الصيغة الفعلية */
    static String shareName(String name, String id, String ext) {
        String s = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "");
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(0, dot);
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 64) s = s.substring(0, 64);
        if (s.isEmpty()) s = "tasmee-" + id;
        return s + ext;
    }

    private static boolean copyFile(File src, File dst) {
        byte[] b = new byte[131072]; int rd;
        try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            while ((rd = in.read(b)) > 0) out.write(b, 0, rd);
            out.flush();
        } catch (Throwable t) { return false; }
        return dst.length() == src.length();
    }

    /** recFinalize({id, bitrate=32000}): WAV → M4A (AAC-LC 16kHz أحادي) ثم حذف WAV. فشل الترميز يُبقي WAV ويحلّ
     *  {format:'wav', encErr}. busy للجلسة الحيّة؛ rec-missing إن لم يوجد (أو حُذف أثناء الترميز). */
    @PluginMethod
    public void recFinalize(PluginCall call) {
        final String id = call.getString("id", "");
        if (!recIdOk(id)) { call.reject("rec-bad-id"); return; }
        if (id.equals(activeRecId())) { call.reject("busy"); return; }
        final int br = (int) Math.max(16000, Math.min(64000, numArg(call.getData(), "bitrate", 32000)));
        recExec.execute(() -> {
            try { doFinalize(call, id, br); }
            catch (Throwable t) { safeReject(call, "io: " + t.getMessage()); }
        });
    }

    private void doFinalize(PluginCall call, String id, int br) {
        File d = recDir();
        File wav = new File(d, id + ".wav"), m4a = new File(d, id + ".m4a"), part = new File(d, id + ".m4a.part");
        synchronized (recFsLock) {
            if (id.equals(activeRecId())) { safeReject(call, "busy"); return; }
            if (!wav.isFile()) {
                if (m4a.isFile() && mp4DurationMs(m4a) >= 0) {   // رُمِّز سابقاً: نتيجة متطابقة
                    JSObject r = recItem(id, m4a); r.put("cached", true);
                    safeResolve(call, r);
                } else safeReject(call, "rec-missing");
                return;
            }
            encId = id; encCancel = false;
        }
        long t0 = SystemClock.elapsedRealtime();
        long ms = Math.max(0, wav.length() - RecWriter.HDR) / RecWriter.BYTES_PER_MS;
        String encErr = null;
        try {
            encodeM4a(id, wav, part, br);
            long dm = mp4DurationMs(part);
            if (dm < 0) throw new IOException("bad-mp4");
            if (dm > 0 && Math.abs(dm - ms) > Math.max(1500, ms / 10)) throw new IOException("duration " + dm + "≠" + ms);
        } catch (CancelledIO c) {
            encErr = "cancelled";
        } catch (Throwable t) {
            encErr = String.valueOf(t.getMessage() != null ? t.getMessage() : t);
        }
        boolean cancelled, promoted = false;
        synchronized (recFsLock) {
            cancelled = encCancel;
            if (encErr == null && !cancelled && promote(part, m4a)) { promoted = true; wav.delete(); }
            else part.delete();
            encId = null; encCancel = false;
        }
        if (cancelled) { safeReject(call, "rec-missing"); return; }       // حُذف أثناء الترميز
        Log.i(TAG, "rec finalize " + id + " " + (promoted ? "m4a" : "wav encErr=" + encErr) + " in " + (SystemClock.elapsedRealtime() - t0) + "ms");
        JSObject r;
        if (promoted) r = recItem(id, m4a);
        else {
            r = recItem(id, wav);
            r.put("encErr", encErr == null ? "rename" : encErr);
        }
        r.put("ms", ms);
        safeResolve(call, r);
    }

    /** ترميز AAC-LC عبر MediaCodec → MediaMuxer(MPEG_4). إطارات 1024 عيّنة؛ pts من عدد العيّنات؛ حارس توقّف 10ث.
     *  إلغاء (حذف/تدمير) → CancelledIO. */
    private void encodeM4a(String id, File wav, File part, int bitrate) throws Exception {
        final int frameBytes = 2048;
        MediaCodec enc = null; MediaMuxer mux = null; boolean muxStarted = false, encStarted = false;
        try (RandomAccessFile in = new RandomAccessFile(wav, "r")) {
            long data = Math.max(0, in.length() - RecWriter.HDR) & ~1L;
            if (data < frameBytes) throw new IOException("empty");
            in.seek(RecWriter.HDR);
            MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 1);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start(); encStarted = true;
            part.delete();
            mux = new MediaMuxer(part.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] fb = new byte[frameBytes];
            long fed = 0, lastPts = 0, lastStep = SystemClock.elapsedRealtime(), frames = 0;
            int track = -1, pct = 0;
            boolean inEos = false, outEos = false;
            while (!outEos) {
                if (encCancel || Thread.currentThread().isInterrupted()) throw new CancelledIO();
                long now = SystemClock.elapsedRealtime();
                if (now - lastStep > 10000) throw new IOException("encoder stalled");
                if (!inEos) {
                    int ii = enc.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = enc.getInputBuffer(ii);
                        long pts = (fed / 2) * 1000000L / SR;
                        int n = (int) Math.min(frameBytes, data - fed);
                        if (ib != null) { ib.clear(); n = Math.min(n, ib.remaining()) & ~1; }
                        if (n <= 0 || ib == null) {
                            enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            in.readFully(fb, 0, n);
                            ib.put(fb, 0, n);
                            enc.queueInputBuffer(ii, 0, n, pts, 0);
                            fed += n;
                            int p = (int) (fed * 10 / data);
                            if (p > pct) {
                                pct = p;
                                JSObject ev = new JSObject();
                                ev.put("kind", "enc"); ev.put("id", id); ev.put("done", fed); ev.put("total", data);
                                emit("rec_event", ev);
                            }
                        }
                        lastStep = now;
                    }
                }
                int oi = enc.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxStarted) throw new IllegalStateException("format changed twice");
                    track = mux.addTrack(enc.getOutputFormat());
                    mux.start(); muxStarted = true;
                    lastStep = now;
                } else if (oi >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(oi);
                    boolean cfg = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!cfg && info.size > 0 && ob != null) {
                        if (!muxStarted) throw new IllegalStateException("data before format");
                        if (info.presentationTimeUs < lastPts) info.presentationTimeUs = lastPts;   // الحاوي يرفض الرجوع
                        ob.position(info.offset); ob.limit(info.offset + info.size);
                        mux.writeSampleData(track, ob, info);
                        lastPts = info.presentationTimeUs; frames++;
                    }
                    enc.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                    lastStep = now;
                }
            }
            if (!muxStarted || frames == 0) throw new IOException("no-output");
            mux.stop(); muxStarted = false;
        } finally {
            if (enc != null) {
                if (encStarted) { try { enc.stop(); } catch (Throwable ignored) {} }
                try { enc.release(); } catch (Throwable ignored) {}
            }
            if (mux != null) {
                if (muxStarted) { try { mux.stop(); } catch (Throwable ignored) {} }
                try { mux.release(); } catch (Throwable ignored) {}
            }
        }
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

    /** deleteFile({name}): يحذف ملفاً واحداً من مجلد المحرّك (+ جزئيّه) ويُسقط مدخله من علامة التحقّق — لإزالة
     *  النموذج السابق بعد أن يُحمَّل الجديد ويعمل. يرفض «in-use» إن كان هو النموذج المحمَّل، و«busy» أثناء
     *  تحميل/جلسة. {ok, freed}؛ ملف غير موجود = ok بلا تحرير. على منفّذ الإدخال/الإخراج: بعد أي تنزيل/تحميل سابق. */
    @PluginMethod
    public void deleteFile(PluginCall call) {
        final String name = call.getString("name", "");
        if (name == null || !NAME_RE.matcher(name).matches() || name.endsWith(".part") || name.startsWith(MARKER)) {
            call.reject("bad-args: name");
            return;
        }
        ioExec.execute(() -> {
            try {
                if (loading || listening) { safeReject(call, "busy"); return; }
                if (recognizer != null && name.equals(loadedModel)) { safeReject(call, "in-use"); return; }
                File dir = asrDir(false);
                File f = new File(dir, name), part = new File(dir, name + ".part");
                long freed = (f.isFile() ? f.length() : 0) + (part.isFile() ? part.length() : 0);
                if (readMarker(dir).has(name)) dropMarker(dir, name);   // العلامة أولاً: لا ملف «مُتحقَّق» نصف محذوف
                if (part.isFile()) part.delete();
                if (f.exists() && !f.delete()) { safeReject(call, "io: تعذّر حذف " + name); return; }
                Log.i(TAG, "deleteFile " + name + " freed=" + freed);
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
            recognizer = null; loadedThreads = 0; loadedModel = null;
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
        encCancel = true;   // ترميز جارٍ يتوقّف (WAV يبقى؛ .m4a.part يُحذف في recList التالي)
        try { recExec.shutdownNow(); } catch (Throwable ignored) {}
        try { recRdExec.shutdownNow(); } catch (Throwable ignored) {}
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

    /** فشل تحميل نموذج (بناء/إعداد/إحماء): تُسقط علامته هو فقط فيعيد ensureModel التالي هاشه. tokens.txt مشترك
     *  بين chunk-24 وchunk-16: لا يُسقَط إلا مع النموذج الافتراضي (chunk-24) — وإلا فشلُ chunk-16 يُبطل تحقّق
     *  chunk-24 فترفض العودة إليه «not-verified» ويبقى المستخدم بلا محرّك وملفه على القرص */
    private static void dropLoadMarker(File dir, String modelName) {
        if (MODEL.equals(modelName)) dropMarker(dir, modelName, TOKENS);
        else dropMarker(dir, modelName);
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
