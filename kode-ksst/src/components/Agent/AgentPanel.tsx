import React, { useEffect, useRef, useState } from 'react';
import {
  Bot,
  Brain,
  Check,
  ChevronDown,
  ChevronRight,
  Loader2,
  Send,
  Shield,
  Square,
  Trash2,
  Wrench,
  X,
  Zap,
} from 'lucide-react';
import { useIdeStore } from '../../store/useIdeStore';
import { AgentToolCallLog } from '../../types/ide';

const ToolCard: React.FC<{ tc: AgentToolCallLog }> = ({ tc }) => {
  const { approveToolCall, openFile } = useIdeStore();
  const [expanded, setExpanded] = useState(tc.status === 'awaiting_approval');

  const statusColor =
    tc.status === 'ok'
      ? 'border-emerald-500/30 bg-emerald-950/15'
      : tc.status === 'error'
        ? 'border-rose-500/35 bg-rose-950/20'
        : tc.status === 'awaiting_approval'
          ? 'border-amber-500/50 bg-amber-950/20'
          : 'border-sky-500/30 bg-sky-950/15';

  const fileTarget = tc.arguments?.path || tc.arguments?.filePath;

  return (
    <div className={`rounded-lg border text-[11px] overflow-hidden ${statusColor}`}>
      <div
        onClick={() => setExpanded((v) => !v)}
        className="flex items-center justify-between px-2.5 py-1.5 cursor-pointer hover:bg-white/5"
      >
        <div className="flex items-center gap-1.5 min-w-0">
          {expanded ? <ChevronDown className="w-3 h-3 text-slate-400" /> : <ChevronRight className="w-3 h-3 text-slate-400" />}
          <Wrench className="w-3 h-3 text-sky-400 shrink-0" />
          <span className="font-mono font-semibold text-slate-200 truncate">{tc.name}</span>
          {fileTarget && <span className="text-slate-400 font-mono truncate">({fileTarget})</span>}
        </div>
        <div className="flex items-center gap-1.5 shrink-0">
          {tc.status === 'running' && <Loader2 className="w-3 h-3 text-sky-400 animate-spin" />}
          {tc.status === 'ok' && <Check className="w-3 h-3 text-emerald-400" />}
          {tc.status === 'error' && <X className="w-3 h-3 text-rose-400" />}
          {tc.status === 'awaiting_approval' && <span className="text-amber-300 font-semibold">Aprobar?</span>}
        </div>
      </div>

      {tc.status === 'awaiting_approval' && tc.approvalId && (
        <div className="px-2.5 py-2 border-t border-amber-500/30 bg-amber-950/30 flex items-center justify-between gap-2">
          <span className="text-amber-200">Permitir esta acción?</span>
          <div className="flex items-center gap-1.5">
            <button
              onClick={() => approveToolCall(tc.approvalId!, false)}
              className="px-2 py-0.5 rounded bg-rose-500/20 hover:bg-rose-500/35 text-rose-200 border border-rose-500/40"
            >
              Rechazar
            </button>
            <button
              onClick={() => approveToolCall(tc.approvalId!, true)}
              className="px-2 py-0.5 rounded bg-emerald-500/25 hover:bg-emerald-500/40 text-emerald-200 border border-emerald-500/40 font-semibold"
            >
              Aprobar
            </button>
          </div>
        </div>
      )}

      {expanded && (
        <div className="px-2.5 py-2 border-t border-slate-800/70 space-y-1.5 bg-black/25">
          <div className="text-slate-400 font-mono whitespace-pre-wrap break-all max-h-28 overflow-y-auto">
            args: {JSON.stringify(tc.arguments, null, 2)}
          </div>
          {tc.output && (
            <div className="text-slate-300 font-mono whitespace-pre-wrap break-all max-h-40 overflow-y-auto pt-1 border-t border-slate-800/60">
              {tc.output}
            </div>
          )}
          {fileTarget && (tc.name === 'write_file' || tc.name === 'edit_file' || tc.name === 'read_file') && (
            <button
              onClick={() => openFile(fileTarget)}
              className="text-sky-400 hover:underline font-medium"
            >
              Abrir {fileTarget} en Editor
            </button>
          )}
        </div>
      )}
    </div>
  );
};

export const AgentPanel: React.FC = () => {
  const {
    showAgentPanel,
    brainConfig,
    brainTestedOk,
    agentMessages,
    agentRunning,
    autoApprove,
    agentInputDraft,
    setShowBrainModal,
    setAutoApprove,
    setAgentInputDraft,
    clearAgentChat,
    sendAgentPrompt,
    abortAgent,
  } = useIdeStore();

  const endRef = useRef<HTMLDivElement>(null);
  const [toolsCount, setToolsCount] = useState(16);

  useEffect(() => {
    fetch('/api/agent/tools')
      .then((r) => r.json())
      .then((d) => setToolsCount(d.tools?.length || 16))
      .catch(() => {});
  }, []);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [agentMessages]);

  if (!showAgentPanel) return null;

  const hasBrainKey = Boolean(brainConfig.apiKey?.trim()) || Boolean(brainConfig.baseUrl?.includes('127.0.0.1')) || Boolean(brainConfig.baseUrl?.includes('localhost'));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const text = agentInputDraft.trim();
    if (!text || agentRunning) return;
    setAgentInputDraft('');
    await sendAgentPrompt(text);
  };

  const quickPrompts = [
    'Audita todo el proyecto actual, verifica sintaxis y ejecuta sus pruebas',
    'Crea un proyecto Python con API FastAPI/HTTP y pruebas unitarias',
    'Optimiza el archivo abierto y muestra el Diff',
  ];

  return (
    <div className="w-[380px] xl:w-[420px] bg-[#090d15] border-l border-slate-800/90 flex flex-col shrink-0">
      {/* Agent Header */}
      <div className="px-3 py-2.5 border-b border-slate-800/80 bg-[#0b1019] space-y-2 shrink-0">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 rounded-lg bg-gradient-to-br from-violet-500/30 to-sky-500/30 border border-violet-500/40 flex items-center justify-center">
              <Bot className="w-4 h-4 text-violet-300" />
            </div>
            <div>
              <div className="text-xs font-semibold text-slate-100 flex items-center gap-1.5">
                <span>KODE KSST AGENT</span>
                <span className="px-1.5 py-0.2 rounded bg-sky-500/15 text-sky-300 text-[10px] font-mono">
                  {toolsCount} tools
                </span>
              </div>
              <div className="text-[10px] text-slate-400 font-mono truncate max-w-[190px]">
                {hasBrainKey ? `${brainConfig.provider} · ${brainConfig.model}` : 'Sin cerebro conectado'}
              </div>
            </div>
          </div>

          <div className="flex items-center gap-1">
            <button
              onClick={() => setShowBrainModal(true)}
              className={`flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-medium border transition ${
                hasBrainKey
                  ? 'bg-emerald-500/15 border-emerald-500/30 text-emerald-300 hover:bg-emerald-500/25'
                  : 'bg-violet-600 hover:bg-violet-500 border-violet-400/50 text-white'
              }`}
              title="Conectar o cambiar API del Cerebro"
            >
              <Brain className="w-3.5 h-3.5" />
              <span>{hasBrainKey ? (brainTestedOk === false ? 'Revisar API' : 'Cerebro API') : 'Conectar API'}</span>
            </button>
            <button
              onClick={clearAgentChat}
              className="p-1.5 rounded hover:bg-slate-800 text-slate-400 hover:text-slate-200"
              title="Limpiar conversación"
            >
              <Trash2 className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        {/* Mode bar */}
        <div className="flex items-center justify-between text-[11px]">
          <button
            onClick={() => setAutoApprove(!autoApprove)}
            className={`flex items-center gap-1.5 px-2 py-0.5 rounded border transition ${
              autoApprove
                ? 'bg-amber-500/10 border-amber-500/30 text-amber-300'
                : 'bg-sky-500/10 border-sky-500/30 text-sky-300'
            }`}
            title="Alternar entre Modo Autónomo y Aprobación Paso a Paso"
          >
            {autoApprove ? <Zap className="w-3 h-3" /> : <Shield className="w-3 h-3" />}
            <span>{autoApprove ? 'Modo Autónomo (Auto-Run)' : 'Modo Seguro (Aprobar Pasos)'}</span>
          </button>
          <span className="text-slate-500">Conoce todo el IDE</span>
        </div>
      </div>

      {/* Chat Stream */}
      <div className="flex-1 overflow-y-auto p-3 space-y-3 text-xs">
        {agentMessages.map((msg) => (
          <div key={msg.id} className={`flex flex-col ${msg.role === 'user' ? 'items-end' : 'items-start'}`}>
            <div
              className={`max-w-[95%] rounded-xl px-3 py-2.5 leading-relaxed ${
                msg.role === 'user'
                  ? 'bg-sky-600/25 border border-sky-500/35 text-slate-100'
                  : 'bg-[#101725] border border-slate-800 text-slate-200 w-full'
              }`}
            >
              {msg.content && (
                <div className="whitespace-pre-wrap break-words space-y-1.5">{msg.content}</div>
              )}

              {msg.toolCalls && msg.toolCalls.length > 0 && (
                <div className="mt-2 space-y-1.5">
                  {msg.toolCalls.map((tc) => (
                    <ToolCard key={tc.toolCallId} tc={tc} />
                  ))}
                </div>
              )}

              {msg.role === 'assistant' && !msg.content && (!msg.toolCalls || msg.toolCalls.length === 0) && agentRunning && (
                <div className="flex items-center gap-2 text-slate-400">
                  <Loader2 className="w-3.5 h-3.5 animate-spin text-violet-400" />
                  <span>Analizando el IDE y planificando...</span>
                </div>
              )}
            </div>
          </div>
        ))}
        <div ref={endRef} />
      </div>

      {/* Quick Prompts */}
      {agentMessages.length <= 2 && (
        <div className="px-3 pb-2 flex flex-wrap gap-1.5">
          {quickPrompts.map((qp, i) => (
            <button
              key={i}
              onClick={() => setAgentInputDraft(qp)}
              className="text-left text-[11px] px-2.5 py-1 rounded-full bg-slate-800/80 hover:bg-slate-700 text-slate-300 border border-slate-700/70 truncate max-w-full"
            >
              {qp}
            </button>
          ))}
        </div>
      )}

      {/* Composer */}
      <form onSubmit={handleSubmit} className="p-2.5 border-t border-slate-800/80 bg-[#0b1019] shrink-0">
        <div className="flex flex-col gap-2 bg-slate-900/90 border border-slate-700/80 rounded-xl p-2 focus-within:border-violet-500/70">
          <textarea
            value={agentInputDraft}
            onChange={(e) => setAgentInputDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSubmit(e);
              }
            }}
            rows={3}
            placeholder="Ordénale al Agente qué construir, depurar, refactorizar o ejecutar en cualquier proyecto..."
            className="w-full bg-transparent text-xs text-slate-100 placeholder-slate-500 resize-none focus:outline-none"
          />
          <div className="flex items-center justify-between">
            <span className="text-[10px] text-slate-500">Enter para enviar · Shift+Enter nueva línea</span>
            {agentRunning ? (
              <button
                type="button"
                onClick={abortAgent}
                className="flex items-center gap-1 px-3 py-1.5 rounded-lg bg-rose-600/80 hover:bg-rose-500 text-white text-xs font-semibold"
              >
                <Square className="w-3 h-3 fill-current" />
                <span>Detener</span>
              </button>
            ) : (
              <button
                type="submit"
                disabled={!agentInputDraft.trim()}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-violet-600 hover:bg-violet-500 disabled:opacity-40 text-white text-xs font-semibold transition"
              >
                <Send className="w-3.5 h-3.5" />
                <span>Programar</span>
              </button>
            )}
          </div>
        </div>
      </form>
    </div>
  );
};
