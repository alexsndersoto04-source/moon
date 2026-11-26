'use strict';

/*
 * Multi-LLM Free Provider Pool, Catalog Auto-Discovery, Routing & Failover Engine.
 * Supports connecting multiple free OpenAI-compatible APIs simultaneously for 150 agents.
 */

const PROVIDER_PRESETS = [
  {
    id: 'openrouter-free',
    name: 'OpenRouter (Modelos Gratis :free)',
    baseUrl: 'https://openrouter.ai/api/v1',
    defaultModel: 'qwen/qwen3-coder:free',
    tier: 'Gratis (:free)',
    requiresKey: true,
    docsUrl: 'https://openrouter.ai/keys',
    description: 'Decenas de modelos gratuitos (:free) como Qwen Coder, DeepSeek, Llama 3.3 y Mistral. Pulsa "Buscar modelos" para ver los activos hoy.',
    extraHeaders: { 'HTTP-Referer': 'https://strata-engineering.local', 'X-Title': 'Strata Engineering 150 Agents' }
  },
  {
    id: 'groq-free',
    name: 'Groq Cloud (Free Tier Ultrarrápido)',
    baseUrl: 'https://api.groq.com/openai/v1',
    defaultModel: 'llama-3.3-70b-versatile',
    tier: 'Gratis (Alta velocidad)',
    requiresKey: true,
    docsUrl: 'https://console.groq.com/keys',
    description: 'Inferencia LPU de bajísima latencia ideal para programar, depurar y ejecutar flujos paralelos de múltiples agentes.'
  },
  {
    id: 'gemini-free',
    name: 'Google Gemini (AI Studio Free Tier)',
    baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai',
    defaultModel: 'gemini-2.5-flash',
    tier: 'Gratis (Gran contexto)',
    requiresKey: true,
    docsUrl: 'https://aistudio.google.com/apikey',
    description: 'Ventana de contexto masiva ideal para arquitectura, análisis de proyectos enormes y revisión de código.'
  },
  {
    id: 'cerebras-free',
    name: 'Cerebras Cloud (Free Tier)',
    baseUrl: 'https://api.cerebras.ai/v1',
    defaultModel: 'llama-3.3-70b',
    tier: 'Gratis (Alto throughput)',
    requiresKey: true,
    docsUrl: 'https://cloud.cerebras.ai/',
    description: 'Generación ultrarrápida gratuita compatible con OpenAI para escuadrones de desarrollo paralelo.'
  },
  {
    id: 'github-models',
    name: 'GitHub Models (Gratis con Token GitHub)',
    baseUrl: 'https://models.inference.ai.azure.com',
    defaultModel: 'gpt-4o-mini',
    tier: 'Gratis (GitHub PAT)',
    requiresKey: true,
    docsUrl: 'https://github.com/marketplace/models',
    description: 'Acceso gratuito a GPT-4o-mini, Llama, DeepSeek y Mistral usando un Personal Access Token de GitHub.'
  },
  {
    id: 'mistral-free',
    name: 'Mistral AI (Free Experiment Tier)',
    baseUrl: 'https://api.mistral.ai/v1',
    defaultModel: 'codestral-latest',
    tier: 'Gratis (Plan Experiment)',
    requiresKey: true,
    docsUrl: 'https://console.mistral.ai/api-keys',
    description: 'Modelos Codestral y Mistral Small especializados en ingeniería de software y refactorización.'
  },
  {
    id: 'sambanova-free',
    name: 'SambaNova Cloud (Free Tier)',
    baseUrl: 'https://api.sambanova.ai/v1',
    defaultModel: 'Meta-Llama-3.3-70B-Instruct',
    tier: 'Gratis',
    requiresKey: true,
    docsUrl: 'https://cloud.sambanova.ai/apis',
    description: 'Modelos Llama 3.3 y Qwen Coder gratuitos de alto rendimiento.'
  },
  {
    id: 'huggingface-free',
    name: 'Hugging Face Router (Free Inference)',
    baseUrl: 'https://router.huggingface.co/v1',
    defaultModel: 'Qwen/Qwen2.5-Coder-32B-Instruct',
    tier: 'Gratis (HF Token)',
    requiresKey: true,
    docsUrl: 'https://huggingface.co/settings/tokens',
    description: 'Router OpenAI-compatible hacia modelos open-source de programación.'
  },
  {
    id: 'ollama-local',
    name: 'Ollama / LM Studio (Local Sin Límites)',
    baseUrl: 'http://127.0.0.1:11434/v1',
    defaultModel: 'qwen2.5-coder:14b',
    tier: '100% Local Gratis',
    requiresKey: false,
    docsUrl: 'https://ollama.com',
    description: 'Ejecución local sin límites de cuota ni API key externa.'
  },
  {
    id: 'custom-openai',
    name: 'Proveedor OpenAI-Compatible Personalizado',
    baseUrl: 'https://api.openai.com/v1',
    defaultModel: '',
    tier: 'Personalizado',
    requiresKey: true,
    docsUrl: '',
    description: 'Conecta cualquier API compatible con /chat/completions y /models.'
  }
];

// Endpoints that already expose /chat/completions directly without appending /v1
const NO_V1_SUFFIX_PATTERNS = [
  /generativelanguage\.googleapis\.com\/v\d+(?:beta\d*)?\/openai$/i,
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
  return stripEndpoint(raw) + '/models';
}

function providerError(raw, status, url) {
  let payload = {};
  try { payload = JSON.parse(raw); } catch { /* provider returned plain text */ }
  const details = payload?.error || payload || {};
  const message = typeof details === 'string'
    ? details
    : String(details.message || payload?.message || '');
  const code = String(typeof details === 'object'
    ? (details.code || details.type || payload?.code || '')
    : '');
  const quotaError = /insufficient_quota|credit_balance_exhausted|no credits remaining/i.test(`${code} ${message}`);
  const friendly = quotaError
    ? 'La API indica que esta clave no tiene saldo/créditos disponibles. Si es una clave de OpenAI, cambiar a otro modelo de OpenAI no lo soluciona: recarga saldo o cambia a otro proveedor usando una API key de ese proveedor. Los modelos GRATIS solo aplican en proveedores que los ofrezcan.'
    : status === 429
      ? 'El proveedor aplicó un límite de solicitudes (HTTP 429); este código por sí solo no indica falta de saldo. Espera un momento o activa el failover multi-proveedor en el Pool LLM.'
      : message || raw.slice(0, 500) || `Error HTTP ${status}`;
  const error = new Error(`HTTP ${status} del proveedor: ${friendly}${url ? ` (${url})` : ''}`);
  error.status = status;
  error.providerCode = code;
  error.isQuota = quotaError;
  error.isRateLimit = status === 429 && !quotaError;
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
  return advertisedFree || /:free$/i.test(id) || Boolean(zeroPricing);
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
  if (config.apiKey) headers.Authorization = 'Bearer ' + String(config.apiKey);
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
  if (!response.ok) throw providerError(raw, response.status, url);
  let payload;
  try { payload = JSON.parse(raw); }
  catch { throw Object.assign(new Error('El endpoint de modelos no devolvió JSON válido.'), { status: 502 }); }
  const source = Array.isArray(payload)
    ? payload
    : Array.isArray(payload?.data) ? payload.data
      : Array.isArray(payload?.models) ? payload.models : [];
  const models = source.map((item) => {
    const id = typeof item === 'string' ? item : String(item?.id || item?.name || '');
    if (!id) return null;
    return { id, free: typeof item === 'object' && item ? freeModel(item, id) : /:free$/i.test(id) };
  }).filter(Boolean);
  const byId = new Map();
  for (const model of models) {
    const existing = byId.get(model.id);
    byId.set(model.id, { id: model.id, free: model.free || Boolean(existing?.free) });
  }
  const unique = [...byId.values()]
    .sort((a, b) => Number(b.free) - Number(a.free) || a.id.localeCompare(b.id))
    .slice(0, 1000);
  return { models: unique, freeCount: unique.filter((model) => model.free).length, source: url };
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
  if (config.apiKey) headers.Authorization = 'Bearer ' + String(config.apiKey);
  const body = {
    model: String(config.model),
    messages,
    temperature: config.temperature == null ? 0.2 : Math.min(Math.max(Number(config.temperature), 0), 2)
  };
  if (Array.isArray(toolsOrFlag) && toolsOrFlag.length) {
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
  if (!response.ok) throw providerError(raw, response.status, url);
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

/* --------------------- Multi-Provider Pool & Telemetry -------------------- */

const providerMetrics = new Map();
let roundRobinCursor = 0;

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
  // Legacy single-provider config compatibility
  if (!Array.isArray(poolOrConfig.providers)) {
    const single = poolOrConfig.model ? [{
      id: poolOrConfig.id || 'primary',
      name: poolOrConfig.name || 'Proveedor principal',
      baseUrl: poolOrConfig.baseUrl || 'https://api.openai.com/v1',
      model: poolOrConfig.model,
      apiKey: poolOrConfig.apiKey || '',
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
    .filter((p) => p && p.enabled !== false && p.model && String(p.model).trim())
    .map((p, idx) => ({
      id: p.id || `prov-${idx + 1}`,
      name: p.name || p.model,
      baseUrl: p.baseUrl || 'https://api.openai.com/v1',
      model: String(p.model).trim(),
      apiKey: p.apiKey || '',
      priority: Number(p.priority) || (idx + 1),
      temperature: p.temperature,
      maxTokens: p.maxTokens,
      tools: p.tools !== false,
      headers: p.headers
    }));
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

  // Check explicit per-agent or per-division pin
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
    // round-robin (default — spreads load cleanly across all free APIs!)
    if (bucket.length <= 1) return bucket;
    const offset = roundRobinCursor++ % bucket.length;
    return [...bucket.slice(offset), ...bucket.slice(0, offset)];
  };

  let ordered = [...sortBucket(ready), ...sortBucket(cooling)];
  if (pinnedId) {
    const idx = ordered.findIndex((p) => p.id === pinnedId);
    if (idx > 0) {
      const [pinned] = ordered.splice(idx, 1);
      ordered.unshift(pinned);
    }
  }
  return ordered;
}

async function callPoolLLM(poolOrConfig, messages, toolsList, options = {}) {
  const pool = normalizePool(poolOrConfig);
  const ordered = selectProviderOrder(pool, options);
  if (!ordered.length) {
    throw Object.assign(new Error('No hay proveedores LLM activos en el Pool. Agrega al menos un proveedor gratuito en Herramientas y Config → APIs LLM Gratis.'), { status: 400 });
  }

  let lastError = null;
  for (let i = 0; i < ordered.length; i++) {
    const provider = ordered[i];
    const metric = metricFor(provider);
    metric.requests++;
    metric.lastUsedAt = Date.now();
    const callConfig = {
      ...pool.defaults,
      ...provider,
      temperature: provider.temperature ?? pool.defaults.temperature,
      maxTokens: provider.maxTokens || pool.defaults.maxTokens
    };
    const useTools = options.useTools !== false && callConfig.tools !== false ? toolsList : false;

    try {
      const res = await callLLM(callConfig, messages, useTools, options.signal);
      metric.successes++;
      metric.status = 'ready';
      metric.cooldownUntil = 0;
      metric.lastError = '';
      metric.avgLatencyMs = metric.avgLatencyMs
        ? Math.round((metric.avgLatencyMs * 0.7) + (res.latencyMs * 0.3))
        : res.latencyMs;
      if (res.usage?.total_tokens) metric.totalTokens += Number(res.usage.total_tokens) || 0;
      return { ...res, provider: { id: provider.id, name: provider.name, model: provider.model, baseUrl: provider.baseUrl } };
    } catch (error) {
      lastError = error;
      if (options.signal?.aborted) throw error;

      // Tool-calling incompatibility on this provider -> let caller retry without native tools
      if (useTools && error.status && [400, 404, 422, 500].includes(error.status) && /tool|function/i.test(String(error.message))) {
        throw error;
      }

      metric.errors++;
      metric.lastError = error.message;
      if (error.isRateLimit) {
        metric.status = 'rate_limited';
        metric.cooldownUntil = Date.now() + 25000;
      } else if (error.isQuota) {
        metric.status = 'quota_exhausted';
        metric.cooldownUntil = Date.now() + 120000;
      } else {
        metric.status = 'error';
        metric.cooldownUntil = Date.now() + 15000;
      }

      if (i < ordered.length - 1) {
        metric.failovers++;
        options.onFailover?.({
          from: { id: provider.id, name: provider.name, model: provider.model },
          to: { id: ordered[i + 1].id, name: ordered[i + 1].name, model: ordered[i + 1].model },
          reason: error.message
        });
        continue;
      }
      throw error;
    }
  }
  throw lastError || new Error('Todos los proveedores del pool fallaron.');
}

async function probe(config, toolsSample = null) {
  const message = [{ role: 'user', content: 'Responde solo: ok' }];
  let tools = true;
  let result;
  try {
    result = await callLLM({ ...config, requestTimeout: 45000 }, message, toolsSample || false);
  } catch (error) {
    if (toolsSample && error.status && /tool|function/i.test(String(error.message))) {
      tools = false;
      result = await callLLM({ ...config, requestTimeout: 45000 }, message, false);
    } else throw error;
  }
  const reply = (result.message.content || '').trim();
  return { ok: true, model: config.model, tools, latencyMs: result.latencyMs, reply: reply.slice(0, 200) };
}

async function testPool(providers = [], toolsSample = null) {
  const list = Array.isArray(providers) ? providers.slice(0, 25) : [];
  const results = await Promise.all(list.map(async (provider) => {
    const started = Date.now();
    if (!provider.model) {
      return { id: provider.id, name: provider.name, ok: false, error: 'Falta especificar el ID del modelo.' };
    }
    try {
      const res = await probe(provider, toolsSample);
      const m = metricFor(provider);
      m.status = 'ready';
      m.avgLatencyMs = res.latencyMs || (Date.now() - started);
      m.lastError = '';
      return { id: provider.id, name: provider.name, ...res };
    } catch (error) {
      const m = metricFor(provider);
      m.status = error.isRateLimit ? 'rate_limited' : 'error';
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
