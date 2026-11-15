# دليل بناء ونشر تطبيق نور — Android و iOS

## المتطلبات المسبقة (على جهازك المحلي)

| أداة | الإصدار | ملاحظة |
|------|---------|--------|
| Node.js | ≥ 18 | `node --version` |
| npm | ≥ 9 | يأتي مع Node |
| Android Studio | Hedgehog+ | لـ Android فقط |
| JDK | 17 | `java --version` |
| Xcode | 15+ | macOS فقط، لـ iOS |
| CocoaPods | أحدث | `sudo gem install cocoapods` |

---

## أولاً — إعداد البيئة المحلية (مرة واحدة)

```bash
git clone https://github.com/ddcbusinesskw-cpu/salah-app
cd salah-app
git checkout feat/capacitor-native   # أو main بعد الدمج
npm install
```

### ملف محرّك التسميع (sherpa-onnx AAR) — خطوة إلزامية قبل أي بناء محلي

ملف `sherpa-onnx-1.13.8.aar` **غير محفوظ في المستودع** (`.gitignore` يستثني `*.aar`)، وخادم Codemagic
يُنزّله في خطوة «Fetch sherpa-onnx AAR» قبل البناء. على جهازك يجب تنزيله يدوياً **مرة واحدة**،
وإلا يفشل تجميع `QuranAsrPlugin.java` (لا يجد حزمة `com.k2fsa.sherpa.onnx`):

```bash
# من جذر المستودع — نفس الرابط والبصمة المثبَّتين في codemagic.yaml
mkdir -p android/app/libs
curl -fL --retry 3 -o android/app/libs/sherpa-onnx-1.13.8.aar.tmp \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
# تحقّق من البصمة (على لينكس: sha256sum بدل shasum -a 256)
shasum -a 256 android/app/libs/sherpa-onnx-1.13.8.aar.tmp
# يجب أن تساوي: 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96
mv android/app/libs/sherpa-onnx-1.13.8.aar.tmp android/app/libs/sherpa-onnx-1.13.8.aar
```

> إن غيّرت إصدار sherpa-onnx فغيّر الرابط والبصمة في `codemagic.yaml` وهنا معاً.
> يلزم أيضاً NDK `26.1.10909125` و CMake `3.22.1` (لمكتبة whisper.cpp) — ثبّتهما من SDK Manager في Android Studio.

---

## ثانياً — بناء Android

### الطريقة 1: Android Studio (موصى به)

```bash
# 1. اجمع ملفات الويب وزامن المشروع
npm run cap:android
# يفتح Android Studio تلقائياً
```

داخل Android Studio:
1. انتظر حتى ينتهي Gradle sync
2. **Build → Generate Signed Bundle/APK**
3. اختر **Android App Bundle (.aab)** — مطلوب لـ Play Store
4. أنشئ Keystore جديداً أو استخدم الموجود:
   ```
   Key store path:  ~/noor-release.jks
   Alias:           noor
   Passwords:       (احفظهما في مكان آمن)
   ```
5. **Build Type: release** → Finish
6. الملف الناتج: `android/app/release/app-release.aab`

### توقيع نسخة release محلياً (متغيرات `CM_KEYSTORE_*`)

`android/app/build.gradle` يقرأ مفتاح التوقيع **من متغيرات البيئة فقط** (التي يضبطها Codemagic تلقائياً):
`CM_KEYSTORE_PATH` و `CM_KEYSTORE_PASSWORD` و `CM_KEY_ALIAS` و `CM_KEY_PASSWORD`.
إن شغّلت بناء release محلياً **دون** ضبطها يفشل البناء برسالة غامضة — السبب ليس خللاً في الكود بل غياب المفتاح:
- `./gradlew bundleRelease` يفشل في مهمة `:app:signReleaseBundle` برسالة `java.lang.NullPointerException (no error message)`.
- `./gradlew assembleRelease` يفشل أيضاً في مهمة `:app:packageRelease` برسالة
  `SigningConfig "release" is missing required property "storeFile"`، **ولا يُنتَج** أي ملف `app-release-unsigned.apk`
  (لذلك خطوات التوقيع اليدوي في «الطريقة 2» أدناه لا تعمل حالياً دون ضبط المتغيرات).

الحلول (اختر واحداً):
- **للتجربة على الهاتف:** ابنِ نسخة debug بدل release: `./gradlew assembleDebug` (لا تحتاج مفتاحاً).
- **لنسخة release موقّعة محلياً:** اضبط المتغيرات الأربعة في الطرفية نفسها قبل البناء (لا تحفظ كلمات المرور في ملفات المستودع):
  ```bash
  export CM_KEYSTORE_PATH=~/noor-release.jks
  export CM_KEY_ALIAS=noor
  read -rs -p "Keystore password: " CM_KEYSTORE_PASSWORD; echo; export CM_KEYSTORE_PASSWORD
  read -rs -p "Key password: " CM_KEY_PASSWORD; echo; export CM_KEY_PASSWORD
  ./gradlew bundleRelease
  ```
- **أو** استعمل معالج Android Studio (الطريقة 1) فهو يوقّع بنفسه.

> يوجد مقترح (لم يُطبَّق بعد) بحارس في `build.gradle` لا يربط إعداد التوقيع إلا عند وجود هذه المتغيرات؛ بعد تطبيقه يُنتج `assembleRelease` ملف `app-release-unsigned.apk` يمكن توقيعه يدوياً بـ `zipalign` ثم `apksigner` كما في «الطريقة 2» (لا بـ `jarsigner`: انظر التنبيه هناك).

### الطريقة 2: سطر الأوامر

```bash
npm run prepare:www
npx cap sync android
cd android

# بناء AAB (للمتجر)
./gradlew bundleRelease

# أو بناء APK (للاختبار)
./gradlew assembleRelease
```

> **تنبيه:** الأمران أعلاه يتطلبان ضبط متغيرات `CM_KEYSTORE_*` الأربعة أولاً (انظر القسم السابق «توقيع نسخة release محلياً»)،
> وإلا يفشلان. وعند ضبطها يكون الناتج موقَّعاً مباشرة (`app-release.apk` / `app-release.aab`) فلا حاجة للخطوات التالية.

**توقيع الـ APK يدوياً** (لا يصلح إلا بعد تطبيق مقترح الحارس `AB-missed` في `build.gradle`؛ بدونه لا يُنتَج `app-release-unsigned.apk`):

> **مهم:** لا تستعمل `jarsigner`. التطبيق يستهدف `targetSdkVersion 36` (`android/variables.gradle`)، و`jarsigner` يكتب توقيع v1 (JAR) فقط؛
> وAndroid 11 فما فوق يرفض تثبيت APK يستهدف SDK 30+ بتوقيع v1 وحده (`Targeting R+ (version 30 and above) requires the resulting APK to be signed with APK Signature Scheme v2 or higher` /
> `INSTALL_PARSE_FAILED_NO_CERTIFICATES`). كما أن `zipalign` يجب أن يسبق التوقيع بـ `apksigner` (تشغيله بعد التوقيع يُبطل توقيع v2).
> الأداتان `zipalign` و`apksigner` موجودتان في `$ANDROID_HOME/build-tools/<الإصدار>/`.

```bash
# 1) المحاذاة أولاً (‎-p تحاذي مكتبات .so غير المضغوطة على حدود الصفحة)
zipalign -p -f 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app/build/outputs/apk/release/aligned.apk

# 2) ثم التوقيع بـ apksigner (يكتب توقيعات v1+v2+v3 تلقائياً؛ يسأل عن كلمة المرور تفاعلياً)
apksigner sign --ks ~/noor-release.jks --ks-key-alias noor \
  --out app/build/outputs/apk/release/noor-release.apk \
  app/build/outputs/apk/release/aligned.apk

# 3) تحقّق: يجب أن يظهر "Verified using v2 scheme (APK Signature Scheme v2): true"
apksigner verify --verbose app/build/outputs/apk/release/noor-release.apk
```

### رفع على Google Play Store
1. افتح [Google Play Console](https://play.google.com/console)
2. أنشئ تطبيقاً جديداً: `com.ddcbusiness.noor`
3. ارفع الـ AAB في **Internal Testing** أولاً
4. اكمل بيانات المتجر (وصف، لقطات شاشة، تصنيف)
5. انشر على **Closed Testing** ثم **Production**

---

## ثالثاً — بناء iOS

### الطريقة 1: Mac محلي + Xcode

```bash
npm run cap:ios
# يفتح Xcode تلقائياً
```

داخل Xcode:
1. **Product → Destination → Any iOS Device**
2. اختر Team: أضف Apple Developer Account
3. Bundle ID: `com.ddcbusiness.noor`
4. **Product → Archive**
5. في Organizer: **Distribute App → App Store Connect**

### الطريقة 2: Codemagic (بدون Mac ✅)

> الأنسب إن لم يكن لديك Mac.

#### إعداد Codemagic:

1. اذهب إلى [codemagic.io](https://codemagic.io) → سجّل بحساب GitHub
2. أضف الريبو: `ddcbusinesskw-cpu/salah-app`
3. اختر **iOS App** → **Capacitor**
4. في إعدادات Build:
   ```yaml
   # codemagic.yaml (موجود في الريبو — أنشئه إن لم يكن)
   workflows:
     ios-release:
       name: iOS Release
       instance_type: mac_mini_m2
       environment:
         ios_signing:
           distribution_type: app_store
           bundle_identifier: com.ddcbusiness.noor
       scripts:
         - name: Install dependencies
           script: npm install
         - name: Prepare www
           script: npm run prepare:www
         - name: Sync Capacitor
           script: npx cap sync ios
         - name: Install CocoaPods
           script: cd ios/App && pod install
         - name: Build iOS
           script: |
             xcode-project build-ipa \
               --workspace ios/App/App.xcworkspace \
               --scheme App
       artifacts:
         - build/ios/ipa/*.ipa
       publishing:
         app_store_connect:
           api_key: $APP_STORE_CONNECT_PRIVATE_KEY
           key_id: $APP_STORE_CONNECT_KEY_IDENTIFIER
           issuer_id: $APP_STORE_CONNECT_ISSUER_ID
   ```

5. في **Environment variables** بـ Codemagic:
   - `APP_STORE_CONNECT_PRIVATE_KEY` — مفتاح API من App Store Connect
   - `APP_STORE_CONNECT_KEY_IDENTIFIER` — Key ID
   - `APP_STORE_CONNECT_ISSUER_ID` — Issuer ID
   - `CERTIFICATE_PRIVATE_KEY` — مفتاح التوقيع
   - `PROVISIONING_PROFILE` — Provisioning Profile

6. اشغّل الـ workflow → الـ IPA يُرفع تلقائياً على TestFlight

---

## رابعاً — TestFlight (اختبار iOS)

1. في [App Store Connect](https://appstoreconnect.apple.com):
   - **My Apps → نور → TestFlight**
2. بعد رفع Build من Codemagic (يستغرق ~10 دقائق للمعالجة):
   - **Internal Testers**: أضف البريد الإلكتروني → يصل دعوة فورية
   - **External Testers**: يحتاج مراجعة Apple (24-48 ساعة)
3. على iPhone:
   ```
   App Store → بحث عن "TestFlight" → تثبيت
   افتح TestFlight → Redeem Code أو قبول الدعوة
   ```

---

## خامساً — سير العمل اليومي (بعد تعديل index.html)

```bash
# بعد كل تعديل على الويب:
npm run cap:sync         # ينسخ www/ ويزامن المنصتين

# لاختبار Android مباشرة على الهاتف:
npx cap run android      # يحتاج Android Studio مثبتاً + جهاز موصول

# لاختبار iOS (على Mac):
npx cap run ios
```

---

## ملاحظات مهمة

### نسخة الويب (GitHub Pages)
- `index.html` في الجذر **لا يُمس أبداً** في عملية البناء
- `www/` يُولَّد ويُمحى في كل `cap sync` — لا يُرفع على Git
- GitHub Pages تستمر تخدم من الجذر بلا تغيير

### الإشعارات (Local Notifications)
بعد دمج الـ PR، اربط `scheduleTodayPrayers()` بحساب المواقيت في `index.html`:
```javascript
// في index.html — بعد حساب مواقيت الصلاة
if (window.scheduleTodayPrayers) {
  window.scheduleTodayPrayers({
    fajr:    new Date(/* وقت الفجر */),
    dhuhr:   new Date(/* وقت الظهر */),
    asr:     new Date(/* وقت العصر */),
    maghrib: new Date(/* وقت المغرب */),
    isha:    new Date(/* وقت العشاء */)
  });
}
```

### حسّاس القُرب
- **Android**: يعمل تلقائياً عبر WebView بدون تغيير
- **iOS**: لا يدعم ProximitySensor في WebView — `rakProxOpen()` ستتراجع لـ touch mode تلقائياً

### كاميرا الركعات
- تعمل مباشرةً في WebView مع الصلاحيات المُضافة في Manifest/Info.plist

---

## ملف `codemagic.yaml`

أنشئ هذا الملف في جذر الريبو لبناء CI/CD كامل:

```yaml
workflows:
  android-release:
    name: Android Release
    instance_type: linux_x2
    environment:
      android_signing:
        - noor_keystore
      vars:
        PACKAGE_NAME: com.ddcbusiness.noor
    scripts:
      - npm install
      - npm run prepare:www
      - npx cap sync android
      - cd android && ./gradlew bundleRelease
    artifacts:
      - android/app/build/outputs/bundle/release/*.aab
    publishing:
      google_play:
        credentials: $GCLOUD_SERVICE_ACCOUNT_CREDENTIALS
        track: internal

  ios-release:
    name: iOS Release
    instance_type: mac_mini_m2
    environment:
      ios_signing:
        distribution_type: app_store
        bundle_identifier: com.ddcbusiness.noor
    scripts:
      - npm install
      - npm run prepare:www
      - npx cap sync ios
      - cd ios/App && pod install
      - xcode-project build-ipa --workspace App.xcworkspace --scheme App
    artifacts:
      - build/ios/ipa/*.ipa
    publishing:
      app_store_connect:
        api_key: $APP_STORE_CONNECT_PRIVATE_KEY
        key_id: $APP_STORE_CONNECT_KEY_IDENTIFIER
        issuer_id: $APP_STORE_CONNECT_ISSUER_ID
        submit_to_testflight: true
```
