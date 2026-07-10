# خريطة نظام التسميع (المصحف الحي) — كيف يعمل وما يستخدم

توثيق المسار الكامل خطوة بخطوة لنظام التسميع الأصلي (معمارية ترتيل):
التقاط أصلي عبر `AudioRecord` → `whisper.cpp` بنوافذ منزلقة → محاذاة حيّة
على المصحف. لكل مرحلة: ما تستخدمه + الحكم.

نظرة سريعة على التدفّق:

```
زر 🎙 → lmToggleRec → lmStart
   → إذن RECORD_AUDIO (checkPermissions/requestPermissions)
   → WN.ready؟ (GGUF محمَّل) وإلا «⏳ يُحمّل المحرّك…»
   → P.startStream({window_sec:2.5, slide_sec:1.0, prompt: سياق قصير})
        Java: AudioRecord 16kHz mono PCM16 → نوافذ منزلقة
              → nativeTranscribe (int16→float32 /32768) → whisper_full
              → حدث 'partial' {text, samples, peak, rms, ms, prompt_len}
   → lmOnPartial → _lmNorm → lmAdvance/_lmWordMatch → تقدّم المؤشر
        → تلوين المصحف (ok/active glow + auto-scroll) + setPrompt(سياق جديد)
   → lmStop → stopStream → آخر نافذة + PCM كامل → lmFinish (أحكام ✅/⬜)
```

---

## ١) نقطة الدخول

| ما يجري | الدالة/المصدر | الحكم |
|---------|--------------|-------|
| فتح شاشة التسميع | `go('memorize')` → `initMemorize()` | ✅ |
| التهيئة: تحميل `pages.json`، ملء منتقي السور (1–114)، عرض صفحة 1، `_wnEnsure()` | `initMemorize` → `lmLoadPages`, `lmRender`, `_wnEnsure` | ✅ |
| زر التسجيل | `onclick="lmToggleRec()"` → `LM.rec? lmStop() : lmStart()` | ✅ |

## ٢) الأذونات (RECORD_AUDIO)

| ما يجري | الموقع | الحكم |
|---------|--------|-------|
| طلب الإذن من JS قبل البدء | `lmStart`: `P.checkPermissions()` ثم `P.requestPermissions()` إن لم يُمنح | ✅ |
| خطّ دفاع أصلي | `WhisperNativePlugin.startListening`: `getPermissionState("microphone")!=GRANTED` → `requestPermissionForAlias("microphone", call, "micPermCallback")` | ✅ |
| إعلان الإذن | `@Permission(strings={Manifest.permission.RECORD_AUDIO}, alias="microphone")` + `AndroidManifest.xml` سطر 47 | ✅ |
| معالجة الرفض | `micPermCallback` يرفض برسالة «إذن مرفوض — فعّله من الإعدادات»؛ JS يعرضها ويوقف بلطف | ✅ |

## ٣) الموديل (GGUF عبر WhisperNative)

| ما يجري | التفصيل | الحكم |
|---------|---------|-------|
| المصدر | `WN_GGUF_URL = huggingface.co/Oa-4/whisper-tiny-ar-quran-gguf/…/ggml-tiny-ar-quran-q8_0.bin` (~43MB، q8_0) | ✅ |
| التهيئة مرة واحدة | `_wnEnsure`: `isAvailable` → `FS.stat` (>10MB؟ حمّل) → وإلا `_wnDownload` | ✅ |
| التنزيل الشفّاف | `_wnDownload`: `fetch` يكشف رمز HTTP والحجم؛ يرفض <10MB (صفحة خطأ)؛ يحفظ عبر `Filesystem.writeFile` (DATA) | ✅ |
| التحميل في المحرّك | `P.loadModel({path})` → `nativeInit` → `whisper_init_from_file_with_params` | ✅ |
| الجاهزية | `WN.ready=true` بعد التحميل؛ `lmStart` لا يبدأ قبلها (يعرض المرحلة/الحجم) | ✅ |
| WASM | لا يُشغَّل في مسار المستخدم — `_w2Init` كسول لحزمة الذهب/فحص الويب فقط | ✅ |

## ٤) التقاط الصوت (AudioRecord الأصلي)

| المعامل | القيمة | الحكم |
|---------|--------|-------|
| المصدر | `MediaRecorder.AudioSource.VOICE_RECOGNITION` | ✅ |
| المعدّل/القنوات/العمق | `16000Hz` · `CHANNEL_IN_MONO` · `ENCODING_PCM_16BIT` | ✅ |
| حجم buffer | `max(getMinBufferSize, SR) × 2` (≥1ث) | ✅ |
| القراءة | خيط `MAX_PRIORITY` يقرأ كتل `2048` عيّنة، يراكمها في `buf` | ✅ |
| البثّ المتواصل | `startStream` = `startListening`؛ لا يغادر الصوت الطبقة الأصلية حتى يصير نصاً | ✅ |
| التمرير للمحرّك | نافذة `[nowTotal-winSamples, nowTotal)` كـ`short[]` → `nativeTranscribe` | ✅ |

## ٥) المحرّك (whisper.cpp)

| المعامل | القيمة | الحكم |
|---------|--------|-------|
| صيغة الإدخال | `int16 → float32` بالقسمة على `32768.0f` قبل `whisper_full` (`whisper_jni.c`) — مطبَّع [-1,1] | ✅ |
| النوافذ المنزلقة | الحجم `2.5s` (`winSamples`)، الانزلاق `1.0s` (`slideSamples`) — نافذة جديدة كل ~1ث | ✅ |
| الخيوط | `threadCount()` = `clamp(1..4, availableProcessors)` | ✅ |
| اللغة/العيّنة | `lang="ar"`، `WHISPER_SAMPLING_GREEDY`، `no_timestamps`، `suppress_blank` | ✅ |
| الprompt | **سياق قصير ~12 كلمة حول المؤشر** (`_lmPromptAt`)، يُحدَّث عبر `setPrompt` مع التقدّم | ✅ (كان: الصفحة كاملة → شظية — أُصلح) |
| مقاييس النافذة | `samples/peak/rms/prompt_len` تُرسَل مع كل حدث `partial` للتشخيص | ✅ |

## ٦) المعالجة والمحاذاة الحيّة

| ما يجري | الدالة | الحكم |
|---------|--------|-------|
| استقبال النص الجزئي | `lmOnPartial(ev)` — يراكم النوافذ في `LM._wins`، يحفظ `LM._heard` | ✅ |
| التطبيع | `_lmNorm` → `_normRec` (يصحّح تحريفات whisper: الله/الهمزة/خلط صوتي) | ✅ |
| المحاذاة | `lmAdvance(heard)`: لكل كلمة مسموعة يبحث في نافذة `[cursor, cursor+4)` | ✅ |
| تطابق الكلمة | `_lmWordMatch`: تطابق تام، أو مسافة تحرير `_memED ≤ 1` للكلمات ≥4 أحرف | ✅ |
| تقدّم المؤشر | يتقدّم لآخر كلمة مطابَقة؛ المتخطّاة تُعرض ✅ مؤقتاً؛ رتيب (لا يرجع) | ✅ |

## ٧) الواجهة (المصحف الحي)

| ما يجري | التفصيل | الحكم |
|---------|---------|-------|
| بيانات الصفحة | `LM.pages = pages.json[604]`؛ كل صفحة قائمة `{s,a,g}` | ✅ |
| خطوط QCF | `lmInjectFont(p)`: `@font-face` ديناميكي `QCF_P###` من `mushaf/fonts/QCF_P###.woff2` | ✅ |
| بناء الكلمات | `lmRender`: مقاطع `<span class="qw" id="lmg#">` لكل glyph؛ نصّ الكلمات من `QURAN_EMBED.surahs[s][a-1]` | ✅ |
| ربط كلمة↔glyph | تناسبي (`floor(j*G/W)`) حين يختلف عدد الكلمات عن الرموز | ✅ (تقريب مقصود؛ عدد رموز QCF ≠ عدد الكلمات) |
| الظل المتحرّك | `_lmActivate`: `.qw.active` + `@keyframes qwglow` | ✅ |
| auto-scroll | `el.scrollIntoView({behavior:'smooth', block:'center'})` | ✅ |
| إخفاء/إظهار | `lmToggleHide` → `.lm-hidden` يُخفي غير المُلوَّنة | ✅ |
| الأحكام الثلاثية | ✅ ما تجاوزه المؤشر (`ok`) · ⬜ ما لم يُسمع (`miss`، لا يُحسب خطأً) · توهّج الكلمة الحالية؛ الحكم النهائي في `lmFinish` | ✅ |

## ٨) التشخيص وحزمة الذهب

| ما يجري | الدالة | الحكم |
|---------|--------|-------|
| تفاصيل تقنية | `_lmDiagText/_lmDiagHtml`: المحرّك، WN.ready، سياق الprompt، لكل نافذة عيّنات/ذروة/rms/طول prompt/نصّها، تنزيل GGUF (`_wnDownDiag`)، سبب فشل الالتقاط | ✅ |
| عزل الprompt | زر «اختبار بلا prompt» (`_lmTogglePrompt`) يشغّل whisper خاماً للمقارنة | ✅ |
| استماع للمسجَّل | `_mem2PlayLast` على PCM الكامل العائد من `stopStream` | ✅ |
| حزمة الذهب | `_gsRun`: worker مستقل (نموذج q8) أو `_wnTranscribe` الأصلي؛ الحكم عبر `_gsVerdict` (`_ltAdvance`/`_ltWordMatch`) — لا تمسّ مسار المستخدم | ✅ |

---

## الملخّص

المسار كامل وسليم من الطرف للطرف: التقاط أصلي نظيف (`AudioRecord`
16kHz mono PCM16) → صيغة float32 صحيحة → whisper.cpp بنوافذ منزلقة
2.5ث/1ث مع **prompt قصير حول المؤشر** (بدل الصفحة كاملة التي كانت تكتم
المخرَج) → محاذاة حيّة بمسافة تحرير → مصحف حيّ بخطوط QCF وتوهّج وauto-scroll
وأحكام ثلاثية. الأذونات والموديل (GGUF/Oa-4) والتشخيص وحزمة الذهب كلها
سليمة. التحقّق النهائي للطبقة الأصلية يكون على جهاز فعلي.

ابنِ من commit `ee69488`
