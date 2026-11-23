import React from 'react';
import {
  Bot,
  Brain,
  Command,
  FolderPlus,
  PanelBottom,
  PanelLeft,
  Play,
  Save,
  ShieldCheck,
  Terminal,
  Zap,
} from 'lucide-react';
import { useIdeStore } from '../store/useIdeStore';

export const TopBar: React.FC = () => {
  const {
    projects,
    activeProjectId,
    openTabs,
    showSidebar,
    showBottomPanel,
    showAgentPanel,
    isRunning,
    brainConfig,
    switchProject,
    runCurrentFile,
    saveAllDirty,
    runDiagnostics,
    setShowSidebar,
    setShowBottomPanel,
    setBottomTab,
    setShowAgentPanel,
    setShowCommandPalette,
    setShowNewProjectModal,
    setShowBrainModal,
  } = useIdeStore();

  const dirtyCount = openTabs.filter((t) => t.dirty).length;
  const hasBrainKey = Boolean(brainConfig.apiKey?.trim()) || Boolean(brainConfig.baseUrl?.includes('127.0.0.1')) || Boolean(brainConfig.baseUrl?.includes('localhost'));

  return (
    <header className="h-11 bg-[#070a10] border-b border-slate-800/90 px-2 sm:px-3 flex items-center justify-between gap-2 sm:gap-3 shrink-0">
      {/* Brand & Project Switcher */}
      <div className="flex items-center gap-2 sm:gap-3 min-w-0">
        <div className="flex items-center gap-1.5 sm:gap-2 shrink-0">
          <div className="w-7 h-7 rounded-lg bg-gradient-to-br from-sky-500 to-violet-600 flex items-center justify-center shadow-lg shadow-sky-500/20">
            <Zap className="w-4 h-4 text-white" />
          </div>
          <span className="font-bold text-xs sm:text-sm tracking-tight bg-gradient-to-r from-sky-300 to-violet-300 bg-clip-text text-transparent whitespace-nowrap">
            KODE KSST
          </span>
        </div>

        <div className="hidden sm:block h-4 w-px bg-slate-800" />

        <div className="flex items-center gap-1 min-w-0">
          <select
            value={activeProjectId}
            onChange={(e) => switchProject(e.target.value)}
            className="bg-slate-900 border border-slate-700/80 rounded-md px-1.5 sm:px-2.5 py-1 text-xs text-slate-200 font-medium focus:outline-none focus:border-sky-500 max-w-[115px] sm:max-w-[220px]"
            title="Cambiar de proyecto"
          >
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
          <button
            onClick={() => setShowNewProjectModal(true)}
            className="flex items-center gap-1 px-1.5 sm:px-2 py-1 rounded-md bg-slate-800/80 hover:bg-slate-700 text-slate-200 text-xs shrink-0"
            title="Crear nuevo proyecto"
          >
            <FolderPlus className="w-3.5 h-3.5 text-sky-400" />
            <span className="hidden md:inline">Nuevo</span>
          </button>
        </div>
      </div>

      {/* Center Execution & Command Palette */}
      <div className="flex items-center gap-1 sm:gap-1.5 shrink-0">
        <button
          onClick={() => setShowCommandPalette(true)}
          className="hidden lg:flex items-center gap-2 px-3 py-1 rounded-md bg-slate-900/90 border border-slate-800 hover:border-slate-700 text-slate-400 text-xs"
          title="Paleta de Comandos (Ctrl+Shift+P)"
        >
          <Command className="w-3.5 h-3.5 text-sky-400" />
          <span>Comandos</span>
          <kbd className="px-1 py-0.2 rounded bg-slate-800 text-[10px] font-mono text-slate-300">Ctrl+K</kbd>
        </button>

        <button
          onClick={() => runCurrentFile()}
          disabled={isRunning}
          className="flex items-center gap-1 px-2 sm:px-3 py-1 rounded-md bg-emerald-500/20 hover:bg-emerald-500/30 disabled:opacity-50 text-emerald-300 border border-emerald-500/40 text-xs font-semibold transition"
          title="Ejecutar archivo actual (Ctrl+Enter)"
        >
          <Play className="w-3.5 h-3.5 fill-current" />
          <span className="hidden xs:inline sm:inline">{isRunning ? 'Run...' : 'Ejecutar'}</span>
        </button>

        <button
          onClick={() => runDiagnostics()}
          className="hidden sm:flex items-center gap-1 px-2.5 py-1 rounded-md bg-slate-800/80 hover:bg-slate-700 text-slate-300 text-xs"
          title="Verificar sintaxis de todo el proyecto"
        >
          <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
          <span>Check</span>
        </button>

        {dirtyCount > 0 && (
          <button
            onClick={() => saveAllDirty()}
            className="flex items-center gap-1 px-2 py-1 rounded-md bg-amber-500/20 hover:bg-amber-500/30 text-amber-300 border border-amber-500/40 text-xs"
            title="Guardar cambios"
          >
            <Save className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Guardar ({dirtyCount})</span>
          </button>
        )}
      </div>

      {/* Right Panel Toggles & Brain */}
      <div className="flex items-center gap-1 sm:gap-1.5 shrink-0">
        <button
          onClick={() => setShowBrainModal(true)}
          className={`flex items-center gap-1.5 px-2 sm:px-2.5 py-1 rounded-md text-xs font-medium border transition ${
            hasBrainKey
              ? 'bg-emerald-500/15 border-emerald-500/30 text-emerald-300 hover:bg-emerald-500/25'
              : 'bg-violet-600/90 hover:bg-violet-500 border-violet-400/40 text-white'
          }`}
          title="Conectar Cerebro API"
        >
          <Brain className="w-3.5 h-3.5" />
          <span className="hidden md:inline">{hasBrainKey ? brainConfig.model : 'Conectar Cerebro API'}</span>
          <span className="md:hidden">API</span>
        </button>

        <button
          onClick={() => {
            setShowBottomPanel(true);
            setBottomTab('terminal');
          }}
          className="hidden md:inline-flex p-1.5 rounded-md hover:bg-slate-800 text-slate-400 hover:text-slate-200"
          title="Abrir Terminal PTY"
        >
          <Terminal className="w-4 h-4" />
        </button>
        <button
          onClick={() => setShowSidebar(!showSidebar)}
          className={`hidden md:inline-flex p-1.5 rounded-md ${showSidebar ? 'bg-slate-800 text-sky-400' : 'text-slate-400 hover:bg-slate-800'}`}
          title="Mostrar/ocultar barra lateral"
        >
          <PanelLeft className="w-4 h-4" />
        </button>
        <button
          onClick={() => setShowBottomPanel(!showBottomPanel)}
          className={`hidden md:inline-flex p-1.5 rounded-md ${showBottomPanel ? 'bg-slate-800 text-sky-400' : 'text-slate-400 hover:bg-slate-800'}`}
          title="Mostrar/ocultar panel inferior"
        >
          <PanelBottom className="w-4 h-4" />
        </button>
        <button
          onClick={() => setShowAgentPanel(!showAgentPanel)}
          className={`hidden md:inline-flex p-1.5 rounded-md ${showAgentPanel ? 'bg-violet-500/20 text-violet-300' : 'text-slate-400 hover:bg-slate-800'}`}
          title="Mostrar/ocultar panel del Agente"
        >
          <Bot className="w-4 h-4" />
        </button>
      </div>
    </header>
  );
};
