package com.ddcbusiness.noor;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * منطق أداة الشاشة الرئيسية «المجدّد — المواقيت» بلا أي اعتماد على أندرويد (قابل للفحص على JVM عادية).
 *
 * المصدر الوحيد للبيانات هو الواجهة (index.html → NoorWidget.update) التي تحسب المواقيت وتخزّنها في
 * SharedPreferences باسم {@link #PREFS}. هنا فقط: قراءة المفاتيح، تحديد الصلاة القادمة بالطريقة نفسها التي
 * تستعملها الواجهة (nextPrayer في index.html: محور متصل، العشاء بعد منتصف الليل يتبع ليلته، وعشاء الأمس الواقع
 * بعد منتصف الليل هو «القادمة» قبل فجر اليوم)، وتنسيق الوقت (١٢/٢٤ ساعة، أرقام عربية)، وجدول أسماء صغير
 * للغات الواجهة (القيم نفسها في DICT داخل index.html).
 */
public final class WidgetLogic {
    private WidgetLogic() {}

    /** اسم ملف SharedPreferences (مستثنى من النسخ الاحتياطي عمداً في المقترح — بيانات مدينة قديمة لا تُنقل) */
    public static final String PREFS = "noor_widget";
    public static final String[] PRAYERS = { "fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha" };
    /** الصلوات الخمس فقط (بلا الشروق) — ترتيب عرض الصف وحساب «القادمة» */
    public static final String[] ADHAN = { "fajr", "dhuhr", "asr", "maghrib", "isha" };
    public static final String[] LANGS = { "ar", "en", "fr", "de", "hi", "zh" };

    /** أسماء الصلوات بلغات الواجهة — القيم مطابقة لـ DICT في index.html (المفاتيح العربية هي الأصل) */
    private static final Map<String, String[]> NAMES = new HashMap<String, String[]>();
    /** عبارات الأداة — ar,en,fr,de,hi,zh */
    private static final Map<String, String[]> LABELS = new HashMap<String, String[]>();
    static {
        NAMES.put("fajr",    new String[] { "الفجر",  "Fajr",    "Fajr",            "Fadschr",      "फ़ज्र",      "晨礼" });
        NAMES.put("sunrise", new String[] { "الشروق", "Sunrise", "Lever du soleil", "Sonnenaufgang","सूर्योदय",   "日出" });
        NAMES.put("dhuhr",   new String[] { "الظهر",  "Dhuhr",   "Dhohr",           "Dhuhr",        "ज़ुहर",      "晌礼" });
        NAMES.put("asr",     new String[] { "العصر",  "Asr",     "Asr",             "Asr",          "अस्र",       "晡礼" });
        NAMES.put("maghrib", new String[] { "المغرب", "Maghrib", "Maghreb",         "Maghrib",      "मग़रिब",     "昏礼" });
        NAMES.put("isha",    new String[] { "العشاء", "Isha",    "Icha",            "Ischa",        "इशा",        "宵礼" });
        LABELS.put("next", new String[] { "الصلاة القادمة", "Next Prayer", "Prochaine prière", "Nächstes Gebet", "अगली नमाज़", "下一次礼拜" });
        LABELS.put("open", new String[] { "افتح التطبيق لتحديث المواقيت", "Open the app to update prayer times",
                "Ouvrez l’application pour actualiser les horaires", "App öffnen, um die Gebetszeiten zu aktualisieren",
                "नमाज़ के समय अपडेट करने के लिए ऐप खोलें", "打开应用以更新礼拜时间" });
        LABELS.put("app", new String[] { "المجدّد", "Al-Mujaddid", "Al-Mujaddid", "Al-Mujaddid", "अल-मुजद्दिद", "穆贾迪德" });
        LABELS.put("am", new String[] { "ص", "AM", "AM", "AM", "AM", "AM" });
        LABELS.put("pm", new String[] { "م", "PM", "PM", "PM", "PM", "PM" });
    }

    /** البيانات المخزّنة كما كتبتها الواجهة (كل القيم نصوص — SharedPreferences) */
    public static final class Data {
        public final Map<String, String> t = new HashMap<String, String>(); /* fajr..isha بصيغة HH:MM (٢٤ ساعة) */
        public String prevMaghrib, prevIsha;   /* مغرب/عشاء الأمس إن وقعا بعد منتصف الليل (HH:MM) أو null */
        public String lang = "ar", city = "", hijri = "", tz = "", day = "";
        public boolean h12 = true;
        public long updatedAt = 0;
        /** صحيحة إذا توافرت الصلوات الخمس على الأقل */
        public boolean valid() {
            for (String k : ADHAN) if (hm(t.get(k)) < 0) return false;
            return true;
        }
    }

    /** قراءة من خريطة عامة (SharedPreferences.getAll() أو HashMap في الفحص) */
    public static Data read(Map<String, ?> kv) {
        Data d = new Data();
        if (kv == null) return d;
        for (String k : PRAYERS) { Object v = kv.get("t_" + k); if (v != null) d.t.put(k, String.valueOf(v)); }
        d.prevMaghrib = str(kv.get("p_maghrib"));
        d.prevIsha = str(kv.get("p_isha"));
        String lang = str(kv.get("lang"));
        d.lang = langOf(lang);
        d.city = nz(str(kv.get("city")));
        d.hijri = nz(str(kv.get("hijri")));
        d.tz = nz(str(kv.get("tz")));
        d.day = nz(str(kv.get("day")));
        String h = str(kv.get("h12"));
        d.h12 = h == null || "1".equals(h) || "true".equals(h);
        try { Object u = kv.get("updatedAt"); if (u != null) d.updatedAt = Long.parseLong(String.valueOf(u)); } catch (NumberFormatException ignored) {}
        return d;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String nz(String s) { return s == null ? "" : s; }

    /** لغة مدعومة أو العربية */
    public static String langOf(String lang) {
        if (lang == null) return "ar";
        for (String l : LANGS) if (l.equals(lang)) return l;
        return "ar";
    }

    static int li(String lang) {
        lang = langOf(lang);
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i].equals(lang)) return i;
        return 0;
    }

    /** "HH:MM" → دقائق منذ منتصف الليل، أو -1 إن لم تكن صيغة صالحة */
    public static int hm(String s) {
        if (s == null) return -1;
        s = s.trim();
        int c = s.indexOf(':');
        if (c < 1 || c > 2 || s.length() - c != 3) return -1;
        try {
            int h = Integer.parseInt(s.substring(0, c)), m = Integer.parseInt(s.substring(c + 1));
            if (h < 0 || h > 29 || m < 0 || m > 59) return -1; /* ٢٤+ محتمل نظرياً لعشاء على محور متصل */
            return h * 60 + m;
        } catch (NumberFormatException e) { return -1; }
    }

    /** الصلاة القادمة */
    public static final class Next {
        public String key;      /* fajr..isha */
        public int atMin;       /* لحظتها على المحور المتصل (قد تتجاوز ١٤٤٠ = غداً) */
        public int inMin;       /* الدقائق المتبقية من «الآن» */
        public boolean tomorrow() { return atMin >= 1440; }
    }

    /**
     * مرآة nextPrayer() في index.html: الصلوات الخمس على محور متصل (وقتٌ أصغر من سابقه = بعد منتصف الليل → +١٤٤٠)،
     * مع مغرب/عشاء الأمس الواقعَين بعد منتصف الليل، ثم فجر الغد (+١٤٤٠). الأقرب بعد «الآن» هو القادمة.
     * @param nowMin دقائق اليوم الحالي بساعة المدينة (0..1439)
     */
    public static Next next(Data d, int nowMin) {
        if (d == null || !d.valid()) return null;
        Next best = null;
        int prev = -1;
        for (String k : ADHAN) {
            int s = hm(d.t.get(k));
            if (s < prev) s += 1440;
            prev = s;
            best = better(best, k, s, nowMin);
        }
        int pm = hm(d.prevMaghrib), pi = hm(d.prevIsha);
        if (pm >= 0) best = better(best, "maghrib", pm, nowMin);
        if (pi >= 0) best = better(best, "isha", pi, nowMin);
        best = better(best, "fajr", hm(d.t.get("fajr")) + 1440, nowMin);
        return best;
    }

    private static Next better(Next cur, String k, int at, int now) {
        if (at <= now) return cur;
        if (cur != null && at >= cur.atMin) return cur;
        Next n = new Next();
        n.key = k; n.atMin = at; n.inMin = at - now;
        return n;
    }

    /** الأرقام الغربية → عربية-هندية */
    public static String arDigits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= '0' && c <= '9' ? (char) ('٠' + (c - '0')) : c);
        }
        return b.toString();
    }

    /** تنسيق "HH:MM" بحسب لغة الواجهة ونظام ١٢/٢٤ — مطابق لـ _tmD في index.html ("٣:٤٢ م" / "3:42 PM" / "15:42") */
    public static String time(String hm, boolean h12, String lang) {
        int m = hm(hm);
        if (m < 0) return "—";
        m %= 1440;
        int h = m / 60, mi = m % 60;
        boolean ar = "ar".equals(langOf(lang));
        String mm = (mi < 10 ? "0" : "") + mi;
        if (!h12) {
            String s = (h < 10 ? "0" : "") + h + ":" + mm;
            return ar ? arDigits(s) : s;
        }
        int h12v = h % 12 == 0 ? 12 : h % 12;
        String s = h12v + ":" + mm;
        String ap = label(h >= 12 ? "pm" : "am", lang);
        return (ar ? arDigits(s) : s) + " " + ap;
    }

    /** اسم الصلاة بلغة الواجهة */
    public static String name(String key, String lang) {
        String[] n = NAMES.get(key);
        if (n == null) return key == null ? "" : key;
        return n[li(lang)];
    }

    /** عبارة الأداة بلغة الواجهة: next / open / app / am / pm */
    public static String label(String id, String lang) {
        String[] n = LABELS.get(id);
        if (n == null) return id;
        return n[li(lang)];
    }

    /** مفتاح اليوم كما تكتبه الواجهة (_ptDayKey: Y-M-D بلا أصفار بادئة) بساعة المنطقة المعطاة */
    public static String dayKey(long nowMs, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        c.setTimeInMillis(nowMs);
        return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1) + "-" + c.get(Calendar.DAY_OF_MONTH);
    }

    /** دقائق اليوم الحالي بساعة المنطقة */
    public static int nowMin(long nowMs, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        c.setTimeInMillis(nowMs);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** منطقة المدينة المخزّنة أو منطقة الجهاز */
    public static TimeZone zone(Data d) {
        if (d != null && d.tz.length() > 0) {
            /* معرّف مجهول → ترجع المكتبة GMT؛ نقبله فقط إن طُلب GMT/UTC فعلاً */
            TimeZone z = TimeZone.getTimeZone(d.tz);
            if (z != null && (!"GMT".equals(z.getID()) || "GMT".equals(d.tz) || "UTC".equals(d.tz))) return z;
        }
        return TimeZone.getDefault();
    }

    /**
     * هل المواقيت المخزّنة قديمة؟ مواقيت يومٍ سابق تبقى صالحة للعرض يوماً واحداً (الفروق دقائق)، وبعد يومَين
     * تظهر دعوة «افتح التطبيق لتحديث المواقيت» — التطبيق يدفع مواقيت جديدة عند كل فتح.
     */
    public static boolean stale(Data d, long nowMs) {
        if (d == null || !d.valid()) return true;
        if (d.updatedAt <= 0) return false;
        return nowMs - d.updatedAt > 2L * 24 * 3600 * 1000;
    }

    /**
     * لحظة إعادة الرسم التالية (ms منذ 1970): بعد الصلاة القادمة بدقيقة (لينتقل التظليل إلى التي تليها)،
     * أو بعد منتصف الليل بدقيقة إن لم يُعرف — بحدٍّ أدنى دقيقة من «الآن».
     */
    public static long nextRefreshAt(Data d, long nowMs) {
        TimeZone tz = zone(d);
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(nowMs);
        int nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        Next n = next(d, nowMin);
        long startOfDay;
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        startOfDay = c.getTimeInMillis();
        long at = n != null ? startOfDay + (n.atMin + 1) * 60000L : startOfDay + 1441 * 60000L;
        if (at < nowMs + 60000L) at = nowMs + 60000L;
        return at;
    }
}
