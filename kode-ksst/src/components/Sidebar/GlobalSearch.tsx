import React, { useState } from 'react';
import { Search } from 'lucide-react';
import { useIdeStore } from '../../store/useIdeStore';
import { SearchMatch } from '../../types/ide';

export const GlobalSearch: React.FC = () => {
  const { activeProjectId, openFile } = useIdeStore();
  const [query, setQuery] = useState('');
  const [isRegex, setIsRegex] = useState(false);
  const [matches, setMatches] = useState<SearchMatch[]>([]);
  const [loading, setLoading] = useState(false);

  const runSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!query.trim() || !activeProjectId) return;
    setLoading(true);
    try {
      const res = await fetch(
        `/api/projects/${encodeURIComponent(activeProjectId)}/search?q=${encodeURIComponent(query)}&regex=${isRegex}`
      ).then((r) => r.json());
      setMatches(res.matches || []);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex flex-col h-full">
      <div className="px-3 py-2 border-b border-slate-800/80 text-[11px] uppercase tracking-wider text-slate-400 font-semibold">
        Búsqueda Global
      </div>
      <form onSubmit={runSearch} className="p-2.5 border-b border-slate-800/70 space-y-2">
        <div className="flex items-center gap-1.5">
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Buscar texto o regex..."
            className="flex-1 bg-slate-900 border border-slate-700 rounded px-2.5 py-1.5 text-xs text-slate-200 focus:outline-none focus:border-sky-500"
          />
          <button
            type="button"
            onClick={() => setIsRegex((v) => !v)}
            className={`px-2 py-1.5 rounded text-xs font-mono border ${
              isRegex ? 'bg-sky-500/20 border-sky-500/40 text-sky-300' : 'border-slate-700 text-slate-400'
            }`}
            title="Usar Expresión Regular"
          >
            .*
          </button>
        </div>
        <button
          type="submit"
          disabled={loading}
          className="w-full flex items-center justify-center gap-1.5 py-1.5 rounded bg-sky-500/20 hover:bg-sky-500/30 text-sky-300 text-xs font-medium"
        >
          <Search className="w-3.5 h-3.5" />
          <span>{loading ? 'Buscando...' : 'Buscar en proyecto'}</span>
        </button>
      </form>

      <div className="flex-1 overflow-y-auto p-1.5 space-y-1 text-xs">
        {matches.map((m, idx) => (
          <div
            key={idx}
            onClick={() => openFile(m.file, m.line, m.column)}
            className="p-2 rounded hover:bg-slate-800/70 cursor-pointer border border-transparent hover:border-slate-700/70"
          >
            <div className="text-sky-300 font-mono text-[11px] truncate">
              {m.file}:{m.line}
            </div>
            <div className="text-slate-300 font-mono text-[11px] truncate mt-0.5">{m.preview}</div>
          </div>
        ))}
        {matches.length === 0 && query && !loading && (
          <div className="text-slate-500 text-center py-6">Sin coincidencias</div>
        )}
      </div>
    </div>
  );
};
