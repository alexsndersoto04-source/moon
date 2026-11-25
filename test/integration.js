'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const WebSocket = require('ws');

async function expectStatus(response, status, message) {
  assert.equal(response.status, status, message || `Expected HTTP ${status}, received ${response.status}`);
}
async function json(response) {
  return response.json();
}
async function request(base, route, options) {
  return fetch(base + route, options);
}
async function waitForTerminal(url) {
  const socket = new WebSocket(url);
  let text = '';
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Terminal WebSocket timed out opening.')), 5000);
      socket.once('open', () => { clearTimeout(timer); resolve(); });
      socket.once('error', (error) => { clearTimeout(timer); reject(error); });
    });
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`Terminal did not return command output. Received: ${text.slice(-400)}`)), 8000);
      socket.on('message', (data) => {
        text += data.toString();
        if (text.includes('codeforge-terminal-ok')) { clearTimeout(timer); resolve(); }
      });
      socket.send(JSON.stringify({ type: 'input', data: "printf 'codeforge-terminal-ok\\n'\n" }));
      socket.once('error', (error) => { clearTimeout(timer); reject(error); });
    });
  } finally {
    if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
      await new Promise((resolve) => {
        const timeout = setTimeout(() => { socket.terminate(); resolve(); }, 1000);
        socket.once('close', () => { clearTimeout(timeout); resolve(); });
        if (socket.readyState !== WebSocket.CLOSING) socket.close();
      });
    }
  }
}

async function main() {
  const temporaryRoot = await fs.mkdtemp(path.join(os.tmpdir(), 'codeforge-integration-'));
  process.env.CF_WORKSPACE = path.join(temporaryRoot, 'workspace');
  process.env.CF_DISABLE_TERMINAL = '0';

  // Load after CF_WORKSPACE is set so the test never touches a developer's workspace.
  const fsx = require('../server/fsx');
  const agent = require('../server/agent');
  const { createHttpServer, originAllowed } = require('../server');
  await fsx.ensure();
  const server = createHttpServer();
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  const base = `http://127.0.0.1:${address.port}`;
  const wsBase = `ws://127.0.0.1:${address.port}`;
  let providerAuthorization = '';
  const providerServer = http.createServer((req, res) => {
    if (req.method === 'GET' && req.url === '/v1/models') {
      providerAuthorization = req.headers.authorization || '';
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ data: [
        { id: 'paid-model', pricing: { prompt: '0.01', completion: '0.02' } },
        { id: 'free-by-price', pricing: { prompt: '0', completion: '0' } },
        { id: 'free-by-input-output', pricing: { input: '0', output: '0' } },
        { id: 'free-by-flag', metadata: { free: true } },
        { id: 'free-by-id:free' },
        { id: 'missing-prices', pricing: {} },
        { id: 'null-prices', pricing: { prompt: null, completion: null } },
        { id: 'duplicate-free', free: true },
        { id: 'duplicate-free', free: false }
      ] }));
      return;
    }
    if (req.method === 'POST' && req.url === '/v1/chat/completions') {
      let raw = '';
      req.setEncoding('utf8');
      req.on('data', (chunk) => { raw += chunk; });
      req.on('end', () => {
        let model = '';
        try { model = JSON.parse(raw).model; } catch { /* malformed test request */ }
        if (model === 'quota-model') {
          res.writeHead(429, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ error: { message: 'You exceeded your current quota, please check your plan and billing details.', type: 'insufficient_quota', code: 'insufficient_quota' } }));
        } else if (model === 'rate-model') {
          res.writeHead(429, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ error: { message: 'Rate limit exceeded.', type: 'rate_limit_exceeded', code: 'rate_limit_exceeded' } }));
        } else {
          res.writeHead(200, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: 'ok' }, finish_reason: 'stop' }] }));
        }
      });
      return;
    }
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: { message: 'Not found' } }));
  });
  await new Promise((resolve, reject) => {
    providerServer.once('error', reject);
    providerServer.listen(0, '127.0.0.1', resolve);
  });
  const providerBase = `http://127.0.0.1:${providerServer.address().port}/v1`;
  let assertions = 0;
  const check = (condition, message) => { assertions++; assert.ok(condition, message); };

  try {
    // The app and vendored editor assets are served by the actual Express server.
    const html = await request(base, '/');
    await expectStatus(html, 200);
    const htmlContent = await html.text();
    check(htmlContent.includes('CodeForge Agent'), 'index.html should serve the actual editor UI');
    check(htmlContent.includes('discover-models-button') && htmlContent.includes('setting-model-options'), 'agent settings expose a searchable provider model catalog');
    check(htmlContent.includes('insufficient_quota') && htmlContent.includes('OpenRouter: buscar modelos GRATIS'), 'settings explain quota errors and offer a free-model provider preset');
    for (const asset of ['/css/app.css', '/js/app.js', '/vendor/monaco/vs/loader.js', '/vendor/xterm/xterm.js', '/vendor/xterm-fit/addon-fit.js', '/vendor/xterm-css/xterm.css']) {
      const response = await request(base, asset);
      await expectStatus(response, 200, `${asset} should be served`);
      check(Number(response.headers.get('content-length') || 0) > 0, `${asset} should not be empty`);
      await response.arrayBuffer();
    }

    check(originAllowed({ headers: { origin: 'https://4321-preview.e2b.app', host: '127.0.0.1:4321', 'x-forwarded-host': '4321-preview.e2b.app' } }), 'terminal accepts the same-origin host forwarded by the preview proxy');
    check(!originAllowed({ headers: { origin: 'https://attacker.example', host: '127.0.0.1:4321' } }), 'terminal rejects a cross-origin WebSocket upgrade');

    const healthResponse = await request(base, '/api/health');
    await expectStatus(healthResponse, 200);
    const health = await json(healthResponse);
    check(health.name === 'CodeForge', 'health endpoint returns application metadata');
    check(['pty', 'compatible'].includes(health.terminalMode), 'health reports the real terminal transport mode');
    check(Array.isArray(health.runtimes) && health.runtimes.some((item) => item.id === 'node' && item.available), 'Node.js runtime is detected');

    const initialTree = await json(await request(base, '/api/tree'));
    check(initialTree.ok && Array.isArray(initialTree.tree), 'tree endpoint returns a real workspace tree');

    let response = await request(base, '/api/file', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ path: 'src/hello.js', content: 'console.log("CodeForge API OK");\n' })
    });
    await expectStatus(response, 200);
    const created = await json(response);
    check(created.bytes > 0 && /^[a-f0-9]{64}$/.test(created.version), 'new file is written atomically and receives a content version');

    response = await request(base, '/api/file?path=src%2Fhello.js');
    await expectStatus(response, 200);
    const opened = await json(response);
    check(opened.content.includes('CodeForge API OK') && opened.language === 'javascript', 'file contents and language are returned');

    response = await request(base, '/api/file', {
      method: 'PUT', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ path: 'src/hello.js', content: 'console.log("saved version");\n', expectedVersion: opened.version })
    });
    await expectStatus(response, 200);
    const saved = await json(response);
    check(saved.version !== opened.version, 'optimistic save updates the content version');

    response = await request(base, '/api/file', {
      method: 'PUT', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ path: 'src/hello.js', content: 'lost update', expectedVersion: opened.version })
    });
    await expectStatus(response, 409, 'stale editor versions must not overwrite a newer file');

    response = await request(base, '/api/search?q=saved%20version');
    await expectStatus(response, 200);
    const matches = await json(response);
    check(matches.results?.some((item) => item.file === 'src/hello.js' && item.line === 1), 'global search returns real file and line results');

    // Execute real Node.js source through the compiled/runtime runner.
    response = await request(base, '/api/file', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ path: 'scripts/run.js', content: 'console.log("runner-ok", process.argv[2] || "no-arg");\n' })
    });
    await expectStatus(response, 200);
    response = await request(base, '/api/run', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ path: 'scripts/run.js', args: ['arg-ok'] })
    });
    await expectStatus(response, 200);
    const execution = (await json(response)).run;
    check(execution.ok && execution.stdout.includes('runner-ok arg-ok'), 'run endpoint launches the actual Node process');

    response = await request(base, '/api/exec', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ command: 'sudo whoami' })
    });
    await expectStatus(response, 200);
    const blocked = (await json(response)).run;
    check(blocked.blocked && blocked.code === 126, 'dangerous shell commands are blocked by default');

    response = await request(base, '/api/exec', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ command: 'printf "shell-runner-ok\\n"' })
    });
    await expectStatus(response, 200);
    const shellRun = (await json(response)).run;
    check(shellRun.ok && shellRun.stdout.includes('shell-runner-ok'), 'safe shell commands execute in the workspace');

    response = await request(base, '/api/mkdir', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ path: 'nested/deep' })
    });
    await expectStatus(response, 200);
    check((await fs.stat(path.join(fsx.ROOT, 'nested/deep'))).isDirectory(), 'nested folders are created on disk');

    response = await request(base, '/api/move', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ from: 'scripts/run.js', to: 'nested/deep/run.js' })
    });
    await expectStatus(response, 200);
    check((await fs.readFile(path.join(fsx.ROOT, 'nested/deep/run.js'), 'utf8')).includes('runner-ok'), 'move endpoint renames real files');

    await fs.mkdir(path.join(fsx.ROOT, 'node_modules'), { recursive: true });
    await fs.writeFile(path.join(fsx.ROOT, 'node_modules', 'preserved.txt'), 'generated dependency data');
    await fs.mkdir(path.join(fsx.ROOT, 'nested/deep/node_modules'), { recursive: true });
    await fs.writeFile(path.join(fsx.ROOT, 'nested/deep/node_modules', 'preserved.txt'), 'nested generated data');
    response = await request(base, '/api/snapshot', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ label: 'integration checkpoint' })
    });
    await expectStatus(response, 200);
    const snapshot = await json(response);
    check(snapshot.id && (await fs.stat(path.join(fsx.SNAP_DIR, snapshot.id))).isDirectory(), 'snapshot copies workspace content to disk');

    response = await request(base, '/api/file', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ path: 'src/hello.js', content: 'console.log("changed after snapshot");\n' })
    });
    await expectStatus(response, 200);
    response = await request(base, '/api/file', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ path: 'src/new-after-snapshot.js', content: 'remove me on restore' })
    });
    await expectStatus(response, 200);
    await fs.writeFile(path.join(fsx.ROOT, 'node_modules', 'preserved.txt'), 'new dependency state');
    response = await request(base, '/api/restore', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ id: snapshot.id })
    });
    await expectStatus(response, 200);
    const restoreResult = await json(response);
    const restored = await fs.readFile(path.join(fsx.ROOT, 'src/hello.js'), 'utf8');
    check(restored.includes('saved version'), 'restore puts snapshot contents back on disk');
    check(restoreResult.safetySnapshot, 'restore creates a recovery snapshot before replacing files');
    check(await fs.readFile(path.join(fsx.ROOT, 'node_modules/preserved.txt'), 'utf8') === 'new dependency state', 'restore preserves ignored dependency folders');
    check(await fs.readFile(path.join(fsx.ROOT, 'nested/deep/node_modules/preserved.txt'), 'utf8') === 'nested generated data', 'restore preserves ignored folders nested inside restored directories');
    await assert.rejects(fs.access(path.join(fsx.ROOT, 'src/new-after-snapshot.js')), 'restore removes files that were created after the snapshot');

    // Path traversal, direct filesystem access and symlink escapes are denied.
    response = await request(base, '/api/file', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ path: '../outside.txt', content: 'nope' })
    });
    await expectStatus(response, 403);
    await fs.writeFile(path.join(temporaryRoot, 'secret.txt'), 'outside');
    await fs.symlink(path.join(temporaryRoot, 'secret.txt'), path.join(fsx.ROOT, 'outside-link'));
    response = await request(base, '/api/file?path=outside-link');
    await expectStatus(response, 403);
    check(await fs.readFile(path.join(temporaryRoot, 'secret.txt'), 'utf8') === 'outside', 'workspace symlinks cannot modify files outside the workspace');

    // WebSocket test sends input to an actual shell, not a mocked terminal.
    await waitForTerminal(`${wsBase}/ws/terminal?cwd=`);
    check(true, 'terminal WebSocket executes a real shell command');

    const tools = await json(await request(base, '/api/agent/tools'));
    check(tools.tools?.some((item) => item.name === 'write_file') && tools.tools.some((item) => item.name === 'run_file'), 'agent exposes real filesystem and execution tools');
    const parsed = agent.parseTextCalls('Voy a inspeccionar.\n```tool\n{"tool":"read_file","args":{"path":"src/hello.js"}}\n```');
    check(parsed.calls.length === 1 && parsed.calls[0].name === 'read_file' && parsed.clean === 'Voy a inspeccionar.', 'text-protocol tool calls parse without consuming ordinary markdown blocks');

    response = await request(base, '/api/agent/test', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ config: {} }) });
    await expectStatus(response, 400, 'agent config validation reports a useful HTTP error');

    check(agent.normalizeModelsUrl(providerBase) === `${providerBase}/models`, 'model catalog URL is derived from a provider base URL');
    check(agent.normalizeModelsUrl(`${providerBase}/chat/completions`) === `${providerBase}/models`, 'model catalog URL is normalized when chat/completions is already present');
    check(agent.normalizeBase(`${providerBase}/models`) === `${providerBase}/chat/completions`, 'chat URL is normalized when models is already present');
    response = await request(base, '/api/agent/models', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ config: { baseUrl: providerBase, apiKey: 'provider-test-key' } })
    });
    await expectStatus(response, 200, 'model discovery proxies the provider catalog through the application API');
    const catalog = await json(response);
    check(catalog.models.length === 8 && catalog.freeCount === 5, 'catalog accepts multiple pricing/free metadata formats and removes duplicate IDs');
    check(catalog.models.slice(0, catalog.freeCount).every((item) => item.free), 'free models sort before models without free metadata');
    check(catalog.models.find((item) => item.id === 'missing-prices')?.free === false && catalog.models.find((item) => item.id === 'null-prices')?.free === false, 'missing or null pricing is not incorrectly treated as free');
    check(providerAuthorization === 'Bearer provider-test-key', 'catalog requests authenticate with the selected provider API key');
    check(catalog.source === `${providerBase}/models`, 'catalog response identifies the queried endpoint');

    response = await request(base, '/api/agent/test', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ config: { baseUrl: providerBase, model: 'quota-model', apiKey: 'provider-test-key' } })
    });
    await expectStatus(response, 429, 'provider quota status reaches the client');
    const quotaFailure = await json(response);
    check(/no tiene saldo\/créditos disponibles/i.test(quotaFailure.error) && /cambiar a otro modelo de OpenAI no lo soluciona/i.test(quotaFailure.error), 'insufficient_quota explains that changing models cannot restore balance');

    response = await request(base, '/api/agent/test', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ config: { baseUrl: providerBase, model: 'rate-model', apiKey: 'provider-test-key' } })
    });
    await expectStatus(response, 429);
    const rateFailure = await json(response);
    check(/límite de solicitudes/i.test(rateFailure.error) && !/no tiene saldo\/créditos disponibles/i.test(rateFailure.error), 'ordinary rate limits are distinguished from exhausted credits');

    const offlinePort = providerServer.address().port;
    await new Promise((resolve) => {
      providerServer.close(resolve);
      providerServer.closeAllConnections?.();
    });
    response = await request(base, '/api/agent/models', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ config: { baseUrl: `http://127.0.0.1:${offlinePort}/v1` } })
    });
    await expectStatus(response, 502, 'provider network failures are returned as upstream connection errors');
    const connectionFailure = await json(response);
    check(/No se pudo conectar con el catálogo de modelos/i.test(connectionFailure.error), 'connection errors are clearly distinguished from provider quota errors');

    console.log(`CodeForge integration: ${assertions} assertions passed.`);
  } finally {
    server.closeWebSockets?.();
    await new Promise((resolve) => {
      let finished = false;
      const finish = () => { if (!finished) { finished = true; clearTimeout(timeout); resolve(); } };
      const timeout = setTimeout(() => { server.closeAllConnections?.(); finish(); }, 3000);
      server.close(finish);
      server.closeAllConnections?.();
    });
    if (providerServer.listening) {
      await new Promise((resolve) => {
        providerServer.close(resolve);
        providerServer.closeAllConnections?.();
      });
    }
    await fs.rm(temporaryRoot, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error('CodeForge integration failed:', error);
  process.exitCode = 1;
});
