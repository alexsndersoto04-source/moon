'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const ROOT = __dirname;
const mime = { '.html':'text/html; charset=utf-8', '.css':'text/css; charset=utf-8', '.js':'application/javascript; charset=utf-8', '.json':'application/json; charset=utf-8', '.webmanifest':'application/manifest+json', '.svg':'image/svg+xml', '.png':'image/png' };
function json(res, status, value) { res.writeHead(status, {'Content-Type':'application/json; charset=utf-8', 'Cache-Control':'no-store'}); res.end(JSON.stringify(value)); }

async function createApp(options = {}) {
  const key = options.key ?? process.env.MARILIANA_KEY ?? '';
  const production = options.production ?? process.env.NODE_ENV === 'production';
  const databaseUrl = options.databaseUrl ?? process.env.DATABASE_URL ?? '';
  if (production && (!key || key.length < 16)) throw Error('En producción se requiere MARILIANA_KEY con al menos 16 caracteres.');
  if (production && !databaseUrl) throw Error('En producción se requiere DATABASE_URL de PostgreSQL.');
  const store = options.store || (databaseUrl ? await require('./postgres-store').createPostgresStore(databaseUrl) : require('./sqlite-store'));
  function authorized(req) {
    if (!key) return true;
    const a = Buffer.from(req.headers['x-mariliana-key'] || ''), b = Buffer.from(key);
    return a.length === b.length && crypto.timingSafeEqual(a,b);
  }
  const server = http.createServer(async (req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Mariliana-Key');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
    const pathname = new URL(req.url, 'http://localhost').pathname;
    if (pathname === '/health' && req.method === 'GET') return json(res, 200, {status:'ok'});
    if (pathname.startsWith('/api/')) {
      if (!authorized(req)) return json(res, 401, {error:'Clave de acceso incorrecta.'});
      if (pathname === '/api/snapshot' && req.method === 'GET') {
        try { return json(res, 200, await store.snapshot()); }
        catch { return json(res, 503, {error:'Base de datos no disponible. Intenta de nuevo.'}); }
      }
      if (pathname === '/api/sync' && req.method === 'POST') {
        let input;
        try {
          let body = ''; for await (const chunk of req) { body += chunk; if (body.length > 1024*1024) throw Error('Solicitud demasiado grande.'); }
          input = JSON.parse(body);
          if (!Array.isArray(input.operations) || input.operations.length > 500) throw Error('Lista de operaciones inválida.');
        } catch (error) { return json(res, 400, {error:error.message}); }
        try {
          const results=[];
          // Preserve each device's order. If anything fails, return 503; the client retries
          // the batch, and previously committed operations are protected by receipts.
          for (const op of input.operations) results.push(await store.apply(op));
          return json(res, 200, {results, ...await store.snapshot()});
        } catch { return json(res, 503, {error:'Base de datos no disponible. Los cambios permanecen pendientes; intenta de nuevo.'}); }
      }
      return json(res, 404, {error:'No encontrado'});
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405); return res.end(); }
    const file = path.join(ROOT, 'public', pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, ''));
    if (!file.startsWith(path.join(ROOT,'public') + path.sep)) { res.writeHead(403); return res.end(); }
    fs.readFile(file, (err, bytes) => { if (err) { res.writeHead(404); return res.end('No encontrado'); } res.writeHead(200, {'Content-Type':mime[path.extname(file)] || 'application/octet-stream', 'Cache-Control':'no-cache', 'X-Content-Type-Options':'nosniff'}); res.end(req.method === 'HEAD' ? undefined : bytes); });
  });
  return {server, store};
}
if (require.main === module) {
  createApp().then(({server}) => {
    const port = Number(process.env.PORT || 3000);
    server.listen(port, '0.0.0.0', () => console.log(`Mariliana en puerto ${port}. ${process.env.MARILIANA_KEY ? 'Acceso protegido por clave.' : 'AVISO: sin clave; solo para pruebas en red confiable.'}`));
  }).catch(error => { console.error('No se pudo iniciar Mariliana:', error.message.replace(/postgres(?:ql)?:\/\/\S+/gi, '[dirección de base de datos oculta]')); process.exit(1); });
}
module.exports = {createApp};
