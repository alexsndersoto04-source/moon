'use strict';

/*
 * Strata Engineering — Enterprise Workspace Filesystem Engine.
 * Paths are strictly confined to STRATA_WORKSPACE; symlinks and internal snapshot/state
 * directories are never exposed through user or agent operations.
 * Includes per-path concurrency locking for safe parallel multi-agent edits.
 */
const fs = require('node:fs');
const fsp = fs.promises;
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

const ROOT = path.resolve(process.env.STRATA_WORKSPACE || path.join(__dirname, '..', 'workspace'));
const SNAP_DIR = path.join(ROOT, '.strata-snapshots');
const STATE_DIR = path.join(ROOT, '.strata-state');
const MAX_READ = 16 * 1024 * 1024;
const MAX_WRITE = 32 * 1024 * 1024;
const MAX_SNAPSHOT_BYTES = Math.max(32, Number(process.env.STRATA_MAX_SNAPSHOT_MB) || 512) * 1024 * 1024;
const MAX_SNAPSHOTS = 20;
const MAX_TREE_NODES = 50000;

const IGNORED = new Set([
  'node_modules', '.git', '.hg', '.svn', '.cache', 'dist', 'build', 'out',
  '__pycache__', '.venv', 'venv', '.idea', '.next', '.nuxt', '.output',
  '.turbo', 'coverage', 'target', '.strata-snapshots', '.strata-state'
]);
const BINARY = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.bmp', '.tiff', '.pdf',
  '.zip', '.gz', '.bz2', '.xz', '.7z', '.rar', '.tar', '.exe', '.dll', '.so',
  '.dylib', '.class', '.pyc', '.o', '.a', '.wasm', '.mp3', '.mp4', '.mov',
  '.avi', '.wav', '.ogg', '.woff', '.woff2', '.ttf', '.eot', '.db', '.sqlite',
  '.jar', '.psd', '.sketch', '.heic'
]);

let rootReal = null;
let snapshotQueue = Promise.resolve();
const fileLocks = new Map();

function fail(message, status = 400) {
  return Object.assign(new Error(message), { status });
}

function withFileLock(key, fn) {
  const prev = fileLocks.get(key) || Promise.resolve();
  const next = prev.then(fn, fn);
  const cleanup = next.then(() => {}, () => {}).finally(() => {
    if (fileLocks.get(key) === cleanup) fileLocks.delete(key);
  });
  fileLocks.set(key, cleanup);
  return next;
}

function isWithin(base, candidate) {
  const rel = path.relative(base, candidate);
  return rel === '' || (!rel.startsWith('..' + path.sep) && rel !== '..' && !path.isAbsolute(rel));
}

/** Resolve a user path lexically. Reject absolute paths and parent traversal. */
function safe(rel) {
  const value = String(rel == null ? '' : rel);
  if (value.includes('\0')) throw fail('La ruta contiene un carácter no válido.');
  const normalized = value.replace(/\\/g, '/');
  if (/^(?:[a-zA-Z]:|\/)/.test(normalized)) throw fail('Usa una ruta relativa al workspace.', 403);
  const parts = normalized.split('/').filter((part) => part && part !== '.');
  if (parts.some((part) => part === '..')) throw fail('La ruta no puede salir del workspace.', 403);
  const abs = path.resolve(ROOT, ...parts);
  if (!isWithin(ROOT, abs)) throw fail('La ruta no puede salir del workspace.', 403);
  return abs;
}

function relOf(abs) {
  return path.relative(ROOT, abs).split(path.sep).join('/');
}

function isInternalPath(abs) {
  return abs === SNAP_DIR || isWithin(SNAP_DIR, abs) || abs === STATE_DIR || isWithin(STATE_DIR, abs);
}

function assertUserPath(abs) {
  if (isInternalPath(abs)) throw fail('La carpeta interna del sistema no se puede modificar.', 403);
}

async function ensure() {
  await fsp.mkdir(ROOT, { recursive: true });
  const st = await fsp.lstat(ROOT);
  if (st.isSymbolicLink() || !st.isDirectory()) throw fail('STRATA_WORKSPACE debe ser un directorio real.', 500);
  rootReal = await fsp.realpath(ROOT);
  return ROOT;
}

async function resolveUserPath(rel, options = {}) {
  await ensure();
  const abs = safe(rel);
  assertUserPath(abs);
  const allowMissing = options.allowMissing !== false;
  const allowRoot = options.allowRoot === true;
  if (abs === ROOT) {
    if (!allowRoot) throw fail('La raíz del workspace no es una ruta de archivo.', 400);
    return abs;
  }

  const relative = path.relative(ROOT, abs);
  const parts = relative.split(path.sep).filter(Boolean);
  let current = ROOT;
  for (let i = 0; i < parts.length; i++) {
    current = path.join(current, parts[i]);
    let st;
    try {
      st = await fsp.lstat(current);
    } catch (error) {
      if (error.code === 'ENOENT' && allowMissing) break;
      if (error.code === 'ENOENT') throw fail('No existe: ' + rel, 404);
      throw error;
    }
    if (st.isSymbolicLink()) throw fail('No se permiten enlaces simbólicos en el workspace.', 403);
    const real = await fsp.realpath(current);
    if (!isWithin(rootReal, real)) throw fail('La ruta sale del workspace.', 403);
    if (i < parts.length - 1 && !st.isDirectory()) throw fail('Un componente de la ruta no es una carpeta.', 400);
  }
  return abs;
}

function visible(name) {
  return !IGNORED.has(name) && name !== '.DS_Store' && name !== 'Thumbs.db';
}

async function isBinary(abs) {
  if (BINARY.has(path.extname(abs).toLowerCase())) return true;
  let fh;
  try {
    fh = await fsp.open(abs, 'r');
    const buf = Buffer.alloc(4096);
    const { bytesRead } = await fh.read(buf, 0, buf.length, 0);
    return buf.subarray(0, bytesRead).includes(0);
  } catch {
    return false;
  } finally {
    await fh?.close().catch(() => {});
  }
}

async function tree(dirRel = '', depth = 14) {
  const base = await resolveUserPath(dirRel || '', { allowRoot: true, allowMissing: false });
  const st = await fsp.stat(base);
  if (!st.isDirectory()) throw fail('La ruta no es una carpeta: ' + (dirRel || '.'), 400);
  const parsedDepth = Number.parseInt(depth, 10);
  const maxDepth = Math.min(Math.max(Number.isFinite(parsedDepth) ? parsedDepth : 14, 0), 25);
  let nodes = 0;

  async function walkDir(dir, level) {
    if (level > maxDepth || nodes >= MAX_TREE_NODES) return [];
    let entries;
    try { entries = await fsp.readdir(dir, { withFileTypes: true }); } catch { return []; }
    entries.sort((a, b) => {
      if (a.isDirectory() !== b.isDirectory()) return a.isDirectory() ? -1 : 1;
      return a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' });
    });
    const out = [];
    for (const entry of entries) {
      if (!visible(entry.name) || entry.isSymbolicLink() || nodes >= MAX_TREE_NODES) continue;
      const abs = path.join(dir, entry.name);
      nodes++;
      if (entry.isDirectory()) {
        out.push({ name: entry.name, path: relOf(abs), type: 'dir', children: await walkDir(abs, level + 1) });
      } else if (entry.isFile()) {
        let size = 0;
        try { size = (await fsp.stat(abs)).size; } catch { /* concurrently removed */ }
        out.push({ name: entry.name, path: relOf(abs), type: 'file', size });
      }
    }
    return out;
  }
  return walkDir(base, 0);
}

async function read(rel, opts = {}) {
  const abs = await resolveUserPath(rel, { allowMissing: false });
  const st = await fsp.stat(abs);
  if (!st.isFile()) throw fail('No es un archivo: ' + rel, 400);
  if (st.size > MAX_READ) throw fail('Archivo demasiado grande (' + st.size + ' bytes; máximo ' + MAX_READ + ').', 413);
  if (await isBinary(abs)) throw fail('El archivo es binario y no se puede abrir como texto.', 415);
  const buffer = await fsp.readFile(abs);
  const content = buffer.toString('utf8');
  const lines = content.split('\n');
  const totalLines = lines.length;
  const version = crypto.createHash('sha256').update(buffer).digest('hex');
  if (opts.startLine != null || opts.endLine != null) {
    const startLine = Math.min(Math.max(Number.parseInt(opts.startLine, 10) || 1, 1), totalLines);
    const endLine = Math.min(Math.max(Number.parseInt(opts.endLine, 10) || totalLines, startLine), totalLines);
    const excerpt = lines.slice(startLine - 1, endLine).map((line, index) => `${startLine + index}\t${line}`).join('\n');
    return { path: relOf(abs), content: excerpt, totalLines, startLine, endLine, truncated: startLine !== 1 || endLine !== totalLines, version, bytes: st.size };
  }
  return { path: relOf(abs), content, totalLines, version, bytes: st.size };
}

async function readMany(paths = [], maxLinesPerFile = 220) {
  const list = Array.isArray(paths) ? paths.slice(0, 20) : [];
  const cap = Math.min(Math.max(Number(maxLinesPerFile) || 220, 20), 1000);
  const results = [];
  for (const rel of list) {
    try {
      const data = await read(rel, { startLine: 1, endLine: cap });
      results.push({ ok: true, ...data });
    } catch (error) {
      results.push({ ok: false, path: String(rel), error: error.message });
    }
  }
  return results;
}

async function mkdir(rel) {
  const abs = await resolveUserPath(rel, { allowRoot: true, allowMissing: true });
  if (abs === ROOT) return { path: '.', type: 'dir' };
  const parts = path.relative(ROOT, abs).split(path.sep).filter(Boolean);
  let current = ROOT;
  for (const part of parts) {
    current = path.join(current, part);
    try {
      const st = await fsp.lstat(current);
      if (st.isSymbolicLink()) throw fail('No se permiten enlaces simbólicos en el workspace.', 403);
      if (!st.isDirectory()) throw fail('Ya existe un archivo en la ruta: ' + rel, 409);
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      await fsp.mkdir(current);
    }
  }
  return { path: relOf(abs), type: 'dir' };
}

async function writeUnlocked(rel, content, options = {}) {
  const abs = await resolveUserPath(rel, { allowMissing: true });
  if (abs === ROOT) throw fail('Ruta inválida.', 400);
  const text = content == null ? '' : String(content);
  const data = Buffer.from(text, 'utf8');
  if (data.length > MAX_WRITE) throw fail('El archivo supera el límite de escritura (' + MAX_WRITE + ' bytes).', 413);
  await mkdir(relOf(path.dirname(abs)) || '.');
  await resolveUserPath(rel, { allowMissing: true });
  let existing = null;
  try { existing = await fsp.lstat(abs); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (existing?.isSymbolicLink()) throw fail('No se permiten enlaces simbólicos en el workspace.', 403);
  if (existing && !existing.isFile()) throw fail('La ruta de destino no es un archivo.', 400);
  const mode = existing ? (existing.mode & 0o777) : 0o644;

  if (options.expectedVersion != null) {
    const actual = await fsp.readFile(abs).then((buf) => crypto.createHash('sha256').update(buf).digest('hex')).catch((error) => {
      if (error.code === 'ENOENT') return null;
      throw error;
    });
    if (actual !== options.expectedVersion) throw fail('El archivo cambió en el servidor desde que lo abriste. Recárgalo antes de guardar para no sobrescribir cambios.', 409);
  }

  const temp = path.join(path.dirname(abs), `.strata-write-${process.pid}-${crypto.randomBytes(8).toString('hex')}.tmp`);
  let handle;
  try {
    handle = await fsp.open(temp, 'wx', mode);
    await handle.writeFile(data);
    await handle.sync();
    await handle.close();
    handle = null;
    await resolveUserPath(rel, { allowMissing: true });
    await fsp.rename(temp, abs);
  } catch (error) {
    await handle?.close().catch(() => {});
    await fsp.rm(temp, { force: true }).catch(() => {});
    throw error;
  }
  const st = await fsp.stat(abs);
  return {
    path: relOf(abs), bytes: st.size, lines: text.split('\n').length,
    version: crypto.createHash('sha256').update(data).digest('hex')
  };
}

async function write(rel, content, options = {}) {
  const abs = safe(rel);
  return withFileLock(abs, () => writeUnlocked(rel, content, options));
}

async function remove(rel) {
  const abs = await resolveUserPath(rel, { allowMissing: false });
  if (abs === ROOT) throw fail('No se puede borrar la raíz del workspace.', 400);
  return withFileLock(abs, async () => {
    await fsp.rm(abs, { recursive: true, force: false });
    return { path: relOf(abs), deleted: true };
  });
}

async function move(from, to) {
  const source = await resolveUserPath(from, { allowMissing: false });
  const target = await resolveUserPath(to, { allowMissing: true });
  if (source === ROOT || target === ROOT) throw fail('No se puede mover la raíz del workspace.', 400);
  if (source === target) return { from: relOf(source), to: relOf(target) };
  const sourceStat = await fsp.stat(source);
  if (sourceStat.isDirectory() && isWithin(source, target)) throw fail('No se puede mover una carpeta dentro de sí misma.', 400);
  await mkdir(relOf(path.dirname(target)) || '.');
  try { await fsp.lstat(target); throw fail('Ya existe un elemento en el destino.', 409); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  await fsp.rename(source, target);
  return { from: relOf(source), to: relOf(target) };
}

async function edit(rel, search, replace, all = false) {
  const abs = safe(rel);
  return withFileLock(abs, async () => {
    const needle = String(search == null ? '' : search);
    if (!needle) throw fail('El fragmento de búsqueda está vacío.');
    const { content, version } = await read(rel);
    const count = content.split(needle).length - 1;
    if (!count) throw fail('No se encontró el fragmento en ' + rel + '.', 404);
    if (count > 1 && !all) throw fail('El fragmento aparece ' + count + ' veces. Añade contexto o activa all=true.', 409);
    const next = all ? content.split(needle).join(String(replace == null ? '' : replace)) : content.replace(needle, String(replace == null ? '' : replace));
    await writeUnlocked(rel, next, { expectedVersion: version });
    return { path: rel, replacements: all ? count : 1 };
  });
}

async function patchLines(rel, startLine, endLine, newContent) {
  const abs = safe(rel);
  return withFileLock(abs, async () => {
    const { content, version } = await read(rel);
    const lines = content.split('\n');
    const start = Math.max(1, Number.parseInt(startLine, 10) || 1);
    const end = Math.min(lines.length, Math.max(start, Number.parseInt(endLine, 10) || start));
    const replacementLines = newContent === '' ? [] : String(newContent == null ? '' : newContent).split('\n');
    lines.splice(start - 1, end - start + 1, ...replacementLines);
    const result = await writeUnlocked(rel, lines.join('\n'), { expectedVersion: version });
    return { ...result, patchedRange: `${start}-${end}`, newLineCount: replacementLines.length };
  });
}

async function* walk(dirRel = '') {
  const base = await resolveUserPath(dirRel || '', { allowRoot: true, allowMissing: false });
  const stack = [base];
  while (stack.length) {
    const dir = stack.pop();
    let entries;
    try { entries = await fsp.readdir(dir, { withFileTypes: true }); } catch { continue; }
    for (const entry of entries) {
      if (!visible(entry.name) || entry.isSymbolicLink()) continue;
      const abs = path.join(dir, entry.name);
      if (entry.isDirectory()) stack.push(abs);
      else if (entry.isFile()) yield abs;
    }
  }
}

function tryRipgrep(baseAbs, query, opts, limit) {
  return new Promise((resolve) => {
    const args = ['--line-number', '--no-heading', '--color', 'never', '--max-filesize', '2M'];
    if (!opts.regex) args.push('--fixed-strings');
    if (!opts.caseSensitive) args.push('--ignore-case');
    if (opts.glob) args.push('--glob', String(opts.glob));
    for (const ignored of IGNORED) args.push('--glob', `!${ignored}/**`);
    args.push('--', String(query), baseAbs);

    let child;
    try {
      child = spawn('rg', args, { cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    } catch { return resolve(null); }

    let out = '';
    let err = '';
    const timer = setTimeout(() => { try { child.kill('SIGKILL'); } catch {} resolve(null); }, 8000);
    child.stdout.on('data', (chunk) => { if (out.length < 2 * 1024 * 1024) out += chunk.toString('utf8'); });
    child.stderr.on('data', (chunk) => { if (err.length < 16384) err += chunk.toString('utf8'); });
    child.once('error', () => { clearTimeout(timer); resolve(null); });
    child.once('close', (code) => {
      clearTimeout(timer);
      if (code === 2) return resolve(null); // invalid regex or rg error -> fallback
      const results = [];
      const scannedFiles = new Set();
      const lines = out.split('\n');
      for (const raw of lines) {
        if (!raw) continue;
        const firstColon = raw.indexOf(':');
        if (firstColon < 0) continue;
        const secondColon = raw.indexOf(':', firstColon + 1);
        if (secondColon < 0) continue;
        const fileAbs = raw.slice(0, firstColon);
        const lineNum = Number.parseInt(raw.slice(firstColon + 1, secondColon), 10);
        if (!Number.isFinite(lineNum)) continue;
        const rel = relOf(path.resolve(ROOT, fileAbs));
        if (rel.startsWith('..') || rel.startsWith('.strata-')) continue;
        scannedFiles.add(rel);
        results.push({ file: rel, line: lineNum, text: raw.slice(secondColon + 1).slice(0, 500) });
        if (results.length >= limit) break;
      }
      resolve({ query: String(query), results, scanned: scannedFiles.size, truncated: results.length >= limit, engine: 'ripgrep' });
    });
  });
}

async function grep(query, opts = {}) {
  if (query == null || String(query) === '') throw fail('La consulta de búsqueda está vacía.');
  const limit = Math.min(Math.max(Number.parseInt(opts.maxResults, 10) || 250, 1), 2000);
  if (opts.regex) {
    try { new RegExp(String(query), opts.caseSensitive ? '' : 'i'); }
    catch (error) { throw fail('Expresión regular inválida: ' + error.message); }
  }
  const base = opts.dir ? opts.dir : '';
  const baseAbs = await resolveUserPath(base, { allowRoot: true, allowMissing: false });

  // Use ripgrep when available for enterprise-scale codebases, unless exact scanned count is tiny
  const rgResult = await tryRipgrep(baseAbs, query, opts, limit);
  if (rgResult && (rgResult.results.length > 0 || opts.fast !== false)) {
    // If rg returned 0 matches, still confirm quickly or return cleanly
    if (rgResult.results.length > 0) return rgResult;
  }

  let test;
  if (opts.regex) {
    const re = new RegExp(String(query), opts.caseSensitive ? '' : 'i');
    test = (line) => re.test(line);
  } else {
    const needle = opts.caseSensitive ? String(query) : String(query).toLocaleLowerCase();
    test = (line) => (opts.caseSensitive ? line : line.toLocaleLowerCase()).includes(needle);
  }

  const results = [];
  let scanned = 0;
  for await (const abs of walk(base)) {
    if (results.length >= limit) break;
    if (BINARY.has(path.extname(abs).toLowerCase())) continue;
    if (opts.glob) {
      const pattern = String(opts.glob).replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.');
      if (!new RegExp('^' + pattern + '$', 'i').test(path.basename(abs))) continue;
    }
    let text;
    try {
      const st = await fsp.stat(abs);
      if (st.size > 2 * 1024 * 1024) continue;
      text = await fsp.readFile(abs, 'utf8');
    } catch { continue; }
    if (text.includes('\0')) continue;
    scanned++;
    const lines = text.split('\n');
    for (let index = 0; index < lines.length; index++) {
      if (test(lines[index])) {
        results.push({ file: relOf(abs), line: index + 1, text: lines[index].slice(0, 500) });
        if (results.length >= limit) break;
      }
    }
  }
  return { query: String(query), results, scanned, truncated: results.length >= limit, engine: 'node' };
}

async function find(namePart) {
  const needle = String(namePart || '').toLocaleLowerCase();
  if (!needle) throw fail('Indica parte del nombre.');
  const out = [];
  for await (const abs of walk('')) {
    if (path.basename(abs).toLocaleLowerCase().includes(needle)) {
      out.push(relOf(abs));
      if (out.length >= 1000) break;
    }
  }
  return out;
}

async function treeText(maxNodes = 400) {
  const nodes = await tree('', 10);
  const out = [];
  const limit = Math.min(Math.max(Number(maxNodes) || 400, 1), 2000);
  function flatten(items, prefix = '') {
    for (const item of items) {
      if (out.length >= limit) return;
      out.push(prefix + item.name + (item.type === 'dir' ? '/' : ''));
      if (item.type === 'dir' && item.children?.length) flatten(item.children, prefix + '  ');
    }
  }
  flatten(nodes);
  if (out.length >= limit) out.push('…');
  return out.join('\n');
}

async function stats(dirRel = '') {
  let totalFiles = 0;
  let totalBytes = 0;
  let totalLines = 0;
  const byExt = new Map();
  const largest = [];
  for await (const abs of walk(dirRel)) {
    totalFiles++;
    let st;
    try { st = await fsp.stat(abs); } catch { continue; }
    totalBytes += st.size;
    const ext = path.extname(abs).toLowerCase() || '(sin ext)';
    const bucket = byExt.get(ext) || { ext, files: 0, bytes: 0, lines: 0 };
    bucket.files++;
    bucket.bytes += st.size;
    let lines = 0;
    if (st.size <= 1024 * 1024 && !(await isBinary(abs))) {
      try {
        const text = await fsp.readFile(abs, 'utf8');
        lines = text.split('\n').length;
        bucket.lines += lines;
        totalLines += lines;
      } catch { /* ignore */ }
    }
    byExt.set(ext, bucket);
    largest.push({ path: relOf(abs), bytes: st.size, lines });
  }
  largest.sort((a, b) => b.lines - a.lines || b.bytes - a.bytes);
  const languages = [...byExt.values()].sort((a, b) => b.lines - a.lines || b.files - a.files);
  return { totalFiles, totalBytes, totalLines, languages: languages.slice(0, 20), largestFiles: largest.slice(0, 15) };
}

async function importFiles(files = []) {
  if (!Array.isArray(files) || !files.length) throw fail('No se recibieron archivos para importar.');
  if (files.length > 200) throw fail('Máximo 200 archivos por importación.');
  const imported = [];
  for (const item of files) {
    if (!item || !item.path) continue;
    const written = await write(String(item.path), item.content == null ? '' : String(item.content));
    imported.push(written.path);
  }
  return { count: imported.length, files: imported };
}

async function exportBundle(dirRel = '') {
  const files = [];
  let bytes = 0;
  const maxTotal = 20 * 1024 * 1024;
  for await (const abs of walk(dirRel)) {
    if (await isBinary(abs)) continue;
    let st;
    try { st = await fsp.stat(abs); } catch { continue; }
    if (st.size > 2 * 1024 * 1024 || bytes + st.size > maxTotal) continue;
    try {
      const content = await fsp.readFile(abs, 'utf8');
      bytes += st.size;
      files.push({ path: relOf(abs), bytes: st.size, content });
    } catch { /* skip unreadable */ }
  }
  return { exportedAt: new Date().toISOString(), workspace: path.basename(ROOT), fileCount: files.length, totalBytes: bytes, files };
}

const TEMPLATES = {
  'node-enterprise-api': {
    label: 'API Empresarial Node.js + Pruebas',
    files: {
      'package.json': JSON.stringify({
        name: 'enterprise-api-service',
        version: '1.0.0',
        private: true,
        scripts: { start: 'node src/server.js', test: 'node --test test/*.test.js' }
      }, null, 2) + '\n',
      'src/store.js': `'use strict';\n\nclass InMemoryRepository {\n  constructor() {\n    this.items = new Map();\n    this.seq = 1;\n  }\n  list() {\n    return [...this.items.values()];\n  }\n  get(id) {\n    return this.items.get(String(id)) || null;\n  }\n  create(payload) {\n    if (!payload || !String(payload.title || '').trim()) {\n      throw Object.assign(new Error('El campo title es obligatorio.'), { status: 400 });\n    }\n    const id = String(this.seq++);\n    const record = {\n      id,\n      title: String(payload.title).trim(),\n      status: payload.status || 'active',\n      createdAt: new Date().toISOString()\n    };\n    this.items.set(id, record);\n    return record;\n  }\n  remove(id) {\n    return this.items.delete(String(id));\n  }\n}\n\nmodule.exports = { InMemoryRepository };\n`,
      'src/server.js': `'use strict';\n\nconst http = require('node:http');\nconst { InMemoryRepository } = require('./store');\n\nfunction createServer(repo = new InMemoryRepository()) {\n  return http.createServer((req, res) => {\n    const url = new URL(req.url, 'http://localhost');\n    const send = (status, body) => {\n      res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });\n      res.end(JSON.stringify(body));\n    };\n    if (req.method === 'GET' && url.pathname === '/health') return send(200, { ok: true, service: 'enterprise-api' });\n    if (req.method === 'GET' && url.pathname === '/items') return send(200, { ok: true, items: repo.list() });\n    if (req.method === 'POST' && url.pathname === '/items') {\n      let raw = '';\n      req.on('data', (chunk) => { raw += chunk; });\n      req.on('end', () => {\n        try {\n          const data = JSON.parse(raw || '{}');\n          return send(201, { ok: true, item: repo.create(data) });\n        } catch (err) {\n          return send(err.status || 400, { ok: false, error: err.message });\n        }\n      });\n      return;\n    }\n    return send(404, { ok: false, error: 'Ruta no encontrada' });\n  });\n}\n\nif (require.main === module) {\n  const port = Number(process.env.PORT || 3000);\n  createServer().listen(port, '0.0.0.0', () => console.log('API lista en puerto', port));\n}\n\nmodule.exports = { createServer };\n`,
      'test/api.test.js': `'use strict';\n\nconst test = require('node:test');\nconst assert = require('node:assert/strict');\nconst { createServer } = require('../src/server');\n\ntest('API CRUD y health check', async () => {\n  const server = createServer();\n  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));\n  const base = \`http://127.0.0.1:\${server.address().port}\`;\n  try {\n    const health = await (await fetch(\`\${base}/health\`)).json();\n    assert.equal(health.ok, true);\n    const created = await fetch(\`\${base}/items\`, {\n      method: 'POST',\n      headers: { 'content-type': 'application/json' },\n      body: JSON.stringify({ title: 'Orden #1001' })\n    });\n    assert.equal(created.status, 201);\n    const body = await created.json();\n    assert.equal(body.item.title, 'Orden #1001');\n    const list = await (await fetch(\`\${base}/items\`)).json();\n    assert.equal(list.items.length, 1);\n  } finally {\n    server.close();\n  }\n});\n`
    }
  },
  'python-service': {
    label: 'Servicio Python Modular + Unittest',
    files: {
      'src/__init__.py': '',
      'src/engine.py': `"""Motor de reglas y procesamiento de datos empresarial."""\nfrom dataclasses import dataclass\nfrom typing import List, Dict\n\n\n@dataclass\nclass Transaction:\n    id: str\n    amount: float\n    currency: str = "USD"\n    category: str = "general"\n\n\ndef summarize_transactions(items: List[Transaction]) -> Dict[str, float]:\n    totals: Dict[str, float] = {}\n    for tx in items:\n        if tx.amount < 0:\n            raise ValueError(f"Monto inválido en transacción {tx.id}")\n        totals[tx.category] = round(totals.get(tx.category, 0.0) + tx.amount, 2)\n    return totals\n\n\nif __name__ == "__main__":\n    sample = [\n        Transaction("TX-1", 1250.50, "USD", "infraestructura"),\n        Transaction("TX-2", 420.25, "USD", "operaciones"),\n        Transaction("TX-3", 310.00, "USD", "infraestructura"),\n    ]\n    print("Resumen:", summarize_transactions(sample))\n`,
      'tests/test_engine.py': `import unittest\nfrom src.engine import Transaction, summarize_transactions\n\n\nclass TestEngine(unittest.TestCase):\n    def test_summarize_by_category(self):\n        items = [\n            Transaction("1", 100.0, "USD", "cloud"),\n            Transaction("2", 50.25, "USD", "cloud"),\n            Transaction("3", 20.0, "USD", "ops"),\n        ]\n        result = summarize_transactions(items)\n        self.assertEqual(result["cloud"], 150.25)\n        self.assertEqual(result["ops"], 20.0)\n\n    def test_rejects_negative_amount(self):\n        with self.assertRaises(ValueError):\n            summarize_transactions([Transaction("bad", -10.0)])\n\n\nif __name__ == "__main__":\n    unittest.main()\n`
    }
  },
  'fullstack-web': {
    label: 'Aplicación Web Full-Stack Completa',
    files: {
      'package.json': JSON.stringify({
        name: 'fullstack-enterprise-app',
        version: '1.0.0',
        private: true,
        scripts: { start: 'node server.js', test: 'node --test test/app.test.js' }
      }, null, 2) + '\n',
      'server.js': `'use strict';\nconst http = require('node:http');\nconst fs = require('node:fs');\nconst path = require('node:path');\n\nconst tasks = [{ id: 1, title: 'Arquitectura inicial verificada', done: true }];\n\nfunction createApp() {\n  return http.createServer((req, res) => {\n    if (req.url === '/' && req.method === 'GET') {\n      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });\n      return res.end(fs.readFileSync(path.join(__dirname, 'public', 'index.html'), 'utf8'));\n    }\n    if (req.url === '/api/tasks' && req.method === 'GET') {\n      res.writeHead(200, { 'content-type': 'application/json' });\n      return res.end(JSON.stringify({ tasks }));\n    }\n    res.writeHead(404);\n    res.end('Not Found');\n  });\n}\n\nif (require.main === module) {\n  const port = Number(process.env.PORT || 3001);\n  createApp().listen(port, '0.0.0.0', () => console.log('Web App en puerto', port));\n}\nmodule.exports = { createApp };\n`,
      'public/index.html': `<!doctype html>\n<html lang="es">\n<head>\n  <meta charset="utf-8">\n  <meta name="viewport" content="width=device-width,initial-scale=1">\n  <title>Portal Operativo</title>\n  <style>body{font-family:system-ui,sans-serif;max-width:720px;margin:40px auto;padding:0 16px;color:#0f172a;background:#f8fafc}h1{font-size:22px}.card{background:#fff;border:1px solid #e2e8f0;border-radius:8px;padding:16px}</style>\n</head>\n<body>\n  <h1>Portal Operativo Empresarial</h1>\n  <div class="card" id="list">Cargando tareas…</div>\n  <script>fetch('/api/tasks').then(r=>r.json()).then(d=>{document.getElementById('list').textContent=d.tasks.map(t=>t.title).join(' · ');});</script>\n</body>\n</html>\n`,
      'test/app.test.js': `'use strict';\nconst test = require('node:test');\nconst assert = require('node:assert/strict');\nconst { createApp } = require('../server');\n\ntest('sirve HTML y API', async () => {\n  const s = createApp();\n  await new Promise((r) => s.listen(0, '127.0.0.1', r));\n  const base = \`http://127.0.0.1:\${s.address().port}\`;\n  try {\n    const html = await (await fetch(base)).text();\n    assert.match(html, /Portal Operativo/);\n    const api = await (await fetch(\`\${base}/api/tasks\`)).json();\n    assert.ok(Array.isArray(api.tasks));\n  } finally { s.close(); }\n});\n`
    }
  }
};

async function scaffoldProject(templateId, targetDir = '') {
  const template = TEMPLATES[templateId];
  if (!template) throw fail(`Plantilla desconocida: ${templateId}. Disponibles: ${Object.keys(TEMPLATES).join(', ')}`);
  const prefix = String(targetDir || '').trim().replace(/^\/+|\/+$/g, '');
  const created = [];
  for (const [relPath, content] of Object.entries(template.files)) {
    const fullRel = prefix ? `${prefix}/${relPath}` : relPath;
    await write(fullRel, content);
    created.push(fullRel);
  }
  return { template: templateId, label: template.label, targetDir: prefix || '.', files: created };
}

/* ------------------------------- snapshots ------------------------------- */

function snapshotId() {
  return new Date().toISOString().replace(/[:.]/g, '-') + '-' + crypto.randomBytes(3).toString('hex');
}

async function copyDir(src, dest, state, depth = 0) {
  if (depth > 40) throw fail('La estructura del proyecto es demasiado profunda.', 413);
  await fsp.mkdir(dest, { recursive: true });
  const entries = await fsp.readdir(src, { withFileTypes: true });
  for (const entry of entries) {
    if (IGNORED.has(entry.name) || entry.isSymbolicLink()) continue;
    const from = path.join(src, entry.name);
    const to = path.join(dest, entry.name);
    if (entry.isDirectory()) {
      await copyDir(from, to, state, depth + 1);
    } else if (entry.isFile()) {
      const st = await fsp.stat(from);
      state.bytes += st.size;
      if (state.bytes > MAX_SNAPSHOT_BYTES) throw fail('El snapshot supera el límite de ' + Math.round(MAX_SNAPSHOT_BYTES / 1024 / 1024) + ' MB.', 413);
      await fsp.copyFile(from, to);
    }
  }
}

async function snapshot(label = 'manual') {
  await ensure();
  const execute = async () => {
    await fsp.mkdir(SNAP_DIR, { recursive: true });
    const id = snapshotId();
    const temp = path.join(SNAP_DIR, '.tmp-' + id);
    const dest = path.join(SNAP_DIR, id);
    try {
      await copyDir(ROOT, temp, { bytes: 0 });
      await fsp.rename(temp, dest);
      const entries = (await fsp.readdir(SNAP_DIR, { withFileTypes: true }))
        .filter((entry) => entry.isDirectory() && /^[\w.-]+$/.test(entry.name))
        .map((entry) => entry.name).sort();
      for (const old of entries.slice(0, Math.max(0, entries.length - MAX_SNAPSHOTS))) {
        await fsp.rm(path.join(SNAP_DIR, old), { recursive: true, force: true }).catch(() => {});
      }
      return { id, label: String(label || 'manual').slice(0, 120), createdAt: new Date().toISOString() };
    } catch (error) {
      await fsp.rm(temp, { recursive: true, force: true }).catch(() => {});
      throw error;
    }
  };
  const pending = snapshotQueue.then(execute, execute);
  snapshotQueue = pending.catch(() => {});
  return pending;
}

async function listSnapshots() {
  await ensure();
  await fsp.mkdir(SNAP_DIR, { recursive: true });
  const entries = await fsp.readdir(SNAP_DIR, { withFileTypes: true });
  return entries.filter((entry) => entry.isDirectory() && /^[\w.-]+$/.test(entry.name))
    .map((entry) => entry.name).sort().reverse();
}

async function removeNonIgnored(abs) {
  const st = await fsp.lstat(abs).catch((error) => error.code === 'ENOENT' ? null : Promise.reject(error));
  if (!st) return false;
  if (!st.isDirectory() || st.isSymbolicLink()) {
    await fsp.rm(abs, { recursive: true, force: true });
    return false;
  }
  const entries = await fsp.readdir(abs, { withFileTypes: true });
  let preserved = false;
  for (const entry of entries) {
    if (IGNORED.has(entry.name)) { preserved = true; continue; }
    if (await removeNonIgnored(path.join(abs, entry.name))) preserved = true;
  }
  if (!preserved) await fsp.rm(abs, { recursive: true, force: true });
  return preserved;
}

async function restoreDirectory(source, destination) {
  await fsp.mkdir(destination, { recursive: true });
  const sourceEntries = await fsp.readdir(source, { withFileTypes: true });
  const sourceByName = new Map(sourceEntries.filter((entry) => !entry.isSymbolicLink()).map((entry) => [entry.name, entry]));
  const currentEntries = await fsp.readdir(destination, { withFileTypes: true });

  for (const current of currentEntries) {
    if (IGNORED.has(current.name)) continue;
    const currentPath = path.join(destination, current.name);
    const snapshotEntry = sourceByName.get(current.name);
    if (!snapshotEntry) {
      await removeNonIgnored(currentPath);
      continue;
    }
    const sourcePath = path.join(source, current.name);
    const currentIsDir = current.isDirectory() && !current.isSymbolicLink();
    if (snapshotEntry.isDirectory() && currentIsDir) {
      await restoreDirectory(sourcePath, currentPath);
    } else if (snapshotEntry.isFile() && current.isFile() && !current.isSymbolicLink()) {
      await fsp.copyFile(sourcePath, currentPath);
    } else {
      await fsp.rm(currentPath, { recursive: true, force: true });
      if (snapshotEntry.isDirectory()) await restoreDirectory(sourcePath, currentPath);
      else if (snapshotEntry.isFile()) {
        await fsp.mkdir(path.dirname(currentPath), { recursive: true });
        await fsp.copyFile(sourcePath, currentPath);
      }
    }
  }

  for (const entry of sourceEntries) {
    if (entry.isSymbolicLink() || IGNORED.has(entry.name)) continue;
    const destinationPath = path.join(destination, entry.name);
    const exists = await fsp.lstat(destinationPath).then(() => true).catch((error) => {
      if (error.code === 'ENOENT') return false;
      throw error;
    });
    if (exists) continue;
    const sourcePath = path.join(source, entry.name);
    if (entry.isDirectory()) await restoreDirectory(sourcePath, destinationPath);
    else if (entry.isFile()) await fsp.copyFile(sourcePath, destinationPath);
  }
}

async function restore(id) {
  const snapshotName = String(id || '');
  if (!/^[\w.-]+$/.test(snapshotName) || snapshotName.startsWith('.')) throw fail('Id de snapshot inválido.');
  await ensure();
  const source = path.join(SNAP_DIR, snapshotName);
  const st = await fsp.lstat(source).catch(() => null);
  if (!st || !st.isDirectory() || st.isSymbolicLink()) throw fail('Snapshot no encontrado: ' + snapshotName, 404);
  const sourceReal = await fsp.realpath(source);
  if (!isWithin(await fsp.realpath(SNAP_DIR), sourceReal)) throw fail('Snapshot inválido.', 403);
  const safety = await snapshot('pre-restore');
  await restoreDirectory(source, ROOT);
  return { restored: snapshotName, safetySnapshot: safety.id };
}

module.exports = {
  ROOT, SNAP_DIR, STATE_DIR, MAX_READ, MAX_WRITE, TEMPLATES,
  safe, relOf, ensure, resolveUserPath, withFileLock,
  tree, read, readMany, write, mkdir, remove, move, edit, patchLines,
  grep, find, treeText, walk, stats, importFiles, exportBundle, scaffoldProject,
  snapshot, listSnapshots, restore, isBinary, IGNORED
};
