const CACHE='alex-v2';
const ASSETS=[
  './',
  './index.html',
  './manifest.json',
  './css/style.css',
  './js/config.js',
  './js/avatar.js',
  './js/brain.js',
  './js/voice.js',
  './js/vision.js',
  './js/app.js',
  './assets/icon-192.png',
  './assets/icon-512.png'
];
self.addEventListener('install', e=>{
  e.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS)));
  self.skipWaiting();
});
self.addEventListener('activate', e=>{
  e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k!==CACHE).map(k=>caches.delete(k)))));
  self.clients.claim();
});
self.addEventListener('fetch', e=>{
  e.respondWith(
    caches.match(e.request).then(r=> r || fetch(e.request).then(res=>{
      // cache dinámico
      return res;
    }).catch(()=> caches.match('./index.html')))
  );
});
