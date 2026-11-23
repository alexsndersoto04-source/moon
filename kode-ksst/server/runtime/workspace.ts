import fs from 'node:fs';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

const execFileAsync = promisify(execFile);
const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const IDE_ROOT = path.resolve(__dirname, '../..');
export const WORKSPACES_ROOT = path.join(IDE_ROOT, 'workspaces');
export const CHECKPOINTS_ROOT = path.join(IDE_ROOT, '.checkpoints');

export interface FileNode {
  name: string;
  path: string; // relative to project root, forward slashes
  type: 'file' | 'directory';
  size?: number;
  language?: string;
  children?: FileNode[];
}

export interface ProjectInfo {
  id: string;
  name: string;
  description: string;
  primaryLanguage: string;
  entryFile?: string;
  fileCount: number;
  hasGit: boolean;
  updatedAt: string;
}

export interface SearchMatch {
  file: string;
  line: number;
  column: number;
  preview: string;
}

export interface SymbolItem {
  name: string;
  kind: 'function' | 'class' | 'method' | 'variable' | 'interface' | 'struct' | 'section';
  line: number;
  detail?: string;
}

export interface CheckpointInfo {
  id: string;
  label: string;
  createdAt: string;
  fileCount: number;
}

const IGNORED_DIRS = new Set([
  'node_modules',
  '.git',
  '__pycache__',
  '.venv',
  'dist',
  'build',
  '.next',
  '.nexus-checkpoints',
]);

export function detectLanguage(filePath: string): string {
  const ext = path.extname(filePath).toLowerCase();
  const base = path.basename(filePath).toLowerCase();
  if (base === 'makefile') return 'makefile';
  if (base === 'dockerfile') return 'dockerfile';
  const map: Record<string, string> = {
    '.ts': 'typescript',
    '.tsx': 'typescript',
    '.js': 'javascript',
    '.mjs': 'javascript',
    '.cjs': 'javascript',
    '.jsx': 'javascript',
    '.py': 'python',
    '.c': 'c',
    '.h': 'c',
    '.cpp': 'cpp',
    '.cc': 'cpp',
    '.cxx': 'cpp',
    '.hpp': 'cpp',
    '.hh': 'cpp',
    '.html': 'html',
    '.htm': 'html',
    '.css': 'css',
    '.scss': 'scss',
    '.json': 'json',
    '.md': 'markdown',
    '.sh': 'shell',
    '.bash': 'shell',
    '.sql': 'sql',
    '.pl': 'perl',
    '.yaml': 'yaml',
    '.yml': 'yaml',
    '.xml': 'xml',
    '.txt': 'plaintext',
    '.env': 'plaintext',
  };
  return map[ext] || 'plaintext';
}

export function ensureDirs() {
  fs.mkdirSync(WORKSPACES_ROOT, { recursive: true });
  fs.mkdirSync(CHECKPOINTS_ROOT, { recursive: true });
}

export function sanitizeProjectId(id: string): string {
  const clean = id.replace(/[^a-zA-Z0-9._-]/g, '-').replace(/^\.+/, '');
  if (!clean) throw new Error('Invalid project ID');
  return clean;
}

export function getProjectDir(projectId: string): string {
  const safeId = sanitizeProjectId(projectId);
  const full = path.resolve(WORKSPACES_ROOT, safeId);
  if (!full.startsWith(WORKSPACES_ROOT + path.sep)) {
    throw new Error('Invalid project path');
  }
  return full;
}

export function safeResolveInProject(projectId: string, relPath: string): string {
  const projectDir = getProjectDir(projectId);
  const normalized = (relPath || '').replace(/\\/g, '/').replace(/^\/+/, '');
  const resolved = path.resolve(projectDir, normalized);
  if (resolved !== projectDir && !resolved.startsWith(projectDir + path.sep)) {
    throw new Error(`Path traversal blocked: ${relPath}`);
  }
  return resolved;
}

function countFiles(dir: string): number {
  let count = 0;
  if (!fs.existsSync(dir)) return 0;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (IGNORED_DIRS.has(entry.name)) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) count += countFiles(full);
    else count++;
  }
  return count;
}

function readManifest(projectDir: string): Partial<ProjectInfo> {
  const manifestPath = path.join(projectDir, '.nexus.json');
  if (fs.existsSync(manifestPath)) {
    try {
      return JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    } catch {
      return {};
    }
  }
  return {};
}

function writeManifest(projectDir: string, data: Partial<ProjectInfo>) {
  const manifestPath = path.join(projectDir, '.nexus.json');
  fs.writeFileSync(manifestPath, JSON.stringify(data, null, 2), 'utf8');
}

export function listProjects(): ProjectInfo[] {
  ensureDirs();
  seedDefaultProjectsIfEmpty();
  const entries = fs.readdirSync(WORKSPACES_ROOT, { withFileTypes: true });
  const projects: ProjectInfo[] = [];
  for (const entry of entries) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
    const dir = path.join(WORKSPACES_ROOT, entry.name);
    const stat = fs.statSync(dir);
    const manifest = readManifest(dir);
    projects.push({
      id: entry.name,
      name: manifest.name || entry.name,
      description: manifest.description || 'Workspace project',
      primaryLanguage: manifest.primaryLanguage || 'multi',
      entryFile: manifest.entryFile,
      fileCount: countFiles(dir),
      hasGit: fs.existsSync(path.join(dir, '.git')),
      updatedAt: stat.mtime.toISOString(),
    });
  }
  return projects.sort((a, b) => a.id.localeCompare(b.id));
}

export function createProject(opts: {
  id: string;
  name?: string;
  description?: string;
  template?: string;
}): ProjectInfo {
  ensureDirs();
  const safeId = sanitizeProjectId(opts.id);
  const projectDir = getProjectDir(safeId);
  if (fs.existsSync(projectDir)) {
    throw new Error(`Project "${safeId}" already exists`);
  }
  fs.mkdirSync(projectDir, { recursive: true });

  const tpl = opts.template || 'node-ts';
  const templates: Record<string, { lang: string; entry: string; files: Record<string, string> }> = {
    'node-ts': {
      lang: 'typescript',
      entry: 'src/index.ts',
      files: {
        'src/index.ts': `interface Task {\n  id: number;\n  title: string;\n  done: boolean;\n}\n\nconst tasks: Task[] = [\n  { id: 1, title: 'Initialize TypeScript engine', done: true },\n  { id: 2, title: 'Connect AI Agent Brain', done: false },\n];\n\nconsole.log('=== KODE KSST TypeScript Project ===');\nfor (const t of tasks) {\n  console.log(\`[\${t.done ? 'x' : ' '}] #\${t.id}: \${t.title}\`);\n}\n`,
        'README.md': `# ${opts.name || safeId}\n\nTypeScript project ready for immediate execution with KODE KSST.\n`,
      },
    },
    'python-app': {
      lang: 'python',
      entry: 'main.py',
      files: {
        'main.py': `from math import sqrt\n\ndef primes_up_to(limit: int) -> list[int]:\n    sieve = [True] * (limit + 1)\n    sieve[0:2] = [False, False]\n    for n in range(2, int(sqrt(limit)) + 1):\n        if sieve[n]:\n            for m in range(n * n, limit + 1, n):\n                sieve[m] = False\n    return [i for i, ok in enumerate(sieve) if ok]\n\nif __name__ == '__main__':\n    primes = primes_up_to(50)\n    print(f"Found {len(primes)} primes <= 50: {primes}")\n`,
        'test_main.py': `import unittest\nfrom main import primes_up_to\n\nclass TestPrimes(unittest.TestCase):\n    def test_primes(self):\n        self.assertEqual(primes_up_to(10), [2, 3, 5, 7])\n\nif __name__ == '__main__':\n    unittest.main()\n`,
      },
    },
    'cpp-project': {
      lang: 'cpp',
      entry: 'main.cpp',
      files: {
        'main.cpp': `#include <iostream>\n#include <vector>\n#include <numeric>\n\nint main() {\n    std::vector<int> nums = {10, 20, 30, 40, 50};\n    int total = std::accumulate(nums.begin(), nums.end(), 0);\n    std::cout << "C++20 Engine Ready! Sum = " << total << std::endl;\n    return 0;\n}\n`,
      },
    },
    'c-project': {
      lang: 'c',
      entry: 'main.c',
      files: {
        'main.c': `#include <stdio.h>\n\nint fib(int n) {\n    if (n <= 1) return n;\n    int a = 0, b = 1;\n    for (int i = 2; i <= n; i++) {\n        int c = a + b;\n        a = b;\n        b = c;\n    }\n    return b;\n}\n\nint main(void) {\n    printf("C17 Fibonacci sequence:\\n");\n    for (int i = 0; i < 12; i++) {\n        printf("F(%d) = %d\\n", i, fib(i));\n    }\n    return 0;\n}\n`,
      },
    },
    'web-app': {
      lang: 'html',
      entry: 'index.html',
      files: {
        'index.html': `<!doctype html>\n<html lang="en">\n<head>\n  <meta charset="UTF-8" />\n  <title>${opts.name || safeId}</title>\n  <link rel="stylesheet" href="style.css" />\n</head>\n<body>\n  <main class="card">\n    <h1>Web Studio Ready</h1>\n    <p id="status">Click the button to run interactive JS.</p>\n    <button id="btn">Trigger Pulse</button>\n  </main>\n  <script src="app.js"></script>\n</body>\n</html>\n`,
        'style.css': `body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #090d16; color: #e2e8f0; font-family: system-ui, sans-serif; }\n.card { padding: 2rem; border-radius: 16px; background: #111827; border: 1px solid #1e293b; text-align: center; box-shadow: 0 20px 50px rgba(0,0,0,.5); }\nbutton { margin-top: 1rem; padding: .75rem 1.5rem; border-radius: 10px; border: 0; background: #38bdf8; color: #082f49; font-weight: 700; cursor: pointer; }\n`,
        'app.js': `let clicks = 0;\ndocument.getElementById('btn').addEventListener('click', () => {\n  clicks++;\n  document.getElementById('status').textContent = \`Interactive events fired: \${clicks}\`;\n});\n`,
      },
    },
    'sql-lab': {
      lang: 'sql',
      entry: 'queries.sql',
      files: {
        'queries.sql': `CREATE TABLE IF NOT EXISTS engineers (\n  id INTEGER PRIMARY KEY AUTOINCREMENT,\n  name TEXT NOT NULL,\n  specialty TEXT NOT NULL,\n  level INTEGER NOT NULL\n);\n\nINSERT INTO engineers (name, specialty, level) VALUES\n  ('Ada Lovelace', 'Algorithms', 10),\n  ('Linus Torvalds', 'Systems C', 10),\n  ('Grace Hopper', 'Compilers', 10);\n\nSELECT id, name, specialty, level FROM engineers ORDER BY id DESC LIMIT 10;\n`,
      },
    },
    empty: {
      lang: 'plaintext',
      entry: 'README.md',
      files: {
        'README.md': `# ${opts.name || safeId}\n\nEmpty workspace created in KODE KSST.\n`,
      },
    },
  };

  const chosen = templates[tpl] || templates['node-ts'];
  for (const [rel, content] of Object.entries(chosen.files)) {
    const dest = safeResolveInProject(safeId, rel);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest, content, 'utf8');
  }

  writeManifest(projectDir, {
    name: opts.name || safeId,
    description: opts.description || `Project (${tpl})`,
    primaryLanguage: chosen.lang,
    entryFile: chosen.entry,
  });

  return listProjects().find((p) => p.id === safeId)!;
}

export function getFileTree(projectId: string, subPath = ''): FileNode[] {
  const root = safeResolveInProject(projectId, subPath);
  const projectRoot = getProjectDir(projectId);
  if (!fs.existsSync(root)) return [];

  const entries = fs.readdirSync(root, { withFileTypes: true });
  const nodes: FileNode[] = [];
  for (const entry of entries) {
    if (IGNORED_DIRS.has(entry.name) || entry.name === '.nexus.json') continue;
    const full = path.join(root, entry.name);
    const rel = path.relative(projectRoot, full).replace(/\\/g, '/');
    if (entry.isDirectory()) {
      nodes.push({
        name: entry.name,
        path: rel,
        type: 'directory',
        children: getFileTree(projectId, rel),
      });
    } else {
      const stat = fs.statSync(full);
      nodes.push({
        name: entry.name,
        path: rel,
        type: 'file',
        size: stat.size,
        language: detectLanguage(entry.name),
      });
    }
  }
  return nodes.sort((a, b) => {
    if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
    return a.name.localeCompare(b.name);
  });
}

export function flattenFiles(nodes: FileNode[]): string[] {
  const out: string[] = [];
  for (const n of nodes) {
    if (n.type === 'file') out.push(n.path);
    else if (n.children) out.push(...flattenFiles(n.children));
  }
  return out;
}

export function readProjectFile(projectId: string, relPath: string): { content: string; language: string; size: number } {
  const full = safeResolveInProject(projectId, relPath);
  if (!fs.existsSync(full) || fs.statSync(full).isDirectory()) {
    throw new Error(`File not found: ${relPath}`);
  }
  const content = fs.readFileSync(full, 'utf8');
  return {
    content,
    language: detectLanguage(relPath),
    size: Buffer.byteLength(content, 'utf8'),
  };
}

export function writeProjectFile(projectId: string, relPath: string, content: string): { path: string; size: number; created: boolean } {
  const full = safeResolveInProject(projectId, relPath);
  const created = !fs.existsSync(full);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, content, 'utf8');
  return {
    path: relPath.replace(/\\/g, '/'),
    size: Buffer.byteLength(content, 'utf8'),
    created,
  };
}

export function editProjectFile(
  projectId: string,
  relPath: string,
  oldText: string,
  newText: string,
  replaceAll = false
): { path: string; replacements: number; content: string } {
  const { content } = readProjectFile(projectId, relPath);
  if (!oldText) {
    throw new Error('oldText cannot be empty');
  }
  const parts = content.split(oldText);
  const occurrences = parts.length - 1;
  if (occurrences === 0) {
    throw new Error(`oldText was not found in ${relPath}. Read the file first to copy exact lines.`);
  }
  if (!replaceAll && occurrences > 1) {
    throw new Error(`oldText matched ${occurrences} places in ${relPath}. Provide more context or set replaceAll=true.`);
  }
  const updated = replaceAll ? parts.join(newText) : content.replace(oldText, newText);
  writeProjectFile(projectId, relPath, updated);
  return {
    path: relPath,
    replacements: replaceAll ? occurrences : 1,
    content: updated,
  };
}

export function createProjectDirectory(projectId: string, relPath: string) {
  const full = safeResolveInProject(projectId, relPath);
  fs.mkdirSync(full, { recursive: true });
}

export function deleteProjectPath(projectId: string, relPath: string) {
  const full = safeResolveInProject(projectId, relPath);
  const projectRoot = getProjectDir(projectId);
  if (full === projectRoot) throw new Error('Cannot delete project root');
  if (!fs.existsSync(full)) throw new Error(`Path does not exist: ${relPath}`);
  fs.rmSync(full, { recursive: true, force: true });
}

export function renameProjectPath(projectId: string, oldRelPath: string, newRelPath: string) {
  const src = safeResolveInProject(projectId, oldRelPath);
  const dest = safeResolveInProject(projectId, newRelPath);
  if (!fs.existsSync(src)) throw new Error(`Source does not exist: ${oldRelPath}`);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.renameSync(src, dest);
}

export function searchInProject(projectId: string, query: string, isRegex = false, maxResults = 150): SearchMatch[] {
  const files = flattenFiles(getFileTree(projectId));
  const matches: SearchMatch[] = [];
  if (!query) return matches;

  let matcher: RegExp;
  try {
    matcher = isRegex ? new RegExp(query, 'gi') : new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi');
  } catch (err: any) {
    throw new Error(`Invalid regex: ${err.message}`);
  }

  for (const rel of files) {
    if (matches.length >= maxResults) break;
    const full = safeResolveInProject(projectId, rel);
    const stat = fs.statSync(full);
    if (stat.size > 1024 * 1024) continue; // skip >1MB files
    let content: string;
    try {
      content = fs.readFileSync(full, 'utf8');
    } catch {
      continue;
    }
    if (content.includes('\0')) continue; // skip binary
    const lines = content.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      matcher.lastIndex = 0;
      const m = matcher.exec(lines[i]);
      if (m) {
        matches.push({
          file: rel,
          line: i + 1,
          column: m.index + 1,
          preview: lines[i].trim().slice(0, 180),
        });
        if (matches.length >= maxResults) break;
      }
    }
  }
  return matches;
}

export function extractSymbols(content: string, language: string): SymbolItem[] {
  const lines = content.split(/\r?\n/);
  const symbols: SymbolItem[] = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const lineNum = i + 1;
    if (language === 'javascript' || language === 'typescript') {
      let m = line.match(/^\s*(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_$][\w$]*)/);
      if (m) symbols.push({ name: m[1], kind: 'function', line: lineNum });
      m = line.match(/^\s*(?:export\s+)?class\s+([A-Za-z_$][\w$]*)/);
      if (m) symbols.push({ name: m[1], kind: 'class', line: lineNum });
      m = line.match(/^\s*(?:export\s+)?interface\s+([A-Za-z_$][\w$]*)/);
      if (m) symbols.push({ name: m[1], kind: 'interface', line: lineNum });
      m = line.match(/^\s*(?:export\s+)?(?:const|let)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s+)?\(/);
      if (m) symbols.push({ name: m[1], kind: 'function', line: lineNum });
    } else if (language === 'python') {
      let m = line.match(/^(\s*)def\s+([A-Za-z_]\w*)\s*\(/);
      if (m) symbols.push({ name: m[2], kind: m[1].length > 0 ? 'method' : 'function', line: lineNum });
      m = line.match(/^\s*class\s+([A-Za-z_]\w*)/);
      if (m) symbols.push({ name: m[1], kind: 'class', line: lineNum });
    } else if (language === 'c' || language === 'cpp') {
      let m = line.match(/^\s*(?:class|struct)\s+([A-Za-z_]\w*)/);
      if (m) symbols.push({ name: m[1], kind: 'struct', line: lineNum });
      m = line.match(/^(?:[A-Za-z_][\w:<>*&\s]+)\s+([A-Za-z_][\w:]*)\s*\([^;]*\)\s*(?:const)?\s*\{?\s*$/);
      if (m && !['if', 'for', 'while', 'switch'].includes(m[1])) {
        symbols.push({ name: m[1], kind: 'function', line: lineNum });
      }
    } else if (language === 'markdown') {
      const m = line.match(/^(#{1,3})\s+(.+)/);
      if (m) symbols.push({ name: m[2].trim(), kind: 'section', line: lineNum });
    }
  }
  return symbols;
}

export function createCheckpoint(projectId: string, label: string): CheckpointInfo {
  ensureDirs();
  const projectDir = getProjectDir(projectId);
  const cpBase = path.join(CHECKPOINTS_ROOT, sanitizeProjectId(projectId));
  fs.mkdirSync(cpBase, { recursive: true });
  const id = `cp-${Date.now()}`;
  const cpDir = path.join(cpBase, id);
  fs.mkdirSync(cpDir, { recursive: true });

  const files = flattenFiles(getFileTree(projectId));
  for (const rel of files) {
    const src = safeResolveInProject(projectId, rel);
    const dest = path.join(cpDir, 'files', rel);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.copyFileSync(src, dest);
  }
  const meta: CheckpointInfo = {
    id,
    label: label || 'Manual checkpoint',
    createdAt: new Date().toISOString(),
    fileCount: files.length,
  };
  fs.writeFileSync(path.join(cpDir, 'meta.json'), JSON.stringify(meta, null, 2), 'utf8');
  // Touch manifest so project exists
  void projectDir;
  return meta;
}

export function listCheckpoints(projectId: string): CheckpointInfo[] {
  const cpBase = path.join(CHECKPOINTS_ROOT, sanitizeProjectId(projectId));
  if (!fs.existsSync(cpBase)) return [];
  const items: CheckpointInfo[] = [];
  for (const entry of fs.readdirSync(cpBase, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const metaPath = path.join(cpBase, entry.name, 'meta.json');
    if (fs.existsSync(metaPath)) {
      try {
        items.push(JSON.parse(fs.readFileSync(metaPath, 'utf8')));
      } catch {}
    }
  }
  return items.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

export function rollbackCheckpoint(projectId: string, checkpointId: string): { restoredFiles: number } {
  const projectDir = getProjectDir(projectId);
  const cpFilesDir = path.join(CHECKPOINTS_ROOT, sanitizeProjectId(projectId), sanitizeProjectId(checkpointId), 'files');
  if (!fs.existsSync(cpFilesDir)) {
    throw new Error(`Checkpoint not found: ${checkpointId}`);
  }
  // Remove current project files (except .git and .nexus.json)
  for (const entry of fs.readdirSync(projectDir, { withFileTypes: true })) {
    if (entry.name === '.git' || entry.name === '.nexus.json' || entry.name === 'node_modules') continue;
    fs.rmSync(path.join(projectDir, entry.name), { recursive: true, force: true });
  }
  let count = 0;
  function copyRec(srcDir: string, destDir: string) {
    fs.mkdirSync(destDir, { recursive: true });
    for (const entry of fs.readdirSync(srcDir, { withFileTypes: true })) {
      const s = path.join(srcDir, entry.name);
      const d = path.join(destDir, entry.name);
      if (entry.isDirectory()) copyRec(s, d);
      else {
        fs.copyFileSync(s, d);
        count++;
      }
    }
  }
  copyRec(cpFilesDir, projectDir);
  return { restoredFiles: count };
}

export async function runProjectGit(
  projectId: string,
  action: 'init' | 'status' | 'diff' | 'log' | 'add_all' | 'commit',
  message?: string
): Promise<{ stdout: string; stderr: string; exitCode: number }> {
  const cwd = getProjectDir(projectId);
  const run = async (args: string[]) => {
    try {
      const { stdout, stderr } = await execFileAsync('git', args, { cwd, timeout: 10000 });
      return { stdout, stderr, exitCode: 0 };
    } catch (err: any) {
      return {
        stdout: err.stdout || '',
        stderr: err.stderr || err.message || 'git error',
        exitCode: typeof err.code === 'number' ? err.code : 1,
      };
    }
  };

  if (action === 'init') {
    const r = await run(['init']);
    await run(['config', 'user.name', 'KODE KSST User']);
    await run(['config', 'user.email', 'user@kode-ksst.local']);
    return r;
  }
  if (action === 'status') return run(['status', '--short', '--branch']);
  if (action === 'diff') return run(['diff']);
  if (action === 'log') return run(['log', '-n', '15', '--oneline']);
  if (action === 'add_all') return run(['add', '-A']);
  if (action === 'commit') {
    await run(['add', '-A']);
    return run(['commit', '-m', message || 'Checkpoint commit from KODE KSST']);
  }
  throw new Error(`Unsupported git action: ${action}`);
}

export function seedDefaultProjectsIfEmpty() {
  ensureDirs();
  const existing = fs.readdirSync(WORKSPACES_ROOT, { withFileTypes: true }).filter((d) => d.isDirectory() && !d.name.startsWith('.'));
  if (existing.length > 0) return;

  // Project 1: TypeScript Multi-File Task & Analytics Engine
  const p1 = path.join(WORKSPACES_ROOT, '01-ts-task-engine');
  fs.mkdirSync(path.join(p1, 'src'), { recursive: true });
  writeManifest(p1, {
    name: '01 · TypeScript Task Engine',
    description: 'Multi-file TypeScript task scheduler, priority queue & test runner',
    primaryLanguage: 'typescript',
    entryFile: 'src/main.ts',
  });
  fs.writeFileSync(
    path.join(p1, 'src/types.ts'),
    `export type Priority = 'critical' | 'high' | 'medium' | 'low';

export interface TaskItem {
  id: string;
  title: string;
  priority: Priority;
  estimateMinutes: number;
  completed: boolean;
  tags: string[];
}
`,
    'utf8'
  );
  fs.writeFileSync(
    path.join(p1, 'src/scheduler.ts'),
    `import type { Priority, TaskItem } from './types.ts';

const WEIGHT: Record<Priority, number> = {
  critical: 4,
  high: 3,
  medium: 2,
  low: 1,
};

export class TaskScheduler {
  private items: TaskItem[] = [];

  add(item: TaskItem): void {
    this.items.push(item);
  }

  complete(id: string): boolean {
    const found = this.items.find((t) => t.id === id);
    if (!found) return false;
    found.completed = true;
    return true;
  }

  pendingOrdered(): TaskItem[] {
    return this.items
      .filter((t) => !t.completed)
      .sort((a, b) => WEIGHT[b.priority] - WEIGHT[a.priority] || a.estimateMinutes - b.estimateMinutes);
  }

  summary() {
    const total = this.items.length;
    const done = this.items.filter((t) => t.completed).length;
    const remainingMinutes = this.pendingOrdered().reduce((acc, t) => acc + t.estimateMinutes, 0);
    return { total, done, pending: total - done, remainingMinutes };
  }
}
`,
    'utf8'
  );
  fs.writeFileSync(
    path.join(p1, 'src/main.ts'),
    `import { TaskScheduler } from './scheduler.ts';

const scheduler = new TaskScheduler();

scheduler.add({ id: 'TSK-1', title: 'Implement AST symbol parser', priority: 'high', estimateMinutes: 45, completed: false, tags: ['compiler'] });
scheduler.add({ id: 'TSK-2', title: 'Fix memory leak in worker pool', priority: 'critical', estimateMinutes: 25, completed: false, tags: ['runtime', 'perf'] });
scheduler.add({ id: 'TSK-3', title: 'Write integration tests', priority: 'medium', estimateMinutes: 30, completed: true, tags: ['qa'] });
scheduler.add({ id: 'TSK-4', title: 'Add WebSocket heartbeat', priority: 'critical', estimateMinutes: 15, completed: false, tags: ['network'] });

console.log('=== NEXUS TASK SCHEDULER ===');
console.log('Summary:', JSON.stringify(scheduler.summary(), null, 2));
console.log('\\nExecution Queue (by Priority & Shortest-Job-First):');
for (const task of scheduler.pendingOrdered()) {
  console.log(\`  [\${task.priority.toUpperCase()}] \${task.id} — \${task.title} (\${task.estimateMinutes}m)\`);
}
`,
    'utf8'
  );

  // Project 2: Python Graph & Data Algorithms
  const p2 = path.join(WORKSPACES_ROOT, '02-python-algorithms');
  fs.mkdirSync(path.join(p2, 'algorithms'), { recursive: true });
  writeManifest(p2, {
    name: '02 · Python Graph & Data Lab',
    description: 'Dijkstra pathfinding, matrix stats, and unittest verification suite',
    primaryLanguage: 'python',
    entryFile: 'main.py',
  });
  fs.writeFileSync(
    path.join(p2, 'algorithms/__init__.py'),
    '',
    'utf8'
  );
  fs.writeFileSync(
    path.join(p2, 'algorithms/pathfinding.py'),
    `import heapq
from typing import Dict, List, Tuple


def dijkstra(graph: Dict[str, List[Tuple[str, float]]], start: str, goal: str) -> Tuple[float, List[str]]:
    queue: List[Tuple[float, str, List[str]]] = [(0.0, start, [start])]
    visited: Dict[str, float] = {}

    while queue:
        cost, node, path = heapq.heappop(queue)
        if node == goal:
            return cost, path
        if node in visited and visited[node] <= cost:
            continue
        visited[node] = cost
        for neighbor, weight in graph.get(node, []):
            new_cost = cost + weight
            if neighbor not in visited or new_cost < visited[neighbor]:
                heapq.heappush(queue, (new_cost, neighbor, path + [neighbor]))

    return float('inf'), []
`,
    'utf8'
  );
  fs.writeFileSync(
    path.join(p2, 'main.py'),
    `from algorithms.pathfinding import dijkstra

NETWORK = {
    'Gateway': [('Auth', 2), ('CDN', 5)],
    'Auth': [('API-Core', 3), ('Cache', 1)],
    'CDN': [('API-Core', 1)],
    'Cache': [('DB-Primary', 4)],
    'API-Core': [('Worker', 2), ('DB-Primary', 6)],
    'Worker': [('DB-Primary', 1)],
    'DB-Primary': [],
}

if __name__ == '__main__':
    cost, route = dijkstra(NETWORK, 'Gateway', 'DB-Primary')
    print(f"Optimal latency route: {' -> '.join(route)}")
    print(f"Total latency: {cost:.1f} ms")
`,
    'utf8'
  );
  fs.writeFileSync(
    path.join(p2, 'test_suite.py'),
    `import unittest
from algorithms.pathfinding import dijkstra
from main import NETWORK


class TestAlgorithms(unittest.TestCase):
    def test_optimal_route(self):
        cost, route = dijkstra(NETWORK, 'Gateway', 'DB-Primary')
        self.assertEqual(cost, 7.0)
        self.assertEqual(route[0], 'Gateway')
        self.assertEqual(route[-1], 'DB-Primary')


if __name__ == '__main__':
    unittest.main(verbosity=2)
`,
    'utf8'
  );

  // Project 3: C++20 Systems Engine
  const p3 = path.join(WORKSPACES_ROOT, '03-cpp-systems-engine');
  fs.mkdirSync(path.join(p3, 'src'), { recursive: true });
  writeManifest(p3, {
    name: '03 · C++20 Systems Engine',
    description: 'High-performance C++20 ring buffer & benchmark with Makefile',
    primaryLanguage: 'cpp',
    entryFile: 'src/main.cpp',
  });
  fs.writeFileSync(
    path.join(p3, 'src/ring_buffer.hpp'),
    `#pragma once
#include <array>
#include <optional>
#include <cstddef>

template <typename T, std::size_t Capacity>
class RingBuffer {
public:
    bool push(const T& value) {
        if (count_ == Capacity) return false;
        data_[tail_] = value;
        tail_ = (tail_ + 1) % Capacity;
        ++count_;
        return true;
    }

    std::optional<T> pop() {
        if (count_ == 0) return std::nullopt;
        T value = data_[head_];
        head_ = (head_ + 1) % Capacity;
        --count_;
        return value;
    }

    std::size_t size() const { return count_; }
    constexpr std::size_t capacity() const { return Capacity; }

private:
    std::array<T, Capacity> data_{};
    std::size_t head_ = 0;
    std::size_t tail_ = 0;
    std::size_t count_ = 0;
};
`,
    'utf8'
  );
  fs.writeFileSync(
    path.join(p3, 'src/main.cpp'),
    `#include <iostream>
#include <chrono>
#include "ring_buffer.hpp"

int main() {
    RingBuffer<int, 1024> rb;
    auto t0 = std::chrono::steady_clock::now();

    long long checksum = 0;
    for (int iter = 0; iter < 50000; ++iter) {
        rb.push(iter);
        if (auto v = rb.pop()) {
            checksum += *v;
        }
    }

    auto t1 = std::chrono::steady_clock::now();
    auto us = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();

    std::cout << "C++20 RingBuffer Benchmark\\n";
    std::cout << "Operations: 100,000 push/pop\\n";
    std::cout << "Checksum:   " << checksum << "\\n";
    std::cout << "Elapsed:    " << us << " us\\n";
    return 0;
}
`,
    'utf8'
  );
  fs.writeFileSync(
    path.join(p3, 'Makefile'),
    `CXX := g++
CXXFLAGS := -std=c++20 -O2 -Wall -Wextra

all: build run

build:
\t$(CXX) $(CXXFLAGS) src/main.cpp -o engine

run: build
\t./engine

clean:
\trm -f engine
`,
    'utf8'
  );

  // Project 4: Interactive Web Studio (HTML5 Canvas Physics)
  const p4 = path.join(WORKSPACES_ROOT, '04-web-particle-studio');
  fs.mkdirSync(p4, { recursive: true });
  writeManifest(p4, {
    name: '04 · Interactive Web Studio',
    description: 'Real-time HTML5 Canvas orbital particle simulation with Live Web Preview',
    primaryLanguage: 'html',
    entryFile: 'index.html',
  });
  fs.writeFileSync(
    path.join(p4, 'index.html'),
    `<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Nexus Orbital Particle Studio</title>
  <link rel="stylesheet" href="styles.css" />
</head>
<body>
  <div class="hud">
    <div class="badge">LIVE WEB PREVIEW</div>
    <h1>Orbital Gravity Field</h1>
    <p>Move cursor to attract particles · Click to emit shockwave</p>
    <div class="stats" id="stats">Particles: 160</div>
  </div>
  <canvas id="scene"></canvas>
  <script src="simulation.js"></script>
</body>
</html>
`,
    'utf8'
  );
  fs.writeFileSync(
    path.join(p4, 'styles.css'),
    `* { box-sizing: border-box; margin: 0; padding: 0; }
body {
  background: radial-gradient(circle at 50% 40%, #0f172a, #030712);
  color: #f8fafc;
  font-family: Inter, system-ui, sans-serif;
  overflow: hidden;
  height: 100vh;
}
canvas { display: block; width: 100%; height: 100%; }
.hud {
  position: fixed;
  top: 20px;
  left: 20px;
  background: rgba(15, 23, 42, 0.72);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(56, 189, 248, 0.25);
  border-radius: 14px;
  padding: 14px 18px;
  pointer-events: none;
}
.badge {
  display: inline-block;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
  color: #38bdf8;
  margin-bottom: 4px;
}
.hud h1 { font-size: 16px; font-weight: 700; }
.hud p { font-size: 12px; color: #94a3b8; margin-top: 4px; }
.stats { font-size: 11px; color: #34d399; margin-top: 8px; font-family: monospace; }
`,
    'utf8'
  );
  fs.writeFileSync(
    path.join(p4, 'simulation.js'),
    `const canvas = document.getElementById('scene');
const ctx = canvas.getContext('2d');
const stats = document.getElementById('stats');

let w = (canvas.width = window.innerWidth);
let h = (canvas.height = window.innerHeight);
const pointer = { x: w / 2, y: h / 2, active: false };

window.addEventListener('resize', () => {
  w = canvas.width = window.innerWidth;
  h = canvas.height = window.innerHeight;
});

window.addEventListener('mousemove', (e) => {
  pointer.x = e.clientX;
  pointer.y = e.clientY;
  pointer.active = true;
});

window.addEventListener('click', () => {
  for (const p of particles) {
    const dx = p.x - pointer.x;
    const dy = p.y - pointer.y;
    const dist = Math.hypot(dx, dy) + 1;
    if (dist < 220) {
      p.vx += (dx / dist) * 9;
      p.vy += (dy / dist) * 9;
    }
  }
});

const particles = Array.from({ length: 160 }, (_, i) => ({
  x: Math.random() * w,
  y: Math.random() * h,
  vx: (Math.random() - 0.5) * 1.5,
  vy: (Math.random() - 0.5) * 1.5,
  r: 1.5 + (i % 3),
  hue: 185 + (i % 70),
}));

let frame = 0;
function loop() {
  ctx.fillStyle = 'rgba(3, 7, 18, 0.22)';
  ctx.fillRect(0, 0, w, h);

  for (let i = 0; i < particles.length; i++) {
    const p = particles[i];
    if (pointer.active) {
      const dx = pointer.x - p.x;
      const dy = pointer.y - p.y;
      const dist = Math.hypot(dx, dy) + 12;
      if (dist < 280) {
        p.vx += (dx / dist) * 0.28;
        p.vy += (dy / dist) * 0.28;
      }
    }
    p.vx *= 0.985;
    p.vy *= 0.985;
    p.x = (p.x + p.vx + w) % w;
    p.y = (p.y + p.vy + h) % h;

    ctx.beginPath();
    ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
    ctx.fillStyle = \`hsl(\${p.hue}, 90%, 65%)\`;
    ctx.fill();
  }

  frame++;
  if (frame % 15 === 0) {
    stats.textContent = \`Particles: \${particles.length} · Frame: \${frame}\`;
  }
  requestAnimationFrame(loop);
}

loop();
`,
    'utf8'
  );
}
