import React, { useEffect, useRef } from 'react';
import * as monaco from 'monaco-editor';
import editorWorker from 'monaco-editor/editor/editor.worker?worker';
import jsonWorker from 'monaco-editor/language/json/json.worker?worker';
import cssWorker from 'monaco-editor/language/css/css.worker?worker';
import htmlWorker from 'monaco-editor/language/html/html.worker?worker';
import tsWorker from 'monaco-editor/language/typescript/ts.worker?worker';
import { Play, Save, ShieldCheck, Sparkles, GitCompare, Code2 } from 'lucide-react';
import { useIdeStore } from '../../store/useIdeStore';

// Configure Monaco local Vite workers once
if (typeof self !== 'undefined' && !(self as any).MonacoEnvironment) {
  (self as any).MonacoEnvironment = {
    getWorker(_: unknown, label: string) {
      if (label === 'json') return new jsonWorker();
      if (label === 'css' || label === 'scss' || label === 'less') return new cssWorker();
      if (label === 'html' || label === 'handlebars' || label === 'razor') return new htmlWorker();
      if (label === 'typescript' || label === 'javascript') return new tsWorker();
      return new editorWorker();
    },
  };
}

export const MonacoWorkspace: React.FC = () => {
  const containerRef = useRef<HTMLDivElement>(null);
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const suppressChangeRef = useRef(false);

  const {
    openTabs,
    activeFile,
    diagnostics,
    jumpRequest,
    updateTabContent,
    saveFile,
    runCurrentFile,
    runDiagnostics,
    setCursor,
    setSelection,
    setDiffModalTab,
    setAgentInputDraft,
    setShowAgentPanel,
    selection,
  } = useIdeStore();

  const activeTab = openTabs.find((t) => t.path === activeFile) || null;

  useEffect(() => {
    if (!containerRef.current) return;

    monaco.editor.defineTheme('nexus-obsidian', {
      base: 'vs-dark',
      inherit: true,
      rules: [
        { token: 'comment', foreground: '64748b', fontStyle: 'italic' },
        { token: 'keyword', foreground: '38bdf8' },
        { token: 'string', foreground: '34d399' },
        { token: 'number', foreground: 'f59e0b' },
        { token: 'type', foreground: 'a78bfa' },
      ],
      colors: {
        'editor.background': '#0d121c',
        'editor.lineHighlightBackground': '#141c2c',
        'editorLineNumber.foreground': '#475569',
        'editorLineNumber.activeForeground': '#38bdf8',
        'editor.selectionBackground': '#0284c740',
        'editorCursor.foreground': '#38bdf8',
      },
    });

    const editor = monaco.editor.create(containerRef.current, {
      value: '',
      language: 'plaintext',
      theme: 'nexus-obsidian',
      automaticLayout: true,
      fontSize: 13.5,
      lineHeight: 21,
      fontFamily: '"JetBrains Mono", "Fira Code", ui-monospace, SFMono-Regular, Menlo, monospace',
      fontLigatures: true,
      minimap: { enabled: true },
      scrollBeyondLastLine: false,
      smoothScrolling: true,
      cursorBlinking: 'smooth',
      cursorSmoothCaretAnimation: 'on',
      bracketPairColorization: { enabled: true },
      padding: { top: 10 },
      tabSize: 2,
    });

    editorRef.current = editor;

    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
      useIdeStore.getState().saveFile();
    });

    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => {
      useIdeStore.getState().runCurrentFile();
    });

    editor.onDidChangeModelContent(() => {
      if (suppressChangeRef.current) return;
      const currentActive = useIdeStore.getState().activeFile;
      if (currentActive) {
        updateTabContent(currentActive, editor.getValue());
      }
    });

    editor.onDidChangeCursorPosition((e) => {
      setCursor(e.position.lineNumber, e.position.column);
    });

    editor.onDidChangeCursorSelection((e) => {
      const model = editor.getModel();
      if (!model) return;
      const text = model.getValueInRange(e.selection);
      setSelection(text);
    });

    return () => {
      editor.dispose();
      editorRef.current = null;
    };
  }, []);

  // Sync active tab content & language into Monaco
  useEffect(() => {
    const editor = editorRef.current;
    if (!editor) return;
    if (!activeTab) {
      suppressChangeRef.current = true;
      editor.setValue('');
      suppressChangeRef.current = false;
      return;
    }

    const model = editor.getModel();
    if (model) {
      monaco.editor.setModelLanguage(model, activeTab.language === 'makefile' ? 'shell' : activeTab.language);
      if (model.getValue() !== activeTab.content) {
        suppressChangeRef.current = true;
        const pos = editor.getPosition();
        model.setValue(activeTab.content);
        if (pos) editor.setPosition(pos);
        suppressChangeRef.current = false;
      }
    }
  }, [activeTab?.path, activeTab?.content, activeTab?.language]);

  // Apply backend diagnostics to Monaco markers
  useEffect(() => {
    const editor = editorRef.current;
    if (!editor || !activeTab) return;
    const model = editor.getModel();
    if (!model) return;
    const fileDiags = diagnostics.filter((d) => d.file === activeTab.path);
    const markers: monaco.editor.IMarkerData[] = fileDiags.map((d) => ({
      severity:
        d.severity === 'error'
          ? monaco.MarkerSeverity.Error
          : d.severity === 'warning'
            ? monaco.MarkerSeverity.Warning
            : monaco.MarkerSeverity.Info,
      message: `[${d.source}] ${d.message}`,
      startLineNumber: Math.max(1, d.line),
      startColumn: Math.max(1, d.column),
      endLineNumber: Math.max(1, d.line),
      endColumn: 120,
    }));
    monaco.editor.setModelMarkers(model, 'nexus-diagnostics', markers);
  }, [diagnostics, activeTab?.path]);

  // Handle jump-to-line requests (search, problems, agent ide_open_file)
  useEffect(() => {
    const editor = editorRef.current;
    if (!editor || !jumpRequest || !activeTab || jumpRequest.path !== activeTab.path) return;
    setTimeout(() => {
      editor.revealLineInCenter(jumpRequest.line);
      editor.setPosition({ lineNumber: jumpRequest.line, column: jumpRequest.column || 1 });
      editor.focus();
    }, 30);
  }, [jumpRequest?.nonce, activeTab?.path]);

  const askAgentAboutSelection = () => {
    const snippet = selection.trim();
    const target = activeTab?.path || '';
    const prompt = snippet
      ? `Analiza y mejora este bloque de ${target}:\n\`\`\`\n${snippet}\n\`\`\``
      : `Revisa el archivo ${target} y dime qué mejoras o pruebas podemos implementar.`;
    setAgentInputDraft(prompt);
    setShowAgentPanel(true);
  };

  return (
    <div className="flex flex-col flex-1 min-h-0 bg-[#0d121c] relative">
      {/* Breadcrumbs & Quick Action Bar */}
      {activeTab && (
        <div className="flex items-center justify-between px-3 py-1.5 bg-[#0b1019] border-b border-slate-800/70 text-xs shrink-0">
          <div className="flex items-center gap-2 text-slate-400 truncate">
            <span className="font-mono text-slate-300 truncate">{activeTab.path}</span>
            <span className="px-1.5 py-0.5 rounded bg-slate-800 text-[10px] uppercase tracking-wider text-sky-300">
              {activeTab.language}
            </span>
          </div>

          <div className="flex items-center gap-1.5 shrink-0">
            {activeTab.previousAgentContent !== null && activeTab.previousAgentContent !== undefined && (
              <button
                onClick={() => setDiffModalTab(activeTab)}
                className="flex items-center gap-1 px-2 py-1 rounded bg-indigo-500/15 hover:bg-indigo-500/25 text-indigo-300 border border-indigo-500/30 transition"
                title="Comparar antes/después de edición del Agente"
              >
                <GitCompare className="w-3.5 h-3.5" />
                <span>Ver Diff IA</span>
              </button>
            )}
            <button
              onClick={askAgentAboutSelection}
              className="flex items-center gap-1 px-2 py-1 rounded bg-violet-500/15 hover:bg-violet-500/25 text-violet-300 border border-violet-500/30 transition"
              title="Enviar archivo o selección al Agente"
            >
              <Sparkles className="w-3.5 h-3.5" />
              <span>{selection.trim() ? 'IA: Selección' : 'IA: Revisar'}</span>
            </button>
            <button
              onClick={() => runDiagnostics(activeTab.path)}
              className="flex items-center gap-1 px-2 py-1 rounded bg-slate-800/80 hover:bg-slate-700 text-slate-300 transition"
              title="Verificar sintaxis y errores"
            >
              <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
              <span className="hidden sm:inline">Check</span>
            </button>
            <button
              onClick={() => saveFile(activeTab.path)}
              className={`flex items-center gap-1 px-2 py-1 rounded transition ${
                activeTab.dirty
                  ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40 hover:bg-amber-500/30'
                  : 'bg-slate-800/80 text-slate-400 hover:bg-slate-700'
              }`}
              title="Guardar (Ctrl+S)"
            >
              <Save className="w-3.5 h-3.5" />
              <span className="hidden sm:inline">Guardar</span>
            </button>
            <button
              onClick={() => runCurrentFile()}
              className="flex items-center gap-1 px-2.5 py-1 rounded bg-emerald-500/20 hover:bg-emerald-500/30 text-emerald-300 border border-emerald-500/40 font-medium transition"
              title="Ejecutar archivo (Ctrl+Enter)"
            >
              <Play className="w-3.5 h-3.5 fill-current" />
              <span>Run</span>
            </button>
          </div>
        </div>
      )}

      {/* Monaco Container */}
      <div className="relative flex-1 min-h-0">
        <div ref={containerRef} className="w-full h-full" />

        {!activeTab && (
          <div className="absolute inset-0 flex flex-col items-center justify-center bg-[#0d121c] text-center p-8 z-10">
            <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-sky-500/20 to-indigo-500/20 border border-sky-500/30 flex items-center justify-center mb-4">
              <Code2 className="w-8 h-8 text-sky-400" />
            </div>
            <h2 className="text-lg font-semibold text-slate-200">KODE KSST Workspace</h2>
            <p className="text-sm text-slate-400 max-w-md mt-2">
              Selecciona un archivo en el Explorador, presiona <kbd className="px-1.5 py-0.5 rounded bg-slate-800 text-sky-300 font-mono text-xs">Ctrl+Shift+P</kbd> para abrir la paleta de comandos o pídele al Agente que programe por ti.
            </p>
          </div>
        )}
      </div>
    </div>
  );
};
