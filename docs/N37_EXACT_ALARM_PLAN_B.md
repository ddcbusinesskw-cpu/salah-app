# N37 — الخطة ب للمنبّه الدقيق: من USE_EXACT_ALARM إلى SCHEDULE_EXACT_ALARM

> يشير تعليق البيان (`AndroidManifest.xml` ~السطر 101) إلى «البديل في N37.md» — لم يكن ذلك الملف موجوداً قطّ؛ هذا هو.
> **لا تُطبَّق هذه الخطة إلا إن رفضت Google Play إقرار «Exact alarm» لـ `USE_EXACT_ALARM`** (انظر `docs/PLAY_DECLARATIONS_ar.md`).

## الوضع الحالي (الخطة أ)
| أندرويد | الإذن الفعّال | الحالة |
|---|---|---|
| ≤ 11 (API ≤ 30) | لا يلزم إذن | `setExactAndAllowWhileIdle` دقيق دائماً |
| 12/12L (API 31–32) | `SCHEDULE_EXACT_ALARM` (maxSdkVersion=32) | ممنوح افتراضياً، يمكن للمستخدم سحبه |
| 13+ (API 33+) | `USE_EXACT_ALARM` | ممنوح دائماً ولا يُسحب — **يتطلّب إقرار Play (فئة Alarm)** |

## الخطة ب
احذف `USE_EXACT_ALARM` وأعلن `SCHEDULE_EXACT_ALARM` لكل الإصدارات. لا يحتاج إقراراً في Play Console، لكن:
- أندرويد 12–13: الإذن ممنوح افتراضياً عادةً.
- **أندرويد 14+: مرفوض افتراضياً للتثبيت الجديد** (targetSdk ≥ 33)؛ والمستخدم الذي يحدّث من الخطة أ أيضاً لا يملكه
  (كان مُعلَناً حتى API 32 فقط). يجب أن يمنحه المستخدم يدوياً من «الإعدادات ← التطبيقات ← المجدّد ← المنبّهات والتذكيرات».
- إن لم يمنحه: الأذان **يبقى يعمل ويوقظ الهاتف** لكن بمنبّه غير دقيق (`setAndAllowWhileIdle`) قد يتأخّر بضع دقائق في
  وضع السكون (Doze). لا يضيع الأذان، ولا يتعطّل التطبيق.
- إن سحبه المستخدم لاحقاً: يلغي النظام المنبّهات الدقيقة القائمة ويوقف التطبيق؛ عند فتحه التالي يعيد الجسر الجدولة
  (`appStateChange` ← `scheduleNoorPrayerAlerts`) بالمسار غير الدقيق.

## ما نُفِّذ مسبقاً (كي تكون الخطة ب تبديلاً في البيان فقط)
كله محروس. مع الخطة أ `canScheduleExactAlarms()` صحيح دائماً على أندرويد 13+ (وما قبل 12) فلا أثر لهذه الإضافات هناك،
**إلا على أندرويد 12/12L (API 31–32)**: هناك `SCHEDULE_EXACT_ALARM` (maxSdkVersion=32) قابل للسحب (الجدول أعلاه)، فإن سحبه
المستخدم يظهر التنبيه والزرّ في الخطة أ أيضاً، والزرّ يعمل لأن الإذن معلَن على هذين الإصدارين. وبعد المنح على 12/12L في الخطة أ
لا يصل البثّ إلى `AdhanSystemReceiver` (لا intent-filter له)، فإعادة الضبط الدقيق تعتمد فقط على مسار العودة للتطبيق في
`native-bridge.js` (البند ٤).

1. **Java — الجدولة** (`AdhanScheduler.arm`، كان موجوداً): على API 31+ إن كان `canScheduleExactAlarms()==false` →
   `setAndAllowWhileIdle(RTC_WAKEUP)` بدل `setExactAndAllowWhileIdle`، و`SecurityException` يرجع للمسار نفسه.
   إضافة الإشعارات (`@capacitor/local-notifications` 6.1.2، مسار التنبيه القصير) تفعل الشيء نفسه في `setExactIfPossible`.
   اخترنا `setAndAllowWhileIdle` لا `setWindow`: الأول يوقظ الهاتف في Doze (حدّ ~مرة كل ٩ دقائق للتطبيق — يكفي ٥ أذانات يومياً)،
   أما `setWindow` فلا يعمل أثناء السكون إلا عند نوافذ الصيانة.
2. **Java — صفحة الإذن** (`AppSettingsPlugin.open({target:'exactAlarm'})`، جديد): على API 31+ يفتح
   `android.settings.REQUEST_SCHEDULE_EXACT_ALARM` لهذا التطبيق؛ إن رمى (نظام معدّل بلا هذا النشاط) يفتح صفحة التطبيق.
   ملاحظة: على AOSP يُحَلّ هذا النشاط ولا يرمي حتى لو لم يُعلَن الإذن، فالرجوع شبكة أمان للأنظمة المعدّلة فقط.
3. **Java — إعادة التسليح عند المنح** (`AdhanSystemReceiver`، جديد): الفعل
   `android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` → `AdhanScheduler.rearm()` فتُضبط المنبّهات المخزّنة
   دقيقةً فوراً حتى لو لم يُفتح التطبيق. يحتاج سطر intent-filter في البيان (ضمن الفرق أدناه).
4. **الجسر** (`native-bridge.js`): `NoorAdhan.isAvailable()` يعيد `exact` (= `canScheduleExactAlarms()`)؛ صار يُعاد
   الاستعلام عند كل عودة للتطبيق، وإن تغيّر تُعاد الجدولة ويُعاد رسم الإعدادات وشاشة المواقيت (إن كانت مفتوحة).
5. **الواجهة** (`index.html` `_adhanPermWarn`): حين يكون الأذان مفعّلاً والوضع الكامل متاحاً و`exact=false` يظهر في
   الإعدادات وشاشة المواقيت تنبيه «⚠ إذن «المنبّهات والتذكيرات» غير ممنوح — قد يتأخّر الأذان بضع دقائق…» مع زرّ
   «افتح إعدادات التطبيق» (`_openAppSettings('exactAlarm')`). مترجَم للغات الست. تنبيه إذن الإشعارات يتقدّم عليه.

## التطبيق (البيان فقط)
الفرق نفسه في `scratchpad/fixes/proposals/N37_planB_manifest.md`. يُطبَّق على `android/app/src/main/AndroidManifest.xml`
كما هو عند الالتزام 3184c00 وما بعده (تحقّق بـ `git apply --check`).

> **مهم:** هذا الفرق مبنيّ على البيان **قبل** تصحيح التعليق (القسم الأخير من هذا الملف). إن كنت طبّقت تصحيح التعليق
> فاستعمل النسخة (ب′) بعد الفرق التالي مباشرةً — أو نفّذ `git apply -R` لفرق التصحيح أولاً ثم طبّق هذا. تطبيق هذا الفرق
> فوق التصحيح يفشل بـ `error: patch failed: android/app/src/main/AndroidManifest.xml:96`.

```diff
--- a/android/app/src/main/AndroidManifest.xml
+++ b/android/app/src/main/AndroidManifest.xml
@@ -49,6 +49,8 @@
                 <action android:name="android.intent.action.BOOT_COMPLETED" />
                 <action android:name="android.intent.action.QUICKBOOT_POWERON" />
                 <action android:name="android.intent.action.TIMEZONE_CHANGED" />
+                <!-- N37 الخطة ب: منح «المنبّهات والتذكيرات» → AdhanSystemReceiver يعيد ضبط منبّهات الأذان دقيقةً -->
+                <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" />
             </intent-filter>
         </receiver>
 
@@ -96,12 +98,11 @@
     <!-- Local Notifications + wakelock for prayer alerts -->
     <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
     <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
-    <!-- N37: الأذان منبّه يضبطه المستخدم لكل صلاة ويجب أن يرنّ في دقيقته — الوظيفة الأساسية للتطبيق.
-         USE_EXACT_ALARM (أندرويد 13+) يتطلّب إقرار «Exact alarm» في Play Console (الفئة: Alarm). تذكير الذكر الساعي
-         لم يعد يوقظ الهاتف (native-bridge.js: بلا allowWhileIdle). إن رفضت Play الإقرار: البديل في N37.md (SCHEDULE_EXACT_ALARM) -->
-    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
-    <!-- دقة الأذان على أندرويد 12/12L (USE_EXACT_ALARM يبدأ من API 33) -->
-    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />
+    <!-- N37 الخطة ب (docs/N37_EXACT_ALARM_PLAN_B.md): رفضت Play إقرار USE_EXACT_ALARM فصار الأذان على SCHEDULE_EXACT_ALARM.
+         أندرويد 12/13: ممنوح افتراضياً غالباً؛ أندرويد 14+: مرفوض افتراضياً للتثبيت الجديد — الإعدادات تعرض تنبيهاً وزرّاً
+         يفتح «المنبّهات والتذكيرات» (AppSettings.open target=exactAlarm). بدونه يبقى الأذان موقظاً لكن غير دقيق
+         (setAndAllowWhileIdle — قد يتأخّر دقائق في وضع السكون). تذكير الذكر الساعي لا يوقظ الهاتف (بلا allowWhileIdle) -->
+    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
     <uses-permission android:name="android.permission.WAKE_LOCK" />
     <!-- N37: أُزيل REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — سياسة Play تمنع طلب الاستثناء المباشر إلا إن تعطّلت الوظيفة
          الأساسية؛ صار التطبيق يشرح السبب ثم يفتح قائمة «تحسين البطارية» في الإعدادات (لا تحتاج إذناً) -->
```

**(ب′) النسخة نفسها للبيان بعد تطبيق تصحيح التعليق** (النتيجة مطابقة بايتاً):

```diff
--- a/android/app/src/main/AndroidManifest.xml
+++ b/android/app/src/main/AndroidManifest.xml
@@ -49,6 +49,8 @@
                 <action android:name="android.intent.action.BOOT_COMPLETED" />
                 <action android:name="android.intent.action.QUICKBOOT_POWERON" />
                 <action android:name="android.intent.action.TIMEZONE_CHANGED" />
+                <!-- N37 الخطة ب: منح «المنبّهات والتذكيرات» → AdhanSystemReceiver يعيد ضبط منبّهات الأذان دقيقةً -->
+                <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" />
             </intent-filter>
         </receiver>
 
@@ -96,12 +98,11 @@
     <!-- Local Notifications + wakelock for prayer alerts -->
     <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
     <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
-    <!-- N37: الأذان منبّه يضبطه المستخدم لكل صلاة ويجب أن يرنّ في دقيقته — الوظيفة الأساسية للتطبيق.
-         USE_EXACT_ALARM (أندرويد 13+) يتطلّب إقرار «Exact alarm» في Play Console (الفئة: Alarm). تذكير الذكر الساعي
-         لم يعد يوقظ الهاتف (native-bridge.js: بلا allowWhileIdle). إن رفضت Play الإقرار: الخطة ب في docs/N37_EXACT_ALARM_PLAN_B.md (SCHEDULE_EXACT_ALARM) -->
-    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
-    <!-- دقة الأذان على أندرويد 12/12L (USE_EXACT_ALARM يبدأ من API 33) -->
-    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />
+    <!-- N37 الخطة ب (docs/N37_EXACT_ALARM_PLAN_B.md): رفضت Play إقرار USE_EXACT_ALARM فصار الأذان على SCHEDULE_EXACT_ALARM.
+         أندرويد 12/13: ممنوح افتراضياً غالباً؛ أندرويد 14+: مرفوض افتراضياً للتثبيت الجديد — الإعدادات تعرض تنبيهاً وزرّاً
+         يفتح «المنبّهات والتذكيرات» (AppSettings.open target=exactAlarm). بدونه يبقى الأذان موقظاً لكن غير دقيق
+         (setAndAllowWhileIdle — قد يتأخّر دقائق في وضع السكون). تذكير الذكر الساعي لا يوقظ الهاتف (بلا allowWhileIdle) -->
+    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
     <uses-permission android:name="android.permission.WAKE_LOCK" />
     <!-- N37: أُزيل REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — سياسة Play تمنع طلب الاستثناء المباشر إلا إن تعطّلت الوظيفة
          الأساسية؛ صار التطبيق يشرح السبب ثم يفتح قائمة «تحسين البطارية» في الإعدادات (لا تحتاج إذناً) -->
```

لا تغيير في gradle ولا في `capacitor.config.json` ولا `package.json`.

## بعد التطبيق
1. في Play Console احذف إقرار Exact alarm (App content) — لن يُطلب لأن `USE_EXACT_ALARM` لم يعد في الحزمة.
2. اختبار على جهاز أندرويد 14+ (تثبيت نظيف):
   - فعّل الأذان ← **يظهر أولاً حوار لمرّة واحدة «اسمح بمنبّه الأذان الدقيق»** (`_exactAlarmAsk` في `index.html`، موجود من قبل،
     يُعرض عند تفعيل الأذان إن لم يكن الإذن ممنوحاً، ويُحفظ `exact_alarm_asked=1` فلا يتكرّر). لاختبار التنبيه الجديد أغلق
     ذلك الحوار دون منح (أو امنح منه ثم اسحب الإذن لاحقاً). قد يليه حوار تحسين البطارية لمرّة واحدة أيضاً.
   - بعد الحوار: يظهر التنبيه الجديد في الإعدادات وفي شاشة المواقيت ← الزرّ يفتح «المنبّهات والتذكيرات» للتطبيق ← امنح ← ارجع:
     يختفي التنبيه من الشاشتين.
   - `adb shell dumpsys alarm | grep -A3 com.ddcbusiness.noor` قبل المنح: منبّهات غير دقيقة؛ بعده (دون فتح التطبيق إن
     أمكن): منبّهات `ADHAN_ALARM` دقيقة (`window=0` مع `ALLOW_WHILE_IDLE`).
   - اسحب الإذن: يُقتل التطبيق؛ افتحه ← يعود التنبيه، والأذان يرنّ (قد يتأخّر دقائق في Doze: `adb shell dumpsys deviceidle force-idle`).
3. اختبار على أندرويد 12/13: الإذن ممنوح افتراضياً — لا تنبيه، والسلوك كالخطة أ (إلا إن سحبه المستخدم؛ وعلى 12/12L يصحّ هذا في الخطة أ أيضاً).

## الرجوع إلى الخطة أ
`git apply -R` **للفرق الذي طبّقته فعلاً**: (ب) إن طُبّق على البيان الأصلي، أو (ب′) إن طُبّق بعد تصحيح التعليق —
`git apply -R` لـ(ب) على بيان طُبّق عليه (ب′) يفشل. بعد الرجوع عن (ب′) يبقى تصحيح التعليق مطبَّقاً (وهو المطلوب للخطة أ).
كود Java/الواجهة يبقى كما هو (محروس).

## تصحيح التعليق القديم دون تطبيق الخطة ب
إن بقيت الخطة أ، فهذا الفرق وحده يصحّح الإشارة إلى الملف غير الموجود (مقترح، لم يُطبَّق). بعد تطبيقه، إن احتجت
الخطة ب لاحقاً فاستعمل (ب′) لا (ب):

```diff
--- a/android/app/src/main/AndroidManifest.xml
+++ b/android/app/src/main/AndroidManifest.xml
@@ -98,7 +98,7 @@
     <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
     <!-- N37: الأذان منبّه يضبطه المستخدم لكل صلاة ويجب أن يرنّ في دقيقته — الوظيفة الأساسية للتطبيق.
          USE_EXACT_ALARM (أندرويد 13+) يتطلّب إقرار «Exact alarm» في Play Console (الفئة: Alarm). تذكير الذكر الساعي
-         لم يعد يوقظ الهاتف (native-bridge.js: بلا allowWhileIdle). إن رفضت Play الإقرار: البديل في N37.md (SCHEDULE_EXACT_ALARM) -->
+         لم يعد يوقظ الهاتف (native-bridge.js: بلا allowWhileIdle). إن رفضت Play الإقرار: الخطة ب في docs/N37_EXACT_ALARM_PLAN_B.md (SCHEDULE_EXACT_ALARM) -->
     <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
     <!-- دقة الأذان على أندرويد 12/12L (USE_EXACT_ALARM يبدأ من API 33) -->
     <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />
```
