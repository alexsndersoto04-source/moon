import React, { useEffect } from 'react';
import { AlertTriangle, Bot, CheckCircle2, Code2, Files, Terminal, X } from 'lucide-react';
import { useIdeStore } from './store/useIdeStore';
import { TopBar } from './components/TopBar';
import { ActivityBar } from './components/ActivityBar';
import { FileExplorer } from './components/Sidebar/FileExplorer';
import { GlobalSearch } from './components/Sidebar/GlobalSearch';
import { GitPanel } from './components/Sidebar/GitPanel';
import { RunConfigsPanel } from './components/Sidebar/RunConfigsPanel';
import { SymbolOutline } from './components/Sidebar/SymbolOutline';
import { EditorTabs } from './components/Editor/EditorTabs';
import { MonacoWorkspace } from './components/Editor/MonacoWorkspace';
import { DiffModal } from './components/Editor/DiffModal';
import { BottomPanel } from './components/BottomPanel/BottomPanel';
import { AgentPanel } from './components/Agent/AgentPanel';
import { BrainConfigModal } from './components/Agent/BrainConfigModal';
import { CommandPalette } from './components/CommandPalette';
import { NewProjectModal } from './components/NewProjectModal';
import { InstallAppModal } from './components/InstallAppModal';

export const App: React.FC = () => {
  const {
    initIde,
    showSidebar,
    sidebarTab,
    cursor,
    openTabs,
    activeFile,
    diagnostics,
    activeProjectId,
    mobileView,
    setMobileView,
    toasts,
    removeToast,
    setBottomTab,
    agentRunning,
    isRunning,
  } = useIdeStore();

  useEffect(() => {
    initIde();
  }, []);

  const activeTab = openTabs.find((t) => t.path === activeFile);

  const renderSidebarContent = () => (
    <>
      {sidebarTab === 'explorer' && <FileExplorer />}
      {sidebarTab === 'search' && <GlobalSearch />}
      {sidebarTab === 'git' && <GitPanel />}
      {sidebarTab === 'runner' && <RunConfigsPanel />}
      {sidebarTab === 'outline' && <SymbolOutline />}
    </>
  );

  return (
    <div className="h-[100dvh] w-screen flex flex-col bg-[#0b0f17] text-slate-100 overflow-hidden">
      <TopBar />

      {/* Desktop Multi-Pane IDE Layout */}
      <div className="hidden md:flex flex-1 min-h-0 overflow-hidden">
        <ActivityBar />

        {showSidebar && (
          <aside className="w-64 bg-[#090d14] border-r border-slate-800/80 flex flex-col shrink-0 overflow-hidden">
            {renderSidebarContent()}
          </aside>
        )}

        <main className="flex-1 flex flex-col min-w-0 min-h-0 overflow-hidden">
          <EditorTabs />
          <MonacoWorkspace />
          <BottomPanel />
        </main>

        <AgentPanel />
      </div>

      {/* Mobile Touch-First Single-Pane IDE Layout */}
      <div className="flex md:hidden flex-1 min-h-0 overflow-hidden">
        {mobileView === 'files' && (
          <div className="flex flex-1 min-w-0 min-h-0 overflow-hidden">
            <ActivityBar />
            <aside className="flex-1 bg-[#090d14] flex flex-col min-w-0 overflow-hidden">
              {renderSidebarContent()}
            </aside>
          </div>
        )}

        {mobileView === 'editor' && (
          <main className="flex-1 flex flex-col min-w-0 min-h-0 overflow-hidden">
            <EditorTabs />
            <MonacoWorkspace />
          </main>
        )}

        {mobileView === 'console' && (
          <div className="flex-1 flex flex-col min-w-0 min-h-0 overflow-hidden">
            <BottomPanel fullHeight />
          </div>
        )}

        {mobileView === 'agent' && (
          <div className="flex-1 flex flex-col min-w-0 min-h-0 overflow-hidden">
            <AgentPanel mobileFull />
          </div>
        )}
      </div>

      {/* Mobile Bottom Navigation Bar */}
      <nav className="flex md:hidden items-center justify-around bg-[#070a10] border-t border-slate-800/90 h-13 px-1 shrink-0">
        <button
          onClick={() => setMobileView('files')}
          className={`flex flex-col items-center justify-center gap-0.5 px-3 py-1 rounded-lg text-[11px] ${
            mobileView === 'files' ? 'text-sky-400 bg-sky-500/10' : 'text-slate-400'
          }`}
        >
          <Files className="w-4 h-4" />
          <span>Archivos</span>
        </button>

        <button
          onClick={() => setMobileView('editor')}
          className={`flex flex-col items-center justify-center gap-0.5 px-3 py-1 rounded-lg text-[11px] ${
            mobileView === 'editor' ? 'text-sky-400 bg-sky-500/10' : 'text-slate-400'
          }`}
        >
          <Code2 className="w-4 h-4" />
          <span>Editor</span>
        </button>

        <button
          onClick={() => setMobileView('console')}
          className={`relative flex flex-col items-center justify-center gap-0.5 px-3 py-1 rounded-lg text-[11px] ${
            mobileView === 'console' ? 'text-sky-400 bg-sky-500/10' : 'text-slate-400'
          }`}
        >
          <Terminal className="w-4 h-4" />
          <span>Consola</span>
          {(isRunning || diagnostics.length > 0) && (
            <span className={`absolute top-1 right-2 w-2 h-2 rounded-full ${isRunning ? 'bg-amber-400 animate-pulse' : 'bg-rose-400'}`} />
          )}
        </button>

        <button
          onClick={() => setMobileView('agent')}
          className={`relative flex flex-col items-center justify-center gap-0.5 px-3 py-1 rounded-lg text-[11px] ${
            mobileView === 'agent' ? 'text-violet-300 bg-violet-500/15' : 'text-slate-400'
          }`}
        >
          <Bot className="w-4 h-4" />
          <span>Agente IA</span>
          {agentRunning && <span className="absolute top-1 right-2 w-2 h-2 rounded-full bg-violet-400 animate-pulse" />}
        </button>
      </nav>

      {/* Desktop Status Bar */}
      <footer className="hidden md:flex h-6 bg-[#070a10] border-t border-slate-800/90 px-3 items-center justify-between text-[11px] text-slate-400 shrink-0">
        <div className="flex items-center gap-3">
          <span className="text-sky-400 font-medium">workspace: {activeProjectId || 'loading...'}</span>
          <button
            onClick={() => setBottomTab('problems')}
            className="flex items-center gap-1 hover:text-slate-200"
          >
            {diagnostics.length > 0 ? (
              <>
                <AlertTriangle className="w-3 h-3 text-rose-400" />
                <span className="text-rose-300">{diagnostics.length} problema(s)</span>
              </>
            ) : (
              <>
                <CheckCircle2 className="w-3 h-3 text-emerald-400" />
                <span>0 errores</span>
              </>
            )}
          </button>
        </div>

        <div className="flex items-center gap-4 font-mono">
          {activeTab && (
            <>
              <span>
                Ln {cursor.line}, Col {cursor.column}
              </span>
              <span className="uppercase text-sky-300">{activeTab.language}</span>
              <span>UTF-8</span>
            </>
          )}
        </div>
      </footer>

      {/* Modals & Palette */}
      <BrainConfigModal />
      <NewProjectModal />
      <InstallAppModal />
      <CommandPalette />
      <DiffModal />

      {/* Toast Notifications */}
      {toasts.length > 0 && (
        <div className="fixed bottom-16 md:bottom-9 right-4 z-50 space-y-2 max-w-sm">
          {toasts.map((t) => (
            <div
              key={t.id}
              className={`flex items-center justify-between gap-3 px-3.5 py-2.5 rounded-xl border shadow-xl text-xs backdrop-blur-md ${
                t.kind === 'error'
                  ? 'bg-rose-950/90 border-rose-500/40 text-rose-200'
                  : t.kind === 'success'
                    ? 'bg-emerald-950/90 border-emerald-500/40 text-emerald-200'
                    : 'bg-slate-900/95 border-sky-500/40 text-slate-100'
              }`}
            >
              <span>{t.message}</span>
              <button onClick={() => removeToast(t.id)} className="opacity-60 hover:opacity-100">
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default App;
