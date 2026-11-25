'use strict';

/* Real process execution for supported languages and workspace shell commands. */
const { spawn } = require('node:child_process');
const fsp = require('node:fs').promises;
const os = require('node:os');
const path = require('node:path');
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

const OUTPUT_LIMIT = 1024 * 1024;

/** Spawn without a shell; kill the whole process group when a run times out. */
function exec(cmd, args, opts = {}) {
  return new Promise((resolve) => {
    const started = Date.now();
    let stdout = '';
    let stderr = '';
    let settled = false;
    let timedOut = false;
    let child;
    let timer = null;
    const timeout = Math.min(Math.max(Number(opts.timeout) || 20000, 50), 300000);
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
      const extra = stdout.length >= OUTPUT_LIMIT || stderr.length >= OUTPUT_LIMIT ? '\n[Salida truncada al superar 1 MB.]' : '';
      if (extra && !stderr.includes('[Salida truncada')) stderr += extra;
      finish({ ok: !timedOut && code === 0, code: timedOut ? 124 : code, signal, timedOut });
    });

    if (opts.stdin != null) {
      child.stdin?.end(String(opts.stdin));
    } else {
      child.stdin?.end();
    }
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
  const bins = ['git', 'npm', 'npx', 'pip3', 'curl', 'make', 'docker', 'podman'];
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
  const timeout = Math.min(Math.max(Number.parseInt(opts.timeout, 10) || 20000, 1000), 180000);
  const cwd = path.dirname(abs);

  if (!runtime.compiler) {
    const [cmd, argv] = runtime.command(abs, args);
    const result = await exec(cmd, argv, { cwd, timeout, stdin: opts.stdin });
    return { ...result, runtime: runtime.label, path: rel };
  }

  const tmp = await fsp.mkdtemp(path.join(os.tmpdir(), 'codeforge-'));
  try {
    if (runtime.compiler === 'native') {
      const output = path.join(tmp, process.platform === 'win32' ? 'program.exe' : 'program');
      let compilerArgs;
      if (runtime.id === 'rust') compilerArgs = [abs, ...runtime.flags, '-o', output];
      else if (runtime.id === 'swift') compilerArgs = [abs, ...runtime.flags, '-o', output];
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
    if (pattern.test(String(command))) return 'Comando bloqueado por seguridad. Si realmente necesitas ejecutarlo, confirma la acción y vuelve a intentarlo desde la terminal: ' + command;
  }
  return null;
}

async function runCommand(command, opts = {}) {
  const text = String(command || '');
  if (!text.trim()) throw Object.assign(new Error('El comando está vacío.'), { status: 400 });
  if (text.length > 10000) throw Object.assign(new Error('El comando supera el límite de 10 000 caracteres.'), { status: 413 });
  const blocked = guard(text, opts.allowDangerous === true);
  if (blocked) return { ok: false, code: 126, blocked: true, stdout: '', stderr: blocked, durationMs: 0, command: text };
  const cwdAbs = await fsx.resolveUserPath(opts.cwd || '', { allowRoot: true, allowMissing: false });
  const st = await fsp.stat(cwdAbs);
  if (!st.isDirectory()) throw Object.assign(new Error('El directorio de trabajo no es una carpeta.'), { status: 400 });
  const timeout = Math.min(Math.max(Number.parseInt(opts.timeout, 10) || 30000, 1000), 300000);
  const result = await exec('bash', ['-lc', text], { cwd: cwdAbs, timeout, stdin: opts.stdin });
  return { ...result, command: text, cwd: fsx.relOf(cwdAbs) || '.' };
}

module.exports = { RUNNERS, LANG, langOf, exec, detect, runFile, runCommand, runnerFor, guard };
