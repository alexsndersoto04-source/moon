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
        if (text.includes('strata-terminal-ok')) { clearTimeout(timer); resolve(); }
      });
      socket.send(JSON.stringify({ type: 'input', data: "printf 'strata-terminal-ok\\n'\n" }));
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
  const temporaryRoot = await fs.mkdtemp(path.join(os.tmpdir(), 'strata-integration-'));
  process.env.STRATA_WORKSPACE = path.join(temporaryRoot, 'workspace');
  process.env.STRATA_DISABLE_TERMINAL = '0';

  // Load after STRATA_WORKSPACE is set so the test never touches a developer's workspace.
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
    check(htmlContent.includes('Strata Agent') && htmlContent.includes('Strata'), 'index.html should serve the standalone Strata Engineering UI');
    check(htmlContent.includes('discover-models-button') && htmlContent.includes('setting-model-options'), 'agent settings expose a searchable provider model catalog');
    check(htmlContent.includes('insufficient_quota') && htmlContent.includes('OpenRouter: buscar modelos GRATIS'), 'settings explain quota errors and offer a free-model provider preset');
    for (const asset of ['/css/app.css', '/js/app.js', '/vendor/monaco/vs/loader.js', '/vendor/xterm/xterm.js', '/vendor/xterm-fit/addon-fit.js', '/vendor/xterm-css/xterm.css']) {
      const response = await request(base, asset);
      await expectStatus(response, 200, `${asset} should be served`);
      check(Number(response.headers.get('content-length') || 0) > 0, `${asset} should not be empty`);
      await response.arrayBuffer();
    }

    check(originAllowed({ headers: { origin: 'https://5000-preview.e2b.app', host: '127.0.0.1:5000', 'x-forwarded-host': '5000-preview.e2b.app' } }), 'terminal accepts the same-origin host forwarded by the preview proxy');
    check(!originAllowed({ headers: { origin: 'https://attacker.example', host: '127.0.0.1:5000' } }), 'terminal rejects a cross-origin WebSocket upgrade');

    const healthResponse = await request(base, '/api/health');
    await expectStatus(healthResponse, 200);
    const health = await json(healthResponse);
    check(health.name === 'Strata Engineering', 'health endpoint returns standalone Strata Engineering metadata');
    check(['pty', 'compatible'].includes(health.terminalMode), 'health reports the real terminal transport mode');
    check(Array.isArray(health.runtimes) && health.runtimes.some((item) => item.id === 'node' && item.available), 'Node.js runtime is detected');

    const initialTree = await json(await request(base, '/api/tree'));
    check(initialTree.ok && Array.isArray(initialTree.tree), 'tree endpoint returns a real workspace tree');

    let response = await request(base, '/api/file', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ path: 'src/hello.js', content: 'console.log("Strata API OK");\n' })
    });
    await expectStatus(response, 200);
    const created = await json(response);
    check(created.bytes > 0 && /^[a-f0-9]{64}$/.test(created.version), 'new file is written atomically and receives a content version');

    response = await request(base, '/api/file?path=src%2Fhello.js');
    await expectStatus(response, 200);
    const opened = await json(response);
    check(opened.content.includes('Strata API OK') && opened.language === 'javascript', 'file contents and language are returned');

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

    // === Enterprise 3.0 Verification Suite ===
    check(!html.headers.get('x-frame-options'), 'live preview must not be blocked by X-Frame-Options');

    // 1. 150 Specialist Engineering Agents across 15 Divisions
    const agentsResp = await json(await request(base, '/api/agents'));
    check(agentsResp.ok && agentsResp.total === 150, 'fleet exposes exactly 150 full-time specialist agents');
    check(Array.isArray(agentsResp.divisions) && agentsResp.divisions.length === 15, 'fleet is organized across 15 enterprise engineering divisions');
    check(agentsResp.agents[0].id === 'AG-001' && agentsResp.agents[149].id === 'AG-150', 'agent IDs span AG-001 through AG-150');

    const agentDetail = await json(await request(base, '/api/agents/AG-051'));
    check(agentDetail.ok && agentDetail.agent.id === 'AG-051' && agentDetail.agent.divisionId === 'debugging', 'individual specialist dossier endpoint returns complete specialist profile');

    // 2. Multi-LLM Free Provider Pool: presets, parallel testing, and automatic failover
    const presetsResp = await json(await request(base, '/api/providers/presets'));
    check(presetsResp.ok && presetsResp.presets.length >= 10, 'exposes at least 10 free/local/custom LLM provider presets');
    check(presetsResp.presets.some((p) => p.id === 'openrouter-free') && presetsResp.presets.some((p) => p.id === 'groq-free') && presetsResp.presets.some((p) => p.id === 'gemini-free'), 'includes OpenRouter, Groq, and Gemini free presets');

    const poolTestResp = await json(await request(base, '/api/providers/test-pool', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        providers: [
          { id: 'p-bad', name: 'Quota Exhausted', baseUrl: providerBase, model: 'quota-model', apiKey: 'k1', priority: 1, enabled: true },
          { id: 'p-ok', name: 'Healthy Free API', baseUrl: providerBase, model: 'healthy-free-model', apiKey: 'k2', priority: 2, enabled: true }
        ]
      })
    }));
    check(poolTestResp.ok && poolTestResp.total === 2 && poolTestResp.healthy === 1, 'pool tester verifies multiple providers concurrently and reports accurate health');

    // 3. Static Syntax Verification, OWASP Security Audit, Cartography, Auto-Repair
    await request(base, '/api/file', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        path: 'src/buggy.js',
        content: 'const apiKey = "sk-1234567890abcdefghijklmnop";\nfunction broken( { return eval("1+1"); }\n'
      })
    });
    const syntaxRes = await json(await request(base, '/api/tools/verify-syntax', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' }));
    check(syntaxRes.ok && syntaxRes.verification.ok === false && syntaxRes.verification.errorCount >= 1, 'static analyzer catches real JS syntax errors');

    const secRes = await json(await request(base, '/api/tools/security-audit', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' }));
    check(secRes.ok && secRes.audit.findings.length >= 2 && secRes.audit.score < 100, 'OWASP security auditor detects hardcoded API keys and eval()');

    const cartoRes = await json(await request(base, '/api/tools/cartography'));
    check(cartoRes.ok && cartoRes.cartography.totalFiles >= 2 && cartoRes.cartography.totalLines > 0, 'codebase cartography indexes files and languages');

    await request(base, '/api/delete', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ path: 'src/buggy.js' }) });

    // 4. Enterprise Project Scaffolding, Import & Export
    const scaffoldRes = await json(await request(base, '/api/project/scaffold', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ template: 'node-enterprise-api', targetDir: 'enterprise-api' })
    }));
    check(scaffoldRes.ok && scaffoldRes.files.length >= 4, 'project scaffolder generates enterprise Node API project');

    const exportRes = await json(await request(base, '/api/project/export'));
    check(exportRes.ok && exportRes.bundle.fileCount >= 4, 'workspace export bundles project files');

    // 5. Git Suite
    const gitInit = await json(await request(base, '/api/git/init', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' }));
    check(gitInit.ok, 'git init initializes repository in workspace');
    const gitCommit = await json(await request(base, '/api/git/commit', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ message: 'chore: initial enterprise scaffold' })
    }));
    check(gitCommit.ok, 'git commit commits workspace changes');
    const gitLog = await json(await request(base, '/api/git/log'));
    check(gitLog.ok && gitLog.commits.some((c) => c.message.includes('initial enterprise scaffold')), 'git log returns real commit history');

    // 6. Background Process Manager
    const procStart = await json(await request(base, '/api/processes/start', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ name: 'test-worker', command: 'node -e "console.log(\'worker-alive\'); setTimeout(()=>{}, 5000)"' })
    }));
    check(procStart.ok && procStart.process.id, 'background process manager launches live processes');
    const procStop = await json(await request(base, `/api/processes/${encodeURIComponent(procStart.process.id)}/stop`, { method: 'POST' }));
    check(procStop.ok, 'background process manager cleanly terminates processes');

    // 7. 150-Agent Mission Orchestrator (both Local Real Engine AND Multi-LLM Pool with Failover)
    const localMissionRes = await json(await request(base, '/api/missions', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ objective: 'Auditar arquitectura, sintaxis y seguridad del proyecto', mode: 'audit-and-heal', scale: 'compact', pool: { providers: [] } })
    }));
    check(localMissionRes.ok && localMissionRes.mission.id && localMissionRes.mission.agentCount === 6, 'mission orchestrator launches deterministic local mission');

    // Wait for local mission to complete
    let localFinal = null;
    for (let i = 0; i < 40; i++) {
      const m = await json(await request(base, `/api/missions/${encodeURIComponent(localMissionRes.mission.id)}`));
      if (m.mission.status !== 'running') { localFinal = m.mission; break; }
      await new Promise((r) => setTimeout(r, 150));
    }
    check(localFinal && localFinal.status === 'completed' && localFinal.report.includes('Informe Ejecutivo de Misión'), 'local deterministic mission completes and produces a real engineering report');

    // Multi-LLM mission with automatic failover from quota-model -> healthy-free-model
    const llmMissionRes = await json(await request(base, '/api/missions', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        objective: 'Revisar el servicio enterprise-api',
        mode: 'squad-dispatch',
        scale: 'custom',
        agentIds: ['AG-001', 'AG-051'],
        pool: {
          strategy: 'priority-failover',
          providers: [
            { id: 'fail-primary', name: 'Quota Primary', baseUrl: providerBase, model: 'quota-model', apiKey: 'k1', priority: 1, enabled: true },
            { id: 'healthy-backup', name: 'Healthy Backup', baseUrl: providerBase, model: 'healthy-free-model', apiKey: 'k2', priority: 2, enabled: true }
          ]
        }
      })
    }));
    check(llmMissionRes.ok && llmMissionRes.mission.agentCount === 2, 'multi-LLM mission dispatches selected specialists');
    let llmFinal = null;
    for (let i = 0; i < 60; i++) {
      const m = await json(await request(base, `/api/missions/${encodeURIComponent(llmMissionRes.mission.id)}`));
      if (m.mission.status !== 'running') { llmFinal = m.mission; break; }
      await new Promise((r) => setTimeout(r, 150));
    }
    check(llmFinal && llmFinal.status === 'completed' && llmFinal.engineMode === 'llm-pool', 'multi-LLM mission succeeds via automatic provider failover');

    // 8. Direct 1-on-1 Specialist Agent Chat with fresh failing primary -> automatic SSE failover
    const chatResp = await request(base, '/api/agents/AG-051/chat', {
      method: 'POST',
      headers: { 'content-type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({
        sessionId: 'int-test-session',
        message: 'Confirma disponibilidad.',
        pool: {
          strategy: 'priority-failover',
          providers: [
            { id: 'fail-chat-primary', name: 'Quota Chat Primary', baseUrl: providerBase, model: 'quota-model', apiKey: 'k1', priority: 1, enabled: true },
            { id: 'healthy-chat-backup', name: 'Healthy Chat Backup', baseUrl: providerBase, model: 'healthy-free-model', apiKey: 'k2', priority: 2, enabled: true }
          ]
        }
      })
    });
    await expectStatus(chatResp, 200);
    const chatBody = await chatResp.text();
    check(chatBody.includes('"type":"failover"') && chatBody.includes('"type":"final"'), 'direct specialist chat streams failover and final SSE events');

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

    console.log(`Strata Engineering integration: ${assertions} assertions passed.`);
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
  console.error('Strata Engineering integration failed:', error);
  process.exitCode = 1;
});
