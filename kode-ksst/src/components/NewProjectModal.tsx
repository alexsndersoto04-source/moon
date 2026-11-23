import React, { useState } from 'react';
import { FolderPlus, X } from 'lucide-react';
import { useIdeStore } from '../store/useIdeStore';

export const NewProjectModal: React.FC = () => {
  const { showNewProjectModal, setShowNewProjectModal, createProject, addToast } = useIdeStore();
  const [name, setName] = useState('');
  const [template, setTemplate] = useState('node-ts');
  const [description, setDescription] = useState('');
  const [creating, setCreating] = useState(false);

  if (!showNewProjectModal) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;
    setCreating(true);
    try {
      const id = name
        .trim()
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-|-$/g, '');
      await createProject({ id: id || `project-${Date.now()}`, name: name.trim(), description, template });
      setShowNewProjectModal(false);
      setName('');
      setDescription('');
    } catch (err: any) {
      addToast(err.message, 'error');
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="w-full max-w-md bg-[#0b1019] border border-slate-700/80 rounded-2xl shadow-2xl overflow-hidden">
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-800 bg-[#090d14]">
          <div className="flex items-center gap-2 text-sm font-semibold text-slate-100">
            <FolderPlus className="w-4 h-4 text-sky-400" />
            <span>Nuevo Proyecto en Workspace</span>
          </div>
          <button onClick={() => setShowNewProjectModal(false)} className="p-1 rounded hover:bg-slate-800 text-slate-400">
            <X className="w-4 h-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-5 space-y-4 text-xs">
          <div>
            <label className="block text-slate-400 mb-1">Nombre del Proyecto</label>
            <input
              autoFocus
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Ej: Mi Motor Rust/C++ o API Backend"
              className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-slate-100 focus:outline-none focus:border-sky-500"
            />
          </div>

          <div>
            <label className="block text-slate-400 mb-1">Plantilla / Lenguaje</label>
            <select
              value={template}
              onChange={(e) => setTemplate(e.target.value)}
              className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-slate-100 focus:outline-none focus:border-sky-500"
            >
              <option value="node-ts">TypeScript + Node.js (esbuild)</option>
              <option value="python-app">Python 3 + Unittest</option>
              <option value="cpp-project">C++20 (G++)</option>
              <option value="c-project">C17 (GCC)</option>
              <option value="web-app">Web Studio (HTML5 + CSS + JS)</option>
              <option value="sql-lab">SQLite SQL Lab</option>
              <option value="empty">Proyecto Vacío</option>
            </select>
          </div>

          <div>
            <label className="block text-slate-400 mb-1">Descripción (opcional)</label>
            <input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Descripción breve..."
              className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-slate-100 focus:outline-none focus:border-sky-500"
            />
          </div>

          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={() => setShowNewProjectModal(false)}
              className="px-3 py-2 rounded-lg hover:bg-slate-800 text-slate-400"
            >
              Cancelar
            </button>
            <button
              type="submit"
              disabled={creating || !name.trim()}
              className="px-4 py-2 rounded-lg bg-sky-600 hover:bg-sky-500 text-white font-semibold"
            >
              {creating ? 'Creando...' : 'Crear Proyecto'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
