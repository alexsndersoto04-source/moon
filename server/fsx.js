'use strict';

/*
 * Workspace filesystem. Paths are always relative to CF_WORKSPACE; symlinks and
 * internal snapshot data are deliberately not exposed through user operations.
 */
const fs = require('node:fs');
const fsp = fs.promises;
const path = require('node:path');
const crypto = require('node:crypto');

const ROOT = path.resolve(process.env.CF_WORKSPACE || path.join(__dirname, '..', 'workspace'));
const SNAP_DIR = path.join(ROOT, '.cf-snapshots');
const MAX_READ = 8 * 1024 * 1024;
const MAX_WRITE = 16 * 1024 * 1024;
const MAX_SNAPSHOT_BYTES = Math.max(16, Number(process.env.CF_MAX_SNAPSHOT_MB) || 256) * 1024 * 1024;
const MAX_SNAPSHOTS = 10;
const MAX_TREE_NODES = 5000;

const IGNORED = new Set([
  'node_modules', '.git', '.hg', '.svn', '.cache', 'dist', 'build', 'out',
  '__pycache__', '.venv', 'venv', '.idea', '.next', '.nuxt', '.output',
  '.turbo', 'coverage', 'target', '.cf-snapshots'
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

function fail(message, status = 400) {
  return Object.assign(new Error(message), { status });
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
  return abs === SNAP_DIR || isWithin(SNAP_DIR, abs);
}

function assertUserPath(abs) {
  if (isInternalPath(abs)) throw fail('La carpeta interna de snapshots no se puede modificar.', 403);
}

async function ensure() {
  await fsp.mkdir(ROOT, { recursive: true });
  const st = await fsp.lstat(ROOT);
  if (st.isSymbolicLink() || !st.isDirectory()) throw fail('CF_WORKSPACE debe ser un directorio real.', 500);
  rootReal = await fsp.realpath(ROOT);
  return ROOT;
}

/**
 * Verify every existing component, rejecting symlinks (including links which
 * point back into the workspace) to avoid traversal and recursive tree loops.
 */
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

async function tree(dirRel = '', depth = 12) {
  const base = await resolveUserPath(dirRel || '', { allowRoot: true, allowMissing: false });
  const st = await fsp.stat(base);
  if (!st.isDirectory()) throw fail('La ruta no es una carpeta: ' + (dirRel || '.'), 400);
  const parsedDepth = Number.parseInt(depth, 10);
  const maxDepth = Math.min(Math.max(Number.isFinite(parsedDepth) ? parsedDepth : 12, 0), 20);
  let nodes = 0;

  async function walk(dir, level) {
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
        out.push({ name: entry.name, path: relOf(abs), type: 'dir', children: await walk(abs, level + 1) });
      } else if (entry.isFile()) {
        let size = 0;
        try { size = (await fsp.stat(abs)).size; } catch { /* concurrently removed */ }
        out.push({ name: entry.name, path: relOf(abs), type: 'file', size });
      }
    }
    return out;
  }
  return walk(base, 0);
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

async function write(rel, content, options = {}) {
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

  const temp = path.join(path.dirname(abs), `.cf-write-${process.pid}-${crypto.randomBytes(8).toString('hex')}.tmp`);
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

async function remove(rel) {
  const abs = await resolveUserPath(rel, { allowMissing: false });
  if (abs === ROOT) throw fail('No se puede borrar la raíz del workspace.', 400);
  await fsp.rm(abs, { recursive: true, force: false });
  return { path: relOf(abs), deleted: true };
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
  const needle = String(search == null ? '' : search);
  if (!needle) throw fail('El fragmento de búsqueda está vacío.');
  const { content, version } = await read(rel);
  const count = content.split(needle).length - 1;
  if (!count) throw fail('No se encontró el fragmento en ' + rel + '.', 404);
  if (count > 1 && !all) throw fail('El fragmento aparece ' + count + ' veces. Añade contexto o activa all=true.', 409);
  const next = all ? content.split(needle).join(String(replace == null ? '' : replace)) : content.replace(needle, String(replace == null ? '' : replace));
  await write(rel, next, { expectedVersion: version });
  return { path: rel, replacements: all ? count : 1 };
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

async function grep(query, opts = {}) {
  if (query == null || String(query) === '') throw fail('La consulta de búsqueda está vacía.');
  const limit = Math.min(Math.max(Number.parseInt(opts.maxResults, 10) || 150, 1), 500);
  let test;
  if (opts.regex) {
    let re;
    try { re = new RegExp(String(query), opts.caseSensitive ? '' : 'i'); }
    catch (error) { throw fail('Expresión regular inválida: ' + error.message); }
    test = (line) => re.test(line);
  } else {
    const needle = opts.caseSensitive ? String(query) : String(query).toLocaleLowerCase();
    test = (line) => (opts.caseSensitive ? line : line.toLocaleLowerCase()).includes(needle);
  }
  let base = '';
  if (opts.dir) base = opts.dir;
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
      if (st.size > 1024 * 1024) continue;
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
  return { query: String(query), results, scanned, truncated: results.length >= limit };
}

async function find(namePart) {
  const needle = String(namePart || '').toLocaleLowerCase();
  if (!needle) throw fail('Indica parte del nombre.');
  const out = [];
  for await (const abs of walk('')) {
    if (path.basename(abs).toLocaleLowerCase().includes(needle)) {
      out.push(relOf(abs));
      if (out.length >= 300) break;
    }
  }
  return out;
}

async function treeText(maxNodes = 300) {
  const nodes = await tree('', 8);
  const out = [];
  const limit = Math.min(Math.max(Number(maxNodes) || 300, 1), 1000);
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
    // Snapshots intentionally omit generated/VCS folders; keep them on restore.
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
      // A file/directory type conflict cannot retain nested ignored data at the same path.
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
  // A safety point makes a failed or mistaken restore recoverable.
  const safety = await snapshot('pre-restore');
  await restoreDirectory(source, ROOT);
  return { restored: snapshotName, safetySnapshot: safety.id };
}

module.exports = {
  ROOT, SNAP_DIR, MAX_READ, MAX_WRITE, safe, relOf, ensure, resolveUserPath,
  tree, read, write, mkdir, remove, move, edit, grep, find, treeText, walk,
  snapshot, listSnapshots, restore, isBinary, IGNORED
};
