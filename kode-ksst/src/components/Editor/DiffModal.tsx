import React, { useEffect, useRef } from 'react';
import * as monaco from 'monaco-editor';
import { X, GitCompare } from 'lucide-react';
import { useIdeStore } from '../../store/useIdeStore';

export const DiffModal: React.FC = () => {
  const { diffModalTab, setDiffModalTab } = useIdeStore();
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!diffModalTab || !containerRef.current) return;

    const diffEditor = monaco.editor.createDiffEditor(containerRef.current, {
      theme: 'nexus-obsidian',
      automaticLayout: true,
      readOnly: true,
      renderSideBySide: true,
      fontSize: 13,
      fontFamily: '"JetBrains Mono", "Fira Code", monospace',
    });

    const originalModel = monaco.editor.createModel(diffModalTab.previousAgentContent || '', diffModalTab.language);
    const modifiedModel = monaco.editor.createModel(diffModalTab.content, diffModalTab.language);
    diffEditor.setModel({ original: originalModel, modified: modifiedModel });

    return () => {
      diffEditor.dispose();
      originalModel.dispose();
      modifiedModel.dispose();
    };
  }, [diffModalTab]);

  if (!diffModalTab) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-6">
      <div className="w-full max-w-6xl h-[82vh] bg-[#0b1019] border border-slate-700/80 rounded-xl shadow-2xl flex flex-col overflow-hidden">
        <div className="flex items-center justify-between px-4 py-3 border-b border-slate-800 bg-[#090d14]">
          <div className="flex items-center gap-2 text-sm font-semibold text-indigo-300">
            <GitCompare className="w-4 h-4" />
            <span>Agent Diff Viewer — {diffModalTab.path}</span>
          </div>
          <button
            onClick={() => setDiffModalTab(null)}
            className="p-1.5 rounded-lg hover:bg-slate-800 text-slate-400 hover:text-white"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
        <div ref={containerRef} className="flex-1 min-h-0" />
      </div>
    </div>
  );
};
