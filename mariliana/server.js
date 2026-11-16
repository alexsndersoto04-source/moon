'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');

const ROOT = __dirname;
const dataDir = process.env.MARILIANA_DATA || path.join(ROOT, 'data');
fs.mkdirSync(dataDir, { recursive: true });
const db = new DatabaseSync(path.join(dataDir, 'inventario.sqlite'));
db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS products (id TEXT PRIMARY KEY, name TEXT NOT NULL, barcode TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', material TEXT NOT NULL DEFAULT '', unit TEXT NOT NULL DEFAULT 'unidad', quantity INTEGER NOT NULL CHECK(quantity >= 0), minimum INTEGER NOT NULL DEFAULT 0, expiry TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '', createdAt TEXT NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS barcode_unique ON products(barcode) WHERE barcode <> '';
CREATE TABLE IF NOT EXISTS history (id TEXT PRIMARY KEY, productId TEXT NOT NULL, name TEXT NOT NULL, delta INTEGER NOT NULL, reason TEXT NOT NULL, date TEXT NOT NULL, actor TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS receipts (id TEXT PRIMARY KEY, result TEXT NOT NULL);`);
const all = (sql, ...args) => db.prepare(sql).all(...args);
const get = (sql, ...args) => db.prepare(sql).get(...args);
const run = (sql, ...args) => db.prepare(sql).run(...args);
const clean = (s, max = 120) => String(s ?? '').trim().slice(0, max);
const integer = (v, max = 1000000) => Number.isSafeInteger(v) && v >= 0 && v <= max;
const validDate = s => /^\d{4}-\d{2}-\d{2}$/.test(s);
const operationTime = op => { const time = Date.parse(op.at); return Number.isFinite(time) ? new Date(time).toISOString() : new Date().toISOString(); };
function snapshot() {
  return { products: all('SELECT * FROM products ORDER BY name COLLATE NOCASE'), history: all('SELECT * FROM history ORDER BY date DESC, rowid DESC LIMIT 10000') };
}
function apply(op) {
  if (!op || typeof op !== 'object' || !/^[\w-]{8,100}$/.test(op.id || '')) throw Error('Operación inválida.');
  const old = get('SELECT result FROM receipts WHERE id=?', op.id);
  if (old) return JSON.parse(old.result);
  let result;
  db.exec('BEGIN IMMEDIATE');
  try {
    if (op.type === 'create' || op.type === 'edit') {
      const p = op.product || {};
      const id = clean(p.id, 100), name = clean(p.name), barcode = clean(p.barcode, 100);
      if (!/^[\w-]{8,100}$/.test(id) || !name || !integer(p.minimum) || (p.expiry && !validDate(p.expiry))) throw Error('Revisa el nombre, la cantidad mínima o la fecha.');
      if (barcode && get('SELECT id FROM products WHERE barcode=? AND id<>?', barcode, id)) throw Error('Este código de barras ya pertenece a otro producto.');
      const fields = [name, barcode, clean(p.category), clean(p.material), clean(p.unit) || 'unidad', p.minimum, clean(p.expiry, 10), clean(p.notes, 500)];
      if (op.type === 'create') {
        if (!integer(p.quantity)) throw Error('Cantidad inicial inválida.');
        run('INSERT INTO products (id,name,barcode,category,material,unit,minimum,expiry,notes,quantity,createdAt) VALUES (?,?,?,?,?,?,?,?,?,?,?)', id, ...fields, p.quantity, new Date().toISOString());
        if (p.quantity) run('INSERT INTO history VALUES (?,?,?,?,?,?,?)', op.id, id, name, p.quantity, 'Inventario inicial', new Date().toISOString(), clean(op.actor));
      } else {
        if (!get('SELECT id FROM products WHERE id=?', id)) throw Error('El producto ya no existe.');
        run('UPDATE products SET name=?,barcode=?,category=?,material=?,unit=?,minimum=?,expiry=?,notes=? WHERE id=?', ...fields, id);
      }
    } else if (op.type === 'adjust') {
      const p = get('SELECT * FROM products WHERE id=?', clean(op.productId, 100));
      if (!p) throw Error('El producto ya no existe.');
      if (!Number.isSafeInteger(op.delta) || op.delta === 0 || Math.abs(op.delta) > 1000000) throw Error('Cantidad inválida.');
      if (p.quantity + op.delta < 0) throw Error(`No hay suficientes unidades de ${p.name}; quedan ${p.quantity}.`);
      run('UPDATE products SET quantity=quantity+? WHERE id=?', op.delta, p.id);
      run('INSERT INTO history VALUES (?,?,?,?,?,?,?)', op.id, p.id, p.name, op.delta, clean(op.reason, 100) || (op.delta < 0 ? 'Salida' : 'Entrada'), operationTime(op), clean(op.actor));
    } else if (op.type === 'clearHistory') {
      if (!validDate(op.day)) throw Error('Fecha inválida.');
      // Dates are stored in UTC. The client sends the exact local-day UTC boundaries.
      const start = Date.parse(op.start), end = Date.parse(op.end);
      if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || end - start > 90000000) throw Error('Intervalo de fecha inválido.');
      run('DELETE FROM history WHERE date>=? AND date<? AND delta<0', new Date(start).toISOString(), new Date(end).toISOString());
    } else throw Error('Operación desconocida.');
    result = { id: op.id, ok: true };
    run('INSERT INTO receipts VALUES (?,?)', op.id, JSON.stringify(result));
    db.exec('COMMIT');
  } catch (err) {
    db.exec('ROLLBACK');
    result = { id: op.id, ok: false, error: err.message.includes('UNIQUE constraint') ? 'El producto o código ya existe.' : err.message };
    run('INSERT OR IGNORE INTO receipts VALUES (?,?)', op.id, JSON.stringify(result));
  }
  return result;
}
const mime = { '.html':'text/html; charset=utf-8', '.css':'text/css; charset=utf-8', '.js':'application/javascript; charset=utf-8', '.json':'application/json; charset=utf-8', '.webmanifest':'application/manifest+json', '.svg':'image/svg+xml', '.png':'image/png' };
const key = process.env.MARILIANA_KEY || '';
function authorized(req) {
  if (!key) return true;
  const a = Buffer.from(req.headers['x-mariliana-key'] || ''), b = Buffer.from(key);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
function json(res, status, value) { res.writeHead(status, {'Content-Type':'application/json; charset=utf-8', 'Cache-Control':'no-store'}); res.end(JSON.stringify(value)); }
const server = http.createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Mariliana-Key');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
  const pathname = new URL(req.url, 'http://localhost').pathname;
  if (pathname.startsWith('/api/')) {
    if (!authorized(req)) return json(res, 401, {error:'Clave de acceso incorrecta.'});
    if (pathname === '/api/snapshot' && req.method === 'GET') return json(res, 200, snapshot());
    if (pathname === '/api/sync' && req.method === 'POST') {
      try {
        let body = ''; for await (const chunk of req) { body += chunk; if (body.length > 1024*1024) throw Error('Solicitud demasiado grande.'); }
        const input = JSON.parse(body); if (!Array.isArray(input.operations) || input.operations.length > 500) throw Error('Lista de operaciones inválida.');
        const results = input.operations.map(op => { try { return apply(op); } catch (e) { return { id: op?.id, ok: false, error: e.message }; } });
        return json(res, 200, {results, ...snapshot()});
      } catch (e) { return json(res, 400, {error:e.message}); }
    }
    return json(res, 404, {error:'No encontrado'});
  }
  if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405); return res.end(); }
  let file = pathname === '/vendor/html5-qrcode.min.js' ? path.join(ROOT, 'node_modules/html5-qrcode/html5-qrcode.min.js') : path.join(ROOT, 'public', pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, ''));
  if (!file.startsWith(path.join(ROOT,'public') + path.sep) && file !== path.join(ROOT, 'node_modules/html5-qrcode/html5-qrcode.min.js')) { res.writeHead(403); return res.end(); }
  fs.readFile(file, (err, bytes) => { if (err) { res.writeHead(404); return res.end('No encontrado'); } res.writeHead(200, {'Content-Type':mime[path.extname(file)] || 'application/octet-stream', 'Cache-Control':'no-cache', 'X-Content-Type-Options':'nosniff'}); res.end(req.method === 'HEAD' ? undefined : bytes); });
});
if (require.main === module) {
  const port = Number(process.env.PORT || 3000);
  server.listen(port, '0.0.0.0', () => console.log(`Mariliana en puerto ${port}. ${key ? 'Acceso protegido por clave.' : 'AVISO: sin clave; solo para pruebas en red confiable.'}`));
}
module.exports = { server, db, apply, snapshot };
