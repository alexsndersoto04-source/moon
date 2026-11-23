import React from 'react';
import { X, GitCompare, FileCode2 } from 'lucide-react';
import { useIdeStore } from '../../store/useIdeStore';

export const EditorTabs: React.FC = () => {
  const { openTabs, activeFile, openFile, closeTab, setDiffModalTab } = useIdeStore();

  if (openTabs.length === 0) return null;

  return (
    <div className="flex items-center bg-[#090d14] border-b border-slate-800/80 overflow-x-auto h-9 shrink-0">
      {openTabs.map((tab) => {
        const isActive = tab.path === activeFile;
        const fileName = tab.path.split('/').pop() || tab.path;
        return (
          <div
            key={tab.path}
            onClick={() => openFile(tab.path)}
            className={`group flex items-center gap-2 px-3 h-full text-xs cursor-pointer border-r border-slate-800/70 shrink-0 transition-colors ${
              isActive
                ? 'bg-[#0d121c] text-sky-300 border-t-2 border-t-sky-400'
                : 'text-slate-400 hover:bg-[#0d121c]/60 hover:text-slate-200 border-t-2 border-t-transparent'
            }`}
            title={tab.path}
          >
            <FileCode2 className="w-3.5 h-3.5 text-sky-400/80 shrink-0" />
            <span className="truncate max-w-[160px] font-medium">{fileName}</span>
            {tab.dirty && <span className="w-2 h-2 rounded-full bg-amber-400 shrink-0" title="Cambios sin guardar" />}
            {tab.previousAgentContent !== null && tab.previousAgentContent !== undefined && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  setDiffModalTab(tab);
                }}
                title="Ver Diff de cambios del Agente"
                className="p-0.5 rounded hover:bg-indigo-500/20 text-indigo-400"
              >
                <GitCompare className="w-3.5 h-3.5" />
              </button>
            )}
            <button
              onClick={(e) => {
                e.stopPropagation();
                closeTab(tab.path);
              }}
              className="p-0.5 rounded opacity-60 group-hover:opacity-100 hover:bg-slate-700/70 text-slate-400 hover:text-white"
              title="Cerrar pestaña"
            >
              <X className="w-3 h-3" />
            </button>
          </div>
        );
      })}
    </div>
  );
};
