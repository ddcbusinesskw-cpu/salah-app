# إقرارات Google Play Console — «المجدّد» (com.ddcbusiness.noor)

هذا الملف يجهّز ما سيُلصق في Play Console قبل رفع أول إصدار إنتاجي يحمل الأذان الكامل. النصوص الإنجليزية داخل الأُطر
(`text`) هي النصّ الحرفي الذي يُنسخ في النماذج (Play تقبل الإنجليزية؛ لا تُترجم عند اللصق). الشرح العربي حولها للمالك فقط.

المرجع التقني (لا تغيّره من هنا):
- `android/app/src/main/AndroidManifest.xml`: الأذونات `USE_EXACT_ALARM`، `SCHEDULE_EXACT_ALARM` (maxSdkVersion=32)،
  `FOREGROUND_SERVICE`، `FOREGROUND_SERVICE_MEDIA_PLAYBACK`، والخدمة `.AdhanPlayerService` (`foregroundServiceType="mediaPlayback"`).
- `AdhanScheduler.java`: منبّه `setExactAndAllowWhileIdle(RTC_WAKEUP)` لكل أذان؛ إن لم يكن المنبّه الدقيق مسموحاً
  (`canScheduleExactAlarms()==false`) يرجع إلى `setAndAllowWhileIdle` (موقظ لكن غير دقيق).
- `AdhanPlayerService.java`: خدمة أمامية تشغّل ملف الأذان المضمّن (res/raw، بلا شبكة) مع إشعار دائم وزرّي «إيقاف» و«كتم اليوم»،
  وتوقف نفسها عند نهاية الملف أو بعد ٦ دقائق كحدّ أقصى (`MAX_MS`).
- الخطة البديلة إن رُفض `USE_EXACT_ALARM`: `docs/N37_EXACT_ALARM_PLAN_B.md`.

---

## ١) إقرار المنبّه الدقيق (Exact alarm permission — USE_EXACT_ALARM)

### أين
Play Console ← التطبيق ← **Policy and programs → App content → Exact alarm permission** (يظهر الإقرار تلقائياً عند رفع
حزمة فيها `USE_EXACT_ALARM`). إن لم يظهر في App content فسيظهر كسؤال إلزامي في صفحة الإصدار قبل الإرسال للمراجعة.

### ماذا نختار
- **Core functionality:** اختر **Alarm clock / Alarms** (وليس Calendar). الأذان منبّه يضبطه المستخدم لكل صلاة، وتأخّره
  دقائق يُفقده معناه (وقت الصلاة يدخل في دقيقة محدّدة؛ والفجر/المغرب مرتبطان بالصيام والإفطار).
- لا تختر «Other» — الإقرار مقبول فقط لفئتي المنبّه والتقويم.

### النصّ الذي يُلصق (English, paste as-is)
```text
Al-Mujaddid is an Islamic prayer-times app. Its core, user-facing function is the adhan: an alarm that the user turns on
per prayer (Fajr, Dhuhr, Asr, Maghrib, Isha) and that must sound at the exact minute each prayer time begins. Prayer times
are computed on the device from the user's location and calculation method, and change every day, so the app schedules
one exact alarm per enabled prayer (setExactAndAllowWhileIdle, RTC_WAKEUP). When the alarm fires, the app plays the
adhan with a visible notification that has Stop and "Mute today" buttons.

A delay of even a few minutes defeats the purpose: the call to prayer marks the start of the prayer window, and the
Fajr and Maghrib times also mark the start and end of the daily fast in Ramadan. Inexact or windowed alarms (which Doze
can defer by several minutes) are therefore not acceptable for this feature.

Device-waking exact alarms (setExactAndAllowWhileIdle) are used only for the adhan alarms the user explicitly enabled,
for a reminder about once a month to renew the adhan schedule (shown only while the adhan is enabled), and for the
one-off test the user can start from the adhan settings. Other optional reminders in the app (hourly
dhikr, morning/evening adhkar) use non-wakeup alarms without allow-while-idle, so they never wake the device from Doze.
The user can turn the adhan off for any prayer, or entirely, in Settings > Prayer adhan.
```

> تنبيه للمالك (دقّة الإقرار): التذكيرات الاختيارية (الذكر الساعي، أذكار الصباح/المساء) تُجدوَل عبر `@capacitor/local-notifications`
> التي تستعمل `setExact(AlarmManager.RTC)` حين يكون المنبّه الدقيق مسموحاً — أي إنها منبّهات «دقيقة» تقنياً لكنها **غير موقظة**
> (بلا `allowWhileIdle`). لذا لا تكتب في أي نموذج أو استئناف أن المنبّهات الدقيقة «لا تُستعمل إلا للأذان»؛ الصحيح أن المنبّهات
> الدقيقة **الموقظة** للأذان، ولتذكير «جدّد جدول الأذان» (المعرّف 2199، القناة `adhan_renew`، نحو مرة كل ~٢٦ يوماً
> الساعة 09:00، بـ `allowWhileIdle` في الوضعين الكامل والقصير — `native-bridge.js` دالة `scheduleNoorNotifications`)،
> ولاختبار الأذان اليدوي. النصّان أعلاه وفي الاستئناف مصوغان على هذا الأساس.

### إن رُفض الإقرار
1. **استأنف أولاً (Appeal)** من صفحة الرفض في Policy status بالنصّ التالي (مع رابط فيديو قصير يُظهر شاشة المواقيت ومفاتيح الأذان لكل صلاة ثم رنين الأذان):
```text
The adhan (Islamic call to prayer) is a user-configured alarm and the primary feature of this app: the user enables it
per prayer and expects it at the exact minute the prayer time begins, which also marks the start/end of fasting in
Ramadan. This is the same use case as an alarm clock: a time-critical, user-set alarm that alerts the user audibly at
a precise time. Device-waking (allow-while-idle) exact alarms are used only for these user-enabled adhan alarms (one per
prayer per day) and for a reminder about once a month to renew the adhan schedule; the app's other optional reminders use non-wakeup alarms and never wake the device from Doze.
Video showing the feature: <VIDEO_LINK>
```
2. إن تكرّر الرفض: نفّذ **الخطة ب** (`docs/N37_EXACT_ALARM_PLAN_B.md`) — تبديل البيان إلى `SCHEDULE_EXACT_ALARM` فقط.
   كل ما يلزم في Java والواجهة مُنفَّذ مسبقاً؛ الخطة ب لا تحتاج إقراراً في Play Console (الإذن يمنحه المستخدم من
   «المنبّهات والتذكيرات»)، ثم احذف إقرار Exact alarm من App content في الإصدار التالي.
3. لا ترفع إصداراً جديداً بـ `USE_EXACT_ALARM` بعد رفض نهائي — سيُرفض الإصدار كله.

---

## ٢) إقرار نوع الخدمة الأمامية (Foreground service — Media playback)

### أين
Play Console ← **Policy and programs → App content → Foreground service permissions** (يظهر لأن البيان فيه
`FOREGROUND_SERVICE_MEDIA_PLAYBACK` ويستهدف API 34+؛ targetSdk الحالي 36).

### ماذا نختار
- **Foreground service type:** `Media playback` (الوحيد المعلَن).
- **Task / feature:** اختر ما يقابل «Playing audio» / «Media playback – user-initiated or user-scheduled audio».
- **User impact if deferred/interrupted:** الأذان ينقطع أو لا يُسمع حين يكون الهاتف مقفلاً والتطبيق مغلقاً.

### النصّ الذي يُلصق — وصف الاستخدام (English, paste as-is)
```text
Feature: full adhan (Islamic call to prayer) playback at the exact prayer time.

Al-Mujaddid is a prayer-times app. When a prayer time begins (usually while the phone is locked and the app is not
running), an exact alarm the user enabled for that prayer starts AdhanPlayerService, a foreground service of type
mediaPlayback. The service plays one of the adhan recordings bundled inside the APK (about 3 to 4.5 minutes each, all under 5 minutes; no network access) on the
alarm or notification audio stream chosen by the user, and shows a persistent, user-visible notification with the
prayer name and two actions: "Stop" and "Mute today".

The service starts only from a user-enabled adhan alarm, or when the user taps "Test" in the adhan settings. It stops
itself as soon as the recording ends, when the user taps Stop, or after at most 6 minutes. It never runs without its
notification, never runs at other times, and does not collect or transmit any data.

A foreground service is required because the audio must keep playing for several minutes while the screen is locked and
the app process is not in the foreground; a regular notification sound is cut after a few seconds on many devices.
The user can switch the adhan per prayer off, or use "short alert only", which uses a plain notification instead of
this service.
```

### النصّ الذي يُلصق — ما يحدث لو أُوقفت الخدمة/أُجّلت (if asked)
```text
If the service were deferred or stopped by the system, the adhan would not be heard at the start of the prayer time,
which is the feature the user explicitly enabled. There is no way to defer this work: it must happen at the prayer time.
```

### سيناريو الفيديو المطلوب (رابط YouTube «غير مدرج» Unlisted أو Google Drive عام للعرض، ٣٠–٩٠ ثانية)
Play تطلب فيديو يُظهر **تشغيل الميزة من منظور المستخدم** و**الإشعار الظاهر**. صوّر بتسجيل الشاشة المدمج في أندرويد
(مع تسجيل صوت الجهاز) على هاتف أندرويد ١٤ أو أحدث، بنسخة الإصدار الموقّعة من Play (Internal testing):

1. **(٠–١٠ ث)** افتح «المجدّد» ← الإعدادات ← قسم «أذان الصلاة»: أظهر أنه «مفعّل ✓» وأزرار كل صلاة (الفجر… العشاء).
2. **(١٠–٢٠ ث)** اضغط «🔔 تجربة»: يبدأ الأذان فوراً ويظهر إشعار «الأذان» الدائم. اسحب شريط الإشعارات لإظهار الإشعار
   وزرّ «إيقاف». اضغط «إيقاف» ليتوقف الصوت ويختفي الإشعار. (هذا يثبت أن المستخدم يتحكّم بالخدمة.)
3. **(٢٠–٣٠ ث)** اجعل الصلاة القادمة بعد دقيقتين: الإعدادات ← التاريخ والوقت ← أوقف «الوقت التلقائي» واضبط الساعة قبل
   وقت الصلاة القادمة بدقيقتين (أو استعمل تعديل الدقائق اليدوي للصلاة في إعدادات المواقيت). أظهر شاشة المواقيت والعدّاد.
4. **(٣٠–٤٠ ث)** أغلق التطبيق من «التطبيقات الأخيرة» (اسحبه للخارج) ثم اقفل الشاشة.
5. **(عند الوقت)** يرنّ الأذان والشاشة مقفلة: أوقظ الشاشة لإظهار الإشعار على شاشة القفل («الأذان — الظهر» مثلاً) مع
   زرّي «إيقاف» و«كتم اليوم». دع الصوت يُسمع ٥–١٠ ثوانٍ ثم اضغط «إيقاف».
6. **(النهاية)** أعِد «الوقت التلقائي». لا حاجة لتعليق صوتي؛ يكفي نصّ على الشاشة إن أردت:
   `Adhan alarm at prayer time -> foreground media playback with notification -> user taps Stop.`

ملاحظات التصوير: لا تُظهر بيانات حساب أو موقعاً دقيقاً (اختر مدينة عامة)؛ إن ظهر حوار إذن الإشعارات فاقبله في الفيديو؛
واجهة عربية مقبولة، لكن اجعل لغة التطبيق English إن أمكن (الإعدادات ← اللغة) ليسهل على المراجع.

### الإشعار الظاهر للمستخدم (للمرجع في النموذج)
- القناة: «الأذان الكامل» / *Full adhan* — الوصف: *Plays the full adhan when a prayer time begins, even with the app closed*.
- العنوان: «الأذان — {الصلاة}» / *Adhan — {prayer}*، والإشعار دائم (ongoing) طوال التشغيل.
- الإجراءات: «إيقاف» / *Stop*، و«كتم اليوم» / *Mute today* (لا يظهر «كتم اليوم» في التجربة).

---

## ٣) ملاحظات «أمان البيانات» (Data safety) المتعلّقة بهاتين الميزتين

لا تضيف هاتان الميزتان أي نوع بيانات إلى استمارة Data safety:
- **المنبّهات الدقيقة:** أوقات الصلاة تُحسب على الجهاز وتُخزَّن محلياً (SharedPreferences `noor_adhan`) لضبط المنبّهات
  وإعادتها بعد الإقلاع. لا تُرسَل لأي خادم.
- **خدمة تشغيل الأذان:** تشغّل ملفاً صوتياً مضمّناً في الـAPK؛ لا شبكة، لا ميكروفون، لا تسجيل، لا تحليلات.
- **الموقع:** يُستعمل لحساب المواقيت والقبلة على الجهاز (مُصرَّح به أصلاً في الاستمارة إن كان الموقع مذكوراً لغير ذلك) —
  ميزة الأذان لا تجمع الموقع ولا ترسله. إن كانت الاستمارة الحالية تقول «Location: not collected» فهذا يبقى صحيحاً لهذه الميزة.
- لا يغيّر أيّ منهما الإجابات عن المشاركة مع أطراف ثالثة أو التشفير أثناء النقل أو حذف البيانات.

نصّ مختصر إن طُلب توضيح (English):
```text
The adhan alarms and the adhan playback service run entirely on the device. Prayer times are computed locally; the
schedule is stored locally only to re-arm alarms after a reboot. The playback service plays an audio file bundled in
the app. Neither feature collects, stores remotely, or shares any user data.
```

تذكير عام (خارج نطاق هذا الملف): بقية الاستمارة (تسجيل الدخول بحساب Google/Firebase، المزامنة، الكاميرا والميكروفون
للمعالجة على الجهاز) تُراجَع مقابل `privacy.html` قبل الإرسال.

---

## قائمة تحقّق قبل الإرسال للمراجعة
- [ ] إقرار Exact alarm: الفئة Alarm + النصّ أعلاه.
- [ ] إقرار Foreground service permissions: Media playback + الوصف + رابط الفيديو (Unlisted، يعمل دون تسجيل دخول).
- [ ] Data safety: لا تغيير بسبب هاتين الميزتين.
- [ ] نسخة الاختبار الداخلي تُظهر الأذان فعلاً والتطبيق مغلق على أندرويد ١٤+ (وإلا فالفيديو لن يطابق السلوك).
- [ ] إن رُفض USE_EXACT_ALARM مرتين: الخطة ب في `docs/N37_EXACT_ALARM_PLAN_B.md`.
