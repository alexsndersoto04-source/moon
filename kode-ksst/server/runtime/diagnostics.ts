import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import fs from 'node:fs';
import * as esbuild from 'esbuild';
import { detectLanguage, flattenFiles, getFileTree, getProjectDir, safeResolveInProject } from './workspace.ts';

const execFileAsync = promisify(execFile);

export interface DiagnosticItem {
  file: string;
  line: number;
  column: number;
  severity: 'error' | 'warning' | 'info';
  message: string;
  source: string;
}

export async function checkFileDiagnostics(projectId: string, relPath: string): Promise<DiagnosticItem[]> {
  const projectDir = getProjectDir(projectId);
  const fullPath = safeResolveInProject(projectId, relPath);
  if (!fs.existsSync(fullPath)) return [];
  const lang = detectLanguage(relPath);
  const diagnostics: DiagnosticItem[] = [];

  if (lang === 'json') {
    try {
      JSON.parse(fs.readFileSync(fullPath, 'utf8'));
    } catch (err: any) {
      diagnostics.push({
        file: relPath,
        line: 1,
        column: 1,
        severity: 'error',
        message: err.message || 'Invalid JSON',
        source: 'json',
      });
    }
    return diagnostics;
  }

  if (lang === 'javascript') {
    try {
      await execFileAsync('node', ['--check', fullPath], { cwd: projectDir, timeout: 5000 });
    } catch (err: any) {
      const stderr = String(err.stderr || err.message || '');
      const lineMatch = stderr.match(/:(\d+)/);
      diagnostics.push({
        file: relPath,
        line: lineMatch ? Number(lineMatch[1]) : 1,
        column: 1,
        severity: 'error',
        message: stderr.split('\n').slice(0, 4).join(' ').trim(),
        source: 'node',
      });
    }
    return diagnostics;
  }

  if (lang === 'typescript') {
    const code = fs.readFileSync(fullPath, 'utf8');
    try {
      await esbuild.transform(code, { loader: relPath.endsWith('.tsx') ? 'tsx' : 'ts' });
    } catch (err: any) {
      if (Array.isArray(err.errors)) {
        for (const e of err.errors) {
          diagnostics.push({
            file: relPath,
            line: e.location?.line || 1,
            column: (e.location?.column || 0) + 1,
            severity: 'error',
            message: e.text,
            source: 'esbuild',
          });
        }
      }
    }
    return diagnostics;
  }

  if (lang === 'python') {
    try {
      await execFileAsync('python3', ['-m', 'py_compile', fullPath], { cwd: projectDir, timeout: 5000 });
    } catch (err: any) {
      const stderr = String(err.stderr || err.message || '');
      const lineMatch = stderr.match(/line (\d+)/);
      diagnostics.push({
        file: relPath,
        line: lineMatch ? Number(lineMatch[1]) : 1,
        column: 1,
        severity: 'error',
        message: stderr.trim(),
        source: 'python3',
      });
    }
    return diagnostics;
  }

  if (lang === 'c' || lang === 'cpp') {
    const compiler = lang === 'c' ? 'gcc' : 'g++';
    const stdFlag = lang === 'c' ? '-std=c17' : '-std=c++20';
    try {
      await execFileAsync(compiler, [stdFlag, '-fsyntax-only', '-Wall', fullPath], {
        cwd: projectDir,
        timeout: 8000,
      });
    } catch (err: any) {
      const stderr = String(err.stderr || '');
      const lines = stderr.split('\n');
      for (const l of lines) {
        const m = l.match(/^.*?:(\d+):(\d+):\s*(error|warning|note):\s*(.+)$/);
        if (m) {
          diagnostics.push({
            file: relPath,
            line: Number(m[1]),
            column: Number(m[2]),
            severity: m[3] === 'warning' ? 'warning' : 'error',
            message: m[4],
            source: compiler,
          });
        }
      }
      if (diagnostics.length === 0 && stderr.trim()) {
        diagnostics.push({
          file: relPath,
          line: 1,
          column: 1,
          severity: 'error',
          message: stderr.trim().slice(0, 300),
          source: compiler,
        });
      }
    }
    return diagnostics;
  }

  if (lang === 'shell') {
    try {
      await execFileAsync('bash', ['-n', fullPath], { cwd: projectDir, timeout: 5000 });
    } catch (err: any) {
      const stderr = String(err.stderr || '');
      const m = stderr.match(/line (\d+):/);
      diagnostics.push({
        file: relPath,
        line: m ? Number(m[1]) : 1,
        column: 1,
        severity: 'error',
        message: stderr.trim(),
        source: 'bash',
      });
    }
    return diagnostics;
  }

  return diagnostics;
}

export async function checkProjectDiagnostics(projectId: string, targetFile?: string): Promise<DiagnosticItem[]> {
  if (targetFile) {
    return checkFileDiagnostics(projectId, targetFile);
  }
  const files = flattenFiles(getFileTree(projectId)).slice(0, 40);
  const results = await Promise.all(files.map((f) => checkFileDiagnostics(projectId, f)));
  return results.flat();
}
