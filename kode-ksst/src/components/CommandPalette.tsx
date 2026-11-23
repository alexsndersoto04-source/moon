import React, { useEffect, useState } from 'react';
import { Command, Play, Brain, FolderPlus, Terminal, Search, ShieldCheck, Save } from 'lucide-react';
import { useIdeStore } from '../store/useIdeStore';

export const CommandPalette: React.FC = () => {
  const {
    showCommandPalette,
    setShowCommandPalette,
    runCurrentFile,
    saveAllDirty,
    runDiagnostics,
    setShowBrainModal,
    setShowNewProjectModal,
    setBottomTab,
    setSidebarTab,
    createCheckpoint,
  } = useIdeStore();

  const [query, setQuery] = useState('');

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && (e.key.toLowerCase() === 'k' || (e.shiftKey && e.key.toLowerCase() === 'p'))) {
        e.preventDefault();
        setShowCommandPalette(!showCommandPalette);
      } else if (e.key === 'Escape' && showCommandPalette) {
        setShowCommandPalette(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [showCommandPalette]);

  if (!showCommandPalette) return null;

  const commands = [
    { id: 'run', label: 'Ejecutar archivo actual (Run)', icon: <Play className="w-4 h-4 text-emerald-400" />, action: () => runCurrentFile() },
    { id: 'save', label: 'Guardar todos los archivos abiertos', icon: <Save className="w-4 h-4 text-amber-400" />, action: () => saveAllDirty() },
    { id: 'diag', label: 'Verificar sintaxis y problemas del proyecto', icon: <ShieldCheck className="w-4 h-4 text-emerald-400" />, action: () => runDiagnostics() },
    { id: 'brain', label: 'Configurar / Conectar Cerebro API del Agente', icon: <Brain className="w-4 h-4 text-violet-400" />, action: () => setShowBrainModal(true) },
    { id: 'newproj', label: 'Crear nuevo proyecto multi-archivo', icon: <FolderPlus className="w-4 h-4 text-sky-400" />, action: () => setShowNewProjectModal(true) },
    { id: 'term', label: 'Abrir Terminal Interactiva PTY', icon: <Terminal className="w-4 h-4 text-sky-400" />, action: () => setBottomTab('terminal') },
    { id: 'search', label: 'Abrir Búsqueda Global', icon: <Search className="w-4 h-4 text-sky-400" />, action: () => setSidebarTab('search') },
    {
      id: 'cp',
      label: 'Crear Checkpoint de seguridad del proyecto',
      icon: <Command className="w-4 h-4 text-amber-400" />,
      action: () => createCheckpoint(`Manual ${new Date().toLocaleTimeString()}`),
    },
  ];

  const filtered = commands.filter((c) => c.label.toLowerCase().includes(query.toLowerCase()));

  return (
    <div
      onClick={() => setShowCommandPalette(false)}
      className="fixed inset-0 z-50 bg-black/65 backdrop-blur-xs flex items-start justify-center pt-20 p-4"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-lg bg-[#0b1019] border border-slate-700/80 rounded-xl shadow-2xl overflow-hidden"
      >
        <div className="flex items-center gap-2 px-3.5 py-3 border-b border-slate-800">
          <Command className="w-4 h-4 text-sky-400" />
          <input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Escribe un comando del IDE..."
            className="flex-1 bg-transparent text-sm text-slate-100 placeholder-slate-500 focus:outline-none"
          />
        </div>
        <div className="max-h-72 overflow-y-auto p-1.5 space-y-0.5">
          {filtered.map((cmd) => (
            <button
              key={cmd.id}
              onClick={() => {
                setShowCommandPalette(false);
                setQuery('');
                cmd.action();
              }}
              className="w-full flex items-center gap-2.5 px-3 py-2 rounded-lg hover:bg-slate-800/80 text-left text-xs text-slate-200"
            >
              {cmd.icon}
              <span>{cmd.label}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
};
