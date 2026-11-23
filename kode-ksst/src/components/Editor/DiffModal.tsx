import React, { useEffect, useRef } from 'react';
import type * as MonacoNS from 'monaco-editor';
import { X, GitCompare } from 'lucide-react';
import { useIdeStore } from '../../store/useIdeStore';
import { loadMonaco } from '../../lib/monacoLoader';

export const DiffModal: React.FC = () => {
  const { diffModalTab, setDiffModalTab } = useIdeStore();
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!diffModalTab || !containerRef.current) return;
    let diffEditor: MonacoNS.editor.IStandaloneDiffEditor | null = null;
    let originalModel: MonacoNS.editor.ITextModel | null = null;
    let modifiedModel: MonacoNS.editor.ITextModel | null = null;
    let disposed = false;

    loadMonaco().then((monaco) => {
      if (disposed || !containerRef.current) return;
      diffEditor = monaco.editor.createDiffEditor(containerRef.current, {
        theme: 'nexus-obsidian',
        automaticLayout: true,
        readOnly: true,
        renderSideBySide: window.innerWidth >= 768,
        fontSize: 13,
        fontFamily: '"JetBrains Mono", "Fira Code", monospace',
      });

      originalModel = monaco.editor.createModel(diffModalTab.previousAgentContent || '', diffModalTab.language);
      modifiedModel = monaco.editor.createModel(diffModalTab.content, diffModalTab.language);
      diffEditor.setModel({ original: originalModel, modified: modifiedModel });
    });

    return () => {
      disposed = true;
      diffEditor?.dispose();
      originalModel?.dispose();
      modifiedModel?.dispose();
    };
  }, [diffModalTab]);

  if (!diffModalTab) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4 sm:p-6">
      <div className="w-full max-w-6xl h-[82vh] bg-[#0b1019] border border-slate-700/80 rounded-xl shadow-2xl flex flex-col overflow-hidden">
        <div className="flex items-center justify-between px-4 py-3 border-b border-slate-800 bg-[#090d14]">
          <div className="flex items-center gap-2 text-sm font-semibold text-indigo-300 truncate">
            <GitCompare className="w-4 h-4 shrink-0" />
            <span className="truncate">Agent Diff Viewer — {diffModalTab.path}</span>
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
