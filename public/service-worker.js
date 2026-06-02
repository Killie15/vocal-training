const CACHE_NAME = 'pitchflight-cache-v4';
const ASSETS = [
  '/',
  '/css/style.css',
  '/css/all.min.css',
  '/webfonts/fa-solid-900.woff2',
  '/webfonts/fa-regular-400.woff2',
  '/js/audio.js',
  '/js/midi-parser.js',
  '/js/game.js',
  '/js/sheet.js',
  '/js/db-helper.js',
  '/js/app.js',
  '/js/curriculum.json',
  '/manifest.json',
  '/icon.svg',
  '/icon-192.png',
  '/icon-512.png',
  '/permission_denied.html',
  '/screenshot-portrait.png',
  '/screenshot-landscape.png'
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      console.log('[SW] Pre-caching assets individually...');
      const cachePromises = ASSETS.map((asset) => {
        return cache.add(asset).catch((err) => {
          console.warn(`[SW] Failed to cache asset: ${asset}`, err);
        });
      });
      return Promise.all(cachePromises).then(() => {
        console.log('[SW] All assets caching complete. Skipping waiting.');
        self.skipWaiting();
      });
    })
  );
});

self.addEventListener('activate', (e) => {
  self.clients.claim();
  e.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME) {
            console.log('[SW] Deleting old cache:', key);
            return caches.delete(key);
          }
        })
      );
    })
  );
});

self.addEventListener('fetch', (e) => {
  // Handle API Requests
  if (e.request.url.includes('/api/')) {
    e.respondWith(
      fetch(e.request)
        .then((response) => {
          // Cache successful read-only API requests for offline fallback
          if (response.status === 200 && (e.request.url.includes('/api/levels') || e.request.url.includes('/api/users'))) {
            const responseToCache = response.clone();
            caches.open(CACHE_NAME).then((cache) => {
              cache.put(e.request, responseToCache);
            });
          }
          return response;
        })
        .catch(async (err) => {
          console.log('[SW] Offline API attempt:', e.request.url);

          // Serve cached read-only API responses
          if (e.request.url.includes('/api/levels') || e.request.url.includes('/api/users')) {
            const cachedResponse = await caches.match(e.request);
            if (cachedResponse) {
              return cachedResponse;
            }
          }

          // Intercept writes (progress save, unlock achievement, create user)
          // and return synthetic queued response to avoid exceptions
          if (e.request.url.includes('/api/progress') || e.request.url.includes('/api/achievements/unlock') || e.request.url.includes('/api/users/create')) {
            return new Response(JSON.stringify({ queued: true, status: 'offline' }), {
              headers: { 'Content-Type': 'application/json' }
            });
          }

          return new Response(JSON.stringify({ error: 'offline', message: 'Offline connection failure' }), {
            status: 503,
            headers: { 'Content-Type': 'application/json' }
          });
        })
    );
    return;
  }

  // Handle Static Asset Requests
  const isStaticAsset = (url) => {
    return url.match(/\.(js|css|woff2|svg|png|jpg|jpeg|json|gif)$/) && !url.includes('/api/');
  };

  if (isStaticAsset(e.request.url)) {
    // Cache-First Strategy
    e.respondWith(
      caches.match(e.request).then((cachedResponse) => {
        if (cachedResponse) {
          return cachedResponse;
        }
        return fetch(e.request).then((response) => {
          if (response && response.status === 200 && e.request.method === 'GET') {
            const responseToCache = response.clone();
            caches.open(CACHE_NAME).then((cache) => {
              cache.put(e.request, responseToCache);
            });
          }
          return response;
        });
      })
    );
    return;
  }

  // Navigation pages (/ or index.html) - Network-First with Fast Timeout
  e.respondWith(
    new Promise((resolve, reject) => {
      let timeoutId = setTimeout(() => {
        caches.match(e.request).then((cachedResponse) => {
          if (cachedResponse) {
            console.log('[SW] Network timeout, serving from cache:', e.request.url);
            resolve(cachedResponse);
          } else {
            reject(new Error('Network timeout and no cached page available'));
          }
        });
      }, 1500);

      fetch(e.request).then((response) => {
        clearTimeout(timeoutId);
        if (response && response.status === 200 && e.request.method === 'GET') {
          const responseToCache = response.clone();
          caches.open(CACHE_NAME).then((cache) => {
            cache.put(e.request, responseToCache);
          });
        }
        resolve(response);
      }).catch((err) => {
        clearTimeout(timeoutId);
        caches.match(e.request).then((cachedResponse) => {
          if (cachedResponse) {
            resolve(cachedResponse);
          } else {
            reject(new Error('Offline, no cached page available'));
          }
        });
      });
    })
  );
});
