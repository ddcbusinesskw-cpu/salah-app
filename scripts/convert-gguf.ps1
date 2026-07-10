#Requires -Version 5
<#
  convert-gguf.ps1 — تحويل ورفع موديل ترتيل tiny إلى GGUF للمحرّك الأصلي (whisper.cpp).
  يشغّل كل الخطوات بالترتيب تلقائياً. يتوقّف عند أي خطأ ويطبعه كاملاً.

  التشغيل:
    powershell -ExecutionPolicy Bypass -File scripts\convert-gguf.ps1

  المتطلبات: Python 3.10+، git، اتصال إنترنت، وحساب HuggingFace بصلاحية write على Oa-4.
#>

$ErrorActionPreference = 'Stop'

# ── إعدادات ثابتة (يجب أن تطابق index.html: WN_GGUF_URL / WN_GGUF_FILE) ──
$SrcModel   = 'Salama1429/tarteel-ai-whisper-tiny-ar-quran'
$Repo       = 'Oa-4/whisper-tiny-ar-quran-gguf'
$OutFile    = 'ggml-tiny-ar-quran-q8_0.bin'
$Quant      = 'q8_0'
$Work       = Join-Path (Get-Location) 'gguf-build'

function Stage([int]$n, [string]$msg) {
  Write-Host ''
  Write-Host "⏳ المرحلة $n/6: $msg" -ForegroundColor Cyan
}
function Ok([string]$msg)   { Write-Host "✓ $msg" -ForegroundColor Green }
function Info([string]$msg) { Write-Host "  $msg" -ForegroundColor Gray }
function Die([string]$msg) {
  Write-Host ''
  Write-Host "✗ توقّف: $msg" -ForegroundColor Red
  exit 1
}

# تشغيل أمر خارجي والتوقف مع طباعة المخرجات كاملةً عند الفشل
function Run([string]$exe, [string[]]$argv) {
  Info ("> $exe " + ($argv -join ' '))
  & $exe @argv 2>&1 | ForEach-Object { Write-Host "  $_" }
  if ($LASTEXITCODE -ne 0) { Die "فشل الأمر: $exe (رمز $LASTEXITCODE) — راجع المخرجات أعلاه." }
}

Write-Host '════════════════════════════════════════════════════════' -ForegroundColor DarkCyan
Write-Host '  تحويل موديل ترتيل tiny → GGUF (whisper.cpp)  ' -ForegroundColor White
Write-Host '════════════════════════════════════════════════════════' -ForegroundColor DarkCyan

# ── المرحلة 1: فحص Python + git ──
Stage 1 'فحص الأدوات (Python + git)'
$py = $null
foreach ($cand in @('python', 'python3', 'py')) {
  $cmd = Get-Command $cand -ErrorAction SilentlyContinue
  if ($cmd) {
    try {
      $v = & $cand --version 2>&1
      if ($v -match 'Python 3\.(\d+)') {
        if ([int]$Matches[1] -ge 10) { $py = $cand; break }
        else { Info "$cand إصداره $v — يلزم 3.10+" }
      }
    } catch {}
  }
}
if (-not $py) {
  Write-Host ''
  Write-Host 'Python 3.10 أو أحدث غير موجود.' -ForegroundColor Red
  Write-Host 'ثبّته من: https://www.python.org/downloads/  (فعّل «Add python.exe to PATH»)' -ForegroundColor Yellow
  exit 1
}
Ok "Python: $(& $py --version)"
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
  Write-Host 'git غير موجود — ثبّته من: https://git-scm.com/download/win' -ForegroundColor Yellow
  Die 'git مطلوب لجلب whisper.cpp.'
}
Ok "git: $(git --version)"

New-Item -ItemType Directory -Force -Path $Work | Out-Null
Set-Location $Work
Info "مجلد العمل: $Work"

# ── المرحلة 2: تثبيت المتطلبات + جلب whisper.cpp ──
Stage 2 'تثبيت متطلبات pip + جلب whisper.cpp'
Run $py @('-m','pip','install','--upgrade','pip')
Run $py @('-m','pip','install','--upgrade','transformers','torch','numpy','huggingface_hub')
if (-not (Test-Path 'whisper.cpp')) {
  Run 'git' @('clone','--depth','1','--branch','v1.7.4','https://github.com/ggerganov/whisper.cpp')
} else { Info 'whisper.cpp موجود — تخطّي الاستنساخ' }
if (-not (Test-Path 'whisper.cpp/openai-whisper')) {
  Run 'git' @('clone','--depth','1','https://github.com/openai/whisper','whisper.cpp/openai-whisper')
} else { Info 'openai/whisper موجود — تخطّي' }
Ok 'المتطلبات وwhisper.cpp جاهزة'

# ── المرحلة 3: تنزيل موديل ترتيل (صيغة HF) ──
Stage 3 "تنزيل موديل المصدر ($SrcModel)"
Run $py @('-c', @"
from huggingface_hub import snapshot_download
p = snapshot_download('$SrcModel', local_dir='tiny-ar-hf')
print('downloaded to', p)
"@)
Ok 'الموديل المصدر نُزّل إلى tiny-ar-hf'

# ── المرحلة 4: التحويل إلى ggml ──
Stage 4 'التحويل إلى ggml (convert-h5-to-ggml)'
Push-Location 'whisper.cpp'
Run $py @('models/convert-h5-to-ggml.py','../tiny-ar-hf','./openai-whisper','.')
if (-not (Test-Path 'ggml-model.bin')) { Pop-Location; Die 'لم يُنتَج ggml-model.bin — راجع مخرجات التحويل.' }
Ok 'ggml-model.bin أُنتج'

# ── المرحلة 5: التكميم q8_0 + التحقق ──
Stage 5 "التكميم ($Quant) + التحقق"
if (-not (Test-Path 'build')) { Run 'cmake' @('-B','build') }
Run 'cmake' @('--build','build','-j','--target','quantize','whisper-cli')
# مسار ثنائيات التكميم يختلف بين إصدارات whisper.cpp — جرّب المعروفة
$quantExe = @('build/bin/quantize.exe','build/bin/quantize','build/bin/Release/quantize.exe') |
  Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $quantExe) { Pop-Location; Die 'لم أجد ثنائي quantize بعد البناء — راجع مخرجات cmake.' }
Run $quantExe @('ggml-model.bin', "../$OutFile", $Quant)
Pop-Location
if (-not (Test-Path $OutFile)) { Die "لم يُنتَج $OutFile." }
$sizeMB = [math]::Round((Get-Item $OutFile).Length / 1MB, 1)
Ok "$OutFile جاهز ($sizeMB م.ب)"

# تحقّق سريع بـwhisper-cli على عيّنة jfk إن توفّرت (اختياري — لا يوقف عند غيابها)
$cli = @('whisper.cpp/build/bin/whisper-cli.exe','whisper.cpp/build/bin/whisper-cli','whisper.cpp/build/bin/Release/whisper-cli.exe') |
  Where-Object { Test-Path $_ } | Select-Object -First 1
$sample = 'whisper.cpp/samples/jfk.wav'
if ($cli -and (Test-Path $sample)) {
  Info 'تحقّق تشغيلي (عيّنة jfk — للتأكد أن الموديل يُحمّل ويُفرّغ):'
  & $cli -m $OutFile -l ar -f $sample -nt 2>&1 | Select-Object -Last 4 | ForEach-Object { Write-Host "  $_" }
} else {
  Info 'تخطّي التحقق التشغيلي (whisper-cli/عيّنة غير متوفرة) — التكميم اكتمل.'
}

# ── المرحلة 6: الرفع إلى HuggingFace ──
Stage 6 "الرفع إلى $Repo"
if (-not $env:HF_TOKEN) {
  Write-Host ''
  Write-Host 'يلزم توكن HuggingFace بصلاحية write على حساب Oa-4.' -ForegroundColor Yellow
  Write-Host 'أنشئه من: https://huggingface.co/settings/tokens  (نوع: Write)' -ForegroundColor Yellow
  $sec = Read-Host 'ألصق التوكن هنا' -AsSecureString
  $env:HF_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
}
if (-not $env:HF_TOKEN) { Die 'لا توكن — أُلغي الرفع.' }
Run $py @('-c', @"
import os
from huggingface_hub import HfApi
api = HfApi(token=os.environ['HF_TOKEN'])
api.create_repo('$Repo', repo_type='model', exist_ok=True)
api.upload_file(path_or_fileobj='$OutFile', path_in_repo='$OutFile',
                repo_id='$Repo', repo_type='model')
print('uploaded $OutFile -> $Repo')
"@)

Write-Host ''
Write-Host '════════════════════════════════════════════════════════' -ForegroundColor Green
Ok "اكتمل. الموديل متاح على: https://huggingface.co/$Repo/resolve/main/$OutFile"
Write-Host '  افتح شاشة التسميع في التطبيق — سينزّله مرة ويتحوّل للمحرّك الأصلي تلقائياً.' -ForegroundColor Gray
Write-Host '  تحقّق من «تفاصيل تقنية»: «المحرّك: أصلي whisper.cpp».' -ForegroundColor Gray
Write-Host '════════════════════════════════════════════════════════' -ForegroundColor Green
