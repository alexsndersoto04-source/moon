import React, { useState } from 'react';
import {
  Terminal as TerminalIcon,
  PlayCircle,
  AlertTriangle,
  Globe,
  ChevronDown,
  RotateCw,
  ExternalLink,
  Sparkles,
  CheckCircle2,
  XCircle,
  Send,
} from 'lucide-react';
import { useIdeStore } from '../../store/useIdeStore';
import { WebTerminal } from './WebTerminal';

export const BottomPanel: React.FC<{ fullHeight?: boolean }> = ({ fullHeight }) => {
  const {
    bottomTab,
    showBottomPanel,
    executionHistory,
    isRunning,
    stdinInput,
    diagnostics,
    previewUrl,
    previewNonce,
    activeProjectId,
    setBottomTab,
    setShowBottomPanel,
    setStdinInput,
    runCommand,
    openFile,
    reloadPreview,
    setAgentInputDraft,
    setShowAgentPanel,
    sendAgentPrompt,
  } = useIdeStore();

  const [quickCmd, setQuickCmd] = useState('');
  const [showStdin, setShowStdin] = useState(false);

  if (!fullHeight && !showBottomPanel) return null;

  const latestExec = executionHistory[0];

  const handleQuickCmd = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!quickCmd.trim()) return;
    const cmd = quickCmd;
    setQuickCmd('');
    await runCommand(cmd);
  };

  const askAgentFixError = (errSummary: string) => {
    const prompt = `El último comando o ejecución falló. Diagnostica el error, corrige el código y vuelve a ejecutarlo para verificar:\n\n${errSummary}`;
    setShowAgentPanel(true);
    sendAgentPrompt(prompt);
  };

  return (
    <div className={`${fullHeight ? 'flex-1 h-full' : 'h-64'} border-t border-slate-800/80 bg-[#080c13] flex flex-col shrink-0`}>
      {/* Header Tabs */}
      <div className="flex items-center justify-between px-2 bg-[#0a0f18] border-b border-slate-800/80 h-9 shrink-0">
        <div className="flex items-center gap-1">
          <button
            onClick={() => setBottomTab('output')}
            className={`flex items-center gap-1.5 px-2.5 py-1 rounded text-xs font-medium transition ${
              bottomTab === 'output' ? 'bg-slate-800 text-sky-300' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <PlayCircle className="w-3.5 h-3.5" />
            <span>Output Runner</span>
            {isRunning && <span className="w-2 h-2 rounded-full bg-amber-400 animate-pulse" />}
          </button>

          <button
            onClick={() => setBottomTab('terminal')}
            className={`flex items-center gap-1.5 px-2.5 py-1 rounded text-xs font-medium transition ${
              bottomTab === 'terminal' ? 'bg-slate-800 text-sky-300' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <TerminalIcon className="w-3.5 h-3.5" />
            <span>Terminal PTY</span>
          </button>

          <button
            onClick={() => setBottomTab('problems')}
            className={`flex items-center gap-1.5 px-2.5 py-1 rounded text-xs font-medium transition ${
              bottomTab === 'problems' ? 'bg-slate-800 text-sky-300' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <AlertTriangle className="w-3.5 h-3.5" />
            <span>Problemas</span>
            {diagnostics.length > 0 && (
              <span className="px-1.5 py-0.2 rounded-full bg-rose-500/20 text-rose-300 text-[10px]">
                {diagnostics.length}
              </span>
            )}
          </button>

          <button
            onClick={() => setBottomTab('preview')}
            className={`flex items-center gap-1.5 px-2.5 py-1 rounded text-xs font-medium transition ${
              bottomTab === 'preview' ? 'bg-slate-800 text-sky-300' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Globe className="w-3.5 h-3.5" />
            <span>Web Preview</span>
          </button>
        </div>

        <div className="flex items-center gap-2">
          {bottomTab === 'output' && (
            <button
              onClick={() => setShowStdin((s) => !s)}
              className={`px-2 py-0.5 rounded text-[11px] border transition ${
                showStdin || stdinInput
                  ? 'border-sky-500/40 bg-sky-500/15 text-sky-300'
                  : 'border-slate-700 text-slate-400 hover:text-slate-200'
              }`}
            >
              stdin {stdinInput ? '(activo)' : ''}
            </button>
          )}
          <button
            onClick={() => setShowBottomPanel(false)}
            className="p-1 rounded hover:bg-slate-800 text-slate-400 hover:text-white"
            title="Ocultar panel inferior"
          >
            <ChevronDown className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Tab Content */}
      <div className="flex-1 min-h-0 overflow-hidden">
        {bottomTab === 'output' && (
          <div className="flex flex-col h-full">
            {showStdin && (
              <div className="px-3 py-1.5 bg-[#0c121e] border-b border-slate-800 flex items-center gap-2 shrink-0">
                <span className="text-[11px] text-slate-400 font-mono shrink-0">STDIN:</span>
                <input
                  value={stdinInput}
                  onChange={(e) => setStdinInput(e.target.value)}
                  placeholder="Entrada estándar para el próximo Run..."
                  className="flex-1 bg-slate-900 border border-slate-700 rounded px-2 py-1 text-xs font-mono text-slate-200 focus:outline-none focus:border-sky-500"
                />
              </div>
            )}

            <div className="flex-1 overflow-y-auto p-3 font-mono text-xs space-y-3">
              {!latestExec && !isRunning && (
                <div className="text-slate-500 py-4 text-center">
                  Presiona <span className="text-emerald-400 font-semibold">Run</span> o{' '}
                  <kbd className="px-1.5 py-0.5 rounded bg-slate-800 text-slate-300">Ctrl+Enter</kbd> para ejecutar cualquier archivo (JS, TS, Python, C, C++, SQL, Bash, Make, HTML).
                </div>
              )}

              {executionHistory.map((item, idx) => (
                <div
                  key={idx}
                  className={`rounded-lg border p-2.5 ${
                    item.success
                      ? 'border-emerald-500/20 bg-emerald-950/10'
                      : 'border-rose-500/30 bg-rose-950/15'
                  }`}
                >
                  <div className="flex items-center justify-between gap-2 pb-1.5 border-b border-slate-800/70 mb-2">
                    <div className="flex items-center gap-2 truncate">
                      {item.success ? (
                        <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400 shrink-0" />
                      ) : (
                        <XCircle className="w-3.5 h-3.5 text-rose-400 shrink-0" />
                      )}
                      <span className="text-slate-200 font-semibold truncate">$ {item.command}</span>
                    </div>
                    <div className="flex items-center gap-2 shrink-0 text-[11px] text-slate-400">
                      <span className={item.exitCode === 0 ? 'text-emerald-400' : 'text-rose-400'}>
                        exit {item.exitCode}
                      </span>
                      <span>· {item.durationMs} ms</span>
                      {item.timestamp && <span>· {item.timestamp}</span>}
                      {!item.success && (
                        <button
                          onClick={() =>
                            askAgentFixError(`Command: ${item.command}\nExit: ${item.exitCode}\nStderr:\n${item.stderr}\nStdout:\n${item.stdout}`)
                          }
                          className="flex items-center gap-1 px-2 py-0.5 rounded bg-violet-500/20 hover:bg-violet-500/30 text-violet-300 border border-violet-500/30"
                        >
                          <Sparkles className="w-3 h-3" />
                          <span>Corregir con Agente</span>
                        </button>
                      )}
                    </div>
                  </div>

                  {item.stdout && (
                    <pre className="whitespace-pre-wrap text-slate-200 leading-relaxed">{item.stdout}</pre>
                  )}
                  {item.stderr && (
                    <pre className="whitespace-pre-wrap text-rose-300 mt-1.5 leading-relaxed">{item.stderr}</pre>
                  )}
                  {!item.stdout && !item.stderr && (
                    <span className="text-slate-500 italic">(Sin salida en stdout/stderr)</span>
                  )}
                </div>
              ))}
            </div>

            {/* Quick Shell Command Bar */}
            <form
              onSubmit={handleQuickCmd}
              className="flex items-center gap-2 px-3 py-1.5 border-t border-slate-800/80 bg-[#0a0f18] shrink-0"
            >
              <span className="text-sky-400 font-mono text-xs">$</span>
              <input
                value={quickCmd}
                onChange={(e) => setQuickCmd(e.target.value)}
                placeholder="Ejecutar comando rápido en el proyecto (ej: python3 -m unittest, make, ls -la)..."
                className="flex-1 bg-transparent text-xs font-mono text-slate-200 placeholder-slate-500 focus:outline-none"
              />
              <button
                type="submit"
                disabled={isRunning || !quickCmd.trim()}
                className="flex items-center gap-1 px-2 py-1 rounded bg-sky-500/20 hover:bg-sky-500/30 disabled:opacity-40 text-sky-300 text-xs"
              >
                <Send className="w-3 h-3" />
                <span>Ejecutar</span>
              </button>
            </form>
          </div>
        )}

        {bottomTab === 'terminal' && <WebTerminal />}

        {bottomTab === 'problems' && (
          <div className="h-full overflow-y-auto p-2 space-y-1 text-xs">
            <div className="flex items-center justify-between px-2 pb-1.5 border-b border-slate-800/70">
              <span className="text-slate-400">
                {diagnostics.length === 0
                  ? 'No se detectaron errores de sintaxis o compilación.'
                  : `${diagnostics.length} problema(s) encontrado(s)`}
              </span>
              {diagnostics.length > 0 && (
                <button
                  onClick={() => {
                    setShowAgentPanel(true);
                    setAgentInputDraft(
                      `Corrige todos los errores de diagnóstico actuales en el proyecto:\n${diagnostics
                        .map((d) => `- ${d.file}:${d.line}:${d.column} [${d.source}] ${d.message}`)
                        .join('\n')}`
                    );
                  }}
                  className="flex items-center gap-1 px-2 py-1 rounded bg-violet-500/20 hover:bg-violet-500/30 text-violet-300 border border-violet-500/30"
                >
                  <Sparkles className="w-3 h-3" />
                  <span>Enviar al Agente</span>
                </button>
              )}
            </div>
            {diagnostics.map((d, i) => (
              <div
                key={i}
                onClick={() => openFile(d.file, d.line, d.column)}
                className="flex items-start gap-2 px-2 py-1.5 rounded hover:bg-slate-800/60 cursor-pointer"
              >
                <AlertTriangle
                  className={`w-3.5 h-3.5 mt-0.5 shrink-0 ${
                    d.severity === 'error' ? 'text-rose-400' : 'text-amber-400'
                  }`}
                />
                <div className="flex-1 min-w-0">
                  <div className="text-slate-200 truncate">{d.message}</div>
                  <div className="text-[11px] text-slate-400 font-mono">
                    {d.file}:{d.line}:{d.column} · {d.source}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}

        {bottomTab === 'preview' && (
          <div className="flex flex-col h-full bg-[#0b0f17]">
            <div className="flex items-center gap-2 px-3 py-1 bg-[#0a0f18] border-b border-slate-800/70 text-xs shrink-0">
              <span className="text-slate-400 font-mono truncate flex-1">
                {previewUrl || `/preview/${activeProjectId}/index.html`}
              </span>
              <button
                onClick={reloadPreview}
                className="flex items-center gap-1 px-2 py-0.5 rounded hover:bg-slate-800 text-slate-300"
              >
                <RotateCw className="w-3 h-3" />
                <span>Recargar</span>
              </button>
              <a
                href={previewUrl || `/preview/${activeProjectId}/index.html`}
                target="_blank"
                rel="noreferrer"
                className="flex items-center gap-1 px-2 py-0.5 rounded hover:bg-slate-800 text-sky-300"
              >
                <ExternalLink className="w-3 h-3" />
                <span>Abrir</span>
              </a>
            </div>
            <iframe
              key={`${previewUrl}-${previewNonce}`}
              src={previewUrl || `/preview/${activeProjectId}/index.html`}
              className="w-full flex-1 bg-white border-0"
              title="KODE KSST Web Preview"
            />
          </div>
        )}
      </div>
    </div>
  );
};
