import React, { useState } from 'react';
import {
  ChevronDown,
  ChevronRight,
  FilePlus,
  FolderPlus,
  RefreshCw,
  Trash2,
  Folder,
  FileCode2,
  Play,
  Edit3,
} from 'lucide-react';
import { useIdeStore } from '../../store/useIdeStore';
import { FileNode } from '../../types/ide';

const TreeNode: React.FC<{ node: FileNode; depth: number }> = ({ node, depth }) => {
  const { activeFile, openFile, deletePath, renamePath, runSpecificFile } = useIdeStore();
  const [expanded, setExpanded] = useState(true);

  const isSelected = node.path === activeFile;

  const handleRename = (e: React.MouseEvent) => {
    e.stopPropagation();
    const next = window.prompt('Nueva ruta o nombre:', node.path);
    if (next && next !== node.path) {
      renamePath(node.path, next);
    }
  };

  const handleDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (window.confirm(`¿Eliminar "${node.path}"?`)) {
      deletePath(node.path);
    }
  };

  if (node.type === 'directory') {
    return (
      <div>
        <div
          onClick={() => setExpanded((v) => !v)}
          style={{ paddingLeft: `${depth * 12 + 8}px` }}
          className="group flex items-center justify-between pr-2 py-1 text-xs text-slate-300 hover:bg-slate-800/60 cursor-pointer rounded"
        >
          <div className="flex items-center gap-1.5 truncate">
            {expanded ? <ChevronDown className="w-3.5 h-3.5 text-slate-500" /> : <ChevronRight className="w-3.5 h-3.5 text-slate-500" />}
            <Folder className="w-3.5 h-3.5 text-sky-400/80 shrink-0" />
            <span className="truncate font-medium">{node.name}</span>
          </div>
          <button
            onClick={handleDelete}
            className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-rose-500/20 text-slate-400 hover:text-rose-300"
            title="Eliminar carpeta"
          >
            <Trash2 className="w-3 h-3" />
          </button>
        </div>
        {expanded && node.children?.map((child) => <TreeNode key={child.path} node={child} depth={depth + 1} />)}
      </div>
    );
  }

  return (
    <div
      onClick={() => openFile(node.path)}
      style={{ paddingLeft: `${depth * 12 + 22}px` }}
      className={`group flex items-center justify-between pr-2 py-1 text-xs cursor-pointer rounded transition ${
        isSelected ? 'bg-sky-500/15 text-sky-300 font-medium' : 'text-slate-300 hover:bg-slate-800/60'
      }`}
    >
      <div className="flex items-center gap-1.5 truncate">
        <FileCode2 className="w-3.5 h-3.5 text-slate-400 shrink-0" />
        <span className="truncate">{node.name}</span>
      </div>
      <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 shrink-0">
        <button
          onClick={(e) => {
            e.stopPropagation();
            runSpecificFile(node.path);
          }}
          className="p-0.5 rounded hover:bg-emerald-500/20 text-emerald-400"
          title="Ejecutar archivo"
        >
          <Play className="w-3 h-3" />
        </button>
        <button
          onClick={handleRename}
          className="p-0.5 rounded hover:bg-slate-700 text-slate-400 hover:text-white"
          title="Renombrar"
        >
          <Edit3 className="w-3 h-3" />
        </button>
        <button
          onClick={handleDelete}
          className="p-0.5 rounded hover:bg-rose-500/20 text-slate-400 hover:text-rose-300"
          title="Eliminar archivo"
        >
          <Trash2 className="w-3 h-3" />
        </button>
      </div>
    </div>
  );
};

export const FileExplorer: React.FC = () => {
  const { fileTree, activeProjectId, projects, refreshFileTree, createFile, createFolder } = useIdeStore();
  const activeProj = projects.find((p) => p.id === activeProjectId);

  const handleNewFile = () => {
    const name = window.prompt('Ruta del nuevo archivo (ej: src/utils.ts o script.py):');
    if (name) createFile(name, '');
  };

  const handleNewFolder = () => {
    const name = window.prompt('Ruta de la nueva carpeta:');
    if (name) createFolder(name);
  };

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-center justify-between px-3 py-2 border-b border-slate-800/80">
        <div className="truncate">
          <div className="text-[11px] uppercase tracking-wider text-slate-400 font-semibold">Explorador</div>
          <div className="text-xs text-sky-300 font-medium truncate">{activeProj?.name || activeProjectId}</div>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <button onClick={handleNewFile} className="p-1 rounded hover:bg-slate-800 text-slate-300" title="Nuevo archivo">
            <FilePlus className="w-3.5 h-3.5" />
          </button>
          <button onClick={handleNewFolder} className="p-1 rounded hover:bg-slate-800 text-slate-300" title="Nueva carpeta">
            <FolderPlus className="w-3.5 h-3.5" />
          </button>
          <button onClick={refreshFileTree} className="p-1 rounded hover:bg-slate-800 text-slate-300" title="Refrescar árbol">
            <RefreshCw className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-1.5 space-y-0.5">
        {fileTree.map((node) => (
          <TreeNode key={node.path} node={node} depth={0} />
        ))}
        {fileTree.length === 0 && (
          <div className="text-xs text-slate-500 p-4 text-center">Proyecto vacío. Crea un archivo o pídele al Agente que genere la arquitectura.</div>
        )}
      </div>
    </div>
  );
};
