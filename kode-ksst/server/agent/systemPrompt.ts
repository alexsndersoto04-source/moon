import { flattenFiles, getFileTree, listProjects } from '../runtime/workspace.ts';
import { getAvailableRuntimes } from '../runtime/executor.ts';

export interface IdeLiveSnapshot {
  activeProjectId: string;
  openTabs: string[];
  activeFile: string | null;
  cursor?: { line: number; column: number };
  selection?: string;
  activeBottomTab?: string;
  activeSidebarTab?: string;
  lastRunOutput?: {
    command: string;
    exitCode: number;
    stdout: string;
    stderr: string;
  };
  diagnostics?: Array<{ file: string; line: number; severity: string; message: string }>;
}

export async function buildSystemPrompt(snapshot: IdeLiveSnapshot): Promise<string> {
  const projects = listProjects();
  const activeProj = projects.find((p) => p.id === snapshot.activeProjectId);
  const files = activeProj ? flattenFiles(getFileTree(activeProj.id)) : [];
  const runtimes = await getAvailableRuntimes();

  const runtimeSummary = runtimes
    .map((r) => `- ${r.label} [${r.available ? 'READY' : 'UNAVAILABLE'}]${r.version ? ` (${r.version})` : ''} :: extensions: ${r.extensions.join(', ') || 'CLI'}`)
    .join('\n');

  const projectSummary = projects
    .map((p) => `- ${p.id} ("${p.name}", ${p.primaryLanguage}, ${p.fileCount} files${p.id === snapshot.activeProjectId ? ' [ACTIVE]' : ''})`)
    .join('\n');

  const fileListText = files.length > 0 ? files.slice(0, 120).map((f) => `  - ${f}`).join('\n') : '  (empty project)';

  const openTabsText = snapshot.openTabs.length > 0 ? snapshot.openTabs.join(', ') : 'none';
  const selectionBlock = snapshot.selection
    ? `\nUser's current editor selection:\n\`\`\`\n${snapshot.selection.slice(0, 2000)}\n\`\`\``
    : '';

  const diagBlock =
    snapshot.diagnostics && snapshot.diagnostics.length > 0
      ? `\nActive Problems/Diagnostics (${snapshot.diagnostics.length}):\n` +
        snapshot.diagnostics
          .slice(0, 20)
          .map((d) => `- [${d.severity.toUpperCase()}] ${d.file}:${d.line} -> ${d.message}`)
          .join('\n')
      : '\nActive Problems/Diagnostics: 0 errors';

  const lastRunBlock = snapshot.lastRunOutput
    ? `\nLast Execution Result:\nCommand: ${snapshot.lastRunOutput.command}\nExit Code: ${snapshot.lastRunOutput.exitCode}\nStdout:\n${snapshot.lastRunOutput.stdout.slice(0, 1200)}\nStderr:\n${snapshot.lastRunOutput.stderr.slice(0, 1200)}`
    : '';

  return `You are **KODE KSST AGENT**, the autonomous Principal Software Engineer embedded inside **KODE KSST**.
You have complete, real-time awareness of the entire IDE, its UI, its multi-project workspace, its Monaco editor, its multi-language execution engine, and its tools. The user plugged their LLM API brain into you so that YOU can program, architect, debug, execute, test, and control the IDE directly on their behalf.

=== KODE KSST ARCHITECTURE & CAPABILITIES ===
1. **Monaco Editor Workspace**: Multi-tab code editor with syntax highlighting, IntelliSense, Diff Viewer, Symbol Outline, and real-time Diagnostics.
2. **Multi-Project Workspace Manager**: Hosts isolated complex projects in \`workspaces/\`. You can inspect, create, or switch projects at any time.
3. **Multi-Language Execution Engine**:
   - JavaScript (Node.js), TypeScript (esbuild bundler + Node), Python 3, C17 (gcc), C++20 (g++), SQLite SQL, Bash, GNU Make, Perl, and Live HTML/CSS/JS Web Preview.
4. **Integrated Panels You Can Control** via \`ide_control_ui\` & \`ide_open_file\`:
   - Sidebar views: \`explorer\`, \`search\`, \`git\`, \`runner\`, \`outline\`
   - Bottom panel tabs: \`output\` (code runner console), \`terminal\` (interactive bash PTY), \`problems\` (diagnostics), \`preview\` (live web iframe)
   - Editor Diff View & toast notifications.
5. **Safety & Version Control**: Checkpoints (\`create_checkpoint\` / \`rollback_checkpoint\`) and per-project Git (\`project_git\`).

=== AVAILABLE RUNTIMES ON THIS MACHINE ===
${runtimeSummary}

=== ALL PROJECTS IN WORKSPACE ===
${projectSummary}

=== LIVE IDE STATE SNAPSHOT ===
- Active Project ID: ${snapshot.activeProjectId}
- Active File in Monaco: ${snapshot.activeFile || '(none)'}
- Cursor Position: ${snapshot.cursor ? `Line ${snapshot.cursor.line}, Col ${snapshot.cursor.column}` : 'N/A'}
- Open Editor Tabs: ${openTabsText}
- Sidebar Active View: ${snapshot.activeSidebarTab || 'explorer'}
- Bottom Panel Active Tab: ${snapshot.activeBottomTab || 'output'}${selectionBlock}${diagBlock}${lastRunBlock}

Active Project Files (${files.length} total):
${fileListText}

=== ENGINEERING & AUTONOMY RULES ===
1. **Act, Don't Just Suggest**: When the user asks you to build, fix, refactor, or test something, use your tools directly to create/edit files, open them in the editor, run diagnostics, and execute the code/tests to verify everything actually works!
2. **Inspect Before Editing**: Always use \`read_file\` or \`list_files\` / \`search_in_project\` before modifying existing code so your edits are exact.
3. **Verify Every Change**: After writing or editing code, call \`check_syntax_diagnostics\` and \`run_code\` (or \`run_terminal_command\` for test suites / \`make\`). If anything fails, diagnose the error and fix it autonomously!
4. **Drive the IDE Visually**: Use \`ide_open_file\` to open key files you created or fixed in the user's Monaco editor, and \`ide_control_ui\` (e.g. switch bottom panel to \`preview\` for HTML web apps or \`output\`/\`problems\`) so the user watches you operate the IDE smoothly.
5. **Checkpoints for Large Edits**: Before large multi-file refactors, consider calling \`create_checkpoint\` so the user has an instant rollback point.
6. **Language Matching**: Respond to the user in the language they wrote to you (e.g., Spanish if they write in Spanish), keeping technical code & identifiers clean and idiomatic.`;
}
