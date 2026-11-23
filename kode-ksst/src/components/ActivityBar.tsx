import React from 'react';
import { Files, Search, GitBranch, Cpu, Braces, Bot } from 'lucide-react';
import { useIdeStore } from '../store/useIdeStore';
import { SidebarTab } from '../types/ide';

export const ActivityBar: React.FC = () => {
  const { sidebarTab, showSidebar, showAgentPanel, setSidebarTab, setShowSidebar, setShowAgentPanel } = useIdeStore();

  const items: Array<{ id: SidebarTab; icon: React.ReactNode; label: string }> = [
    { id: 'explorer', icon: <Files className="w-5 h-5" />, label: 'Explorador de Archivos' },
    { id: 'search', icon: <Search className="w-5 h-5" />, label: 'Búsqueda Global' },
    { id: 'git', icon: <GitBranch className="w-5 h-5" />, label: 'Control Git' },
    { id: 'runner', icon: <Cpu className="w-5 h-5" />, label: 'Runtimes, Scratchpad & Checkpoints' },
    { id: 'outline', icon: <Braces className="w-5 h-5" />, label: 'Outline de Símbolos' },
  ];

  return (
    <div className="w-12 bg-[#070a10] border-r border-slate-800/80 flex flex-col items-center justify-between py-2 shrink-0">
      <div className="flex flex-col items-center gap-1">
        {items.map((item) => {
          const active = showSidebar && sidebarTab === item.id;
          return (
            <button
              key={item.id}
              onClick={() => {
                if (active) setShowSidebar(false);
                else setSidebarTab(item.id);
              }}
              title={item.label}
              className={`w-9 h-9 rounded-lg flex items-center justify-center transition ${
                active
                  ? 'bg-sky-500/20 text-sky-400 border border-sky-500/30'
                  : 'text-slate-500 hover:text-slate-200 hover:bg-slate-800/60'
              }`}
            >
              {item.icon}
            </button>
          );
        })}
      </div>

      <button
        onClick={() => setShowAgentPanel(!showAgentPanel)}
        title="Mostrar / Ocultar Agente Autónomo"
        className={`w-9 h-9 rounded-lg flex items-center justify-center transition ${
          showAgentPanel
            ? 'bg-violet-500/20 text-violet-400 border border-violet-500/40'
            : 'text-slate-500 hover:text-slate-200 hover:bg-slate-800/60'
        }`}
      >
        <Bot className="w-5 h-5" />
      </button>
    </div>
  );
};
