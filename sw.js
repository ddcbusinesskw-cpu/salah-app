const CACHE='mujaddid-v48'; /* v48: fix2 «data2» N81 — app shell cache-first + background revalidate, one entry for index.html (v47: mushaf/pages.json N01) */
/* N81: اسم الكاش يحمل بصمة البناء (sw.js?v=<build>) — كل إصدار جديد يبدأ بكاش جديد ويحذف القديم عند التفعيل */
const VER=(new URL(self.location.href)).searchParams.get('v')||'';
const NAME=CACHE+(VER&&VER!=='dev'?'-'+VER.replace(/[^\w.-]/g,'').slice(0,24):'');
const SHELL='./index.html';
const CORE=[
  SHELL,'./manifest.json',
  './favicon.png','./apple-touch-icon.png','./icon-192.png','./icon-512.png','./icon-maskable-512.png',
  './firebase/firebase-app-compat.js',
  './firebase/firebase-auth-compat.js',
  './firebase/firebase-database-compat.js'
];
self.addEventListener('install',e=>{self.skipWaiting();e.waitUntil(caches.open(NAME).then(c=>c.addAll(CORE).catch(()=>{})));});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==NAME).map(k=>caches.delete(k)))).then(()=>self.clients.claim()));});
/* تحديث الواجهة في الخلفية: إن تغيّرت (ETag/Last-Modified/الحجم) تُبلَّغ الصفحة بأن نسخة جديدة جاهزة للفتح القادم */
function sig(r){return r?[r.headers.get('etag')||'',r.headers.get('last-modified')||'',r.headers.get('content-length')||''].join('|'):'';}
function revalidate(cache,old){
  return fetch(SHELL,{cache:'no-cache'}).then(resp=>{
    if(!resp||!resp.ok)return;
    const changed=!!old&&sig(old)!==sig(resp)&&sig(resp)!=='||';
    return cache.put(SHELL,resp.clone()).then(()=>{if(changed)return self.clients.matchAll({type:'window'}).then(cs=>cs.forEach(c=>c.postMessage({type:'noor-sw-updated'})));});
  }).catch(()=>{});
}
self.addEventListener('fetch',e=>{
  const req=e.request;
  if(req.method!=='GET'){return;}
  const url=new URL(req.url);
  if(url.origin!==location.origin){return;}
  const scope=new URL(self.registration.scope).pathname;
  if(url.pathname===scope||url.pathname===scope+'index.html'){
    /* N81: الواجهة من الكاش فوراً (لا انتظار للشبكة)، وتُجدَّد في الخلفية — مدخل واحد لـ ./ و./index.html */
    e.respondWith(caches.open(NAME).then(cache=>cache.match(SHELL).then(hit=>{
      if(hit){e.waitUntil(revalidate(cache,hit));return hit;}
      /* أول زيارة (لا نسخة محفوظة): من الشبكة، ثم ما تيسّر */
      return fetch(req).then(resp=>{if(resp&&resp.ok){const cp=resp.clone();cache.put(SHELL,cp).catch(()=>{});}return resp;})
        .catch(()=>cache.match(SHELL).then(r=>r||new Response('',{status:503,statusText:'Offline'})));
    })));
    return;
  }
  if(req.mode==='navigate'||req.destination==='document'){
    /* صفحات أخرى (privacy.html…): الشبكة أولاً والكاش احتياط */
    e.respondWith(fetch(req).then(resp=>{if(resp.ok){const cp=resp.clone();caches.open(NAME).then(c=>c.put(req,cp)).catch(()=>{});}return resp;})
      .catch(()=>caches.match(req).then(r=>r||new Response('',{status:503,statusText:'Offline'}))));
    return;
  }
  // بقية الأصول الثابتة: cache-first كما كان (pages.json يُطلب بـ?v= فيتجدّد مع كل إصدار)
  e.respondWith(caches.match(req).then(r=>r||fetch(req).then(resp=>{
    if(resp.ok){const cp=resp.clone();caches.open(NAME).then(c=>c.put(req,cp)).catch(()=>{});}
    return resp;
  }).catch(()=>new Response('',{status:503,statusText:'Offline'}))));
});
