import React, { useState } from 'react';
import { Cpu, Play, History, RotateCcw, Plus } from 'lucide-react';
import { useIdeStore } from '../../store/useIdeStore';

export const RunConfigsPanel: React.FC = () => {
  const { runtimes, checkpoints, runSnippet, createCheckpoint, rollbackCheckpoint } = useIdeStore();
  const [lang, setLang] = useState('python');
  const [code, setCode] = useState('print("Hola desde KODE KSST Multi-Language Runner!")');

  const handleSaveCheckpoint = () => {
    const label = window.prompt('Nombre del Checkpoint:', `Snapshot ${new Date().toLocaleTimeString()}`);
    if (label) createCheckpoint(label);
  };

  return (
    <div className="flex flex-col h-full">
      <div className="px-3 py-2 border-b border-slate-800/80 text-[11px] uppercase tracking-wider text-slate-400 font-semibold flex items-center gap-1.5">
        <Cpu className="w-3.5 h-3.5 text-emerald-400" />
        <span>Runtimes &amp; Herramientas</span>
      </div>

      <div className="flex-1 overflow-y-auto p-2.5 space-y-4 text-xs">
        {/* Scratchpad Runner */}
        <div className="p-2.5 rounded-lg bg-slate-900/70 border border-slate-800 space-y-2">
          <div className="font-semibold text-slate-200 flex items-center justify-between">
            <span>Scratchpad Multi-Lenguaje</span>
            <select
              value={lang}
              onChange={(e) => setLang(e.target.value)}
              className="bg-slate-950 border border-slate-700 rounded px-1.5 py-0.5 text-[11px] text-sky-300"
            >
              <option value="python">Python 3</option>
              <option value="typescript">TypeScript</option>
              <option value="javascript">JavaScript</option>
              <option value="cpp">C++20</option>
              <option value="c">C17</option>
              <option value="sql">SQLite SQL</option>
              <option value="shell">Bash</option>
            </select>
          </div>
          <textarea
            value={code}
            onChange={(e) => setCode(e.target.value)}
            rows={4}
            className="w-full bg-slate-950 border border-slate-800 rounded p-2 font-mono text-[11px] text-slate-200 focus:outline-none focus:border-sky-500"
          />
          <button
            onClick={() => runSnippet(lang, code)}
            className="w-full flex items-center justify-center gap-1.5 py-1.5 rounded bg-emerald-500/20 hover:bg-emerald-500/30 text-emerald-300 font-medium"
          >
            <Play className="w-3.5 h-3.5" />
            <span>Ejecutar Snippet</span>
          </button>
        </div>

        {/* Checkpoints */}
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="font-semibold text-slate-300 flex items-center gap-1.5">
              <History className="w-3.5 h-3.5 text-amber-400" />
              Checkpoints
            </span>
            <button
              onClick={handleSaveCheckpoint}
              className="flex items-center gap-1 px-2 py-0.5 rounded bg-amber-500/15 hover:bg-amber-500/25 text-amber-300 text-[11px]"
            >
              <Plus className="w-3 h-3" />
              <span>Crear</span>
            </button>
          </div>
          {checkpoints.length === 0 ? (
            <div className="text-slate-500 text-[11px]">Sin snapshots aún.</div>
          ) : (
            <div className="space-y-1">
              {checkpoints.map((cp) => (
                <div
                  key={cp.id}
                  className="flex items-center justify-between p-2 rounded bg-slate-900/60 border border-slate-800/80"
                >
                  <div className="truncate pr-2">
                    <div className="text-slate-200 truncate">{cp.label}</div>
                    <div className="text-[10px] text-slate-500">{cp.fileCount} archivos</div>
                  </div>
                  <button
                    onClick={() => {
                      if (window.confirm(`¿Restaurar el proyecto al checkpoint "${cp.label}"?`)) {
                        rollbackCheckpoint(cp.id);
                      }
                    }}
                    className="p-1 rounded hover:bg-amber-500/20 text-amber-300 shrink-0"
                    title="Restaurar snapshot"
                  >
                    <RotateCcw className="w-3.5 h-3.5" />
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Detected Runtimes */}
        <div className="space-y-1.5">
          <div className="font-semibold text-slate-300">Motores Detectados</div>
          {runtimes.map((rt) => (
            <div
              key={rt.id}
              className="p-2 rounded bg-slate-900/50 border border-slate-800/70 flex items-center justify-between gap-2"
            >
              <div className="min-w-0">
                <div className="text-slate-200 truncate">{rt.label}</div>
                {rt.version && <div className="text-[10px] text-slate-500 font-mono truncate">{rt.version}</div>}
              </div>
              <span
                className={`px-1.5 py-0.5 rounded text-[10px] font-semibold shrink-0 ${
                  rt.available ? 'bg-emerald-500/15 text-emerald-300' : 'bg-slate-800 text-slate-500'
                }`}
              >
                {rt.available ? 'OK' : 'OFF'}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
