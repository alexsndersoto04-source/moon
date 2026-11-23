import { create } from 'zustand';
import {
  AgentChatItem,
  BottomTab,
  BrainConfig,
  CheckpointInfo,
  DiagnosticItem,
  EditorTab,
  ExecutionResult,
  FileNode,
  MobileView,
  ProjectInfo,
  RuntimeCapability,
  SidebarTab,
} from '../types/ide';

const BRAIN_STORAGE_KEY = 'nexus_ide_brain_config_v1';

function loadBrainConfig(): BrainConfig {
  try {
    const raw = localStorage.getItem(BRAIN_STORAGE_KEY);
    if (raw) return JSON.parse(raw);
  } catch {}
  return {
    provider: 'openai',
    apiKey: '',
    baseUrl: 'https://api.openai.com/v1',
    model: 'gpt-4o',
    temperature: 0.2,
  };
}

export interface ToastItem {
  id: string;
  message: string;
  kind: 'info' | 'success' | 'error';
}

interface IdeState {
  // Projects & Files
  projects: ProjectInfo[];
  activeProjectId: string;
  fileTree: FileNode[];
  runtimes: RuntimeCapability[];
  checkpoints: CheckpointInfo[];

  // Editor Tabs
  openTabs: EditorTab[];
  activeFile: string | null;
  cursor: { line: number; column: number };
  selection: string;
  jumpRequest: { path: string; line: number; column: number; nonce: number } | null;
  diffModalTab: EditorTab | null;

  // Layout
  sidebarTab: SidebarTab;
  showSidebar: boolean;
  bottomTab: BottomTab;
  showBottomPanel: boolean;
  showAgentPanel: boolean;
  mobileView: MobileView;
  showCommandPalette: boolean;
  showNewProjectModal: boolean;
  showBrainModal: boolean;

  // Execution & Diagnostics
  executionHistory: ExecutionResult[];
  isRunning: boolean;
  stdinInput: string;
  previewUrl: string;
  previewNonce: number;
  diagnostics: DiagnosticItem[];

  // Agent & Brain
  brainConfig: BrainConfig;
  brainTestedOk: boolean | null;
  agentMessages: AgentChatItem[];
  agentRunning: boolean;
  activeRunId: string | null;
  autoApprove: boolean;
  agentInputDraft: string;

  // Notifications
  toasts: ToastItem[];

  // Actions
  initIde: () => Promise<void>;
  refreshProjects: () => Promise<void>;
  refreshFileTree: () => Promise<void>;
  refreshCheckpoints: () => Promise<void>;
  switchProject: (projectId: string) => Promise<void>;
  createProject: (opts: { id: string; name?: string; description?: string; template?: string }) => Promise<void>;

  openFile: (filePath: string, line?: number, column?: number) => Promise<void>;
  closeTab: (filePath: string) => void;
  updateTabContent: (filePath: string, content: string) => void;
  saveFile: (filePath?: string) => Promise<void>;
  saveAllDirty: () => Promise<void>;
  createFile: (filePath: string, content?: string) => Promise<void>;
  createFolder: (folderPath: string) => Promise<void>;
  deletePath: (targetPath: string) => Promise<void>;
  renamePath: (oldPath: string, newPath: string) => Promise<void>;

  setCursor: (line: number, column: number) => void;
  setSelection: (text: string) => void;
  setDiffModalTab: (tab: EditorTab | null) => void;

  runCurrentFile: (args?: string[]) => Promise<void>;
  runSpecificFile: (filePath: string, args?: string[]) => Promise<void>;
  runSnippet: (language: string, code: string) => Promise<void>;
  runCommand: (command: string) => Promise<void>;
  runDiagnostics: (filePath?: string) => Promise<void>;
  setStdinInput: (val: string) => void;
  createCheckpoint: (label: string) => Promise<void>;
  rollbackCheckpoint: (cpId: string) => Promise<void>;

  setSidebarTab: (tab: SidebarTab) => void;
  setShowSidebar: (v: boolean) => void;
  setBottomTab: (tab: BottomTab) => void;
  setShowBottomPanel: (v: boolean) => void;
  setShowAgentPanel: (v: boolean) => void;
  setMobileView: (v: MobileView) => void;
  setShowCommandPalette: (v: boolean) => void;
  setShowNewProjectModal: (v: boolean) => void;
  setShowBrainModal: (v: boolean) => void;
  setPreviewUrl: (url: string) => void;
  reloadPreview: () => void;

  setBrainConfig: (cfg: BrainConfig) => void;
  setBrainTestedOk: (ok: boolean | null) => void;
  setAutoApprove: (v: boolean) => void;
  setAgentInputDraft: (v: string) => void;
  clearAgentChat: () => void;
  sendAgentPrompt: (prompt: string) => Promise<void>;
  abortAgent: () => Promise<void>;
  approveToolCall: (approvalId: string, approved: boolean) => Promise<void>;

  addToast: (message: string, kind?: 'info' | 'success' | 'error') => void;
  removeToast: (id: string) => void;
}

export const useIdeStore = create<IdeState>((set, get) => ({
  projects: [],
  activeProjectId: '',
  fileTree: [],
  runtimes: [],
  checkpoints: [],

  openTabs: [],
  activeFile: null,
  cursor: { line: 1, column: 1 },
  selection: '',
  jumpRequest: null,
  diffModalTab: null,

  sidebarTab: 'explorer',
  showSidebar: true,
  bottomTab: 'output',
  showBottomPanel: true,
  showAgentPanel: true,
  mobileView: 'editor',
  showCommandPalette: false,
  showNewProjectModal: false,
  showBrainModal: false,

  executionHistory: [],
  isRunning: false,
  stdinInput: '',
  previewUrl: '',
  previewNonce: 1,
  diagnostics: [],

  brainConfig: loadBrainConfig(),
  brainTestedOk: null,
  agentMessages: [
    {
      id: 'welcome-msg',
      role: 'assistant',
      timestamp: new Date().toISOString(),
      content:
        'Hola. Soy **KODE KSST AGENT**, el ingeniero autónomo integrado en todo este IDE.\n\nConozco la estructura completa de tus proyectos, el editor Monaco, las pestañas abiertas, tu selección de código, los diagnósticos y los motores de ejecución (TypeScript, JavaScript, Python 3, C17, C++20, SQLite, Bash, Make y Web Preview).\n\nHaz clic en **"Conectar Cerebro API"** arriba para vincular tu API key (OpenAI, Anthropic Claude, Gemini, DeepSeek, Groq, OpenRouter u Ollama) y empezaré a programar, compilar, depurar y operar el editor por ti.',
    },
  ],
  agentRunning: false,
  activeRunId: null,
  autoApprove: true,
  agentInputDraft: '',

  toasts: [],

  addToast: (message, kind = 'info') => {
    const id = `toast_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
    set((s) => ({ toasts: [...s.toasts, { id, message, kind }] }));
    setTimeout(() => {
      set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }));
    }, 4000);
  },

  removeToast: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),

  initIde: async () => {
    const [projRes, rtRes] = await Promise.all([
      fetch('/api/projects').then((r) => r.json()),
      fetch('/api/runtimes').then((r) => r.json()),
    ]);
    const projects: ProjectInfo[] = projRes.projects || [];
    const runtimes: RuntimeCapability[] = rtRes.runtimes || [];
    set({ projects, runtimes });
    if (projects.length > 0) {
      await get().switchProject(projects[0].id);
    }
  },

  refreshProjects: async () => {
    const res = await fetch('/api/projects').then((r) => r.json());
    set({ projects: res.projects || [] });
  },

  refreshFileTree: async () => {
    const pid = get().activeProjectId;
    if (!pid) return;
    const res = await fetch(`/api/projects/${encodeURIComponent(pid)}/tree`).then((r) => r.json());
    set({ fileTree: res.tree || [] });
  },

  refreshCheckpoints: async () => {
    const pid = get().activeProjectId;
    if (!pid) return;
    const res = await fetch(`/api/projects/${encodeURIComponent(pid)}/checkpoints`).then((r) => r.json());
    set({ checkpoints: res.checkpoints || [] });
  },

  switchProject: async (projectId: string) => {
    set({
      activeProjectId: projectId,
      openTabs: [],
      activeFile: null,
      diagnostics: [],
      executionHistory: [],
    });
    await Promise.all([get().refreshFileTree(), get().refreshCheckpoints()]);
    const proj = get().projects.find((p) => p.id === projectId);
    if (proj?.entryFile) {
      try {
        await get().openFile(proj.entryFile);
        if (proj.entryFile.endsWith('.html')) {
          set({ previewUrl: `/preview/${projectId}/${proj.entryFile}`, previewNonce: Date.now() });
        }
      } catch {}
    }
    await get().runDiagnostics();
  },

  createProject: async (opts) => {
    const res = await fetch('/api/projects', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(opts),
    }).then((r) => r.json());
    if (res.error) throw new Error(res.error);
    set({ projects: res.projects });
    await get().switchProject(res.project.id);
    get().addToast(`Proyecto creado: ${res.project.name}`, 'success');
  },

  openFile: async (filePath, line, column) => {
    const pid = get().activeProjectId;
    if (!pid) return;
    const existing = get().openTabs.find((t) => t.path === filePath);
    if (existing) {
      set({
        activeFile: filePath,
        mobileView: 'editor',
        jumpRequest: line ? { path: filePath, line, column: column || 1, nonce: Date.now() } : get().jumpRequest,
      });
      return;
    }
    const res = await fetch(`/api/projects/${encodeURIComponent(pid)}/file?path=${encodeURIComponent(filePath)}`).then((r) =>
      r.json()
    );
    if (res.error) {
      get().addToast(res.error, 'error');
      return;
    }
    const newTab: EditorTab = {
      path: filePath,
      content: res.content,
      savedContent: res.content,
      language: res.language,
      dirty: false,
      symbols: res.symbols || [],
      previousAgentContent: null,
    };
    set((s) => ({
      openTabs: [...s.openTabs, newTab],
      activeFile: filePath,
      mobileView: 'editor',
      jumpRequest: line ? { path: filePath, line, column: column || 1, nonce: Date.now() } : s.jumpRequest,
    }));
  },

  closeTab: (filePath) => {
    set((s) => {
      const next = s.openTabs.filter((t) => t.path !== filePath);
      let nextActive = s.activeFile;
      if (s.activeFile === filePath) {
        nextActive = next.length > 0 ? next[next.length - 1].path : null;
      }
      return { openTabs: next, activeFile: nextActive };
    });
  },

  updateTabContent: (filePath, content) => {
    set((s) => ({
      openTabs: s.openTabs.map((t) =>
        t.path === filePath ? { ...t, content, dirty: content !== t.savedContent } : t
      ),
    }));
  },

  saveFile: async (targetPath) => {
    const pid = get().activeProjectId;
    const filePath = targetPath || get().activeFile;
    if (!pid || !filePath) return;
    const tab = get().openTabs.find((t) => t.path === filePath);
    if (!tab) return;

    const res = await fetch(`/api/projects/${encodeURIComponent(pid)}/file`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: filePath, content: tab.content }),
    }).then((r) => r.json());
    if (res.error) {
      get().addToast(res.error, 'error');
      return;
    }
    // Reload symbols & diagnostics
    const meta = await fetch(`/api/projects/${encodeURIComponent(pid)}/file?path=${encodeURIComponent(filePath)}`).then((r) =>
      r.json()
    );
    set((s) => ({
      openTabs: s.openTabs.map((t) =>
        t.path === filePath
          ? { ...t, savedContent: t.content, dirty: false, symbols: meta.symbols || t.symbols }
          : t
      ),
    }));
    await get().runDiagnostics(filePath);
    if (filePath.endsWith('.html') || filePath.endsWith('.css') || filePath.endsWith('.js')) {
      get().reloadPreview();
    }
  },

  saveAllDirty: async () => {
    const dirtyTabs = get().openTabs.filter((t) => t.dirty);
    for (const t of dirtyTabs) {
      await get().saveFile(t.path);
    }
  },

  createFile: async (filePath, content = '') => {
    const pid = get().activeProjectId;
    if (!pid) return;
    const res = await fetch(`/api/projects/${encodeURIComponent(pid)}/file`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: filePath, content }),
    }).then((r) => r.json());
    if (res.error) {
      get().addToast(res.error, 'error');
      return;
    }
    await get().refreshFileTree();
    await get().openFile(filePath);
  },

  createFolder: async (folderPath) => {
    const pid = get().activeProjectId;
    if (!pid) return;
    await fetch(`/api/projects/${encodeURIComponent(pid)}/mkdir`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: folderPath }),
    });
    await get().refreshFileTree();
  },

  deletePath: async (targetPath) => {
    const pid = get().activeProjectId;
    if (!pid) return;
    await fetch(`/api/projects/${encodeURIComponent(pid)}/file?path=${encodeURIComponent(targetPath)}`, {
      method: 'DELETE',
    });
    get().closeTab(targetPath);
    await get().refreshFileTree();
  },

  renamePath: async (oldPath, newPath) => {
    const pid = get().activeProjectId;
    if (!pid) return;
    await fetch(`/api/projects/${encodeURIComponent(pid)}/rename`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ oldPath, newPath }),
    });
    get().closeTab(oldPath);
    await get().refreshFileTree();
    await get().openFile(newPath);
  },

  setCursor: (line, column) => set({ cursor: { line, column } }),
  setSelection: (text) => set({ selection: text }),
  setDiffModalTab: (tab) => set({ diffModalTab: tab }),

  runCurrentFile: async (args) => {
    const active = get().activeFile;
    if (!active) {
      get().addToast('Abre un archivo para ejecutarlo.', 'error');
      return;
    }
    await get().saveAllDirty();
    await get().runSpecificFile(active, args);
  },

  runSpecificFile: async (filePath: string, args?: string[]) => {
    const pid = get().activeProjectId;
    if (!pid) return;
    set({ isRunning: true, showBottomPanel: true });
    try {
      const result: ExecutionResult = await fetch(`/api/projects/${encodeURIComponent(pid)}/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          filePath,
          stdin: get().stdinInput || undefined,
          args,
        }),
      }).then((r) => r.json());

      const stamped = { ...result, timestamp: new Date().toLocaleTimeString() };
      set((s) => ({
        executionHistory: [stamped, ...s.executionHistory].slice(0, 30),
        bottomTab: result.previewUrl ? 'preview' : 'output',
        mobileView: 'console',
        previewUrl: result.previewUrl || s.previewUrl,
        previewNonce: result.previewUrl ? Date.now() : s.previewNonce,
      }));
    } catch (err: any) {
      get().addToast(err.message || 'Execution failed', 'error');
    } finally {
      set({ isRunning: false });
    }
  },

  runSnippet: async (language, code) => {
    const pid = get().activeProjectId;
    if (!pid) return;
    set({ isRunning: true, showBottomPanel: true, bottomTab: 'output' });
    try {
      const result: ExecutionResult = await fetch(`/api/projects/${encodeURIComponent(pid)}/run-snippet`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ language, code, stdin: get().stdinInput || undefined }),
      }).then((r) => r.json());
      set((s) => ({
        executionHistory: [{ ...result, timestamp: new Date().toLocaleTimeString() }, ...s.executionHistory].slice(0, 30),
      }));
    } finally {
      set({ isRunning: false });
    }
  },

  runCommand: async (command) => {
    const pid = get().activeProjectId;
    if (!pid) return;
    set({ isRunning: true, showBottomPanel: true, bottomTab: 'output' });
    try {
      const result: ExecutionResult = await fetch(`/api/projects/${encodeURIComponent(pid)}/exec`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ command }),
      }).then((r) => r.json());
      set((s) => ({
        executionHistory: [{ ...result, timestamp: new Date().toLocaleTimeString() }, ...s.executionHistory].slice(0, 30),
      }));
    } finally {
      set({ isRunning: false });
    }
  },

  runDiagnostics: async (filePath) => {
    const pid = get().activeProjectId;
    if (!pid) return;
    const res = await fetch(`/api/projects/${encodeURIComponent(pid)}/diagnostics`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ filePath }),
    }).then((r) => r.json());
    const incoming: DiagnosticItem[] = res.diagnostics || [];
    if (!filePath) {
      set({ diagnostics: incoming });
    } else {
      set((s) => ({
        diagnostics: [...s.diagnostics.filter((d) => d.file !== filePath), ...incoming],
      }));
    }
  },

  setStdinInput: (val) => set({ stdinInput: val }),

  createCheckpoint: async (label) => {
    const pid = get().activeProjectId;
    if (!pid) return;
    await fetch(`/api/projects/${encodeURIComponent(pid)}/checkpoints`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ label }),
    });
    await get().refreshCheckpoints();
    get().addToast(`Checkpoint creado: ${label}`, 'success');
  },

  rollbackCheckpoint: async (cpId) => {
    const pid = get().activeProjectId;
    if (!pid) return;
    await fetch(`/api/projects/${encodeURIComponent(pid)}/checkpoints/${encodeURIComponent(cpId)}/rollback`, {
      method: 'POST',
    });
    await get().switchProject(pid);
    get().addToast('Proyecto restaurado al checkpoint', 'success');
  },

  setSidebarTab: (tab) => set({ sidebarTab: tab, showSidebar: true, mobileView: 'files' }),
  setShowSidebar: (v) => set({ showSidebar: v }),
  setBottomTab: (tab) => set({ bottomTab: tab, showBottomPanel: true, mobileView: 'console' }),
  setShowBottomPanel: (v) => set({ showBottomPanel: v }),
  setShowAgentPanel: (v) => set({ showAgentPanel: v, mobileView: v ? 'agent' : 'editor' }),
  setMobileView: (v) => set({ mobileView: v }),
  setShowCommandPalette: (v) => set({ showCommandPalette: v }),
  setShowNewProjectModal: (v) => set({ showNewProjectModal: v }),
  setShowBrainModal: (v) => set({ showBrainModal: v }),
  setPreviewUrl: (url) => set({ previewUrl: url, previewNonce: Date.now() }),
  reloadPreview: () => set({ previewNonce: Date.now() }),

  setBrainConfig: (cfg) => {
    try {
      localStorage.setItem(BRAIN_STORAGE_KEY, JSON.stringify(cfg));
    } catch {}
    set({ brainConfig: cfg, brainTestedOk: null });
  },
  setBrainTestedOk: (ok) => set({ brainTestedOk: ok }),
  setAutoApprove: (v) => set({ autoApprove: v }),
  setAgentInputDraft: (v) => set({ agentInputDraft: v }),
  clearAgentChat: () =>
    set({
      agentMessages: [
        {
          id: `clear_${Date.now()}`,
          role: 'assistant',
          timestamp: new Date().toISOString(),
          content: 'Sesión reiniciada. Estoy listo para programar y operar cualquier archivo o proyecto del IDE.',
        },
      ],
    }),

  sendAgentPrompt: async (prompt: string) => {
    const state = get();
    if (!prompt.trim() || state.agentRunning) return;

    if (!state.brainConfig.apiKey && !state.brainConfig.baseUrl?.includes('localhost') && !state.brainConfig.baseUrl?.includes('127.0.0.1')) {
      set({ showBrainModal: true });
      get().addToast('Conecta tu API Key en "Conectar Cerebro API" primero.', 'error');
      return;
    }

    await get().saveAllDirty();

    const runId = `run_${Date.now()}`;
    const userMsg: AgentChatItem = {
      id: `u_${Date.now()}`,
      role: 'user',
      content: prompt,
      timestamp: new Date().toISOString(),
    };
    const assistantMsgId = `a_${Date.now()}`;
    const assistantMsg: AgentChatItem = {
      id: assistantMsgId,
      role: 'assistant',
      content: '',
      timestamp: new Date().toISOString(),
      toolCalls: [],
    };

    const history = state.agentMessages
      .filter((m) => m.role === 'user' || m.role === 'assistant')
      .filter((m) => m.id !== 'welcome-msg')
      .map((m) => ({ role: m.role as 'user' | 'assistant', content: m.content }));

    const lastExec = state.executionHistory[0];
    const snapshot = {
      activeProjectId: state.activeProjectId,
      openTabs: state.openTabs.map((t) => t.path),
      activeFile: state.activeFile,
      cursor: state.cursor,
      selection: state.selection,
      activeBottomTab: state.bottomTab,
      activeSidebarTab: state.sidebarTab,
      lastRunOutput: lastExec
        ? {
            command: lastExec.command,
            exitCode: lastExec.exitCode,
            stdout: lastExec.stdout,
            stderr: lastExec.stderr,
          }
        : undefined,
      diagnostics: state.diagnostics.map((d) => ({
        file: d.file,
        line: d.line,
        severity: d.severity,
        message: d.message,
      })),
    };

    set((s) => ({
      agentMessages: [...s.agentMessages, userMsg, assistantMsg],
      agentRunning: true,
      activeRunId: runId,
      showAgentPanel: true,
    }));

    const updateAssistant = (updater: (msg: AgentChatItem) => AgentChatItem) => {
      set((s) => ({
        agentMessages: s.agentMessages.map((m) => (m.id === assistantMsgId ? updater(m) : m)),
      }));
    };

    try {
      const response = await fetch('/api/agent/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          runId,
          brain: state.brainConfig,
          prompt,
          history,
          snapshot,
          autoApprove: state.autoApprove,
          maxSteps: 20,
        }),
      });

      if (!response.body) throw new Error('No response stream from agent server.');
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      const handleSseEvent = async (eventName: string, data: any) => {
        if (eventName === 'assistant_message') {
          updateAssistant((m) => ({
            ...m,
            step: data.step,
            content: m.content ? `${m.content}\n\n${data.text}` : data.text,
          }));
        } else if (eventName === 'tool_call') {
          updateAssistant((m) => ({
            ...m,
            toolCalls: [
              ...(m.toolCalls || []),
              {
                toolCallId: data.toolCallId,
                name: data.name,
                arguments: data.arguments,
                status: 'running',
              },
            ],
          }));
        } else if (eventName === 'approval_required') {
          updateAssistant((m) => ({
            ...m,
            toolCalls: (m.toolCalls || []).map((tc) =>
              tc.toolCallId === data.toolCallId
                ? { ...tc, status: 'awaiting_approval', approvalId: data.approvalId }
                : tc
            ),
          }));
        } else if (eventName === 'tool_result') {
          updateAssistant((m) => ({
            ...m,
            toolCalls: (m.toolCalls || []).map((tc) =>
              tc.toolCallId === data.toolCallId
                ? { ...tc, status: data.ok ? 'ok' : 'error', output: data.output }
                : tc
            ),
          }));
        } else if (eventName === 'ui_event') {
          const { type, payload } = data;
          if (type === 'projects_updated') {
            set({ projects: payload.projects });
          } else if (type === 'project_switched') {
            await get().refreshProjects();
            await get().switchProject(payload.projectId);
          } else if (type === 'file_changed') {
            await get().refreshFileTree();
            const isActiveProj = payload.projectId === get().activeProjectId;
            if (isActiveProj) {
              const existing = get().openTabs.find((t) => t.path === payload.path);
              if (existing) {
                set((s) => ({
                  openTabs: s.openTabs.map((t) =>
                    t.path === payload.path
                      ? {
                          ...t,
                          content: payload.content,
                          savedContent: payload.content,
                          dirty: false,
                          previousAgentContent: payload.previousContent ?? t.content,
                        }
                      : t
                  ),
                  activeFile: payload.openInEditor ? payload.path : s.activeFile,
                }));
              } else if (payload.openInEditor) {
                await get().openFile(payload.path);
                set((s) => ({
                  openTabs: s.openTabs.map((t) =>
                    t.path === payload.path
                      ? { ...t, previousAgentContent: payload.previousContent ?? null }
                      : t
                  ),
                }));
              }
              if (payload.path.endsWith('.html') || payload.path.endsWith('.css') || payload.path.endsWith('.js')) {
                get().reloadPreview();
              }
            }
          } else if (type === 'file_deleted') {
            get().closeTab(payload.path);
            await get().refreshFileTree();
          } else if (type === 'open_file') {
            if (payload.projectId === get().activeProjectId) {
              await get().openFile(payload.path, payload.line, payload.column);
            }
          } else if (type === 'execution_result') {
            set((s) => ({
              executionHistory: [{ ...payload, timestamp: new Date().toLocaleTimeString() }, ...s.executionHistory].slice(0, 30),
            }));
          } else if (type === 'diagnostics_result') {
            set({ diagnostics: payload.diagnostics || [] });
          } else if (type === 'checkpoint_created') {
            await get().refreshCheckpoints();
          } else if (type === 'ui_control') {
            set((s) => ({
              sidebarTab: payload.sidebarTab || s.sidebarTab,
              bottomTab: payload.bottomTab || s.bottomTab,
              showBottomPanel: payload.showBottomPanel !== undefined ? payload.showBottomPanel : s.showBottomPanel,
              previewUrl: payload.previewUrl || s.previewUrl,
              previewNonce: payload.previewUrl ? Date.now() : s.previewNonce,
            }));
            if (payload.toast) get().addToast(payload.toast, 'info');
          }
        } else if (eventName === 'error') {
          updateAssistant((m) => ({
            ...m,
            content: (m.content ? m.content + '\n\n' : '') + `**Error:** ${data.message}`,
          }));
          get().addToast(data.message, 'error');
        } else if (eventName === 'aborted') {
          updateAssistant((m) => ({
            ...m,
            content: (m.content ? m.content + '\n\n' : '') + '*(Ejecución detenida por el usuario)*',
          }));
        }
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let idx: number;
        while ((idx = buffer.indexOf('\n\n')) !== -1) {
          const block = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          let evName = 'message';
          let dataStr = '';
          for (const line of block.split('\n')) {
            if (line.startsWith('event:')) evName = line.slice(6).trim();
            else if (line.startsWith('data:')) dataStr += line.slice(5).trim();
          }
          if (dataStr) {
            try {
              await handleSseEvent(evName, JSON.parse(dataStr));
            } catch {}
          }
        }
      }
    } catch (err: any) {
      updateAssistant((m) => ({
        ...m,
        content: (m.content ? m.content + '\n\n' : '') + `**Error de conexión:** ${err.message}`,
      }));
    } finally {
      set({ agentRunning: false, activeRunId: null });
    }
  },

  abortAgent: async () => {
    const runId = get().activeRunId;
    if (!runId) return;
    await fetch('/api/agent/abort', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ runId }),
    });
    set({ agentRunning: false, activeRunId: null });
  },

  approveToolCall: async (approvalId, approved) => {
    await fetch('/api/agent/approve', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ approvalId, approved }),
    });
    set((s) => ({
      agentMessages: s.agentMessages.map((m) => ({
        ...m,
        toolCalls: m.toolCalls?.map((tc) =>
          tc.approvalId === approvalId ? { ...tc, status: approved ? 'running' : 'error' } : tc
        ),
      })),
    }));
  },
}));
