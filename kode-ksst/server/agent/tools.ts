import { ToolDefinition } from './brainAdapter.ts';
import { IdeLiveSnapshot } from './systemPrompt.ts';
import {
  createCheckpoint,
  createProject,
  deleteProjectPath,
  editProjectFile,
  extractSymbols,
  flattenFiles,
  getFileTree,
  listCheckpoints,
  listProjects,
  readProjectFile,
  rollbackCheckpoint,
  runProjectGit,
  searchInProject,
  writeProjectFile,
} from '../runtime/workspace.ts';
import { getAvailableRuntimes, runProjectFile, runShellCommand, runSnippet } from '../runtime/executor.ts';
import { checkProjectDiagnostics } from '../runtime/diagnostics.ts';

export interface UiSideEffect {
  type:
    | 'file_changed'
    | 'file_deleted'
    | 'open_file'
    | 'project_switched'
    | 'projects_updated'
    | 'ui_control'
    | 'execution_result'
    | 'diagnostics_result'
    | 'checkpoint_created';
  payload: Record<string, any>;
}

export const MUTATING_OR_EXEC_TOOLS = new Set([
  'write_file',
  'edit_file',
  'delete_path',
  'create_project',
  'run_terminal_command',
  'project_git',
  'rollback_checkpoint',
]);

export const IDE_TOOLS: ToolDefinition[] = [
  {
    name: 'get_ide_state',
    description: 'Get the current live IDE state, active project, open editor tabs, active file, cursor position, selection, and available system runtimes.',
    parameters: {
      type: 'object',
      properties: {
        includeRuntimes: { type: 'boolean', description: 'Include detected language runtimes and versions.' },
      },
    },
  },
  {
    name: 'list_projects',
    description: 'List all workspace projects available inside KODE KSST.',
    parameters: {
      type: 'object',
      properties: {},
    },
  },
  {
    name: 'create_project',
    description: 'Create a new isolated multi-file project in the IDE from a template and optionally switch to it.',
    parameters: {
      type: 'object',
      properties: {
        id: { type: 'string', description: 'URL-safe project folder ID (e.g. "my-api-server").' },
        name: { type: 'string', description: 'Human-readable project title.' },
        description: { type: 'string', description: 'Short project description.' },
        template: {
          type: 'string',
          enum: ['node-ts', 'python-app', 'cpp-project', 'c-project', 'web-app', 'sql-lab', 'empty'],
          description: 'Starter template.',
        },
        switchToProject: { type: 'boolean', description: 'Whether to switch the active IDE workspace to this new project.' },
      },
      required: ['id'],
    },
  },
  {
    name: 'switch_project',
    description: 'Switch the active IDE workspace to another existing project ID.',
    parameters: {
      type: 'object',
      properties: {
        projectId: { type: 'string', description: 'Target project ID.' },
      },
      required: ['projectId'],
    },
  },
  {
    name: 'list_files',
    description: 'List all files and folders in a project.',
    parameters: {
      type: 'object',
      properties: {
        projectId: { type: 'string', description: 'Optional project ID (defaults to active project).' },
        subPath: { type: 'string', description: 'Optional subfolder path.' },
      },
    },
  },
  {
    name: 'read_file',
    description: 'Read a file from the project with optional line range and symbol outline.',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Relative file path inside the project.' },
        projectId: { type: 'string', description: 'Optional project ID (defaults to active project).' },
        startLine: { type: 'integer', description: '1-based start line (optional).' },
        endLine: { type: 'integer', description: '1-based end line (optional).' },
      },
      required: ['path'],
    },
  },
  {
    name: 'write_file',
    description: 'Create or overwrite a file in the project and immediately sync it into the Monaco Editor.',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Relative file path inside the project.' },
        content: { type: 'string', description: 'Full file content.' },
        projectId: { type: 'string', description: 'Optional project ID (defaults to active project).' },
        openInEditor: { type: 'boolean', description: 'Open/focus this file in Monaco Editor (default true).' },
      },
      required: ['path', 'content'],
    },
  },
  {
    name: 'edit_file',
    description: 'Perform an exact search-and-replace edit on an existing file and refresh it in Monaco Editor with diff tracking.',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Relative file path inside the project.' },
        oldText: { type: 'string', description: 'Exact existing text to replace.' },
        newText: { type: 'string', description: 'Replacement text.' },
        replaceAll: { type: 'boolean', description: 'Replace all occurrences instead of requiring a single match.' },
        projectId: { type: 'string', description: 'Optional project ID (defaults to active project).' },
      },
      required: ['path', 'oldText', 'newText'],
    },
  },
  {
    name: 'delete_path',
    description: 'Delete a file or directory from the project.',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Relative path inside the project.' },
        projectId: { type: 'string', description: 'Optional project ID.' },
      },
      required: ['path'],
    },
  },
  {
    name: 'search_in_project',
    description: 'Search text or regex across all files in the project (like grep).',
    parameters: {
      type: 'object',
      properties: {
        query: { type: 'string', description: 'Search string or regex pattern.' },
        isRegex: { type: 'boolean', description: 'Treat query as regular expression.' },
        projectId: { type: 'string', description: 'Optional project ID.' },
      },
      required: ['query'],
    },
  },
  {
    name: 'run_code',
    description: 'Compile and execute a project file or an ad-hoc code snippet in JS, TypeScript, Python, C, C++, SQLite SQL, Bash, Perl, Make, or HTML preview.',
    parameters: {
      type: 'object',
      properties: {
        filePath: { type: 'string', description: 'Relative path of a file in the project to execute.' },
        snippet: { type: 'string', description: 'Inline code snippet to run (used if filePath is omitted).' },
        language: {
          type: 'string',
          enum: ['javascript', 'typescript', 'python', 'c', 'cpp', 'sql', 'shell', 'perl'],
          description: 'Language when running a snippet or overriding file language.',
        },
        stdin: { type: 'string', description: 'Optional standard input string.' },
        args: { type: 'array', items: { type: 'string' }, description: 'Optional CLI arguments.' },
        projectId: { type: 'string', description: 'Optional project ID.' },
      },
    },
  },
  {
    name: 'run_terminal_command',
    description: 'Execute a shell command inside the active project directory (e.g. npm install, pytest, make, node tests, ls).',
    parameters: {
      type: 'object',
      properties: {
        command: { type: 'string', description: 'Bash command to execute in the project root.' },
        projectId: { type: 'string', description: 'Optional project ID.' },
        timeoutMs: { type: 'integer', description: 'Timeout in milliseconds (default 30000).' },
      },
      required: ['command'],
    },
  },
  {
    name: 'check_syntax_diagnostics',
    description: 'Run real compiler/interpreter syntax diagnostics on a specific file or the whole project and update the IDE Problems panel.',
    parameters: {
      type: 'object',
      properties: {
        filePath: { type: 'string', description: 'Optional file path; omit to check all files in the project.' },
        projectId: { type: 'string', description: 'Optional project ID.' },
      },
    },
  },
  {
    name: 'ide_open_file',
    description: 'Open a file in the Monaco Editor and optionally jump to a specific line and column.',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Relative file path to open.' },
        line: { type: 'integer', description: '1-based line number to scroll/focus.' },
        column: { type: 'integer', description: '1-based column number.' },
      },
      required: ['path'],
    },
  },
  {
    name: 'ide_control_ui',
    description: 'Control KODE KSST panels and visual state (switch sidebar view, switch bottom panel tab, toggle diff mode, refresh web preview, or show notification).',
    parameters: {
      type: 'object',
      properties: {
        sidebarTab: { type: 'string', enum: ['explorer', 'search', 'git', 'runner', 'outline'] },
        bottomTab: { type: 'string', enum: ['output', 'terminal', 'problems', 'preview'] },
        showBottomPanel: { type: 'boolean' },
        previewUrl: { type: 'string', description: 'Optional preview URL path (e.g. /preview/04-web-particle-studio/index.html).' },
        toast: { type: 'string', description: 'Optional notification message to show in the IDE.' },
      },
    },
  },
  {
    name: 'checkpoint_or_git',
    description: 'Manage project safety checkpoints (create/list/rollback) or run Git operations (init/status/diff/log/commit) in the project.',
    parameters: {
      type: 'object',
      properties: {
        mode: { type: 'string', enum: ['checkpoint_create', 'checkpoint_list', 'checkpoint_rollback', 'git'] },
        labelOrMessage: { type: 'string', description: 'Checkpoint label or Git commit message.' },
        checkpointId: { type: 'string', description: 'Checkpoint ID for rollback.' },
        gitAction: { type: 'string', enum: ['init', 'status', 'diff', 'log', 'add_all', 'commit'] },
        projectId: { type: 'string', description: 'Optional project ID.' },
      },
      required: ['mode'],
    },
  },
];

export async function executeIdeTool(
  name: string,
  args: Record<string, any>,
  ctx: { snapshot: IdeLiveSnapshot; emitUi: (ev: UiSideEffect) => void }
): Promise<string> {
  const activeProject = args.projectId || ctx.snapshot.activeProjectId;

  switch (name) {
    case 'get_ide_state': {
      const runtimes = args.includeRuntimes !== false ? await getAvailableRuntimes() : undefined;
      return JSON.stringify(
        {
          activeProjectId: ctx.snapshot.activeProjectId,
          activeFile: ctx.snapshot.activeFile,
          openTabs: ctx.snapshot.openTabs,
          cursor: ctx.snapshot.cursor,
          selection: ctx.snapshot.selection,
          activeSidebarTab: ctx.snapshot.activeSidebarTab,
          activeBottomTab: ctx.snapshot.activeBottomTab,
          diagnosticsCount: ctx.snapshot.diagnostics?.length || 0,
          runtimes,
        },
        null,
        2
      );
    }

    case 'list_projects': {
      return JSON.stringify({ activeProjectId: ctx.snapshot.activeProjectId, projects: listProjects() }, null, 2);
    }

    case 'create_project': {
      const proj = createProject({
        id: args.id,
        name: args.name,
        description: args.description,
        template: args.template,
      });
      ctx.emitUi({ type: 'projects_updated', payload: { projects: listProjects() } });
      if (args.switchToProject !== false) {
        ctx.snapshot.activeProjectId = proj.id;
        ctx.emitUi({ type: 'project_switched', payload: { projectId: proj.id } });
      }
      return JSON.stringify({ created: proj, switched: args.switchToProject !== false }, null, 2);
    }

    case 'switch_project': {
      const exists = listProjects().find((p) => p.id === args.projectId);
      if (!exists) throw new Error(`Project "${args.projectId}" does not exist.`);
      ctx.snapshot.activeProjectId = exists.id;
      ctx.emitUi({ type: 'project_switched', payload: { projectId: exists.id } });
      return JSON.stringify({ switchedTo: exists, files: flattenFiles(getFileTree(exists.id)) }, null, 2);
    }

    case 'list_files': {
      const tree = getFileTree(activeProject, args.subPath || '');
      return JSON.stringify({ projectId: activeProject, files: flattenFiles(tree), tree }, null, 2);
    }

    case 'read_file': {
      const data = readProjectFile(activeProject, args.path);
      const allLines = data.content.split(/\r?\n/);
      const start = Math.max(1, Number(args.startLine || 1));
      const end = Math.min(allLines.length, Number(args.endLine || allLines.length));
      const numbered = allLines
        .slice(start - 1, end)
        .map((line, idx) => `${start + idx}: ${line}`)
        .join('\n');
      const symbols = extractSymbols(data.content, data.language);
      return JSON.stringify(
        {
          path: args.path,
          language: data.language,
          totalLines: allLines.length,
          range: `${start}-${end}`,
          symbols,
          contentWithLineNumbers: numbered,
        },
        null,
        2
      );
    }

    case 'write_file': {
      let previousContent: string | null = null;
      try {
        previousContent = readProjectFile(activeProject, args.path).content;
      } catch {
        previousContent = null;
      }
      const res = writeProjectFile(activeProject, args.path, args.content);
      ctx.emitUi({
        type: 'file_changed',
        payload: {
          projectId: activeProject,
          path: res.path,
          content: args.content,
          previousContent,
          created: res.created,
          openInEditor: args.openInEditor !== false,
        },
      });
      if (activeProject === ctx.snapshot.activeProjectId && args.openInEditor !== false) {
        ctx.snapshot.activeFile = res.path;
        if (!ctx.snapshot.openTabs.includes(res.path)) ctx.snapshot.openTabs.push(res.path);
      }
      return JSON.stringify(res, null, 2);
    }

    case 'edit_file': {
      const previousContent = readProjectFile(activeProject, args.path).content;
      const res = editProjectFile(activeProject, args.path, args.oldText, args.newText, Boolean(args.replaceAll));
      ctx.emitUi({
        type: 'file_changed',
        payload: {
          projectId: activeProject,
          path: res.path,
          content: res.content,
          previousContent,
          created: false,
          openInEditor: true,
        },
      });
      return JSON.stringify({ path: res.path, replacements: res.replacements }, null, 2);
    }

    case 'delete_path': {
      deleteProjectPath(activeProject, args.path);
      ctx.emitUi({ type: 'file_deleted', payload: { projectId: activeProject, path: args.path } });
      return JSON.stringify({ deleted: args.path }, null, 2);
    }

    case 'search_in_project': {
      const matches = searchInProject(activeProject, args.query, Boolean(args.isRegex));
      return JSON.stringify({ projectId: activeProject, count: matches.length, matches }, null, 2);
    }

    case 'run_code': {
      const result = args.filePath
        ? await runProjectFile({
            projectId: activeProject,
            filePath: args.filePath,
            stdin: args.stdin,
            args: args.args,
            languageOverride: args.language,
          })
        : await runSnippet({
            projectId: activeProject,
            language: args.language || 'javascript',
            code: args.snippet || '',
            stdin: args.stdin,
          });
      ctx.emitUi({ type: 'execution_result', payload: result });
      if (result.previewUrl) {
        ctx.emitUi({
          type: 'ui_control',
          payload: { bottomTab: 'preview', showBottomPanel: true, previewUrl: result.previewUrl },
        });
      } else {
        ctx.emitUi({
          type: 'ui_control',
          payload: { bottomTab: 'output', showBottomPanel: true },
        });
      }
      return JSON.stringify(result, null, 2);
    }

    case 'run_terminal_command': {
      const result = await runShellCommand({
        projectId: activeProject,
        command: args.command,
        timeoutMs: args.timeoutMs,
      });
      ctx.emitUi({ type: 'execution_result', payload: result });
      ctx.emitUi({ type: 'ui_control', payload: { bottomTab: 'output', showBottomPanel: true } });
      return JSON.stringify(result, null, 2);
    }

    case 'check_syntax_diagnostics': {
      const items = await checkProjectDiagnostics(activeProject, args.filePath);
      ctx.emitUi({ type: 'diagnostics_result', payload: { projectId: activeProject, diagnostics: items } });
      return JSON.stringify({ count: items.length, diagnostics: items }, null, 2);
    }

    case 'ide_open_file': {
      // Verify file exists
      readProjectFile(activeProject, args.path);
      ctx.snapshot.activeFile = args.path;
      ctx.emitUi({
        type: 'open_file',
        payload: {
          projectId: activeProject,
          path: args.path,
          line: args.line || 1,
          column: args.column || 1,
        },
      });
      return JSON.stringify({ opened: args.path, line: args.line || 1, column: args.column || 1 }, null, 2);
    }

    case 'ide_control_ui': {
      ctx.emitUi({ type: 'ui_control', payload: args });
      return JSON.stringify({ applied: args }, null, 2);
    }

    case 'checkpoint_or_git': {
      if (args.mode === 'checkpoint_create') {
        const cp = createCheckpoint(activeProject, args.labelOrMessage || 'Agent checkpoint');
        ctx.emitUi({ type: 'checkpoint_created', payload: cp });
        return JSON.stringify(cp, null, 2);
      }
      if (args.mode === 'checkpoint_list') {
        return JSON.stringify({ checkpoints: listCheckpoints(activeProject) }, null, 2);
      }
      if (args.mode === 'checkpoint_rollback') {
        const res = rollbackCheckpoint(activeProject, args.checkpointId);
        ctx.emitUi({ type: 'project_switched', payload: { projectId: activeProject } });
        return JSON.stringify(res, null, 2);
      }
      if (args.mode === 'git') {
        const r = await runProjectGit(activeProject, args.gitAction || 'status', args.labelOrMessage);
        return JSON.stringify(r, null, 2);
      }
      throw new Error('Invalid checkpoint_or_git mode');
    }

    default:
      throw new Error(`Unknown tool: ${name}`);
  }
}
