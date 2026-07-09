# تحويل الموديل الصغير للمرحلة السريعة (tiny)

الشلال ثنائي الموديل في التسميع يحمّل موديلاً صغيراً (`TINY_MODEL` في `index.html`)
للمرحلة السريعة، ويسقط بأمان إلى `base` وحده إن لم يكن منشوراً بعد. هذه هي وصفة
إنتاجه ورفعه — **نفس pipeline الذي أنتج `Oa-4/whisper-base-ar-quran-ONNX-tjs-v2`**.

> ملاحظة: هذه الخطوة تحتاج تنزيل الموديل من HuggingFace وتشغيل `optimum` ورفعاً
> بحسابك — تُنفَّذ في بيئتك المحلية/Colab، لا داخل بيئة الوكيل (الشبكة محجوبة هناك).

## ١) البيئة
```bash
pip install -U "optimum[onnxruntime]" "transformers>=4.40" "onnx" "onnxruntime" "huggingface_hub"
huggingface-cli login   # توكن بصلاحية write على حساب Oa-4
```

## ٢) التصدير إلى ONNX (transformers.js layout)
```bash
optimum-cli export onnx \
  --model Salama1429/tarteel-ai-whisper-tiny-ar-quran \
  --task automatic-speech-recognition-with-past \
  --opset 14 \
  tiny-ar-quran-onnx/
```
هذا ينتج داخل `tiny-ar-quran-onnx/onnx/`:
`encoder_model.onnx`، `decoder_model.onnx`، `decoder_model_merged.onnx`،
`decoder_with_past_model.onnx` + `config.json`، `tokenizer.json`،
`preprocessor_config.json`، `generation_config.json`.

## ٣) التكميم q8 لكل مكوّنات whisper (نفس ما فُعل مع base)
transformers.js يتوقّع لاحقة `_quantized`. كمّم الأربعة:
```python
from onnxruntime.quantization import quantize_dynamic, QuantType
import glob, os
d = "tiny-ar-quran-onnx/onnx"
for f in ["encoder_model","decoder_model","decoder_model_merged","decoder_with_past_model"]:
    src = os.path.join(d, f+".onnx")
    if os.path.exists(src):
        quantize_dynamic(src, os.path.join(d, f+"_quantized.onnx"),
                         weight_type=QuantType.QInt8, per_channel=False, reduce_range=False)
        print("quantized", f)
```
> مهم: **أبقِ ملفات q8 (`_quantized`) واحذف/لا ترفع نسخ fp32** لتفادي تضخّم
> التنزيل — الكود يطلب `_quantized` صراحةً لكل المكوّنات الأربعة.

## ٤) التحقق محلياً قبل الرفع (نفس worker التطبيق)
شغّل transformers.js 3.5.0 على `tiny-ar-quran-onnx/` محلياً (Node أو صفحة اختبار)
مع `dtype:{encoder_model:"q8",decoder_model_merged:"q8",decoder_model:"q8",decoder_with_past_model:"q8"}`
وتأكّد أن مقطع تلاوة قصير يُفرَّغ نصاً عربياً صحيحاً.

## ٥) الرفع
```bash
huggingface-cli repo create whisper-tiny-ar-quran-ONNX-tjs --type model
huggingface-cli upload Oa-4/whisper-tiny-ar-quran-ONNX-tjs tiny-ar-quran-onnx/ .
```
بنية المستودع النهائية يجب أن تطابق `Oa-4/whisper-base-ar-quran-ONNX-tjs-v2`
(نفس أسماء الملفات في `onnx/` + ملفات الضبط في الجذر).

## ٦) التفعيل في التطبيق
لا تغيير في الكود لازم — `TINY_MODEL='Oa-4/whisper-tiny-ar-quran-ONNX-tjs'` سيُحمَّل
تلقائياً عند فتح شاشة التسميع. تحقّق من عمله من:
- **لوحة `?bench=1` → 🏆 حزمة الذهب**: سجّل مقاطع مرجعية وشغّلها على الشلال الكامل.
- **«تفاصيل تقنية»** في شاشة نتيجة التسميع: يجب أن يظهر «الموديل الصغير: جاهز ✓»
  وأزمنة نوافذ tiny القصيرة.

## اختبار الحزمة الذهبية
بعد التفعيل، مقاطع الحزمة تمرّ على مسار التقييم الكامل (tiny للنوافذ ثم base
للتشخيص) ويُتحقَّق من الأحكام الثلاثية مقابل المتوقَّع لكل مقطع.
