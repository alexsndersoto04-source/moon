'use strict';

/* CodeForge HTTP API, static UI, SSE agent channel, and interactive terminal. */
const express = require('express');
const http = require('node:http');
const path = require('node:path');
const { WebSocketServer, WebSocket } = require('ws');
const { spawn } = require('node:child_process');
const fsx = require('./fsx');
const runner = require('./runner');
const agent = require('./agent');

const PORT = process.env.PORT == null ? 4321 : Number.parseInt(process.env.PORT, 10);
const HOST = process.env.HOST || '0.0.0.0';
const PUBLIC_DIR = path.join(__dirname, '..', 'public');
const sessions = new Map();
const SESSION_TTL = 2 * 60 * 60 * 1000;
const MAX_TERMINALS_PER_IP = 4;

const ok = (res, object = {}) => res.json({ ok: true, ...object });
const bad = (res, error) => {
  const status = Number(error?.status) || 500;
  return res.status(status).json({ ok: false, error: error?.message || String(error) });
};
const wrap = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch((error) => {
  if (res.headersSent) return res.end();
  return bad(res, error);
});

function createApp() {
  const app = express();
  app.disable('x-powered-by');
  app.set('etag', 'weak');
  app.use((req, res, next) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'same-origin');
    res.setHeader('X-Frame-Options', 'SAMEORIGIN');
    res.setHeader('Cross-Origin-Resource-Policy', 'same-origin');
    // Monaco uses workers and its AMD loader; the allowlist stays same-origin.
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self' ws: wss:; worker-src 'self' blob:; child-src blob:; frame-ancestors 'self'; base-uri 'self'; form-action 'self'");
    next();
  });
  app.use(express.json({ limit: '20mb', strict: true }));
  app.use(express.static(PUBLIC_DIR, { etag: true, maxAge: process.env.NODE_ENV === 'production' ? '1h' : 0 }));

  app.get('/api/health', wrap(async (_req, res) => {
    const detected = await runner.detect();
    ok(res, {
      name: 'CodeForge', version: '2.1.0', workspace: path.basename(fsx.ROOT),
      uptime: Math.round(process.uptime()), runtimes: detected.runtimes,
      tools: detected.tools, platform: detected.platform, node: detected.node,
      terminal: terminalAvailable, terminalMode: terminalAvailable ? (pty ? 'pty' : 'compatible') : 'disabled'
    });
  }));

  app.get('/api/tree', wrap(async (req, res) => {
    ok(res, { tree: await fsx.tree(req.query.dir || '', req.query.depth) });
  }));

  app.get('/api/file', wrap(async (req, res) => {
    if (!req.query.path) throw Object.assign(new Error('Falta el parámetro path.'), { status: 400 });
    const result = await fsx.read(req.query.path);
    result.language = runner.langOf(req.query.path);
    ok(res, result);
  }));
  app.post('/api/file', wrap(async (req, res) => {
    const body = req.body || {};
    if (!body.path) throw Object.assign(new Error('Falta path.'), { status: 400 });
    const result = await fsx.write(body.path, body.content, { expectedVersion: body.expectedVersion });
    ok(res, result);
  }));
  app.put('/api/file', wrap(async (req, res) => {
    const body = req.body || {};
    if (!body.path) throw Object.assign(new Error('Falta path.'), { status: 400 });
    const result = await fsx.write(body.path, body.content, { expectedVersion: body.expectedVersion });
    ok(res, result);
  }));
  app.post('/api/edit', wrap(async (req, res) => {
    const body = req.body || {};
    ok(res, await fsx.edit(body.path, body.search, body.replace, body.all));
  }));
  app.post('/api/mkdir', wrap(async (req, res) => ok(res, await fsx.mkdir((req.body || {}).path))));
  app.post('/api/delete', wrap(async (req, res) => ok(res, await fsx.remove((req.body || {}).path))));
  app.post('/api/move', wrap(async (req, res) => ok(res, await fsx.move((req.body || {}).from, (req.body || {}).to))));
  app.get('/api/find', wrap(async (req, res) => ok(res, { files: await fsx.find(req.query.name) })));
  app.get('/api/search', wrap(async (req, res) => {
    const result = await fsx.grep(req.query.q || '', {
      regex: req.query.regex === '1', glob: req.query.glob,
      caseSensitive: req.query.case === '1', maxResults: req.query.max,
      dir: req.query.dir || ''
    });
    ok(res, result);
  }));

  app.get('/api/runtimes', wrap(async (_req, res) => ok(res, await runner.detect(true))));
  app.post('/api/run', wrap(async (req, res) => {
    const body = req.body || {};
    if (!body.path) throw Object.assign(new Error('Falta path.'), { status: 400 });
    ok(res, { run: await runner.runFile(body.path, { args: body.args, timeout: body.timeout, stdin: body.stdin }) });
  }));
  app.post('/api/exec', wrap(async (req, res) => {
    const body = req.body || {};
    if (!body.command) throw Object.assign(new Error('Falta command.'), { status: 400 });
    ok(res, { run: await runner.runCommand(body.command, {
      cwd: body.cwd, timeout: body.timeout, stdin: body.stdin,
      allowDangerous: body.allowDangerous === true
    }) });
  }));

  app.get('/api/snapshots', wrap(async (_req, res) => ok(res, { snapshots: await fsx.listSnapshots() })));
  app.post('/api/snapshot', wrap(async (req, res) => ok(res, await fsx.snapshot((req.body || {}).label))));
  app.post('/api/restore', wrap(async (req, res) => ok(res, await fsx.restore((req.body || {}).id))));

  app.post('/api/agent/reset', (req, res) => {
    sessions.delete(sessionKey((req.body || {}).sessionId));
    ok(res, { reset: true });
  });
  app.get('/api/agent/tools', (_req, res) => ok(res, {
    tools: agent.TOOLS.map((item) => ({ name: item.function.name, description: item.function.description }))
  }));
  app.post('/api/agent/models', async (req, res) => {
    try {
      const config = (req.body || {}).config || {};
      ok(res, await agent.listModels(config));
    } catch (error) { bad(res, error); }
  });
  app.post('/api/agent/test', async (req, res) => {
    try {
      const config = (req.body || {}).config || {};
      if (!config.model) throw Object.assign(new Error('Falta el nombre del modelo.'), { status: 400 });
      ok(res, await agent.probe(config));
    } catch (error) { bad(res, error); }
  });
  app.post('/api/agent/chat', async (req, res) => {
    const body = req.body || {};
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform', Connection: 'keep-alive',
      'X-Accel-Buffering': 'no'
    });
    res.flushHeaders?.();
    const emit = (event) => {
      if (!res.writableEnded && !res.destroyed) res.write('data: ' + JSON.stringify(event) + '\n\n');
    };
    const abort = new AbortController();
    res.on('close', () => { if (!res.writableEnded) abort.abort(); });
    const ping = setInterval(() => { if (!res.destroyed) res.write(': ping\n\n'); }, 15000);
    ping.unref?.();

    try {
      const key = sessionKey(body.sessionId);
      if (body.reset) sessions.delete(key);
      const state = session(key);
      if (body.message != null && String(body.message).trim()) state.messages.push({ role: 'user', content: String(body.message).slice(0, 30000) });
      if (!state.messages.length) throw new Error('Escribe una solicitud para el agente.');
      if (!body.config?.model) throw new Error('Configura el proveedor y el modelo en Ajustes del agente.');
      await agent.loop(body.config, state.messages, emit, { openFile: body.openFile || '', signal: abort.signal });
    } catch (error) {
      emit({ type: 'error', text: error.message || String(error) });
    } finally {
      clearInterval(ping);
      emit({ type: 'done' });
      if (!res.writableEnded) res.end();
    }
  });

  app.get('/api/*', (_req, res) => res.status(404).json({ ok: false, error: 'Endpoint API no encontrado.' }));
  app.get('*', (req, res, next) => {
    if (req.accepts('html')) return res.sendFile(path.join(PUBLIC_DIR, 'index.html'), (error) => error && next(error));
    return res.status(404).send('Not found');
  });
  app.use((error, _req, res, _next) => {
    if (res.headersSent) return res.end();
    const status = error.status || (error.type === 'entity.too.large' ? 413 : 500);
    res.status(status).json({ ok: false, error: error.message || 'Error interno del servidor.' });
  });
  return app;
}

let pty = null;
try { pty = require('node-pty'); } catch { /* optional native module */ }
const terminalAvailable = process.env.CF_DISABLE_TERMINAL !== '1';

function sessionKey(input) {
  const raw = String(input || 'default').slice(0, 128);
  return /^[\w.-]+$/.test(raw) ? raw : 'default';
}
function session(key) {
  const existing = sessions.get(key);
  if (existing) { existing.at = Date.now(); return existing; }
  const created = { messages: [], at: Date.now() };
  sessions.set(key, created);
  return created;
}
const sessionPruner = setInterval(() => {
  const cutoff = Date.now() - SESSION_TTL;
  for (const [key, state] of sessions) if (state.at < cutoff) sessions.delete(key);
}, 10 * 60 * 1000);
sessionPruner.unref?.();

function originAllowed(req) {
  const origin = req.headers.origin;
  if (!origin) return true;
  const allowed = String(process.env.CF_ALLOWED_ORIGINS || '').split(',').map((item) => item.trim()).filter(Boolean);
  if (allowed.includes('*') || allowed.includes(origin)) return true;
  try {
    const parsed = new URL(origin);
    const forwardedHost = String(req.headers['x-forwarded-host'] || '').split(',')[0].trim();
    const requestHost = forwardedHost || req.headers.host;
    return parsed.host === requestHost;
  } catch { return false; }
}

function shellInfo() {
  const shell = process.env.CF_SHELL || process.env.SHELL || (process.platform === 'win32' ? 'powershell.exe' : '/bin/bash');
  const name = path.basename(shell).toLowerCase();
  const args = /^(?:bash|zsh|sh|fish)$/.test(name) ? ['-i'] : [];
  return { shell, args };
}

function createHttpServer(app = createApp()) {
  const server = http.createServer(app);
  const wss = new WebSocketServer({
    noServer: true,
    maxPayload: 64 * 1024,
    perMessageDeflate: false
  });
  const clientsByIp = new Map();

  server.on('upgrade', (req, socket, head) => {
    let url;
    try { url = new URL(req.url, 'http://localhost'); } catch { socket.destroy(); return; }
    if (url.pathname !== '/ws/terminal' || !terminalAvailable || !originAllowed(req)) {
      socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }
    const ip = req.socket.remoteAddress || 'unknown';
    if ((clientsByIp.get(ip) || 0) >= MAX_TERMINALS_PER_IP) {
      socket.write('HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      clientsByIp.set(ip, (clientsByIp.get(ip) || 0) + 1);
      ws.once('close', () => {
        const count = (clientsByIp.get(ip) || 1) - 1;
        if (count <= 0) clientsByIp.delete(ip); else clientsByIp.set(ip, count);
      });
      wss.emit('connection', ws, req, url);
    });
  });

  wss.on('connection', async (ws, req, url) => {
    let processHandle = null;
    let usePty = false;
    let pending = [];
    const handleMessage = (message) => {
      if (!message || typeof message !== 'object') return;
      if (message.type === 'input' && typeof message.data === 'string') {
        if (message.data.length > 8192 || !processHandle) return;
        try {
          if (usePty) processHandle.write(message.data);
          else if (message.data.includes('\x03') && process.platform !== 'win32' && processHandle.pid) process.kill(-processHandle.pid, 'SIGINT');
          else if (message.data.includes('\x04')) processHandle.stdin?.end();
          else processHandle.stdin?.write(message.data);
        } catch { /* shell already exited */ }
      } else if (message.type === 'resize' && usePty && processHandle) {
        const cols = Math.min(Math.max(Number.parseInt(message.cols, 10) || 100, 20), 300);
        const rows = Math.min(Math.max(Number.parseInt(message.rows, 10) || 28, 5), 120);
        try { processHandle.resize(cols, rows); } catch { /* PTY closed */ }
      }
    };
    ws.on('message', (raw) => {
      let message;
      try { message = JSON.parse(raw.toString()); } catch { return; }
      if (!processHandle) {
        if (pending.length < 100) pending.push(message);
      } else handleMessage(message);
    });
    const killProcess = () => {
      if (!processHandle) return;
      try {
        if (!usePty && process.platform !== 'win32' && processHandle.pid) process.kill(-processHandle.pid, 'SIGKILL');
        else processHandle.kill();
      } catch { try { processHandle.kill('SIGKILL'); } catch { /* process already exited */ } }
    };
    ws.on('close', killProcess);
    ws.on('error', killProcess);

    let cwd = fsx.ROOT;
    try {
      cwd = await fsx.resolveUserPath(url.searchParams.get('cwd') || '', { allowRoot: true, allowMissing: false });
      const stat = await require('node:fs').promises.stat(cwd);
      if (!stat.isDirectory()) throw new Error('El directorio no es una carpeta.');
    } catch (error) {
      if (ws.readyState === WebSocket.OPEN) ws.close(1008, 'Directorio inválido: ' + String(error.message).slice(0, 80));
      return;
    }
    if (ws.readyState !== WebSocket.OPEN) return;
    if (process.env.CF_DISABLE_TERMINAL === '1') { ws.close(1008, 'Terminal desactivada.'); return; }
    const { shell, args } = shellInfo();
    const commonEnv = { ...process.env, TERM: 'xterm-256color', COLORTERM: 'truecolor', FORCE_COLOR: '1' };
    try {
      if (pty) {
        processHandle = pty.spawn(shell, args, { name: 'xterm-256color', cols: 100, rows: 28, cwd, env: commonEnv });
        usePty = true;
        processHandle.onData((data) => {
          if (ws.readyState === WebSocket.OPEN && ws.bufferedAmount < 2 * 1024 * 1024) ws.send(data);
        });
        processHandle.onExit(({ exitCode, signal }) => {
          if (ws.readyState === WebSocket.OPEN) {
            ws.send(`\r\n[terminal cerrada · código ${exitCode}${signal ? ' · señal ' + signal : ''}]\r\n`);
            ws.close(1000, 'Shell cerrada');
          }
        });
      } else {
        const isBash = path.basename(shell).toLowerCase().startsWith('bash');
        const fallbackArgs = isBash ? ['--noprofile', '--norc', '-i'] : args;
        processHandle = spawn(shell, fallbackArgs, {
          cwd, env: { ...commonEnv, PS1: '\\w $ ' },
          stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
          detached: process.platform !== 'win32'
        });
        const send = (chunk) => {
          if (ws.readyState === WebSocket.OPEN && ws.bufferedAmount < 2 * 1024 * 1024) ws.send(chunk.toString('utf8'));
        };
        processHandle.stdout.on('data', send);
        processHandle.stderr.on('data', send);
        processHandle.on('error', (error) => {
          if (ws.readyState === WebSocket.OPEN) { ws.send(`\r\nNo se pudo iniciar la shell: ${error.message}\r\n`); ws.close(1011, 'Shell no disponible'); }
        });
        processHandle.on('close', (code) => {
          if (ws.readyState === WebSocket.OPEN) {
            ws.send(`\r\n[terminal cerrada · código ${code}]\r\n`);
            ws.close(1000, 'Shell cerrada');
          }
        });
        ws.send('CodeForge · terminal real (modo compatible sin PTY).\r\n');
      }
      const queued = pending;
      pending = [];
      for (const message of queued) handleMessage(message);
    } catch (error) {
      if (ws.readyState === WebSocket.OPEN) { ws.send(`\r\nNo se pudo iniciar la terminal: ${error.message}\r\n`); ws.close(1011, 'No se pudo iniciar'); }
      killProcess();
    }
  });

  server.closeWebSockets = () => {
    for (const client of wss.clients) {
      try { client.terminate(); } catch { /* connection already closed */ }
    }
  };
  server.once('close', () => {
    clearInterval(sessionPruner);
    server.closeWebSockets();
  });
  return server;
}

async function start() {
  await fsx.ensure();
  const server = createHttpServer();
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(PORT, HOST, () => { server.off('error', reject); resolve(); });
  });
  const address = server.address();
  const port = typeof address === 'object' && address ? address.port : PORT;
  console.log(`\n  CodeForge 2.1 — editor listo\n  URL:       http://${HOST}:${port}\n  Workspace: ${fsx.ROOT}\n  Terminal:  ${terminalAvailable ? (pty ? 'PTY' : 'shell compatible') : 'desactivada'}\n`);
  return server;
}

if (require.main === module) {
  start().catch((error) => {
    console.error('No se pudo iniciar CodeForge:', error);
    process.exitCode = 1;
  });
}

module.exports = { createApp, createHttpServer, start, originAllowed };
