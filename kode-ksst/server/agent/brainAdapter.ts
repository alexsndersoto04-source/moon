export type BrainProvider = 'openai' | 'anthropic' | 'gemini';

export interface BrainConfig {
  provider: BrainProvider;
  apiKey: string;
  baseUrl?: string;
  model: string;
  temperature?: number;
  maxTokens?: number;
}

export interface ToolDefinition {
  name: string;
  description: string;
  parameters: Record<string, any>; // JSON Schema object
}

export interface AgentToolCall {
  id: string;
  name: string;
  arguments: Record<string, any>;
}

export type ConversationMessage =
  | { role: 'system'; content: string }
  | { role: 'user'; content: string }
  | { role: 'assistant'; content: string; toolCalls?: AgentToolCall[] }
  | { role: 'tool'; toolCallId: string; toolName: string; content: string };

export interface BrainResponse {
  text: string;
  toolCalls: AgentToolCall[];
  usage?: { inputTokens?: number; outputTokens?: number };
  rawFinishReason?: string;
}

export function sanitizeApiKey(raw: string | undefined): string {
  if (!raw) return '';
  return raw.replace(/[\s\u200B-\u200D\uFEFF"']/g, '').trim();
}

function cleanBaseUrl(url: string | undefined, fallback: string): string {
  return (url && url.trim() ? url.trim() : fallback).replace(/\/+$/, '');
}

function normalizeConfig(raw: BrainConfig): BrainConfig {
  const apiKey = sanitizeApiKey(raw.apiKey);
  let provider = raw.provider;
  let baseUrl = raw.baseUrl?.trim() || '';
  let model = raw.model?.trim() || '';

  // Auto-detect provider if mismatched with key prefix
  if (apiKey.startsWith('AIza') && provider !== 'gemini') {
    provider = 'gemini';
    if (!baseUrl || baseUrl.includes('openai.com')) {
      baseUrl = 'https://generativelanguage.googleapis.com';
    }
    if (!model || model.startsWith('gpt-') || model.startsWith('claude-')) {
      model = 'gemini-2.5-flash';
    }
  } else if (apiKey.startsWith('sk-ant-') && provider !== 'anthropic') {
    provider = 'anthropic';
    if (!baseUrl || baseUrl.includes('openai.com')) {
      baseUrl = 'https://api.anthropic.com';
    }
    if (!model || model.startsWith('gpt-') || model.startsWith('gemini-')) {
      model = 'claude-sonnet-4-20250514';
    }
  } else if (apiKey.startsWith('gsk_') && (!baseUrl || baseUrl.includes('openai.com'))) {
    provider = 'openai';
    baseUrl = 'https://api.groq.com/openai/v1';
    if (!model || model.startsWith('gpt-')) model = 'llama-3.3-70b-versatile';
  } else if (apiKey.startsWith('sk-or-v1-') && (!baseUrl || baseUrl.includes('openai.com'))) {
    provider = 'openai';
    baseUrl = 'https://openrouter.ai/api/v1';
    if (!model || model === 'gpt-4o') model = 'openai/gpt-4o-mini';
  } else if (apiKey.startsWith('xai-') && (!baseUrl || baseUrl.includes('openai.com'))) {
    provider = 'openai';
    baseUrl = 'https://api.x.ai/v1';
    if (!model || model.startsWith('gpt-')) model = 'grok-3-mini';
  }

  return { ...raw, provider, apiKey, baseUrl, model };
}

export async function callBrain(
  rawConfig: BrainConfig,
  messages: ConversationMessage[],
  tools: ToolDefinition[],
  signal?: AbortSignal
): Promise<BrainResponse> {
  const config = normalizeConfig(rawConfig);

  if (!config.apiKey && config.provider !== 'openai') {
    throw new Error('Falta la API Key para este proveedor.');
  }
  if (
    config.provider === 'openai' &&
    !config.apiKey &&
    !config.baseUrl?.includes('localhost') &&
    !config.baseUrl?.includes('127.0.0.1')
  ) {
    throw new Error('Falta la API Key. Configúrala en "Conectar Cerebro API".');
  }

  if (config.provider === 'openai') {
    return callOpenAICompatible(config, messages, tools, signal);
  }
  if (config.provider === 'anthropic') {
    return callAnthropic(config, messages, tools, signal);
  }
  if (config.provider === 'gemini') {
    return callGemini(config, messages, tools, signal);
  }
  throw new Error(`Proveedor no soportado: ${config.provider}`);
}

async function callOpenAICompatible(
  config: BrainConfig,
  messages: ConversationMessage[],
  tools: ToolDefinition[],
  signal?: AbortSignal,
  skipTemperature = false
): Promise<BrainResponse> {
  let baseUrl = cleanBaseUrl(config.baseUrl, 'https://api.openai.com/v1');
  if (baseUrl === 'https://api.openai.com') baseUrl = 'https://api.openai.com/v1';
  const url = baseUrl.endsWith('/chat/completions') ? baseUrl : `${baseUrl}/chat/completions`;

  const formattedMessages = messages.map((m) => {
    if (m.role === 'assistant') {
      const msg: any = { role: 'assistant', content: m.content || null };
      if (m.toolCalls && m.toolCalls.length > 0) {
        msg.tool_calls = m.toolCalls.map((tc) => ({
          id: tc.id,
          type: 'function',
          function: {
            name: tc.name,
            arguments: JSON.stringify(tc.arguments),
          },
        }));
      }
      return msg;
    }
    if (m.role === 'tool') {
      return {
        role: 'tool',
        tool_call_id: m.toolCallId,
        content: m.content,
      };
    }
    return { role: m.role, content: m.content };
  });

  const model = config.model || 'gpt-4o';
  const isReasoningModel = /^(o1|o3|o4|gpt-5)/i.test(model);

  const body: any = {
    model,
    messages: formattedMessages,
  };
  if (!skipTemperature && !isReasoningModel) {
    body.temperature = config.temperature ?? 0.2;
  }
  if (config.maxTokens) body.max_tokens = config.maxTokens;
  if (tools.length > 0) {
    body.tools = tools.map((t) => ({
      type: 'function',
      function: {
        name: t.name,
        description: t.description,
        parameters: t.parameters,
      },
    }));
    body.tool_choice = 'auto';
  }

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (config.apiKey) {
    headers['Authorization'] = `Bearer ${config.apiKey}`;
  }
  if (baseUrl.includes('openrouter.ai')) {
    headers['HTTP-Referer'] = 'https://kode-ksst.app';
    headers['X-Title'] = 'KODE KSST IDE';
  }

  const res = await fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    signal,
  });

  const rawText = await res.text();
  if (!res.ok) {
    if (!skipTemperature && res.status === 400 && rawText.toLowerCase().includes('temperature')) {
      return callOpenAICompatible(config, messages, tools, signal, true);
    }
    throw new Error(`Error API (${res.status}) en ${url}: ${rawText.slice(0, 600)}`);
  }

  const data = JSON.parse(rawText);
  const choice = data.choices?.[0];
  if (!choice) throw new Error('La API no devolvió opciones (choices vacío).');

  const msg = choice.message || {};
  const toolCalls: AgentToolCall[] = [];
  if (Array.isArray(msg.tool_calls)) {
    for (const tc of msg.tool_calls) {
      let parsedArgs: Record<string, any> = {};
      try {
        parsedArgs = tc.function?.arguments ? JSON.parse(tc.function.arguments) : {};
      } catch {
        parsedArgs = { _raw: tc.function?.arguments };
      }
      toolCalls.push({
        id: tc.id || `call_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
        name: tc.function?.name,
        arguments: parsedArgs,
      });
    }
  }

  return {
    text: typeof msg.content === 'string' ? msg.content : '',
    toolCalls,
    usage: data.usage
      ? { inputTokens: data.usage.prompt_tokens, outputTokens: data.usage.completion_tokens }
      : undefined,
    rawFinishReason: choice.finish_reason,
  };
}

async function callAnthropic(
  config: BrainConfig,
  messages: ConversationMessage[],
  tools: ToolDefinition[],
  signal?: AbortSignal
): Promise<BrainResponse> {
  const baseUrl = cleanBaseUrl(config.baseUrl, 'https://api.anthropic.com').replace(/\/v1$/, '');
  const url = `${baseUrl}/v1/messages`;

  const systemParts: string[] = [];
  const anthropicMsgs: any[] = [];

  for (const m of messages) {
    if (m.role === 'system') {
      systemParts.push(m.content);
    } else if (m.role === 'user') {
      anthropicMsgs.push({ role: 'user', content: m.content });
    } else if (m.role === 'assistant') {
      const blocks: any[] = [];
      if (m.content) blocks.push({ type: 'text', text: m.content });
      if (m.toolCalls) {
        for (const tc of m.toolCalls) {
          blocks.push({
            type: 'tool_use',
            id: tc.id,
            name: tc.name,
            input: tc.arguments,
          });
        }
      }
      anthropicMsgs.push({ role: 'assistant', content: blocks });
    } else if (m.role === 'tool') {
      const last = anthropicMsgs[anthropicMsgs.length - 1];
      const block = {
        type: 'tool_result',
        tool_use_id: m.toolCallId,
        content: m.content,
      };
      if (last && last.role === 'user' && Array.isArray(last.content)) {
        last.content.push(block);
      } else {
        anthropicMsgs.push({ role: 'user', content: [block] });
      }
    }
  }

  const body: any = {
    model: config.model || 'claude-sonnet-4-20250514',
    max_tokens: config.maxTokens || 4096,
    system: systemParts.join('\n\n'),
    messages: anthropicMsgs,
    temperature: config.temperature ?? 0.2,
  };
  if (tools.length > 0) {
    body.tools = tools.map((t) => ({
      name: t.name,
      description: t.description,
      input_schema: t.parameters,
    }));
  }

  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-api-key': config.apiKey,
      'anthropic-version': '2023-06-01',
    },
    body: JSON.stringify(body),
    signal,
  });

  const rawText = await res.text();
  if (!res.ok) {
    throw new Error(`Anthropic API error (${res.status}): ${rawText.slice(0, 600)}`);
  }

  const data = JSON.parse(rawText);
  const blocks = Array.isArray(data.content) ? data.content : [];
  let text = '';
  const toolCalls: AgentToolCall[] = [];
  for (const b of blocks) {
    if (b.type === 'text') text += b.text;
    if (b.type === 'tool_use') {
      toolCalls.push({
        id: b.id,
        name: b.name,
        arguments: b.input || {},
      });
    }
  }

  return {
    text,
    toolCalls,
    usage: data.usage
      ? { inputTokens: data.usage.input_tokens, outputTokens: data.usage.output_tokens }
      : undefined,
    rawFinishReason: data.stop_reason,
  };
}

async function callGemini(
  config: BrainConfig,
  messages: ConversationMessage[],
  tools: ToolDefinition[],
  signal?: AbortSignal
): Promise<BrainResponse> {
  const baseUrl = cleanBaseUrl(config.baseUrl, 'https://generativelanguage.googleapis.com').replace(/\/v1beta$/, '');
  const model = (config.model || 'gemini-2.5-flash').replace(/^models\//, '');
  const url = `${baseUrl}/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(config.apiKey)}`;

  const systemParts: string[] = [];
  const contents: any[] = [];

  for (const m of messages) {
    if (m.role === 'system') {
      systemParts.push(m.content);
    } else if (m.role === 'user') {
      contents.push({ role: 'user', parts: [{ text: m.content }] });
    } else if (m.role === 'assistant') {
      const parts: any[] = [];
      if (m.content) parts.push({ text: m.content });
      if (m.toolCalls) {
        for (const tc of m.toolCalls) {
          parts.push({
            functionCall: {
              name: tc.name,
              args: tc.arguments,
            },
          });
        }
      }
      contents.push({ role: 'model', parts });
    } else if (m.role === 'tool') {
      let parsedResp: any;
      try {
        parsedResp = JSON.parse(m.content);
      } catch {
        parsedResp = { result: m.content };
      }
      const part = {
        functionResponse: {
          name: m.toolName,
          response: parsedResp,
        },
      };
      const last = contents[contents.length - 1];
      if (last && last.role === 'user' && last.parts?.[0]?.functionResponse) {
        last.parts.push(part);
      } else {
        contents.push({ role: 'user', parts: [part] });
      }
    }
  }

  const body: any = {
    contents,
    generationConfig: {
      temperature: config.temperature ?? 0.2,
      ...(config.maxTokens ? { maxOutputTokens: config.maxTokens } : {}),
    },
  };
  if (systemParts.length > 0) {
    body.systemInstruction = { parts: [{ text: systemParts.join('\n\n') }] };
  }
  if (tools.length > 0) {
    body.tools = [
      {
        functionDeclarations: tools.map((t) => ({
          name: t.name,
          description: t.description,
          parameters: t.parameters,
        })),
      },
    ];
  }

  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });

  const rawText = await res.text();
  if (!res.ok) {
    throw new Error(`Gemini API error (${res.status}): ${rawText.slice(0, 600)}`);
  }

  const data = JSON.parse(rawText);
  const candidate = data.candidates?.[0];
  const parts = candidate?.content?.parts || [];
  let text = '';
  const toolCalls: AgentToolCall[] = [];
  for (const p of parts) {
    if (p.text) text += p.text;
    if (p.functionCall) {
      toolCalls.push({
        id: `gem_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
        name: p.functionCall.name,
        arguments: p.functionCall.args || {},
      });
    }
  }

  return {
    text,
    toolCalls,
    usage: data.usageMetadata
      ? {
          inputTokens: data.usageMetadata.promptTokenCount,
          outputTokens: data.usageMetadata.candidatesTokenCount,
        }
      : undefined,
    rawFinishReason: candidate?.finishReason,
  };
}

export async function listBrainModels(rawConfig: BrainConfig): Promise<string[]> {
  const config = normalizeConfig(rawConfig);

  if (config.provider === 'gemini') {
    const baseUrl = cleanBaseUrl(config.baseUrl, 'https://generativelanguage.googleapis.com').replace(/\/v1beta$/, '');
    const res = await fetch(`${baseUrl}/v1beta/models?key=${encodeURIComponent(config.apiKey)}`);
    const raw = await res.text();
    if (!res.ok) throw new Error(`Error listando modelos Gemini (${res.status}): ${raw.slice(0, 300)}`);
    const data = JSON.parse(raw);
    const models: string[] = (data.models || [])
      .filter((m: any) => Array.isArray(m.supportedGenerationMethods) && m.supportedGenerationMethods.includes('generateContent'))
      .map((m: any) => String(m.name || '').replace(/^models\//, ''))
      .filter(Boolean);
    return models;
  }

  if (config.provider === 'anthropic') {
    const baseUrl = cleanBaseUrl(config.baseUrl, 'https://api.anthropic.com').replace(/\/v1$/, '');
    const res = await fetch(`${baseUrl}/v1/models`, {
      headers: {
        'x-api-key': config.apiKey,
        'anthropic-version': '2023-06-01',
      },
    });
    const raw = await res.text();
    if (!res.ok) throw new Error(`Error listando modelos Anthropic (${res.status}): ${raw.slice(0, 300)}`);
    const data = JSON.parse(raw);
    return (data.data || []).map((m: any) => m.id).filter(Boolean);
  }

  let baseUrl = cleanBaseUrl(config.baseUrl, 'https://api.openai.com/v1');
  if (baseUrl === 'https://api.openai.com') baseUrl = 'https://api.openai.com/v1';
  const headers: Record<string, string> = {};
  if (config.apiKey) headers['Authorization'] = `Bearer ${config.apiKey}`;
  const res = await fetch(`${baseUrl}/models`, { headers });
  const raw = await res.text();
  if (!res.ok) throw new Error(`Error listando modelos (${res.status}): ${raw.slice(0, 300)}`);
  const data = JSON.parse(raw);
  return (data.data || []).map((m: any) => m.id).filter(Boolean).sort();
}

export async function testBrainConnection(
  rawConfig: BrainConfig
): Promise<{ ok: boolean; reply: string; latencyMs: number; resolvedConfig?: BrainConfig; availableModels?: string[] }> {
  const config = normalizeConfig(rawConfig);
  const start = Date.now();

  try {
    const resp = await callBrain(
      config,
      [
        { role: 'system', content: 'Respond with exactly: KODE_KSST_BRAIN_ONLINE' },
        { role: 'user', content: 'Health check ping' },
      ],
      []
    );
    return {
      ok: true,
      reply: resp.text.trim() || 'Conectado',
      latencyMs: Date.now() - start,
      resolvedConfig: config,
    };
  } catch (primaryErr: any) {
    // If key itself works for listing models, auto-pick a supported model on that key!
    try {
      const models = await listBrainModels(config);
      if (models.length > 0) {
        const preferred =
          models.find((m) => /flash|gpt-4o-mini|gpt-4o|claude|deepseek-chat|llama-3\.3|grok/i.test(m)) || models[0];
        const retryCfg = { ...config, model: preferred };
        const resp = await callBrain(
          retryCfg,
          [
            { role: 'system', content: 'Respond with exactly: KODE_KSST_BRAIN_ONLINE' },
            { role: 'user', content: 'Health check ping' },
          ],
          []
        );
        return {
          ok: true,
          reply: `${resp.text.trim() || 'Conectado'} (modelo ajustado a ${preferred})`,
          latencyMs: Date.now() - start,
          resolvedConfig: retryCfg,
          availableModels: models.slice(0, 25),
        };
      }
    } catch {}
    throw primaryErr;
  }
}
