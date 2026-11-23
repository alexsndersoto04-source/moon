import http from 'node:http';
import path from 'node:path';
import fs from 'node:fs';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import express from 'express';
import cors from 'cors';
import { WebSocketServer } from 'ws';
import {
  createCheckpoint,
  createProject,
  createProjectDirectory,
  deleteProjectPath,
  extractSymbols,
  getFileTree,
  getProjectDir,
  listCheckpoints,
  listProjects,
  readProjectFile,
  renameProjectPath,
  rollbackCheckpoint,
  runProjectGit,
  safeResolveInProject,
  searchInProject,
  writeProjectFile,
} from './runtime/workspace.ts';
import { getAvailableRuntimes, runProjectFile, runShellCommand, runSnippet } from './runtime/executor.ts';
import { checkProjectDiagnostics } from './runtime/diagnostics.ts';
import { listBrainModels, testBrainConnection } from './agent/brainAdapter.ts';
import { abortAgentRun, resolveToolApproval, runAgentLoop } from './agent/orchestrator.ts';
import { IDE_TOOLS } from './agent/tools.ts';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT_DIR = path.resolve(__dirname, '..');
const PTY_BRIDGE_PATH = path.join(__dirname, 'runtime', 'ptyBridge.py');

async function startServer() {
  const app = express();
  const server = http.createServer(app);

  app.use(cors());
  app.use(express.json({ limit: '15mb' }));

  // Ensure iframe preview & sandbox preview work seamlessly
  app.use((_req, res, next) => {
    res.removeHeader('X-Frame-Options');
    next();
  });

  // Health & Runtimes
  app.get('/api/health', async (_req, res) => {
    res.json({ ok: true, name: 'KODE KSST Backend', timestamp: new Date().toISOString() });
  });

  app.get('/api/runtimes', async (_req, res) => {
    try {
      const runtimes = await getAvailableRuntimes();
      res.json({ runtimes });
    } catch (err: any) {
      res.status(500).json({ error: err.message });
    }
  });

  // Projects
  app.get('/api/projects', (_req, res) => {
    try {
      res.json({ projects: listProjects() });
    } catch (err: any) {
      res.status(500).json({ error: err.message });
    }
  });

  app.post('/api/projects', (req, res) => {
    try {
      const project = createProject(req.body);
      res.json({ project, projects: listProjects() });
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  // File Tree & CRUD
  app.get('/api/projects/:id/tree', (req, res) => {
    try {
      const tree = getFileTree(req.params.id);
      res.json({ tree });
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  app.get('/api/projects/:id/file', (req, res) => {
    try {
      const filePath = String(req.query.path || '');
      const data = readProjectFile(req.params.id, filePath);
      const symbols = extractSymbols(data.content, data.language);
      res.json({ ...data, path: filePath, symbols });
    } catch (err: any) {
      res.status(404).json({ error: err.message });
    }
  });

  app.put('/api/projects/:id/file', (req, res) => {
    try {
      const { path: filePath, content } = req.body;
      const result = writeProjectFile(req.params.id, filePath, content ?? '');
      res.json(result);
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  app.delete('/api/projects/:id/file', (req, res) => {
    try {
      const filePath = String(req.query.path || req.body?.path || '');
      deleteProjectPath(req.params.id, filePath);
      res.json({ deleted: filePath });
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  app.post('/api/projects/:id/mkdir', (req, res) => {
    try {
      createProjectDirectory(req.params.id, req.body.path);
      res.json({ created: req.body.path });
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  app.post('/api/projects/:id/rename', (req, res) => {
    try {
      renameProjectPath(req.params.id, req.body.oldPath, req.body.newPath);
      res.json({ renamed: true });
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  app.get('/api/projects/:id/search', (req, res) => {
    try {
      const q = String(req.query.q || '');
      const isRegex = req.query.regex === 'true';
      const matches = searchInProject(req.params.id, q, isRegex);
      res.json({ matches });
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  // Execution & Diagnostics
  app.post('/api/projects/:id/run', async (req, res) => {
    try {
      const result = await runProjectFile({
        projectId: req.params.id,
        filePath: req.body.filePath,
        stdin: req.body.stdin,
        args: req.body.args,
        timeoutMs: req.body.timeoutMs,
        languageOverride: req.body.languageOverride,
      });
      res.json(result);
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  app.post('/api/projects/:id/run-snippet', async (req, res) => {
    try {
      const result = await runSnippet({
        projectId: req.params.id,
        language: req.body.language,
        code: req.body.code,
        stdin: req.body.stdin,
        timeoutMs: req.body.timeoutMs,
      });
      res.json(result);
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  app.post('/api/projects/:id/exec', async (req, res) => {
    try {
      const result = await runShellCommand({
        projectId: req.params.id,
        command: req.body.command,
        stdin: req.body.stdin,
        timeoutMs: req.body.timeoutMs,
      });
      res.json(result);
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  app.post('/api/projects/:id/diagnostics', async (req, res) => {
    try {
      const diagnostics = await checkProjectDiagnostics(req.params.id, req.body?.filePath);
      res.json({ diagnostics });
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  // Checkpoints & Git
  app.get('/api/projects/:id/checkpoints', (req, res) => {
    try {
      res.json({ checkpoints: listCheckpoints(req.params.id) });
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  app.post('/api/projects/:id/checkpoints', (req, res) => {
    try {
      const checkpoint = createCheckpoint(req.params.id, req.body?.label || 'Manual snapshot');
      res.json({ checkpoint });
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  app.post('/api/projects/:id/checkpoints/:cpId/rollback', (req, res) => {
    try {
      const result = rollbackCheckpoint(req.params.id, req.params.cpId);
      res.json(result);
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  app.post('/api/projects/:id/git', async (req, res) => {
    try {
      const result = await runProjectGit(req.params.id, req.body.action, req.body.message);
      res.json(result);
    } catch (err: any) {
      res.status(400).json({ error: err.message });
    }
  });

  // Agent & Brain Endpoints
  app.get('/api/agent/tools', (_req, res) => {
    res.json({ tools: IDE_TOOLS });
  });

  app.post('/api/agent/test-brain', async (req, res) => {
    try {
      const result = await testBrainConnection(req.body.brain);
      res.json(result);
    } catch (err: any) {
      res.status(400).json({ ok: false, error: err.message });
    }
  });

  app.post('/api/agent/list-models', async (req, res) => {
    try {
      const models = await listBrainModels(req.body.brain);
      res.json({ ok: true, models });
    } catch (err: any) {
      res.status(400).json({ ok: false, error: err.message });
    }
  });

  app.post('/api/agent/run', async (req, res) => {
    res.setHeader('Content-Type', 'text/event-stream; charset=utf-8');
    res.setHeader('Cache-Control', 'no-cache, no-transform');
    res.setHeader('Connection', 'keep-alive');
    res.setHeader('X-Accel-Buffering', 'no');
    res.flushHeaders?.();

    const sendEvent = (event: string, data: any) => {
      if (res.writableEnded) return;
      res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    };

    const runId = req.body.runId || `run_${Date.now()}`;
    res.on('close', () => {
      if (!res.writableEnded) {
        abortAgentRun(runId);
      }
    });

    const heartbeat = setInterval(() => {
      if (!res.writableEnded) {
        res.write(': ping\n\n');
      }
    }, 15000);

    try {
      await runAgentLoop(
        {
          runId,
          brain: req.body.brain,
          prompt: req.body.prompt,
          history: req.body.history || [],
          snapshot: req.body.snapshot,
          autoApprove: req.body.autoApprove !== false,
          maxSteps: req.body.maxSteps || 20,
        },
        sendEvent
      );
    } finally {
      clearInterval(heartbeat);
      if (!res.writableEnded) res.end();
    }
  });

  app.post('/api/agent/abort', (req, res) => {
    const ok = abortAgentRun(req.body.runId);
    res.json({ aborted: ok });
  });

  app.post('/api/agent/approve', (req, res) => {
    const ok = resolveToolApproval(req.body.approvalId, Boolean(req.body.approved));
    res.json({ resolved: ok });
  });

  // Live Static Web Preview for workspace projects
  app.use('/preview/:projectId', (req, res) => {
    try {
      const rel = req.path === '/' ? 'index.html' : req.path.replace(/^\/+/, '');
      const fullPath = safeResolveInProject(req.params.projectId, rel);
      if (!fs.existsSync(fullPath) || fs.statSync(fullPath).isDirectory()) {
        res.status(404).send(`Not found: ${rel}`);
        return;
      }
      res.setHeader('Cache-Control', 'no-store');
      res.sendFile(fullPath);
    } catch (err: any) {
      res.status(400).send(err.message);
    }
  });

  // Interactive PTY Terminal via WebSocket
  const wss = new WebSocketServer({ noServer: true });
  server.on('upgrade', (request, socket, head) => {
    const url = new URL(request.url || '/', 'http://localhost');
    if (url.pathname === '/ws/terminal') {
      wss.handleUpgrade(request, socket, head, (ws) => {
        wss.emit('connection', ws, request);
      });
    } else if (process.env.NODE_ENV === 'production') {
      socket.destroy();
    }
  });

  wss.on('connection', (ws, request) => {
    const url = new URL(request.url || '/', 'http://localhost');
    const projectId = url.searchParams.get('projectId') || '';
    let cwd = ROOT_DIR;
    try {
      if (projectId) cwd = getProjectDir(projectId);
    } catch {
      cwd = ROOT_DIR;
    }

    const ptyProc = spawn('python3', ['-u', PTY_BRIDGE_PATH, cwd], {
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    let lineBuf = '';
    ptyProc.stdout.on('data', (chunk: Buffer) => {
      lineBuf += chunk.toString('utf8');
      let nlIdx: number;
      while ((nlIdx = lineBuf.indexOf('\n')) !== -1) {
        const line = lineBuf.slice(0, nlIdx).trim();
        lineBuf = lineBuf.slice(nlIdx + 1);
        if (!line) continue;
        try {
          const msg = JSON.parse(line);
          if (msg.type === 'output' && ws.readyState === 1) {
            const text = Buffer.from(msg.data, 'base64').toString('utf8');
            ws.send(JSON.stringify({ type: 'output', data: text }));
          } else if (msg.type === 'exit' && ws.readyState === 1) {
            ws.send(JSON.stringify({ type: 'exit', code: msg.code }));
          }
        } catch {}
      }
    });

    ptyProc.stderr.on('data', (chunk: Buffer) => {
      if (ws.readyState === 1) {
        ws.send(JSON.stringify({ type: 'output', data: chunk.toString('utf8') }));
      }
    });

    ws.on('message', (raw) => {
      try {
        const msg = JSON.parse(raw.toString('utf8'));
        if (msg.type === 'input') {
          const payload = JSON.stringify({
            type: 'input',
            data: Buffer.from(msg.data, 'utf8').toString('base64'),
          });
          ptyProc.stdin.write(payload + '\n');
        } else if (msg.type === 'resize') {
          const payload = JSON.stringify({
            type: 'resize',
            cols: msg.cols,
            rows: msg.rows,
          });
          ptyProc.stdin.write(payload + '\n');
        }
      } catch {}
    });

    ws.on('close', () => {
      ptyProc.kill('SIGTERM');
    });
  });

  // Serve pre-minified Monaco Editor assets locally (zero CDN, fast mobile/cloud builds)
  app.use('/monaco-vs', express.static(path.join(ROOT_DIR, 'node_modules/monaco-editor/min/vs')));

  // Frontend serving: Vite middleware in dev, dist/ in production
  const isProd = process.env.NODE_ENV === 'production';
  if (isProd) {
    const distDir = path.join(ROOT_DIR, 'dist');
    app.use(express.static(distDir));
    app.use((_req, res) => {
      res.sendFile(path.join(distDir, 'index.html'));
    });
  } else {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      root: ROOT_DIR,
      server: {
        middlewareMode: true,
        hmr: { server },
        allowedHosts: true,
      },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  }

  const PORT = Number(process.env.PORT || 3000);
  server.listen(PORT, '0.0.0.0', () => {
    console.log(`KODE KSST server listening on http://0.0.0.0:${PORT} (${isProd ? 'production' : 'development'})`);
  });
}

startServer();
