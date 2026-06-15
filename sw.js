const CACHE='mujaddid-v43';
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
  if(url.origin===location.origin){
    e.respondWith(caches.match(req).then(r=>r||fetch(req).then(resp=>{
      // Only cache successful responses
      if(resp.ok){const cp=resp.clone();caches.open(CACHE).then(c=>c.put(req,cp)).catch(()=>{});}
      return resp;
    }).catch(()=>{
      // Only fall back to index.html for HTML navigation requests — never for scripts/assets
      if(req.destination==='document'||req.headers.get('accept')||''.includes('text/html')){
        return caches.match('./index.html');
      }
      return new Response('',{status:503,statusText:'Offline'});
    })));
  }
});
