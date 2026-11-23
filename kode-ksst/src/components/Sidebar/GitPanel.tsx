import React, { useEffect, useState } from 'react';
import { GitBranch, GitCommit, RefreshCw } from 'lucide-react';
import { useIdeStore } from '../../store/useIdeStore';

export const GitPanel: React.FC = () => {
  const { activeProjectId, addToast, refreshProjects } = useIdeStore();
  const [statusOut, setStatusOut] = useState('');
  const [logOut, setLogOut] = useState('');
  const [commitMsg, setCommitMsg] = useState('');
  const [loading, setLoading] = useState(false);

  const runGit = async (action: string, message?: string) => {
    if (!activeProjectId) return null;
    setLoading(true);
    try {
      const res = await fetch(`/api/projects/${encodeURIComponent(activeProjectId)}/git`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action, message }),
      }).then((r) => r.json());
      return res;
    } finally {
      setLoading(false);
    }
  };

  const refreshGit = async () => {
    const st = await runGit('status');
    const lg = await runGit('log');
    if (st) setStatusOut(st.stdout || st.stderr || '(sin repo git)');
    if (lg) setLogOut(lg.stdout || '');
  };

  useEffect(() => {
    refreshGit();
  }, [activeProjectId]);

  const handleInit = async () => {
    await runGit('init');
    await refreshProjects();
    await refreshGit();
    addToast('Repositorio Git inicializado en el proyecto', 'success');
  };

  const handleCommit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!commitMsg.trim()) return;
    const res = await runGit('commit', commitMsg);
    if (res?.exitCode === 0) {
      addToast('Commit realizado', 'success');
      setCommitMsg('');
    } else {
      addToast(res?.stderr || 'Error en commit', 'error');
    }
    await refreshGit();
  };

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-center justify-between px-3 py-2 border-b border-slate-800/80">
        <span className="text-[11px] uppercase tracking-wider text-slate-400 font-semibold flex items-center gap-1.5">
          <GitBranch className="w-3.5 h-3.5 text-sky-400" />
          Git del Proyecto
        </span>
        <button onClick={refreshGit} className="p-1 rounded hover:bg-slate-800 text-slate-300" title="Refrescar Git">
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
        </button>
      </div>

      <div className="p-2.5 space-y-3 overflow-y-auto flex-1 text-xs">
        {statusOut.includes('not a git repository') ? (
          <div className="p-3 rounded-lg bg-slate-900/80 border border-slate-800 space-y-2">
            <p className="text-slate-400">Este proyecto aún no tiene Git inicializado.</p>
            <button
              onClick={handleInit}
              className="w-full py-1.5 rounded bg-sky-500/20 hover:bg-sky-500/30 text-sky-300 font-medium"
            >
              Inicializar Git (git init)
            </button>
          </div>
        ) : (
          <>
            <form onSubmit={handleCommit} className="space-y-2">
              <input
                value={commitMsg}
                onChange={(e) => setCommitMsg(e.target.value)}
                placeholder="Mensaje del commit..."
                className="w-full bg-slate-900 border border-slate-700 rounded px-2.5 py-1.5 text-xs text-slate-200 focus:outline-none focus:border-sky-500"
              />
              <button
                type="submit"
                disabled={!commitMsg.trim() || loading}
                className="w-full flex items-center justify-center gap-1.5 py-1.5 rounded bg-emerald-500/20 hover:bg-emerald-500/30 disabled:opacity-40 text-emerald-300 font-medium"
              >
                <GitCommit className="w-3.5 h-3.5" />
                <span>Stage &amp; Commit</span>
              </button>
            </form>

            <div>
              <div className="text-[11px] text-slate-400 font-semibold mb-1">Estado</div>
              <pre className="p-2 rounded bg-slate-950 border border-slate-800 font-mono text-[11px] text-slate-300 whitespace-pre-wrap">
                {statusOut || 'Working tree clean'}
              </pre>
            </div>

            {logOut && (
              <div>
                <div className="text-[11px] text-slate-400 font-semibold mb-1">Commits recientes</div>
                <pre className="p-2 rounded bg-slate-950 border border-slate-800 font-mono text-[11px] text-slate-400 whitespace-pre-wrap">
                  {logOut}
                </pre>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
};
