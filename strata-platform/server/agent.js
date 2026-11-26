'use strict';

/*
 * Enterprise Autonomous Coding Agent Engine with 24 Real Workspace Tools,
 * Multi-LLM Free Provider Pool Failover, and Specialist Role Support (150 Agents).
 */
const fsx = require('./fsx');
const runner = require('./runner');
const analyzer = require('./analyzer');
const providers = require('./providers');
const catalog = require('./agents-catalog');

const tool = (name, description, properties, required = []) => ({
  type: 'function',
  function: {
    name,
    description,
    parameters: { type: 'object', properties, required }
  }
});
const str = (description) => ({ type: 'string', description });
const int = (description) => ({ type: 'integer', description });
const bool = (description) => ({ type: 'boolean', description });

const TOOLS = [
  tool('list_files', 'Lista el árbol de archivos y carpetas del workspace.', { dir: str('Subcarpeta relativa; vacío para la raíz.'), depth: int('Profundidad máxima, hasta 20.') }),
  tool('read_file', 'Lee un archivo de texto del workspace.', { path: str('Ruta relativa al workspace.'), start_line: int('Primera línea (1-based).'), end_line: int('Última línea (1-based).') }, ['path']),
  tool('read_many_files', 'Lee varios archivos en lote para análisis arquitectónico y depuración multi-módulo.', { paths: { type: 'array', items: { type: 'string' }, description: 'Lista de hasta 20 rutas relativas.' }, max_lines_per_file: int('Máximo de líneas por archivo.') }, ['paths']),
  tool('write_file', 'Crea o sobrescribe un archivo de texto de forma atómica con bloqueo de concurrencia.', { path: str('Ruta relativa al workspace.'), content: str('Contenido completo del archivo.') }, ['path', 'content']),
  tool('edit_file', 'Reemplaza un fragmento exacto de un archivo existente.', { path: str('Ruta relativa.'), search: str('Fragmento exacto y suficientemente único.'), replace: str('Texto de reemplazo; puede ser vacío.'), all: bool('Reemplazar todas las coincidencias.') }, ['path', 'search', 'replace']),
  tool('patch_lines', 'Reemplaza un rango exacto de líneas (1-based inclusivo) en un archivo grande.', { path: str('Ruta relativa.'), start_line: int('Línea inicial (1-based).'), end_line: int('Línea final (1-based).'), content: str('Nuevo contenido para esas líneas.') }, ['path', 'start_line', 'end_line', 'content']),
  tool('delete_path', 'Borra un archivo o carpeta del workspace.', { path: str('Ruta relativa.') }, ['path']),
  tool('move_path', 'Mueve o renombra un archivo o carpeta.', { from: str('Ruta origen.'), to: str('Ruta destino.') }, ['from', 'to']),
  tool('make_dir', 'Crea una carpeta y sus carpetas padre.', { path: str('Ruta relativa.') }, ['path']),
  tool('find_files', 'Busca archivos por nombre en todo el workspace.', { name: str('Texto que debe aparecer en el nombre.') }, ['name']),
  tool('search_code', 'Busca texto o regex con aceleración ripgrep en todo el proyecto.', { query: str('Texto o expresión regular.'), regex: bool('Tratar query como regex.'), glob: str('Filtro por nombre, por ejemplo *.js.'), max_results: int('Máximo de coincidencias; 1–2000.') }, ['query']),
  tool('project_cartography', 'Genera un mapa completo del proyecto: estadísticas, lenguajes, grafo de dependencias, entry points, tests y hotspots de complejidad.', { dir: str('Subcarpeta relativa opcional.') }),
  tool('verify_syntax', 'Verifica sintaxis y compilación real (Node.js, Python AST, JSON, Bash, C/C++, CSS, HTML) en un archivo o en todo el proyecto.', { path: str('Archivo específico opcional; vacío para verificar todo el workspace.') }),
  tool('security_audit', 'Ejecuta auditoría de seguridad OWASP, secretos hardcoded, inyecciones, XSS y deuda técnica en el workspace.', { dir: str('Subcarpeta relativa opcional.') }),
  tool('run_tests', 'Detecta y ejecuta la suite de pruebas real del proyecto (npm test, node --test, python unittest o comando personalizado).', { dir: str('Subcarpeta opcional.'), command: str('Comando de prueba personalizado opcional.') }),
  tool('run_file', 'Ejecuta un archivo con el runtime correspondiente y devuelve stdout, stderr y código de salida.', { path: str('Ruta relativa del archivo.'), args: { type: 'array', items: { type: 'string' } }, timeout_ms: int('Tiempo límite en milisegundos.'), stdin: str('Entrada estándar opcional.') }, ['path']),
  tool('run_command', 'Ejecuta un comando real en bash dentro del workspace (build, dependencias, scripts, verificaciones).', { command: str('Comando shell.'), cwd: str('Directorio de trabajo relativo.'), timeout_ms: int('Tiempo límite en milisegundos.') }, ['command']),
  tool('git_status', 'Consulta el estado actual de Git en el workspace.', { dir: str('Subcarpeta opcional.') }),
  tool('git_diff', 'Obtiene el diff real de cambios en el workspace o en un archivo específico.', { path: str('Archivo opcional.'), dir: str('Subcarpeta opcional.') }),
  tool('git_log', 'Consulta los últimos commits del repositorio Git del workspace.', { limit: int('Número de commits (1–100).'), dir: str('Subcarpeta opcional.') }),
  tool('open_in_editor', 'Abre un archivo en el editor y salta a una línea opcional.', { path: str('Ruta relativa.'), line: int('Número de línea.') }, ['path']),
  tool('list_runtimes', 'Lista runtimes, compiladores y herramientas instalados en el servidor.', { filter: str('Filtro opcional por nombre de runtime.') }),
  tool('snapshot', 'Guarda una copia de seguridad del workspace antes de cambios masivos.', { label: str('Etiqueta del snapshot.') }),
  tool('restore_snapshot', 'Restaura un snapshot guardado (crea copia de seguridad previa automáticamente).', { id: str('Id de snapshot.') }, ['id'])
];
const TOOL_NAMES = new Set(TOOLS.map((item) => item.function.name));

const READ_ONLY_TOOL_NAMES = new Set([
  'list_files', 'read_file', 'find_files', 'search_code',
  'project_cartography', 'verify_syntax'
]);
const PLAN_TOOLS = TOOLS.filter((item) => READ_ONLY_TOOL_NAMES.has(item.function.name));

function planSystemPrompt(ctx) {
  return [
    'Eres el Arquitecto Principal y Director Técnico de Strata Engineering, una plataforma de desarrollo impulsada por 150 agentes especialistas divididos en 15 áreas (Arquitectura, Backend, Frontend, Sistemas, Datos, Depuración, QA, DevOps, Seguridad OWASP, Rendimiento, IA/ML, Móvil, Integraciones, Refactorización y Dirección).',
    '',
    'ESTÁS EN MODO PLANIFICACIÓN Y CONVERSACIÓN COLABORATIVA:',
    '- Tu objetivo en esta fase es CONVERSAR de forma natural, clara, cercana y de altísimo nivel técnico con el usuario ANTES de construir nada.',
    '- NO modifiques, NO escribas ni borres archivos en esta fase (solo tienes herramientas de lectura e inspección del workspace).',
    '- Si el usuario saluda o plantea una idea inicial o ambigua: respóndele conversacionalmente, valida su idea, aporta criterio de ingeniería real y haz solo 2 a 4 preguntas concretas y útiles para definir alcance, tecnologías, estructura o diseño.',
    '- Si el workspace ya tiene archivos y es relevante para la conversación, inspecciónalos en silencio con tus herramientas de solo lectura antes de opinar.',
    '- Cuando ya haya suficiente claridad (o cuando el usuario pida el plan o diga que está listo para construir), presenta un plan claro y bien estructurado bajo el título `## Plan de Implementación` que incluya:',
    '  1. Arquitectura y stack técnico acordado',
    '  2. Estructura de carpetas y archivos que se crearán o modificarán',
    '  3. Divisiones de los 150 agentes que participarán y sus responsabilidades',
    '  4. Verificación real (sintaxis, seguridad OWASP y pruebas automáticas)',
    '- SOLO cuando presentes ese plan completo listo para que el usuario lo apruebe y comience la construcción, agrega exactamente en la última línea (sin texto después): [[PLAN_READY]]',
    '- Si todavía estás conversando o esperando respuestas a tus preguntas, NO pongas [[PLAN_READY]].',
    '- Responde siempre en español claro, conciso, bien organizado y sin relleno corporativo innecesario.',
    ctx?.openFile ? `\nArchivo abierto actualmente en el editor: ${ctx.openFile}` : '',
    ctx?.treeSummary ? `\nEstado actual del workspace:\n${ctx.treeSummary}` : ''
  ].filter(Boolean).join('\n');
}

function systemPrompt(ctx) {
  return catalog.buildAgentSystemPrompt(ctx?.agent || null, ctx || {});
}

function preview(result, limit = 10000) {
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
    case 'read_many_files': {
      const files = await fsx.readMany(args.paths || [], args.max_lines_per_file || 220);
      return { result: files };
    }
    case 'write_file': {
      const file = await fsx.write(args.path, args.content);
      ctx.onFileChange?.(file.path, 'write');
      return { result: `Escrito ${file.path} (${file.bytes} bytes, ${file.lines} líneas)`, ui: { kind: 'file', path: file.path, open: true } };
    }
    case 'edit_file': {
      const file = await fsx.edit(args.path, args.search, args.replace, args.all);
      ctx.onFileChange?.(file.path, 'edit');
      return { result: `Editado ${file.path} (${file.replacements} reemplazo/s)`, ui: { kind: 'file', path: file.path, open: true } };
    }
    case 'patch_lines': {
      const file = await fsx.patchLines(args.path, args.start_line, args.end_line, args.content);
      ctx.onFileChange?.(file.path, 'patch');
      return { result: `Parcheado ${file.path} líneas ${file.patchedRange} (${file.newLineCount} línea(s) nuevas)`, ui: { kind: 'file', path: file.path, open: true } };
    }
    case 'delete_path': {
      const res = await fsx.remove(args.path);
      ctx.onFileChange?.(res.path, 'delete');
      return { result: 'Borrado ' + res.path, ui: { kind: 'tree' } };
    }
    case 'move_path': {
      const result = await fsx.move(args.from, args.to);
      ctx.onFileChange?.(result.to, 'move');
      return { result: `Movido ${result.from} → ${result.to}`, ui: { kind: 'move', from: result.from, to: result.to } };
    }
    case 'make_dir': return { result: 'Carpeta creada: ' + (await fsx.mkdir(args.path)).path, ui: { kind: 'tree' } };
    case 'find_files': {
      const files = await fsx.find(args.name);
      return { result: files.length ? files.join('\n') : `Ningún archivo contiene «${args.name}» en el nombre.` };
    }
    case 'search_code': {
      const result = await fsx.grep(args.query, { regex: args.regex, glob: args.glob, maxResults: args.max_results });
      return {
        result: result.results.length
          ? result.results.map((match) => `${match.file}:${match.line}: ${match.text}`).join('\n')
          : `Sin resultados para «${args.query}».`
      };
    }
    case 'project_cartography': return { result: await analyzer.cartography(args.dir || '') };
    case 'verify_syntax': {
      if (args.path) return { result: await analyzer.verifyFile(args.path) };
      return { result: await analyzer.verifyWorkspaceSyntax('') };
    }
    case 'security_audit': return { result: await analyzer.securityAudit(args.dir || '') };
    case 'run_tests': {
      const res = await runner.runProjectTests(args.dir || '', args.command || '');
      return { result: res, ui: { kind: 'ran', run: res } };
    }
    case 'run_file': {
      const result = await runner.runFile(args.path, { args: args.args, timeout: args.timeout_ms, stdin: args.stdin });
      return { result: { estado: result.ok ? 'éxito' : 'fallo', codigo: result.code, runtime: result.runtime, etapa: result.stage, stdout: result.stdout, stderr: result.stderr, ms: result.durationMs }, ui: { kind: 'ran', path: args.path, run: result } };
    }
    case 'run_command': {
      const result = await runner.runCommand(args.command, { cwd: args.cwd, timeout: args.timeout_ms, allowDangerous: false });
      return { result: { estado: result.ok ? 'éxito' : result.blocked ? 'bloqueado' : 'fallo', codigo: result.code, stdout: result.stdout, stderr: result.stderr, ms: result.durationMs }, ui: { kind: 'ran', run: result } };
    }
    case 'git_status': return { result: await runner.gitStatus(args.dir || '') };
    case 'git_diff': return { result: await runner.gitDiff(args.dir || '', args.path || '') };
    case 'git_log': return { result: await runner.gitLog(args.dir || '', args.limit || 20) };
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

async function buildContext(options = {}) {
  const [tree, detected] = await Promise.all([
    fsx.treeText(260).catch(() => ''),
    runner.detect().catch(() => null)
  ]);
  const runtimes = detected ? detected.runtimes.filter((item) => item.available).map((item) => item.label).join(', ') : '';
  return {
    agent: options.agent || null,
    missionObjective: options.missionObjective || '',
    taskBrief: options.taskBrief || '',
    sharedNotes: options.sharedNotes || '',
    tree,
    openFile: options.openFile || '',
    runtimes
  };
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
    } catch { /* ordinary code block */ }
  }
  let clean = text;
  for (const [start, end] of ranges.reverse()) clean = clean.slice(0, start) + clean.slice(end);
  return { calls, clean: clean.trim() };
}

function emitSafely(emit, event) {
  try { emit(event); } catch { /* client disconnected */ }
}

/**
 * Tool-calling autonomous execution loop.
 * Accepts either a single provider config OR a full Multi-LLM Pool config.
 */
async function loop(poolOrConfig, history, emit, options = {}) {
  const pool = providers.normalizePool(poolOrConfig);
  const maxSteps = Math.min(Math.max(Number.parseInt(options.maxSteps || poolOrConfig?.maxSteps || pool.defaults.maxSteps, 10) || 18, 1), 60);
  const context = await buildContext(options);
  const system = { role: 'system', content: systemPrompt(context) };
  const convo = [system, ...history];
  let useTools = poolOrConfig?.tools !== false && pool.defaults.tools !== false;
  let usedTools = false;
  let tokens = 0;
  let lastProvider = null;
  const filesTouched = new Set();
  const toolLog = [];

  const execCtx = {
    allowDangerous: false,
    onFileChange: (filePath, op) => {
      filesTouched.add(filePath);
      options.onFileChange?.(filePath, op);
    }
  };

  try {
    for (let step = 1; step <= maxSteps; step++) {
      if (options.signal?.aborted) return { ok: false, cancelled: true, filesTouched: [...filesTouched] };
      emitSafely(emit, { type: 'step', step, max: maxSteps, agentId: options.agent?.id || null });
      let response;
      try {
        response = await providers.callPoolLLM(poolOrConfig, convo, TOOLS, {
          useTools,
          signal: options.signal,
          agentId: options.agent?.id,
          divisionId: options.agent?.divisionId,
          preferredProviderId: options.preferredProviderId,
          onFailover: (info) => {
            emitSafely(emit, {
              type: 'failover',
              agentId: options.agent?.id || null,
              from: info.from,
              to: info.to,
              reason: info.reason
            });
          },
          onRetryWait: (info) => {
            emitSafely(emit, {
              type: 'retry_wait',
              agentId: options.agent?.id || null,
              seconds: info.seconds,
              round: info.round,
              maxRounds: info.maxRounds
            });
          }
        });
        lastProvider = response.provider;
        emitSafely(emit, { type: 'provider', provider: response.provider, latencyMs: response.latencyMs, agentId: options.agent?.id || null });
      } catch (error) {
        const message = String(error.message || error);
        if (useTools && error.status && [400, 404, 422, 500].includes(error.status) && /tool|function|schema|additionalProperties|parameters|tool_choice|endpoint/i.test(message)) {
          emitSafely(emit, { type: 'notice', text: 'El proveedor no admite llamadas de herramientas nativas; se cambiará al protocolo JSON estructurado.' });
          useTools = false;
          continue;
        }
        emitSafely(emit, { type: 'error', text: message, agentId: options.agent?.id || null });
        return { ok: false, error: message, filesTouched: [...filesTouched], tokens, provider: lastProvider };
      }

      if (response.usage) {
        tokens += Number(response.usage.total_tokens) || 0;
        emitSafely(emit, { type: 'usage', usage: response.usage, total: tokens, agentId: options.agent?.id || null });
      }
      const message = response.message || {};
      const text = typeof message.content === 'string' ? message.content.trim() : '';
      const calls = Array.isArray(message.tool_calls) ? message.tool_calls : [];

      if (calls.length && useTools) {
        usedTools = true;
        const normalized = calls.map((call, index) => ({
          id: call.id || `s${String(step).padStart(2, '0')}${String(index).padStart(6, '0')}`,
          type: 'function',
          function: { name: call.function?.name || '', arguments: call.function?.arguments || '{}' }
        }));
        convo.push({ role: 'assistant', content: message.content || null, tool_calls: normalized });
        history.push({ role: 'assistant', content: message.content || null, tool_calls: normalized });
        if (text) emitSafely(emit, { type: 'assistant_text', text, agentId: options.agent?.id || null });
        for (const call of normalized) {
          if (options.signal?.aborted) return { ok: false, cancelled: true, filesTouched: [...filesTouched] };
          const name = call.function.name;
          let args;
          try { args = JSON.parse(call.function.arguments || '{}'); } catch { args = {}; }
          if (!args || typeof args !== 'object' || Array.isArray(args)) args = {};
          emitSafely(emit, { type: 'tool_start', id: call.id, name, args, agentId: options.agent?.id || null });
          let output;
          try { output = await execute(name, args, execCtx); }
          catch (error) { output = { result: { error: error.message || String(error) } }; }
          if (output.ui) emitSafely(emit, { type: 'ui', ui: output.ui, agentId: options.agent?.id || null });
          const result = preview(output.result, 10000);
          const shortPreview = preview(output.result, 500);
          toolLog.push({ name, args, preview: shortPreview });
          emitSafely(emit, { type: 'tool_end', id: call.id, name, preview: shortPreview, agentId: options.agent?.id || null });
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
          if (parsed.clean) emitSafely(emit, { type: 'assistant_text', text: parsed.clean, agentId: options.agent?.id || null });
          convo.push({ role: 'assistant', content: text });
          history.push({ role: 'assistant', content: text });
          for (let index = 0; index < parsed.calls.length; index++) {
            const call = parsed.calls[index];
            const id = `text-${step}-${index}`;
            emitSafely(emit, { type: 'tool_start', id, name: call.name, args: call.args, agentId: options.agent?.id || null });
            let output;
            try { output = await execute(call.name, call.args, execCtx); }
            catch (error) { output = { result: { error: error.message || String(error) } }; }
            if (output.ui) emitSafely(emit, { type: 'ui', ui: output.ui, agentId: options.agent?.id || null });
            const shortPreview = preview(output.result, 500);
            toolLog.push({ name: call.name, args: call.args, preview: shortPreview });
            emitSafely(emit, { type: 'tool_end', id, name: call.name, preview: shortPreview, agentId: options.agent?.id || null });
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
        emitSafely(emit, { type: 'final', text, agentId: options.agent?.id || null });
        return { ok: true, text, steps: step, usedTools, tokens, filesTouched: [...filesTouched], toolLog, provider: lastProvider };
      }
      convo.push({ role: 'assistant', content: '(sin contenido)' });
      history.push({ role: 'assistant', content: '(sin contenido)' });
      emitSafely(emit, { type: 'final', text: 'El modelo no devolvió contenido.', agentId: options.agent?.id || null });
      return { ok: true, steps: step, usedTools, tokens, filesTouched: [...filesTouched], toolLog, provider: lastProvider };
    }
    const text = `Límite de ${maxSteps} pasos alcanzado. Revisa los cambios y continúa si es necesario.`;
    emitSafely(emit, { type: 'final', text, agentId: options.agent?.id || null });
    return { ok: true, steps: maxSteps, usedTools, tokens, limit: true, filesTouched: [...filesTouched], toolLog, provider: lastProvider };
  } finally {
    if (history.length > 80) history.splice(0, history.length - 80);
  }
}

/**
 * Read-only collaborative planning & architecture conversation loop.
 * Converses naturally with the user across turns, inspects workspace read-only
 * when needed, and detects [[PLAN_READY]] when a build plan is ready for approval.
 */
async function planLoop(poolOrConfig, history, emit, options = {}) {
  const pool = providers.normalizePool(poolOrConfig);
  const maxSteps = Math.min(Math.max(Number.parseInt(options.maxSteps || 8, 10) || 8, 1), 16);
  const treeSummary = await fsx.treeText(120).catch(() => '(workspace vacío)');
  const workspaceHasFiles = Boolean(treeSummary && !/^\(workspace vacío\)$/i.test(treeSummary.trim()));
  const system = {
    role: 'system',
    content: planSystemPrompt({ openFile: options.openFile || '', treeSummary })
  };
  const convo = [system, ...history];
  let useTools = workspaceHasFiles && poolOrConfig?.tools !== false && pool.defaults.tools !== false;
  let tokens = 0;
  let lastProvider = null;

  try {
    for (let step = 1; step <= maxSteps; step++) {
      if (options.signal?.aborted) return { ok: false, cancelled: true };
      let response;
      try {
        response = await providers.callPoolLLM(poolOrConfig, convo, PLAN_TOOLS, {
          useTools,
          signal: options.signal,
          agentId: 'AG-001',
          divisionId: 'architecture',
          onFailover: (info) => emitSafely(emit, { type: 'failover', from: info.from, to: info.to, reason: info.reason }),
          onRetryWait: (info) => emitSafely(emit, { type: 'retry_wait', seconds: info.seconds, round: info.round, maxRounds: info.maxRounds })
        });
        lastProvider = response.provider;
        emitSafely(emit, { type: 'provider', provider: response.provider, latencyMs: response.latencyMs });
      } catch (error) {
        const message = String(error.message || error);
        if (useTools && error.status && [400, 404, 422, 500].includes(error.status) && /tool|function|schema|additionalProperties|parameters|tool_choice|endpoint/i.test(message)) {
          useTools = false;
          continue;
        }
        emitSafely(emit, { type: 'error', text: message });
        return { ok: false, error: message, tokens, provider: lastProvider };
      }

      if (response.usage) {
        tokens += Number(response.usage.total_tokens) || 0;
        emitSafely(emit, { type: 'usage', usage: response.usage, total: tokens });
      }
      const message = response.message || {};
      const text = typeof message.content === 'string' ? message.content.trim() : '';
      const calls = Array.isArray(message.tool_calls) ? message.tool_calls : [];

      if (calls.length && useTools) {
        const normalized = calls.map((call, index) => ({
          id: call.id || `p${String(step).padStart(2, '0')}${String(index).padStart(6, '0')}`,
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
          if (!READ_ONLY_TOOL_NAMES.has(name)) {
            output = { result: { error: 'Modo planificación: solo se permiten herramientas de lectura/inspección hasta que el usuario apruebe construir.' } };
          } else {
            try { output = await execute(name, args, { allowDangerous: false }); }
            catch (error) { output = { result: { error: error.message || String(error) } }; }
          }
          const result = preview(output.result, 8000);
          emitSafely(emit, { type: 'tool_end', id: call.id, name, preview: preview(output.result, 320) });
          const toolResult = { role: 'tool', tool_call_id: call.id, content: result };
          convo.push(toolResult);
          history.push(toolResult);
        }
        continue;
      }

      const rawText = text || 'Cuéntame qué tienes en mente y lo diseñamos juntos antes de empezar a construir.';
      const hasMarker = /\[\[PLAN_READY\]\]/i.test(rawText);
      const hasPlanHeading = /^#{1,3}\s+plan\s+de\s+implementaci[oó]n/im.test(rawText);
      const cleanText = rawText.replace(/\s*\[\[PLAN_READY\]\]\s*/gi, '').trim();
      const planReady = hasMarker || hasPlanHeading;

      convo.push({ role: 'assistant', content: cleanText });
      history.push({ role: 'assistant', content: cleanText });
      emitSafely(emit, { type: 'final', text: cleanText, planReady, provider: lastProvider });
      return { ok: true, text: cleanText, planReady, tokens, provider: lastProvider };
    }
    const fallback = 'Ya tengo suficiente contexto. ¿Quieres que presente el Plan de Implementación final para aprobarlo?';
    emitSafely(emit, { type: 'final', text: fallback, planReady: false, provider: lastProvider });
    return { ok: true, text: fallback, planReady: false, tokens, provider: lastProvider };
  } finally {
    if (history.length > 60) history.splice(0, history.length - 60);
  }
}

async function probe(config) {
  return providers.probe(config, null);
}

module.exports = {
  TOOLS,
  PLAN_TOOLS,
  READ_ONLY_TOOL_NAMES,
  loop,
  planLoop,
  execute,
  systemPrompt,
  planSystemPrompt,
  parseTextCalls,
  probe,
  callLLM: (config, messages, withTools, signal) => providers.callLLM(config, messages, withTools ? TOOLS : false, signal),
  normalizeBase: providers.normalizeBase,
  normalizeModelsUrl: providers.normalizeModelsUrl,
  listModels: providers.listModels,
  providerError: providers.providerError
};
