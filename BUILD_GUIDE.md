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

**توقيع الـ APK يدوياً:**
```bash
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore ~/noor-release.jks \
  app/build/outputs/apk/release/app-release-unsigned.apk noor

zipalign -v 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app/build/outputs/apk/release/noor-release.apk
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
