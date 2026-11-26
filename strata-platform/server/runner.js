'use strict';

/*
 * Enterprise Process Execution, Multi-Language Compiler/Runner,
 * Git Operations Suite, Project Test Detector, and Background Process Manager.
 */
const { spawn } = require('node:child_process');
const fsp = require('node:fs').promises;
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const fsx = require('./fsx');

const RUNNERS = [
  { id: 'python', label: 'Python 3', exts: ['.py'], bin: 'python3', command: (file, args) => ['python3', [file, ...args]] },
  { id: 'node', label: 'Node.js', exts: ['.js', '.mjs', '.cjs'], bin: 'node', command: (file, args) => ['node', [file, ...args]] },
  { id: 'typescript', label: 'TypeScript (tsx)', exts: ['.ts', '.tsx'], bin: 'npx', command: (file, args) => ['npx', ['--yes', 'tsx', file, ...args]] },
  { id: 'bash', label: 'Bash', exts: ['.sh', '.bash'], bin: 'bash', command: (file, args) => ['bash', [file, ...args]] },
  { id: 'ruby', label: 'Ruby', exts: ['.rb'], bin: 'ruby', command: (file, args) => ['ruby', [file, ...args]] },
  { id: 'php', label: 'PHP', exts: ['.php'], bin: 'php', command: (file, args) => ['php', [file, ...args]] },
  { id: 'lua', label: 'Lua', exts: ['.lua'], bin: 'lua', command: (file, args) => ['lua', [file, ...args]] },
  { id: 'perl', label: 'Perl', exts: ['.pl'], bin: 'perl', command: (file, args) => ['perl', [file, ...args]] },
  { id: 'go', label: 'Go', exts: ['.go'], bin: 'go', command: (file, args) => ['go', ['run', file, ...args]] },
  { id: 'deno', label: 'Deno', exts: ['.mts'], bin: 'deno', command: (file, args) => ['deno', ['run', '-A', file, ...args]] },
  { id: 'c', label: 'C (GCC)', exts: ['.c'], bin: 'gcc', compiler: 'native', flags: ['-O2', '-lm'] },
  { id: 'cpp', label: 'C++ (G++)', exts: ['.cpp', '.cc', '.cxx'], bin: 'g++', compiler: 'native', flags: ['-O2', '-lm'] },
  { id: 'rust', label: 'Rust (rustc)', exts: ['.rs'], bin: 'rustc', compiler: 'native', flags: ['-O'] },
  { id: 'java', label: 'Java', exts: ['.java'], bin: 'javac', compiler: 'java' },
  { id: 'kotlin', label: 'Kotlin', exts: ['.kt'], bin: 'kotlinc', compiler: 'kotlin' },
  { id: 'swift', label: 'Swift', exts: ['.swift'], bin: 'swiftc', compiler: 'native', flags: ['-O'] }
];

const LANG = {
  '.py': 'python', '.js': 'javascript', '.mjs': 'javascript', '.cjs': 'javascript', '.ts': 'typescript', '.mts': 'typescript', '.tsx': 'typescript',
  '.json': 'json', '.html': 'html', '.htm': 'html', '.css': 'css', '.scss': 'scss', '.less': 'less', '.md': 'markdown',
  '.sh': 'shell', '.bash': 'shell', '.c': 'c', '.h': 'c', '.cpp': 'cpp', '.cc': 'cpp', '.cxx': 'cpp', '.hpp': 'cpp',
  '.rs': 'rust', '.go': 'go', '.java': 'java', '.rb': 'ruby', '.php': 'php', '.sql': 'sql', '.yml': 'yaml', '.yaml': 'yaml',
  '.xml': 'xml', '.svg': 'xml', '.lua': 'lua', '.pl': 'perl', '.kt': 'kotlin', '.swift': 'swift', '.ini': 'ini',
  '.toml': 'ini', '.txt': 'plaintext', '.env': 'ini', '.gitignore': 'plaintext', '.dockerfile': 'dockerfile'
};
const langOf = (rel) => LANG[path.extname(rel).toLowerCase()] || LANG[path.basename(rel).toLowerCase()] || 'plaintext';

const OUTPUT_LIMIT = 4 * 1024 * 1024;
const bgProcesses = new Map();

function exec(cmd, args, opts = {}) {
  return new Promise((resolve) => {
    const started = Date.now();
    let stdout = '';
    let stderr = '';
    let settled = false;
    let timedOut = false;
    let child;
    let timer = null;
    const timeout = Math.min(Math.max(Number(opts.timeout) || 30000, 50), 300000);
    const env = {
      ...process.env,
      PYTHONUNBUFFERED: '1', PYTHONIOENCODING: 'utf-8', NO_COLOR: '1', CI: '1', TERM: 'xterm-256color',
      ...(opts.env || {})
    };

    const finish = (result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({ ...result, stdout, stderr, durationMs: Date.now() - started });
    };

    try {
      child = spawn(cmd, args, {
        cwd: opts.cwd || fsx.ROOT,
        env,
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true,
        detached: process.platform !== 'win32'
      });
    } catch (error) {
      return finish({ ok: false, code: -1, error: error.message });
    }

    const collect = (current, chunk) => {
      if (current.length >= OUTPUT_LIMIT) return current;
      const text = chunk.toString('utf8');
      return current + text.slice(0, OUTPUT_LIMIT - current.length);
    };
    child.stdout?.on('data', (chunk) => { stdout = collect(stdout, chunk); });
    child.stderr?.on('data', (chunk) => { stderr = collect(stderr, chunk); });

    const terminate = () => {
      if (!child || child.killed) return;
      try {
        if (process.platform !== 'win32' && child.pid) process.kill(-child.pid, 'SIGKILL');
        else child.kill('SIGKILL');
      } catch {
        try { child.kill('SIGKILL'); } catch { /* already gone */ }
      }
    };
    timer = setTimeout(() => {
      timedOut = true;
      terminate();
    }, timeout);
    timer.unref?.();

    child.once('error', (error) => finish({ ok: false, code: -1, error: error.message }));
    child.once('close', (code, signal) => {
      const extra = stdout.length >= OUTPUT_LIMIT || stderr.length >= OUTPUT_LIMIT ? '\n[Salida truncada al superar 4 MB.]' : '';
      if (extra && !stderr.includes('[Salida truncada')) stderr += extra;
      finish({ ok: !timedOut && code === 0, code: timedOut ? 124 : code, signal, timedOut });
    });

    if (opts.stdin != null) child.stdin?.end(String(opts.stdin));
    else child.stdin?.end();
  });
}

let detectCache = null;
let detectCacheAt = 0;
async function detect(force = false) {
  if (!force && detectCache && Date.now() - detectCacheAt < 30000) return detectCache;
  const runtimes = await Promise.all(RUNNERS.map(async (runtime) => {
    const result = await exec('bash', ['-lc', `command -v ${runtime.bin}`], { timeout: 2500 });
    return { id: runtime.id, label: runtime.label, exts: runtime.exts, bin: runtime.bin, available: result.code === 0 && Boolean(result.stdout.trim()) };
  }));
  const bins = ['git', 'npm', 'npx', 'pip3', 'rg', 'curl', 'make', 'docker', 'podman'];
  const tools = await Promise.all(bins.map(async (name) => {
    const result = await exec('bash', ['-lc', `command -v ${name}`], { timeout: 2500 });
    return { name, available: result.code === 0 && Boolean(result.stdout.trim()) };
  }));
  detectCache = { runtimes, tools, platform: process.platform, node: process.version };
  detectCacheAt = Date.now();
  return detectCache;
}

const runnerFor = (rel) => RUNNERS.find((runtime) => runtime.exts.includes(path.extname(rel).toLowerCase())) || null;

async function runFile(rel, opts = {}) {
  const abs = await fsx.resolveUserPath(rel, { allowMissing: false });
  const st = await fsp.stat(abs);
  if (!st.isFile()) throw Object.assign(new Error('La ruta no apunta a un archivo: ' + rel), { status: 400 });
  const runtime = runnerFor(rel);
  if (!runtime) throw Object.assign(new Error('No hay runtime asociado a la extensión "' + (path.extname(rel) || 'sin extensión') + '".'), { status: 400 });
  const args = Array.isArray(opts.args) ? opts.args.map(String).slice(0, 100) : [];
  const timeout = Math.min(Math.max(Number.parseInt(opts.timeout, 10) || 30000, 1000), 300000);
  const cwd = path.dirname(abs);

  if (!runtime.compiler) {
    const [cmd, argv] = runtime.command(abs, args);
    const result = await exec(cmd, argv, { cwd, timeout, stdin: opts.stdin });
    return { ...result, runtime: runtime.label, path: rel };
  }

  const tmp = await fsp.mkdtemp(path.join(os.tmpdir(), 'strata-'));
  try {
    if (runtime.compiler === 'native') {
      const output = path.join(tmp, process.platform === 'win32' ? 'program.exe' : 'program');
      let compilerArgs;
      if (runtime.id === 'rust' || runtime.id === 'swift') compilerArgs = [abs, ...runtime.flags, '-o', output];
      else compilerArgs = [abs, '-o', output, ...runtime.flags];
      const compiled = await exec(runtime.bin, compilerArgs, { cwd, timeout: Math.max(timeout, 90000) });
      if (compiled.code !== 0) return { ...compiled, ok: false, runtime: runtime.label, stage: 'compilación', path: rel };
      const result = await exec(output, args, { cwd, timeout, stdin: opts.stdin });
      return { ...result, runtime: runtime.label, stage: 'ejecución', compileMs: compiled.durationMs, path: rel };
    }
    if (runtime.compiler === 'java') {
      const compiled = await exec('javac', ['-encoding', 'UTF-8', '-d', tmp, abs], { cwd, timeout: Math.max(timeout, 90000) });
      if (compiled.code !== 0) return { ...compiled, ok: false, runtime: runtime.label, stage: 'compilación', path: rel };
      const source = await fsp.readFile(abs, 'utf8');
      const packageName = (source.match(/^\s*package\s+([\w.]+)\s*;/m) || [])[1];
      const className = path.basename(abs, '.java');
      const result = await exec('java', ['-cp', tmp, packageName ? `${packageName}.${className}` : className, ...args], { cwd, timeout, stdin: opts.stdin });
      return { ...result, runtime: runtime.label, stage: 'ejecución', compileMs: compiled.durationMs, path: rel };
    }
    if (runtime.compiler === 'kotlin') {
      const jar = path.join(tmp, 'app.jar');
      const compiled = await exec('kotlinc', [abs, '-include-runtime', '-d', jar], { cwd, timeout: Math.max(timeout, 120000) });
      if (compiled.code !== 0) return { ...compiled, ok: false, runtime: runtime.label, stage: 'compilación', path: rel };
      const result = await exec('java', ['-jar', jar, ...args], { cwd, timeout, stdin: opts.stdin });
      return { ...result, runtime: runtime.label, stage: 'ejecución', compileMs: compiled.durationMs, path: rel };
    }
    throw new Error('Compilador no soportado: ' + runtime.compiler);
  } finally {
    setTimeout(() => { fsp.rm(tmp, { recursive: true, force: true }).catch(() => {}); }, 1000).unref?.();
  }
}

const DANGER = [
  /\brm\s+-(?:[^\s]*r[^\s]*f|[^\s]*f[^\s]*r)[^\s]*\s+(?:\/|~(?:\/|\s|$))/i,
  /\bmkfs(?:\.[\w]+)?\b/i, /\bdd\s+[^\n]*\bif=/i, /:\(\)\s*\{\s*:\|:&\s*\};:/,
  /\b(?:shutdown|reboot|poweroff|halt)\b/i, /\bchmod\s+-R\s+777\s+\//i,
  /\b(?:curl|wget)\b[^\n|]*\|\s*(?:ba)?sh\b/i, /\bsudo\b/i,
  /\bgit\s+push\b/i, /\bnpm\s+publish\b/i, /\/etc\/(?:passwd|shadow)\b/i
];
function guard(command, allowDangerous = false) {
  if (allowDangerous) return null;
  for (const pattern of DANGER) {
    if (pattern.test(String(command))) return 'Comando bloqueado por seguridad. Si realmente necesitas ejecutarlo, confirma la acción desde la terminal: ' + command;
  }
  return null;
}

async function runCommand(command, opts = {}) {
  const text = String(command || '');
  if (!text.trim()) throw Object.assign(new Error('El comando está vacío.'), { status: 400 });
  if (text.length > 20000) throw Object.assign(new Error('El comando supera el límite de 20 000 caracteres.'), { status: 413 });
  const blocked = guard(text, opts.allowDangerous === true);
  if (blocked) return { ok: false, code: 126, blocked: true, stdout: '', stderr: blocked, durationMs: 0, command: text };
  const cwdAbs = await fsx.resolveUserPath(opts.cwd || '', { allowRoot: true, allowMissing: false });
  const st = await fsp.stat(cwdAbs);
  if (!st.isDirectory()) throw Object.assign(new Error('El directorio de trabajo no es una carpeta.'), { status: 400 });
  const timeout = Math.min(Math.max(Number.parseInt(opts.timeout, 10) || 45000, 1000), 300000);
  const result = await exec('bash', ['-lc', text], { cwd: cwdAbs, timeout, stdin: opts.stdin });
  return { ...result, command: text, cwd: fsx.relOf(cwdAbs) || '.' };
}

/* --------------------- Project Commands & Test Runner --------------------- */

async function detectProjectCommands(dirRel = '') {
  const baseAbs = await fsx.resolveUserPath(dirRel || '', { allowRoot: true, allowMissing: false });
  const commands = [];

  // package.json scripts
  try {
    const pkgRaw = await fsp.readFile(path.join(baseAbs, 'package.json'), 'utf8');
    const pkg = JSON.parse(pkgRaw);
    if (pkg.scripts && typeof pkg.scripts === 'object') {
      for (const [name, script] of Object.entries(pkg.scripts)) {
        commands.push({ id: `npm:${name}`, label: `npm run ${name}`, command: `npm run ${name}`, kind: name === 'test' ? 'test' : name === 'build' ? 'build' : name === 'lint' ? 'lint' : 'script', detail: String(script), cwd: dirRel || '' });
      }
    }
  } catch { /* no package.json */ }

  // Python tests
  const hasPyTests = await fsx.find('test_').then((list) => list.some((f) => f.endsWith('.py'))).catch(() => false);
  if (hasPyTests) {
    commands.push({ id: 'python:unittest', label: 'Python Unittest (discover)', command: 'python3 -m unittest discover -v', kind: 'test', detail: 'Ejecuta todos los tests Python', cwd: dirRel || '' });
  }

  // Node native test files if no package.json test script
  if (!commands.some((c) => c.kind === 'test')) {
    const jsTests = await fsx.find('.test.').then((list) => list.filter((f) => /\.(?:js|mjs|cjs)$/.test(f))).catch(() => []);
    if (jsTests.length) {
      commands.push({ id: 'node:test', label: 'Node.js Test Runner', command: `node --test ${jsTests.slice(0, 30).map((f) => JSON.stringify(f)).join(' ')}`, kind: 'test', detail: `${jsTests.length} archivo(s) de prueba`, cwd: dirRel || '' });
    }
  }

  // Makefile
  try {
    await fsp.access(path.join(baseAbs, 'Makefile'));
    commands.push({ id: 'make', label: 'make', command: 'make', kind: 'build', detail: 'Ejecuta target por defecto del Makefile', cwd: dirRel || '' });
  } catch { /* no Makefile */ }

  return commands;
}

async function runProjectTests(dirRel = '', customCommand = '') {
  if (customCommand && String(customCommand).trim()) {
    return runCommand(String(customCommand).trim(), { cwd: dirRel, timeout: 120000 });
  }
  const detected = await detectProjectCommands(dirRel);
  const testCmd = detected.find((c) => c.kind === 'test');
  if (testCmd) {
    const res = await runCommand(testCmd.command, { cwd: testCmd.cwd || dirRel, timeout: 120000 });
    return { ...res, detectedCommand: testCmd };
  }
  // Search immediate subfolders for package.json or python tests (e.g. cloned repo or scaffolded project)
  const rootTree = await fsx.tree(dirRel, 2).catch(() => []);
  for (const node of rootTree) {
    if (node.type !== 'dir') continue;
    const subCmds = await detectProjectCommands(node.path).catch(() => []);
    const subTest = subCmds.find((c) => c.kind === 'test');
    if (subTest) {
      const res = await runCommand(subTest.command, { cwd: node.path, timeout: 120000 });
      return { ...res, detectedCommand: { ...subTest, cwd: node.path } };
    }
  }
  return { ok: true, code: 0, skipped: true, stdout: 'No se detectó una suite de pruebas automática (package.json test, *.test.js o test_*.py). Puedes crear pruebas o indicar un comando de prueba personalizado.', stderr: '', durationMs: 0 };
}

/* ------------------------------- Git Suite ------------------------------- */

async function gitExec(args, dirRel = '', timeout = 60000) {
  const cwdAbs = await fsx.resolveUserPath(dirRel || '', { allowRoot: true, allowMissing: false });
  return exec('git', args, { cwd: cwdAbs, timeout });
}

async function gitStatus(dirRel = '') {
  const check = await gitExec(['rev-parse', '--is-inside-work-tree'], dirRel);
  if (check.code !== 0) return { initialized: false, branch: '', files: [], summary: 'El workspace no es un repositorio Git todavía.' };
  const [branchRes, statusRes] = await Promise.all([
    gitExec(['rev-parse', '--abbrev-ref', 'HEAD'], dirRel),
    gitExec(['status', '--porcelain=v1'], dirRel)
  ]);
  const files = statusRes.stdout.split('\n').filter(Boolean).map((line) => ({
    status: line.slice(0, 2).trim() || 'M',
    path: line.slice(3).trim()
  }));
  return { initialized: true, branch: branchRes.stdout.trim() || 'main', files, clean: files.length === 0 };
}

async function gitDiff(dirRel = '', filePath = '') {
  const args = ['diff', '--stat', '-p'];
  if (filePath) {
    const abs = await fsx.resolveUserPath(filePath, { allowMissing: true });
    args.push('--', fsx.relOf(abs));
  }
  const res = await gitExec(args, dirRel);
  return { ok: res.code === 0, diff: res.stdout || res.stderr || '(sin diferencias en working tree)' };
}

async function gitLog(dirRel = '', limit = 20) {
  const max = Math.min(Math.max(Number(limit) || 20, 1), 100);
  const res = await gitExec(['log', `-n`, String(max), '--pretty=format:%h|%an|%ad|%s', '--date=short'], dirRel);
  if (res.code !== 0) return { ok: false, commits: [], error: res.stderr || 'Sin commits.' };
  const commits = res.stdout.split('\n').filter(Boolean).map((line) => {
    const [hash, author, date, ...msg] = line.split('|');
    return { hash, author, date, message: msg.join('|') };
  });
  return { ok: true, commits };
}

async function gitInit(dirRel = '') {
  const res = await gitExec(['init'], dirRel);
  return { ok: res.code === 0, stdout: res.stdout, stderr: res.stderr };
}

async function gitCommit(message, dirRel = '') {
  const msg = String(message || '').trim();
  if (!msg) throw Object.assign(new Error('Escribe un mensaje de commit.'), { status: 400 });
  const add = await gitExec(['add', '-A'], dirRel);
  if (add.code !== 0) return { ok: false, stderr: add.stderr };
  const commit = await gitExec(['-c', 'user.name=Strata Engineering', '-c', 'user.email=agents@strata-engineering.local', 'commit', '-m', msg], dirRel);
  return { ok: commit.code === 0, stdout: commit.stdout, stderr: commit.stderr };
}

async function gitClone(repoUrl, targetDir = '') {
  const raw = String(repoUrl || '').trim();
  let parsed;
  try { parsed = new URL(raw); } catch { throw Object.assign(new Error('URL de repositorio inválida. Usa una URL HTTPS como https://github.com/usuario/repo'), { status: 400 }); }
  if (parsed.protocol !== 'https:' || !/^github\.com$/i.test(parsed.hostname)) {
    throw Object.assign(new Error('Por seguridad de red, solo se admiten repositorios HTTPS de github.com.'), { status: 400 });
  }
  const repoName = path.basename(parsed.pathname.replace(/\.git$/i, '')) || 'repo';
  const cleanTarget = String(targetDir || repoName).trim().replace(/^\/+|\/+$/g, '');
  const destAbs = await fsx.resolveUserPath(cleanTarget, { allowMissing: true });
  const exists = await fsp.lstat(destAbs).then(() => true).catch(() => false);
  if (exists) throw Object.assign(new Error(`La carpeta destino «${cleanTarget}» ya existe en el workspace.`), { status: 409 });
  await fsx.mkdir(fsx.relOf(path.dirname(destAbs)) || '.');
  const res = await exec('git', ['clone', '--depth', '1', raw, destAbs], { cwd: fsx.ROOT, timeout: 120000 });
  return { ok: res.code === 0, targetDir: cleanTarget, stdout: res.stdout, stderr: res.stderr };
}

/* ---------------------- Background Process Manager ----------------------- */

async function startBackgroundProcess(command, opts = {}) {
  const text = String(command || '').trim();
  if (!text) throw Object.assign(new Error('Falta el comando.'), { status: 400 });
  const blocked = guard(text, false);
  if (blocked) throw Object.assign(new Error(blocked), { status: 403 });
  if (bgProcesses.size >= 8) throw Object.assign(new Error('Máximo 8 procesos simultáneos en segundo plano.'), { status: 429 });
  const cwdAbs = await fsx.resolveUserPath(opts.cwd || '', { allowRoot: true, allowMissing: false });
  const id = 'proc-' + crypto.randomBytes(3).toString('hex');
  const name = String(opts.name || text.slice(0, 48)).trim();
  const child = spawn('bash', ['-lc', text], {
    cwd: cwdAbs,
    env: { ...process.env, PYTHONUNBUFFERED: '1', NO_COLOR: '1' },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
    detached: process.platform !== 'win32'
  });
  const entry = {
    id, name, command: text, cwd: fsx.relOf(cwdAbs) || '.', pid: child.pid,
    startedAt: new Date().toISOString(), status: 'running', exitCode: null,
    logs: '', child
  };
  const append = (chunk) => {
    entry.logs += chunk.toString('utf8');
    if (entry.logs.length > 200000) entry.logs = entry.logs.slice(-160000);
  };
  child.stdout?.on('data', append);
  child.stderr?.on('data', append);
  child.once('close', (code) => {
    entry.status = code === 0 ? 'stopped' : 'exited';
    entry.exitCode = code;
    entry.endedAt = new Date().toISOString();
  });
  bgProcesses.set(id, entry);
  return summarizeProcess(entry);
}

function summarizeProcess(entry) {
  return {
    id: entry.id, name: entry.name, command: entry.command, cwd: entry.cwd,
    pid: entry.pid, status: entry.status, exitCode: entry.exitCode,
    startedAt: entry.startedAt, endedAt: entry.endedAt || null,
    logTail: entry.logs.slice(-4000)
  };
}

function listProcesses() {
  return [...bgProcesses.values()].map(summarizeProcess);
}

function getProcessLogs(id) {
  const entry = bgProcesses.get(id);
  if (!entry) throw Object.assign(new Error('Proceso no encontrado.'), { status: 404 });
  return summarizeProcess(entry);
}

function stopBackgroundProcess(id) {
  const entry = bgProcesses.get(id);
  if (!entry) throw Object.assign(new Error('Proceso no encontrado.'), { status: 404 });
  if (entry.status === 'running' && entry.child) {
    try {
      if (process.platform !== 'win32' && entry.child.pid) process.kill(-entry.child.pid, 'SIGTERM');
      else entry.child.kill('SIGTERM');
    } catch { /* already gone */ }
    setTimeout(() => {
      try {
        if (entry.status === 'running' && entry.child?.pid) {
          if (process.platform !== 'win32') process.kill(-entry.child.pid, 'SIGKILL');
          else entry.child.kill('SIGKILL');
        }
      } catch { /* ignore */ }
    }, 1500).unref?.();
    entry.status = 'stopping';
  }
  return summarizeProcess(entry);
}

function stopAllProcesses() {
  for (const id of bgProcesses.keys()) {
    try { stopBackgroundProcess(id); } catch { /* ignore */ }
  }
}

module.exports = {
  RUNNERS, LANG, langOf, exec, detect, runFile, runCommand, runnerFor, guard,
  detectProjectCommands, runProjectTests,
  gitStatus, gitDiff, gitLog, gitInit, gitCommit, gitClone,
  startBackgroundProcess, listProcesses, getProcessLogs, stopBackgroundProcess, stopAllProcesses
};
