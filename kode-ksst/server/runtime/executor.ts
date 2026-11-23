import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import * as esbuild from 'esbuild';
import { getProjectDir, safeResolveInProject, detectLanguage } from './workspace.ts';

const execFileAsync = promisify(execFile);

export interface RuntimeCapability {
  id: string;
  label: string;
  available: boolean;
  version?: string;
  extensions: string[];
}

export interface ExecutionResult {
  success: boolean;
  exitCode: number;
  language: string;
  command: string;
  stdout: string;
  stderr: string;
  durationMs: number;
  previewUrl?: string;
  timedOut?: boolean;
}

let cachedRuntimes: RuntimeCapability[] | null = null;

async function checkBin(cmd: string, args: string[]): Promise<{ ok: boolean; version?: string }> {
  try {
    const { stdout, stderr } = await execFileAsync(cmd, args, { timeout: 3000 });
    const text = (stdout || stderr || '').trim().split('\n')[0];
    return { ok: true, version: text };
  } catch {
    return { ok: false };
  }
}

export async function getAvailableRuntimes(forceRefresh = false): Promise<RuntimeCapability[]> {
  if (cachedRuntimes && !forceRefresh) return cachedRuntimes;
  const [nodeV, pyV, gccV, gppV, bashV, makeV, gitV, perlV] = await Promise.all([
    checkBin('node', ['-v']),
    checkBin('python3', ['--version']),
    checkBin('gcc', ['--version']),
    checkBin('g++', ['--version']),
    checkBin('bash', ['--version']),
    checkBin('make', ['--version']),
    checkBin('git', ['--version']),
    checkBin('perl', ['-v']),
  ]);

  cachedRuntimes = [
    { id: 'javascript', label: 'Node.js (JavaScript)', available: nodeV.ok, version: nodeV.version, extensions: ['.js', '.mjs', '.cjs'] },
    { id: 'typescript', label: 'TypeScript (esbuild + Node)', available: nodeV.ok, version: `esbuild ${esbuild.version} + ${nodeV.version || 'node'}`, extensions: ['.ts', '.tsx'] },
    { id: 'python', label: 'Python 3', available: pyV.ok, version: pyV.version, extensions: ['.py'] },
    { id: 'c', label: 'C17 (GCC)', available: gccV.ok, version: gccV.version, extensions: ['.c'] },
    { id: 'cpp', label: 'C++20 (G++)', available: gppV.ok, version: gppV.version, extensions: ['.cpp', '.cc', '.cxx'] },
    { id: 'sql', label: 'SQLite SQL', available: pyV.ok, version: 'SQLite3 via Python', extensions: ['.sql'] },
    { id: 'shell', label: 'Bash Shell', available: bashV.ok, version: bashV.version?.split(',')[0], extensions: ['.sh', '.bash'] },
    { id: 'makefile', label: 'GNU Make', available: makeV.ok, version: makeV.version, extensions: ['Makefile'] },
    { id: 'perl', label: 'Perl', available: perlV.ok, version: perlV.version ? 'perl' : undefined, extensions: ['.pl'] },
    { id: 'html', label: 'Web Preview (HTML/CSS/JS)', available: true, version: 'Built-in HTTP Preview', extensions: ['.html', '.htm'] },
    { id: 'git', label: 'Git CLI', available: gitV.ok, version: gitV.version, extensions: [] },
  ];
  return cachedRuntimes;
}

function spawnWithTimeout(
  cmd: string,
  args: string[],
  opts: { cwd: string; env?: NodeJS.ProcessEnv; stdin?: string; timeoutMs?: number }
): Promise<{ stdout: string; stderr: string; exitCode: number; timedOut: boolean; durationMs: number }> {
  const start = Date.now();
  const timeoutMs = Math.min(Math.max(opts.timeoutMs ?? 20000, 1000), 120000);

  return new Promise((resolve) => {
    const child = spawn(cmd, args, {
      cwd: opts.cwd,
      env: { ...process.env, ...opts.env },
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    let stdout = '';
    let stderr = '';
    let timedOut = false;
    const MAX_OUT = 256 * 1024;

    child.stdout.on('data', (chunk: Buffer) => {
      if (stdout.length < MAX_OUT) stdout += chunk.toString('utf8');
    });
    child.stderr.on('data', (chunk: Buffer) => {
      if (stderr.length < MAX_OUT) stderr += chunk.toString('utf8');
    });

    if (opts.stdin !== undefined) {
      child.stdin.write(opts.stdin);
    }
    child.stdin.end();

    const timer = setTimeout(() => {
      timedOut = true;
      child.kill('SIGKILL');
    }, timeoutMs);

    child.on('close', (code) => {
      clearTimeout(timer);
      resolve({
        stdout,
        stderr,
        exitCode: code ?? (timedOut ? 124 : 1),
        timedOut,
        durationMs: Date.now() - start,
      });
    });

    child.on('error', (err) => {
      clearTimeout(timer);
      resolve({
        stdout,
        stderr: stderr + '\n' + err.message,
        exitCode: 1,
        timedOut: false,
        durationMs: Date.now() - start,
      });
    });
  });
}

export async function runProjectFile(opts: {
  projectId: string;
  filePath: string;
  stdin?: string;
  args?: string[];
  timeoutMs?: number;
  languageOverride?: string;
}): Promise<ExecutionResult> {
  const projectDir = getProjectDir(opts.projectId);
  const fullPath = safeResolveInProject(opts.projectId, opts.filePath);
  if (!fs.existsSync(fullPath)) {
    throw new Error(`File not found: ${opts.filePath}`);
  }

  const language = opts.languageOverride || detectLanguage(opts.filePath);
  const args = opts.args || [];
  const start = Date.now();

  if (language === 'html') {
    const cleanRel = opts.filePath.replace(/\\/g, '/').replace(/^\/+/, '');
    return {
      success: true,
      exitCode: 0,
      language: 'html',
      command: `Preview ${cleanRel}`,
      stdout: `Web Preview ready at /preview/${opts.projectId}/${cleanRel}`,
      stderr: '',
      durationMs: Date.now() - start,
      previewUrl: `/preview/${opts.projectId}/${cleanRel}`,
    };
  }

  if (language === 'javascript') {
    const r = await spawnWithTimeout('node', [fullPath, ...args], {
      cwd: projectDir,
      stdin: opts.stdin,
      timeoutMs: opts.timeoutMs,
    });
    return { ...r, success: r.exitCode === 0, language, command: `node ${opts.filePath}` };
  }

  if (language === 'typescript') {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nexus-ts-'));
    const outFile = path.join(tmpDir, 'bundle.mjs');
    try {
      await esbuild.build({
        entryPoints: [fullPath],
        bundle: true,
        platform: 'node',
        format: 'esm',
        target: 'node22',
        outfile: outFile,
        packages: 'external',
        sourcemap: 'inline',
      });
      const r = await spawnWithTimeout('node', [outFile, ...args], {
        cwd: projectDir,
        stdin: opts.stdin,
        timeoutMs: opts.timeoutMs,
      });
      return {
        ...r,
        success: r.exitCode === 0,
        language,
        command: `esbuild + node ${opts.filePath}`,
        durationMs: Date.now() - start,
      };
    } catch (err: any) {
      return {
        success: false,
        exitCode: 1,
        language,
        command: `esbuild ${opts.filePath}`,
        stdout: '',
        stderr: err.message || String(err),
        durationMs: Date.now() - start,
      };
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  }

  if (language === 'python') {
    const r = await spawnWithTimeout('python3', ['-u', fullPath, ...args], {
      cwd: projectDir,
      env: { PYTHONPATH: projectDir },
      stdin: opts.stdin,
      timeoutMs: opts.timeoutMs,
    });
    return { ...r, success: r.exitCode === 0, language, command: `python3 ${opts.filePath}` };
  }

  if (language === 'c' || language === 'cpp') {
    const compiler = language === 'c' ? 'gcc' : 'g++';
    const stdFlag = language === 'c' ? '-std=c17' : '-std=c++20';
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nexus-cpp-'));
    const binPath = path.join(tmpDir, 'program');
    try {
      const compile = await spawnWithTimeout(
        compiler,
        [stdFlag, '-O2', '-Wall', '-Wextra', fullPath, '-o', binPath, '-lm'],
        { cwd: projectDir, timeoutMs: 25000 }
      );
      if (compile.exitCode !== 0) {
        return {
          success: false,
          exitCode: compile.exitCode,
          language,
          command: `${compiler} ${stdFlag} ${opts.filePath}`,
          stdout: compile.stdout,
          stderr: compile.stderr,
          durationMs: Date.now() - start,
        };
      }
      const run = await spawnWithTimeout(binPath, args, {
        cwd: projectDir,
        stdin: opts.stdin,
        timeoutMs: opts.timeoutMs,
      });
      return {
        ...run,
        success: run.exitCode === 0,
        language,
        command: `${compiler} ${stdFlag} ${opts.filePath} && ./program`,
        stderr: [compile.stderr, run.stderr].filter(Boolean).join('\n'),
        durationMs: Date.now() - start,
      };
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  }

  if (language === 'makefile') {
    const r = await spawnWithTimeout('make', args, {
      cwd: path.dirname(fullPath),
      stdin: opts.stdin,
      timeoutMs: opts.timeoutMs,
    });
    return { ...r, success: r.exitCode === 0, language, command: `make ${args.join(' ')}`.trim() };
  }

  if (language === 'shell') {
    const r = await spawnWithTimeout('bash', [fullPath, ...args], {
      cwd: projectDir,
      stdin: opts.stdin,
      timeoutMs: opts.timeoutMs,
    });
    return { ...r, success: r.exitCode === 0, language, command: `bash ${opts.filePath}` };
  }

  if (language === 'perl') {
    const r = await spawnWithTimeout('perl', [fullPath, ...args], {
      cwd: projectDir,
      stdin: opts.stdin,
      timeoutMs: opts.timeoutMs,
    });
    return { ...r, success: r.exitCode === 0, language, command: `perl ${opts.filePath}` };
  }

  if (language === 'sql') {
    const pyRunner = `
import sqlite3, sys
db_path, sql_path = sys.argv[1], sys.argv[2]
with open(sql_path, 'r', encoding='utf-8') as f:
    script = f.read()
conn = sqlite3.connect(db_path)
cur = conn.cursor()
statements = [s.strip() for s in script.split(';') if s.strip()]
for idx, stmt in enumerate(statements, 1):
    try:
        cur.execute(stmt)
        if cur.description:
            cols = [d[0] for d in cur.description]
            rows = cur.fetchall()
            widths = [max([len(str(c))] + [len(str(r[i])) for r in rows]) for i, c in enumerate(cols)]
            header = " | ".join(str(c).ljust(widths[i]) for i, c in enumerate(cols))
            sep = "-+-".join("-" * w for w in widths)
            print(header)
            print(sep)
            for r in rows:
                print(" | ".join(str(r[i]).ljust(widths[i]) for i in range(len(cols))))
            print(f"({len(rows)} rows)\\n")
        else:
            print(f"OK (statement {idx}, rows affected: {cur.rowcount})")
    except Exception as e:
        print(f"SQL Error in statement {idx}: {e}", file=sys.stderr)
        sys.exit(1)
conn.commit()
conn.close()
`;
    const dbPath = path.join(projectDir, 'database.sqlite');
    const r = await spawnWithTimeout('python3', ['-c', pyRunner, dbPath, fullPath], {
      cwd: projectDir,
      timeoutMs: opts.timeoutMs,
    });
    return { ...r, success: r.exitCode === 0, language, command: `sqlite3 database.sqlite < ${opts.filePath}` };
  }

  if (language === 'json') {
    try {
      JSON.parse(fs.readFileSync(fullPath, 'utf8'));
      return {
        success: true,
        exitCode: 0,
        language,
        command: `JSON.parse(${opts.filePath})`,
        stdout: `Valid JSON (${fs.statSync(fullPath).size} bytes)`,
        stderr: '',
        durationMs: Date.now() - start,
      };
    } catch (err: any) {
      return {
        success: false,
        exitCode: 1,
        language,
        command: `JSON.parse(${opts.filePath})`,
        stdout: '',
        stderr: err.message,
        durationMs: Date.now() - start,
      };
    }
  }

  throw new Error(`Execution not supported for language "${language}" (${opts.filePath}). Use the Terminal or Run Command tool for custom binaries.`);
}

export async function runSnippet(opts: {
  projectId: string;
  language: string;
  code: string;
  stdin?: string;
  timeoutMs?: number;
}): Promise<ExecutionResult> {
  const projectDir = getProjectDir(opts.projectId);
  const extMap: Record<string, string> = {
    javascript: '.mjs',
    typescript: '.ts',
    python: '.py',
    c: '.c',
    cpp: '.cpp',
    shell: '.sh',
    sql: '.sql',
    perl: '.pl',
  };
  const ext = extMap[opts.language] || '.txt';
  const tmpName = `.nexus-scratch-${Date.now()}${ext}`;
  const tmpFull = path.join(projectDir, tmpName);
  fs.writeFileSync(tmpFull, opts.code, 'utf8');
  try {
    return await runProjectFile({
      projectId: opts.projectId,
      filePath: tmpName,
      stdin: opts.stdin,
      timeoutMs: opts.timeoutMs,
      languageOverride: opts.language,
    });
  } finally {
    if (fs.existsSync(tmpFull)) fs.unlinkSync(tmpFull);
  }
}

export async function runShellCommand(opts: {
  projectId: string;
  command: string;
  timeoutMs?: number;
  stdin?: string;
}): Promise<ExecutionResult> {
  const projectDir = getProjectDir(opts.projectId);
  const r = await spawnWithTimeout('bash', ['-lc', opts.command], {
    cwd: projectDir,
    stdin: opts.stdin,
    timeoutMs: opts.timeoutMs ?? 30000,
  });
  return {
    ...r,
    success: r.exitCode === 0,
    language: 'shell',
    command: opts.command,
  };
}
