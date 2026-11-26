'use strict';

/*
 * Enterprise Static Analysis, Multi-Language Syntax Verification,
 * Security Audit (OWASP / Secrets / Sinks), Codebase Cartography & Safe Auto-Repair.
 */
const fsp = require('node:fs').promises;
const path = require('node:path');
const fsx = require('./fsx');
const runner = require('./runner');

const SECURITY_RULES = [
  { id: 'SEC-001', severity: 'critical', title: 'Posible clave privada embebida', regex: /-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----/ },
  { id: 'SEC-002', severity: 'critical', title: 'Posible API Key / Secret hardcoded', regex: /\b(?:sk-[a-zA-Z0-9_-]{20,}|ghp_[a-zA-Z0-9]{30,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z\-_]{35})\b/ },
  { id: 'SEC-003', severity: 'high', title: 'Ejecución dinámica insegura (eval / new Function)', regex: /\b(?:eval\s*\(|new\s+Function\s*\()/ },
  { id: 'SEC-004', severity: 'high', title: 'Posible inyección de comandos en shell', regex: /\b(?:child_process\.exec|execSync|os\.system|subprocess\.(?:run|Popen)\([^\n]*shell\s*=\s*True)\s*\(/ },
  { id: 'SEC-005', severity: 'high', title: 'Posible inyección SQL por concatenación', regex: /(?:SELECT|INSERT|UPDATE|DELETE)\b[^\n]{0,80}(?:\$\{|['"]\s*\+\s*\w)/i },
  { id: 'SEC-006', severity: 'medium', title: 'Posible XSS por inserción HTML directa', regex: /\.(?:innerHTML|outerHTML)\s*=|document\.write\s*\(|dangerouslySetInnerHTML/ },
  { id: 'SEC-007', severity: 'medium', title: 'Verificación TLS desactivada', regex: /rejectUnauthorized\s*:\s*false|NODE_TLS_REJECT_UNAUTHORIZED\s*=\s*['"]?0/ },
  { id: 'SEC-008', severity: 'low', title: 'Algoritmo criptográfico débil (MD5/SHA1)', regex: /createHash\s*\(\s*['"](?:md5|sha1)['"]\s*\)|hashlib\.(?:md5|sha1)\s*\(/i },
  { id: 'SEC-009', severity: 'info', title: 'Marcador técnico pendiente (TODO / FIXME / XXX)', regex: /\b(?:TODO|FIXME|HACK|XXX)\b/ }
];

const SEVERITY_WEIGHT = { critical: 20, high: 10, medium: 5, low: 2, info: 0 };

function parseLineFromError(text) {
  const patterns = [
    /:(\d+):(\d+)/,
    /line\s+(\d+)/i,
    /línea\s+(\d+)/i,
    /at position\s+(\d+)/i
  ];
  for (const re of patterns) {
    const match = String(text || '').match(re);
    if (match) return { line: Number.parseInt(match[1], 10) || 1, column: Number.parseInt(match[2], 10) || 1 };
  }
  return { line: 1, column: 1 };
}

async function checkSingleFileSyntax(abs, detectedTools = null) {
  const rel = fsx.relOf(abs);
  const ext = path.extname(abs).toLowerCase();
  const diagnostics = [];

  try {
    if (ext === '.json') {
      const raw = await fsp.readFile(abs, 'utf8');
      try { JSON.parse(raw); }
      catch (err) {
        let line = 1;
        let column = 1;
        const posMatch = err.message.match(/position\s+(\d+)/i);
        if (posMatch) {
          const offset = Number(posMatch[1]);
          const before = raw.slice(0, offset);
          const parts = before.split('\n');
          line = parts.length;
          column = parts[parts.length - 1].length + 1;
        }
        diagnostics.push({ file: rel, line, column, severity: 'error', rule: 'json-syntax', runtime: 'JSON', message: err.message });
      }
      return diagnostics;
    }

    if (['.js', '.mjs', '.cjs'].includes(ext)) {
      const res = await runner.exec('node', ['--check', abs], { timeout: 10000 });
      if (res.code !== 0) {
        const errText = (res.stderr || res.stdout || 'Error de sintaxis JavaScript').trim();
        const loc = parseLineFromError(errText);
        diagnostics.push({ file: rel, line: loc.line, column: loc.column, severity: 'error', rule: 'js-syntax', runtime: 'Node.js', message: errText.split('\n').slice(0, 4).join(' | ') });
      }
      return diagnostics;
    }

    if (ext === '.py') {
      const res = await runner.exec('python3', ['-c', 'import ast, sys; ast.parse(open(sys.argv[1], encoding="utf-8").read(), filename=sys.argv[1])', abs], { timeout: 10000 });
      if (res.code !== 0) {
        const errText = (res.stderr || res.stdout || 'Error de sintaxis Python').trim();
        const loc = parseLineFromError(errText);
        diagnostics.push({ file: rel, line: loc.line, column: loc.column, severity: 'error', rule: 'py-syntax', runtime: 'Python 3', message: errText.split('\n').slice(-3).join(' | ') });
      }
      return diagnostics;
    }

    if (['.sh', '.bash'].includes(ext)) {
      const res = await runner.exec('bash', ['-n', abs], { timeout: 8000 });
      if (res.code !== 0) {
        const errText = (res.stderr || res.stdout || 'Error de sintaxis Bash').trim();
        const loc = parseLineFromError(errText);
        diagnostics.push({ file: rel, line: loc.line, column: loc.column, severity: 'error', rule: 'bash-syntax', runtime: 'Bash', message: errText });
      }
      return diagnostics;
    }

    if (ext === '.c' && detectedTools?.has('gcc')) {
      const res = await runner.exec('gcc', ['-fsyntax-only', '-Wall', abs], { timeout: 15000 });
      if (res.code !== 0) {
        const errText = (res.stderr || res.stdout).trim();
        const loc = parseLineFromError(errText);
        diagnostics.push({ file: rel, line: loc.line, column: loc.column, severity: 'error', rule: 'c-syntax', runtime: 'GCC', message: errText.split('\n').slice(0, 4).join(' | ') });
      }
      return diagnostics;
    }

    if (['.cpp', '.cc', '.cxx'].includes(ext) && detectedTools?.has('g++')) {
      const res = await runner.exec('g++', ['-fsyntax-only', '-Wall', abs], { timeout: 15000 });
      if (res.code !== 0) {
        const errText = (res.stderr || res.stdout).trim();
        const loc = parseLineFromError(errText);
        diagnostics.push({ file: rel, line: loc.line, column: loc.column, severity: 'error', rule: 'cpp-syntax', runtime: 'G++', message: errText.split('\n').slice(0, 4).join(' | ') });
      }
      return diagnostics;
    }

    if (ext === '.css') {
      const raw = await fsp.readFile(abs, 'utf8');
      const cleaned = raw.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(["'])(?:\\.|(?!\1).)*\1/g, '');
      let balance = 0;
      const lines = cleaned.split('\n');
      for (let i = 0; i < lines.length; i++) {
        for (const ch of lines[i]) {
          if (ch === '{') balance++;
          else if (ch === '}') {
            balance--;
            if (balance < 0) {
              diagnostics.push({ file: rel, line: i + 1, column: 1, severity: 'error', rule: 'css-braces', runtime: 'CSS', message: 'Llave de cierre "}" inesperada sin apertura previa.' });
              return diagnostics;
            }
          }
        }
      }
      if (balance > 0) {
        diagnostics.push({ file: rel, line: lines.length, column: 1, severity: 'error', rule: 'css-braces', runtime: 'CSS', message: `Faltan ${balance} llave(s) de cierre "}" en la hoja de estilos.` });
      }
      return diagnostics;
    }

    if (ext === '.html' || ext === '.htm') {
      const raw = await fsp.readFile(abs, 'utf8');
      if (/<script\b[^>]*>[\s\S]*?<\/script>/i.test(raw)) {
        const scriptMatches = [...raw.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)];
        for (const m of scriptMatches) {
          if (/\bsrc\s*=/i.test(m[0].slice(0, m[0].indexOf('>')))) continue;
          const inlineJs = (m[1] || '').trim();
          if (!inlineJs) continue;
          try { new Function(inlineJs); }
          catch (err) {
            const line = raw.slice(0, m.index).split('\n').length;
            diagnostics.push({ file: rel, line, column: 1, severity: 'error', rule: 'html-inline-script', runtime: 'HTML/JS', message: `Script embebido inválido: ${err.message}` });
          }
        }
      }
      return diagnostics;
    }
  } catch (error) {
    diagnostics.push({ file: rel, line: 1, column: 1, severity: 'warning', rule: 'checker-error', runtime: 'Analyzer', message: error.message });
  }
  return diagnostics;
}

async function mapConcurrent(items, limit, worker) {
  const out = [];
  let idx = 0;
  async function next() {
    while (idx < items.length) {
      const current = idx++;
      out[current] = await worker(items[current], current);
    }
  }
  const runners = Array.from({ length: Math.min(limit, items.length) }, () => next());
  await Promise.all(runners);
  return out;
}

async function verifyWorkspaceSyntax(dirRel = '', maxFiles = 400) {
  const started = Date.now();
  const detected = await runner.detect();
  const availableBins = new Set(detected.runtimes.filter((r) => r.available).map((r) => r.bin));
  const checkableExts = new Set(['.js', '.mjs', '.cjs', '.py', '.json', '.sh', '.bash', '.c', '.cpp', '.cc', '.cxx', '.css', '.html', '.htm']);
  const targets = [];

  for await (const abs of fsx.walk(dirRel)) {
    if (!checkableExts.has(path.extname(abs).toLowerCase())) continue;
    try {
      const st = await fsp.stat(abs);
      if (st.size > 2 * 1024 * 1024) continue;
      targets.push(abs);
    } catch { /* ignore */ }
    if (targets.length >= maxFiles) break;
  }

  const perFile = await mapConcurrent(targets, 6, (abs) => checkSingleFileSyntax(abs, availableBins));
  const diagnostics = perFile.flat();
  const errorCount = diagnostics.filter((d) => d.severity === 'error').length;
  const warningCount = diagnostics.filter((d) => d.severity === 'warning').length;
  return {
    ok: errorCount === 0,
    checkedFiles: targets.length,
    errorCount,
    warningCount,
    diagnostics,
    durationMs: Date.now() - started
  };
}

async function verifyFile(rel) {
  const abs = await fsx.resolveUserPath(rel, { allowMissing: false });
  const detected = await runner.detect();
  const availableBins = new Set(detected.runtimes.filter((r) => r.available).map((r) => r.bin));
  const syntax = await checkSingleFileSyntax(abs, availableBins);
  const security = await scanFileSecurity(abs);
  return {
    path: fsx.relOf(abs),
    ok: syntax.length === 0,
    syntax,
    security,
    totalIssues: syntax.length + security.filter((s) => s.severity !== 'info').length
  };
}

async function scanFileSecurity(abs) {
  const findings = [];
  const rel = fsx.relOf(abs);
  if (await fsx.isBinary(abs)) return findings;
  let text;
  try {
    const st = await fsp.stat(abs);
    if (st.size > 1024 * 1024) return findings;
    text = await fsp.readFile(abs, 'utf8');
  } catch { return findings; }
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.length > 2000) continue;
    for (const rule of SECURITY_RULES) {
      if (rule.regex.test(line)) {
        findings.push({
          id: rule.id,
          severity: rule.severity,
          title: rule.title,
          file: rel,
          line: i + 1,
          snippet: line.trim().slice(0, 180)
        });
      }
    }
  }
  return findings;
}

async function securityAudit(dirRel = '', maxFiles = 500) {
  const started = Date.now();
  const targets = [];
  for await (const abs of fsx.walk(dirRel)) {
    targets.push(abs);
    if (targets.length >= maxFiles) break;
  }
  const perFile = await mapConcurrent(targets, 8, scanFileSecurity);
  const findings = perFile.flat();
  const counts = { critical: 0, high: 0, medium: 0, low: 0, info: 0 };
  let penalty = 0;
  for (const f of findings) {
    counts[f.severity] = (counts[f.severity] || 0) + 1;
    penalty += SEVERITY_WEIGHT[f.severity] || 0;
  }
  const score = Math.max(0, Math.min(100, 100 - penalty));
  return {
    scannedFiles: targets.length,
    score,
    counts,
    findings: findings.slice(0, 500),
    truncated: findings.length > 500,
    durationMs: Date.now() - started
  };
}

async function cartography(dirRel = '') {
  const started = Date.now();
  const [workspaceStats, commands] = await Promise.all([
    fsx.stats(dirRel),
    runner.detectProjectCommands(dirRel)
  ]);
  const imports = [];
  const entryPoints = [];
  const testFiles = [];
  const hotspots = [];
  let scanned = 0;

  for await (const abs of fsx.walk(dirRel)) {
    if (scanned >= 400) break;
    const rel = fsx.relOf(abs);
    const base = path.basename(abs).toLowerCase();
    const ext = path.extname(abs).toLowerCase();
    if (/^(?:index|main|app|server)\.(?:js|ts|py|go|rs|java|c|cpp)$/.test(base)) entryPoints.push(rel);
    if (/(?:\.test\.|\.spec\.|^test_|_test\.)/i.test(base)) testFiles.push(rel);
    if (!['.js', '.mjs', '.cjs', '.ts', '.tsx', '.py', '.go', '.rs', '.java', '.c', '.cpp'].includes(ext)) continue;
    scanned++;
    try {
      const st = await fsp.stat(abs);
      if (st.size > 1024 * 1024) continue;
      const text = await fsp.readFile(abs, 'utf8');
      const lines = text.split('\n');
      const branchCount = (text.match(/\b(?:if|for|while|switch|catch|&&|\|\||\?)\b/g) || []).length;
      if (lines.length > 250 || branchCount > 50) {
        hotspots.push({ path: rel, lines: lines.length, complexityScore: branchCount });
      }
      const deps = new Set();
      for (const m of text.matchAll(/(?:require\(\s*['"]([^'"]+)['"]\s*\)|from\s+['"]([^'"]+)['"]|import\s+['"]([^'"]+)['"]|^\s*(?:from|import)\s+([\w.]+))/gm)) {
        const dep = m[1] || m[2] || m[3] || m[4];
        if (dep) deps.add(dep);
      }
      if (deps.size) imports.push({ file: rel, imports: [...deps].slice(0, 25) });
    } catch { /* ignore */ }
  }

  hotspots.sort((a, b) => b.complexityScore - a.complexityScore || b.lines - a.lines);
  return {
    ...workspaceStats,
    entryPoints: entryPoints.slice(0, 30),
    testFiles: testFiles.slice(0, 50),
    hotspots: hotspots.slice(0, 20),
    dependencyGraph: imports.slice(0, 80),
    detectedCommands: commands,
    durationMs: Date.now() - started
  };
}

async function autoRepairSafe(dirRel = '') {
  const repaired = [];
  const unresolved = [];
  for await (const abs of fsx.walk(dirRel)) {
    const rel = fsx.relOf(abs);
    if (await fsx.isBinary(abs)) continue;
    let st;
    try { st = await fsp.stat(abs); } catch { continue; }
    if (st.size > 2 * 1024 * 1024) continue;
    const raw = await fsp.readFile(abs, 'utf8');
    let next = raw;
    const reasons = [];

    // Strip UTF-8 BOM if present
    if (next.charCodeAt(0) === 0xFEFF) {
      next = next.slice(1);
      reasons.push('Eliminado BOM UTF-8');
    }
    // Normalize CRLF -> LF
    if (next.includes('\r\n')) {
      next = next.replace(/\r\n/g, '\n');
      reasons.push('Normalizados saltos de línea CRLF → LF');
    }
    // Pretty-normalize valid JSON if minified/inconsistent or fix trailing commas in JSON
    if (path.extname(abs).toLowerCase() === '.json') {
      try {
        JSON.parse(next);
      } catch {
        const withoutTrailingCommas = next.replace(/,\s*([}\]])/g, '$1');
        try {
          const parsed = JSON.parse(withoutTrailingCommas);
          next = JSON.stringify(parsed, null, 2) + '\n';
          reasons.push('Corregidas comas finales inválidas en JSON');
        } catch (err) {
          unresolved.push({ file: rel, error: err.message });
        }
      }
    }
    if (next !== raw) {
      await fsx.write(rel, next);
      repaired.push({ file: rel, changes: reasons });
    }
  }
  const syntaxAfter = await verifyWorkspaceSyntax(dirRel);
  return { repaired, unresolved, syntaxAfter };
}

module.exports = {
  SECURITY_RULES,
  verifyWorkspaceSyntax,
  verifyFile,
  securityAudit,
  cartography,
  autoRepairSafe
};
