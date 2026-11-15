# مقترحات ملفات البناء (لم يُطبَّق منها شيء — تنتظر موافقة المالك)

كل بند أدناه يعدّل ملف بناء، لذا كُتب مقترحاً فقط. لا يُطبَّق إلا بعد موافقة المالك.

---

## AB-missed — بناء release محلياً بلا مفاتيح التوقيع يفشل بـ NullPointerException غامض

**الحالة:** مقترح — **لم يُطبَّق** (`android/app/build.gradle` ملف بناء). التوثيق **طُبِّق** في `BUILD_GUIDE.md`
(قسم «توقيع نسخة release محلياً» وقسم «ملف محرّك التسميع»).

## المشكلة
`signingConfigs.release` لا يُملأ إلا إن وُجد `CM_KEYSTORE_PATH`، لكن `buildTypes.release` يربطه دائماً.
محلياً بلا المتغيرات: `:app:signReleaseBundle` → `java.lang.NullPointerException (no error message)`
(`audit4/android-build/release.log:584,651`). لا شيء يدلّ المطوّر على السبب.

## الحل المقترح
- التحقق من المتغيرات الأربعة ووجود ملف المفتاح.
- إن نقص شيء ولم تُطلب مهمة release: لا شيء (لا ضجيج في بناء debug).
- إن طُلبت مهمة release:
  - على CI (`CI` مضبوط — Codemagic يضبطه): **فشل فوري برسالة واضحة** تسمّي المتغير الناقص (بدل رفع AAB غير موقّع).
  - محلياً: تحذير واضح + بناء release **غير موقّع** (يُوقَّع يدوياً بـ `zipalign -p -f 4` ثم `apksigner sign` كما في BUILD_GUIDE — **لا** بـ `jarsigner`: التطبيق يستهدف SDK 36 وتوقيع v1 وحده يرفضه Android 11+ عند التثبيت `INSTALL_PARSE_FAILED_NO_CERTIFICATES`).
- لا تُطبع أي قيمة سرّية — أسماء المتغيرات فقط.

## الفرق
```diff
--- a/android/app/build.gradle
+++ b/android/app/build.gradle
@@ -51,9 +51,26 @@
             }
         }
     }
+    // AB-missed: بلا CM_KEYSTORE_* كان signReleaseBundle يفشل بـ NullPointerException بلا رسالة.
+    // الآن: على خادم CI (CI=true) نفشل فوراً برسالة واضحة؛ محلياً نبني release غير موقّع مع تحذير
+    // (يُوقَّع يدوياً بـ zipalign ثم apksigner كما في BUILD_GUIDE.md، أو تُضبط المتغيرات الأربعة).
+    def _ksVars = ["CM_KEYSTORE_PATH", "CM_KEYSTORE_PASSWORD", "CM_KEY_ALIAS", "CM_KEY_PASSWORD"]
+    def _ksMissing = _ksVars.findAll { !System.getenv(it) }
+    def _ksPath = System.getenv("CM_KEYSTORE_PATH")
+    if (_ksPath && !file(_ksPath).exists()) _ksMissing += ["CM_KEYSTORE_PATH (file not found)"]
+    def _ksOk = _ksMissing.isEmpty()
+    if (!_ksOk) {
+        def _ksMsg = "Release signing disabled: missing " + _ksMissing.join(", ") + " — see BUILD_GUIDE.md («توقيع نسخة release محلياً»)."
+        gradle.taskGraph.whenReady { g ->
+            if (g.allTasks.any { it.name.toLowerCase().contains("release") }) {
+                if (System.getenv("CI")) throw new GradleException(_ksMsg)
+                logger.warn("WARNING: " + _ksMsg + " Building an UNSIGNED release.")
+            }
+        }
+    }
     buildTypes {
         release {
-            signingConfig signingConfigs.release
+            signingConfig _ksOk ? signingConfigs.release : null
             minifyEnabled false
             proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
         }
```

## التحقق
- محلياً بلا متغيرات: `./gradlew bundleRelease` → تحذير «Release signing disabled: missing …» ثم نجاح بملف غير موقّع.
- `CI=1 ./gradlew bundleRelease` بلا متغيرات → فشل فوري: `Release signing disabled: missing CM_KEYSTORE_PATH, …`.
- `./gradlew assembleDebug` → لا تحذير.
- Codemagic (المتغيرات موجودة) → سلوك مطابق لليوم، AAB موقّع.

## المخاطر
- منخفضة. أسوأ حالة: نسيان ربط مجموعة `noor_keystore` في Codemagic يُفشل البناء برسالة واضحة — وهذا مطلوب.
- `CI` قد يكون مضبوطاً في بيئات محلية خاصة؛ عندها يفشل بدل التحذير — مقبول وصريح.

```diff
--- a/android/app/build.gradle
+++ b/android/app/build.gradle
@@ -51,9 +51,26 @@
             }
         }
     }
+    // AB-missed: بلا CM_KEYSTORE_* كان signReleaseBundle يفشل بـ NullPointerException بلا رسالة.
+    // الآن: على خادم CI (CI=true) نفشل فوراً برسالة واضحة؛ محلياً نبني release غير موقّع مع تحذير
+    // (يُوقَّع يدوياً بـ zipalign ثم apksigner كما في BUILD_GUIDE.md، أو تُضبط المتغيرات الأربعة).
+    def _ksVars = ["CM_KEYSTORE_PATH", "CM_KEYSTORE_PASSWORD", "CM_KEY_ALIAS", "CM_KEY_PASSWORD"]
+    def _ksMissing = _ksVars.findAll { !System.getenv(it) }
+    def _ksPath = System.getenv("CM_KEYSTORE_PATH")
+    if (_ksPath && !file(_ksPath).exists()) _ksMissing += ["CM_KEYSTORE_PATH (file not found)"]
+    def _ksOk = _ksMissing.isEmpty()
+    if (!_ksOk) {
+        def _ksMsg = "Release signing disabled: missing " + _ksMissing.join(", ") + " — see BUILD_GUIDE.md («توقيع نسخة release محلياً»)."
+        gradle.taskGraph.whenReady { g ->
+            if (g.allTasks.any { it.name.toLowerCase().contains("release") }) {
+                if (System.getenv("CI")) throw new GradleException(_ksMsg)
+                logger.warn("WARNING: " + _ksMsg + " Building an UNSIGNED release.")
+            }
+        }
+    }
     buildTypes {
         release {
-            signingConfig signingConfigs.release
+            signingConfig _ksOk ? signingConfigs.release : null
             minifyEnabled false
             proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
         }
```

---

## AB-02 — ترقية AGP لإلغاء استخراج المكتبات الأصلية (~29 MB على ذاكرة الهاتف)

**الحالة:** مقترح — **لم يُطبَّق** (ملفات بناء: `android/build.gradle`, `android/app/build.gradle`, `android/gradle/wrapper/gradle-wrapper.properties`).

## المشكلة
`useLegacyPackaging true` يضغط مكتبات `.so` داخل الـAPK، فيضطر النظام عند التثبيت إلى **استخراجها** إلى
`/data/app/.../lib/` — نسخة ثانية مقاسة بـ **29,127,552 بايت** (arm64، `verify-android-build/metrics-recheck.txt`).
كان الضغط حيلةً لأن AGP 8.2.1 لا يحاذي المكتبات غير المضغوطة على حدود 16KB داخل zip (شرط أندرويد 15+ / Play).
AGP **8.5.1 فأحدث** يحاذيها تلقائياً، فلا داعي للضغط.

## الحل المقترح
- AGP `8.2.1 → 8.7.3` (أحدث سلسلة مختبرة مع Capacitor 7؛ تعمل مع Capacitor 6 لأن الـclasspath في الجذر هو المعتمد).
- Gradle `8.2.1 → 8.9` (الحد الأدنى لـAGP 8.7).
- `useLegacyPackaging false` **صراحةً** (الافتراضي مع minSdk 22 هو true).
- `android.suppressUnsupportedCompileSdk=36` الموجود في gradle.properties يبقى (AGP 8.7 مختبر حتى 35).

## الفرق (يطبَّق بـ `git apply` من جذر المستودع)
```diff
--- a/android/app/build.gradle
+++ b/android/app/build.gradle
@@ -5,9 +5,11 @@
     compileSdk rootProject.ext.compileSdkVersion
     // محرّك Whisper الأصلي (whisper.cpp) عبر CMake/NDK — إصدار مثبَّت
     ndkVersion "26.1.10909125"
-    // AGP 8.2 لا يحاذي zip على 16KB للمكتبات غير المضغوطة — نضغطها بدل ترقية AGP
+    // AB-02: AGP ≥ 8.5.1 يحاذي المكتبات غير المضغوطة على 16KB داخل zip — فلا داعي للضغط
+    // (الضغط كان يجبر النظام على استخراج ~29MB من .so إلى ذاكرة الهاتف عند التثبيت).
+    // صراحةً false لأن الافتراضي مع minSdk 22 هو true.
     packaging { jniLibs {
-        useLegacyPackaging true
+        useLegacyPackaging false
         // N64: مكتبتا C/C++ API في sherpa-onnx غير مستعملتين (libsherpa-onnx-jni لا تحتاج إلا libonnxruntime) — ~4.9MB لكل معمارية
         excludes += ['**/libsherpa-onnx-c-api.so', '**/libsherpa-onnx-cxx-api.so']
     } }
--- a/android/build.gradle
+++ b/android/build.gradle
@@ -7,7 +7,7 @@
         mavenCentral()
     }
     dependencies {
-        classpath 'com.android.tools.build:gradle:8.2.1'
+        classpath 'com.android.tools.build:gradle:8.7.3'
         classpath 'com.google.gms:google-services:4.4.0'
 
         // NOTE: Do not place your application dependencies here; they belong
--- a/android/gradle/wrapper/gradle-wrapper.properties
+++ b/android/gradle/wrapper/gradle-wrapper.properties
@@ -1,6 +1,6 @@
 distributionBase=GRADLE_USER_HOME
 distributionPath=wrapper/dists
-distributionUrl=https\://services.gradle.org/distributions/gradle-8.2.1-all.zip
+distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-all.zip
 networkTimeout=10000
 validateDistributionUrl=true
 zipStoreBase=GRADLE_USER_HOME
```

## التحقق بعد التطبيق
1. `cd android && ./gradlew clean bundleRelease assembleDebug`
2. `zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk | grep '\.so'` → كل السطور `(OK)`.
3. `unzip -v app-debug.apk | grep '\.so'` → الطريقة `Stored` (غير مضغوطة).
4. `aapt2 dump badging` / البيان المدمج: `android:extractNativeLibs="false"`.
5. على جهاز: «معلومات التطبيق ← التخزين» — يجب أن ينقص حجم التطبيق ~29MB مقارنةً بالبناء الحالي.
6. تشغيل التسميع (sherpa-onnx) و whisper.cpp مرة على جهاز arm64 وأخرى على armeabi-v7a.

## المخاطر
- **متوسطة:** قفزة AGP قد تُظهر تحذيرات/أخطاء في إضافات Capacitor 6 أو cordova (namespace موجود مسبقاً، فالخطر منخفض).
  `rootProject.buildDir` و `aaptOptions` مُهمَلان لكن يعملان في Gradle 8.x.
- حجم ملف الـAPK الخام يكبر (المكتبات غير مضغوطة) لكن **حجم التنزيل من Play لا يتغيّر تقريباً** (Play يضغط عند النقل)،
  والمحصّلة على الجهاز توفير ~29MB.
- أندرويد 5.1 (API 22) يتجاهل extractNativeLibs ويستخرج دائماً — يعمل كما هو اليوم.
- يتطلب Codemagic: لا تغيير (الـwrapper يُنزّل Gradle 8.9 تلقائياً؛ JDK 17 كافٍ).
- إن ظهر تعارض، البديل الأدنى خطراً: البقاء على 8.2.1 مع الضغط (الوضع الحالي) — صحيح وظيفياً.

```diff
--- a/android/app/build.gradle
+++ b/android/app/build.gradle
@@ -5,9 +5,11 @@
     compileSdk rootProject.ext.compileSdkVersion
     // محرّك Whisper الأصلي (whisper.cpp) عبر CMake/NDK — إصدار مثبَّت
     ndkVersion "26.1.10909125"
-    // AGP 8.2 لا يحاذي zip على 16KB للمكتبات غير المضغوطة — نضغطها بدل ترقية AGP
+    // AB-02: AGP ≥ 8.5.1 يحاذي المكتبات غير المضغوطة على 16KB داخل zip — فلا داعي للضغط
+    // (الضغط كان يجبر النظام على استخراج ~29MB من .so إلى ذاكرة الهاتف عند التثبيت).
+    // صراحةً false لأن الافتراضي مع minSdk 22 هو true.
     packaging { jniLibs {
-        useLegacyPackaging true
+        useLegacyPackaging false
         // N64: مكتبتا C/C++ API في sherpa-onnx غير مستعملتين (libsherpa-onnx-jni لا تحتاج إلا libonnxruntime) — ~4.9MB لكل معمارية
         excludes += ['**/libsherpa-onnx-c-api.so', '**/libsherpa-onnx-cxx-api.so']
     } }
--- a/android/build.gradle
+++ b/android/build.gradle
@@ -7,7 +7,7 @@
         mavenCentral()
     }
     dependencies {
-        classpath 'com.android.tools.build:gradle:8.2.1'
+        classpath 'com.android.tools.build:gradle:8.7.3'
         classpath 'com.google.gms:google-services:4.4.0'
 
         // NOTE: Do not place your application dependencies here; they belong
--- a/android/gradle/wrapper/gradle-wrapper.properties
+++ b/android/gradle/wrapper/gradle-wrapper.properties
@@ -1,6 +1,6 @@
 distributionBase=GRADLE_USER_HOME
 distributionPath=wrapper/dists
-distributionUrl=https\://services.gradle.org/distributions/gradle-8.2.1-all.zip
+distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-all.zip
 networkTimeout=10000
 validateDistributionUrl=true
 zipStoreBase=GRADLE_USER_HOME
```

---

## AB-06 — الميكروفون والموقع يُعتبران «إلزاميين» ضمنياً في المتجر

**الحالة:** مقترح — **لم يُطبَّق** (`AndroidManifest.xml` ملف بناء).

> **تنبيه — تفسير المنفِّذ لا وصف المدقّق:** مادة التدقيق لا تصف `AB-06` إطلاقاً؛ المعرّف مذكور مجرداً في
> `audit4/report_tech.md:52` وضمن قائمة `P3` في `audit4/report.json`، ولا يذكره أي ملف في مجلدات المجالات
> (`grep -rn "AB-06" audit4/`). ربطه بـ«الميزات الضمنية للموقع/الميكروفون» **اجتهاد من المنفِّذ** مبني على
> `verify-android-build/probe-badging.txt` (وهو دليل حقيقي لمشكلة حقيقية، لكن قد لا يكون ما قصده المدقّق).
> لذا يجب أن يعدّ صاحب المشروع `AB-06` الأصلي **مفتوحاً** حتى يتأكد من المقصود به.

## المشكلة
`aapt dump badging` (`audit4/verify-android-build/probe-badging.txt:10-13`):
```
uses-feature: name='android.hardware.location'   (implied by ACCESS_*_LOCATION)
uses-feature: name='android.hardware.microphone' (implied by RECORD_AUDIO)
```
Play يستبعد كل جهاز بلا GPS/موقع أو بلا ميكروفون (بعض الأجهزة اللوحية، Android TV/Chromebook بلا ميكروفون…)،
مع أن الموقع اختياري (بحث المدينة يدوياً `#citySearch`) والتسميع ميزة واحدة من التطبيق.
الكاميرا والقرب معلَنان أصلاً `required="false"`؛ هذا يكمل الصورة. الأثر ضئيل على الهواتف لكنه صحيح ومجاني.

## الفرق
```diff
--- a/android/app/src/main/AndroidManifest.xml
+++ b/android/app/src/main/AndroidManifest.xml
@@ -82,6 +82,10 @@
     <!-- Geolocation: prayer times + Qibla direction -->
     <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
     <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
+    <!-- AB-06: الموقع اختياري (يمكن إدخال المدينة يدوياً) — بلا هذا يستنتج المتجر android.hardware.location إلزامياً -->
+    <uses-feature android:name="android.hardware.location" android:required="false" />
+    <uses-feature android:name="android.hardware.location.gps" android:required="false" />
+    <uses-feature android:name="android.hardware.location.network" android:required="false" />
 
     <!-- Camera: متابع الصلاة — وضع الكاميرا (getUserMedia، معالجة على الجهاز فقط) -->
     <uses-permission android:name="android.permission.CAMERA" />
@@ -89,6 +93,8 @@
 
     <!-- Microphone: tasmee (memorization test) speech recognition -->
     <uses-permission android:name="android.permission.RECORD_AUDIO" />
+    <!-- AB-06: التسميع ميزة واحدة من التطبيق — لا نستبعد الأجهزة بلا ميكروفون (يستنتجه المتجر من RECORD_AUDIO) -->
+    <uses-feature android:name="android.hardware.microphone" android:required="false" />
 
     <!-- Proximity sensor: rakaat counter prox mode -->
     <uses-feature android:name="android.hardware.sensor.proximity" android:required="false" />
```

## التحقق
`aapt2 dump badging app-release.apk | grep feature` → يجب أن تظهر `uses-feature-not-required` للأربعة،
ولا `uses-implied-feature` للموقع/الميكروفون. وفي Play Console ← «مستكشف التطبيقات والحِزم ← الأجهزة المدعومة» يزيد العدد.

## المخاطر
منخفضة جداً: لا تغيير في الأذونات ولا السلوك. الكود يتعامل أصلاً مع رفض الإذن/غياب الميكروفون (رسالة في التسميع)
وغياب الموقع (بحث المدينة).

```diff
--- a/android/app/src/main/AndroidManifest.xml
+++ b/android/app/src/main/AndroidManifest.xml
@@ -82,6 +82,10 @@
     <!-- Geolocation: prayer times + Qibla direction -->
     <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
     <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
+    <!-- AB-06: الموقع اختياري (يمكن إدخال المدينة يدوياً) — بلا هذا يستنتج المتجر android.hardware.location إلزامياً -->
+    <uses-feature android:name="android.hardware.location" android:required="false" />
+    <uses-feature android:name="android.hardware.location.gps" android:required="false" />
+    <uses-feature android:name="android.hardware.location.network" android:required="false" />
 
     <!-- Camera: متابع الصلاة — وضع الكاميرا (getUserMedia، معالجة على الجهاز فقط) -->
     <uses-permission android:name="android.permission.CAMERA" />
@@ -89,6 +93,8 @@
 
     <!-- Microphone: tasmee (memorization test) speech recognition -->
     <uses-permission android:name="android.permission.RECORD_AUDIO" />
+    <!-- AB-06: التسميع ميزة واحدة من التطبيق — لا نستبعد الأجهزة بلا ميكروفون (يستنتجه المتجر من RECORD_AUDIO) -->
+    <uses-feature android:name="android.hardware.microphone" android:required="false" />
 
     <!-- Proximity sensor: rakaat counter prox mode -->
     <uses-feature android:name="android.hardware.sensor.proximity" android:required="false" />
```

---

## AB-07 — حماية أصوات الأذان من مقلِّص الموارد — **طُبِّق** (ملف مورد، لا ملف بناء)

أصوات الأذان تُحمَّل بالاسم عبر `getResources().getIdentifier(sound, "raw", pkg)` في
`AdhanPlayerService.java` و `AdhanPlugin.java` (بقائمة بيضاء `AdhanLogic.isBundled`)، فلا يرى
`shrinkResources` أي إشارة `R.raw.adhan_*` ولو فُعِّل مستقبلاً (`minifyEnabled true` + `shrinkResources true`) لحذفها
بصمت → أذان صامت. lint يعدّها الآن «غير مستعملة» (`UnusedResources` ×4).

**المُضاف:** `android/app/src/main/res/raw/keep.xml`
```xml
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@raw/adhan_*,@raw/licenses_adhan" />
```
- لا يوجد ملف keep آخر في `res/` (تحقّق: `grep -rn "tools:keep\|tools:discard\|shrinkMode" android/app/src/main/res` → فقط هذا) فلا تعارض.
- أداة البناء تحذف `keep.xml` من الحزمة فقط عند تفعيل تقليص الموارد (shrinkResources)؛ ومع الإعداد الحالي (معطّل) يُحزَم كمورد raw عادي صغير (بضع مئات من البايتات، كما يظهر `base/res/raw/firebase_common_keep.xml` في AAB الحالي) بلا أثر وظيفي. لا يتعارض مع `isBundled` (اسم `keep` ليس في القائمة البيضاء).
- `licenses_adhan` مُبقى لأنه نصّ الترخيص المرافق للأصوات.
- الأثر اليوم: صفر (التقليص غير مفعّل)؛ وقائي فقط.

---

## AB-09 — خطوط المصحف (48 MB) عبر Play Asset Delivery — وثيقة تصميم

**الحالة:** مقترح تصميم — **لم يُطبَّق** (يمسّ `settings.gradle` و `app/build.gradle` ووحدة Gradle جديدة، وكلها ملفات بناء).
**الأولوية:** منخفضة (P3). لا يوجد حدّ يُتجاوز: حجم الوحدة الأساسية ≈ 69.7 MB أقل بكثير من حدّ Play (200 MB).

## القياس
- `mushaf/fonts/QCF_P001..604.woff2`: **604 ملفاً، 48,018,252 بايت** (`audit4/verify-android-build/metrics-recheck.txt`).
- تُنسخ إلى `www/mushaf/fonts` (`scripts/prepare-www.sh`) فتدخل `assets/public/` في الوحدة الأساسية.
- تُحمَّل بالاسم من `index.html` → `lmInjectFont(p)`: `url('mushaf/fonts/QCF_Pnnn.woff2')`, `font-display:block`.
- أي ≈ 69% من حجم التنزيل لأجل ميزة التسميع بصفحات المصحف فقط.

## الخيارات
| الخيار | يوفّر من التنزيل الأول | العمل دون اتصال | التعقيد | ملاحظة |
|---|---|---|---|---|
| (0) الوضع الحالي | 0 | كامل | — | صحيح وبسيط |
| (1) install-time asset pack | **0** | كامل | منخفض | يُنزَّل مع التثبيت — لا يحلّ شيئاً |
| (2) **fast-follow asset pack** | ~48 MB | بعد دقائق من التثبيت | متوسط | يُنزَّل تلقائياً بعد التثبيت دون فتح التطبيق |
| (3) on-demand asset pack | ~48 MB | بعد طلب المستخدم | متوسط+ | يحتاج واجهة «تنزيل المصحف» |
| (4) تنزيل من الويب وتخزينه | ~48 MB | بعد التنزيل | متوسط | لا يحتاج Play، لكن يعتمد على استضافة خارجية |

**التوصية:** (2) fast-follow — إن قُرّر تخفيض الحجم. وإلا يبقى (0) مقبولاً للإصدار الأول.

## تصميم (2) — الخطوات
1. **وحدة أصول جديدة** `android/mushaf_fonts/build.gradle`:
   ```groovy
   plugins { id 'com.android.asset-pack' }
   assetPack {
       packName = "mushaf_fonts"
       dynamicDelivery { deliveryType = "fast-follow" }
   }
   ```
   والخطوط في `android/mushaf_fonts/src/main/assets/mushaf/fonts/*.woff2`
   (يُعدَّل `scripts/prepare-www.sh` لينسخها هناك **بدل** `www/mushaf/fonts` في بناء أندرويد فقط؛ نسخة الويب كما هي).
2. **settings.gradle:**
   ```diff
    include ':app'
   +include ':mushaf_fonts'
    include ':capacitor-cordova-android-plugins'
   ```
3. **app/build.gradle:**
   ```diff
    android {
        namespace "com.ddcbusiness.noor"
   +    assetPacks = [":mushaf_fonts"]
   ```
   ```diff
    dependencies {
   +    implementation "com.google.android.play:asset-delivery:2.2.2"
   ```
4. **إضافة Capacitor أصلية صغيرة** `MushafPackPlugin.java`:
   - `status()` → `AssetPackManagerFactory.getInstance(ctx).getPackLocation("mushaf_fonts")`؛
     إن وُجد: `{ready:true, path: location.assetsPath() + "/mushaf/fonts"}`، وإلا `getPackStates` → `{ready:false, status, bytes, total}`.
   - `fetch()` → `manager.fetch(["mushaf_fonts"])` (للإعادة إن فشل التنزيل التلقائي) + مستمع تقدّم.
   - على الأجهزة المحمّلة جانبياً (APK بلا Play) يعيد `{ready:false, status:"unavailable"}`.
5. **index.html** (ES5، مع مفاتيح DICT للغات الست):
   - `lmInjectFont(p)`: إن كانت الحزمة جاهزة يُبنى الرابط بـ `Capacitor.convertFileSrc(path + '/QCF_P' + pp + '.woff2')`،
     وإلا يبقى `mushaf/fonts/...` (يعمل على الويب وعلى أي بناء ما زال يضمّ الخطوط).
   - إن لم تكن جاهزة على أندرويد: رسالة «يُنزَّل المصحف… (x%)» مكان الصفحة وزر «إعادة المحاولة»، بدل نص غير مرئي
     (`font-display:block` مع خط مفقود = صفحة فارغة).
6. **الاختبار المحلي:** APK العادي (`assembleDebug`) **لا يضمّ** الحزمة؛ يُختبر بـ
   `bundletool build-apks --local-testing --bundle app-release.aab --output t.apks && bundletool install-apks --apks t.apks`.

## المخاطر
- **متوسطة:** صفحة مصحف فارغة إن لم تكتمل الحزمة ولم تُعالَج الحالة — الخطوة 5 إلزامية قبل الدمج.
- نسخ debug/APK الجانبية تفقد الخطوط ما لم يُبقِ `prepare-www.sh` نسخة احتياطية لبناء debug.
- إضافة مكتبة Play Core: لا أذونات جديدة؛ تبقى ضمن سياسة Play.
- لا يمسّ نصّ القرآن ولا `pages.json` — الخطوط تُنقل كما هي بلا تعديل (sha256 يجب أن يبقى مطابقاً).

---

## AB-05 — مفتوح: لا يمكن معالجته (لا وصف ولا دليل في مادة التدقيق)

**الحالة:** **لم يُعالَج — ما زال مفتوحاً.** لا تغيير في الكود ولا مقترح.

## لماذا
مادة التدقيق `audit4` لا تحتوي أي وصف أو دليل لهذا البند:
- `audit4/report_tech.md:52` يذكر المعرّف `AB-05` مجرداً ضمن قائمة البنود منخفضة الأولوية بلا أي شرح.
- `audit4/report.json` يذكره فقط كمعرّف داخل قائمة `P3` بلا تفاصيل.
- لا يذكره أي ملف في مجلدات المجالات (`audit4/android-build`، `audit4/verify-android-build`، …) — تحقّق: `grep -rn "AB-05" audit4/`.

لذلك لا يمكن تحديد المشكلة المقصودة ولا إثبات إصلاحها دون تخمين، ولم نُخمّن.

## المطلوب
على صاحب المشروع أو المدقّق تقديم وصف البند ودليله (الملف/السطر/خطوات إعادة الإنتاج) ليُعالَج في جولة لاحقة.
