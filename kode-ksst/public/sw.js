const CACHE_NAME = 'kode-ksst-v1';

self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', (event) => {
  // Network-first pass-through for API/WS/Live execution
  if (event.request.method !== 'GET' || event.request.url.includes('/api/') || event.request.url.includes('/preview/')) {
    return;
  }
  event.respondWith(
    fetch(event.request).catch(() => caches.match(event.request))
  );
});
