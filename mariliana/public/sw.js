const CACHE='mariliana-v2';
const ASSETS=['/','/index.html','/style.css','/app.js','/manifest.webmanifest','/icon.svg','/bebidas.svg','/icon-192.png','/icon-512.png','/vendor/html5-qrcode.min.js'];
self.addEventListener('install', e=>e.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS)).then(()=>self.skipWaiting())));
self.addEventListener('activate', e=>e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch', e=>{ if(e.request.method!=='GET'||new URL(e.request.url).pathname.startsWith('/api/')) return; e.respondWith(caches.match(e.request).then(c=>c||fetch(e.request))); });
