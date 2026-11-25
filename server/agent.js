'use strict';

/* OpenAI-compatible coding agent with real, workspace-confined tools. */
const fsx = require('./fsx');
const runner = require('./runner');

const tool = (name, description, properties, required = []) => ({
  type: 'function',
  function: {
    name, description,
    parameters: { type: 'object', properties, required, additionalProperties: false }
  }
});
const str = (description) => ({ type: 'string', description });
const int = (description) => ({ type: 'integer', description });
const bool = (description) => ({ type: 'boolean', description });
const TOOLS = [
  tool('list_files', 'Lista el árbol de archivos y carpetas del workspace.', { dir: str('Subcarpeta relativa; vacío para la raíz.'), depth: int('Profundidad máxima, hasta 20.') }),
  tool('read_file', 'Lee un archivo de texto del workspace.', { path: str('Ruta relativa al workspace.'), start_line: int('Primera línea (1-based).'), end_line: int('Última línea (1-based).') }, ['path']),
  tool('write_file', 'Crea o sobrescribe un archivo de texto.', { path: str('Ruta relativa al workspace.'), content: str('Contenido completo del archivo.') }, ['path', 'content']),
  tool('edit_file', 'Reemplaza un fragmento exacto de un archivo existente.', { path: str('Ruta relativa.'), search: str('Fragmento exacto y suficientemente único.'), replace: str('Texto de reemplazo; puede ser vacío.'), all: bool('Reemplazar todas las coincidencias.') }, ['path', 'search', 'replace']),
  tool('delete_path', 'Borra un archivo o carpeta del workspace.', { path: str('Ruta relativa.') }, ['path']),
  tool('move_path', 'Mueve o renombra un archivo o carpeta.', { from: str('Ruta origen.'), to: str('Ruta destino.') }, ['from', 'to']),
  tool('make_dir', 'Crea una carpeta y sus carpetas padre.', { path: str('Ruta relativa.') }, ['path']),
  tool('find_files', 'Busca archivos por nombre.', { name: str('Texto que debe aparecer en el nombre.') }, ['name']),
  tool('search_code', 'Busca texto o regex dentro de archivos de texto.', { query: str('Texto o expresión regular.'), regex: bool('Tratar query como regex.'), glob: str('Filtro por nombre, por ejemplo *.js.'), max_results: int('Máximo de coincidencias; 1–500.') }, ['query']),
  tool('run_file', 'Ejecuta un archivo con el runtime correspondiente y devuelve salida y código de salida.', { path: str('Ruta relativa del archivo.'), args: { type: 'array', items: { type: 'string' } }, timeout_ms: int('Tiempo límite en milisegundos.'), stdin: str('Entrada estándar opcional.') }, ['path']),
  tool('run_command', 'Ejecuta un comando real en bash dentro del workspace. Los comandos peligrosos siguen bloqueados.', { command: str('Comando shell.'), cwd: str('Directorio de trabajo relativo.'), timeout_ms: int('Tiempo límite en milisegundos.') }, ['command']),
  tool('open_in_editor', 'Abre un archivo en el editor y salta a una línea opcional.', { path: str('Ruta relativa.'), line: int('Número de línea.') }, ['path']),
  tool('list_runtimes', 'Lista runtimes y herramientas instalados en el servidor.', {}),
  tool('snapshot', 'Guarda una copia del workspace antes de cambios grandes.', { label: str('Etiqueta del snapshot.') }),
  tool('restore_snapshot', 'Restaura un snapshot guardado. Crea automáticamente una copia de seguridad antes de restaurar.', { id: str('Id de snapshot.') }, ['id'])
];
const TOOL_NAMES = new Set(TOOLS.map((item) => item.function.name));

const EDITOR_DOC = `CODEFORGE — editor real
- Monaco Editor (motor del editor VS Code), pestañas, guardado, búsqueda y resaltado de sintaxis.
- Explorador, escritura/edición/borrado de archivos y búsqueda global sobre el workspace real.
- Terminal interactiva por WebSocket, ejecución de programas y runtimes instalados.
- Snapshots con restauración y copias de seguridad; rutas confinadas al workspace.
- El agente puede inspeccionar y cambiar archivos reales, ejecutar pruebas y abrir archivos en el editor.`;

function systemPrompt(ctx) {
  return [
    'Eres CodeForge Agent, un ingeniero de software autónomo integrado en un editor de código.',
    'Trabajas sobre el sistema de archivos real del workspace y ejecutas código de verdad; nunca afirmes haber hecho algo que no confirmaste.',
    '', EDITOR_DOC, '',
    ctx.tree ? 'ÁRBOL ACTUAL DEL WORKSPACE:\n' + ctx.tree : '',
    ctx.openFile ? 'ARCHIVO ABIERTO: ' + ctx.openFile : '',
    ctx.runtimes ? 'RUNTIMES DISPONIBLES: ' + ctx.runtimes : '',
    '', 'REGLAS DE TRABAJO',
    '1. Inspecciona archivos antes de modificarlos. Usa list_files/read_file/search_code.',
    '2. Usa edit_file para cambios localizados; write_file solo para archivos nuevos o reescrituras completas.',
    '3. No inventes contenido previo ni afirmes resultados sin leerlos o ejecutarlos.',
    '4. Entrega código completo y funcional; evita marcadores TODO o código omitido.',
    '5. Ejecuta pruebas relevantes, corrige los fallos y explica cualquier limitación real.',
    '6. Antes de una modificación extensa, crea un snapshot.',
    '7. Usa open_in_editor para mostrar los archivos principales modificados.',
    '8. Los comandos peligrosos permanecen bloqueados; no intentes evadir la protección.',
    '9. Responde siempre en español, con un resumen de archivos tocados y cómo verificar el resultado.',
    '',
    'Si el proveedor no admite function calling, solicita herramientas con un bloque JSON: ```tool\n{"tool":"read_file","args":{"path":"..."}}\n```.'
  ].filter(Boolean).join('\n');
}

function preview(result, limit = 9000) {
  let text;
  if (typeof result === 'string') text = result;
  else if (result && typeof result === 'object' && Object.keys(result).length === 1 && 'output' in result) text = result.output;
  else text = JSON.stringify(result, null, 2);
  if (text == null) text = 'null';
  if (text.length > limit) text = text.slice(0, limit) + `\n… [salida truncada; ${text.length} caracteres]`;
  return text;
}

async function execute(name, args = {}, ctx = {}) {
  switch (name) {
    case 'list_files': return { result: await fsx.tree(args.dir || '', args.depth || 12) };
    case 'read_file': {
      const file = await fsx.read(args.path, { startLine: args.start_line, endLine: args.end_line });
      const heading = file.truncated
        ? `Líneas ${file.startLine}–${file.endLine} de ${file.totalLines} (numeradas)\n`
        : `Archivo completo (${file.totalLines} líneas)\n`;
      return { result: heading + file.content, ui: { kind: 'open', path: file.path, line: file.startLine } };
    }
    case 'write_file': {
      const file = await fsx.write(args.path, args.content);
      return { result: `Escrito ${file.path} (${file.bytes} bytes, ${file.lines} líneas)`, ui: { kind: 'file', path: file.path, open: true } };
    }
    case 'edit_file': {
      const file = await fsx.edit(args.path, args.search, args.replace, args.all);
      return { result: `Editado ${file.path} (${file.replacements} reemplazo/s)`, ui: { kind: 'file', path: file.path, open: true } };
    }
    case 'delete_path': return { result: 'Borrado ' + (await fsx.remove(args.path)).path, ui: { kind: 'tree' } };
    case 'move_path': {
      const result = await fsx.move(args.from, args.to);
      return { result: `Movido ${result.from} → ${result.to}`, ui: { kind: 'move', from: result.from, to: result.to } };
    }
    case 'make_dir': return { result: 'Carpeta creada: ' + (await fsx.mkdir(args.path)).path, ui: { kind: 'tree' } };
    case 'find_files': {
      const files = await fsx.find(args.name);
      return { result: files.length ? files.join('\n') : `Ningún archivo contiene «${args.name}» en el nombre.` };
    }
    case 'search_code': {
      const result = await fsx.grep(args.query, { regex: args.regex, glob: args.glob, maxResults: args.max_results });
      return { result: result.results.length
        ? result.results.map((match) => `${match.file}:${match.line}: ${match.text}`).join('\n')
        : `Sin resultados para «${args.query}».` };
    }
    case 'run_file': {
      const result = await runner.runFile(args.path, { args: args.args, timeout: args.timeout_ms, stdin: args.stdin });
      return { result: { estado: result.ok ? 'éxito' : 'fallo', codigo: result.code, runtime: result.runtime, etapa: result.stage, stdout: result.stdout, stderr: result.stderr, ms: result.durationMs }, ui: { kind: 'ran', path: args.path, run: result } };
    }
    case 'run_command': {
      const result = await runner.runCommand(args.command, { cwd: args.cwd, timeout: args.timeout_ms, allowDangerous: false });
      return { result: { estado: result.ok ? 'éxito' : result.blocked ? 'bloqueado' : 'fallo', codigo: result.code, stdout: result.stdout, stderr: result.stderr, ms: result.durationMs }, ui: { kind: 'ran', run: result } };
    }
    case 'open_in_editor': return { result: 'Abierto ' + args.path, ui: { kind: 'open', path: args.path, line: args.line } };
    case 'list_runtimes': {
      const detected = await runner.detect();
      return { result: { lenguajes: detected.runtimes.filter((item) => item.available).map((item) => item.label), herramientas: detected.tools.filter((item) => item.available).map((item) => item.name) } };
    }
    case 'snapshot': {
      const result = await fsx.snapshot(args.label);
      return { result: `Snapshot creado: ${result.id}`, ui: { kind: 'tree' } };
    }
    case 'restore_snapshot': {
      const result = await fsx.restore(args.id);
      return { result: `Workspace restaurado desde ${result.restored}. Copia previa: ${result.safetySnapshot}.`, ui: { kind: 'tree' } };
    }
    default: return { result: 'Herramienta desconocida: ' + name };
  }
}

async function buildContext(openFile) {
  const [tree, detected] = await Promise.all([
    fsx.treeText(220).catch(() => ''),
    runner.detect().catch(() => null)
  ]);
  const runtimes = detected ? detected.runtimes.filter((item) => item.available).map((item) => item.label).join(', ') : '';
  return { tree, openFile, runtimes };
}

function normalizeBase(url) {
  let base = String(url || '').trim().replace(/\/+$/, '');
  if (!base) base = 'https://api.openai.com/v1';
  base = base.replace(/\/(?:models|chat\/completions)$/i, '');
  if (!/\/v\d+(?:beta\d*)?$/i.test(base)) base += '/v1';
  return base + '/chat/completions';
}

function normalizeModelsUrl(url) {
  let base = String(url || '').trim().replace(/\/+$/, '');
  if (!base) base = 'https://api.openai.com/v1';
  if (/\/models$/i.test(base)) return base;
  base = base.replace(/\/chat\/completions$/i, '');
  if (!/\/v\d+(?:beta\d*)?$/i.test(base)) base += '/v1';
  return base + '/models';
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
      ? 'El proveedor aplicó un límite de solicitudes (HTTP 429); este código por sí solo no indica falta de saldo. Espera un momento o revisa los límites de tu cuenta.'
      : message || raw.slice(0, 500) || `Error HTTP ${status}`;
  const error = new Error(`HTTP ${status} del proveedor: ${friendly}${url ? ` (${url})` : ''}`);
  error.status = status;
  error.providerCode = code;
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

async function callLLM(config, messages, withTools, signal) {
  if (!config.model || !String(config.model).trim()) throw new Error('Indica el nombre del modelo en Ajustes del agente.');
  const url = normalizeBase(config.baseUrl);
  let parsed;
  try { parsed = new URL(url); } catch { throw new Error('La URL base del proveedor no es válida.'); }
  if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error('La URL del proveedor debe usar HTTP o HTTPS.');
  const headers = { 'Content-Type': 'application/json', ...(config.headers && typeof config.headers === 'object' ? config.headers : {}) };
  if (config.apiKey) headers.Authorization = 'Bearer ' + String(config.apiKey);
  const body = {
    model: String(config.model),
    messages,
    temperature: config.temperature == null ? 0.2 : Math.min(Math.max(Number(config.temperature), 0), 2)
  };
  if (withTools) { body.tools = TOOLS; body.tool_choice = 'auto'; }
  if (config.maxTokens) body.max_tokens = Math.min(Math.max(Number(config.maxTokens) || 0, 1), 32768);
  const controller = new AbortController();
  const timeoutMs = Math.min(Math.max(Number(config.requestTimeout) || 120000, 5000), 300000);
  const timer = setTimeout(() => controller.abort(new Error('timeout')), timeoutMs);
  const onAbort = () => controller.abort(signal.reason);
  signal?.addEventListener('abort', onAbort, { once: true });
  if (signal?.aborted) controller.abort(signal.reason);
  let response;
  let raw;
  try {
    response = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body), signal: controller.signal });
    raw = await response.text();
  } catch (error) {
    if (signal?.aborted) throw new Error('La solicitud del agente fue cancelada.');
    throw new Error(error.name === 'AbortError' ? `Tiempo límite excedido al conectar con ${url}.` : `No se pudo completar la solicitud a ${url}: ${error.message}`);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
  if (!response.ok) throw providerError(raw, response.status, url);
  let data;
  try { data = JSON.parse(raw); } catch { throw new Error('El proveedor devolvió una respuesta que no es JSON: ' + raw.slice(0, 400)); }
  const choice = data.choices?.[0];
  if (!choice) throw new Error('El proveedor no devolvió choices: ' + raw.slice(0, 400));
  return { message: choice.message || {}, usage: data.usage || null, finish: choice.finish_reason };
}

function parseTextCalls(text) {
  const calls = [];
  const ranges = [];
  const expression = /```(?:tool|json)?\s*\n?([\s\S]*?)```/gi;
  let match;
  while ((match = expression.exec(text)) !== null) {
    try {
      const parsed = JSON.parse(match[1].trim());
      if (parsed && TOOL_NAMES.has(parsed.tool)) {
        calls.push({ name: parsed.tool, args: parsed.args || parsed.arguments || {} });
        ranges.push([match.index, expression.lastIndex]);
      }
    } catch { /* ordinary code block, not a tool call */ }
  }
  let clean = text;
  for (const [start, end] of ranges.reverse()) clean = clean.slice(0, start) + clean.slice(end);
  return { calls, clean: clean.trim() };
}

function emitSafely(emit, event) {
  try { emit(event); } catch { /* client disconnected */ }
}

/** Tool-call loop. `history` is a mutable session transcript owned by index.js. */
async function loop(config, history, emit, options = {}) {
  const maxSteps = Math.min(Math.max(Number.parseInt(config.maxSteps, 10) || 16, 1), 40);
  const context = await buildContext(options.openFile);
  const system = { role: 'system', content: systemPrompt(context) };
  const convo = [system, ...history];
  let useTools = config.tools !== false;
  let usedTools = false;
  let tokens = 0;

  try {
    for (let step = 1; step <= maxSteps; step++) {
      if (options.signal?.aborted) return { ok: false, cancelled: true };
      emitSafely(emit, { type: 'step', step, max: maxSteps });
      let response;
      try {
        response = await callLLM(config, convo, useTools, options.signal);
      } catch (error) {
        const message = String(error.message || error);
        if (useTools && error.status && [400, 404, 422, 500].includes(error.status) && /tool|function/i.test(message)) {
          emitSafely(emit, { type: 'notice', text: 'El proveedor no admite llamadas de herramientas nativas; se intentará el protocolo JSON.' });
          useTools = false;
          continue;
        }
        emitSafely(emit, { type: 'error', text: message });
        return { ok: false, error: message };
      }
      if (response.usage) {
        tokens += Number(response.usage.total_tokens) || 0;
        emitSafely(emit, { type: 'usage', usage: response.usage, total: tokens });
      }
      const message = response.message || {};
      const text = typeof message.content === 'string' ? message.content.trim() : '';
      const calls = Array.isArray(message.tool_calls) ? message.tool_calls : [];

      if (calls.length && useTools) {
        usedTools = true;
        const normalized = calls.map((call, index) => ({
          id: call.id || `cf-${step}-${index}`,
          type: 'function',
          function: { name: call.function?.name || '', arguments: call.function?.arguments || '{}' }
        }));
        convo.push({ role: 'assistant', content: message.content || null, tool_calls: normalized });
        history.push({ role: 'assistant', content: message.content || null, tool_calls: normalized });
        if (text) emitSafely(emit, { type: 'assistant_text', text });
        for (const call of normalized) {
          if (options.signal?.aborted) return { ok: false, cancelled: true };
          const name = call.function.name;
          let args;
          try { args = JSON.parse(call.function.arguments || '{}'); } catch { args = {}; }
          if (!args || typeof args !== 'object' || Array.isArray(args)) args = {};
          emitSafely(emit, { type: 'tool_start', id: call.id, name, args });
          let output;
          try { output = await execute(name, args, { allowDangerous: false }); }
          catch (error) { output = { result: { error: error.message || String(error) } }; }
          if (output.ui) emitSafely(emit, { type: 'ui', ui: output.ui });
          const result = preview(output.result, 9000);
          emitSafely(emit, { type: 'tool_end', id: call.id, name, preview: preview(output.result, 500) });
          const toolResult = { role: 'tool', tool_call_id: call.id, content: result };
          convo.push(toolResult);
          history.push(toolResult);
        }
        continue;
      }

      if (!useTools && text) {
        const parsed = parseTextCalls(text);
        if (parsed.calls.length) {
          usedTools = true;
          if (parsed.clean) emitSafely(emit, { type: 'assistant_text', text: parsed.clean });
          convo.push({ role: 'assistant', content: text });
          history.push({ role: 'assistant', content: text });
          for (let index = 0; index < parsed.calls.length; index++) {
            const call = parsed.calls[index];
            const id = `text-${step}-${index}`;
            emitSafely(emit, { type: 'tool_start', id, name: call.name, args: call.args });
            let output;
            try { output = await execute(call.name, call.args, { allowDangerous: false }); }
            catch (error) { output = { result: { error: error.message || String(error) } }; }
            if (output.ui) emitSafely(emit, { type: 'ui', ui: output.ui });
            emitSafely(emit, { type: 'tool_end', id, name: call.name, preview: preview(output.result, 500) });
            const toolResult = { role: 'user', content: `RESULTADO DE ${call.name}:\n${preview(output.result)}` };
            convo.push(toolResult);
            history.push(toolResult);
          }
          continue;
        }
      }

      if (text) {
        convo.push({ role: 'assistant', content: text });
        history.push({ role: 'assistant', content: text });
        emitSafely(emit, { type: 'final', text });
        return { ok: true, text, steps: step, usedTools, tokens };
      }
      convo.push({ role: 'assistant', content: '(sin contenido)' });
      history.push({ role: 'assistant', content: '(sin contenido)' });
      emitSafely(emit, { type: 'final', text: 'El modelo no devolvió contenido.' });
      return { ok: true, steps: step, usedTools, tokens };
    }
    const text = `Límite de ${maxSteps} pasos alcanzado. Revisa los cambios y pide continuar si hace falta.`;
    emitSafely(emit, { type: 'final', text });
    return { ok: true, steps: maxSteps, usedTools, tokens, limit: true };
  } finally {
    if (history.length > 80) history.splice(0, history.length - 80);
  }
}

async function probe(config) {
  const message = [{ role: 'user', content: 'Responde solo: ok' }];
  let tools = true;
  let reply;
  try {
    reply = ((await callLLM({ ...config, requestTimeout: 45000 }, message, true)).message.content || '').trim();
  } catch (error) {
    if (error.status && /tool|function/i.test(String(error.message))) {
      tools = false;
      reply = ((await callLLM({ ...config, requestTimeout: 45000 }, message, false)).message.content || '').trim();
    } else throw error;
  }
  return { ok: true, model: config.model, tools, reply: reply.slice(0, 200) };
}

module.exports = { TOOLS, loop, execute, systemPrompt, parseTextCalls, probe, callLLM, normalizeBase, normalizeModelsUrl, listModels, providerError };
