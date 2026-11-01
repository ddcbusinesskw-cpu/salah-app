package com.ddcbusiness.noor;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

/**
 * الأذان الكامل والتطبيق مغلق — المنطق الخالص (بلا أندرويد) كي يُفحص على JVM عادية:
 *  - أسماء الملفات المضمّنة في res/raw وتحقّقها، واختيار الصوت لكل صلاة (الفجر → أذان الفجر افتراضاً).
 *  - مفتاح «لا يُؤذَّن مرتين للصلاة نفسها» (الصلاة@اليوم) ومقارنته بآخر ما أُذِّن.
 *  - قرار المنبّه عند رنينه: تشغيل كامل / تنبيه قصير (كتم اليوم) / تجاهل (فائت، مكرَّر، مبكر، مجهول).
 *  - ترميز قائمة المنبّهات المخزّنة (نصّ واحد في SharedPreferences) وفكّه، وتقليمها (الفائت يُحذف، السقف ١٦٠).
 * يستعمله AdhanScheduler (المنبّهات)، وAdhanPlayerService (الخدمة)، وAdhanPlugin (الإضافة NoorAdhan).
 */
public final class AdhanLogic {
    private AdhanLogic() {}

    /** الملفات المضمّنة (res/raw، OGG/Opus أحادي ~٦٠ ك.ب/ث، −16 LUFS) — الأسماء كما في native-bridge.js وindex.html */
    public static final String SOUND_ALAFASY = "adhan_alafasy";
    public static final String SOUND_MADINAH = "adhan_madinah";
    public static final String SOUND_FAJR_DOHA = "adhan_fajr_doha";
    public static final String[] SOUNDS = { SOUND_ALAFASY, SOUND_MADINAH, SOUND_FAJR_DOHA };
    public static final String DEFAULT_SOUND = SOUND_ALAFASY;
    public static final String DEFAULT_FAJR_SOUND = SOUND_FAJR_DOHA;

    public static final String STREAM_ALARM = "alarm";
    public static final String STREAM_NOTIFICATION = "notification";

    public static final String[] PRAYERS = { "fajr", "dhuhr", "asr", "maghrib", "isha" };

    /** أذان فات وقته بأكثر من ١٠ دقائق لا يُشغَّل (الهاتف كان مطفأً) — نفس حدّ AdhanSystemReceiver.STALE_MS */
    public static final long STALE_MS = 10L * 60L * 1000L;
    /** المنبّه الدقيق قد يسبق بثوانٍ قليلة؛ أكثر من دقيقة = خلل ساعة → تجاهل */
    public static final long EARLY_MS = 60L * 1000L;
    /** سقف المنبّهات المخزّنة (الجسر يرسل ≤ ١٦٠ = ٣٢ يوماً × ٥) */
    public static final int MAX_ITEMS = 160;
    /** معرّفات الأذان كما في الجسر: 2000 + يوم×5 + صلاة ∈ [2000, 2190) */
    public static final int ID_MIN = 2000, ID_MAX = 2190;

    private static final char FS = '\u001f', RS = '\u001e';

    /** منبّه أذان واحد كما يرسله الجسر: {id, at, prayer, sound, title, body} */
    public static final class Item {
        public final int id;
        public final long at;
        public final String prayer, sound, title, body;

        public Item(int id, long at, String prayer, String sound, String title, String body) {
            this.id = id;
            this.at = at;
            this.prayer = normPrayer(prayer);
            this.sound = isBundled(sound) ? sound : resolveSound(this.prayer, null, null);
            this.title = clean(title);
            this.body = clean(body);
        }

        public String encode() {
            return id + "" + FS + at + FS + prayer + FS + sound + FS + title + FS + body;
        }

        /** null إن كان النصّ معطوباً */
        public static Item decode(String s) {
            if (s == null) return null;
            String[] f = s.split(String.valueOf(FS), -1);
            if (f.length < 6) return null;
            try {
                int id = Integer.parseInt(f[0]);
                long at = Long.parseLong(f[1]);
                return new Item(id, at, f[2], f[3], f[4], f[5]);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override public String toString() { return "Item#" + id + "(" + prayer + "@" + at + "," + sound + ")"; }
    }

    public enum Decision { PLAY, SHORT, SKIP_STALE, SKIP_EARLY, SKIP_DUPLICATE, SKIP_UNKNOWN }

    public static boolean isBundled(String sound) {
        if (sound == null) return false;
        for (String s : SOUNDS) if (s.equals(sound)) return true;
        return false;
    }

    public static boolean validStream(String s) { return STREAM_ALARM.equals(s) || STREAM_NOTIFICATION.equals(s); }

    public static String normStream(String s) { return validStream(s) ? s : STREAM_ALARM; }

    public static String normPrayer(String p) {
        if (p == null) return "dhuhr";
        String q = p.trim().toLowerCase();
        for (String k : PRAYERS) if (k.equals(q)) return k;
        return "dhuhr";
    }

    /** الصوت الفعلي: الفجر → اختيار الفجر (افتراضياً أذان الفجر من الدوحة)، غيره → اختيار المستخدم (افتراضياً العفاسي) */
    public static String resolveSound(String prayer, String userSound, String fajrSound) {
        if ("fajr".equals(normPrayer(prayer))) return isBundled(fajrSound) ? fajrSound : DEFAULT_FAJR_SOUND;
        return isBundled(userSound) ? userSound : DEFAULT_SOUND;
    }

    /** «fajr@2026-10-31» — ثابت لإعادة الجدولة بوقت مختلف قليلاً لليوم نفسه (تغيير طريقة الحساب) */
    public static String playKey(String prayer, long atMs, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        c.setTimeInMillis(atMs);
        return normPrayer(prayer) + "@" + c.get(Calendar.YEAR) + "-" + pad2(c.get(Calendar.MONTH) + 1) + "-" + pad2(c.get(Calendar.DAY_OF_MONTH));
    }

    private static String pad2(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    /**
     * قرار المنبّه. lastKey = آخر مفتاح أُذِّن له (كاملاً أو قصيراً)، muteUntil = «كتم اليوم» حتى منتصف الليل (0 = لا كتم).
     */
    public static Decision decide(Item it, long nowMs, String lastKey, long muteUntil, TimeZone tz) {
        if (it == null) return Decision.SKIP_UNKNOWN;
        if (nowMs < it.at - EARLY_MS) return Decision.SKIP_EARLY;
        if (nowMs - it.at > STALE_MS) return Decision.SKIP_STALE;
        String key = playKey(it.prayer, it.at, tz);
        if (key.equals(lastKey)) return Decision.SKIP_DUPLICATE;
        if (muteUntil > nowMs) return Decision.SHORT;
        return Decision.PLAY;
    }

    /** منتصف الليل القادم بتوقيت الجهاز — نهاية «كتم اليوم» */
    public static long nextMidnight(long nowMs, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        c.setTimeInMillis(nowMs);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** الفائت (أكثر من ١٠ دقائق) يُحذف، المعرّفات خارج المدى تُحذف، ترتيب زمني، سقف ١٦٠ (الأقرب أولاً)، لا معرّف مكرّر */
    public static List<Item> prune(List<Item> items, long nowMs) {
        List<Item> out = new ArrayList<Item>();
        if (items == null) return out;
        for (Item it : items) {
            if (it == null) continue;
            if (it.id < ID_MIN || it.id >= ID_MAX) continue;
            if (nowMs - it.at > STALE_MS) continue;
            boolean dup = false;
            for (Item o : out) if (o.id == it.id) { dup = true; break; }
            if (!dup) out.add(it);
        }
        Collections.sort(out, new Comparator<Item>() {
            public int compare(Item a, Item b) { return a.at < b.at ? -1 : (a.at > b.at ? 1 : a.id - b.id); }
        });
        if (out.size() > MAX_ITEMS) out = new ArrayList<Item>(out.subList(0, MAX_ITEMS));
        return out;
    }

    public static String encodeAll(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        if (items != null) for (Item it : items) {
            if (it == null) continue;
            if (sb.length() > 0) sb.append(RS);
            sb.append(it.encode());
        }
        return sb.toString();
    }

    public static List<Item> decodeAll(String s) {
        List<Item> out = new ArrayList<Item>();
        if (s == null || s.length() == 0) return out;
        for (String p : s.split(String.valueOf(RS), -1)) {
            Item it = Item.decode(p);
            if (it != null) out.add(it);
        }
        return out;
    }

    /** المعرّفات في القديم وليست في الجديد — تُلغى منبّهاتها */
    public static List<Integer> removedIds(List<Item> old, List<Item> now) {
        List<Integer> out = new ArrayList<Integer>();
        if (old == null) return out;
        for (Item o : old) {
            boolean keep = false;
            if (now != null) for (Item n : now) if (n.id == o.id) { keep = true; break; }
            if (!keep) out.add(o.id);
        }
        return out;
    }

    public static Item find(List<Item> items, int id) {
        if (items != null) for (Item it : items) if (it.id == id) return it;
        return null;
    }

    /** أحرف التحكّم (ومنها فاصلا الترميز) تُستبدل بمسافة؛ طول أقصى ٢٠٠ */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 200; i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 ? ' ' : c);
        }
        return sb.toString().trim();
    }
}
