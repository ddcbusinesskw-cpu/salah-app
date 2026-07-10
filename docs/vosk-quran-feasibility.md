# جدوى بناء موديل Vosk قرآني عبر استبدال شبكة اللغة (بلا تدريب صوتي)

**المرحلة ٠ — دراسة جدوى فقط. لا تنفيذ.**

الفكرة: أذن mgb2 الصوتية (`am/final.mdl` + `ivector/`) سليمة؛ المشكلة في شبكة
الكلمات الإعلامية (`graph/HCLG.fst`). نعيد بناء الشبكة من معجم ولغة القرآن
فقط (~15 ألف كلمة فريدة) بنفس الأذن → مخرجات المحرّك تنحصر في كلمات القرآن
→ ستريمنغ لحظي دقيق للتلاوة.

---

## الحكم الصريح

> **ممكن — بشرط واحد قابل للفحص في 5 دقائق.**
>
> كل مكوّنات الوصفة تحقّقتُ منها فعلياً من بيئتي (أدناه)، والعقبة الوحيدة
> المحتملة هي: هل يشحن zip موديل Vosk ملفَّي `am/tree` و`graph/phones.txt`؟
> **خطوة الجرد (القسم ١) تحسم ذلك فوراً**، ولكل احتمال مسار جاهز (القسم ٥).

### ما تحقّقتُ منه فعلياً (لا افتراضات)

| الحقيقة | الدليل (متحقَّق من بيئتي) |
|---|---|
| وصفة MGB-2 الرسمية تستخدم **معجماً حرفياً** (grapheme) لا فونيمياً | `kaldi/egs/mgb2_arabic/s5/run.sh` سطر 34: `ar-ar_grapheme_lexicon` + `local/graphgeme_mgb_prep_dict.sh` |
| المعجم المصدر متاح للتنزيل | `github.com/qcri/ArabicASRChallenge2016/lexicon/ar-ar_grapheme_lexicon` → HTTP 200، الصيغة: `كلمة ح ر و ف` |
| الوحدات الصوتية: `SIL` + حروف — النطق = تهجئة الكلمة | نص `graphgeme_mgb_prep_dict.sh`: `nonsilence_phones` تُشتقّ من أعمدة المعجم نفسها |
| Kaldi يدعم بناء lang جديد **بجدول فونات موجود** (مفتاح استبدال الشبكة) | `utils/prepare_lang.sh --phone-symbol-table` (سطر 64/94) — يضمن تطابق أرقام الفونات مع `final.mdl` |
| Kaldi متوفّر مُجمَّعاً لـColab عبر conda-forge (بلا تجميع ساعة) | قناة conda-forge تستجيب (repodata 200؛ حزمة `kaldi` هي أساس Montreal Forced Aligner) |
| kenlm متوفّر لتدريب الـLM | pypi 200 |
| نص القرآن كاملاً بالحياز | `QURAN_EMBED` في التطبيق (رسم إملائي، هو نفسه مرجع المحاذاة `_lmNorm`) |

**لماذا المعجم الحرفي يحسم أصعب سؤال (٣):** لا نحتاج G2P إطلاقاً. نطق أي
كلمة قرآنية = حروفها بعد التطبيع. `بسم` → `b s m` (بنفس رموز `phones.txt`
الموجودة في الموديل). التشكيل والرسم العثماني يُطبَّعان لرسم إملائي بلا تشكيل
— **وهو نفسه ما تنتظره محاذاة التطبيق (`_lmNorm`/`snorm`)، فتتطابق مخرجات
المحرّك مع كلمات الصفحة حرفياً.**

---

## ١) الجرد الحاسم (يُنفَّذ أولاً — 5 دقائق)

الموديل بحوزتك (نُزِّل على الجهاز/جهازك). المطلوب جرد الـzip. خلية Colab:

```python
# ═══ خلية الجرد — تكشف أي مسار نسلك ═══
!wget -q https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip
!unzip -l vosk-model-ar-mgb2-0.4.zip
# ملفات القرار:
#   am/tree            → موجود؟ المسار أ (mkgraph كامل) ✅
#   graph/phones.txt   → موجود؟ شرط للمسارين أ وب
#   graph/words.txt    → عربي أم Buckwalter؟ (يحدّد صيغة معجمنا)
#   graph/HCLr.fst+Gr.fst بدل HCLG.fst → المسار ب الأسهل (استبدال Gr فقط)
#   graph/disambig_tid.int, graph/phones/word_boundary.int → لسلامة التغليف
!unzip -q vosk-model-ar-mgb2-0.4.zip
!head -30 vosk-model-ar-mgb2-0.4/graph/phones.txt 2>/dev/null || echo "phones.txt مفقود!"
!head -20 vosk-model-ar-mgb2-0.4/graph/words.txt
!ls -la vosk-model-ar-mgb2-0.4/am/
```

*(بديل بلا Colab: قسم «المحرّكان» في تفاصيل تقنية يعرض الآن أحجام الملفات
الأساسية عبر `voskStats` — يجيب جزئياً عن HCLG/HCLr وword_boundary.)*

**شجرة القرار:**

```
am/tree + graph/phones.txt موجودان؟
├─ نعم → المسار أ: mkgraph كامل (الوصفة أدناه) — الطريق الرئيسي
├─ HCLr.fst + Gr.fst بدل HCLG (موديل lookahead)؟
│   → المسار ب: استبدال Gr.fst فقط (أسهل: LM قرآني فوق words.txt الموجود،
│     بلا tree أصلاً) + مكافأة: يدعم grammar لحظي per-page من JS
└─ tree مفقود وphones.txt مفقود والموديل HCLG ساكن
    → المسار ج: الخطة البديلة (القسم ٥)
```

---

## ٢) الوصفة الكاملة — المسار أ (mkgraph كامل في Colab)

**قابلة للتنفيذ في Colab المجاني:** الموديل 318MB + فكّه ~500MB + kaldi
conda ~1GB → أقل بكثير من قرص Colab (~70GB). الذاكرة: mkgraph لمفردات 15k
بـLM ثلاثي مقصوص = مئات الميجابايت (المفردات الإعلامية مئات الآلاف؛ نحن 15k
— أصغر بـ20 مرة). لا GPU مطلوب.

### الخلية ١ — الأدوات (kaldi مُجمَّع من conda-forge، ~10 دقائق)
```python
!pip install -q condacolab && python -c "import condacolab; condacolab.install_miniforge()"
# (يعيد تشغيل runtime تلقائياً — أعد تشغيل الخلايا التالية بعده)
```
```python
!mamba install -y -c conda-forge kaldi openfst pynini
!pip install -q kenlm arpa
# سكربتات utils/ (نصية فقط، لا تجميع):
!git clone -q --depth 1 https://github.com/kaldi-asr/kaldi /content/kaldi-scripts
import os
os.environ['PATH'] = '/opt/conda/bin:' + os.environ['PATH']
!which fstcompile compile-train-graphs   # تحقّق: ثنائيات kaldi جاهزة
```

### الخلية ٢ — الموديل + نص القرآن
```python
!wget -q https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip
!unzip -q vosk-model-ar-mgb2-0.4.zip && mv vosk-model-ar-mgb2-0.4 model
# نص القرآن الإملائي (نفس مصدر التطبيق — مثلا tanzil.net quran-simple.txt):
!wget -q "https://tanzil.net/pub/download/index.php?quranType=simple-clean&outType=txt&agree=true" -O quran.txt
```

### الخلية ٣ — التطبيع + المعجم الحرفي + الـLM
```python
import re, collections
# تطبيع لرسم إملائي بلا تشكيل (مطابق لتطبيع التطبيق):
def norm(w):
    w = re.sub(r'[ً-ْٰٓ-ٟۖ-ۭـ]', '', w)  # تشكيل/تطويل/علامات
    return w.strip()
words = collections.Counter()
lines = []
for line in open('quran.txt', encoding='utf-8'):
    line = line.split('|')[-1].strip()          # صيغة tanzil: سورة|آية|نص
    toks = [norm(t) for t in line.split()]
    toks = [t for t in toks if t]
    if toks: lines.append(' '.join(toks)); words.update(toks)
print(len(words), 'كلمة فريدة')                 # متوقّع ~15k
open('quran_norm.txt','w',encoding='utf-8').write('\n'.join(lines))

# المعجم: النطق = الحروف — **بنفس رموز phones.txt الموجودة**
phones = {l.split()[0] for l in open('model/graph/phones.txt', encoding='utf-8')}
# إن كانت الرموز Buckwalter: حوّل الحروف العربية إليها بجدول ثابت هنا
def graphemes(w):
    return [c for c in w]   # عدّل بجدول عربي→Buckwalter إذا أظهر الجرد ذلك
oov_units = set()
with open('lexicon.txt','w',encoding='utf-8') as f:
    f.write('<UNK> SIL\n')
    for w in sorted(words):
        units = graphemes(w)
        missing = [u for u in units if u not in phones and f'{u}_B' not in phones]
        if missing: oov_units.update(missing); continue
        f.write(w + ' ' + ' '.join(units) + '\n')
print('وحدات غير معروفة (يجب أن تكون قليلة/صفراً):', oov_units)

# LM ثلاثي على نص القرآن (kenlm):
!mamba install -y -c conda-forge kenlm  # ثنائيات lmplz/build_binary
!lmplz -o 3 --discount_fallback < quran_norm.txt > quran3.arpa
```

### الخلية ٤ — lang جديد بنفس جدول الفونات + mkgraph
```python
%%bash
cd /content
K=/content/kaldi-scripts/egs/wsj/s5
export PATH=$K/utils:$PATH
mkdir -p dict lang_tmp
cp lexicon.txt dict/lexicon.txt
echo SIL > dict/silence_phones.txt
echo SIL > dict/optional_silence.txt
cut -d' ' -f2- dict/lexicon.txt | tr ' ' '\n' | sort -u | grep -v SIL > dict/nonsilence_phones.txt
touch dict/extra_questions.txt
# ★ المفتاح: --phone-symbol-table يقفل أرقام الفونات على phones.txt الأصلي
$K/utils/prepare_lang.sh --phone-symbol-table model/graph/phones.txt \
  dict "<UNK>" lang_tmp lang
# G.fst من arpa:
$K/utils/format_lm.sh lang quran3.arpa dict/lexicon.txt lang_test
# HCLG قرآني بالأذن الأصلية (tree + final.mdl):
$K/utils/mkgraph.sh lang_test model/am graph_quran
ls -la graph_quran/
```

### الخلية ٥ — التغليف والرفع إلى HF (Oa-4)
```python
%%bash
mkdir -p vosk-model-ar-quran-0.1
cp -r model/am model/ivector model/conf vosk-model-ar-quran-0.1/
mkdir -p vosk-model-ar-quran-0.1/graph
cp graph_quran/HCLG.fst graph_quran/words.txt vosk-model-ar-quran-0.1/graph/
cp -r graph_quran/phones vosk-model-ar-quran-0.1/graph/     # word_boundary.int وأخواته
cp graph_quran/phones.txt graph_quran/disambig_tid.int vosk-model-ar-quran-0.1/graph/ 2>/dev/null
zip -rq vosk-model-ar-quran-0.1.zip vosk-model-ar-quran-0.1
du -h vosk-model-ar-quran-0.1.zip
```
```python
from huggingface_hub import HfApi
api = HfApi(token='hf_...')  # توكن write على Oa-4
api.create_repo('Oa-4/vosk-model-ar-quran', repo_type='model', exist_ok=True)
api.upload_file(path_or_fileobj='vosk-model-ar-quran-0.1.zip',
                path_in_repo='vosk-model-ar-quran-0.1.zip',
                repo_id='Oa-4/vosk-model-ar-quran', repo_type='model')
```
ثم في التطبيق: تغيير `VK_MODEL_URL/VK_MODEL_NAME` فقط.

### اختبار قبول داخل Colab (قبل الرفع)
```python
!pip -q install vosk soundfile
import vosk, json, soundfile as sf, urllib.request
urllib.request.urlretrieve('https://everyayah.com/data/Alafasy_64kbps/001001.mp3','a.mp3')
!ffmpeg -y -loglevel error -i a.mp3 -ar 16000 -ac 1 a.wav
m = vosk.Model('vosk-model-ar-quran-0.1'); r = vosk.KaldiRecognizer(m, 16000)
data, _ = sf.read('a.wav', dtype='int16')
r.AcceptWaveform(data.tobytes()); print(json.loads(r.FinalResult()))
# متوقّع: «بسم الله الرحمن الرحيم» حرفياً — المفردات محصورة قرآنياً
```

---

## ٣) معجم النطق (سؤال ٣ محسوم)

- **لا حاجة لـG2P ولا phonetisaurus:** الأذن حرفية (متحقَّق من الوصفة الرسمية
  والمعجم المصدر). النطق = تهجئة.
- **التشكيل:** يُحذف كلّياً (regex الخلية ٣) — الأذن تتعامل مع الصوتيات
  القصيرة ضمنياً كما تدرّبت على نص إعلامي غير مشكول.
- **الرسم العثماني:** لا نستخدمه للمعجم — نستخدم الرسم الإملائي (quran-simple
  أو `QURAN_EMBED` نفسه)، وهو ما تعرضه المحاذاة أصلاً. (الرسم العثماني يبقى
  للعرض فقط عبر خطوط QCF — طبقة العرض منفصلة عن طبقة التعرّف أصلاً في التطبيق.)
- **الاحتمال الوحيد:** إن أظهر الجرد أن `words.txt`/`phones.txt` بـBuckwalter،
  نضيف جدول تحويل ثابتاً (سطر واحد في الخلية ٣) ونحوّل مخرجات المحرّك عربياً
  في JS (جدول عكسي ~40 حرفاً).

## ٤) الحجم والأداء المتوقّعان + التغليف

| البند | التقدير |
|---|---|
| LM قرآني ثلاثي (15k كلمة، 78k توكن) | arpa ~2–5MB |
| HCLG القرآني | ~20–60MB (مقابل ~400MB إعلامي — المفردات أصغر 20×) |
| الموديل الكامل (am+ivector+conf+graph) | **~100–160MB** (مقابل 318MB) |
| زمن الجزئيات | نفس اللحظية (نفس الأذن) بل أسرع قليلاً (شبكة أصغر = بحث أخف وRAM أقل) |
| الدقّة على التلاوة | قفزة كبيرة متوقعة: كل مخرج محصور بكلمات القرآن، والالتباسات الإعلامية مستحيلة بنيوياً. يبقى التحفّظ الصوتي (مدود التجويد) — تمتصّه المحاذاة المتسامحة + تأكيد whisper |
| بنية المجلد (Vosk صالح) | `am/ conf/ graph/(HCLG.fst,words.txt,phones/word_boundary.int) ivector/` — نفس بنية mgb2 مع استبدال graph فقط |
| الرخصة | نماذج Vosk بترخيص Apache 2.0 — التعديل وإعادة النشر على Oa-4 جائزان مع النسبة |

## ٥) الخطط البديلة (إن أظهر الجرد نقصاً)

| العائق | البديل | الكلفة |
|---|---|---|
| الموديل lookahead (HCLr+Gr بدل HCLG) | **أفضل من الخطة الأصلية:** استبدال `Gr.fst` فقط بـLM قرآني فوق `words.txt` الموجود (opengrm/ngram، بلا tree ولا mkgraph) + يدعم **grammar لحظي من JS** (تمرير كلمات الصفحة الحالية لـRecognizer — تقييد ديناميكي لكل صفحة!) | ساعة عمل |
| `tree` مفقود وphones.txt موجود | لا mkgraph ممكن بالأذن نفسها → جرّب `vosk-model-ar-0.22-linto` (1.3GB) — إن شمل tree نفّذ عليه نفس الوصفة (الناتج النهائي بعد قصّ الشبكة يبقى صغيراً) | جرد إضافي + نفس الوصفة |
| phones.txt مفقود كلياً | لا يمكن مواءمة المعجم بأمان → المسار التدريبي: fine-tune خفيف لموديل ستريمنغ (k2/icefall zipformer أو wav2vec2-streaming) على everyayah (~ساعات GPU، أيام عمل) — يُقيَّم حينها مقابل الاكتفاء بـwhisper المُسرَّع الحالي | أيام |
| فشل التنزيل من alphacephei في Colab | التنزيل يعمل على جهازك (ثبت actualy في التطبيق) — ارفع الـzip يدوياً لـColab أو لـHF أولاً | دقائق |

## ٦) المدة المتوقّعة والمخاطر

**المدة (المسار أ):** جرد 5د · تجهيز Colab ~20د · معجم+LM ~15د ·
prepare_lang+mkgraph ~10–30د · اختبار قبول داخل Colab ~10د · تغليف+رفع ~20د
→ **~1.5–2 ساعة عمل واحدة**، ثم تغيير سطرين في التطبيق (`VK_MODEL_URL/NAME`).

**المخاطر ومصدّاتها:**
1. **tree/phones.txt غير مشحونين** — الخطر الأكبر؛ يُحسم بخطوة الجرد قبل أي
   استثمار، ولكل نتيجة مسار (القسم ٥).
2. **عدم تطابق أرقام الفونات** — مصدّه `--phone-symbol-table` (متحقَّق أنه
   موجود في prepare_lang.sh) + فحص القبول داخل Colab قبل الرفع.
3. **words.txt بـBuckwalter** — جدول تحويل ثابت (يكشفه الجرد فوراً).
4. **position-dependent phones** (لواحق `_B/_I/_E/_S`) — يظهر في phones.txt
   عند الجرد؛ `prepare_lang.sh` يولّدها افتراضياً بنفس النمط، والخلية ٣ تفحص
   `u_B` احتياطاً.
5. **ذاكرة mkgraph في Colab** — شبه معدومة لمفردات 15k (المشكلة تظهر عند
   مئات الآلاف).
6. **بقايا لهجة الأذن** — الأذن تسمع MSA إعلامياً؛ التلاوة المجوّدة قد تشوّه
   بعض الأحرف الممدودة. التقييد المعجمي يصحّح الأغلب، وwhisper القرآني يبقى
   طبقة الدقّة النهائية (المعمارية الثنائية كما هي).

---

**الخلاصة: ممكن، والطريق قصير (جلسة عمل واحدة)، والمكوّن المجهول الوحيد
(tree/phones.txt داخل الـzip) يُحسم بخلية جرد واحدة قبل أي التزام. أعطني
إشارة التنفيذ بعد أن تشغّل خلية الجرد وتلصق لي مخرجاتها، أو شغّلها أنت
وألصق النتيجة هنا لأكمل بالمسار الصحيح.**
