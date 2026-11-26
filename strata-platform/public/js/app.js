'use strict';

(() => {
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const CONFIG_KEY = 'strata.engineering.v3';
  const THREADS_KEY = 'strata.threads.v3';

  const state = {
    theme: 'light',
    presets: [],
    divisions: [],
    agents: [],
    agentFilter: { q: '', division: '' },
    selectedSquad: new Set(),

    // Primary two-phase workflow: 'plan' (default conversational planning) | 'build' (150-agent execution)
    phase: 'plan',
    buildScale: 'standard', // standard | compact | enterprise | full-fleet | audit | single | custom
    singleAgentId: 'AG-141',

    // Conversations
    threads: [],
    activeThreadId: null,
    busy: false,
    activeMissionId: null,
    activeStream: null,
    activeAbort: null,

    // Multi-LLM Pool & Settings
    config: {
      providers: [],
      strategy: 'round-robin',
      concurrency: 4,
      maxSteps: 18,
      temperature: 0.2,
      tools: true,
      divisionMap: {},
      agentMap: {},
      autoSnapshot: true,
      autoDebugRetries: 2,
      theme: 'light',
      fontSize: 14,
      wordWrap: true,
      autosave: false
    },

    // Workspace Panel
    workspaceOpen: false,
    wsTab: 'code',
    tree: [],
    expanded: new Set(),
    selectedDir: '',
    tabs: new Map(),
    activePath: null,
    editor: null,
    monacoReady: false,
    promptResolve: null,

    terminal: null,
    termFit: null,
    termWs: null,
    termCompat: false,
    lastSyntax: null,
    lastSecurity: null,
    lastDiagnostics: []
  };

  async function api(url, options = {}) {
    const headers = new Headers(options.headers || {});
    let body = options.body;
    if (body != null && typeof body !== 'string' && !(body instanceof FormData)) {
      headers.set('Content-Type', 'application/json');
      body = JSON.stringify(body);
    }
    const res = await fetch(url, { ...options, headers, body, credentials: 'same-origin' });
    const ct = res.headers.get('content-type') || '';
    const data = ct.includes('application/json')
      ? await res.json().catch(() => ({}))
      : { message: await res.text().catch(() => '') };
    if (!res.ok || data.ok === false) {
      throw new Error(data.error || data.message || `Error HTTP ${res.status}`);
    }
    return data;
  }

  function esc(str) {
    return String(str == null ? '' : str).replace(/[&<>"']/g, (c) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[c]);
  }

  function renderMarkdown(text) {
    const source = String(text || '').replace(/\r\n/g, '\n');
    const blocks = [];
    let safe = esc(source).replace(/```([\w-]*)\n?([\s\S]*?)```/g, (_m, lang, code) => {
      const cleanCode = code.replace(/^\n|\n$/g, '');
      const idx = blocks.push(
        `<div class="code-block-wrap"><div class="code-block-head"><span>${esc(lang || 'código')}</span><button type="button" class="copy-code-btn">Copiar</button></div><pre><code>${cleanCode}</code></pre></div>`
      ) - 1;
      return `\u0000BLK${idx}\u0000`;
    });
    safe = safe
      .replace(/`([^`\n]+)`/g, '<code>$1</code>')
      .replace(/^###\s+(.+)$/gm, '<h3>$1</h3>')
      .replace(/^##\s+(.+)$/gm, '<h2>$1</h2>')
      .replace(/^#\s+(.+)$/gm, '<h1>$1</h1>')
      .replace(/^&gt;\s?(.+)$/gm, '<blockquote>$1</blockquote>')
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
    const lines = safe.split('\n');
    const out = [];
    let para = [];
    let list = null;
    const flushPara = () => { if (para.length) { out.push(`<p>${para.join('<br>')}</p>`); para = []; } };
    const flushList = () => { if (list) { out.push(`<${list.tag}>${list.items.map((i) => `<li>${i}</li>`).join('')}</${list.tag}>`); list = null; } };
    for (const line of lines) {
      const blk = line.match(/^\u0000BLK(\d+)\u0000$/);
      if (blk) { flushPara(); flushList(); out.push(blocks[Number(blk[1])]); continue; }
      if (/^<(?:h[1-3]|blockquote)>.*<\/(?:h[1-3]|blockquote)>$/.test(line)) { flushPara(); flushList(); out.push(line); continue; }
      const ul = line.match(/^\s*[-*]\s+(.+)$/);
      const ol = line.match(/^\s*\d+[.)]\s+(.+)$/);
      if (ul || ol) {
        flushPara();
        const tag = ol ? 'ol' : 'ul';
        if (list && list.tag !== tag) flushList();
        if (!list) list = { tag, items: [] };
        list.items.push((ul || ol)[1]);
        continue;
      }
      if (!line.trim()) { flushPara(); flushList(); continue; }
      flushList();
      para.push(line);
    }
    flushPara();
    flushList();
    return out.join('') || '';
  }

  function toast(message, kind = 'success', ms = 3200) {
    const root = $('#toast-root');
    const el = document.createElement('div');
    el.className = `toast ${kind}`;
    el.textContent = message;
    root.append(el);
    setTimeout(() => el.remove(), ms);
  }

  function langFor(rel) {
    const base = String(rel || '').split('/').pop().toLowerCase();
    const ext = base.includes('.') ? base.slice(base.lastIndexOf('.')) : '';
    const map = {
      '.js': 'javascript', '.mjs': 'javascript', '.cjs': 'javascript',
      '.ts': 'typescript', '.tsx': 'typescript', '.py': 'python',
      '.html': 'html', '.css': 'css', '.json': 'json', '.md': 'markdown',
      '.sh': 'shell', '.bash': 'shell', '.c': 'c', '.cpp': 'cpp',
      '.go': 'go', '.rs': 'rust', '.java': 'java', '.sql': 'sql', '.yml': 'yaml', '.yaml': 'yaml'
    };
    return map[ext] || 'plaintext';
  }

  /* ===================== CONFIG & STORAGE ===================== */

  function loadStorage() {
    try {
      const saved = JSON.parse(
        localStorage.getItem(CONFIG_KEY) ||
        localStorage.getItem('strata.engineering.v2') ||
        localStorage.getItem('strata.engineering.v1') || '{}'
      );
      state.config = { ...state.config, ...saved };
      if (!Array.isArray(state.config.providers)) state.config.providers = [];
      if (!state.config.divisionMap || typeof state.config.divisionMap !== 'object') state.config.divisionMap = {};
      if (!state.config.agentMap || typeof state.config.agentMap !== 'object') state.config.agentMap = {};
    } catch { /* ignore */ }
    try {
      const threads = JSON.parse(localStorage.getItem(THREADS_KEY) || '[]');
      if (Array.isArray(threads)) state.threads = threads.slice(0, 30);
    } catch { state.threads = []; }
    applyTheme(state.config.theme || 'light');
  }

  function saveConfig() {
    localStorage.setItem(CONFIG_KEY, JSON.stringify(state.config));
    updatePoolStatusUI();
    renderDivisionMatrix();
  }

  function saveThreads() {
    try { localStorage.setItem(THREADS_KEY, JSON.stringify(state.threads.slice(0, 30))); } catch { /* ignore */ }
    renderThreadsSidebar();
    updateQuickBuildButton();
  }

  function applyTheme(theme) {
    state.theme = theme === 'dark' ? 'dark' : 'light';
    state.config.theme = state.theme;
    document.documentElement.setAttribute('data-theme', state.theme);
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = state.theme === 'dark' ? '#111317' : '#fafafa';
    const sel = $('#setting-theme');
    if (sel) sel.value = state.theme;
    if (window.monaco && state.monacoReady) {
      window.monaco.editor.setTheme(state.theme === 'dark' ? 'strata-dark' : 'strata-light');
    }
  }

  function buildPoolPayload() {
    return {
      providers: state.config.providers.filter((p) => p.enabled !== false && p.model && p.model.trim()),
      strategy: state.config.strategy || 'round-robin',
      temperature: Number(state.config.temperature ?? 0.2),
      maxSteps: Number(state.config.maxSteps || 18),
      tools: state.config.tools !== false,
      divisionMap: state.config.divisionMap || {},
      agentMap: state.config.agentMap || {}
    };
  }

  function updatePoolStatusUI() {
    const active = state.config.providers.filter((p) => p.enabled !== false && p.model && p.model.trim());
    const count = active.length;
    $('#connected-providers-count').textContent = String(state.config.providers.length);
    $('#sidebar-pool-dot').classList.toggle('is-ready', count > 0);
    $('#composer-pool-dot').classList.toggle('is-ready', count > 0);
    $('#sidebar-pool-badge').textContent = count > 0 ? `${count} API${count > 1 ? 's' : ''}` : 'Local';
    $('#sidebar-pool-sub').textContent = count > 0
      ? `Activas: ${active.slice(0, 3).map((p) => p.name).join(', ')}`
      : 'Conecta OpenRouter, Groq, Gemini, Cerebras u Ollama para conversar y construir con IA.';
    $('#composer-pool-label').textContent = count > 0 ? `${count} API${count > 1 ? 's' : ''}` : 'Local';
  }

  /* ===================== PHASE (PLANIFICAR vs CONSTRUIR) & SQUAD ===================== */

  function setPhase(phase) {
    state.phase = phase === 'build' ? 'build' : 'plan';
    $('#phase-plan-btn').classList.toggle('is-active', state.phase === 'plan');
    $('#phase-plan-btn').setAttribute('aria-selected', String(state.phase === 'plan'));
    $('#phase-build-btn').classList.toggle('is-active', state.phase === 'build');
    $('#phase-build-btn').setAttribute('aria-selected', String(state.phase === 'build'));

    const dot = $('#composer-phase-dot');
    dot.classList.toggle('plan', state.phase === 'plan');
    dot.classList.toggle('build', state.phase === 'build');

    const heroChip = $('#hero-phase-chip');
    if (heroChip) {
      heroChip.innerHTML = state.phase === 'plan'
        ? '<span class="phase-dot plan"></span><span>Modo Planificar · Conversa primero sin modificar archivos</span>'
        : `<span class="phase-dot build"></span><span>Modo Construir · ${esc(scaleLabel(state.buildScale))} listos para ejecutar</span>`;
    }

    $('#composer-input').placeholder = state.phase === 'plan'
      ? 'Conversa con la IA para planificar tu proyecto, arquitectura o solución…'
      : 'Da la orden a los 150 agentes para construir, depurar o ejecutar ahora…';

    updateComposerModeLabel();
    updateQuickBuildButton();
  }

  function scaleLabel(scale) {
    const singleAgent = state.agents.find((a) => a.id === state.singleAgentId);
    const map = {
      standard: '15 agentes',
      compact: '6 agentes',
      enterprise: '45 agentes',
      'full-fleet': '150 agentes',
      audit: 'Auditoría',
      single: singleAgent?.id || '1-a-1',
      custom: `Escuadrón (${state.selectedSquad.size})`
    };
    return map[scale] || '15 agentes';
  }

  function setBuildScale(scale) {
    state.buildScale = scale;
    $$('.mode-option').forEach((el) => el.classList.toggle('is-selected', el.dataset.buildScale === scale));
    const singleAgent = state.agents.find((a) => a.id === state.singleAgentId);
    $('#mode-single-label').textContent = `Especialista 1-a-1 · ${singleAgent?.name || 'Strata Agent'} (${singleAgent?.id || 'AG-141'})`;
    updateComposerModeLabel();
    $('#mode-popover').classList.add('hidden');
  }

  function updateComposerModeLabel() {
    $('#composer-mode-label').textContent = state.phase === 'plan'
      ? 'Planificar'
      : `Construir · ${scaleLabel(state.buildScale)}`;
  }

  function updateQuickBuildButton() {
    const thread = getActiveThread();
    const hasPlanChat = Boolean(thread && thread.messages.some((m) => m.role === 'user'));
    $('#quick-build-from-plan-btn').classList.toggle('hidden', !(state.phase === 'plan' && hasPlanChat && !state.busy));
  }

  /* ===================== SHELL NAVIGATION (SIDEBAR, WORKSPACE, DRAWER BARS) ===================== */

  const isMobile = () => window.innerWidth <= 960;

  function openSidebar() {
    if (isMobile()) {
      $('#strata-app').classList.add('sidebar-open-mobile');
      $('#sidebar-backdrop').classList.remove('hidden');
    } else {
      $('#strata-app').classList.remove('sidebar-collapsed');
    }
  }

  function closeSidebar() {
    if (isMobile()) {
      $('#strata-app').classList.remove('sidebar-open-mobile');
      $('#sidebar-backdrop').classList.add('hidden');
    } else {
      $('#strata-app').classList.add('sidebar-collapsed');
    }
  }

  function toggleSidebar() {
    if (isMobile()) {
      if ($('#strata-app').classList.contains('sidebar-open-mobile')) closeSidebar();
      else openSidebar();
    } else {
      $('#strata-app').classList.toggle('sidebar-collapsed');
    }
    setTimeout(() => { state.editor?.layout(); fitTerminal(); }, 220);
  }

  function openWorkspace(tab = state.wsTab || 'code') {
    if (isMobile()) closeSidebar();
    state.workspaceOpen = true;
    $('#workspace-panel').classList.remove('hidden');
    $('#toggle-workspace-btn').classList.add('is-active');
    switchWorkspaceTab(tab);
  }

  function closeWorkspace() {
    state.workspaceOpen = false;
    $('#workspace-panel').classList.add('hidden');
    $('#toggle-workspace-btn').classList.remove('is-active');
  }

  function switchWorkspaceTab(tab) {
    state.wsTab = tab;
    $$('.ws-tab').forEach((b) => b.classList.toggle('is-active', b.dataset.wsTab === tab));
    $$('.ws-pane').forEach((p) => p.classList.toggle('is-active', p.id === `ws-pane-${tab}`));
    if (tab === 'code') setTimeout(() => state.editor?.layout(), 60);
    if (tab === 'terminal') { ensureTerminal(); setTimeout(() => fitTerminal(), 80); }
    if (tab === 'diagnostics' && !state.lastSyntax) runSyntaxCheck().catch(() => {});
  }

  function closeAllOverlays() {
    $('#overlay-backdrop').classList.add('hidden');
    $('#agents-modal').classList.add('hidden');
    $('#tools-drawer').classList.add('hidden');
    $('#mode-popover').classList.add('hidden');
  }

  function openToolsDrawerBar(barId = 'bar-presets') {
    if (isMobile()) closeSidebar();
    closeAllOverlays();
    $('#overlay-backdrop').classList.remove('hidden');
    $('#tools-drawer').classList.remove('hidden');
    $$('.bar-card', $('#tools-drawer')).forEach((el) => {
      if (barId === 'bar-presets') {
        el.open = el.id === 'bar-presets' || el.id === 'bar-connected';
      } else {
        el.open = el.id === barId;
      }
    });
    const target = document.getElementById(barId);
    if (target) setTimeout(() => target.scrollIntoView({ behavior: 'smooth', block: 'start' }), 40);
    if (barId === 'bar-git') refreshGitStatus().catch(() => {});
  }

  function openAgentsModal() {
    if (isMobile()) closeSidebar();
    closeAllOverlays();
    $('#overlay-backdrop').classList.remove('hidden');
    $('#agents-modal').classList.remove('hidden');
    renderAgentsGrid();
    setTimeout(() => $('#agent-search-input').focus(), 60);
  }

  /* ===================== CONVERSATIONS & TWO-PHASE STREAMING ===================== */

  function getActiveThread() {
    return state.threads.find((t) => t.id === state.activeThreadId) || null;
  }

  function createNewThread(firstPrompt = '') {
    const thread = {
      id: `th-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 5)}`,
      title: firstPrompt ? firstPrompt.slice(0, 44) : 'Nueva conversación',
      createdAt: new Date().toISOString(),
      messages: []
    };
    state.threads.unshift(thread);
    state.activeThreadId = thread.id;
    saveThreads();
    renderActiveThread();
    return thread;
  }

  function renderThreadsSidebar() {
    const list = $('#threads-list');
    $('#sidebar-threads-count').textContent = String(state.threads.length);
    list.replaceChildren();
    if (!state.threads.length) {
      list.innerHTML = '<p class="bar-hint">Aún no hay conversaciones guardadas.</p>';
      return;
    }
    for (const th of state.threads) {
      const row = document.createElement('div');
      row.className = `thread-item ${th.id === state.activeThreadId ? 'is-active' : ''}`;
      row.innerHTML = `<span class="thread-title">${esc(th.title)}</span><button type="button" class="thread-del" title="Eliminar">×</button>`;
      row.addEventListener('click', (e) => {
        if (e.target.closest('.thread-del')) {
          state.threads = state.threads.filter((x) => x.id !== th.id);
          if (state.activeThreadId === th.id) state.activeThreadId = state.threads[0]?.id || null;
          saveThreads();
          renderActiveThread();
        } else {
          state.activeThreadId = th.id;
          if (isMobile()) closeSidebar();
          renderThreadsSidebar();
          renderActiveThread();
          updateQuickBuildButton();
        }
      });
      list.append(row);
    }
  }

  function renderActiveThread() {
    const thread = getActiveThread();
    const hasMessages = Boolean(thread && thread.messages.length);
    $('#welcome-hero').classList.toggle('hidden', hasMessages);
    $('#messages-stream').classList.toggle('hidden', !hasMessages);
    updateQuickBuildButton();
    if (!hasMessages) return;

    const stream = $('#messages-stream');
    stream.replaceChildren();
    for (const m of thread.messages) stream.append(renderMessageElement(m));
    scrollChatToBottom();
  }

  function renderMessageElement(m) {
    if (m.role === 'user') {
      const wrap = document.createElement('div');
      wrap.className = 'msg-user';
      wrap.innerHTML = `<div class="msg-user-bubble">${esc(m.text)}</div>`;
      return wrap;
    }

    const wrap = document.createElement('article');
    wrap.className = 'msg-assistant';
    wrap.dataset.msgId = m.id;

    const isPlan = m.kind === 'plan';
    const badgeCls = m.status === 'running' ? 'is-running'
      : m.status === 'error' ? 'is-error'
      : isPlan ? 'is-plan' : 'is-done';
    const badgeText = m.status === 'running' ? (m.phaseLabel || (isPlan ? 'Analizando…' : 'Construyendo…'))
      : m.status === 'error' ? 'Error'
      : m.status === 'cancelled' ? 'Detenido'
      : isPlan ? (m.planReady ? 'Plan listo para aprobar' : 'Planificación · Solo lectura')
      : 'Completado';

    const head = document.createElement('div');
    head.className = 'msg-head';
    head.innerHTML = `
      <div class="msg-avatar ${isPlan ? 'is-plan' : 'is-build'}">${esc(m.avatar || (isPlan ? 'A' : 'S'))}</div>
      <span class="msg-author">${esc(m.author || (isPlan ? 'Arquitecto Principal' : 'Equipo Strata'))}</span>
      <span class="msg-badge ${badgeCls}">${esc(badgeText)}</span>
    `;
    wrap.append(head);

    // Collapsible Activity / Tool Inspection Bar
    if ((m.agents && m.agents.length) || (m.steps && m.steps.length)) {
      const det = document.createElement('details');
      det.className = 'activity-bar';
      if (m.status === 'running') det.open = true;
      const doneCount = (m.agents || []).filter((a) => a.status === 'completed').length;
      const title = isPlan
        ? `Inspección en solo lectura (${m.steps.length} consulta${m.steps.length === 1 ? '' : 's'})`
        : `${m.agents?.length ? `${m.agents.length} especialistas (${doneCount} listos)` : 'Actividad'} · ${m.steps?.length || 0} acción${(m.steps?.length || 0) === 1 ? '' : 'es'}`;
      det.innerHTML = `<summary><span>${esc(title)}</span></summary>`;

      const body = document.createElement('div');
      body.className = 'activity-body';
      if (m.agents?.length) {
        const chips = document.createElement('div');
        chips.className = 'agents-chips';
        for (const a of m.agents.slice(0, 18)) {
          const cls = a.status === 'working' ? 'is-working' : a.status === 'completed' ? 'is-done' : '';
          const sym = a.status === 'completed' ? '✓' : a.status === 'working' ? '●' : '○';
          const chip = document.createElement('span');
          chip.className = `agent-chip ${cls}`;
          chip.textContent = `${sym} ${a.id} ${a.name}`;
          chips.append(chip);
        }
        if (m.agents.length > 18) {
          const more = document.createElement('span');
          more.className = 'agent-chip';
          more.textContent = `+${m.agents.length - 18} más`;
          chips.append(more);
        }
        body.append(chips);
      }
      if (m.steps?.length) {
        const steps = document.createElement('div');
        steps.className = 'steps-list';
        for (const st of m.steps.slice(-24)) {
          const row = document.createElement('div');
          row.className = 'step-row';
          row.innerHTML = `<span class="step-icon ${esc(st.kind || 'ok')}">${st.kind === 'err' ? '✕' : st.kind === 'warn' ? '↻' : st.kind === 'run' ? '▶' : '✓'}</span><span class="step-text">${esc(st.text)}</span>`;
          steps.append(row);
        }
        body.append(steps);
      }
      det.append(body);
      wrap.append(det);
    }

    // Inline File Artifacts
    if (m.files?.length) {
      const filesRow = document.createElement('div');
      filesRow.className = 'artifacts-row';
      for (const fp of m.files) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'artifact-card';
        btn.textContent = `📄 ${fp}`;
        btn.addEventListener('click', () => {
          openWorkspace('code');
          openFile(fp).catch((err) => toast(err.message, 'error'));
        });
        filesRow.append(btn);
      }
      wrap.append(filesRow);
    }

    // Editorial Markdown Response
    if (m.content) {
      const md = document.createElement('div');
      md.className = 'msg-markdown';
      md.innerHTML = renderMarkdown(m.content);
      $$('.copy-code-btn', md).forEach((btn) => {
        btn.addEventListener('click', () => {
          const code = btn.closest('.code-block-wrap')?.querySelector('pre code')?.textContent || '';
          navigator.clipboard?.writeText(code).then(() => toast('Código copiado.')).catch(() => {});
        });
      });
      wrap.append(md);
    }

    // Plan Approval Card when Architect finishes a plan!
    if (isPlan && m.status !== 'running' && m.planReady && !m.planApproved) {
      const card = document.createElement('div');
      card.className = 'plan-approve-card';
      card.innerHTML = `
        <div class="plan-approve-head">
          <strong>¿Aprobamos este plan y comenzamos a construir?</strong>
          <span>Esperando tu orden</span>
        </div>
        <div class="plan-approve-controls">
          <select class="field sm plan-scale-select">
            <option value="standard" ${state.buildScale === 'standard' ? 'selected' : ''}>Escuadrón Inteligente (15 agentes · Recomendado)</option>
            <option value="compact" ${state.buildScale === 'compact' ? 'selected' : ''}>Equipo Rápido (6 agentes)</option>
            <option value="enterprise" ${state.buildScale === 'enterprise' ? 'selected' : ''}>Proyecto Grande (45 agentes)</option>
            <option value="full-fleet" ${state.buildScale === 'full-fleet' ? 'selected' : ''}>Flota Completa (150 especialistas)</option>
            ${state.selectedSquad.size ? `<option value="custom" ${state.buildScale === 'custom' ? 'selected' : ''}>Escuadrón Manual (${state.selectedSquad.size})</option>` : ''}
          </select>
          <button type="button" class="btn btn-primary btn-sm approve-go-btn">✓ Aprobar y construir</button>
          <button type="button" class="btn btn-subtle btn-sm keep-plan-btn">Seguir ajustando</button>
        </div>
      `;
      $('.approve-go-btn', card).addEventListener('click', () => {
        const chosenScale = $('.plan-scale-select', card).value;
        m.planApproved = true;
        setBuildScale(chosenScale);
        approvePlanAndBuild(m.content);
      });
      $('.keep-plan-btn', card).addEventListener('click', () => {
        $('#composer-input').focus();
      });
      wrap.append(card);
    }

    // Quick CTAs when API not connected or files created
    if (m.status !== 'running' && (m.needsApiCta || m.files?.length)) {
      const row = document.createElement('div');
      row.className = 'msg-cta-row';
      if (m.needsApiCta) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn btn-primary btn-xs';
        btn.textContent = '+ Conectar API LLM gratis';
        btn.addEventListener('click', () => openToolsDrawerBar('bar-presets'));
        row.append(btn);
      }
      if (m.files?.length) {
        const btn2 = document.createElement('button');
        btn2.type = 'button';
        btn2.className = 'btn btn-subtle btn-xs';
        btn2.textContent = 'Ver código en espacio de trabajo';
        btn2.addEventListener('click', () => { openWorkspace('code'); openFile(m.files[0]).catch(() => {}); });
        row.append(btn2);
      }
      wrap.append(row);
    }

    return wrap;
  }

  function updateMessageInDOM(m) {
    const existing = $(`[data-msg-id="${m.id}"]`);
    const fresh = renderMessageElement(m);
    if (existing) existing.replaceWith(fresh);
    else $('#messages-stream').append(fresh);
    scrollChatToBottom();
  }

  function scrollChatToBottom() {
    const sc = $('#chat-scroll');
    sc.scrollTop = sc.scrollHeight;
  }

  function setBusy(isBusy) {
    state.busy = isBusy;
    const btn = $('#composer-send-btn');
    btn.classList.toggle('is-stop', isBusy);
    btn.title = isBusy ? 'Detener' : 'Enviar';
    btn.innerHTML = isBusy
      ? '<svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor"/></svg>'
      : '<svg viewBox="0 0 24 24"><path d="M12 19V5M5 12l7-7 7 7"/></svg>';
    updateQuickBuildButton();
  }

  async function stopActiveRun() {
    if (state.activeMissionId) {
      await api(`/api/missions/${encodeURIComponent(state.activeMissionId)}/cancel`, { method: 'POST' }).catch(() => {});
    }
    if (state.activeStream) { state.activeStream.close(); state.activeStream = null; }
    if (state.activeAbort) { state.activeAbort.abort(); state.activeAbort = null; }
    setBusy(false);
    toast('Detenido.', 'warning');
  }

  function buildConversationBrief(thread, explicitPlanText = '') {
    const parts = [];
    for (const m of (thread?.messages || []).slice(-12)) {
      if (m.role === 'user') parts.push(`Usuario: ${m.text}`);
      else if (m.kind === 'plan' && m.content) parts.push(`Planificación acordada:\n${m.content.slice(0, 3500)}`);
    }
    if (explicitPlanText && !parts.some((p) => p.includes(explicitPlanText.slice(0, 100)))) {
      parts.push(`Plan aprobado:\n${explicitPlanText}`);
    }
    return `CONSTRUIR SEGÚN EL PLAN APROBADO POR EL USUARIO:\n\n${parts.join('\n\n')}`.slice(0, 12000);
  }

  function approvePlanAndBuild(planText = '') {
    const thread = getActiveThread();
    if (!thread || state.busy) return;
    setPhase('build');
    const objective = buildConversationBrief(thread, planText);
    const pool = buildPoolPayload();
    thread.messages.push({ id: `u-${Date.now()}`, role: 'user', text: 'Plan aprobado. Comiencen a construir y verificar el proyecto ahora.' });
    const msg = {
      id: `a-${Date.now().toString(36)}`,
      role: 'assistant',
      kind: 'build',
      avatar: 'S',
      author: `Equipo Strata · ${scaleLabel(state.buildScale)}`,
      status: 'running',
      phaseLabel: 'Desplegando especialistas…',
      agents: [],
      steps: [],
      files: [],
      content: '',
      needsApiCta: false
    };
    thread.messages.push(msg);
    saveThreads();
    renderActiveThread();
    setBusy(true);
    if (state.buildScale === 'single') runSingleSpecialistTurn(thread, msg, objective, pool);
    else runTeamMissionTurn(thread, msg, objective, pool);
  }

  async function handleSendPrompt(rawText) {
    if (state.busy) { await stopActiveRun(); return; }
    const text = String(rawText || '').trim();
    if (!text) return;

    let thread = getActiveThread();
    if (!thread) thread = createNewThread(text);
    else if (thread.messages.length === 0) thread.title = text.slice(0, 44);

    thread.messages.push({ id: `u-${Date.now()}`, role: 'user', text });
    const pool = buildPoolPayload();

    $('#composer-input').value = '';
    autoGrowComposer();

    // PHASE 1: CONVERSATIONAL PLANNING (DEFAULT)
    if (state.phase === 'plan') {
      const msg = {
        id: `p-${Date.now().toString(36)}`,
        role: 'assistant',
        kind: 'plan',
        avatar: 'A',
        author: 'Arquitecto Principal · Planificación',
        status: 'running',
        phaseLabel: 'Pensando…',
        steps: [],
        content: '',
        planReady: false,
        needsApiCta: false
      };
      thread.messages.push(msg);
      saveThreads();
      renderActiveThread();
      setBusy(true);
      await runPlanningTurn(thread, msg, text, pool);
      return;
    }

    // PHASE 2: BUILD / EXECUTION WITH 150 AGENTS
    const isSingle = state.buildScale === 'single';
    if (isSingle && !pool.providers.length) {
      thread.messages.push({
        id: `a-${Date.now()}`,
        role: 'assistant',
        kind: 'build',
        avatar: state.singleAgentId.replace('AG-', ''),
        author: `${state.singleAgentId} · ${state.agents.find((a) => a.id === state.singleAgentId)?.name || 'Especialista'}`,
        status: 'completed',
        content: 'Para trabajar 1-a-1 con un especialista con IA, conecta una API LLM gratuita en **Herramientas → APIs LLM Gratis**.',
        needsApiCta: true
      });
      saveThreads();
      renderActiveThread();
      return;
    }

    const msg = {
      id: `a-${Date.now().toString(36)}`,
      role: 'assistant',
      kind: 'build',
      avatar: isSingle ? state.singleAgentId.replace('AG-', '') : 'S',
      author: isSingle
        ? `${state.singleAgentId} · ${state.agents.find((a) => a.id === state.singleAgentId)?.name || 'Strata Agent'}`
        : `Equipo Strata · ${scaleLabel(state.buildScale)}`,
      status: 'running',
      phaseLabel: 'Iniciando…',
      agents: [],
      steps: [],
      files: [],
      content: '',
      needsApiCta: false
    };
    thread.messages.push(msg);
    saveThreads();
    renderActiveThread();
    setBusy(true);

    if (isSingle) await runSingleSpecialistTurn(thread, msg, text, pool);
    else await runTeamMissionTurn(thread, msg, text, pool);
  }

  async function runPlanningTurn(thread, msg, text, pool) {
    const controller = new AbortController();
    state.activeAbort = controller;
    const history = thread.messages
      .slice(0, -2)
      .filter((m) => (m.role === 'user' && m.text) || (m.role === 'assistant' && m.content))
      .map((m) => ({ role: m.role, content: m.role === 'user' ? m.text : m.content }));

    let acc = '';
    try {
      const response = await fetch('/api/plan/chat', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({
          sessionId: thread.id,
          message: text,
          history,
          pool,
          openFile: state.activePath || ''
        }),
        signal: controller.signal
      });
      if (!response.ok) {
        const errBody = await response.json().catch(() => ({}));
        throw new Error(errBody.error || `Error HTTP ${response.status}`);
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buf = '';
      let done = false;
      while (!done) {
        const chunk = await reader.read();
        done = chunk.done;
        buf += decoder.decode(chunk.value || new Uint8Array(), { stream: !done });
        const frames = buf.split(/\r?\n\r?\n/);
        buf = frames.pop() || '';
        for (const frame of frames) {
          const dataLine = frame.split(/\r?\n/).filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trim()).join('\n');
          if (!dataLine) continue;
          let ev;
          try { ev = JSON.parse(dataLine); } catch { continue; }
          if (ev.type === 'tool_start') {
            msg.phaseLabel = `Inspeccionando (${ev.name})…`;
            msg.steps.push({ kind: 'run', text: `${ev.name} ${compactArgs(ev.args)}` });
          } else if (ev.type === 'tool_end') {
            msg.steps.push({ kind: 'ok', text: `${ev.name} completado` });
          } else if (ev.type === 'failover') {
            msg.steps.push({ kind: 'warn', text: `Failover ${ev.from?.name || ''} (${ev.from?.model || ''}) → ${ev.to?.name || ''} (${ev.to?.model || ''})` });
          } else if (ev.type === 'retry_wait') {
            msg.phaseLabel = `Reintentando en ${ev.seconds}s…`;
            msg.steps.push({ kind: 'warn', text: `Límite temporal 429 — esperando ${ev.seconds}s y reintentando (${ev.round}/${ev.maxRounds})…` });
          } else if (ev.type === 'assistant_text') {
            acc += (acc ? '\n\n' : '') + (ev.text || '');
            msg.content = acc;
          } else if (ev.type === 'final') {
            msg.content = ev.text || acc;
            msg.planReady = Boolean(ev.planReady);
            msg.needsApiCta = Boolean(ev.needsApi);
            msg.status = 'completed';
          } else if (ev.type === 'error') {
            msg.status = 'error';
            msg.content = `**Error:** ${ev.text}`;
            if (/429|quota|límite/i.test(String(ev.text || ''))) msg.needsApiCta = true;
          }
          updateMessageInDOM(msg);
        }
      }
      if (msg.status === 'running') msg.status = 'completed';
    } catch (err) {
      msg.status = err.name === 'AbortError' ? 'cancelled' : 'error';
      msg.content = err.name === 'AbortError' ? (acc || 'Planificación detenida.') : `**Error:** ${err.message}`;
    } finally {
      state.activeAbort = null;
      setBusy(false);
      saveThreads();
      updateMessageInDOM(msg);
    }
  }

  async function runTeamMissionTurn(thread, msg, objective, pool) {
    const mode = state.buildScale === 'audit' ? 'audit-and-heal' : 'full-lifecycle';
    const scale = state.buildScale === 'audit' ? 'standard' : state.buildScale;
    const payload = {
      objective,
      mode,
      scale,
      pool,
      concurrency: Number(state.config.concurrency || 4),
      maxStepsPerAgent: Number(state.config.maxSteps || 18),
      autoSnapshot: state.config.autoSnapshot !== false,
      autoDebugRetries: Number(state.config.autoDebugRetries ?? 2)
    };
    if (scale === 'custom' && state.selectedSquad.size) payload.agentIds = [...state.selectedSquad];

    try {
      const res = await api('/api/missions', { method: 'POST', body: payload });
      const missionId = res.mission.id;
      state.activeMissionId = missionId;
      msg.author = `Equipo Strata · ${res.mission.agentCount} especialistas`;
      msg.needsApiCta = pool.providers.length === 0;
      updateMessageInDOM(msg);

      const es = new EventSource(`/api/missions/${encodeURIComponent(missionId)}/stream`);
      state.activeStream = es;
      const finish = (finalStatus) => {
        msg.status = finalStatus === 'failed' ? 'error' : finalStatus;
        if (state.activeStream) { state.activeStream.close(); state.activeStream = null; }
        state.activeMissionId = null;
        setBusy(false);
        saveThreads();
        updateMessageInDOM(msg);
        refreshTree().catch(() => {});
        refreshDiagnosticsCount().catch(() => {});
      };

      es.onmessage = (event) => {
        let ev;
        try { ev = JSON.parse(event.data); } catch { return; }
        if (ev.type === 'snapshot' && ev.mission) {
          msg.agents = (ev.mission.agents || []).map((a) => ({ id: a.id, name: a.name, status: a.status }));
          msg.files = [...(ev.mission.filesChanged || [])];
          if (ev.mission.report) msg.content = ev.mission.report;
          if (ev.mission.status !== 'running') finish(ev.mission.status);
          updateMessageInDOM(msg);
          return;
        }
        if (ev.type === 'phase') {
          msg.phaseLabel = ev.label || ev.phase;
          msg.steps.push({ kind: 'run', text: ev.label || ev.phase });
        } else if (ev.type === 'agent_status') {
          const slot = msg.agents.find((a) => a.id === ev.agentId);
          if (slot) slot.status = ev.status;
          else msg.agents.push({ id: ev.agentId, name: ev.name || ev.agentId, status: ev.status });
        } else if (ev.type === 'tool_start') {
          msg.steps.push({ kind: 'run', text: `${ev.agentId || ''} → ${ev.name} ${compactArgs(ev.args)}` });
        } else if (ev.type === 'tool_end') {
          msg.steps.push({ kind: 'ok', text: `${ev.agentId || ''} ${ev.name}: ${String(ev.preview || 'ok').slice(0, 120)}` });
        } else if (ev.type === 'failover') {
          msg.steps.push({ kind: 'warn', text: `Failover ${ev.from?.name || ''} (${ev.from?.model || ''}) → ${ev.to?.name || ''} (${ev.to?.model || ''})` });
        } else if (ev.type === 'retry_wait') {
          msg.steps.push({ kind: 'warn', text: `${ev.agentId || ''} límite 429 — reintentando en ${ev.seconds}s (${ev.round}/${ev.maxRounds})…` });
        } else if (ev.type === 'file_changed') {
          if (!msg.files.includes(ev.path)) msg.files.push(ev.path);
          refreshTree().catch(() => {});
        } else if (ev.type === 'diagnostics') {
          const s = ev.syntax || {};
          const sec = ev.security || {};
          msg.steps.push({ kind: s.ok ? 'ok' : 'err', text: `Verificación: sintaxis ${s.ok ? 'OK' : `${s.errorCount} err`} · seguridad ${sec.score ?? 100}/100` });
        } else if (ev.type === 'report') {
          msg.content = ev.report;
        } else if (ev.type === 'error') {
          msg.steps.push({ kind: 'err', text: ev.text || 'Error' });
        } else if (ev.type === 'mission_status' && ['completed', 'failed', 'cancelled'].includes(ev.status)) {
          finish(ev.status);
        }
        updateMessageInDOM(msg);
      };
      es.onerror = () => { if (es.readyState === EventSource.CLOSED && state.busy) finish('completed'); };
    } catch (err) {
      msg.status = 'error';
      msg.content = `**Error:** ${err.message}`;
      setBusy(false);
      saveThreads();
      updateMessageInDOM(msg);
    }
  }

  async function runSingleSpecialistTurn(thread, msg, text, pool) {
    const controller = new AbortController();
    state.activeAbort = controller;
    let acc = '';
    try {
      const response = await fetch(`/api/agents/${encodeURIComponent(state.singleAgentId)}/chat`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({
          sessionId: thread.id,
          message: text,
          pool,
          openFile: state.activePath || '',
          preferredProviderId: state.config.agentMap[state.singleAgentId] || ''
        }),
        signal: controller.signal
      });
      if (!response.ok) {
        const errBody = await response.json().catch(() => ({}));
        throw new Error(errBody.error || `Error HTTP ${response.status}`);
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buf = '';
      let done = false;
      while (!done) {
        const chunk = await reader.read();
        done = chunk.done;
        buf += decoder.decode(chunk.value || new Uint8Array(), { stream: !done });
        const frames = buf.split(/\r?\n\r?\n/);
        buf = frames.pop() || '';
        for (const frame of frames) {
          const dataLine = frame.split(/\r?\n/).filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trim()).join('\n');
          if (!dataLine) continue;
          let ev;
          try { ev = JSON.parse(dataLine); } catch { continue; }
          if (ev.type === 'tool_start') {
            msg.phaseLabel = `Ejecutando ${ev.name}…`;
            msg.steps.push({ kind: 'run', text: `${ev.name} ${compactArgs(ev.args)}` });
          } else if (ev.type === 'tool_end') {
            msg.steps.push({ kind: 'ok', text: `${ev.name}: ${String(ev.preview || 'ok').slice(0, 120)}` });
          } else if (ev.type === 'failover') {
            msg.steps.push({ kind: 'warn', text: `Failover ${ev.from?.name || ''} (${ev.from?.model || ''}) → ${ev.to?.name || ''} (${ev.to?.model || ''})` });
          } else if (ev.type === 'retry_wait') {
            msg.phaseLabel = `Reintentando en ${ev.seconds}s…`;
            msg.steps.push({ kind: 'warn', text: `Límite temporal 429 — esperando ${ev.seconds}s (${ev.round}/${ev.maxRounds})…` });
          } else if (ev.type === 'ui' && ev.ui?.kind === 'file' && ev.ui.path) {
            if (!msg.files.includes(ev.ui.path)) msg.files.push(ev.ui.path);
            refreshTree().catch(() => {});
          } else if (ev.type === 'assistant_text') {
            acc += (acc ? '\n\n' : '') + (ev.text || '');
            msg.content = acc;
          } else if (ev.type === 'final') {
            msg.content = ev.text || acc || 'Completado.';
            msg.status = 'completed';
          } else if (ev.type === 'error') {
            msg.status = 'error';
            msg.content = `**Error:** ${ev.text}`;
            if (/429|quota|límite/i.test(String(ev.text || ''))) msg.needsApiCta = true;
          }
          updateMessageInDOM(msg);
        }
      }
      if (msg.status === 'running') msg.status = 'completed';
    } catch (err) {
      msg.status = err.name === 'AbortError' ? 'cancelled' : 'error';
      msg.content = err.name === 'AbortError' ? (acc || 'Detenido.') : `**Error:** ${err.message}`;
    } finally {
      state.activeAbort = null;
      setBusy(false);
      saveThreads();
      updateMessageInDOM(msg);
      refreshTree().catch(() => {});
    }
  }

  function compactArgs(args) {
    if (!args || typeof args !== 'object') return '';
    const v = args.path || args.command || args.query || args.url || '';
    return v ? String(v).slice(0, 70) : '';
  }

  function autoGrowComposer() {
    const el = $('#composer-input');
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }

  /* ===================== 150 AGENTS MODAL ===================== */

  async function loadAgentsAndPresets() {
    const [agentsRes, presetsRes] = await Promise.all([
      api('/api/agents'),
      api('/api/providers/presets')
    ]);
    state.agents = agentsRes.agents || [];
    state.divisions = agentsRes.divisions || [];
    state.presets = presetsRes.presets || [];
    $('#sidebar-agents-count').textContent = String(state.agents.length);
    renderDivisionPills();
    renderProviderPresets();
    renderProvidersList();
    renderDivisionMatrix();
    setBuildScale(state.buildScale);
  }

  function renderDivisionPills() {
    const el = $('#division-pills');
    el.replaceChildren();
    const all = document.createElement('button');
    all.type = 'button';
    all.className = `div-pill ${!state.agentFilter.division ? 'is-active' : ''}`;
    all.textContent = `Todas (${state.agents.length})`;
    all.addEventListener('click', () => { state.agentFilter.division = ''; renderDivisionPills(); renderAgentsGrid(); });
    el.append(all);
    for (const d of state.divisions) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = `div-pill ${state.agentFilter.division === d.id ? 'is-active' : ''}`;
      b.textContent = `${d.shortName} (${d.count})`;
      b.addEventListener('click', () => { state.agentFilter.division = d.id; renderDivisionPills(); renderAgentsGrid(); });
      el.append(b);
    }
  }

  function renderAgentsGrid() {
    const grid = $('#agents-grid');
    const q = state.agentFilter.q.trim().toLowerCase();
    const div = state.agentFilter.division;
    const list = state.agents.filter((a) => {
      if (div && a.divisionId !== div) return false;
      if (q) {
        const hay = `${a.id} ${a.name} ${a.divisionName} ${a.specialty} ${(a.focusTags || []).join(' ')}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
    $('#agents-filtered-count').textContent = `${list.length} de ${state.agents.length} especialistas`;
    grid.replaceChildren();
    const frag = document.createDocumentFragment();
    for (const a of list) {
      const sel = state.selectedSquad.has(a.id);
      const card = document.createElement('article');
      card.className = `agent-tile ${sel ? 'is-selected' : ''}`;
      card.innerHTML = `
        <div class="agent-tile-head"><span class="agent-id">${esc(a.id)}</span><span class="agent-div">${esc(a.divisionShort)}</span></div>
        <h3>${esc(a.name)}</h3>
        <p>${esc(a.specialty)}</p>
        <div class="agent-tile-actions">
          <button type="button" class="btn btn-primary btn-xs talk-btn">Usar 1-a-1</button>
          <button type="button" class="btn btn-subtle btn-xs squad-btn">${sel ? '✓ En Escuadrón' : '+ Escuadrón'}</button>
        </div>
      `;
      $('.talk-btn', card).addEventListener('click', () => {
        state.singleAgentId = a.id;
        setBuildScale('single');
        setPhase('build');
        closeAllOverlays();
        $('#composer-input').focus();
        toast(`Especialista activo: ${a.id} · ${a.name}`);
      });
      $('.squad-btn', card).addEventListener('click', () => {
        if (state.selectedSquad.has(a.id)) state.selectedSquad.delete(a.id);
        else state.selectedSquad.add(a.id);
        updateSquadButtons();
        renderAgentsGrid();
      });
      frag.append(card);
    }
    grid.append(frag);
    updateSquadButtons();
  }

  function updateSquadButtons() {
    const n = state.selectedSquad.size;
    $('#squad-count-label').textContent = String(n);
    $('#clear-squad-btn').classList.toggle('hidden', n === 0);
    $('#apply-squad-btn').classList.toggle('hidden', n === 0);
    $('#mode-custom-squad-opt').classList.toggle('hidden', n === 0);
    $('#mode-custom-label').textContent = `Escuadrón Manual (${n} especialista${n === 1 ? '' : 's'})`;
  }

  /* ===================== MULTI-LLM POOL UI ===================== */

  function findPresetForProvider(p) {
    return state.presets.find((pr) => pr.id === p.presetId)
      || state.presets.find((pr) => p.baseUrl && pr.baseUrl && String(p.baseUrl).toLowerCase().includes(String(pr.baseUrl).toLowerCase().replace(/^https?:\/\//, '').split('/')[0]));
  }

  function updateOpenRouterWarning() {
    const box = $('#openrouter-dup-warning');
    if (!box) return;
    const enabled = state.config.providers.filter((p) => p.enabled !== false);
    const orCount = enabled.filter((p) => /openrouter\.ai/i.test(p.baseUrl || '')).length;
    const others = enabled.length - orCount;
    if (orCount >= 2 && others === 0) {
      box.classList.remove('hidden');
      box.innerHTML = '<strong>Aviso Anti-429:</strong> Tienes varias claves de OpenRouter sin otro proveedor. OpenRouter aplica su tope de <strong>50 req/día en :free por cuenta</strong> (no por clave). Agrega arriba <strong>Groq, Google Gemini, NVIDIA NIM o Mistral</strong> para tener cuotas independientes y failover automático.';
    } else if (orCount >= 1 && others === 0) {
      box.classList.remove('hidden');
      box.innerHTML = '<strong>Consejo Anti-429:</strong> Solo tienes OpenRouter conectado (tope 50 req/día en <code>:free</code>). Usa modelo <code>openrouter/free</code> y agrega arriba <strong>Groq, Gemini, NVIDIA NIM o Mistral</strong> gratis para no quedar bloqueado nunca.';
    } else {
      box.classList.add('hidden');
    }
  }

  function renderProviderPresets() {
    const grid = $('#provider-presets-grid');
    $('#presets-count-badge').textContent = `${state.presets.length} opciones`;
    grid.replaceChildren();
    for (const preset of state.presets) {
      const card = document.createElement('div');
      card.className = `preset-card ${preset.recommended ? 'is-rec' : ''}`;
      const label = preset.id === 'openrouter-free' ? 'OpenRouter: buscar modelos GRATIS' : preset.name;
      card.innerHTML = `
        <div class="preset-card-top">
          <strong>${esc(label)}</strong>
          <span>${esc(preset.tier || 'OpenAI-compatible')}</span>
        </div>
        <div class="preset-card-actions">
          <button type="button" class="btn btn-subtle btn-xs add-preset-btn">+ Agregar</button>
          ${preset.docsUrl ? `<a class="preset-link" href="${esc(preset.docsUrl)}" target="_blank" rel="noopener">Clave gratis ↗</a>` : ''}
        </div>
      `;
      $('.add-preset-btn', card).addEventListener('click', () => addProvider(preset));
      grid.append(card);
    }
  }

  function addProvider(preset, silent = false) {
    const id = `prov-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 5)}`;
    const item = {
      id,
      presetId: preset?.id || '',
      name: preset ? preset.name : `API #${state.config.providers.length + 1}`,
      baseUrl: preset ? preset.baseUrl : 'https://api.groq.com/openai/v1',
      model: preset ? (preset.defaultModel || '') : '',
      suggestedModels: Array.isArray(preset?.suggestedModels) ? [...preset.suggestedModels] : [],
      apiKey: '',
      enabled: true,
      priority: state.config.providers.length + 1,
      docsUrl: preset?.docsUrl || ''
    };
    state.config.providers.push(item);
    if (!silent) {
      saveConfig();
      renderProvidersList();
      $('#bar-connected').open = true;
      toast(`Añadido: ${item.name}. Pega tu clave API y pulsa Probar.`);
    }
    return item;
  }

  function addRecommendedFreePack() {
    const wanted = ['groq-free', 'gemini-free', 'nvidia-nim-free', 'mistral-free'];
    let added = 0;
    for (const pid of wanted) {
      const preset = state.presets.find((p) => p.id === pid);
      if (!preset) continue;
      const host = preset.baseUrl.replace(/^https?:\/\//, '').split('/')[0].toLowerCase();
      const exists = state.config.providers.some((p) => String(p.baseUrl || '').toLowerCase().includes(host));
      if (!exists) { addProvider(preset, true); added++; }
    }
    saveConfig();
    renderProvidersList();
    $('#bar-connected').open = true;
    setTimeout(() => $('#bar-connected').scrollIntoView({ behavior: 'smooth', block: 'start' }), 40);
    toast(added > 0
      ? `Pack añadido (${added} proveedor${added === 1 ? '' : 'es'} gratis). Abre "Obtener gratis ↗" en cada tarjeta, pega tus claves y pulsa Probar.`
      : 'Ya tienes los 4 proveedores recomendados en tu Pool.', 'success', 4500);
  }

  function renderProvidersList() {
    updateOpenRouterWarning();
    const list = $('#providers-list');
    list.replaceChildren();
    if (!state.config.providers.length) {
      list.innerHTML = '<div class="empty-clean">Sin APIs conectadas aún. Pulsa arriba <strong>+ Agregar Pack Top 4 Gratis</strong> o elige cualquier proveedor gratuito.</div>';
      return;
    }
    for (const p of state.config.providers) {
      const preset = findPresetForProvider(p);
      const suggested = (Array.isArray(p.suggestedModels) && p.suggestedModels.length)
        ? p.suggestedModels
        : (preset?.suggestedModels || []);
      const docsUrl = p.docsUrl || preset?.docsUrl || '';
      const card = document.createElement('div');
      card.className = 'provider-card';
      card.dataset.providerId = p.id;
      const dlId = `dl-${p.id}`;
      card.innerHTML = `
        <div class="provider-card-head">
          <label class="inline-row"><input type="checkbox" class="p-en" ${p.enabled !== false ? 'checked' : ''}><strong>${esc(p.name)}</strong></label>
          <div class="inline-row">
            ${docsUrl ? `<a class="preset-link" href="${esc(docsUrl)}" target="_blank" rel="noopener">Clave gratis ↗</a>` : ''}
            <button type="button" class="btn btn-subtle btn-xs p-del">Quitar</button>
          </div>
        </div>
        <div class="field-col">
          <label>URL Base API</label>
          <input type="url" class="field sm p-url" value="${esc(p.baseUrl || '')}">
        </div>
        <div class="field-col">
          <label>API Key</label>
          <div class="inline-row">
            <input type="password" class="field sm p-key" value="${esc(p.apiKey || '')}" placeholder="Pega tu clave API aquí" autocomplete="new-password">
            <button type="button" class="btn btn-subtle btn-xs p-vis">Ver</button>
          </div>
        </div>
        <div class="field-col">
          <label>Modelo</label>
          <div class="inline-row">
            <input type="text" class="field sm p-model" list="${dlId}" value="${esc(p.model || '')}" placeholder="ID del modelo">
            <datalist id="${dlId}"></datalist>
            <button type="button" class="btn btn-subtle btn-xs p-disc">Modelos</button>
          </div>
          ${suggested.length ? `<div class="model-chips">${suggested.map((m) => `<button type="button" class="model-chip ${p.model === m ? 'is-active' : ''}" data-pick-model="${esc(m)}">${esc(m)}</button>`).join('')}</div>` : ''}
        </div>
        <div class="inline-row">
          <button type="button" class="btn btn-primary btn-xs p-test">Probar conexión</button>
        </div>
        <div class="provider-msg hidden"></div>
      `;
      const sync = () => {
        p.enabled = $('.p-en', card).checked;
        p.baseUrl = $('.p-url', card).value.trim();
        p.apiKey = $('.p-key', card).value.trim();
        p.model = $('.p-model', card).value.trim();
        $$('.model-chip', card).forEach((ch) => ch.classList.toggle('is-active', ch.dataset.pickModel === p.model));
        saveConfig();
        updateOpenRouterWarning();
      };
      ['.p-en', '.p-url', '.p-key', '.p-model'].forEach((s) => {
        $(s, card).addEventListener('change', sync);
        $(s, card).addEventListener('input', sync);
      });
      $$('.model-chip', card).forEach((ch) => {
        ch.addEventListener('click', () => {
          $('.p-model', card).value = ch.dataset.pickModel;
          sync();
        });
      });
      $('.p-vis', card).addEventListener('click', (e) => {
        const inp = $('.p-key', card);
        inp.type = inp.type === 'password' ? 'text' : 'password';
        e.currentTarget.textContent = inp.type === 'password' ? 'Ver' : 'Ocultar';
      });
      $('.p-del', card).addEventListener('click', () => {
        state.config.providers = state.config.providers.filter((x) => x.id !== p.id);
        saveConfig(); renderProvidersList();
      });
      $('.p-disc', card).addEventListener('click', async (e) => {
        sync();
        const btn = e.currentTarget; const msg = $('.provider-msg', card); btn.disabled = true;
        try {
          const r = await api('/api/providers/models', { method: 'POST', body: { config: p } });
          const dl = $(`#${dlId}`, card); dl.replaceChildren();
          for (const m of r.models || []) {
            const opt = document.createElement('option'); opt.value = m.id; opt.label = m.free ? 'GRATIS' : ''; dl.append(opt);
          }
          const best = r.models?.find((m) => m.free) || r.models?.[0];
          if (best && !p.model) { $('.p-model', card).value = best.id; sync(); }
          msg.className = 'provider-msg ok'; msg.textContent = `${r.models.length} modelos encontrados (${r.freeCount} gratis).`;
        } catch (err) { msg.className = 'provider-msg err'; msg.textContent = err.message; }
        finally { btn.disabled = false; }
      });
      $('.p-test', card).addEventListener('click', async (e) => {
        sync();
        const btn = e.currentTarget; const msg = $('.provider-msg', card); btn.disabled = true;
        try {
          const r = await api('/api/providers/test', { method: 'POST', body: { config: p } });
          msg.className = 'provider-msg ok'; msg.textContent = `✓ Conectado · ${r.model} · ${r.latencyMs || 0} ms`;
        } catch (err) { msg.className = 'provider-msg err'; msg.textContent = err.message; }
        finally { btn.disabled = false; }
      });
      list.append(card);
    }
  }

  function renderDivisionMatrix() {
    const el = $('#division-provider-matrix');
    if (!el) return;
    el.replaceChildren();
    for (const d of state.divisions) {
      const row = document.createElement('div');
      row.className = 'division-matrix-row';
      const cur = state.config.divisionMap[d.id] || '';
      const opts = ['<option value="">Automático</option>']
        .concat(state.config.providers.map((p) => `<option value="${esc(p.id)}" ${p.id === cur ? 'selected' : ''}>${esc(p.name)}</option>`));
      row.innerHTML = `<span>${esc(d.shortName)}</span><select class="field sm">${opts.join('')}</select>`;
      $('select', row).addEventListener('change', (e) => {
        if (e.target.value) state.config.divisionMap[d.id] = e.target.value;
        else delete state.config.divisionMap[d.id];
        saveConfig();
      });
      el.append(row);
    }
  }

  /* ===================== CODE STUDIO, TERMINAL & DIAGNOSTICS ===================== */

  function initMonaco() {
    if (!window.require) return;
    const vsPath = '/vendor/monaco/vs';
    let workerUrl = null;
    window.MonacoEnvironment = {
      getWorkerUrl() {
        if (!workerUrl) {
          const s = `self.MonacoEnvironment={baseUrl:location.origin+'/vendor/monaco/'};importScripts('${vsPath}/base/worker/workerMain.js');`;
          workerUrl = URL.createObjectURL(new Blob([s], { type: 'text/javascript' }));
        }
        return workerUrl;
      }
    };
    window.require.config({ paths: { vs: vsPath } });
    window.require(['vs/editor/editor.main'], () => {
      const monaco = window.monaco;
      if (!monaco) return;
      monaco.editor.defineTheme('strata-light', { base: 'vs', inherit: true, rules: [], colors: { 'editor.background': '#ffffff', 'editor.lineHighlightBackground': '#fafafa' } });
      monaco.editor.defineTheme('strata-dark', { base: 'vs-dark', inherit: true, rules: [], colors: { 'editor.background': '#181b21', 'editor.lineHighlightBackground': '#20242c' } });
      state.editor = monaco.editor.create($('#monaco-editor'), {
        theme: state.theme === 'dark' ? 'strata-dark' : 'strata-light',
        value: '', language: 'plaintext', automaticLayout: true,
        fontSize: state.config.fontSize || 14,
        wordWrap: state.config.wordWrap !== false ? 'on' : 'off',
        minimap: { enabled: false }, scrollBeyondLastLine: false, padding: { top: 8, bottom: 8 }
      });
      state.monacoReady = true;
      if (state.activePath) activateTab(state.activePath);
    });
  }

  async function refreshTree() {
    const res = await api('/api/tree?depth=16');
    state.tree = res.tree || [];
    let count = 0;
    const walk = (nodes) => { for (const n of nodes) { if (n.type === 'file') count++; if (n.children) walk(n.children); } };
    walk(state.tree);
    $('#sidebar-files-count').textContent = String(count);
    $('#topbar-files-badge').textContent = String(count);
    $('#tree-empty').classList.toggle('hidden', state.tree.length > 0);
    $('#file-tree').classList.toggle('hidden', state.tree.length === 0);
    renderTree();
  }

  function renderTree() {
    const root = $('#file-tree');
    root.replaceChildren();
    const build = (node, depth) => {
      const wrap = document.createElement('div');
      const row = document.createElement('button');
      row.type = 'button';
      row.className = `tree-row ${node.path === state.activePath ? 'is-selected' : ''}`;
      row.style.paddingLeft = `${8 + Math.min(depth * 12, 72)}px`;
      const expanded = node.type === 'dir' && state.expanded.has(node.path);
      const dirty = node.type === 'file' && state.tabs.get(node.path)?.dirty;
      row.innerHTML = `<span>${node.type === 'dir' ? (expanded ? '▾' : '▸') : '·'}</span><span class="tree-name">${esc(node.name)}</span>${dirty ? '<span class="tree-dirty"></span>' : ''}`;
      row.addEventListener('click', () => {
        if (node.type === 'dir') {
          state.selectedDir = node.path;
          if (expanded) state.expanded.delete(node.path); else state.expanded.add(node.path);
          renderTree();
        } else {
          if (isMobile()) $('#code-body').classList.add('tree-hidden');
          openFile(node.path).catch((err) => toast(err.message, 'error'));
        }
      });
      wrap.append(row);
      if (expanded && node.children) for (const ch of node.children) wrap.append(build(ch, depth + 1));
      return wrap;
    };
    for (const n of state.tree) root.append(build(n, 0));
  }

  async function openFile(relPath, line) {
    let tab = state.tabs.get(relPath);
    if (!tab) {
      const data = await api(`/api/file?path=${encodeURIComponent(relPath)}`);
      tab = { path: data.path, name: data.path.split('/').pop(), content: data.content, savedContent: data.content, version: data.version, language: data.language || langFor(data.path), model: null, dirty: false };
      state.tabs.set(data.path, tab);
    }
    await activateTab(tab.path);
    if (line && state.editor) {
      const ln = Math.max(1, Number(line) || 1);
      state.editor.revealLineInCenter(ln);
      state.editor.setPosition({ lineNumber: ln, column: 1 });
    }
  }

  async function activateTab(relPath) {
    const tab = state.tabs.get(relPath);
    if (!tab) return;
    if (state.activePath && state.activePath !== relPath && state.config.autosave) {
      const prev = state.tabs.get(state.activePath);
      if (prev?.dirty) await saveTab(prev).catch(() => {});
    }
    state.activePath = relPath;
    $('#file-diagnostic-banner').classList.add('hidden');
    if (state.monacoReady) {
      if (!tab.model) {
        const uri = window.monaco.Uri.parse('file:///strata/' + tab.path.split('/').map(encodeURIComponent).join('/'));
        tab.model = window.monaco.editor.createModel(tab.content, tab.language, uri);
        tab.model.onDidChangeContent(() => {
          if (state.activePath !== tab.path) return;
          tab.content = tab.model.getValue();
          tab.dirty = tab.content !== tab.savedContent;
          renderTabs(); updateFileButtons();
        });
      }
      $('#editor-empty-state').classList.add('hidden');
      $('#monaco-editor').classList.remove('hidden');
      state.editor.setModel(tab.model);
      state.editor.layout();
    }
    $('#active-file-breadcrumb').textContent = tab.path;
    renderTabs(); renderTree(); updateFileButtons();
  }

  function updateFileButtons() {
    const tab = state.tabs.get(state.activePath);
    $('#save-file-btn').disabled = !tab?.dirty;
    $('#run-file-btn').disabled = !tab;
    $('#verify-file-btn').disabled = !tab;
  }

  function renderTabs() {
    const strip = $('#editor-tabs');
    strip.replaceChildren();
    for (const [p, tab] of state.tabs) {
      const el = document.createElement('div');
      el.className = `editor-tab ${p === state.activePath ? 'is-active' : ''}`;
      el.innerHTML = `<span>${esc(tab.name)}${tab.dirty ? ' •' : ''}</span><button type="button" class="tab-close">×</button>`;
      el.addEventListener('click', (e) => {
        if (e.target.closest('.tab-close')) {
          tab.model?.dispose();
          state.tabs.delete(p);
          if (state.activePath === p) {
            state.activePath = [...state.tabs.keys()].at(-1) || null;
            if (state.activePath) activateTab(state.activePath);
            else {
              $('#editor-empty-state').classList.remove('hidden');
              $('#monaco-editor').classList.add('hidden');
              $('#active-file-breadcrumb').textContent = 'Sin archivo abierto';
              updateFileButtons();
            }
          }
          renderTabs();
        } else activateTab(p);
      });
      strip.append(el);
    }
  }

  async function saveTab(tab) {
    if (!tab) return;
    const latest = tab.model ? tab.model.getValue() : tab.content;
    const res = await api('/api/file', { method: 'PUT', body: { path: tab.path, content: latest, expectedVersion: tab.version } });
    tab.content = latest; tab.savedContent = latest; tab.version = res.version; tab.dirty = false;
    renderTabs(); updateFileButtons();
  }

  function ensureTerminal() {
    if (state.terminal || !window.Terminal || !window.FitAddon?.FitAddon) return;
    const term = new window.Terminal({
      cursorBlink: true, fontSize: 13, fontFamily: "'SFMono-Regular', Menlo, Consolas, monospace",
      convertEol: true, theme: { background: '#111827', foreground: '#e5e7eb', cursor: '#60a5fa' }
    });
    const fit = new window.FitAddon.FitAddon();
    term.loadAddon(fit);
    const host = document.createElement('div');
    host.className = 'terminal-host';
    $('#terminal-host-wrap').append(host);
    term.open(host);
    state.terminal = term; state.termFit = fit;

    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${proto}//${location.host}/ws/terminal?cwd=`);
    state.termWs = ws;
    ws.addEventListener('open', () => { $('#terminal-state').textContent = 'Conectada'; fitTerminal(); });
    ws.addEventListener('message', (e) => {
      const write = (t) => { if (t.includes('modo compatible')) state.termCompat = true; term.write(t); };
      if (typeof e.data === 'string') write(e.data);
      else if (e.data instanceof Blob) e.data.text().then(write);
    });
    ws.addEventListener('close', () => { $('#terminal-state').textContent = 'Desconectada'; });
    term.onData((d) => {
      if (state.termCompat) {
        if (d === '\r' || d === '\n') term.write('\r\n');
        else if (d === '\x7f' || d === '\b') term.write('\b \b');
        else if (d >= ' ') term.write(d);
      }
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'input', data: d }));
    });
  }

  function fitTerminal() { try { state.termFit?.fit(); } catch { /* hidden */ } }

  function sendTerminalInput(str) {
    ensureTerminal();
    if (!state.termWs || state.termWs.readyState !== WebSocket.OPEN) return toast('Terminal conectando…', 'warning');
    if (state.termCompat && str !== '\x03') state.terminal.write(str.replace(/\n/g, '\r\n'));
    state.termWs.send(JSON.stringify({ type: 'input', data: str }));
  }

  function renderRunOutput(run) {
    $('#run-output').textContent = `${run.ok ? '✓ OK' : `✕ FALLO (código ${run.code})`} · ${run.runtime || run.command || ''} (${run.durationMs || 0} ms)\n\n${run.stdout || ''}${run.stderr ? '\n--- STDERR ---\n' + run.stderr : ''}`;
  }

  async function runSyntaxCheck() {
    const res = await api('/api/tools/verify-syntax', { method: 'POST', body: {} });
    state.lastSyntax = res.verification; renderDiagnostics(); return res.verification;
  }

  async function runSecurityAudit() {
    const res = await api('/api/tools/security-audit', { method: 'POST', body: {} });
    state.lastSecurity = res.audit; renderDiagnostics(); return res.audit;
  }

  async function refreshDiagnosticsCount() {
    const [syn, sec] = await Promise.all([
      api('/api/tools/verify-syntax', { method: 'POST', body: {} }).catch(() => null),
      api('/api/tools/security-audit', { method: 'POST', body: {} }).catch(() => null)
    ]);
    if (syn) state.lastSyntax = syn.verification;
    if (sec) state.lastSecurity = sec.audit;
    renderDiagnostics();
  }

  function renderDiagnostics() {
    const combined = [];
    for (const d of state.lastSyntax?.diagnostics || []) combined.push({ file: d.file, line: d.line || 1, label: `[Sintaxis · ${d.runtime}] ${d.message}` });
    for (const f of state.lastSecurity?.findings || []) combined.push({ file: f.file, line: f.line || 1, label: `[Seguridad · ${f.severity}] ${f.title}` });
    state.lastDiagnostics = combined;
    $('#ws-diag-badge').textContent = String(combined.length);
    $('#sidebar-diag-count').textContent = String(combined.length);
    $('#diagnostics-summary-line').textContent = `Sintaxis: ${state.lastSyntax ? (state.lastSyntax.ok ? 'OK' : `${state.lastSyntax.errorCount} err`) : '—'} · Seguridad: ${state.lastSecurity ? `${state.lastSecurity.score}/100` : '—'}`;
    const list = $('#diagnostics-list');
    list.replaceChildren();
    if (!combined.length) { list.innerHTML = '<div class="empty-clean">✓ Sin errores de sintaxis ni vulnerabilidades.</div>'; return; }
    for (const item of combined.slice(0, 150)) {
      const row = document.createElement('div');
      row.className = 'diag-row';
      row.innerHTML = `<div class="diag-loc">${esc(item.file)}:${item.line}</div><div class="diag-msg">${esc(item.label)}</div>`;
      row.addEventListener('click', () => { switchWorkspaceTab('code'); openFile(item.file, item.line).catch(() => {}); });
      list.append(row);
    }
  }

  async function refreshProcesses() {
    const res = await api('/api/processes');
    const list = $('#processes-list'); list.replaceChildren();
    if (!res.processes?.length) { list.innerHTML = '<div class="empty-clean">Sin procesos en segundo plano.</div>'; return; }
    for (const p of res.processes) {
      const el = document.createElement('div');
      el.className = 'diag-row';
      el.innerHTML = `<div class="inline-row justify-between"><strong>${esc(p.name)}</strong><span class="muted-xs">${esc(p.status)}</span></div><pre class="clean-pre" style="margin-top:6px;">${esc(p.logTail || '(sin salida)')}</pre>`;
      if (p.status === 'running') {
        const stop = document.createElement('button'); stop.type = 'button'; stop.className = 'btn btn-danger btn-xs'; stop.style.marginTop = '6px'; stop.textContent = 'Detener';
        stop.addEventListener('click', async () => { await api(`/api/processes/${encodeURIComponent(p.id)}/stop`, { method: 'POST' }); refreshProcesses(); });
        el.append(stop);
      }
      list.append(el);
    }
  }

  async function refreshGitStatus() {
    const r = await api('/api/git/status'); const g = r.git;
    $('#git-status-box').textContent = !g.initialized ? g.summary : `Rama: ${g.branch}\n\n${g.files.length ? g.files.map((f) => `${f.status} ${f.path}`).join('\n') : 'Limpio.'}`;
  }

  async function refreshSnapshots() {
    const r = await api('/api/snapshots'); const list = $('#snapshot-list'); list.replaceChildren();
    if (!r.snapshots?.length) { list.innerHTML = '<div class="empty-clean">Sin copias guardadas.</div>'; return; }
    for (const id of r.snapshots) {
      const item = document.createElement('div'); item.className = 'snap-item';
      item.innerHTML = `<strong class="mono">${esc(id)}</strong><button type="button" class="btn btn-subtle btn-xs">Restaurar</button>`;
      $('button', item).addEventListener('click', async () => {
        if (!window.confirm(`¿Restaurar desde ${id}?`)) return;
        await api('/api/restore', { method: 'POST', body: { id } }); toast('Copia restaurada.'); refreshTree();
      });
      list.append(item);
    }
  }

  function promptUser(title, desc, value = '') {
    $('#prompt-title').textContent = title; $('#prompt-description').textContent = desc; $('#prompt-input').value = value;
    const dlg = $('#prompt-dialog'); if (!dlg.open) dlg.showModal();
    requestAnimationFrame(() => $('#prompt-input').focus());
    return new Promise((resolve) => { state.promptResolve = resolve; });
  }

  async function createFileOrDir(isDir) {
    const name = await promptUser(isDir ? 'Nueva carpeta' : 'Nuevo archivo', 'Ruta relativa al workspace', state.selectedDir ? `${state.selectedDir}/` : '');
    if (!name || !name.trim()) return;
    const clean = name.trim().replace(/^\/+|\/+$/g, '');
    if (isDir) await api('/api/mkdir', { method: 'POST', body: { path: clean } });
    else { await api('/api/file', { method: 'POST', body: { path: clean, content: '' } }); await refreshTree(); await openFile(clean); return; }
    await refreshTree();
  }

  async function handleFileUpload(fileList) {
    const payload = [];
    for (const f of [...fileList].slice(0, 60)) {
      if (f.size > 4 * 1024 * 1024) continue;
      payload.push({ path: f.webkitRelativePath || f.name, content: await f.text() });
    }
    if (!payload.length) return;
    const r = await api('/api/project/import', { method: 'POST', body: { files: payload } });
    toast(`Importados ${r.count} archivo(s).`); await refreshTree(); openWorkspace('code');
  }

  /* ===================== EVENTS ===================== */

  function bindEvents() {
    $('#toggle-sidebar-btn').addEventListener('click', toggleSidebar);
    $('#close-sidebar-btn').addEventListener('click', closeSidebar);
    $('#sidebar-backdrop').addEventListener('click', closeSidebar);
    $('#new-chat-btn').addEventListener('click', () => {
      state.activeThreadId = null;
      setPhase('plan');
      if (isMobile()) closeSidebar();
      renderThreadsSidebar();
      renderActiveThread();
      $('#composer-input').focus();
    });

    // Two-phase switch
    $('#phase-plan-btn').addEventListener('click', () => setPhase('plan'));
    $('#phase-build-btn').addEventListener('click', () => setPhase('build'));
    $('#quick-build-from-plan-btn').addEventListener('click', () => approvePlanAndBuild(''));

    // Quick scale from sidebar bar
    $$('[data-quick-scale]').forEach((b) => b.addEventListener('click', () => {
      setBuildScale(b.dataset.quickScale);
      setPhase('build');
      if (isMobile()) closeSidebar();
      toast(`Modo Construir · ${scaleLabel(b.dataset.quickScale)}`);
    }));

    // Workspace & Drawers
    $('#toggle-workspace-btn').addEventListener('click', () => state.workspaceOpen ? closeWorkspace() : openWorkspace(state.wsTab));
    $('#close-workspace-btn').addEventListener('click', closeWorkspace);
    $$('[data-open-workspace]').forEach((b) => b.addEventListener('click', () => openWorkspace(b.dataset.openWorkspace)));
    $$('[data-open-drawer-bar]').forEach((b) => b.addEventListener('click', () => openToolsDrawerBar(b.dataset.openDrawerBar)));
    $('#open-agents-modal-btn').addEventListener('click', openAgentsModal);
    $('#popover-browse-agents-btn').addEventListener('click', openAgentsModal);
    $('#close-agents-modal-btn').addEventListener('click', closeAllOverlays);
    $('#close-tools-drawer-btn').addEventListener('click', closeAllOverlays);
    $('#overlay-backdrop').addEventListener('click', closeAllOverlays);
    $('#theme-toggle-btn').addEventListener('click', () => { applyTheme(state.theme === 'dark' ? 'light' : 'dark'); saveConfig(); });

    // Composer Mode Popover
    $('#composer-squad-btn').addEventListener('click', () => $('#mode-popover').classList.toggle('hidden'));
    $$('.mode-option').forEach((btn) => btn.addEventListener('click', () => {
      setBuildScale(btn.dataset.buildScale);
      setPhase('build');
    }));
    document.addEventListener('click', (e) => {
      if (!e.target.closest('#mode-popover') && !e.target.closest('#composer-squad-btn')) $('#mode-popover').classList.add('hidden');
    });

    // Composer & Starters
    $$('[data-starter-prompt]').forEach((b) => b.addEventListener('click', () => handleSendPrompt(b.dataset.starterPrompt)));
    $('#composer-form').addEventListener('submit', (e) => { e.preventDefault(); handleSendPrompt($('#composer-input').value); });
    $('#composer-input').addEventListener('input', autoGrowComposer);
    $('#composer-input').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && !isMobile()) { e.preventDefault(); handleSendPrompt($('#composer-input').value); }
    });
    $('#composer-upload-btn').addEventListener('click', () => $('#hidden-file-upload').click());
    $('#welcome-upload-btn').addEventListener('click', () => $('#hidden-file-upload').click());
    $('#drawer-upload-files-btn').addEventListener('click', () => $('#hidden-file-upload').click());
    $('#hidden-file-upload').addEventListener('change', (e) => { handleFileUpload(e.target.files).catch((err) => toast(err.message, 'error')); e.target.value = ''; });

    // Agents Modal
    $('#agent-search-input').addEventListener('input', (e) => { state.agentFilter.q = e.target.value; renderAgentsGrid(); });
    $('#clear-squad-btn').addEventListener('click', () => { state.selectedSquad.clear(); updateSquadButtons(); renderAgentsGrid(); });
    $('#apply-squad-btn').addEventListener('click', () => {
      setBuildScale('custom'); setPhase('build'); closeAllOverlays();
      toast(`Escuadrón manual activo (${state.selectedSquad.size}).`);
    });

    // Workspace Tabs & Code
    $$('.ws-tab').forEach((b) => b.addEventListener('click', () => switchWorkspaceTab(b.dataset.wsTab)));
    $$('[data-tree-tab]').forEach((b) => b.addEventListener('click', () => {
      const t = b.dataset.treeTab;
      $$('[data-tree-tab]').forEach((x) => x.classList.toggle('is-active', x.dataset.treeTab === t));
      $$('.tree-pane').forEach((p) => p.classList.toggle('is-active', p.id === `tree-pane-${t}`));
      if (t === 'snapshots') refreshSnapshots().catch(() => {});
    }));
    $('#toggle-tree-btn').addEventListener('click', () => $('#code-body').classList.toggle('tree-hidden'));
    $('#new-file-btn').addEventListener('click', () => createFileOrDir(false));
    $('#empty-new-file-btn').addEventListener('click', () => createFileOrDir(false));
    $('#welcome-new-file-btn').addEventListener('click', () => createFileOrDir(false));
    $('#new-folder-btn').addEventListener('click', () => createFileOrDir(true));
    $('#refresh-tree-btn').addEventListener('click', () => refreshTree().then(() => toast('Archivos actualizados.')));
    $('#save-file-btn').addEventListener('click', async () => { try { await saveTab(state.tabs.get(state.activePath)); toast('Guardado.'); } catch (err) { toast(err.message, 'error'); } });
    $('#verify-file-btn').addEventListener('click', async () => {
      const tab = state.tabs.get(state.activePath); if (!tab) return;
      if (tab.dirty) await saveTab(tab).catch(() => {});
      const r = await api('/api/tools/verify-file', { method: 'POST', body: { path: tab.path } });
      const b = $('#file-diagnostic-banner'); b.classList.remove('hidden', 'is-ok', 'is-error');
      b.classList.add(r.verification.ok && r.verification.totalIssues === 0 ? 'is-ok' : 'is-error');
      b.textContent = r.verification.ok && r.verification.totalIssues === 0 ? `✓ ${tab.path}: sin errores.` : `✕ ${r.verification.totalIssues} problema(s) detectado(s).`;
    });
    $('#run-file-btn').addEventListener('click', async () => {
      const tab = state.tabs.get(state.activePath); if (!tab) return;
      if (tab.dirty) await saveTab(tab).catch(() => {});
      const r = await api('/api/run', { method: 'POST', body: { path: tab.path } });
      renderRunOutput(r.run); switchWorkspaceTab('terminal');
      $$('[data-term-sub]').forEach((x) => x.classList.toggle('is-active', x.dataset.termSub === 'output'));
      $$('.term-subpane').forEach((p) => p.classList.toggle('is-active', p.id === 'term-sub-output'));
    });
    $('#code-search-form').addEventListener('submit', async (e) => {
      e.preventDefault(); const q = $('#search-query').value.trim(); if (!q) return;
      const r = await api('/api/search?q=' + encodeURIComponent(q)); const box = $('#search-results'); box.replaceChildren();
      for (const hit of (r.results || []).slice(0, 100)) {
        const b = document.createElement('button'); b.type = 'button'; b.className = 'search-hit';
        b.innerHTML = `<strong>${esc(hit.file)}:${hit.line}</strong><span>${esc(hit.text)}</span>`;
        b.addEventListener('click', () => openFile(hit.file, hit.line)); box.append(b);
      }
    });
    $('#create-snapshot-btn').addEventListener('click', async () => { await api('/api/snapshot', { method: 'POST', body: { label: 'manual' } }); toast('Copia guardada.'); refreshSnapshots(); });

    // Terminal & Diagnostics
    $$('[data-term-sub]').forEach((b) => b.addEventListener('click', () => {
      const s = b.dataset.termSub;
      $$('[data-term-sub]').forEach((x) => x.classList.toggle('is-active', x.dataset.termSub === s));
      $$('.term-subpane').forEach((p) => p.classList.toggle('is-active', p.id === `term-sub-${s}`));
      if (s === 'shell') { ensureTerminal(); setTimeout(() => fitTerminal(), 60); }
      if (s === 'procs') refreshProcesses().catch(() => {});
    }));
    $('#clear-terminal-btn').addEventListener('click', () => state.terminal?.clear());
    $$('[data-term-key="ctrl-c"]').forEach((b) => b.addEventListener('click', () => sendTerminalInput('\x03')));
    $$('[data-term-cmd]').forEach((b) => b.addEventListener('click', () => sendTerminalInput(b.dataset.termCmd + '\n')));
    $('#mobile-term-form').addEventListener('submit', (e) => { e.preventDefault(); const v = $('#mobile-term-input').value; if (!v.trim()) return; sendTerminalInput(v + '\n'); $('#mobile-term-input').value = ''; });
    $('#start-proc-form').addEventListener('submit', async (e) => { e.preventDefault(); await api('/api/processes/start', { method: 'POST', body: { name: $('#proc-name-input').value.trim(), command: $('#proc-cmd-input').value.trim() } }); $('#proc-name-input').value = ''; $('#proc-cmd-input').value = ''; refreshProcesses(); });
    $('#rt-run-syntax-btn').addEventListener('click', () => runSyntaxCheck().then((v) => toast(v.ok ? 'Sintaxis OK.' : `${v.errorCount} error(es).`, v.ok ? 'success' : 'warning')));
    $('#rt-run-security-btn').addEventListener('click', () => runSecurityAudit().then((a) => toast(`Seguridad: ${a.score}/100.`)));
    $('#send-diags-to-debuggers-btn').addEventListener('click', () => {
      closeWorkspace(); setBuildScale('audit'); setPhase('build');
      const summary = state.lastDiagnostics.slice(0, 12).map((d) => `${d.file}:${d.line} ${d.label}`).join('; ');
      handleSendPrompt(`Corrige todos los errores de sintaxis y seguridad y verifica con pruebas: ${summary || 'auditoría completa'}`);
    });

    // Multi-LLM Pool & Tools Drawer Bars
    $('#add-free-pack-btn').addEventListener('click', addRecommendedFreePack);
    $('#add-custom-provider-btn').addEventListener('click', () => addProvider(null));
    $('#test-all-providers-btn').addEventListener('click', async () => {
      const active = state.config.providers.filter((p) => p.enabled !== false);
      if (!active.length) return toast('Agrega primero una API.', 'warning');
      const btn = $('#test-all-providers-btn');
      btn.disabled = true;
      btn.textContent = 'Probando…';
      try {
        const r = await api('/api/providers/test-pool', { method: 'POST', body: { providers: active } });
        for (const item of r.results || []) {
          const card = $(`.provider-card[data-provider-id="${item.id}"]`);
          const msg = card && $('.provider-msg', card);
          if (!msg) continue;
          msg.className = `provider-msg ${item.ok ? 'ok' : 'err'}`;
          msg.textContent = item.ok
            ? `✓ Conectado · ${item.model} · ${item.latencyMs || 0} ms`
            : (item.error || 'Error de conexión');
        }
        toast(`Pool: ${r.healthy} de ${r.total} operativas.`, r.healthy === r.total ? 'success' : 'warning');
      } finally {
        btn.disabled = false;
        btn.textContent = 'Probar todo el Pool';
      }
    });
    $('#save-pool-config-btn').addEventListener('click', () => {
      state.config.strategy = $('#pool-strategy').value;
      state.config.concurrency = Math.min(16, Math.max(1, Number($('#pool-concurrency').value) || 4));
      state.config.maxSteps = Math.min(60, Math.max(2, Number($('#pool-max-steps').value) || 18));
      state.config.temperature = Number($('#pool-temperature').value) || 0.2;
      state.config.tools = $('#pool-tools-enabled').checked;
      saveConfig(); toast('Enrutamiento guardado.');
    });
    $('#toggle-api-key').addEventListener('click', (e) => { const i = $('#setting-api-key'); i.type = i.type === 'password' ? 'text' : 'password'; e.currentTarget.textContent = i.type === 'password' ? 'Ver' : 'Ocultar'; });
    $('#discover-models-button').addEventListener('click', async () => {
      try {
        const r = await api('/api/agent/models', { method: 'POST', body: { config: { baseUrl: $('#setting-base-url').value.trim(), apiKey: $('#setting-api-key').value.trim() } } });
        const dl = $('#setting-model-options'); dl.replaceChildren();
        for (const m of r.models || []) { const o = document.createElement('option'); o.value = m.id; o.label = m.free ? 'GRATIS' : ''; dl.append(o); }
        const best = r.models?.find((m) => m.free) || r.models?.[0]; if (best) $('#setting-model').value = best.id;
        $('#model-discovery-note').textContent = `${r.models.length} modelos (${r.freeCount} gratis).`;
      } catch (err) { $('#model-discovery-note').textContent = err.message; }
    });
    $('#quick-test-single-btn').addEventListener('click', async () => {
      const b = $('#agent-test-result'); b.className = 'status-banner'; b.textContent = 'Probando…';
      try {
        const r = await api('/api/agent/test', { method: 'POST', body: { config: { baseUrl: $('#setting-base-url').value.trim(), apiKey: $('#setting-api-key').value.trim(), model: $('#setting-model').value.trim() } } });
        b.className = 'status-banner ok'; b.textContent = `✓ Conectado · ${r.model}`;
      } catch (err) { b.className = 'status-banner err'; b.textContent = err.message; }
    });
    $('#quick-add-discovered-btn').addEventListener('click', () => {
      const model = $('#setting-model').value.trim(); if (!model) return toast('Indica un modelo.', 'warning');
      state.config.providers.push({ id: `prov-${Date.now().toString(36)}`, name: model.split('/').pop(), baseUrl: $('#setting-base-url').value.trim() || 'https://openrouter.ai/api/v1', apiKey: $('#setting-api-key').value.trim(), model, enabled: true, priority: state.config.providers.length + 1 });
      saveConfig(); renderProvidersList(); toast(`Añadido ${model}.`);
    });

    $('#tool-verify-syntax').addEventListener('click', async () => { await runSyntaxCheck(); closeAllOverlays(); openWorkspace('diagnostics'); });
    $('#tool-security-audit').addEventListener('click', async () => { await runSecurityAudit(); closeAllOverlays(); openWorkspace('diagnostics'); });
    $('#tool-run-tests').addEventListener('click', async () => {
      const r = await api('/api/tools/run-tests', { method: 'POST', body: {} }); renderRunOutput(r.run); closeAllOverlays(); openWorkspace('terminal');
      $$('[data-term-sub]').forEach((x) => x.classList.toggle('is-active', x.dataset.termSub === 'output'));
      $$('.term-subpane').forEach((p) => p.classList.toggle('is-active', p.id === 'term-sub-output'));
    });
    $('#tool-auto-repair').addEventListener('click', async () => { const r = await api('/api/tools/auto-repair', { method: 'POST', body: {} }); $('#tool-output-preview').classList.remove('hidden'); $('#tool-output-preview').textContent = JSON.stringify(r.repair, null, 2); refreshTree(); });
    $('#tool-cartography').addEventListener('click', async () => { const r = await api('/api/tools/cartography'); $('#tool-output-preview').classList.remove('hidden'); $('#tool-output-preview').textContent = JSON.stringify(r.cartography, null, 2); });
    $('#tool-list-runtimes').addEventListener('click', async () => { const r = await api('/api/runtimes'); $('#tool-output-preview').classList.remove('hidden'); $('#tool-output-preview').textContent = r.runtimes.filter((x) => x.available).map((x) => `✓ ${x.label} (${x.bin})`).join('\n'); });
    $('#scaffold-create-btn').addEventListener('click', async () => {
      const r = await api('/api/project/scaffold', { method: 'POST', body: { template: $('#scaffold-template').value, targetDir: $('#scaffold-dir').value.trim() } });
      toast(`Creado: ${r.label} (${r.files.length} archivos).`); await refreshTree(); closeAllOverlays(); openWorkspace('code'); if (r.files[0]) openFile(r.files[0]);
    });
    $('#quick-exec-form').addEventListener('submit', async (e) => {
      e.preventDefault(); const r = await api('/api/exec', { method: 'POST', body: { command: $('#quick-exec-input').value.trim() } });
      renderRunOutput(r.run); closeAllOverlays(); openWorkspace('terminal');
      $$('[data-term-sub]').forEach((x) => x.classList.toggle('is-active', x.dataset.termSub === 'output'));
      $$('.term-subpane').forEach((p) => p.classList.toggle('is-active', p.id === 'term-sub-output'));
    });
    $('#drawer-export-bundle-btn').addEventListener('click', async () => {
      const r = await api('/api/project/export'); const a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([JSON.stringify(r.bundle, null, 2)], { type: 'application/json' })); a.download = `strata-workspace-${Date.now()}.json`; a.click();
    });
    $('#drawer-create-snapshot-btn').addEventListener('click', async () => { const r = await api('/api/snapshot', { method: 'POST', body: { label: 'manual' } }); toast(`Copia: ${r.id}`); });

    $('#git-clone-form').addEventListener('submit', async (e) => {
      e.preventDefault(); const btn = $('#git-clone-btn'); btn.disabled = true; btn.textContent = 'Clonando…';
      try {
        const r = await api('/api/git/clone', { method: 'POST', body: { url: $('#git-clone-url').value.trim(), targetDir: $('#git-clone-dir').value.trim() } });
        if (!r.ok) throw new Error(r.stderr || 'Error clonando.');
        toast(`Clonado en ${r.targetDir}`); await refreshTree(); await refreshGitStatus();
      } catch (err) { toast(err.message, 'error'); } finally { btn.disabled = false; btn.textContent = 'Clonar'; }
    });
    $('#git-refresh-btn').addEventListener('click', () => refreshGitStatus());
    $('#git-diff-btn').addEventListener('click', async () => { $('#git-status-box').textContent = (await api('/api/git/diff')).diff; });
    $('#git-log-btn').addEventListener('click', async () => { const r = await api('/api/git/log'); $('#git-status-box').textContent = r.commits?.length ? r.commits.map((c) => `${c.hash}  ${c.message}`).join('\n') : 'Sin commits.'; });
    $('#git-init-btn').addEventListener('click', async () => { await api('/api/git/init', { method: 'POST', body: {} }); refreshGitStatus(); });
    $('#git-commit-form').addEventListener('submit', async (e) => {
      e.preventDefault(); const r = await api('/api/git/commit', { method: 'POST', body: { message: $('#git-commit-msg').value.trim() } });
      if (!r.ok) return toast(r.stderr || 'Error commit.', 'error'); $('#git-commit-msg').value = ''; toast('Commit creado.'); refreshGitStatus();
    });

    $('#setting-font-size').addEventListener('input', (e) => { $('#font-size-value').textContent = `${e.target.value} px`; });
    $('#save-general-settings-btn').addEventListener('click', () => {
      state.config.autoDebugRetries = Number($('#setting-debug-retries').value) || 2;
      state.config.autoSnapshot = $('#setting-auto-snapshot').checked;
      state.config.fontSize = Number($('#setting-font-size').value) || 14;
      state.config.wordWrap = $('#setting-wordwrap').checked;
      state.config.autosave = $('#setting-autosave').checked;
      applyTheme($('#setting-theme').value); saveConfig();
      state.editor?.updateOptions({ fontSize: state.config.fontSize, wordWrap: state.config.wordWrap ? 'on' : 'off' });
      toast('Ajustes guardados.');
    });
    $('#export-config-btn').addEventListener('click', () => {
      const clean = { ...state.config, providers: state.config.providers.map((p) => ({ ...p, apiKey: '' })) };
      const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([JSON.stringify(clean, null, 2)], { type: 'application/json' })); a.download = 'strata-config.json'; a.click();
    });
    $('#import-config-btn').addEventListener('click', () => $('#import-config-file').click());
    $('#import-config-file').addEventListener('change', async (e) => {
      const f = e.target.files?.[0]; if (!f) return;
      try { state.config = { ...state.config, ...JSON.parse(await f.text()) }; saveConfig(); renderProvidersList(); applyTheme(state.config.theme); toast('Configuración importada.'); }
      catch { toast('JSON inválido.', 'error'); } e.target.value = '';
    });

    $('#prompt-form').addEventListener('submit', (e) => { e.preventDefault(); const v = $('#prompt-input').value; $('#prompt-dialog').close(); const fn = state.promptResolve; state.promptResolve = null; fn?.(v); });
    $$('[data-close-dialog]').forEach((b) => b.addEventListener('click', () => document.getElementById(b.dataset.closeDialog)?.close()));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') closeAllOverlays();
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's' && state.workspaceOpen && state.wsTab === 'code') {
        e.preventDefault(); saveTab(state.tabs.get(state.activePath)).then(() => toast('Guardado.')).catch(() => {});
      }
    });
    window.addEventListener('resize', () => { state.editor?.layout(); fitTerminal(); });
  }

  async function init() {
    loadStorage();
    $('#pool-strategy').value = state.config.strategy || 'round-robin';
    $('#pool-concurrency').value = state.config.concurrency || 4;
    $('#pool-max-steps').value = state.config.maxSteps || 18;
    $('#pool-temperature').value = state.config.temperature ?? 0.2;
    $('#pool-tools-enabled').checked = state.config.tools !== false;
    $('#setting-debug-retries').value = state.config.autoDebugRetries ?? 2;
    $('#setting-auto-snapshot').checked = state.config.autoSnapshot !== false;
    $('#setting-font-size').value = state.config.fontSize || 14;
    $('#font-size-value').textContent = `${state.config.fontSize || 14} px`;
    $('#setting-wordwrap').checked = state.config.wordWrap !== false;
    $('#setting-autosave').checked = state.config.autosave === true;
    if (isMobile()) $('#code-body').classList.add('tree-hidden');

    bindEvents();
    initMonaco();
    setPhase('plan');
    updatePoolStatusUI();
    renderThreadsSidebar();
    renderActiveThread();

    await Promise.all([
      loadAgentsAndPresets().catch(() => {}),
      refreshTree().catch(() => {}),
      refreshDiagnosticsCount().catch(() => {})
    ]);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();
})();
