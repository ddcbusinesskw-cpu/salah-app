# فحص جاهزية البناء الكامل — بعد حذف نظام التسميع القديم (١٥٤٥ سطراً)

الغرض: التأكّد أن التطبيق **كله** يُبنى ويعمل سليماً بعد حذف المحرّك القديم
وإصلاح التسميع الأصلي — لا التسميع فقط.

## ١) اكتمال البناء (JS)

| الفحص | النتيجة |
|-------|---------|
| `node --check` للكتل السبع المضمّنة | ✅ ٧/٧ سليمة |
| تشغيل فعلي (web + WebView محاكاة) + `boot()` + `go()` لـ٧ شاشات + inits | ✅ لا `ReferenceError`/`TypeError` جديد (14 خطأ `classList` سابق = قيد المحاكاة `getElementById→null`، موجود قبل الحذف) |
| مراجع متبقية لأي اسم محذوف (٦٧ دالة) | ✅ صفر |
| معالجات HTML (`onclick=…`) تشير لدالة محذوفة | ✅ صفر |

## ٢) الطبقة الأصلية والـCI

| المنطقة | النتيجة |
|---------|---------|
| WhisperNative (JNI `whisper_jni.c`) | ✅ سليم، غير مُعدَّل |
| `CMakeLists.txt` | ✅ FetchContent whisper.cpp `v1.7.4` ساكن، يربط `whisper log`، `GGML_NATIVE=OFF` |
| `WhisperNativePlugin.java` | ✅ أقواس متوازنة، إذن `RECORD_AUDIO` عبر `@Permission` + `requestPermissionForAlias` |
| `build.gradle`: NDK/CMake | ✅ `ndkVersion 26.1.10909125` + `cmake 3.22.1` (يطابقان تثبيت codemagic)، `abiFilters arm64-v8a, armeabi-v7a`، `-DANDROID_ARM_NEON=ON` |
| `versionCode` ديناميكي | ✅ من `PROJECT_BUILD_NUMBER`/`BUILD_NUMBER`، fallback 1 |
| التوقيع | ✅ `signingConfigs.release` من `CM_KEYSTORE_*` (مجموعة `noor_keystore`)، release مُوقَّع |
| `codemagic.yaml` خطوة google-services | ✅ لا تفشل (سرّ ← ملف مُلتزَم ← تحذير) |
| الخطوط (48MB · 604 woff2) تُنسخ | ✅ `prepare-www.sh` → `www/mushaf`، عبر `npm run prepare:www` قبل `cap sync` |
| النموذج الأصلي يُبنى في CI | ✅ خطوة تثبيت NDK/CMake ثم `gradlew bundleRelease assembleRelease` |

## ٣) بقية الميزات (لم يكسرها الحذف)

| المنطقة | الحكم |
|---------|-------|
| المواقيت/الأذان (`renderTimesScreen`) | ✅ سليم |
| القبلة (`setQibla`/`startQibla`) | ✅ سليم |
| القرآن/المصحف/المشغّل (`initQuran`، `mushafFull`) | ✅ سليم |
| الأذكار (`initAdhkar`) | ✅ سليم |
| التسبيح (`renderTasbih`/`tasbihReset`) | ✅ سليم |
| تتبّع الصلاة والعدّاد (`_st*`، `_srProbe`/`_srHealthProbe`) | ✅ سليم — فحص التعرّف الأصلي مُبقىً ونظيف |
| الرقية/الأسماء/الأدعية/الحديث (`initRuqya`/`initFadail`/`initAdiya`) | ✅ سليم |
| الإنجازات/المشاركة | ✅ سليم |
| الختمة (`loadKhatmah`/`startKhatmah`) | ✅ سليم |
| الحساب/المزامنة (Firebase) | ✅ سليم |
| الإعدادات/اللغات، الزر العائم، المساعدة، التهيئة | ✅ سليم |
| حزمة الذهب/الفحص الذاتي (WASM المتبقّي) | ✅ سليم — `_w2Init` كسول، يعمل عند التشغيل الصريح فقط (زر «تشغيل الحزمة») أو تهيئة الويب، ولا يُشغَّل على مسار المستخدم الأصلي |

## ٤) الموارد وJSON والمفاتيح

| الفحص | النتيجة |
|-------|---------|
| `mushaf/pages.json` (`lmLoadPages`) | ✅ `fetch→json()` داخل `try/catch` مع رسالة ودّية عند الفشل |
| مفاتيح LM الجديدة | ✅ الحالة في الذاكرة فقط (`LM.page/cursor/...`) — لا مفاتيح localStorage يتيمة |
| توازن الموارد | ✅ الالتقاط الجديد أصلي (لا `AudioContext`/`MediaRecorder` في WebView تتسرّب)؛ `_mem2PlayPcm` يغلق سياقه عند `onended` |
| WASM على مسار المستخدم الأصلي | ✅ صفر — التهيئة الأصلية تنزّل GGUF (~43MB) عبر WhisperNative فقط |

## الحكم النهائي

> **البناء يكتمل كاملاً وجاهز من commit `d8d3ae6`: نعم**
> — بسبب: كل الكتل السبع سليمة نحوياً وتنفيذياً؛ صفر مرجع/معالج مكسور لأي
> دالة محذوفة؛ الطبقة الأصلية (CMake/NDK/JNI + إذن المايك) وcodemagic
> (توقيع + google-services + خطوط 48MB + بناء النموذج + versionCode
> ديناميكي) مكتملة؛ وكل الميزات الأخرى تحتفظ بنقاط دخولها سليمةً.
