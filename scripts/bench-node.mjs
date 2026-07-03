#!/usr/bin/env node
/**
 * bench-node.mjs — تشخيص مستقل لسلسلة التسميع خارج المتصفح.
 *
 * يفكّ مقطع مرجعي (Alafasy) إلى 16kHz mono f32 عبر ffmpeg، يمرّره لنموذج
 * whisper-base-ar-quran بنفس معاملات التطبيق، ويطبع الخام والمعيَّر ونسبة
 * التطابق — لعزل أي خلل في لوحة المقارنة داخل المتصفح.
 *
 * Usage:
 *   node scripts/bench-node.mjs            # fp32 (default)
 *   node scripts/bench-node.mjs q8         # quantised
 *   node scripts/bench-node.mjs all        # both, sequentially
 */
import { pipeline } from '@huggingface/transformers';
import { execFileSync } from 'node:child_process';
import { writeFileSync, readFileSync, existsSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dir = dirname(fileURLToPath(import.meta.url));
const TMP = join(__dir, '.bench-tmp');
mkdirSync(TMP, { recursive: true });

/* ── نفس مقاطع اللوحة (_BM_SEGS في index.html) ── */
const SEGS = [
  { key: '001001', url: 'https://everyayah.com/data/Alafasy_64kbps/001001.mp3', ref: 'بسم الله الرحمن الرحيم' },
  { key: '001002', url: 'https://everyayah.com/data/Alafasy_64kbps/001002.mp3', ref: 'الحمد لله رب العالمين' },
  { key: '112001', url: 'https://everyayah.com/data/Alafasy_64kbps/112001.mp3', ref: 'قل هو الله احد' },
  { key: '112002', url: 'https://everyayah.com/data/Alafasy_64kbps/112002.mp3', ref: 'الله الصمد' },
  { key: '112003', url: 'https://everyayah.com/data/Alafasy_64kbps/112003.mp3', ref: 'لم يلد ولم يولد' },
];

const MODEL = 'Oa-4/whisper-base-ar-quran-ONNX-tjs-v2';

/* ── دوال التطبيع منسوخة حرفياً من index.html ── */
function snorm(x){return x.replace(/وٰ/g,'ا').replace(/ٰ/g,'ا').replace(/[ً-ٟـۖ-ۯ]/g,'').replace(/[إأآٱ]/g,'ا').replace(/ى/g,'ي').replace(/ؤ/g,'و').replace(/ئ/g,'ي').replace(/ة/g,'ه').replace(/\s+/g,' ').trim();}
function _normRec(x){
  x=x.replace(/اللاه/g,'الله').replace(/الاه/g,'الله').replace(/اللاة/g,'الله');
  x=x.replace(/لاله/g,'لله');
  if(x==='اللا'||x==='اللاا')x='الله';
  x=x.replace(/ء/g,'');
  x=x.replace(/ت(?=[ها]$)/g,'ه');
  x=x.replace(/نن$/,'ن');
  x=x.replace(/(.)(\1){2,}/g,'$1');
  x=x.replace(/اا+/g,'ا').replace(/وو+/g,'و').replace(/يي+/g,'ي');
  x=snorm(x);
  x=x.replace(/الرحمان/g,'الرحمن'); // بعد snorm — انظر index.html
  if(x.length>=3)x=x.replace(/ا$/,'ي');
  return x;
}
function _memED(a,b){if(a===b)return 0;if(!a.length)return b.length;if(!b.length)return a.length;var p=[],r=[];for(var j=0;j<=b.length;j++)p[j]=j;for(var i=1;i<=a.length;i++){r=[i];for(var j=1;j<=b.length;j++)r[j]=a[i-1]===b[j-1]?p[j-1]:1+Math.min(p[j-1],p[j],r[j-1]);p=r.slice();}return p[b.length];}
function wer(hyp, ref){
  const h = hyp.trim().split(/\s+/).filter(Boolean).map(_normRec);
  const r = ref.trim().split(/\s+/).filter(Boolean).map(snorm);
  if(!r.length) return 0;
  return Math.min(1, _memED(h, r) / r.length);
}

/* ── نفس تشذيب الصمت في worker اللوحة (_tr) ── */
function trimSilence(f32){
  const W=320, T=0.003;
  const rms=(b,s)=>{let x=0;for(let i=s;i<s+W&&i<b.length;i++)x+=b[i]*b[i];return Math.sqrt(x/W);};
  let s=0; while(s+W<f32.length && rms(f32,s)<T) s+=W;
  let e=f32.length-W; while(e>s && rms(f32,e)<T) e-=W;
  return f32.slice(Math.max(0,s-1600), Math.min(f32.length,e+W+1600));
}

async function fetchMp3(seg){
  const mp3Path = join(TMP, seg.key + '.mp3');
  if(!existsSync(mp3Path)){
    const res = await fetch(seg.url);
    if(!res.ok) throw new Error('HTTP ' + res.status + ' for ' + seg.url);
    writeFileSync(mp3Path, Buffer.from(await res.arrayBuffer()));
  }
  return mp3Path;
}

function decodeTo16k(mp3Path){
  const pcmPath = mp3Path.replace(/\.mp3$/, '.pcm');
  execFileSync('ffmpeg', ['-y','-loglevel','error','-i',mp3Path,'-ar','16000','-ac','1','-f','f32le',pcmPath]);
  const buf = readFileSync(pcmPath);
  return new Float32Array(buf.buffer, buf.byteOffset, buf.byteLength/4);
}

async function runVariant(variant){
  const dtype = variant === 'q8'
    ? { encoder_model:'q8', decoder_model_merged:'q8' }
    : { encoder_model:'fp32', decoder_model_merged:'fp32' };
  console.log('\n════════ variant: ' + variant + ' ════════');
  const t0 = Date.now();
  const asr = await pipeline('automatic-speech-recognition', MODEL, { dtype });
  console.log('نموذج جاهز في ' + (Date.now()-t0) + 'ms');

  let accSum = 0, procSum = 0;
  for(const seg of SEGS){
    const mp3 = await fetchMp3(seg);
    const raw16k = decodeTo16k(mp3);
    const audio = trimSilence(raw16k);
    const t1 = Date.now();
    const out = await asr(audio, { language:'arabic', task:'transcribe', return_timestamps:false });
    const procMs = Date.now()-t1;
    const rawText = out.text || '';
    const hypNorm = rawText.trim().split(/\s+/).filter(Boolean).map(_normRec).join(' ');
    const refNorm = seg.ref.trim().split(/\s+/).filter(Boolean).map(snorm).join(' ');
    const acc = Math.round((1-wer(rawText, seg.ref))*100);
    accSum += acc; procSum += procMs;
    console.log('─'.repeat(60));
    console.log('مفتاح     : ' + seg.key + '  (عينات ' + raw16k.length + ' → بعد التشذيب ' + audio.length + ')');
    console.log('المتوقّع  : ' + seg.ref);
    console.log('الخام     : ' + (rawText || '(فارغ)'));
    console.log('معيَّر hyp: ' + (hypNorm || '(فارغ)'));
    console.log('معيَّر ref: ' + refNorm);
    console.log('تطابق     : ' + acc + '%   (' + procMs + 'ms)');
  }
  const avgAcc = Math.round(accSum/SEGS.length);
  const avgMs = Math.round(procSum/SEGS.length);
  console.log('═'.repeat(60));
  console.log('متوسط ' + variant + ': تطابق ' + avgAcc + '% · ' + avgMs + 'ms/مقطع');
  return { variant, avgAcc, avgMs };
}

const arg = (process.argv[2]||'fp32').toLowerCase();
const variants = arg === 'all' ? ['fp32','q8'] : [arg];
const results = [];
for(const v of variants) results.push(await runVariant(v));
console.log('\n📊 الخلاصة:');
for(const r of results) console.log('  ' + r.variant + ': ' + r.avgAcc + '% · ' + r.avgMs + 'ms');
