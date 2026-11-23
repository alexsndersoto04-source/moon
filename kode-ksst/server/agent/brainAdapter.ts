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
  parameters: Record<string, any>;
}

export interface AgentToolCall {
  id: string;
  name: string;
  arguments: Record<string, any>;
  extraMeta?: Record<string, any>; // e.g. Gemini thoughtSignature
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
  usedPromptToolFallback?: boolean;
}

export function sanitizeApiKey(raw: string | undefined): string {
  if (!raw) return '';
  return raw.replace(/[\s\u200B-\u200D\uFEFF"']/g, '').trim();
}

function cleanBaseUrl(url: string | undefined, fallback: string): string {
  return (url && url.trim() ? url.trim() : fallback).replace(/\/+$/, '');
}

export function normalizeConfig(raw: BrainConfig): BrainConfig {
  const apiKey = sanitizeApiKey(raw.apiKey);
  let provider = raw.provider;
  let baseUrl = raw.baseUrl?.trim() || '';
  let model = raw.model?.trim() || '';

  const isKnownCloudDefault =
    !baseUrl ||
    baseUrl.includes('api.openai.com') ||
    baseUrl.includes('generativelanguage.googleapis.com') ||
    baseUrl.includes('api.anthropic.com') ||
    baseUrl.includes('api.groq.com') ||
    baseUrl.includes('api.deepseek.com') ||
    baseUrl.includes('api.x.ai') ||
    baseUrl.includes('openrouter.ai') ||
    baseUrl.includes('router.huggingface.co');

  if (apiKey.startsWith('AIza')) {
    provider = 'gemini';
    if (isKnownCloudDefault && !baseUrl.includes('googleapis.com')) {
      baseUrl = 'https://generativelanguage.googleapis.com';
    }
    if (!model || !model.toLowerCase().includes('gemini')) {
      model = 'gemini-2.5-flash';
    }
  } else if (apiKey.startsWith('sk-ant-')) {
    provider = 'anthropic';
    if (isKnownCloudDefault && !baseUrl.includes('anthropic.com')) {
      baseUrl = 'https://api.anthropic.com';
    }
    if (!model || !model.toLowerCase().includes('claude')) {
      model = 'claude-sonnet-4-20250514';
    }
  } else if (apiKey.startsWith('gsk_')) {
    provider = 'openai';
    if (isKnownCloudDefault && !baseUrl.includes('groq.com')) {
      baseUrl = 'https://api.groq.com/openai/v1';
    }
    if (!model || model.startsWith('gpt-') || model.startsWith('gemini-') || model.startsWith('claude-') || /allam|whisper|tts|guard/i.test(model)) {
      model = 'llama-3.3-70b-versatile';
    }
  } else if (apiKey.startsWith('sk-or-')) {
    provider = 'openai';
    if (isKnownCloudDefault && !baseUrl.includes('openrouter.ai')) {
      baseUrl = 'https://openrouter.ai/api/v1';
    }
    if (!model || model === 'gpt-4o') model = 'openai/gpt-4o-mini';
  } else if (apiKey.startsWith('xai-')) {
    provider = 'openai';
    if (isKnownCloudDefault && !baseUrl.includes('x.ai')) {
      baseUrl = 'https://api.x.ai/v1';
    }
    if (!model || !model.toLowerCase().includes('grok')) model = 'grok-3-mini';
  } else if (apiKey.startsWith('hf_')) {
    provider = 'openai';
    if (isKnownCloudDefault && !baseUrl.includes('huggingface.co')) {
      baseUrl = 'https://router.huggingface.co/v1';
    }
    if (!model || model.startsWith('gpt-')) model = 'Qwen/Qwen2.5-Coder-32B-Instruct';
  } else if (apiKey.startsWith('sk-proj-')) {
    provider = 'openai';
    if (isKnownCloudDefault && !baseUrl.includes('openai.com')) {
      baseUrl = 'https://api.openai.com/v1';
    }
    if (!model || model.startsWith('gemini-') || model.startsWith('claude-') || model.startsWith('llama')) {
      model = 'gpt-4o-mini';
    }
  } else if (apiKey.startsWith('sk-') && (provider === 'gemini' || provider === 'anthropic')) {
    provider = 'openai';
    if (isKnownCloudDefault && (baseUrl.includes('googleapis.com') || baseUrl.includes('anthropic.com'))) {
      baseUrl = 'https://api.openai.com/v1';
    }
    if (!model || model.startsWith('gemini-') || model.startsWith('claude-')) {
      model = 'gpt-4o-mini';
    }
  }

  return { ...raw, provider, apiKey, baseUrl, model };
}

function isNonToolChatModel(modelId: string): boolean {
  return /(whisper|tts|orpheus|playai|guard|shield|embed|embedding|imagen|veo|dall-e|sora|moderation|transcribe|audio|realtime|search|allam|gemma-3n|babbage|davinci)/i.test(
    modelId
  );
}

function rankModelsForCoding(models: string[], provider: BrainProvider, baseUrl = '', apiKey = ''): string[] {
  const priorityPatterns: RegExp[] =
    provider === 'gemini'
      ? [/^gemini-2\.5-flash$/i, /^gemini-2\.0-flash$/i, /^gemini-2\.5-pro$/i, /^gemini-1\.5-flash$/i, /^gemini-1\.5-pro$/i, /^gemini/i]
      : provider === 'anthropic'
        ? [/claude-sonnet-4/i, /claude-3-7-sonnet/i, /claude-3-5-sonnet/i, /claude-3-5-haiku/i, /claude/i]
        : baseUrl.includes('groq.com') || apiKey.startsWith('gsk_')
          ? [/^llama-3\.3-70b-versatile$/i, /^qwen-2\.5-coder/i, /^qwen\/qwen3/i, /^llama-3\.1-8b-instant$/i, /^meta-llama\/llama-4/i, /llama/i]
          : baseUrl.includes('deepseek.com')
            ? [/^deepseek-chat$/i, /^deepseek-reasoner$/i]
            : [/^gpt-4o-mini$/i, /^gpt-4o$/i, /^gpt-4\.1-mini$/i, /^gpt-4\.1$/i, /^gpt-4/i, /^o3-mini$/i, /^grok-3/i, /coder/i];

  const usable = models.filter((m) => !isNonToolChatModel(m));
  const pool = usable.length > 0 ? usable : models;

  return [...pool].sort((a, b) => {
    const score = (id: string) => {
      for (let i = 0; i < priorityPatterns.length; i++) {
        if (priorityPatterns[i].test(id)) return i;
      }
      return priorityPatterns.length + 1;
    };
    const diff = score(a) - score(b);
    return diff !== 0 ? diff : a.localeCompare(b);
  });
}

function isToolUnsupportedError(status: number, rawText: string): boolean {
  if (status !== 400 && status !== 422) return false;
  return /tool.?call|function.?call|tools.*not supported|does not support tools|tool_choice|unknown field.*tools/i.test(
    rawText
  );
}

function buildReactToolMessages(messages: ConversationMessage[], tools: ToolDefinition[]): ConversationMessage[] {
  const toolSpec = tools
    .map((t) => `- ${t.name}: ${t.description}\n  parameters: ${JSON.stringify(t.parameters)}`)
    .join('\n');

  const reactInstruction = `
=== TOOL CALLING PROTOCOL (TEXT MODE) ===
Native tool calling is disabled on this model, so to invoke any IDE tool you MUST output a block in this exact format:
<tool_call>
{"name": "TOOL_NAME", "arguments": { ... }}
</tool_call>

Available tools:
${toolSpec}

Rules:
- You may output one or more <tool_call>...</tool_call> blocks when you need to inspect files, edit code, run commands, or control the IDE.
- Wait for the tool result before assuming output.
- When finished and no more tools are needed, respond normally without <tool_call>.`;

  const converted: ConversationMessage[] = [];
  let addedInstruction = false;

  for (const m of messages) {
    if (m.role === 'system') {
      converted.push({ role: 'system', content: `${m.content}\n\n${reactInstruction}` });
      addedInstruction = true;
    } else if (m.role === 'assistant') {
      const tcText = (m.toolCalls || [])
        .map((tc) => `<tool_call>\n${JSON.stringify({ name: tc.name, arguments: tc.arguments })}\n</tool_call>`)
        .join('\n');
      converted.push({
        role: 'assistant',
        content: [m.content, tcText].filter(Boolean).join('\n'),
      });
    } else if (m.role === 'tool') {
      converted.push({
        role: 'user',
        content: `<tool_result name="${m.toolName}">\n${m.content}\n</tool_result>`,
      });
    } else {
      converted.push(m);
    }
  }

  if (!addedInstruction) {
    converted.unshift({ role: 'system', content: reactInstruction });
  }
  return converted;
}

function parseReactToolCalls(text: string): { cleanText: string; toolCalls: AgentToolCall[] } {
  const toolCalls: AgentToolCall[] = [];
  let cleanText = text;

  const regex = /<tool_call>\s*([\s\S]*?)\s*<\/tool_call>/gi;
  let match: RegExpExecArray | null;
  while ((match = regex.exec(text)) !== null) {
    const rawJson = match[1].replace(/^```json\s*/i, '').replace(/```$/, '').trim();
    try {
      const parsed = JSON.parse(rawJson);
      if (parsed && typeof parsed.name === 'string') {
        toolCalls.push({
          id: `react_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
          name: parsed.name,
          arguments: parsed.arguments && typeof parsed.arguments === 'object' ? parsed.arguments : {},
        });
      }
    } catch {}
  }

  if (toolCalls.length > 0) {
    cleanText = text.replace(regex, '').trim();
  }
  return { cleanText, toolCalls };
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

  const dispatch = (msgs: ConversationMessage[], activeTools: ToolDefinition[]) => {
    if (config.provider === 'openai') return callOpenAICompatible(config, msgs, activeTools, signal);
    if (config.provider === 'anthropic') return callAnthropic(config, msgs, activeTools, signal);
    if (config.provider === 'gemini') return callGemini(config, msgs, activeTools, signal);
    throw new Error(`Proveedor no soportado: ${config.provider}`);
  };

  try {
    return await dispatch(messages, tools);
  } catch (err: any) {
    if (tools.length > 0 && err?._toolUnsupported) {
      // Automatic fallback: run the exact same request with Text-Mode ReAct Tool Calling!
      const reactMsgs = buildReactToolMessages(messages, tools);
      const resp = await dispatch(reactMsgs, []);
      const parsed = parseReactToolCalls(resp.text);
      return {
        ...resp,
        text: parsed.cleanText,
        toolCalls: parsed.toolCalls,
        usedPromptToolFallback: true,
      };
    }
    throw err;
  }
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
      const msg: any = { role: 'assistant', content: m.content || (m.toolCalls?.length ? null : '') };
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

  const model = config.model || 'gpt-4o-mini';
  const isReasoningModel = /^(o1|o3|o4|gpt-5)/i.test(model);

  const body: any = {
    model,
    messages: formattedMessages,
  };
  if (!skipTemperature && !isReasoningModel) {
    body.temperature = config.temperature ?? 0.2;
  }
  if (config.maxTokens && !isReasoningModel) body.max_tokens = config.maxTokens;
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
    const err: any = new Error(`Error API (${res.status}) en ${url}: ${rawText.slice(0, 600)}`);
    if (tools.length > 0 && isToolUnsupportedError(res.status, rawText)) {
      err._toolUnsupported = true;
    }
    throw err;
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
  const anthropicMsgs: Array<{ role: 'user' | 'assistant'; content: any[] }> = [];

  const pushMsg = (role: 'user' | 'assistant', blocks: any[]) => {
    if (blocks.length === 0) return;
    const last = anthropicMsgs[anthropicMsgs.length - 1];
    if (last && last.role === role) {
      last.content.push(...blocks);
    } else {
      anthropicMsgs.push({ role, content: blocks });
    }
  };

  for (const m of messages) {
    if (m.role === 'system') {
      if (m.content.trim()) systemParts.push(m.content);
    } else if (m.role === 'user') {
      pushMsg('user', [{ type: 'text', text: m.content || ' ' }]);
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
      pushMsg('assistant', blocks);
    } else if (m.role === 'tool') {
      pushMsg('user', [
        {
          type: 'tool_result',
          tool_use_id: m.toolCallId,
          content: m.content || 'ok',
        },
      ]);
    }
  }

  const body: any = {
    model: config.model || 'claude-sonnet-4-20250514',
    max_tokens: config.maxTokens || 4096,
    messages: anthropicMsgs,
    temperature: config.temperature ?? 0.2,
  };
  if (systemParts.length > 0) {
    body.system = systemParts.join('\n\n');
  }
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
    const err: any = new Error(`Anthropic API error (${res.status}): ${rawText.slice(0, 600)}`);
    if (tools.length > 0 && isToolUnsupportedError(res.status, rawText)) {
      err._toolUnsupported = true;
    }
    throw err;
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

function sanitizeSchemaForGemini(schema: any): any {
  if (!schema || typeof schema !== 'object') return schema;
  const copy: any = {};
  for (const [k, v] of Object.entries(schema)) {
    if (k === 'additionalProperties' || k === '$schema' || k === 'default') continue;
    if (k === 'properties' && v && typeof v === 'object') {
      const props: any = {};
      for (const [pk, pv] of Object.entries(v as Record<string, any>)) {
        props[pk] = sanitizeSchemaForGemini(pv);
      }
      copy.properties = props;
    } else if (k === 'items' && v && typeof v === 'object') {
      copy.items = sanitizeSchemaForGemini(v);
    } else {
      copy[k] = v;
    }
  }
  return copy;
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
  const contents: Array<{ role: 'user' | 'model'; parts: any[] }> = [];

  const pushPart = (role: 'user' | 'model', part: any) => {
    const last = contents[contents.length - 1];
    if (last && last.role === role) {
      last.parts.push(part);
    } else {
      contents.push({ role, parts: [part] });
    }
  };

  for (const m of messages) {
    if (m.role === 'system') {
      if (m.content.trim()) systemParts.push(m.content);
    } else if (m.role === 'user') {
      pushPart('user', { text: m.content || ' ' });
    } else if (m.role === 'assistant') {
      if (m.content) pushPart('model', { text: m.content });
      if (m.toolCalls) {
        for (const tc of m.toolCalls) {
          const fcPart: any = {
            functionCall: {
              name: tc.name,
              args: tc.arguments || {},
            },
          };
          if (tc.extraMeta?.thoughtSignature) {
            fcPart.thoughtSignature = tc.extraMeta.thoughtSignature;
          }
          pushPart('model', fcPart);
        }
      }
    } else if (m.role === 'tool') {
      let parsedResp: any;
      try {
        parsedResp = JSON.parse(m.content);
        if (typeof parsedResp !== 'object' || parsedResp === null || Array.isArray(parsedResp)) {
          parsedResp = { result: parsedResp };
        }
      } catch {
        parsedResp = { result: m.content };
      }
      pushPart('user', {
        functionResponse: {
          name: m.toolName,
          response: parsedResp,
        },
      });
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
        functionDeclarations: tools.map((t) => {
          const decl: any = {
            name: t.name,
            description: t.description,
          };
          const propKeys = Object.keys(t.parameters?.properties || {});
          if (propKeys.length > 0) {
            decl.parameters = sanitizeSchemaForGemini(t.parameters);
          }
          return decl;
        }),
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
    const err: any = new Error(`Gemini API error (${res.status}): ${rawText.slice(0, 600)}`);
    if (tools.length > 0 && isToolUnsupportedError(res.status, rawText)) {
      err._toolUnsupported = true;
    }
    throw err;
  }

  const data = JSON.parse(rawText);
  const candidate = data.candidates?.[0];
  const parts = candidate?.content?.parts || [];
  let text = '';
  const toolCalls: AgentToolCall[] = [];
  for (const p of parts) {
    if (p.thought) continue; // skip internal thinking traces
    if (p.text) text += p.text;
    if (p.functionCall) {
      toolCalls.push({
        id: `gem_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
        name: p.functionCall.name,
        arguments: p.functionCall.args || {},
        extraMeta: p.thoughtSignature ? { thoughtSignature: p.thoughtSignature } : undefined,
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
      .filter((id: string) => id && !isNonToolChatModel(id) && !/^gemma/i.test(id));
    return rankModelsForCoding(models, 'gemini');
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
    const models = (data.data || []).map((m: any) => m.id).filter(Boolean);
    return rankModelsForCoding(models, 'anthropic');
  }

  let baseUrl = cleanBaseUrl(config.baseUrl, 'https://api.openai.com/v1');
  if (baseUrl === 'https://api.openai.com') baseUrl = 'https://api.openai.com/v1';
  const headers: Record<string, string> = {};
  if (config.apiKey) headers['Authorization'] = `Bearer ${config.apiKey}`;
  const res = await fetch(`${baseUrl}/models`, { headers });
  const raw = await res.text();
  if (!res.ok) throw new Error(`Error listando modelos (${res.status}): ${raw.slice(0, 300)}`);
  const data = JSON.parse(raw);
  const models = (data.data || [])
    .map((m: any) => m.id)
    .filter((id: string) => Boolean(id) && !isNonToolChatModel(id));
  return rankModelsForCoding(models, 'openai', baseUrl, config.apiKey);
}

const PING_TOOL: ToolDefinition[] = [
  {
    name: 'ide_ping',
    description: 'Verify IDE tool calling capability.',
    parameters: {
      type: 'object',
      properties: {
        status: { type: 'string', description: 'Short status string.' },
      },
    },
  },
];

export async function testBrainConnection(
  rawConfig: BrainConfig
): Promise<{ ok: boolean; reply: string; latencyMs: number; resolvedConfig?: BrainConfig; availableModels?: string[] }> {
  const config = normalizeConfig(rawConfig);
  const start = Date.now();

  const tryPing = async (cfg: BrainConfig) => {
    return callBrain(
      cfg,
      [
        { role: 'system', content: 'Respond briefly with: KODE_KSST_BRAIN_ONLINE (do not call tools for this ping).' },
        { role: 'user', content: 'Health check ping' },
      ],
      PING_TOOL
    );
  };

  try {
    const resp = await tryPing(config);
    let models: string[] | undefined;
    try {
      models = (await listBrainModels(config)).slice(0, 30);
    } catch {}
    return {
      ok: true,
      reply: `${resp.text.trim() || 'Conectado'}${resp.usedPromptToolFallback ? ' (modo ReAct)' : ''}`,
      latencyMs: Date.now() - start,
      resolvedConfig: config,
      availableModels: models,
    };
  } catch (primaryErr: any) {
    // Try discovering working models on that API key
    try {
      const models = await listBrainModels(config);
      for (const candidate of models.slice(0, 5)) {
        try {
          const retryCfg = { ...config, model: candidate };
          const resp = await tryPing(retryCfg);
          return {
            ok: true,
            reply: `${resp.text.trim() || 'Conectado'} (modelo ajustado a ${candidate})`,
            latencyMs: Date.now() - start,
            resolvedConfig: retryCfg,
            availableModels: models.slice(0, 30),
          };
        } catch {}
      }
    } catch {}
    throw primaryErr;
  }
}
