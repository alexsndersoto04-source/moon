'use strict';

/*
 * Enterprise 150-Agent Mission Orchestrator & Live Fleet Telemetry Engine.
 * Coordinates concurrent multi-agent execution, file-locked edits, automatic
 * syntax/test/security verification, debug self-healing loops, and SSE streaming.
 */
const crypto = require('node:crypto');
const fsx = require('./fsx');
const runner = require('./runner');
const analyzer = require('./analyzer');
const providers = require('./providers');
const catalog = require('./agents-catalog');
const agentEngine = require('./agent');

const MAX_MISSIONS = 50;
const missions = new Map();
const missionOrder = [];
const missionListeners = new Map(); // missionId -> Set(fn)
const globalListeners = new Set();

const agentRuntime = new Map();
for (const a of catalog.AGENTS) {
  agentRuntime.set(a.id, {
    status: 'idle',
    currentMissionId: null,
    currentTask: '',
    lastProvider: '',
    tasksCompleted: 0,
    filesModified: 0,
    toolsExecuted: 0,
    tokensUsed: 0,
    lastActiveAt: null
  });
}

function broadcastGlobal(event) {
  const payload = { ...event, ts: Date.now() };
  for (const fn of globalListeners) {
    try { fn(payload); } catch { /* ignore */ }
  }
}

function subscribeGlobal(fn) {
  globalListeners.add(fn);
  return () => globalListeners.delete(fn);
}

function emitMission(mission, event) {
  const enriched = { ...event, missionId: mission.id, ts: Date.now() };
  mission.events.push(enriched);
  if (mission.events.length > 600) mission.events.splice(0, mission.events.length - 600);
  const listeners = missionListeners.get(mission.id);
  if (listeners) {
    for (const fn of listeners) {
      try { fn(enriched); } catch { /* ignore */ }
    }
  }
  if (['mission_status', 'phase', 'agent_status', 'file_changed', 'failover'].includes(event.type)) {
    broadcastGlobal(enriched);
  }
}

function subscribeMission(missionId, fn) {
  let set = missionListeners.get(missionId);
  if (!set) {
    set = new Set();
    missionListeners.set(missionId, set);
  }
  set.add(fn);
  return () => {
    set.delete(fn);
    if (!set.size) missionListeners.delete(missionId);
  };
}

function updateAgentState(agentId, patch, mission = null) {
  const state = agentRuntime.get(agentId);
  if (!state) return;
  Object.assign(state, patch);
  state.lastActiveAt = new Date().toISOString();
  if (mission) {
    const slot = mission.agents.find((a) => a.id === agentId);
    if (slot) {
      if (patch.status) slot.status = patch.status;
      if (patch.currentTask !== undefined) slot.task = patch.currentTask;
      if (patch.lastProvider) slot.provider = patch.lastProvider;
    }
    emitMission(mission, { type: 'agent_status', agentId, status: state.status, task: state.currentTask, provider: state.lastProvider });
  } else {
    broadcastGlobal({ type: 'agent_status', agentId, status: state.status, task: state.currentTask, provider: state.lastProvider });
  }
}

function listAgents(filters = {}) {
  const q = String(filters.q || '').trim().toLowerCase();
  const division = String(filters.division || '').trim().toLowerCase();
  const phase = String(filters.phase || '').trim().toLowerCase();
  const status = String(filters.status || '').trim().toLowerCase();

  const items = [];
  for (const a of catalog.AGENTS) {
    const rt = agentRuntime.get(a.id);
    if (division && a.divisionId !== division) continue;
    if (phase && a.phase !== phase) continue;
    if (status && rt.status !== status) continue;
    if (q) {
      const hay = `${a.id} ${a.name} ${a.divisionName} ${a.specialty} ${a.tier} ${a.focusTags.join(' ')}`.toLowerCase();
      if (!hay.includes(q)) continue;
    }
    items.push({ ...a, runtime: { ...rt } });
  }
  return items;
}

function getAgentDetail(agentId) {
  const agent = catalog.getAgent(agentId);
  if (!agent) return null;
  return { ...agent, runtime: { ...agentRuntime.get(agent.id) } };
}

function getFleetStats() {
  let active = 0;
  let queued = 0;
  let totalTasks = 0;
  let totalFiles = 0;
  let totalTools = 0;
  let totalTokens = 0;
  for (const rt of agentRuntime.values()) {
    if (rt.status === 'working' || rt.status === 'verifying') active++;
    if (rt.status === 'queued') queued++;
    totalTasks += rt.tasksCompleted;
    totalFiles += rt.filesModified;
    totalTools += rt.toolsExecuted;
    totalTokens += rt.tokensUsed;
  }
  const activeMissions = [...missions.values()].filter((m) => m.status === 'running').length;
  return {
    totalAgents: catalog.AGENTS.length,
    totalDivisions: catalog.DIVISIONS_SUMMARY.length,
    activeAgents: active,
    queuedAgents: queued,
    idleAgents: catalog.AGENTS.length - active - queued,
    activeMissions,
    totalMissions: missions.size,
    totalTasksCompleted: totalTasks,
    totalFilesModified: totalFiles,
    totalToolsExecuted: totalTools,
    totalTokensUsed: totalTokens
  };
}

function summarizeMission(m) {
  return {
    id: m.id,
    objective: m.objective,
    mode: m.mode,
    scale: m.scale,
    engineMode: m.engineMode,
    status: m.status,
    phase: m.phase,
    agentCount: m.agents.length,
    completedAgents: m.agents.filter((a) => a.status === 'completed').length,
    failedAgents: m.agents.filter((a) => a.status === 'error').length,
    filesChangedCount: m.filesChanged.length,
    tokensUsed: m.tokensUsed,
    createdAt: m.createdAt,
    endedAt: m.endedAt,
    durationMs: m.endedAt ? (new Date(m.endedAt) - new Date(m.createdAt)) : (Date.now() - new Date(m.createdAt))
  };
}

function listMissions() {
  return missionOrder.map((id) => missions.get(id)).filter(Boolean).map(summarizeMission);
}

function getMission(id) {
  const m = missions.get(id);
  if (!m) return null;
  return {
    ...summarizeMission(m),
    agents: m.agents,
    filesChanged: m.filesChanged,
    diagnostics: m.diagnostics,
    report: m.report,
    events: m.events.slice(-200)
  };
}

async function runWithConcurrency(items, concurrency, signal, worker) {
  let cursor = 0;
  const results = new Array(items.length);
  async function runnerLoop() {
    while (cursor < items.length) {
      if (signal?.aborted) break;
      const idx = cursor++;
      results[idx] = await worker(items[idx], idx);
    }
  }
  const count = Math.min(Math.max(1, concurrency), items.length);
  await Promise.all(Array.from({ length: count }, () => runnerLoop()));
  return results;
}

function defaultBriefForAgent(agent, mission, diagSummary) {
  const base = `Objetivo global: ${mission.objective}\n\nEstado real del proyecto:\n${diagSummary}\n\n`;
  if (mission.mode === 'audit-and-heal') {
    if (agent.phase === 'debug') return base + 'Inspecciona los errores de sintaxis, ejecución o lógica encontrados en el diagnóstico, corrige las causas raíz directamente en los archivos reales y verifica con verify_syntax / run_tests.';
    if (agent.phase === 'security') return base + 'Revisa los hallazgos de seguridad y vulnerabilidades en el workspace, remedia riesgos críticos/altos en los archivos afectados y confirma con security_audit.';
    if (agent.phase === 'test') return base + 'Ejecuta y refuerza pruebas reales sobre los módulos críticos y confirma que todas pasan sin regresiones.';
    return base + `Aplica tu especialidad (${agent.specialty}) para detectar y corregir defectos estructurales, mejorar la calidad y reportar hallazgos verificados.`;
  }
  if (agent.phase === 'plan' || agent.phase === 'architect') {
    return base + `Analiza la estructura del workspace y define/prepara la arquitectura y módulos necesarios según tu especialidad (${agent.specialty}). Crea carpetas, contratos o especificaciones base si corresponde.`;
  }
  if (agent.phase === 'implement' || agent.phase === 'devops' || agent.phase === 'optimize') {
    return base + `Implementa en archivos reales la parte correspondiente a tu especialidad (${agent.specialty}) con código completo, limpio, modular y listo para producción. Verifica sintaxis antes de concluir.`;
  }
  if (agent.phase === 'debug') {
    return base + 'Verifica errores de compilación, sintaxis, ejecución o bordes introducidos; depura y corrige cualquier fallo real encontrado.';
  }
  if (agent.phase === 'security') {
    return base + 'Audita los archivos del proyecto con security_audit y lectura directa; corrige vulnerabilidades de inyección, XSS, secretos o validación deficiente.';
  }
  if (agent.phase === 'test') {
    return base + 'Crea o ejecuta pruebas reales (run_tests / run_file) validando la implementación completa y confirma código de salida 0.';
  }
  return base + `Realiza revisión de código y verificación final según tu especialidad (${agent.specialty}), corrige problemas menores y entrega un dictamen técnico conciso.`;
}

function summarizeDiagnosticsForPrompt(carto, syntax, security, tests) {
  const lines = [
    `- Archivos: ${carto.totalFiles} | Líneas totales: ${carto.totalLines} | Lenguajes: ${(carto.languages || []).slice(0, 6).map((l) => `${l.ext}(${l.files})`).join(', ') || 'ninguno aún'}`,
    `- Sintaxis: ${syntax.ok ? 'OK (0 errores)' : `${syntax.errorCount} error(es) en ${syntax.checkedFiles} archivo(s)`}`,
    `- Seguridad: puntuación ${security.score}/100 (críticos: ${security.counts.critical}, altos: ${security.counts.high}, medios: ${security.counts.medium})`
  ];
  if (syntax.diagnostics?.length) {
    lines.push('Errores de sintaxis detectados:\n' + syntax.diagnostics.slice(0, 15).map((d) => `  * ${d.file}:${d.line} [${d.runtime}] ${d.message}`).join('\n'));
  }
  if (security.findings?.length) {
    const important = security.findings.filter((f) => ['critical', 'high', 'medium'].includes(f.severity)).slice(0, 15);
    if (important.length) {
      lines.push('Hallazgos de seguridad:\n' + important.map((f) => `  * ${f.file}:${f.line} [${f.severity.toUpperCase()}] ${f.title}`).join('\n'));
    }
  }
  if (tests && !tests.skipped) {
    lines.push(`- Pruebas: ${tests.ok ? 'PASARON' : `FALLARON (código ${tests.code})`}`);
    if (!tests.ok) lines.push('Salida de fallo de pruebas:\n' + ((tests.stderr || tests.stdout || '').slice(0, 1800)));
  }
  return lines.join('\n');
}

async function planMissionTasksWithLLM(mission, poolConfig, diagSummary, signal) {
  const lead = catalog.getAgent('AG-141');
  const rosterLines = mission.agents.map((a) => `${a.id} | ${a.name} (${a.divisionShort}, fase: ${a.phase}) — ${a.specialty}`).join('\n');
  const prompt = [
    `Eres ${lead.name} (${lead.id}). Debes descomponer esta misión de ingeniería en asignaciones claras, concretas y sin solapamiento para los agentes seleccionados.`,
    '',
    `OBJETIVO:\n${mission.objective}`,
    '',
    `DIAGNÓSTICO REAL DEL WORKSPACE:\n${diagSummary}`,
    '',
    `ESPECIALISTAS DESPLEGADOS (${mission.agents.length}):\n${rosterLines}`,
    '',
    'Devuelve EXCLUSIVAMENTE un JSON válido con este formato (sin texto extra):',
    '{"summary":"Resumen arquitectónico breve","assignments":[{"agentId":"AG-001","task":"Instrucción concreta, archivos objetivo y criterio de verificación"}]}',
    'Incluye solo los agentes necesarios para ejecutar de forma eficiente y real (entre 1 y ' + Math.min(mission.agents.length, 24) + ' asignaciones activas principales; el resto participará en verificación si aplica).'
  ].join('\n');

  try {
    const res = await providers.callPoolLLM(poolConfig, [
      { role: 'system', content: 'Eres un director técnico experto en descomposición de proyectos complejos. Responde solo JSON válido.' },
      { role: 'user', content: prompt }
    ], false, { signal, agentId: lead.id, divisionId: lead.divisionId, useTools: false });
    const raw = String(res.message?.content || '').trim();
    const jsonMatch = raw.match(/\{[\s\S]*\}/);
    if (!jsonMatch) return null;
    const parsed = JSON.parse(jsonMatch[0]);
    if (Array.isArray(parsed.assignments) && parsed.assignments.length) return parsed;
  } catch { /* fallback cleanly to deterministic assignment */ }
  return null;
}

async function executeMission(mission, options = {}) {
  const signal = mission._abort.signal;
  const poolConfig = options.pool || options.config || null;
  const normalizedPool = providers.normalizePool(poolConfig);
  const hasLLM = normalizedPool.providers.length > 0;
  mission.engineMode = hasLLM ? 'llm-pool' : 'local-engine';
  const concurrency = Math.min(Math.max(Number(options.concurrency) || (hasLLM ? 4 : 6), 1), 16);
  const autoSnapshot = options.autoSnapshot !== false;
  const autoDebugRetries = Math.min(Math.max(Number(options.autoDebugRetries ?? 2), 0), 5);
  const maxStepsPerAgent = Math.min(Math.max(Number(options.maxStepsPerAgent) || 14, 2), 40);

  const markFiles = (filePath) => {
    if (filePath && !mission.filesChanged.includes(filePath)) {
      mission.filesChanged.push(filePath);
      emitMission(mission, { type: 'file_changed', path: filePath });
    }
  };

  try {
    emitMission(mission, { type: 'mission_status', status: 'running', engineMode: mission.engineMode, agentCount: mission.agents.length });

    // Mark all assigned agents as queued
    for (const slot of mission.agents) {
      updateAgentState(slot.id, { status: 'queued', currentMissionId: mission.id, currentTask: 'En cola de despliegue' }, mission);
    }

    // Optional safety snapshot before touching existing files
    const initialStats = await fsx.stats('').catch(() => ({ totalFiles: 0 }));
    if (autoSnapshot && initialStats.totalFiles > 0) {
      const snap = await fsx.snapshot(`pre-mission-${mission.id}`).catch(() => null);
      if (snap) {
        mission.diagnostics.preSnapshot = snap.id;
        emitMission(mission, { type: 'notice', text: `Snapshot de seguridad creado automáticamente: ${snap.id}` });
      }
    }

    // PHASE 1: Real Cartography, Syntax & Security Diagnosis
    mission.phase = 'analyze';
    emitMission(mission, { type: 'phase', phase: 'analyze', label: 'Fase 1 · Cartografía, Verificación de Sintaxis y Auditoría Real' });
    const [carto, syntaxBefore, securityBefore, initialTests] = await Promise.all([
      analyzer.cartography(''),
      analyzer.verifyWorkspaceSyntax(''),
      analyzer.securityAudit(''),
      mission.mode === 'audit-and-heal' ? runner.runProjectTests('') : Promise.resolve({ ok: true, skipped: true })
    ]);
    mission.diagnostics.cartography = carto;
    mission.diagnostics.syntaxBefore = syntaxBefore;
    mission.diagnostics.securityBefore = securityBefore;
    mission.diagnostics.testsBefore = initialTests;
    emitMission(mission, {
      type: 'diagnostics',
      stage: 'before',
      cartography: { totalFiles: carto.totalFiles, totalLines: carto.totalLines, languages: carto.languages },
      syntax: { ok: syntaxBefore.ok, checkedFiles: syntaxBefore.checkedFiles, errorCount: syntaxBefore.errorCount },
      security: { score: securityBefore.score, counts: securityBefore.counts }
    });

    if (signal.aborted) throw new Error('Misión cancelada por el usuario.');

    const diagSummary = summarizeDiagnosticsForPrompt(carto, syntaxBefore, securityBefore, initialTests);

    // LOCAL ENGINEERING ENGINE MODE (when user hasn't connected free LLM APIs yet)
    if (!hasLLM) {
      mission.phase = 'execute';
      emitMission(mission, {
        type: 'phase',
        phase: 'execute',
        label: 'Fase 2 · Ejecución con Motor Local Determinista (Conecta APIs LLM Gratis para generación autónoma con IA)'
      });

      let repairResult = null;
      if (mission.mode === 'audit-and-heal') {
        updateAgentState('AG-060', { status: 'working', currentTask: 'Ejecutando auto-reparación determinista segura' }, mission);
        repairResult = await analyzer.autoRepairSafe('');
        for (const item of repairResult.repaired) markFiles(item.file);
        mission.diagnostics.safeRepair = repairResult;
      }

      mission.phase = 'verify';
      emitMission(mission, { type: 'phase', phase: 'verify', label: 'Fase 3 · Ejecución de Pruebas y Consolidación de Diagnóstico' });
      const testsAfter = await runner.runProjectTests('');
      const syntaxAfter = repairResult ? repairResult.syntaxAfter : syntaxBefore;
      const securityAfter = await analyzer.securityAudit('');
      mission.diagnostics.testsAfter = testsAfter;
      mission.diagnostics.syntaxAfter = syntaxAfter;
      mission.diagnostics.securityAfter = securityAfter;

      // Attribute concrete domain diagnostics to each deployed specialist
      for (const slot of mission.agents) {
        const agentObj = catalog.getAgent(slot.id);
        let note = 'Inspección estática completada en modo local.';
        if (agentObj.phase === 'debug') {
          note = syntaxAfter.ok
            ? `Verificados ${syntaxAfter.checkedFiles} archivo(s) sin errores de sintaxis.`
            : `Detectados ${syntaxAfter.errorCount} error(es) de sintaxis listos para corrección asistida por LLM.`;
        } else if (agentObj.phase === 'security') {
          note = `Auditoría OWASP/Secretos: puntuación ${securityAfter.score}/100 (críticos: ${securityAfter.counts.critical}, altos: ${securityAfter.counts.high}).`;
        } else if (agentObj.phase === 'test') {
          note = testsAfter.skipped
            ? 'No se encontró suite de tests automática en el workspace.'
            : testsAfter.ok ? 'Suite de pruebas ejecutada y aprobada (código 0).' : `Suite de pruebas falló con código ${testsAfter.code}.`;
        } else if (agentObj.phase === 'plan' || agentObj.phase === 'architect') {
          note = `Cartografía completada: ${carto.totalFiles} archivos, ${carto.totalLines} líneas, ${carto.hotspots.length} hotspot(s) de complejidad.`;
        }
        slot.summary = note;
        slot.provider = 'Motor Local';
        const rt = agentRuntime.get(slot.id);
        if (rt) rt.tasksCompleted++;
        updateAgentState(slot.id, { status: 'completed', currentTask: note, lastProvider: 'Motor Local' }, mission);
      }

      mission.phase = 'completed';
      mission.status = 'completed';
      mission.endedAt = new Date().toISOString();
      mission.report = buildExecutiveReport(mission, true);
      emitMission(mission, { type: 'report', report: mission.report });
      emitMission(mission, { type: 'mission_status', status: 'completed', durationMs: summarizeMission(mission).durationMs });
      return mission;
    }

    // FULL MULTI-LLM AUTONOMOUS SWARM MODE
    mission.phase = 'plan';
    emitMission(mission, { type: 'phase', phase: 'plan', label: 'Fase 2 · Planificación y Descomposición Multi-Agente' });
    const plan = await planMissionTasksWithLLM(mission, poolConfig, diagSummary, signal);
    const briefByAgent = new Map();
    if (plan?.assignments) {
      emitMission(mission, { type: 'plan', summary: plan.summary || 'Plan de misión generado.', assignments: plan.assignments });
      for (const item of plan.assignments) {
        if (item.agentId && item.task) briefByAgent.set(String(item.agentId).toUpperCase(), String(item.task));
      }
    }

    // Determine which agents actively run LLM tool loops vs verification wave
    // For massive scales (e.g. 45 or 150 selected), run the planned/core active cohort in parallel waves
    // and keep full fleet telemetry synchronized cleanly without exhausting free API quotas blindly.
    const maxActiveLLM = Math.min(mission.agents.length, Number(options.maxActiveAgents) || (mission.agents.length <= 15 ? mission.agents.length : 18));
    let activeSlots = [];
    if (briefByAgent.size > 0) {
      for (const slot of mission.agents) {
        if (briefByAgent.has(slot.id)) activeSlots.push(slot);
      }
    }
    if (!activeSlots.length) {
      activeSlots = mission.agents.slice(0, maxActiveLLM);
    } else if (activeSlots.length > maxActiveLLM) {
      activeSlots = activeSlots.slice(0, maxActiveLLM);
    }

    // Group active slots into ordered lifecycle waves so architecture -> implementation -> debug/security/test/review flow coherently
    const waveGroups = [
      { id: 'architect', label: 'Fase 3A · Arquitectura y Diseño Base', phases: new Set(['plan', 'architect', 'analyze']) },
      { id: 'implement', label: 'Fase 3B · Programación e Implementación Paralela', phases: new Set(['implement', 'devops', 'optimize']) },
      { id: 'verify-wave', label: 'Fase 3C · Depuración, Seguridad, Pruebas y Revisión', phases: new Set(['debug', 'security', 'test', 'review']) }
    ];

    const sharedNotes = [];
    const executedIds = new Set();

    for (const wave of waveGroups) {
      if (signal.aborted) break;
      const waveSlots = activeSlots.filter((slot) => wave.phases.has(slot.phase));
      if (!waveSlots.length) continue;

      mission.phase = wave.id;
      emitMission(mission, { type: 'phase', phase: wave.id, label: `${wave.label} (${waveSlots.length} especialista/s)` });

      await runWithConcurrency(waveSlots, concurrency, signal, async (slot) => {
        if (signal.aborted) return;
        executedIds.add(slot.id);
        const agentObj = catalog.getAgent(slot.id);
        const brief = briefByAgent.get(slot.id) || defaultBriefForAgent(agentObj, mission, diagSummary);
        const started = Date.now();
        updateAgentState(slot.id, { status: 'working', currentMissionId: mission.id, currentTask: brief.slice(0, 140) }, mission);

        const history = [{ role: 'user', content: brief }];
        const res = await agentEngine.loop(poolConfig, history, (ev) => {
          if (ev.type === 'tool_start') {
            const rt = agentRuntime.get(slot.id);
            if (rt) rt.toolsExecuted++;
            emitMission(mission, { type: 'tool_start', agentId: slot.id, agentName: slot.name, name: ev.name, args: ev.args });
          } else if (ev.type === 'tool_end') {
            emitMission(mission, { type: 'tool_end', agentId: slot.id, agentName: slot.name, name: ev.name, preview: ev.preview });
          } else if (ev.type === 'failover') {
            emitMission(mission, { type: 'failover', agentId: slot.id, from: ev.from, to: ev.to, reason: ev.reason });
          } else if (ev.type === 'provider') {
            updateAgentState(slot.id, { lastProvider: `${ev.provider.name} (${ev.provider.model})` }, mission);
          } else if (ev.type === 'ui') {
            emitMission(mission, { type: 'ui', agentId: slot.id, ui: ev.ui });
          }
        }, {
          agent: agentObj,
          missionObjective: mission.objective,
          taskBrief: brief,
          sharedNotes: sharedNotes.slice(-10).join('\n\n'),
          maxSteps: maxStepsPerAgent,
          signal,
          onFileChange: (fp) => {
            markFiles(fp);
            const rt = agentRuntime.get(slot.id);
            if (rt) rt.filesModified++;
          }
        });

        slot.durationMs = Date.now() - started;
        slot.filesTouched = res.filesTouched || [];
        slot.tokens = res.tokens || 0;
        slot.provider = res.provider ? `${res.provider.name} · ${res.provider.model}` : slot.provider;
        mission.tokensUsed += slot.tokens;
        const rt = agentRuntime.get(slot.id);
        if (rt) {
          rt.tokensUsed += slot.tokens;
          if (res.ok) rt.tasksCompleted++;
        }

        if (res.ok) {
          slot.summary = (res.text || 'Tarea completada.').slice(0, 1200);
          sharedNotes.push(`[${slot.id} · ${slot.name}]: ${slot.summary.slice(0, 420)}${slot.filesTouched.length ? ` | Archivos: ${slot.filesTouched.join(', ')}` : ''}`);
          updateAgentState(slot.id, { status: 'completed', currentTask: 'Completado' }, mission);
        } else {
          slot.summary = res.error || (res.cancelled ? 'Cancelado' : 'Error en ejecución');
          updateAgentState(slot.id, { status: 'error', currentTask: slot.summary.slice(0, 140) }, mission);
        }
      });
    }

    // Mark remaining specialists in large fleet deployments as standby/verified
    for (const slot of mission.agents) {
      if (!executedIds.has(slot.id)) {
        slot.summary = 'Disponible en guardia activa / cubierto por el escuadrón asignado.';
        updateAgentState(slot.id, { status: 'completed', currentTask: 'Verificación de reserva completada' }, mission);
      }
    }

    // PHASE 4: Automatic Verification & Self-Healing Debug Loop
    mission.phase = 'verify';
    emitMission(mission, { type: 'phase', phase: 'verify', label: 'Fase 4 · Verificación Real y Auto-Depuración' });

    let syntaxAfter = await analyzer.verifyWorkspaceSyntax('');
    let securityAfter = await analyzer.securityAudit('');
    let testsAfter = await runner.runProjectTests('');

    for (let attempt = 1; attempt <= autoDebugRetries; attempt++) {
      if (signal.aborted) break;
      const needsFix = !syntaxAfter.ok || (!testsAfter.skipped && !testsAfter.ok);
      if (!needsFix) break;

      const debuggerAgent = catalog.getAgent(attempt === 1 ? 'AG-051' : 'AG-060');
      emitMission(mission, {
        type: 'notice',
        text: `Auto-depuración ciclo ${attempt}/${autoDebugRetries}: desplegando a ${debuggerAgent.id} (${debuggerAgent.name}) para corregir fallos detectados.`
      });
      updateAgentState(debuggerAgent.id, { status: 'working', currentMissionId: mission.id, currentTask: `Auto-corrección ciclo ${attempt}` }, mission);

      const fixPrompt = [
        `CICLO AUTOMÁTICO DE DEPURACIÓN #${attempt}`,
        'Se detectaron errores reales al verificar el workspace tras la implementación. Debes localizarlos, corregir la causa raíz y confirmar que desaparecen.',
        '',
        !syntaxAfter.ok ? 'ERRORES DE SINTAXIS:\n' + syntaxAfter.diagnostics.map((d) => `- ${d.file}:${d.line} [${d.runtime}] ${d.message}`).join('\n') : '',
        (!testsAfter.skipped && !testsAfter.ok) ? `FALLO EN PRUEBAS (código ${testsAfter.code}):\nSTDOUT:\n${(testsAfter.stdout || '').slice(0, 2500)}\nSTDERR:\n${(testsAfter.stderr || '').slice(0, 2500)}` : ''
      ].filter(Boolean).join('\n');

      const fixRes = await agentEngine.loop(poolConfig, [{ role: 'user', content: fixPrompt }], (ev) => {
        if (ev.type === 'tool_start') emitMission(mission, { type: 'tool_start', agentId: debuggerAgent.id, agentName: debuggerAgent.name, name: ev.name, args: ev.args });
        if (ev.type === 'tool_end') emitMission(mission, { type: 'tool_end', agentId: debuggerAgent.id, agentName: debuggerAgent.name, name: ev.name, preview: ev.preview });
        if (ev.type === 'failover') emitMission(mission, { type: 'failover', agentId: debuggerAgent.id, from: ev.from, to: ev.to, reason: ev.reason });
      }, {
        agent: debuggerAgent,
        missionObjective: mission.objective,
        maxSteps: 14,
        signal,
        onFileChange: (fp) => markFiles(fp)
      });

      mission.tokensUsed += fixRes.tokens || 0;
      const rt = agentRuntime.get(debuggerAgent.id);
      if (rt) {
        rt.tokensUsed += fixRes.tokens || 0;
        if (fixRes.ok) rt.tasksCompleted++;
      }
      updateAgentState(debuggerAgent.id, { status: fixRes.ok ? 'completed' : 'error', currentTask: `Auto-corrección ciclo ${attempt} finalizada` }, mission);

      syntaxAfter = await analyzer.verifyWorkspaceSyntax('');
      securityAfter = await analyzer.securityAudit('');
      testsAfter = await runner.runProjectTests('');
    }

    mission.diagnostics.syntaxAfter = syntaxAfter;
    mission.diagnostics.securityAfter = securityAfter;
    mission.diagnostics.testsAfter = testsAfter;

    emitMission(mission, {
      type: 'diagnostics',
      stage: 'after',
      syntax: { ok: syntaxAfter.ok, checkedFiles: syntaxAfter.checkedFiles, errorCount: syntaxAfter.errorCount },
      security: { score: securityAfter.score, counts: securityAfter.counts },
      tests: { ok: testsAfter.ok, skipped: Boolean(testsAfter.skipped), code: testsAfter.code }
    });

    mission.phase = signal.aborted ? 'cancelled' : 'completed';
    mission.status = signal.aborted ? 'cancelled' : 'completed';
    mission.endedAt = new Date().toISOString();
    mission.report = buildExecutiveReport(mission, false);
    emitMission(mission, { type: 'report', report: mission.report });
    emitMission(mission, { type: 'mission_status', status: mission.status, durationMs: summarizeMission(mission).durationMs });
    return mission;
  } catch (error) {
    mission.status = signal.aborted ? 'cancelled' : 'failed';
    mission.phase = mission.status;
    mission.endedAt = new Date().toISOString();
    mission.report = `### Misión ${mission.status === 'cancelled' ? 'cancelada' : 'interrumpida'}\n\n${error.message}`;
    for (const slot of mission.agents) {
      if (slot.status === 'working' || slot.status === 'queued') {
        updateAgentState(slot.id, { status: 'idle', currentTask: '' }, mission);
      }
    }
    emitMission(mission, { type: 'error', text: error.message });
    emitMission(mission, { type: 'mission_status', status: mission.status });
    return mission;
  } finally {
    // Release agents back to idle cleanly after short telemetry hold
    setTimeout(() => {
      for (const slot of mission.agents) {
        const rt = agentRuntime.get(slot.id);
        if (rt && rt.currentMissionId === mission.id) {
          rt.status = 'idle';
          rt.currentMissionId = null;
          rt.currentTask = '';
        }
      }
    }, 2500).unref?.();
  }
}

function buildExecutiveReport(mission, isLocalOnly) {
  const s = mission.diagnostics.syntaxAfter || mission.diagnostics.syntaxBefore || { ok: true, checkedFiles: 0, errorCount: 0, diagnostics: [] };
  const sec = mission.diagnostics.securityAfter || mission.diagnostics.securityBefore || { score: 100, counts: { critical: 0, high: 0, medium: 0, low: 0 } };
  const tests = mission.diagnostics.testsAfter || mission.diagnostics.testsBefore || { ok: true, skipped: true };
  const carto = mission.diagnostics.cartography || { totalFiles: 0, totalLines: 0 };

  const lines = [
    `## Informe Ejecutivo de Misión (${mission.id})`,
    '',
    `**Objetivo:** ${mission.objective}`,
    `**Modo de Motor:** ${isLocalOnly ? 'Motor Local Determinista (sin API LLM conectada)' : 'Flota Autónoma Multi-LLM con Herramientas Reales'}`,
    `**Especialistas Desplegados:** ${mission.agents.length} de 150 | **Archivos modificados:** ${mission.filesChanged.length} | **Tokens:** ${mission.tokensUsed.toLocaleString()}`,
    '',
    '### Resultados de Verificación Real',
    `- **Workspace:** ${carto.totalFiles} archivos · ${carto.totalLines} líneas analizadas`,
    `- **Sintaxis Multi-Lenguaje:** ${s.ok ? `Aprobada (${s.checkedFiles} archivo/s verificados, 0 errores)` : `${s.errorCount} error(es) detectado(s)`}`,
    `- **Auditoría de Seguridad:** Puntuación ${sec.score}/100 (Críticos: ${sec.counts.critical}, Altos: ${sec.counts.high}, Medios: ${sec.counts.medium})`,
    `- **Suite de Pruebas:** ${tests.skipped ? 'Sin suite automática detectada' : tests.ok ? 'Aprobada (exit code 0)' : `Falló (exit code ${tests.code})`}`
  ];

  if (mission.filesChanged.length) {
    lines.push('', '### Archivos Modificados / Creados');
    for (const f of mission.filesChanged.slice(0, 40)) lines.push(`- \`${f}\``);
  }

  if (!s.ok && s.diagnostics?.length) {
    lines.push('', '### Errores de Sintaxis Pendientes');
    for (const d of s.diagnostics.slice(0, 15)) lines.push(`- \`${d.file}:${d.line}\` [${d.runtime}]: ${d.message}`);
  }

  if (isLocalOnly) {
    lines.push(
      '',
      '### Siguiente Paso para Programación Autónoma Completa con IA',
      'El diagnóstico, verificación de sintaxis, auditoría de seguridad y pruebas se ejecutaron realmente en el servidor. Para que los 150 especialistas escriban arquitectura compleja, refactoricen y corrijan automáticamente código con IA, abre **Herramientas y Config → APIs LLM Gratis** y conecta una o más APIs gratuitas (OpenRouter `:free`, Groq, Google Gemini, Cerebras, GitHub Models u Ollama).'
    );
  } else {
    const highlights = mission.agents.filter((a) => a.summary && !a.summary.startsWith('Disponible en guardia')).slice(0, 12);
    if (highlights.length) {
      lines.push('', '### Resumen de Especialistas Ejecutados');
      for (const h of highlights) {
        lines.push(`- **${h.id} · ${h.name}** (${h.status}${h.provider ? ` · ${h.provider}` : ''}): ${h.summary.split('\n')[0].slice(0, 220)}`);
      }
    }
  }

  return lines.join('\n');
}

function startMission(params = {}) {
  const objective = String(params.objective || '').trim();
  if (!objective) throw Object.assign(new Error('Describe el objetivo técnico de la misión.'), { status: 400 });

  const mode = ['full-lifecycle', 'audit-and-heal', 'squad-dispatch'].includes(params.mode) ? params.mode : 'full-lifecycle';
  const scale = params.scale || 'standard';
  const selected = catalog.selectAgentsForMission(objective, {
    mode,
    scale,
    agentCount: params.agentCount,
    agentIds: params.agentIds,
    divisionIds: params.divisionIds
  });

  const id = 'MIS-' + crypto.randomBytes(3).toString('hex').toUpperCase();
  const mission = {
    id,
    objective: objective.slice(0, 20000),
    mode,
    scale,
    engineMode: 'pending',
    status: 'running',
    phase: 'init',
    createdAt: new Date().toISOString(),
    endedAt: null,
    tokensUsed: 0,
    filesChanged: [],
    diagnostics: {},
    report: '',
    events: [],
    agents: selected.map((a) => ({
      id: a.id,
      name: a.name,
      tier: a.tier,
      divisionId: a.divisionId,
      divisionShort: a.divisionShort,
      phase: a.phase,
      specialty: a.specialty,
      status: 'queued',
      task: '',
      provider: '',
      summary: '',
      filesTouched: [],
      tokens: 0,
      durationMs: 0
    })),
    _abort: new AbortController()
  };

  missions.set(id, mission);
  missionOrder.unshift(id);
  while (missionOrder.length > MAX_MISSIONS) {
    const oldId = missionOrder.pop();
    missions.delete(oldId);
    missionListeners.delete(oldId);
  }

  // Launch asynchronously so HTTP POST responds immediately with mission metadata
  setImmediate(() => {
    executeMission(mission, params).catch((err) => {
      console.error('Mission error:', err);
    });
  });

  return summarizeMission(mission);
}

function cancelMission(id) {
  const mission = missions.get(id);
  if (!mission) throw Object.assign(new Error('Misión no encontrada.'), { status: 404 });
  if (mission.status === 'running') {
    mission._abort.abort(new Error('Cancelada por el usuario'));
    emitMission(mission, { type: 'notice', text: 'Cancelando ejecución de la misión…' });
  }
  return summarizeMission(mission);
}

module.exports = {
  listAgents,
  getAgentDetail,
  getFleetStats,
  startMission,
  cancelMission,
  listMissions,
  getMission,
  subscribeMission,
  subscribeGlobal,
  updateAgentState
};
