const CACHE='mujaddid-v45';
const CORE=[
  './','./index.html','./manifest.json',
  './favicon.png','./apple-touch-icon.png','./icon-192.png','./icon-512.png','./icon-maskable-512.png',
  './models/tf.min.js','./models/pose-detection.min.js',
  './firebase/firebase-app-compat.js',
  './firebase/firebase-auth-compat.js',
  './firebase/firebase-database-compat.js'
];
self.addEventListener('install',e=>{self.skipWaiting();e.waitUntil(caches.open(CACHE).then(c=>c.addAll(CORE).catch(()=>{})));});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()));});
self.addEventListener('fetch',e=>{
  const req=e.request;
  if(req.method!=='GET'){return;}
  const url=new URL(req.url);
  if(url.origin!==location.origin){return;}
  const isHTML=req.destination==='document'||(req.headers.get('accept')||'').includes('text/html')||url.pathname.endsWith('/index.html');
  if(isHTML){
    // network-first: تحديثات index.html تصل فوراً؛ الكاش احتياط للأوفلاين فقط
    e.respondWith(fetch(req).then(resp=>{
      if(resp.ok){const cp=resp.clone();caches.open(CACHE).then(c=>c.put(req,cp)).catch(()=>{});}
      return resp;
    }).catch(()=>caches.match(req).then(r=>r||caches.match('./index.html'))));
    return;
  }
  // بقية الأصول الثابتة: cache-first كما كان
  e.respondWith(caches.match(req).then(r=>r||fetch(req).then(resp=>{
    if(resp.ok){const cp=resp.clone();caches.open(CACHE).then(c=>c.put(req,cp)).catch(()=>{});}
    return resp;
  }).catch(()=>new Response('',{status:503,statusText:'Offline'}))));
});
