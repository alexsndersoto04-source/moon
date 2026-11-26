'use strict';

/*
 * Multi-LLM Free Provider Pool, Catalog Auto-Discovery, Anti-429 Resilience,
 * Smart Routing & Instant Multi-Provider Failover Engine.
 */

const OPENROUTER_FALLBACK_MODELS = [
  'openrouter/free',
  'meta-llama/llama-3.3-70b-instruct:free',
  'mistralai/mistral-small-3.1-24b-instruct:free',
  'google/gemma-3-27b-it:free',
  'qwen/qwen3-coder:free'
];

const PROVIDER_PRESETS = [
  {
    id: 'groq-free',
    name: 'Groq Cloud (Gratis Ultrarrápido)',
    baseUrl: 'https://api.groq.com/openai/v1',
    defaultModel: 'llama-3.3-70b-versatile',
    suggestedModels: ['llama-3.3-70b-versatile', 'openai/gpt-oss-120b', 'qwen/qwen3-32b', 'llama-3.1-8b-instant'],
    tier: 'Gratis sin tarjeta · Muy rápido',
    recommended: true,
    requiresKey: true,
    docsUrl: 'https://console.groq.com/keys',
    description: 'Inferencia LPU ultrarrápida gratuita (1K–14K req/día según modelo). Ideal como motor principal para evitar 429.'
  },
  {
    id: 'gemini-free',
    name: 'Google Gemini (AI Studio Gratis)',
    baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai',
    defaultModel: 'gemini-2.5-flash',
    suggestedModels: ['gemini-2.5-flash', 'gemini-2.5-flash-lite', 'gemini-2.5-pro'],
    tier: 'Gratis sin tarjeta · Gran contexto',
    recommended: true,
    requiresKey: true,
    docsUrl: 'https://aistudio.google.com/apikey',
    description: 'Ventana de contexto masiva gratuita directa de Google AI Studio. Excelente para planificar y revisar proyectos enteros.'
  },
  {
    id: 'nvidia-nim-free',
    name: 'NVIDIA NIM (build.nvidia.com Gratis)',
    baseUrl: 'https://integrate.api.nvidia.com/v1',
    defaultModel: 'meta/llama-3.3-70b-instruct',
    suggestedModels: ['meta/llama-3.3-70b-instruct', 'qwen/qwen2.5-coder-32b-instruct', 'deepseek-ai/deepseek-r1', 'mistralai/mistral-small-24b-instruct'],
    tier: 'Gratis sin tarjeta · Muchos modelos',
    recommended: true,
    requiresKey: true,
    docsUrl: 'https://build.nvidia.com/explore/discover',
    description: 'Acceso gratuito a Llama 3.3, Qwen Coder 32B y DeepSeek R1 desde el catálogo oficial de NVIDIA.'
  },
  {
    id: 'mistral-free',
    name: 'Mistral AI (Plan Experiment Gratis)',
    baseUrl: 'https://api.mistral.ai/v1',
    defaultModel: 'mistral-small-latest',
    suggestedModels: ['mistral-small-latest', 'codestral-latest', 'devstral-small-latest', 'open-mistral-nemo'],
    tier: 'Gratis sin tarjeta · Ideal código',
    recommended: true,
    requiresKey: true,
    docsUrl: 'https://console.mistral.ai/api-keys',
    description: 'Plan Experiment gratuito con Mistral Small, Codestral y Devstral especializados en programación.'
  },
  {
    id: 'cerebras-free',
    name: 'Cerebras Cloud (Gratis Alta Velocidad)',
    baseUrl: 'https://api.cerebras.ai/v1',
    defaultModel: 'llama-3.3-70b',
    suggestedModels: ['llama-3.3-70b', 'qwen-3-32b', 'gpt-oss-120b', 'llama3.1-8b'],
    tier: 'Gratis · Throughput extremo',
    requiresKey: true,
    docsUrl: 'https://cloud.cerebras.ai/',
    description: 'Generación ultrarrápida compatible con OpenAI para escuadrones paralelos.'
  },
  {
    id: 'github-models',
    name: 'GitHub Models (Gratis con Token GitHub)',
    baseUrl: 'https://models.github.ai/inference',
    defaultModel: 'openai/gpt-4o-mini',
    suggestedModels: ['openai/gpt-4o-mini', 'meta/llama-3.3-70b-instruct', 'deepseek/deepseek-r1', 'mistral-ai/codestral-2501'],
    tier: 'Gratis con cuenta GitHub',
    requiresKey: true,
    docsUrl: 'https://github.com/settings/tokens',
    description: 'Usa un Personal Access Token de GitHub (permiso models:read) para acceder gratis a GPT-4o-mini, Llama y DeepSeek.'
  },
  {
    id: 'openrouter-free',
    name: 'OpenRouter (Auto-Router :free)',
    baseUrl: 'https://openrouter.ai/api/v1',
    defaultModel: 'openrouter/free',
    suggestedModels: OPENROUTER_FALLBACK_MODELS,
    tier: 'Gratis (50 req/día por cuenta)',
    requiresKey: true,
    docsUrl: 'https://openrouter.ai/keys',
    description: 'Usa "openrouter/free" para enrutamiento automático. Nota: OpenRouter limita :free a 50 req/día y 20/min por cuenta; combínalo con Groq, Gemini o NVIDIA.',
    extraHeaders: { 'HTTP-Referer': 'https://strata-engineering.local', 'X-Title': 'Strata Engineering 150 Agents' }
  },
  {
    id: 'cohere-free',
    name: 'Cohere (Trial API Gratis)',
    baseUrl: 'https://api.cohere.ai/compatibility/v1',
    defaultModel: 'command-a-03-2025',
    suggestedModels: ['command-a-03-2025', 'command-r-plus-08-2024', 'command-r7b-12-2024'],
    tier: 'Gratis sin tarjeta (Trial)',
    requiresKey: true,
    docsUrl: 'https://dashboard.cohere.com/api-keys',
    description: 'API Trial gratuita compatible con OpenAI para arquitectura, razonamiento y código.'
  },
  {
    id: 'huggingface-free',
    name: 'Hugging Face Router (Gratis HF Token)',
    baseUrl: 'https://router.huggingface.co/v1',
    defaultModel: 'Qwen/Qwen2.5-Coder-32B-Instruct',
    suggestedModels: ['Qwen/Qwen2.5-Coder-32B-Instruct', 'meta-llama/Llama-3.3-70B-Instruct', 'deepseek-ai/DeepSeek-R1'],
    tier: 'Gratis (HF Token)',
    requiresKey: true,
    docsUrl: 'https://huggingface.co/settings/tokens',
    description: 'Router oficial OpenAI-compatible hacia modelos open-source de programación.'
  },
  {
    id: 'sambanova-free',
    name: 'SambaNova Cloud (Free Tier)',
    baseUrl: 'https://api.sambanova.ai/v1',
    defaultModel: 'Meta-Llama-3.3-70B-Instruct',
    suggestedModels: ['Meta-Llama-3.3-70B-Instruct', 'Qwen2.5-Coder-32B-Instruct', 'DeepSeek-R1-Distill-Llama-70B'],
    tier: 'Gratis sin tarjeta',
    requiresKey: true,
    docsUrl: 'https://cloud.sambanova.ai/apis',
    description: 'Modelos Llama 3.3 y Qwen Coder gratuitos de respaldo.'
  },
  {
    id: 'cloudflare-free',
    name: 'Cloudflare Workers AI (Gratis 10K/día)',
    baseUrl: 'https://api.cloudflare.com/client/v4/accounts/TU_ACCOUNT_ID/ai/v1',
    defaultModel: '@cf/meta/llama-3.3-70b-instruct-fp8-fast',
    suggestedModels: ['@cf/meta/llama-3.3-70b-instruct-fp8-fast', '@cf/qwen/qwen2.5-coder-32b-instruct', '@cf/deepseek-ai/deepseek-r1-distill-qwen-32b'],
    tier: 'Gratis (10K neuronas/día)',
    requiresKey: true,
    docsUrl: 'https://dash.cloudflare.com/',
    description: 'Reemplaza TU_ACCOUNT_ID en la URL por tu ID de cuenta de Cloudflare y pega tu API Token.'
  },
  {
    id: 'ollama-local',
    name: 'Ollama / LM Studio (Local Sin Límites)',
    baseUrl: 'http://127.0.0.1:11434/v1',
    defaultModel: 'qwen2.5-coder:14b',
    suggestedModels: ['qwen2.5-coder:14b', 'qwen2.5-coder:7b', 'llama3.2:latest'],
    tier: '100% Local Sin 429',
    requiresKey: false,
    docsUrl: 'https://ollama.com',
    description: 'Ejecución local sin límites de cuota ni API key externa.'
  },
  {
    id: 'custom-openai',
    name: 'Proveedor OpenAI-Compatible Personalizado',
    baseUrl: 'https://api.openai.com/v1',
    defaultModel: '',
    suggestedModels: [],
    tier: 'Personalizado',
    requiresKey: true,
    docsUrl: '',
    description: 'Conecta cualquier API compatible con /chat/completions y /models (DeepSeek, Together, Fireworks, Chutes, etc.).'
  }
];

// Endpoints that already expose /chat/completions directly without appending /v1
const NO_V1_SUFFIX_PATTERNS = [
  /generativelanguage\.googleapis\.com\/v\d+(?:beta\d*)?\/openai$/i,
  /models\.github\.ai\/inference$/i,
  /models\.inference\.ai\.azure\.com$/i,
  /\/openai$/i
];

function stripEndpoint(url) {
  let base = String(url || '').trim().replace(/\/+$/, '');
  if (!base) base = 'https://api.openai.com/v1';
  base = base.replace(/\/(?:models|chat\/completions)$/i, '');
  const skipV1 = NO_V1_SUFFIX_PATTERNS.some((re) => re.test(base));
  if (!skipV1 && !/\/v\d+(?:beta\d*)?$/i.test(base)) base += '/v1';
  return base;
}

function normalizeBase(url) {
  return stripEndpoint(url) + '/chat/completions';
}

function normalizeModelsUrl(url) {
  let raw = String(url || '').trim().replace(/\/+$/, '');
  if (!raw) raw = 'https://api.openai.com/v1';
  if (/\/models$/i.test(raw)) return raw;
  if (/models\.github\.ai(?:\/inference)?$/i.test(raw)) return 'https://models.github.ai/catalog/models';
  return stripEndpoint(raw) + '/models';
}

function isOpenRouterUrl(url) {
  return /openrouter\.ai/i.test(String(url || ''));
}

function isLocalProviderUrl(url) {
  try {
    const host = new URL(String(url || '')).hostname.toLowerCase();
    return host === 'localhost' || host === '127.0.0.1' || host === '::1' || host === '0.0.0.0' || /^192\.168\./.test(host) || /^10\./.test(host);
  } catch {
    return false;
  }
}

function cleanApiKey(raw) {
  return String(raw || '')
    .trim()
    .replace(/^["'`]+|["'`]+$/g, '')
    .replace(/^Bearer\s+/i, '')
    .replace(/[\s\u200B-\u200D\uFEFF\r\n]+/g, '');
}

const NON_CHAT_MODEL_RE = /embed|whisper|tts|dall-e|imagen|veo|moderation|guard|audio|rerank|transcribe|realtime|speech|aqa|babbage|davinci|ocr|vision-preview/i;

function pickLiveChatCandidates(models = [], excludeModel = '', baseUrl = '') {
  const isOR = isOpenRouterUrl(baseUrl);
  const pool = models
    .filter((m) => m && m.id && m.id !== excludeModel && !NON_CHAT_MODEL_RE.test(m.id))
    .filter((m) => !isOR || m.free || /:free$/i.test(m.id) || m.id === 'openrouter/free');
  const score = (id) => {
    let s = 0;
    if (id === 'openrouter/free') s += 100;
    if (/gemini.*flash/i.test(id) && !/thinking|lite-preview|exp/i.test(id)) s += 90;
    if (/llama-3\.3-70b|gpt-oss-120b|qwen.*coder|deepseek-r1|deepseek-v|mistral-small|nemotron.*super/i.test(id)) s += 80;
    if (/llama-3\.1-8b|flash-lite|gemma-3|qwen-3|qwen3/i.test(id)) s += 65;
    if (/instruct|versatile|chat|latest/i.test(id)) s += 20;
    if (/preview|exp|beta/i.test(id)) s -= 15;
    return s;
  };
  return pool
    .sort((a, b) => score(b.id) - score(a.id) || a.id.localeCompare(b.id))
    .map((m) => m.id);
}

function parseRetryAfterSec(headers) {
  if (!headers) return 0;
  const retryAfter = headers.get?.('retry-after');
  if (retryAfter) {
    const num = Number(retryAfter);
    if (Number.isFinite(num) && num > 0) return Math.min(Math.ceil(num), 120);
    const ts = Date.parse(retryAfter);
    if (Number.isFinite(ts)) {
      const diff = Math.ceil((ts - Date.now()) / 1000);
      if (diff > 0) return Math.min(diff, 120);
    }
  }
  const reset = headers.get?.('x-ratelimit-reset');
  if (reset) {
    const num = Number(reset);
    if (Number.isFinite(num) && num > 0) {
      const resetMs = num > 1e12 ? num : num > 1e9 ? num * 1000 : Date.now() + num * 1000;
      const diff = Math.ceil((resetMs - Date.now()) / 1000);
      if (diff > 0 && diff <= 300) return diff;
    }
  }
  return 0;
}

function providerError(raw, status, url, headers = null) {
  let payload = {};
  try { payload = JSON.parse(raw); } catch { /* provider returned plain text */ }
  const details = payload?.error || payload || {};
  const baseMessage = typeof details === 'string'
    ? details
    : String(details.message || payload?.message || '');
  const upstreamRaw = typeof details === 'object' ? String(details.metadata?.raw || details.metadata?.provider_name || '') : '';
  const combinedDetail = [baseMessage, upstreamRaw].filter(Boolean).join(' — ').slice(0, 360);
  const code = String(typeof details === 'object'
    ? (details.code || details.type || payload?.code || '')
    : '');
  const hay = `${code} ${baseMessage} ${upstreamRaw}`;

  const quotaError = /insufficient_quota|credit_balance_exhausted|no credits remaining|negative balance/i.test(hay);
  const dailyLimit = status === 429 && /free-models-per-day|requests per day|tokens per day|daily limit|\bRPD\b|\bTPD\b|per-day/i.test(hay);
  const upstreamCongested = status === 429 && !dailyLimit && /upstream|temporarily rate-limited|provider returned error|overloaded|capacity/i.test(hay);
  const dataPolicyBlocked = isOpenRouterUrl(url) && /data policy|free model publication/i.test(hay);
  const modelNotFound = !dataPolicyBlocked && (
    status === 404
    || (status === 400 && /model.*not found|does not exist|decommissioned|deprecated|invalid model|unknown model|not supported/i.test(hay))
  );
  const authError = status === 401 || status === 403;
  const retryAfterSec = status === 429 ? parseRetryAfterSec(headers) : 0;

  let friendly;
  if (quotaError) {
    friendly = 'La API indica que esta clave no tiene saldo/créditos disponibles. Si es una clave de OpenAI, cambiar a otro modelo de OpenAI no lo soluciona: recarga saldo o cambia a otro proveedor usando una API key de ese proveedor. Los modelos GRATIS solo aplican en proveedores que los ofrezcan.';
  } else if (dataPolicyBlocked) {
    friendly = `OpenRouter bloqueó los modelos :free por tu política de privacidad. Abre https://openrouter.ai/settings/privacy y activa "Enable free endpoints that may publish prompts", luego vuelve a pulsar Probar.${combinedDetail ? ` [Detalle: ${combinedDetail}]` : ''}`;
  } else if (authError) {
    friendly = `Clave API inválida, expirada o sin permisos en este proveedor (verifica que copiaste la clave correcta para esta URL y sin espacios).${combinedDetail ? ` [Detalle: ${combinedDetail}]` : ''}`;
  } else if (dailyLimit) {
    const extra = isOpenRouterUrl(url)
      ? ' OpenRouter limita los modelos :free a 50 solicitudes/día por cuenta (crear otra clave en la misma cuenta comparte ese tope). Agrega otro proveedor gratuito como Groq, Google Gemini, NVIDIA NIM o Mistral en el Pool LLM.'
      : ' Alcanzaste el tope diario gratuito de este proveedor. Agrega otros proveedores gratuitos (Groq, Gemini, NVIDIA NIM, Mistral) para failover automático.';
    friendly = `Límite diario gratuito alcanzado (HTTP 429).${extra}${combinedDetail ? ` [Detalle: ${combinedDetail}]` : ''}`;
  } else if (status === 429) {
    const detailSuffix = combinedDetail ? ` [Detalle: ${combinedDetail}]` : '';
    friendly = `El proveedor aplicó un límite de solicitudes temporal (HTTP 429); este código por sí solo no indica falta de saldo. Agrega más proveedores gratuitos (Groq, Gemini, NVIDIA NIM, Mistral) en el Pool LLM para failover instantáneo.${detailSuffix}`;
  } else {
    friendly = combinedDetail || raw.slice(0, 500) || `Error HTTP ${status}`;
  }

  const error = new Error(`HTTP ${status} del proveedor: ${friendly}${url ? ` (${url})` : ''}`);
  error.status = status;
  error.providerCode = code;
  error.rawDetail = combinedDetail;
  error.isQuota = quotaError;
  error.isDailyLimit = dailyLimit;
  error.isUpstreamCongested = upstreamCongested;
  error.isDataPolicyBlocked = dataPolicyBlocked;
  error.isModelNotFound = modelNotFound;
  error.isAuthError = authError;
  error.isRateLimit = status === 429 && !quotaError;
  error.retryAfterSec = retryAfterSec;
  error.retryable = status === 429 || status === 402 || status >= 500;
  return error;
}

function freeModel(item, id) {
  const pricing = item?.pricing;
  const isZeroPrice = (value) => {
    if (typeof value !== 'number' && typeof value !== 'string') return false;
    if (typeof value === 'string' && !value.trim()) return false;
    const amount = Number(value);
    return Number.isFinite(amount) && amount === 0;
  };
  const inputPrice = pricing?.prompt ?? pricing?.input;
  const outputPrice = pricing?.completion ?? pricing?.output;
  const zeroPricing = pricing && isZeroPrice(inputPrice) && isZeroPrice(outputPrice);
  const advertisedFree = [item?.free, item?.is_free, item?.metadata?.free]
    .some((value) => value === true || value === 'true');
  return advertisedFree || /:free$/i.test(id) || id === 'openrouter/free' || Boolean(zeroPricing);
}

async function listModels(config = {}) {
  const url = normalizeModelsUrl(config.baseUrl);
  let parsed;
  try { parsed = new URL(url); }
  catch { throw Object.assign(new Error('La URL base del proveedor no es válida.'), { status: 400 }); }
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw Object.assign(new Error('La URL del proveedor debe usar HTTP o HTTPS.'), { status: 400 });
  }
  const headers = { Accept: 'application/json', ...(config.headers && typeof config.headers === 'object' ? config.headers : {}) };
  const apiKey = cleanApiKey(config.apiKey);
  if (apiKey) headers.Authorization = 'Bearer ' + apiKey;
  if (/models\.github\.ai/i.test(parsed.hostname)) {
    headers['X-GitHub-Api-Version'] = '2022-11-28';
  }
  const controller = new AbortController();
  const timeoutMs = Math.min(Math.max(Number(config.requestTimeout) || 30000, 5000), 90000);
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let response;
  let raw;
  try {
    response = await fetch(url, { method: 'GET', headers, signal: controller.signal });
    raw = await response.text();
  } catch (error) {
    const message = error.name === 'AbortError'
      ? `Tiempo límite al consultar el catálogo de modelos (${url}).`
      : `No se pudo conectar con el catálogo de modelos en ${url}: ${error.message}`;
    throw Object.assign(new Error(message), { status: 502, code: 'PROVIDER_CONNECTION_FAILED' });
  } finally { clearTimeout(timer); }
  if (!response.ok) throw providerError(raw, response.status, url, response.headers);
  let payload;
  try { payload = JSON.parse(raw); }
  catch { throw Object.assign(new Error('El endpoint de modelos no devolvió JSON válido.'), { status: 502 }); }
  const source = Array.isArray(payload)
    ? payload
    : Array.isArray(payload?.data) ? payload.data
      : Array.isArray(payload?.models) ? payload.models : [];
  const isGemini = /generativelanguage\.googleapis\.com/i.test(parsed.hostname);
  const models = source.map((item) => {
    let id = typeof item === 'string' ? item : String(item?.id || item?.name || '');
    if (!id) return null;
    if (isGemini) id = id.replace(/^models\//i, '');
    return { id, free: typeof item === 'object' && item ? freeModel(item, id) : /:free$/i.test(id) };
  }).filter(Boolean);
  if (isOpenRouterUrl(url) && !models.some((m) => m.id === 'openrouter/free')) {
    models.unshift({ id: 'openrouter/free', free: true });
  }
  const byId = new Map();
  for (const model of models) {
    const existing = byId.get(model.id);
    byId.set(model.id, { id: model.id, free: model.free || Boolean(existing?.free) });
  }
  const unique = [...byId.values()]
    .sort((a, b) => Number(b.free) - Number(a.free) || (a.id === 'openrouter/free' ? -1 : b.id === 'openrouter/free' ? 1 : a.id.localeCompare(b.id)))
    .slice(0, 1000);
  return { models: unique, freeCount: unique.filter((model) => model.free).length, source: url };
}

function sanitizeMessagesForProvider(messages, useTools) {
  if (!Array.isArray(messages)) return [];
  const rawClean = [];
  let expectedToolIds = null;

  for (const m of messages) {
    if (!m || !m.role) continue;
    if (!useTools && m.role === 'tool') {
      rawClean.push({ role: 'user', content: `Resultado de herramienta:\n${String(m.content || '')}` });
      expectedToolIds = null;
      continue;
    }
    if (m.role === 'assistant' && Array.isArray(m.tool_calls) && m.tool_calls.length) {
      if (useTools) {
        expectedToolIds = new Set(m.tool_calls.map((c) => String(c.id || '')));
        rawClean.push({ role: 'assistant', content: m.content == null ? '' : String(m.content), tool_calls: m.tool_calls });
      } else if (m.content) {
        expectedToolIds = null;
        rawClean.push({ role: 'assistant', content: String(m.content) });
      }
      continue;
    }
    if (m.role === 'tool') {
      const id = String(m.tool_call_id || '');
      if (expectedToolIds && expectedToolIds.has(id)) {
        rawClean.push({ role: 'tool', tool_call_id: id, content: String(m.content || '') });
      } else {
        rawClean.push({ role: 'user', content: `Resultado previo:\n${String(m.content || '')}` });
      }
      continue;
    }
    expectedToolIds = null;
    const content = String(m.content || '').trim();
    if (!content) continue;
    rawClean.push({ role: m.role, content });
  }

  // Merge consecutive user/user or assistant/assistant turns (strictly required by Gemini, Mistral & Cohere)
  const merged = [];
  for (const msg of rawClean) {
    const prev = merged[merged.length - 1];
    if (
      prev
      && prev.role === msg.role
      && (msg.role === 'user' || msg.role === 'assistant')
      && !prev.tool_calls
      && !msg.tool_calls
    ) {
      prev.content = `${prev.content}\n\n${msg.content}`;
    } else {
      merged.push(msg);
    }
  }
  return merged;
}

async function callLLM(config, messages, toolsOrFlag, signal) {
  if (!config.model || !String(config.model).trim()) throw new Error('Indica el nombre del modelo en Configuración del Pool LLM.');
  const url = normalizeBase(config.baseUrl);
  let parsed;
  try { parsed = new URL(url); } catch { throw new Error('La URL base del proveedor no es válida.'); }
  if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error('La URL del proveedor debe usar HTTP o HTTPS.');
  const headers = { 'Content-Type': 'application/json', ...(config.headers && typeof config.headers === 'object' ? config.headers : {}) };
  if (/openrouter\.ai/i.test(parsed.hostname)) {
    headers['HTTP-Referer'] = headers['HTTP-Referer'] || 'https://strata-engineering.local';
    headers['X-Title'] = headers['X-Title'] || 'Strata Engineering 150 Agents';
  }
  if (/models\.github\.ai/i.test(parsed.hostname)) {
    headers['X-GitHub-Api-Version'] = headers['X-GitHub-Api-Version'] || '2022-11-28';
  }
  const apiKey = cleanApiKey(config.apiKey);
  if (apiKey) headers.Authorization = 'Bearer ' + apiKey;

  const hasTools = Array.isArray(toolsOrFlag) && toolsOrFlag.length > 0;
  const body = {
    model: String(config.model),
    messages: sanitizeMessagesForProvider(messages, hasTools),
    temperature: config.temperature == null ? 0.2 : Math.min(Math.max(Number(config.temperature), 0), 2)
  };
  if (hasTools) {
    body.tools = toolsOrFlag;
    body.tool_choice = 'auto';
  }
  if (config.maxTokens) body.max_tokens = Math.min(Math.max(Number(config.maxTokens) || 0, 1), 65536);

  const controller = new AbortController();
  const timeoutMs = Math.min(Math.max(Number(config.requestTimeout) || 120000, 5000), 300000);
  const timer = setTimeout(() => controller.abort(new Error('timeout')), timeoutMs);
  const onAbort = () => controller.abort(signal.reason);
  signal?.addEventListener('abort', onAbort, { once: true });
  if (signal?.aborted) controller.abort(signal.reason);

  const started = Date.now();
  let response;
  let raw;
  try {
    response = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body), signal: controller.signal });
    raw = await response.text();
  } catch (error) {
    if (signal?.aborted) throw new Error('La solicitud del agente fue cancelada.');
    const err = new Error(error.name === 'AbortError' ? `Tiempo límite excedido al conectar con ${url}.` : `No se pudo completar la solicitud a ${url}: ${error.message}`);
    err.retryable = true;
    throw err;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
  if (!response.ok) throw providerError(raw, response.status, url, response.headers);
  let data;
  try { data = JSON.parse(raw); } catch { throw new Error('El proveedor devolvió una respuesta que no es JSON: ' + raw.slice(0, 400)); }
  const choice = data.choices?.[0];
  if (!choice) throw new Error('El proveedor no devolvió choices: ' + raw.slice(0, 400));
  return {
    message: choice.message || {},
    usage: data.usage || null,
    finish: choice.finish_reason,
    latencyMs: Date.now() - started
  };
}

/* --------------------- Multi-Provider Pool, Anti-429 & Telemetry -------------------- */

const providerMetrics = new Map();
const providerInFlight = new Map();
let roundRobinCursor = 0;

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(new Error('La solicitud del agente fue cancelada.'));
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(new Error('La solicitud del agente fue cancelada.'));
    };
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

async function withProviderSlot(key, maxConcurrent, signal, fn) {
  let slot = providerInFlight.get(key);
  if (!slot) {
    slot = { active: 0, queue: [] };
    providerInFlight.set(key, slot);
  }
  if (slot.active >= maxConcurrent) {
    await new Promise((resolve, reject) => {
      const entry = { resolve, reject };
      const onAbort = () => {
        const idx = slot.queue.indexOf(entry);
        if (idx >= 0) slot.queue.splice(idx, 1);
        reject(new Error('La solicitud del agente fue cancelada.'));
      };
      if (signal) {
        if (signal.aborted) return reject(new Error('La solicitud del agente fue cancelada.'));
        signal.addEventListener('abort', onAbort, { once: true });
        entry.cleanup = () => signal.removeEventListener('abort', onAbort);
      }
      slot.queue.push(entry);
    });
  }
  slot.active++;
  try {
    return await fn();
  } finally {
    slot.active--;
    const next = slot.queue.shift();
    if (next) {
      next.cleanup?.();
      next.resolve();
    }
  }
}

function metricFor(provider) {
  const key = String(provider.id || `${provider.baseUrl}|${provider.model}`);
  let m = providerMetrics.get(key);
  if (!m) {
    m = {
      id: key,
      name: provider.name || provider.model || 'Proveedor',
      model: provider.model || '',
      baseUrl: provider.baseUrl || '',
      status: 'ready',
      requests: 0,
      successes: 0,
      errors: 0,
      rateLimits: 0,
      failovers: 0,
      totalTokens: 0,
      avgLatencyMs: 0,
      cooldownUntil: 0,
      lastError: '',
      lastUsedAt: 0
    };
    providerMetrics.set(key, m);
  } else {
    m.name = provider.name || m.name;
    m.model = provider.model || m.model;
    m.baseUrl = provider.baseUrl || m.baseUrl;
  }
  return m;
}

function normalizePool(poolOrConfig) {
  if (!poolOrConfig) return { providers: [], strategy: 'round-robin', divisionMap: {}, agentMap: {}, defaults: {} };
  if (!Array.isArray(poolOrConfig.providers)) {
    const key = cleanApiKey(poolOrConfig.apiKey);
    const base = poolOrConfig.baseUrl || 'https://api.openai.com/v1';
    const single = (poolOrConfig.model && (key || isLocalProviderUrl(base))) ? [{
      id: poolOrConfig.id || 'primary',
      name: poolOrConfig.name || 'Proveedor principal',
      baseUrl: base,
      model: String(poolOrConfig.model).trim(),
      apiKey: key,
      enabled: true,
      priority: 1,
      temperature: poolOrConfig.temperature,
      maxTokens: poolOrConfig.maxTokens,
      tools: poolOrConfig.tools !== false
    }] : [];
    return {
      providers: single,
      strategy: 'priority-failover',
      divisionMap: {},
      agentMap: {},
      defaults: {
        temperature: poolOrConfig.temperature ?? 0.2,
        maxSteps: poolOrConfig.maxSteps || 18,
        maxTokens: poolOrConfig.maxTokens || 0,
        tools: poolOrConfig.tools !== false,
        requestTimeout: poolOrConfig.requestTimeout || 120000
      }
    };
  }
  const active = poolOrConfig.providers
    .map((p, idx) => {
      if (!p || p.enabled === false || !p.model || !String(p.model).trim()) return null;
      const baseUrl = p.baseUrl || 'https://api.openai.com/v1';
      const apiKey = cleanApiKey(p.apiKey);
      // Skip remote placeholder cards that don't have an API key pasted yet!
      if (!apiKey && !isLocalProviderUrl(baseUrl)) return null;
      return {
        id: p.id || `prov-${idx + 1}`,
        name: p.name || p.model,
        baseUrl,
        model: String(p.model).trim(),
        apiKey,
        priority: Number(p.priority) || (idx + 1),
        temperature: p.temperature,
        maxTokens: p.maxTokens,
        tools: p.tools !== false,
        headers: p.headers
      };
    })
    .filter(Boolean);
  return {
    providers: active,
    strategy: poolOrConfig.strategy || 'round-robin',
    divisionMap: poolOrConfig.divisionMap || {},
    agentMap: poolOrConfig.agentMap || {},
    defaults: {
      temperature: poolOrConfig.temperature ?? 0.2,
      maxSteps: poolOrConfig.maxSteps || 20,
      maxTokens: poolOrConfig.maxTokens || 0,
      tools: poolOrConfig.tools !== false,
      requestTimeout: poolOrConfig.requestTimeout || 120000
    }
  };
}

function selectProviderOrder(pool, context = {}) {
  const candidates = [...pool.providers];
  if (!candidates.length) return [];
  const now = Date.now();

  const pinnedId = (context.agentId && pool.agentMap?.[context.agentId])
    || (context.divisionId && pool.divisionMap?.[context.divisionId])
    || context.preferredProviderId;

  const ready = [];
  const cooling = [];
  for (const p of candidates) {
    const m = metricFor(p);
    if (m.cooldownUntil > now) cooling.push(p);
    else ready.push(p);
  }

  const sortBucket = (bucket) => {
    if (pool.strategy === 'priority-failover') {
      return bucket.sort((a, b) => a.priority - b.priority);
    }
    if (pool.strategy === 'least-used') {
      return bucket.sort((a, b) => {
        const ma = metricFor(a);
        const mb = metricFor(b);
        return (ma.requests - mb.requests) || (ma.avgLatencyMs - mb.avgLatencyMs);
      });
    }
    if (bucket.length <= 1) return bucket;
    const offset = roundRobinCursor++ % bucket.length;
    return [...bucket.slice(offset), ...bucket.slice(0, offset)];
  };

  // Sort cooling bucket by soonest cooldown expiry so transient 429s beat exhausted daily caps!
  cooling.sort((a, b) => metricFor(a).cooldownUntil - metricFor(b).cooldownUntil);

  const ordered = [...sortBucket(ready), ...cooling];
  if (pinnedId) {
    const idx = ordered.findIndex((p) => p.id === pinnedId);
    if (idx > 0 && metricFor(ordered[idx]).cooldownUntil <= now) {
      const [pinned] = ordered.splice(idx, 1);
      ordered.unshift(pinned);
    }
  }
  return ordered;
}

const liveModelsCache = new Map();
const LIVE_MODELS_TTL = 5 * 60 * 1000;

async function discoverFallbackModels(provider, excludeModel) {
  const cacheKey = `${stripEndpoint(provider.baseUrl)}|${cleanApiKey(provider.apiKey).slice(-8)}`;
  const cached = liveModelsCache.get(cacheKey);
  let models = cached && (Date.now() - cached.ts < LIVE_MODELS_TTL) ? cached.models : null;
  if (!models) {
    try {
      const res = await listModels({ ...provider, requestTimeout: 15000 });
      models = res.models || [];
      liveModelsCache.set(cacheKey, { ts: Date.now(), models });
    } catch {
      models = [];
    }
  }
  const picked = pickLiveChatCandidates(models, excludeModel, provider.baseUrl);
  if (isOpenRouterUrl(provider.baseUrl)) {
    for (const fallback of OPENROUTER_FALLBACK_MODELS) {
      if (fallback !== excludeModel && !picked.includes(fallback)) picked.push(fallback);
    }
  }
  return picked.slice(0, 3);
}

async function callPoolLLM(poolOrConfig, messages, toolsList, options = {}) {
  const pool = normalizePool(poolOrConfig);
  if (!pool.providers.length) {
    throw Object.assign(new Error('No hay proveedores LLM con API key activa en el Pool. Pega al menos una clave gratuita en Herramientas y Config → APIs LLM Gratis.'), { status: 400 });
  }

  const maxTransientRounds = options.max429Retries != null ? Number(options.max429Retries) : 2;
  let lastError = null;

  for (let round = 0; round <= maxTransientRounds; round++) {
    const ordered = selectProviderOrder(pool, options);
    let anyTransientRateLimit = false;
    let shortestWaitMs = 0;

    for (let i = 0; i < ordered.length; i++) {
      const provider = ordered[i];
      const metric = metricFor(provider);
      const now = Date.now();

      if (round === 0 && (metric.status === 'daily_limit' || metric.status === 'quota_exhausted' || metric.status === 'auth_error') && metric.cooldownUntil > now && i < ordered.length - 1) {
        continue;
      }

      const callConfig = {
        ...pool.defaults,
        ...provider,
        temperature: provider.temperature ?? pool.defaults.temperature,
        maxTokens: provider.maxTokens || pool.defaults.maxTokens
      };
      const useTools = options.useTools !== false && callConfig.tools !== false ? toolsList : false;
      const slotLimit = metric.rateLimits > 0 ? 1 : 2;

      const attemptProvider = async (modelOverride = null, toolsForCall = useTools) => {
        metric.requests++;
        metric.lastUsedAt = Date.now();
        const cfg = modelOverride ? { ...callConfig, model: modelOverride } : callConfig;
        return withProviderSlot(metric.id, slotLimit, options.signal, () => callLLM(cfg, messages, toolsForCall, options.signal));
      };

      const recordSuccess = (res, modelUsed, label = provider.name) => {
        metric.successes++;
        metric.status = 'ready';
        metric.cooldownUntil = 0;
        metric.lastError = '';
        metric.avgLatencyMs = metric.avgLatencyMs
          ? Math.round((metric.avgLatencyMs * 0.7) + (res.latencyMs * 0.3))
          : res.latencyMs;
        if (res.usage?.total_tokens) metric.totalTokens += Number(res.usage.total_tokens) || 0;
        return { ...res, provider: { id: provider.id, name: label, model: modelUsed, baseUrl: provider.baseUrl } };
      };

      try {
        const res = await attemptProvider();
        return recordSuccess(res, provider.model);
      } catch (error) {
        lastError = error;
        if (options.signal?.aborted) throw error;

        const isToolUnsupported = Boolean(
          useTools && error.status && [400, 404, 422, 500].includes(error.status)
          && /tool|function|schema|additionalProperties|parameters|tool_choice|endpoint|unsupported/i.test(String(error.message))
        );

        // Immediately retry THIS provider WITHOUT native tools so models that dislike tool schemas STILL work!
        if (isToolUnsupported && !error.isDataPolicyBlocked) {
          try {
            const plainRes = await attemptProvider(null, false);
            return recordSuccess(plainRes, provider.model);
          } catch (plainErr) {
            lastError = plainErr;
          }
        }

        // Retired/deprecated model (404/400) on ANY provider OR congested OpenRouter :free model -> auto-discover live working models!
        const shouldAutoRecoverModel = !lastError.isDailyLimit && !lastError.isQuota && !lastError.isAuthError && !lastError.isDataPolicyBlocked && (
          lastError.isModelNotFound
          || (isOpenRouterUrl(provider.baseUrl) && lastError.isRateLimit && (/:free$/i.test(provider.model) || provider.model === 'openrouter/free'))
        );

        if (shouldAutoRecoverModel) {
          const candidates = await discoverFallbackModels(provider, provider.model);
          for (const altModel of candidates) {
            if (options.signal?.aborted) throw lastError;
            try {
              options.onFailover?.({
                from: { id: provider.id, name: provider.name, model: provider.model },
                to: { id: provider.id, name: provider.name, model: altModel },
                reason: `Modelo ${provider.model} no disponible (${lastError.status}) — usando modelo activo ${altModel}`
              });
              const altRes = await attemptProvider(altModel, false);
              return recordSuccess(altRes, altModel, `${provider.name} (auto)`);
            } catch (altErr) {
              lastError = altErr;
              if (altErr.isDailyLimit || altErr.isQuota || altErr.isAuthError || altErr.isDataPolicyBlocked) break;
            }
          }
        }

        metric.errors++;
        metric.lastError = lastError.message;
        if (lastError.isAuthError) {
          metric.status = 'auth_error';
          metric.cooldownUntil = Date.now() + 300000;
        } else if (lastError.isDailyLimit) {
          metric.rateLimits++;
          metric.status = 'daily_limit';
          metric.cooldownUntil = Date.now() + 900000;
        } else if (lastError.isQuota) {
          metric.status = 'quota_exhausted';
          metric.cooldownUntil = Date.now() + 600000;
        } else if (lastError.isRateLimit) {
          metric.rateLimits++;
          metric.status = 'rate_limited';
          const waitSec = lastError.retryAfterSec ? Math.min(Math.max(lastError.retryAfterSec, 2), 30) : 20;
          metric.cooldownUntil = Date.now() + waitSec * 1000;
          anyTransientRateLimit = true;
          const retryMs = Math.min(Math.max((lastError.retryAfterSec || (2 + round * 2)) * 1000, 1500), 6000);
          if (!shortestWaitMs || retryMs < shortestWaitMs) shortestWaitMs = retryMs;
        } else {
          metric.status = 'error';
          metric.cooldownUntil = Date.now() + 15000;
        }

        if (i < ordered.length - 1) {
          metric.failovers++;
          options.onFailover?.({
            from: { id: provider.id, name: provider.name, model: provider.model },
            to: { id: ordered[i + 1].id, name: ordered[i + 1].name, model: ordered[i + 1].model },
            reason: lastError.message
          });
          continue;
        }
      }
    }

    if (anyTransientRateLimit && round < maxTransientRounds && !options.signal?.aborted) {
      const delayMs = options.retryDelayMs != null ? Number(options.retryDelayMs) : (shortestWaitMs || (2200 * (round + 1)));
      const seconds = Math.max(1, Math.round(delayMs / 1000));
      options.onRetryWait?.({ round: round + 1, maxRounds: maxTransientRounds, seconds });
      if (delayMs > 0) await sleep(delayMs, options.signal);
      continue;
    }

    throw lastError || new Error('Todos los proveedores del pool fallaron.');
  }

  throw lastError || new Error('Todos los proveedores del pool fallaron.');
}

async function probe(config, toolsSample = null) {
  const apiKey = cleanApiKey(config.apiKey);
  if (!apiKey && !isLocalProviderUrl(config.baseUrl)) {
    throw Object.assign(new Error('Falta pegar tu API key en esta tarjeta antes de probar.'), { status: 400 });
  }
  const cleanCfg = { ...config, apiKey, maxTokens: 24, requestTimeout: 35000 };
  const message = [{ role: 'user', content: 'Responde solo: ok' }];
  try {
    // Always test with a tiny plain request (no bloated 24-tool JSON schema!) so Groq/Gemini/Mistral/OpenRouter connect reliably
    const result = await callLLM(cleanCfg, message, false);
    const reply = (result.message.content || '').trim();
    return { ok: true, model: cleanCfg.model, tools: true, latencyMs: result.latencyMs, reply: reply.slice(0, 200) };
  } catch (error) {
    // Auto-discover & switch to a live active model when the preset model is deprecated/404 OR OpenRouter :free model is temporarily 429
    const canAutoDiscover = !error.isAuthError && !error.isQuota && !error.isDailyLimit && !error.isDataPolicyBlocked && (
      error.isModelNotFound
      || (isOpenRouterUrl(cleanCfg.baseUrl) && error.isRateLimit)
    );
    if (canAutoDiscover) {
      const candidates = await discoverFallbackModels(cleanCfg, cleanCfg.model);
      for (const candidate of candidates) {
        try {
          const altResult = await callLLM({ ...cleanCfg, model: candidate }, message, false);
          const reply = (altResult.message.content || '').trim();
          return {
            ok: true,
            model: candidate,
            autoSwitchedFrom: cleanCfg.model,
            tools: true,
            latencyMs: altResult.latencyMs,
            reply: reply.slice(0, 200)
          };
        } catch (altErr) {
          if (altErr.isAuthError || altErr.isQuota || altErr.isDailyLimit || altErr.isDataPolicyBlocked) throw altErr;
        }
      }
    }
    throw error;
  }
}

async function testPool(providers = [], toolsSample = null) {
  const list = Array.isArray(providers) ? providers.slice(0, 25) : [];
  const results = await Promise.all(list.map(async (provider) => {
    const started = Date.now();
    if (!provider.model) {
      return { id: provider.id, name: provider.name, ok: false, error: 'Falta especificar el ID del modelo.' };
    }
    if (!cleanApiKey(provider.apiKey) && !isLocalProviderUrl(provider.baseUrl)) {
      return { id: provider.id, name: provider.name, model: provider.model, ok: false, skipped: true, error: 'Sin API key pegada (omitido del Pool activo).' };
    }
    try {
      const res = await probe(provider, null);
      const m = metricFor(provider);
      m.status = 'ready';
      m.cooldownUntil = 0;
      m.avgLatencyMs = res.latencyMs || (Date.now() - started);
      m.lastError = '';
      return { id: provider.id, name: provider.name, ...res };
    } catch (error) {
      const m = metricFor(provider);
      m.status = error.isAuthError ? 'auth_error' : error.isDailyLimit ? 'daily_limit' : error.isRateLimit ? 'rate_limited' : 'error';
      m.lastError = error.message;
      return { id: provider.id, name: provider.name, model: provider.model, ok: false, status: error.status || 500, error: error.message, latencyMs: Date.now() - started };
    }
  }));
  return {
    total: results.length,
    healthy: results.filter((r) => r.ok).length,
    results
  };
}

function getHealthSnapshot() {
  const now = Date.now();
  return [...providerMetrics.values()].map((m) => ({
    ...m,
    coolingDown: m.cooldownUntil > now,
    cooldownRemainingSec: m.cooldownUntil > now ? Math.ceil((m.cooldownUntil - now) / 1000) : 0
  }));
}

module.exports = {
  PROVIDER_PRESETS,
  OPENROUTER_FALLBACK_MODELS,
  cleanApiKey,
  isLocalProviderUrl,
  sanitizeMessagesForProvider,
  normalizeBase,
  normalizeModelsUrl,
  providerError,
  listModels,
  callLLM,
  normalizePool,
  callPoolLLM,
  probe,
  testPool,
  getHealthSnapshot
};
