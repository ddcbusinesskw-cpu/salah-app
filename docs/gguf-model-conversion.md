# تحويل موديل ترتيل tiny إلى GGUF للمحرّك الأصلي (whisper.cpp)

المحرّك الأصلي `WhisperNative` يحمّل موديل GGUF من
`Oa-4/whisper-tiny-ar-quran-gguf`. هذه وصفة إنتاجه — تُنفَّذ في بيئتك/Colab
(شبكة الوكيل تحجب HuggingFace).

## ١) البيئة
```bash
git clone --depth 1 https://github.com/ggerganov/whisper.cpp
pip install -U transformers torch huggingface_hub
huggingface-cli login   # توكن write على حساب Oa-4
```

## ٢) تنزيل موديل ترتيل (صيغة HF)
```bash
huggingface-cli download Salama1429/tarteel-ai-whisper-tiny-ar-quran \
  --local-dir tiny-ar-hf
```

## ٣) التحويل إلى ggml (السكربت الرسمي)
```bash
cd whisper.cpp
# يحتاج مجلد whisper الأصلي من openai لملفات الـmel filters/tokenizer الأساسية:
git clone --depth 1 https://github.com/openai/whisper openai-whisper
python models/convert-h5-to-ggml.py ../tiny-ar-hf ./openai-whisper .
# الناتج: ggml-model.bin (fp32 ~150MB أو f16 حسب المصدر)
```

## ٤) التكميم (q8_0 موصى به للدقة، q5_1 أصغر)
```bash
cmake -B build && cmake --build build -j --target quantize
./build/bin/quantize ggml-model.bin ggml-tiny-ar-quran-q8_0.bin q8_0
# الناتج ~43MB (q8_0) أو ~32MB (q5_1)
```

## ٥) تحقّق محلي قبل الرفع
```bash
cmake --build build -j --target whisper-cli
./build/bin/whisper-cli -m ggml-tiny-ar-quran-q8_0.bin -l ar -f samples/jfk.wav
# جرّب ملف تلاوة wav 16kHz mono وتأكد من نص عربي صحيح
```

## ٦) الرفع
```bash
huggingface-cli repo create whisper-tiny-ar-quran-gguf --type model
huggingface-cli upload Oa-4/whisper-tiny-ar-quran-gguf ggml-tiny-ar-quran-q8_0.bin
```

> **الاسم مهم**: التطبيق ينزّل حرفياً
> `https://huggingface.co/Oa-4/whisper-tiny-ar-quran-gguf/resolve/main/ggml-tiny-ar-quran-q8_0.bin`
> (الثابتان `WN_GGUF_URL`/`WN_GGUF_FILE` في index.html).

## ٧) التفعيل
لا تغيير كود لازم: عند فتح شاشة التسميع على native، ينزّل التطبيق الملف مرة
(بتقدّم ومهلة) عبر Filesystem، يحمّله في `WhisperNative`، وتتحوّل نوافذ
الشلال للمحرّك الأصلي تلقائياً. تحقّق من «تفاصيل تقنية»: «المحرّك: أصلي
(whisper.cpp، N خيوط)» وأزمنة النوافذ، ومن حزمة الذهب (تعمل على الأصلي
وتطابق أحكام WASM).
