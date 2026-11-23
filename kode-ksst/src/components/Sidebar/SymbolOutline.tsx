import React from 'react';
import { Braces } from 'lucide-react';
import { useIdeStore } from '../../store/useIdeStore';

export const SymbolOutline: React.FC = () => {
  const { openTabs, activeFile, openFile } = useIdeStore();
  const activeTab = openTabs.find((t) => t.path === activeFile);

  return (
    <div className="flex flex-col h-full">
      <div className="px-3 py-2 border-b border-slate-800/80 text-[11px] uppercase tracking-wider text-slate-400 font-semibold flex items-center gap-1.5">
        <Braces className="w-3.5 h-3.5 text-violet-400" />
        <span>Outline de Símbolos</span>
      </div>

      <div className="flex-1 overflow-y-auto p-2 space-y-1 text-xs">
        {!activeTab && <div className="text-slate-500 p-3 text-center">Abre un archivo para ver sus símbolos.</div>}
        {activeTab && activeTab.symbols.length === 0 && (
          <div className="text-slate-500 p-3 text-center">Sin símbolos detectados en {activeTab.path}.</div>
        )}
        {activeTab?.symbols.map((sym, i) => (
          <div
            key={i}
            onClick={() => openFile(activeTab.path, sym.line, 1)}
            className="flex items-center justify-between px-2 py-1.5 rounded hover:bg-slate-800/70 cursor-pointer"
          >
            <div className="flex items-center gap-2 truncate">
              <span className="px-1.5 py-0.5 rounded bg-violet-500/15 text-violet-300 text-[10px] font-mono uppercase">
                {sym.kind.slice(0, 3)}
              </span>
              <span className="text-slate-200 truncate font-mono">{sym.name}</span>
            </div>
            <span className="text-[11px] text-slate-500 font-mono">:{sym.line}</span>
          </div>
        ))}
      </div>
    </div>
  );
};
