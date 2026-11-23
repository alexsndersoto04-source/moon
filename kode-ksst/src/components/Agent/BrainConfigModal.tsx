import React, { useState } from 'react';
import { Brain, CheckCircle2, Eye, EyeOff, Loader2, PlugZap, X, AlertCircle } from 'lucide-react';
import { useIdeStore } from '../../store/useIdeStore';
import { BrainConfig, BrainProvider } from '../../types/ide';

const PRESETS: Array<{
  id: string;
  label: string;
  provider: BrainProvider;
  baseUrl: string;
  model: string;
  models: string[];
}> = [
  {
    id: 'openai',
    label: 'OpenAI',
    provider: 'openai',
    baseUrl: 'https://api.openai.com/v1',
    model: 'gpt-4o',
    models: ['gpt-4o', 'gpt-4.1', 'o3-mini', 'gpt-4o-mini'],
  },
  {
    id: 'anthropic',
    label: 'Anthropic Claude',
    provider: 'anthropic',
    baseUrl: 'https://api.anthropic.com',
    model: 'claude-sonnet-4-20250514',
    models: ['claude-sonnet-4-20250514', 'claude-3-7-sonnet-20250219', 'claude-3-5-haiku-20241022'],
  },
  {
    id: 'gemini',
    label: 'Google Gemini',
    provider: 'gemini',
    baseUrl: 'https://generativelanguage.googleapis.com',
    model: 'gemini-2.5-flash',
    models: ['gemini-2.5-flash', 'gemini-2.5-pro', 'gemini-2.0-flash'],
  },
  {
    id: 'deepseek',
    label: 'DeepSeek',
    provider: 'openai',
    baseUrl: 'https://api.deepseek.com/v1',
    model: 'deepseek-chat',
    models: ['deepseek-chat', 'deepseek-reasoner'],
  },
  {
    id: 'groq',
    label: 'Groq',
    provider: 'openai',
    baseUrl: 'https://api.groq.com/openai/v1',
    model: 'llama-3.3-70b-versatile',
    models: ['llama-3.3-70b-versatile', 'qwen-2.5-coder-32b'],
  },
  {
    id: 'openrouter',
    label: 'OpenRouter',
    provider: 'openai',
    baseUrl: 'https://openrouter.ai/api/v1',
    model: 'anthropic/claude-sonnet-4',
    models: ['anthropic/claude-sonnet-4', 'openai/gpt-4o', 'google/gemini-2.5-pro'],
  },
  {
    id: 'ollama',
    label: 'Ollama / Local',
    provider: 'openai',
    baseUrl: 'http://127.0.0.1:11434/v1',
    model: 'qwen2.5-coder:14b',
    models: ['qwen2.5-coder:14b', 'llama3.1:8b', 'deepseek-coder-v2'],
  },
];

export const BrainConfigModal: React.FC = () => {
  const { showBrainModal, setShowBrainModal, brainConfig, setBrainConfig, setBrainTestedOk, addToast } = useIdeStore();
  const [draft, setDraft] = useState<BrainConfig>(brainConfig);
  const [showKey, setShowKey] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; msg: string } | null>(null);

  if (!showBrainModal) return null;

  const applyPreset = (presetId: string) => {
    const p = PRESETS.find((x) => x.id === presetId);
    if (!p) return;
    setDraft((d) => ({
      ...d,
      provider: p.provider,
      baseUrl: p.baseUrl,
      model: p.model,
    }));
    setTestResult(null);
  };

  const handleTest = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      const res = await fetch('/api/agent/test-brain', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ brain: draft }),
      }).then((r) => r.json());
      if (res.ok) {
        setTestResult({ ok: true, msg: `Conectado (${res.latencyMs} ms): ${res.reply}` });
        setBrainTestedOk(true);
      } else {
        setTestResult({ ok: false, msg: res.error || 'Error de conexión' });
        setBrainTestedOk(false);
      }
    } catch (err: any) {
      setTestResult({ ok: false, msg: err.message });
      setBrainTestedOk(false);
    } finally {
      setTesting(false);
    }
  };

  const handleSave = () => {
    setBrainConfig(draft);
    setShowBrainModal(false);
    addToast(`Cerebro configurado: ${draft.model} (${draft.provider})`, 'success');
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="w-full max-w-xl max-h-[92vh] overflow-y-auto bg-[#0b1019] border border-slate-700/80 rounded-2xl shadow-2xl">
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-800 bg-[#090d14]">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-violet-500/20 border border-violet-500/40 flex items-center justify-center">
              <Brain className="w-4 h-4 text-violet-300" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-slate-100">Conectar Cerebro API al Agente</h3>
              <p className="text-[11px] text-slate-400">Tu API key se envía directo al backend proxy del IDE sin CORS.</p>
            </div>
          </div>
          <button onClick={() => setShowBrainModal(false)} className="p-1.5 rounded-lg hover:bg-slate-800 text-slate-400">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5 space-y-4 text-xs">
          {/* Quick Presets */}
          <div>
            <div className="text-slate-400 font-medium mb-2">Proveedores rápidos</div>
            <div className="flex flex-wrap gap-1.5">
              {PRESETS.map((p) => (
                <button
                  key={p.id}
                  onClick={() => applyPreset(p.id)}
                  className={`px-2.5 py-1 rounded-md border text-[11px] font-medium transition ${
                    draft.baseUrl === p.baseUrl && draft.provider === p.provider
                      ? 'bg-violet-500/25 border-violet-500/50 text-violet-200'
                      : 'bg-slate-900 border-slate-700 text-slate-300 hover:border-slate-500'
                  }`}
                >
                  {p.label}
                </button>
              ))}
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-slate-400 mb-1">Protocolo API</label>
              <select
                value={draft.provider}
                onChange={(e) => setDraft({ ...draft, provider: e.target.value as BrainProvider })}
                className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-slate-200 focus:outline-none focus:border-violet-500"
              >
                <option value="openai">OpenAI / Compatible</option>
                <option value="anthropic">Anthropic Messages</option>
                <option value="gemini">Google Gemini</option>
              </select>
            </div>
            <div>
              <label className="block text-slate-400 mb-1">Modelo</label>
              <input
                value={draft.model}
                onChange={(e) => setDraft({ ...draft, model: e.target.value })}
                placeholder="gpt-4o / claude-sonnet-4-20250514 / gemini-2.5-flash"
                className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-slate-200 font-mono focus:outline-none focus:border-violet-500"
              />
            </div>
          </div>

          <div>
            <label className="block text-slate-400 mb-1">Base URL del Endpoint</label>
            <input
              value={draft.baseUrl || ''}
              onChange={(e) => setDraft({ ...draft, baseUrl: e.target.value })}
              placeholder="https://api.openai.com/v1"
              className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-slate-200 font-mono focus:outline-none focus:border-violet-500"
            />
          </div>

          <div>
            <label className="block text-slate-400 mb-1">API Key</label>
            <div className="relative">
              <input
                type={showKey ? 'text' : 'password'}
                value={draft.apiKey}
                onChange={(e) => setDraft({ ...draft, apiKey: e.target.value })}
                placeholder="sk-... / AIza... / sk-ant-..."
                className="w-full bg-slate-900 border border-slate-700 rounded-lg pl-3 pr-10 py-2 text-slate-200 font-mono focus:outline-none focus:border-violet-500"
              />
              <button
                type="button"
                onClick={() => setShowKey((v) => !v)}
                className="absolute right-2.5 top-2 text-slate-400 hover:text-slate-200"
              >
                {showKey ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
          </div>

          {testResult && (
            <div
              className={`p-3 rounded-lg border flex items-start gap-2 ${
                testResult.ok
                  ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300'
                  : 'bg-rose-500/10 border-rose-500/30 text-rose-300'
              }`}
            >
              {testResult.ok ? <CheckCircle2 className="w-4 h-4 shrink-0 mt-0.5" /> : <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />}
              <span className="break-all">{testResult.msg}</span>
            </div>
          )}
        </div>

        <div className="flex items-center justify-between px-5 py-3.5 border-t border-slate-800 bg-[#090d14]">
          <button
            onClick={handleTest}
            disabled={testing}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-medium"
          >
            {testing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <PlugZap className="w-3.5 h-3.5 text-sky-400" />}
            <span>Probar Conexión</span>
          </button>

          <div className="flex items-center gap-2">
            <button
              onClick={() => setShowBrainModal(false)}
              className="px-3 py-2 rounded-lg hover:bg-slate-800 text-slate-400 text-xs"
            >
              Cancelar
            </button>
            <button
              onClick={handleSave}
              className="px-4 py-2 rounded-lg bg-violet-600 hover:bg-violet-500 text-white text-xs font-semibold"
            >
              Guardar y Activar Cerebro
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
