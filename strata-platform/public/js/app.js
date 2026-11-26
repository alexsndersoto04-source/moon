'use strict';

(() => {
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const CONFIG_KEY = 'strata.engineering.v2';
  const THREADS_KEY = 'strata.threads.v2';

  const state = {
    theme: 'light',
    presets: [],
    divisions: [],
    agents: [],
    agentFilter: { q: '', division: '' },
    selectedSquad: new Set(),

    // Dispatch mode for Arena composer
    dispatchMode: 'standard', // standard | compact | enterprise | full-fleet | audit | single | custom
    singleAgentId: 'AG-141',

    // Conversations (Arena-style persistent threads)
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

    // Workspace Panel (Code Studio + Terminal + Diagnostics)
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
      if (/^<h[1-3]>.*<\/h[1-3]>$/.test(line)) { flushPara(); flushList(); out.push(line); continue; }
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

  /* ===================== CONFIG & PERSISTENCE ===================== */

  function loadStorage() {
    try {
      const saved = JSON.parse(localStorage.getItem(CONFIG_KEY) || localStorage.getItem('strata.engineering.v1') || '{}');
      state.config = { ...state.config, ...saved };
      if (!Array.isArray(state.config.providers)) state.config.providers = [];
      if (!state.config.divisionMap || typeof state.config.divisionMap !== 'object') state.config.divisionMap = {};
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
    try { localStorage.setItem(THREADS_KEY, JSON.stringify(state.threads.slice(0, 30))); } catch { /* quota */ }
    renderThreadsSidebar();
  }

  function applyTheme(theme) {
    state.theme = theme === 'dark' ? 'dark' : 'light';
    state.config.theme = state.theme;
    document.documentElement.setAttribute('data-theme', state.theme);
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = state.theme === 'dark' ? '#14161a' : '#ffffff';
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
    $('#sidebar-pool-title').textContent = count > 0 ? `${count} API${count > 1 ? 's' : ''} LLM Activa${count > 1 ? 's' : ''}` : 'Motor Local Activo';
    $('#sidebar-pool-sub').textContent = count > 0
      ? active.slice(0, 2).map((p) => p.name).join(' · ')
      : 'Conectar APIs LLM gratis';
    $('#composer-pool-label').textContent = count > 0 ? `${count} API${count > 1 ? 's' : ''} LLM` : 'Motor Local';
  }

  /* ===================== SHELL NAVIGATION (SIDEBAR, WORKSPACE, MODALS) ===================== */

  const isMobile = () => window.innerWidth <= 960;

  function openSidebar() {
    if (isMobile()) {
      $('#arena-app').classList.add('sidebar-open-mobile');
      $('#sidebar-backdrop').classList.remove('hidden');
    } else {
      $('#arena-app').classList.remove('sidebar-collapsed');
    }
  }

  function closeSidebar() {
    if (isMobile()) {
      $('#arena-app').classList.remove('sidebar-open-mobile');
      $('#sidebar-backdrop').classList.add('hidden');
    } else {
      $('#arena-app').classList.add('sidebar-collapsed');
    }
  }

  function toggleSidebar() {
    if (isMobile()) {
      if ($('#arena-app').classList.contains('sidebar-open-mobile')) closeSidebar();
      else openSidebar();
    } else {
      $('#arena-app').classList.toggle('sidebar-collapsed');
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

  function openToolsDrawer(tab = 'providers') {
    if (isMobile()) closeSidebar();
    closeAllOverlays();
    $('#overlay-backdrop').classList.remove('hidden');
    $('#tools-drawer').classList.remove('hidden');
    $$('.drawer-tab').forEach((b) => b.classList.toggle('is-active', b.dataset.drawerTab === tab));
    $$('.drawer-pane').forEach((p) => p.classList.toggle('is-active', p.id === `drawer-pane-${tab}`));
    if (tab === 'git') refreshGitStatus().catch(() => {});
  }

  function openAgentsModal() {
    if (isMobile()) closeSidebar();
    closeAllOverlays();
    $('#overlay-backdrop').classList.remove('hidden');
    $('#agents-modal').classList.remove('hidden');
    renderAgentsGrid();
    setTimeout(() => $('#agent-search-input').focus(), 60);
  }

  /* ===================== MODE SELECTOR ===================== */

  function setDispatchMode(mode) {
    state.dispatchMode = mode;
    $$('.mode-option').forEach((el) => el.classList.toggle('is-selected', el.dataset.dispatchMode === mode));
    const singleAgent = state.agents.find((a) => a.id === state.singleAgentId);
    const labels = {
      standard: { header: 'Strata · 150 Agentes', chip: '15 agentes' },
      compact: { header: 'Strata · Equipo Rápido (6)', chip: '6 agentes' },
      enterprise: { header: 'Strata · Gran Escala (45)', chip: '45 agentes' },
      'full-fleet': { header: 'Strata · Flota Total (150)', chip: '150 agentes' },
      audit: { header: 'Strata · Auditoría y Reparación', chip: 'Auditoría' },
      single: { header: `${singleAgent?.id || 'AG-141'} · ${singleAgent?.name || 'Strata Agent'}`, chip: singleAgent?.id || '1-a-1' },
      custom: { header: `Escuadrón Manual (${state.selectedSquad.size})`, chip: `Escuadrón (${state.selectedSquad.size})` }
    };
    const lbl = labels[mode] || labels.standard;
    $('#header-mode-label').textContent = lbl.header;
    $('#composer-mode-label').textContent = lbl.chip;
    $('#mode-single-label').textContent = `Especialista 1-a-1 · ${singleAgent?.name || 'Strata Agent'} (${singleAgent?.id || 'AG-141'})`;
    $('#mode-popover').classList.add('hidden');
  }

  /* ===================== ARENA CONVERSATIONS & LIVE STREAMING ===================== */

  function getActiveThread() {
    return state.threads.find((t) => t.id === state.activeThreadId) || null;
  }

  function createNewThread(firstPrompt = '') {
    const thread = {
      id: `th-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 5)}`,
      title: firstPrompt ? firstPrompt.slice(0, 48) : 'Nueva conversación',
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
    list.replaceChildren();
    if (!state.threads.length) {
      list.innerHTML = '<div class="muted-xs" style="padding:8px;">Sin conversaciones previas.</div>';
      return;
    }
    for (const th of state.threads) {
      const btn = document.createElement('div');
      btn.className = `thread-item ${th.id === state.activeThreadId ? 'is-active' : ''}`;
      btn.innerHTML = `<span class="thread-title">${esc(th.title)}</span><button type="button" class="thread-del" title="Eliminar">×</button>`;
      btn.addEventListener('click', (e) => {
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
        }
      });
      list.append(btn);
    }
  }

  function renderActiveThread() {
    const thread = getActiveThread();
    const hasMessages = Boolean(thread && thread.messages.length);
    $('#welcome-hero').classList.toggle('hidden', hasMessages);
    $('#messages-stream').classList.toggle('hidden', !hasMessages);
    if (!hasMessages) return;

    const stream = $('#messages-stream');
    stream.replaceChildren();
    for (const m of thread.messages) {
      stream.append(renderMessageElement(m));
    }
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

    const statusClass = m.status === 'running' ? 'is-running' : m.status === 'error' ? 'is-error' : 'is-done';
    const statusLabel = m.status === 'running' ? (m.phaseLabel || 'Trabajando…') : m.status === 'error' ? 'Error' : m.status === 'cancelled' ? 'Detenido' : 'Completado';

    const head = document.createElement('div');
    head.className = 'msg-assistant-head';
    head.innerHTML = `
      <div class="msg-avatar ${m.isSingle ? 'is-specialist' : ''}">${esc(m.avatar || 'S')}</div>
      <span class="msg-author-title">${esc(m.author || 'Equipo Strata')}</span>
      <span class="msg-status-pill ${statusClass}">${esc(statusLabel)}</span>
    `;
    wrap.append(head);

    // Collapsible Arena-style Activity & Tool Steps Block
    if ((m.agents && m.agents.length) || (m.steps && m.steps.length)) {
      const det = document.createElement('details');
      det.className = 'activity-card';
      if (m.status === 'running') det.open = true;
      const doneAgents = (m.agents || []).filter((a) => a.status === 'completed').length;
      const agentSummary = m.agents?.length ? `${m.agents.length} especialista${m.agents.length > 1 ? 's' : ''} (${doneAgents} listo${doneAgents === 1 ? '' : 's'})` : 'Herramientas';
      const stepSummary = m.steps?.length ? ` · ${m.steps.length} acción${m.steps.length === 1 ? '' : 'es'}` : '';
      det.innerHTML = `<summary><span>${esc(agentSummary + stepSummary)}</span></summary>`;

      const body = document.createElement('div');
      body.className = 'activity-body';

      if (m.agents && m.agents.length) {
        const chips = document.createElement('div');
        chips.className = 'activity-agents-row';
        for (const a of m.agents.slice(0, 18)) {
          const cls = a.status === 'working' ? 'is-working' : a.status === 'completed' ? 'is-done' : '';
          const icon = a.status === 'completed' ? '✓' : a.status === 'working' ? '●' : '○';
          const chip = document.createElement('span');
          chip.className = `agent-chip ${cls}`;
          chip.textContent = `${icon} ${a.id} ${a.name}`;
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

      if (m.steps && m.steps.length) {
        const stepsEl = document.createElement('div');
        stepsEl.className = 'activity-steps';
        for (const st of m.steps.slice(-24)) {
          const row = document.createElement('div');
          row.className = 'step-row';
          row.innerHTML = `<span class="step-icon ${esc(st.kind || 'ok')}">${st.kind === 'err' ? '✕' : st.kind === 'warn' ? '↻' : st.kind === 'run' ? '▶' : '✓'}</span><span class="step-text">${esc(st.text)}</span>`;
          stepsEl.append(row);
        }
        body.append(stepsEl);
      }
      det.append(body);
      wrap.append(det);
    }

    // Inline Clickable File / Artifact Cards
    if (m.files && m.files.length) {
      const filesRow = document.createElement('div');
      filesRow.className = 'artifacts-row';
      for (const fp of m.files) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'artifact-card';
        btn.innerHTML = `<span>📄</span><small>${esc(fp)}</small>`;
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

    // Helpful 1-tap actions when finished in local mode or with files
    if (m.status !== 'running' && (m.needsApiCta || (m.files && m.files.length))) {
      const ctaRow = document.createElement('div');
      ctaRow.className = 'msg-cta-row';
      if (m.needsApiCta) {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'btn btn-primary btn-xs';
        b.textContent = 'Conectar API LLM gratis →';
        b.addEventListener('click', () => openToolsDrawer('providers'));
        ctaRow.append(b);
      }
      if (m.files && m.files.length) {
        const b2 = document.createElement('button');
        b2.type = 'button';
        b2.className = 'btn btn-subtle btn-xs';
        b2.textContent = 'Abrir espacio de código';
        b2.addEventListener('click', () => { openWorkspace('code'); openFile(m.files[0]).catch(() => {}); });
        ctaRow.append(b2);
      }
      wrap.append(ctaRow);
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
  }

  async function stopActiveRun() {
    if (state.activeMissionId) {
      await api(`/api/missions/${encodeURIComponent(state.activeMissionId)}/cancel`, { method: 'POST' }).catch(() => {});
    }
    if (state.activeStream) { state.activeStream.close(); state.activeStream = null; }
    if (state.activeAbort) { state.activeAbort.abort(); state.activeAbort = null; }
    setBusy(false);
    toast('Ejecución detenida.', 'warning');
  }

  async function handleSendPrompt(rawText) {
    if (state.busy) {
      await stopActiveRun();
      return;
    }
    const text = String(rawText || '').trim();
    if (!text) return;

    let thread = getActiveThread();
    if (!thread) thread = createNewThread(text);
    else if (thread.messages.length === 0) thread.title = text.slice(0, 48);

    thread.messages.push({ id: `u-${Date.now()}`, role: 'user', text });
    const pool = buildPoolPayload();
    const isSingle = state.dispatchMode === 'single';

    if (isSingle && !pool.providers.length) {
      thread.messages.push({
        id: `a-${Date.now()}`,
        role: 'assistant',
        isSingle: true,
        avatar: state.singleAgentId.replace('AG-', ''),
        author: `${state.singleAgentId} · ${state.agents.find((a) => a.id === state.singleAgentId)?.name || 'Especialista'}`,
        status: 'completed',
        content: 'Para conversar 1-a-1 con un especialista individual con IA, conecta al menos una API LLM gratuita (como OpenRouter `:free`, Groq o Google Gemini).\n\nTambién puedes cambiar el selector del compositor a **15 agentes** o **Auditoría** para ejecutar diagnósticos y herramientas locales de inmediato.',
        needsApiCta: true
      });
      saveThreads();
      renderActiveThread();
      return;
    }

    const msgId = `a-${Date.now().toString(36)}`;
    const assistantMsg = {
      id: msgId,
      role: 'assistant',
      isSingle,
      avatar: isSingle ? state.singleAgentId.replace('AG-', '') : 'S',
      author: isSingle
        ? `${state.singleAgentId} · ${state.agents.find((a) => a.id === state.singleAgentId)?.name || 'Strata Agent'}`
        : 'Equipo Strata · 150 Agentes',
      status: 'running',
      phaseLabel: 'Iniciando…',
      agents: [],
      steps: [],
      files: [],
      content: '',
      needsApiCta: false
    };
    thread.messages.push(assistantMsg);
    saveThreads();
    renderActiveThread();

    $('#composer-input').value = '';
    autoGrowComposer();
    setBusy(true);

    if (isSingle) {
      await runSingleSpecialistTurn(thread, assistantMsg, text, pool);
    } else {
      await runTeamMissionTurn(thread, assistantMsg, text, pool);
    }
  }

  async function runTeamMissionTurn(thread, msg, objective, pool) {
    const mode = state.dispatchMode === 'audit' ? 'audit-and-heal' : 'full-lifecycle';
    const scale = state.dispatchMode === 'audit' ? 'standard' : state.dispatchMode;
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
    if (scale === 'custom' && state.selectedSquad.size) {
      payload.agentIds = [...state.selectedSquad];
    }

    try {
      const res = await api('/api/missions', { method: 'POST', body: payload });
      const missionId = res.mission.id;
      state.activeMissionId = missionId;
      msg.author = `Equipo Strata · ${res.mission.agentCount} especialista${res.mission.agentCount > 1 ? 's' : ''}`;
      msg.needsApiCta = pool.providers.length === 0;
      updateMessageInDOM(msg);

      const es = new EventSource(`/api/missions/${encodeURIComponent(missionId)}/stream`);
      state.activeStream = es;

      es.onmessage = (event) => {
        let ev;
        try { ev = JSON.parse(event.data); } catch { return; }

        if (ev.type === 'snapshot' && ev.mission) {
          msg.agents = (ev.mission.agents || []).map((a) => ({ id: a.id, name: a.name, status: a.status }));
          msg.files = [...(ev.mission.filesChanged || [])];
          if (ev.mission.report) msg.content = ev.mission.report;
          if (ev.mission.status !== 'running') finishMission(ev.mission.status);
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
          msg.steps.push({ kind: 'warn', text: `Failover ${ev.from?.name} → ${ev.to?.name}` });
        } else if (ev.type === 'file_changed') {
          if (!msg.files.includes(ev.path)) msg.files.push(ev.path);
          refreshTree().catch(() => {});
        } else if (ev.type === 'diagnostics') {
          const s = ev.syntax || {};
          const sec = ev.security || {};
          msg.steps.push({
            kind: s.ok ? 'ok' : 'err',
            text: `Verificación: sintaxis ${s.ok ? 'OK' : `${s.errorCount} error(es)`} · seguridad ${sec.score ?? 100}/100`
          });
        } else if (ev.type === 'report') {
          msg.content = ev.report;
        } else if (ev.type === 'error') {
          msg.steps.push({ kind: 'err', text: ev.text || 'Error' });
        } else if (ev.type === 'mission_status' && ['completed', 'failed', 'cancelled'].includes(ev.status)) {
          finishMission(ev.status);
        }
        updateMessageInDOM(msg);
      };

      es.onerror = () => {
        if (es.readyState === EventSource.CLOSED && state.busy) finishMission('completed');
      };

      const finishMission = (finalStatus) => {
        msg.status = finalStatus === 'failed' ? 'error' : finalStatus;
        if (state.activeStream) { state.activeStream.close(); state.activeStream = null; }
        state.activeMissionId = null;
        setBusy(false);
        saveThreads();
        updateMessageInDOM(msg);
        refreshTree().catch(() => {});
        refreshDiagnosticsCount().catch(() => {});
      };
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
            msg.steps.push({ kind: 'warn', text: `Failover ${ev.from?.name} → ${ev.to?.name}` });
          } else if (ev.type === 'ui' && ev.ui?.kind === 'file' && ev.ui.path) {
            if (!msg.files.includes(ev.ui.path)) msg.files.push(ev.ui.path);
            refreshTree().catch(() => {});
          } else if (ev.type === 'assistant_text') {
            acc += (acc ? '\n\n' : '') + (ev.text || '');
            msg.content = acc;
          } else if (ev.type === 'final') {
            msg.content = ev.text || acc || 'Listo.';
            msg.status = 'completed';
          } else if (ev.type === 'error') {
            msg.status = 'error';
            msg.content = `**Error:** ${ev.text}`;
          }
          updateMessageInDOM(msg);
        }
      }
      if (msg.status === 'running') msg.status = 'completed';
    } catch (err) {
      msg.status = err.name === 'AbortError' ? 'cancelled' : 'error';
      msg.content = err.name === 'AbortError' ? (acc || 'Respuesta detenida.') : `**Error:** ${err.message}`;
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

  /* ===================== 150 AGENTS DIRECTORY MODAL ===================== */

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
    setDispatchMode(state.dispatchMode);
  }

  function renderDivisionPills() {
    const container = $('#division-pills');
    container.replaceChildren();
    const allBtn = document.createElement('button');
    allBtn.type = 'button';
    allBtn.className = `div-pill ${!state.agentFilter.division ? 'is-active' : ''}`;
    allBtn.textContent = `Todas (${state.agents.length})`;
    allBtn.addEventListener('click', () => { state.agentFilter.division = ''; renderDivisionPills(); renderAgentsGrid(); });
    container.append(allBtn);

    for (const div of state.divisions) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = `div-pill ${state.agentFilter.division === div.id ? 'is-active' : ''}`;
      btn.textContent = `${div.shortName} (${div.count})`;
      btn.addEventListener('click', () => { state.agentFilter.division = div.id; renderDivisionPills(); renderAgentsGrid(); });
      container.append(btn);
    }
  }

  function renderAgentsGrid() {
    const container = $('#agents-grid');
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
    container.replaceChildren();
    const frag = document.createDocumentFragment();
    for (const a of list) {
      const selected = state.selectedSquad.has(a.id);
      const card = document.createElement('article');
      card.className = `agent-tile ${selected ? 'is-selected' : ''}`;
      card.innerHTML = `
        <div class="agent-tile-head">
          <span class="agent-id">${esc(a.id)}</span>
          <span class="agent-div">${esc(a.divisionShort)}</span>
        </div>
        <h3>${esc(a.name)}</h3>
        <p>${esc(a.specialty)}</p>
        <div class="agent-tile-actions">
          <button type="button" class="btn btn-primary btn-xs talk-btn">Hablar 1-a-1</button>
          <button type="button" class="btn btn-subtle btn-xs squad-btn">${selected ? '✓ En Escuadrón' : '+ Escuadrón'}</button>
        </div>
      `;
      $('.talk-btn', card).addEventListener('click', () => {
        state.singleAgentId = a.id;
        setDispatchMode('single');
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
    container.append(frag);
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

  /* ===================== MULTI-LLM FREE POOL UI ===================== */

  function renderProviderPresets() {
    const grid = $('#provider-presets-grid');
    grid.replaceChildren();
    for (const preset of state.presets) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'preset-btn';
      btn.innerHTML = `<strong>${esc(preset.id === 'openrouter-free' ? 'OpenRouter: buscar modelos GRATIS' : preset.name)}</strong><span>${esc(preset.tier || 'OpenAI-compatible')}</span>`;
      btn.addEventListener('click', () => addProvider(preset));
      grid.append(btn);
    }
  }

  function addProvider(preset) {
    const id = `prov-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 5)}`;
    const item = {
      id,
      name: preset ? preset.name : `Proveedor #${state.config.providers.length + 1}`,
      baseUrl: preset ? preset.baseUrl : 'https://openrouter.ai/api/v1',
      model: preset ? (preset.defaultModel || '') : '',
      apiKey: '',
      enabled: true,
      priority: state.config.providers.length + 1,
      docsUrl: preset?.docsUrl || ''
    };
    state.config.providers.push(item);
    saveConfig();
    renderProvidersList();
    toast(`Añadido: ${item.name}. Pega tu API key y pulsa Probar.`);
  }

  function renderProvidersList() {
    const list = $('#providers-list');
    list.replaceChildren();
    if (!state.config.providers.length) {
      list.innerHTML = '<div class="empty-clean">Aún no hay APIs conectadas. Toca un proveedor arriba (como OpenRouter, Groq o Gemini) para agregarlo.</div>';
      return;
    }
    for (const p of state.config.providers) {
      const card = document.createElement('div');
      card.className = 'provider-card';
      const dlId = `dl-${p.id}`;
      card.innerHTML = `
        <div class="provider-card-head">
          <label class="inline-row"><input type="checkbox" class="p-en" ${p.enabled !== false ? 'checked' : ''}><strong>${esc(p.name)}</strong></label>
          <button type="button" class="btn btn-subtle btn-xs p-del">Quitar</button>
        </div>
        <div class="field-col">
          <label>URL Base API</label>
          <input type="url" class="field sm p-url" value="${esc(p.baseUrl || '')}">
        </div>
        <div class="field-col">
          <label>API Key ${p.docsUrl ? `<a href="${esc(p.docsUrl)}" target="_blank" rel="noopener" style="color:var(--accent-blue);margin-left:6px;">Obtener gratis ↗</a>` : ''}</label>
          <div class="inline-row">
            <input type="password" class="field sm p-key" value="${esc(p.apiKey || '')}" placeholder="Pega tu clave API" autocomplete="new-password">
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
        saveConfig();
      };
      ['.p-en', '.p-url', '.p-key', '.p-model'].forEach((s) => {
        $(s, card).addEventListener('change', sync);
        $(s, card).addEventListener('input', sync);
      });
      $('.p-vis', card).addEventListener('click', (e) => {
        const inp = $('.p-key', card);
        inp.type = inp.type === 'password' ? 'text' : 'password';
        e.currentTarget.textContent = inp.type === 'password' ? 'Ver' : 'Ocultar';
      });
      $('.p-del', card).addEventListener('click', () => {
        state.config.providers = state.config.providers.filter((x) => x.id !== p.id);
        saveConfig();
        renderProvidersList();
      });
      $('.p-disc', card).addEventListener('click', async (e) => {
        sync();
        const btn = e.currentTarget;
        const msg = $('.provider-msg', card);
        btn.disabled = true;
        try {
          const r = await api('/api/providers/models', { method: 'POST', body: { config: p } });
          const dl = $(`#${dlId}`, card);
          dl.replaceChildren();
          for (const m of r.models || []) {
            const opt = document.createElement('option');
            opt.value = m.id;
            opt.label = m.free ? 'GRATIS' : '';
            dl.append(opt);
          }
          const best = r.models?.find((m) => m.free) || r.models?.[0];
          if (best && !p.model) { $('.p-model', card).value = best.id; sync(); }
          msg.className = 'provider-msg ok';
          msg.textContent = `${r.models.length} modelos encontrados (${r.freeCount} gratis).`;
        } catch (err) {
          msg.className = 'provider-msg err';
          msg.textContent = err.message;
        } finally { btn.disabled = false; }
      });
      $('.p-test', card).addEventListener('click', async (e) => {
        sync();
        const btn = e.currentTarget;
        const msg = $('.provider-msg', card);
        btn.disabled = true;
        try {
          const r = await api('/api/providers/test', { method: 'POST', body: { config: p } });
          msg.className = 'provider-msg ok';
          msg.textContent = `✓ Conectado · ${r.model} · ${r.latencyMs || 0} ms`;
        } catch (err) {
          msg.className = 'provider-msg err';
          msg.textContent = err.message;
        } finally { btn.disabled = false; }
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

  /* ===================== CODE STUDIO (MONACO + TREE) ===================== */

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
      monaco.editor.defineTheme('strata-light', {
        base: 'vs', inherit: true, rules: [],
        colors: { 'editor.background': '#ffffff', 'editor.lineHighlightBackground': '#f7f8fa' }
      });
      monaco.editor.defineTheme('strata-dark', {
        base: 'vs-dark', inherit: true, rules: [],
        colors: { 'editor.background': '#14161a', 'editor.lineHighlightBackground': '#1c2026' }
      });
      state.editor = monaco.editor.create($('#monaco-editor'), {
        theme: state.theme === 'dark' ? 'strata-dark' : 'strata-light',
        value: '',
        language: 'plaintext',
        automaticLayout: true,
        fontSize: state.config.fontSize || 14,
        wordWrap: state.config.wordWrap !== false ? 'on' : 'off',
        minimap: { enabled: false },
        scrollBeyondLastLine: false,
        padding: { top: 8, bottom: 8 }
      });
      state.monacoReady = true;
      if (state.activePath) activateTab(state.activePath);
    });
  }

  async function refreshTree() {
    const res = await api('/api/tree?depth=16');
    state.tree = res.tree || [];
    let count = 0;
    const walk = (nodes) => {
      for (const n of nodes) {
        if (n.type === 'file') count++;
        if (n.children) walk(n.children);
      }
    };
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
    const renderNode = (node, depth) => {
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
      if (expanded && node.children) {
        for (const ch of node.children) wrap.append(renderNode(ch, depth + 1));
      }
      return wrap;
    };
    for (const n of state.tree) root.append(renderNode(n, 0));
  }

  async function openFile(relPath, line) {
    let tab = state.tabs.get(relPath);
    if (!tab) {
      const data = await api(`/api/file?path=${encodeURIComponent(relPath)}`);
      tab = {
        path: data.path,
        name: data.path.split('/').pop(),
        content: data.content,
        savedContent: data.content,
        version: data.version,
        language: data.language || langFor(data.path),
        model: null,
        dirty: false
      };
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
          renderTabs();
          updateFileButtons();
        });
      }
      $('#editor-empty-state').classList.add('hidden');
      $('#monaco-editor').classList.remove('hidden');
      state.editor.setModel(tab.model);
      state.editor.layout();
    }
    $('#active-file-breadcrumb').textContent = tab.path;
    renderTabs();
    renderTree();
    updateFileButtons();
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
    const res = await api('/api/file', {
      method: 'PUT',
      body: { path: tab.path, content: latest, expectedVersion: tab.version }
    });
    tab.content = latest;
    tab.savedContent = latest;
    tab.version = res.version;
    tab.dirty = false;
    renderTabs();
    updateFileButtons();
  }

  /* ===================== TERMINAL, OUTPUT, DIAGNOSTICS ===================== */

  function ensureTerminal() {
    if (state.terminal || !window.Terminal || !window.FitAddon?.FitAddon) return;
    const term = new window.Terminal({
      cursorBlink: true,
      fontSize: 13,
      fontFamily: "'SFMono-Regular', Menlo, Consolas, monospace",
      convertEol: true,
      theme: { background: '#161b22', foreground: '#e6edf3', cursor: '#60a5fa' }
    });
    const fit = new window.FitAddon.FitAddon();
    term.loadAddon(fit);
    const host = document.createElement('div');
    host.className = 'terminal-host';
    $('#terminal-host-wrap').append(host);
    term.open(host);
    state.terminal = term;
    state.termFit = fit;

    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${proto}//${location.host}/ws/terminal?cwd=`);
    state.termWs = ws;
    ws.addEventListener('open', () => { $('#terminal-state').textContent = 'Conectada'; fitTerminal(); });
    ws.addEventListener('message', (e) => {
      const write = (t) => {
        if (t.includes('modo compatible')) state.termCompat = true;
        term.write(t);
      };
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

  function fitTerminal() {
    try { state.termFit?.fit(); } catch { /* hidden */ }
  }

  function sendTerminalInput(str) {
    ensureTerminal();
    if (!state.termWs || state.termWs.readyState !== WebSocket.OPEN) return toast('Terminal conectando…', 'warning');
    if (state.termCompat && str !== '\x03') state.terminal.write(str.replace(/\n/g, '\r\n'));
    state.termWs.send(JSON.stringify({ type: 'input', data: str }));
  }

  function renderRunOutput(run) {
    const box = $('#run-output');
    box.textContent = `${run.ok ? '✓ OK' : `✕ FALLO (código ${run.code})`} · ${run.runtime || run.command || ''} (${run.durationMs || 0} ms)\n\n${run.stdout || ''}${run.stderr ? '\n--- STDERR ---\n' + run.stderr : ''}`;
  }

  async function runSyntaxCheck() {
    const res = await api('/api/tools/verify-syntax', { method: 'POST', body: {} });
    state.lastSyntax = res.verification;
    renderDiagnostics();
    return res.verification;
  }

  async function runSecurityAudit() {
    const res = await api('/api/tools/security-audit', { method: 'POST', body: {} });
    state.lastSecurity = res.audit;
    renderDiagnostics();
    return res.audit;
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
    for (const d of state.lastSyntax?.diagnostics || []) {
      combined.push({ file: d.file, line: d.line || 1, label: `[Sintaxis · ${d.runtime}] ${d.message}` });
    }
    for (const f of state.lastSecurity?.findings || []) {
      combined.push({ file: f.file, line: f.line || 1, label: `[Seguridad · ${f.severity}] ${f.title}` });
    }
    state.lastDiagnostics = combined;
    $('#ws-diag-badge').textContent = String(combined.length);
    $('#sidebar-diag-count').textContent = String(combined.length);
    $('#diagnostics-summary-line').textContent =
      `Sintaxis: ${state.lastSyntax ? (state.lastSyntax.ok ? 'OK' : `${state.lastSyntax.errorCount} error(es)`) : '—'} · Seguridad: ${state.lastSecurity ? `${state.lastSecurity.score}/100` : '—'}`;
    const list = $('#diagnostics-list');
    list.replaceChildren();
    if (!combined.length) {
      list.innerHTML = '<div class="empty-clean">✓ Sin errores de sintaxis ni vulnerabilidades detectadas.</div>';
      return;
    }
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
    const list = $('#processes-list');
    list.replaceChildren();
    if (!res.processes?.length) { list.innerHTML = '<div class="empty-clean">Sin procesos en segundo plano.</div>'; return; }
    for (const p of res.processes) {
      const el = document.createElement('div');
      el.className = 'diag-row';
      el.innerHTML = `<div class="group-head-row"><strong>${esc(p.name)}</strong><span class="muted-xs">${esc(p.status)}</span></div><pre class="clean-pre" style="margin-top:6px;">${esc(p.logTail || '(sin salida)')}</pre>`;
      if (p.status === 'running') {
        const stop = document.createElement('button');
        stop.type = 'button';
        stop.className = 'btn btn-danger btn-xs';
        stop.style.marginTop = '6px';
        stop.textContent = 'Detener';
        stop.addEventListener('click', async () => {
          await api(`/api/processes/${encodeURIComponent(p.id)}/stop`, { method: 'POST' });
          refreshProcesses();
        });
        el.append(stop);
      }
      list.append(el);
    }
  }

  async function refreshGitStatus() {
    const r = await api('/api/git/status');
    const g = r.git;
    $('#git-status-box').textContent = !g.initialized
      ? g.summary
      : `Rama: ${g.branch}\n\n${g.files.length ? g.files.map((f) => `${f.status} ${f.path}`).join('\n') : 'Limpio (sin cambios pendientes).'}`;
  }

  async function refreshSnapshots() {
    const r = await api('/api/snapshots');
    const list = $('#snapshot-list');
    list.replaceChildren();
    if (!r.snapshots?.length) { list.innerHTML = '<div class="empty-clean">Sin copias guardadas.</div>'; return; }
    for (const id of r.snapshots) {
      const item = document.createElement('div');
      item.className = 'snap-item';
      item.innerHTML = `<strong class="mono">${esc(id)}</strong><button type="button" class="btn btn-subtle btn-xs">Restaurar</button>`;
      $('button', item).addEventListener('click', async () => {
        if (!window.confirm(`¿Restaurar desde ${id}?`)) return;
        await api('/api/restore', { method: 'POST', body: { id } });
        toast('Copia restaurada.');
        refreshTree();
      });
      list.append(item);
    }
  }

  function promptUser(title, desc, value = '') {
    $('#prompt-title').textContent = title;
    $('#prompt-description').textContent = desc;
    $('#prompt-input').value = value;
    const dlg = $('#prompt-dialog');
    if (!dlg.open) dlg.showModal();
    requestAnimationFrame(() => $('#prompt-input').focus());
    return new Promise((resolve) => { state.promptResolve = resolve; });
  }

  async function createFileOrDir(isDir) {
    const name = await promptUser(isDir ? 'Nueva carpeta' : 'Nuevo archivo', 'Ruta relativa al workspace', state.selectedDir ? `${state.selectedDir}/` : '');
    if (!name || !name.trim()) return;
    const clean = name.trim().replace(/^\/+|\/+$/g, '');
    if (isDir) await api('/api/mkdir', { method: 'POST', body: { path: clean } });
    else {
      await api('/api/file', { method: 'POST', body: { path: clean, content: '' } });
      await refreshTree();
      await openFile(clean);
      return;
    }
    await refreshTree();
  }

  async function handleFileUpload(fileList) {
    const files = [...fileList];
    if (!files.length) return;
    const payload = [];
    for (const f of files.slice(0, 60)) {
      if (f.size > 4 * 1024 * 1024) continue;
      payload.push({ path: f.webkitRelativePath || f.name, content: await f.text() });
    }
    if (!payload.length) return;
    const r = await api('/api/project/import', { method: 'POST', body: { files: payload } });
    toast(`Importados ${r.count} archivo(s).`);
    await refreshTree();
    openWorkspace('code');
  }

  /* ===================== EVENT BINDINGS ===================== */

  function bindEvents() {
    // Sidebar & Header
    $('#toggle-sidebar-btn').addEventListener('click', toggleSidebar);
    $('#close-sidebar-btn').addEventListener('click', closeSidebar);
    $('#sidebar-backdrop').addEventListener('click', closeSidebar);
    $('#new-chat-btn').addEventListener('click', () => {
      state.activeThreadId = null;
      if (isMobile()) closeSidebar();
      renderThreadsSidebar();
      renderActiveThread();
      $('#composer-input').focus();
    });
    $('#toggle-workspace-btn').addEventListener('click', () => {
      if (state.workspaceOpen) closeWorkspace();
      else openWorkspace(state.wsTab);
    });
    $('#close-workspace-btn').addEventListener('click', closeWorkspace);
    $$('[data-open-workspace]').forEach((b) => b.addEventListener('click', () => openWorkspace(b.dataset.openWorkspace)));
    $$('[data-open-drawer]').forEach((b) => b.addEventListener('click', () => openToolsDrawer(b.dataset.openDrawer)));
    $('#open-agents-modal-btn').addEventListener('click', openAgentsModal);
    $('#popover-browse-agents-btn').addEventListener('click', openAgentsModal);
    $('#close-agents-modal-btn').addEventListener('click', closeAllOverlays);
    $('#close-tools-drawer-btn').addEventListener('click', closeAllOverlays);
    $('#overlay-backdrop').addEventListener('click', closeAllOverlays);
    $('#theme-toggle-btn').addEventListener('click', () => { applyTheme(state.theme === 'dark' ? 'light' : 'dark'); saveConfig(); });

    // Mode Popover
    const togglePopover = () => $('#mode-popover').classList.toggle('hidden');
    $('#composer-mode-btn').addEventListener('click', togglePopover);
    $('#header-mode-btn').addEventListener('click', togglePopover);
    $$('.mode-option').forEach((btn) => btn.addEventListener('click', () => setDispatchMode(btn.dataset.dispatchMode)));
    document.addEventListener('click', (e) => {
      if (!e.target.closest('#mode-popover') && !e.target.closest('#composer-mode-btn') && !e.target.closest('#header-mode-btn')) {
        $('#mode-popover').classList.add('hidden');
      }
    });

    // Arena Composer & Starter Cards
    $$('[data-starter-prompt]').forEach((card) => card.addEventListener('click', () => {
      handleSendPrompt(card.dataset.starterPrompt);
    }));
    $('#composer-form').addEventListener('submit', (e) => { e.preventDefault(); handleSendPrompt($('#composer-input').value); });
    $('#composer-input').addEventListener('input', autoGrowComposer);
    $('#composer-input').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && !isMobile()) {
        e.preventDefault();
        handleSendPrompt($('#composer-input').value);
      }
    });
    $('#composer-upload-btn').addEventListener('click', () => $('#hidden-file-upload').click());
    $('#welcome-upload-btn').addEventListener('click', () => $('#hidden-file-upload').click());
    $('#drawer-upload-files-btn').addEventListener('click', () => $('#hidden-file-upload').click());
    $('#hidden-file-upload').addEventListener('change', (e) => { handleFileUpload(e.target.files).catch((err) => toast(err.message, 'error')); e.target.value = ''; });

    // Agents Modal
    $('#agent-search-input').addEventListener('input', (e) => { state.agentFilter.q = e.target.value; renderAgentsGrid(); });
    $('#clear-squad-btn').addEventListener('click', () => { state.selectedSquad.clear(); updateSquadButtons(); renderAgentsGrid(); });
    $('#apply-squad-btn').addEventListener('click', () => {
      setDispatchMode('custom');
      closeAllOverlays();
      $('#composer-input').focus();
      toast(`Escuadrón activo: ${state.selectedSquad.size} especialistas.`);
    });

    // Workspace Tabs & Code Studio
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
    $('#save-file-btn').addEventListener('click', async () => {
      try { await saveTab(state.tabs.get(state.activePath)); toast('Archivo guardado.'); } catch (err) { toast(err.message, 'error'); }
    });
    $('#verify-file-btn').addEventListener('click', async () => {
      const tab = state.tabs.get(state.activePath);
      if (!tab) return;
      if (tab.dirty) await saveTab(tab).catch(() => {});
      const r = await api('/api/tools/verify-file', { method: 'POST', body: { path: tab.path } });
      const b = $('#file-diagnostic-banner');
      b.classList.remove('hidden', 'is-ok', 'is-error');
      b.classList.add(r.verification.ok && r.verification.totalIssues === 0 ? 'is-ok' : 'is-error');
      b.textContent = r.verification.ok && r.verification.totalIssues === 0
        ? `✓ ${tab.path}: sin errores.`
        : `✕ ${r.verification.totalIssues} problema(s) detectado(s).`;
    });
    $('#run-file-btn').addEventListener('click', async () => {
      const tab = state.tabs.get(state.activePath);
      if (!tab) return;
      if (tab.dirty) await saveTab(tab).catch(() => {});
      const r = await api('/api/run', { method: 'POST', body: { path: tab.path } });
      renderRunOutput(r.run);
      switchWorkspaceTab('terminal');
      $$('[data-term-sub]').forEach((x) => x.classList.toggle('is-active', x.dataset.termSub === 'output'));
      $$('.term-subpane').forEach((p) => p.classList.toggle('is-active', p.id === 'term-sub-output'));
    });
    $('#code-search-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const q = $('#search-query').value.trim();
      if (!q) return;
      const r = await api('/api/search?q=' + encodeURIComponent(q));
      const box = $('#search-results');
      box.replaceChildren();
      for (const hit of (r.results || []).slice(0, 100)) {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'search-hit';
        b.innerHTML = `<strong>${esc(hit.file)}:${hit.line}</strong><span>${esc(hit.text)}</span>`;
        b.addEventListener('click', () => openFile(hit.file, hit.line));
        box.append(b);
      }
    });
    $('#create-snapshot-btn').addEventListener('click', async () => {
      await api('/api/snapshot', { method: 'POST', body: { label: 'manual' } });
      toast('Copia guardada.');
      refreshSnapshots();
    });

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
    $('#mobile-term-form').addEventListener('submit', (e) => {
      e.preventDefault();
      const v = $('#mobile-term-input').value;
      if (!v.trim()) return;
      sendTerminalInput(v + '\n');
      $('#mobile-term-input').value = '';
    });
    $('#start-proc-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      await api('/api/processes/start', { method: 'POST', body: { name: $('#proc-name-input').value.trim(), command: $('#proc-cmd-input').value.trim() } });
      $('#proc-name-input').value = '';
      $('#proc-cmd-input').value = '';
      refreshProcesses();
    });
    $('#rt-run-syntax-btn').addEventListener('click', () => runSyntaxCheck().then((v) => toast(v.ok ? 'Sintaxis OK.' : `${v.errorCount} error(es).`, v.ok ? 'success' : 'warning')));
    $('#rt-run-security-btn').addEventListener('click', () => runSecurityAudit().then((a) => toast(`Seguridad: ${a.score}/100.`)));
    $('#send-diags-to-debuggers-btn').addEventListener('click', () => {
      closeWorkspace();
      setDispatchMode('audit');
      const summary = state.lastDiagnostics.slice(0, 12).map((d) => `${d.file}:${d.line} ${d.label}`).join('; ');
      handleSendPrompt(`Corrige todos los errores de sintaxis y hallazgos de seguridad y verifica con pruebas reales: ${summary || 'auditoría completa del proyecto'}`);
    });

    // Config Drawer Tabs & Actions
    $$('.drawer-tab').forEach((b) => b.addEventListener('click', () => openToolsDrawer(b.dataset.drawerTab)));
    $('#add-custom-provider-btn').addEventListener('click', () => addProvider(null));
    $('#test-all-providers-btn').addEventListener('click', async () => {
      const active = state.config.providers.filter((p) => p.enabled !== false);
      if (!active.length) return toast('Agrega primero un proveedor.', 'warning');
      const r = await api('/api/providers/test-pool', { method: 'POST', body: { providers: active } });
      toast(`Pool: ${r.healthy} de ${r.total} operativos.`, r.healthy === r.total ? 'success' : 'warning');
    });
    $('#save-pool-config-btn').addEventListener('click', () => {
      state.config.strategy = $('#pool-strategy').value;
      state.config.concurrency = Math.min(16, Math.max(1, Number($('#pool-concurrency').value) || 4));
      state.config.maxSteps = Math.min(60, Math.max(2, Number($('#pool-max-steps').value) || 18));
      state.config.temperature = Number($('#pool-temperature').value) || 0.2;
      state.config.tools = $('#pool-tools-enabled').checked;
      saveConfig();
      toast('Pool Multi-LLM guardado.');
    });
    $('#toggle-api-key').addEventListener('click', (e) => {
      const i = $('#setting-api-key');
      i.type = i.type === 'password' ? 'text' : 'password';
      e.currentTarget.textContent = i.type === 'password' ? 'Ver' : 'Ocultar';
    });
    $('#discover-models-button').addEventListener('click', async () => {
      try {
        const r = await api('/api/agent/models', { method: 'POST', body: { config: { baseUrl: $('#setting-base-url').value.trim(), apiKey: $('#setting-api-key').value.trim() } } });
        const dl = $('#setting-model-options');
        dl.replaceChildren();
        for (const m of r.models || []) {
          const opt = document.createElement('option');
          opt.value = m.id; opt.label = m.free ? 'GRATIS' : '';
          dl.append(opt);
        }
        const best = r.models?.find((m) => m.free) || r.models?.[0];
        if (best) $('#setting-model').value = best.id;
        $('#model-discovery-note').textContent = `${r.models.length} modelos encontrados (${r.freeCount} gratis).`;
      } catch (err) { $('#model-discovery-note').textContent = err.message; }
    });
    $('#quick-test-single-btn').addEventListener('click', async () => {
      const b = $('#agent-test-result');
      b.className = 'status-banner';
      b.textContent = 'Probando…';
      try {
        const r = await api('/api/agent/test', { method: 'POST', body: { config: { baseUrl: $('#setting-base-url').value.trim(), apiKey: $('#setting-api-key').value.trim(), model: $('#setting-model').value.trim() } } });
        b.className = 'status-banner ok'; b.textContent = `✓ Conectado · ${r.model}`;
      } catch (err) { b.className = 'status-banner err'; b.textContent = err.message; }
    });
    $('#quick-add-discovered-btn').addEventListener('click', () => {
      const model = $('#setting-model').value.trim();
      if (!model) return toast('Indica un modelo.', 'warning');
      state.config.providers.push({
        id: `prov-${Date.now().toString(36)}`,
        name: model.split('/').pop(),
        baseUrl: $('#setting-base-url').value.trim() || 'https://openrouter.ai/api/v1',
        apiKey: $('#setting-api-key').value.trim(),
        model, enabled: true, priority: state.config.providers.length + 1
      });
      saveConfig(); renderProvidersList(); toast(`Añadido ${model}.`);
    });

    // Tools Tab
    $('#tool-verify-syntax').addEventListener('click', async () => { await runSyntaxCheck(); closeAllOverlays(); openWorkspace('diagnostics'); });
    $('#tool-security-audit').addEventListener('click', async () => { await runSecurityAudit(); closeAllOverlays(); openWorkspace('diagnostics'); });
    $('#tool-run-tests').addEventListener('click', async () => {
      const r = await api('/api/tools/run-tests', { method: 'POST', body: {} });
      renderRunOutput(r.run); closeAllOverlays(); openWorkspace('terminal');
      $$('[data-term-sub]').forEach((x) => x.classList.toggle('is-active', x.dataset.termSub === 'output'));
      $$('.term-subpane').forEach((p) => p.classList.toggle('is-active', p.id === 'term-sub-output'));
    });
    $('#tool-auto-repair').addEventListener('click', async () => {
      const r = await api('/api/tools/auto-repair', { method: 'POST', body: {} });
      $('#tool-output-preview').classList.remove('hidden');
      $('#tool-output-preview').textContent = JSON.stringify(r.repair, null, 2);
      refreshTree();
    });
    $('#tool-cartography').addEventListener('click', async () => {
      const r = await api('/api/tools/cartography');
      $('#tool-output-preview').classList.remove('hidden');
      $('#tool-output-preview').textContent = JSON.stringify(r.cartography, null, 2);
    });
    $('#tool-list-runtimes').addEventListener('click', async () => {
      const r = await api('/api/runtimes');
      $('#tool-output-preview').classList.remove('hidden');
      $('#tool-output-preview').textContent = r.runtimes.filter((x) => x.available).map((x) => `✓ ${x.label} (${x.bin})`).join('\n');
    });
    $('#scaffold-create-btn').addEventListener('click', async () => {
      const r = await api('/api/project/scaffold', { method: 'POST', body: { template: $('#scaffold-template').value, targetDir: $('#scaffold-dir').value.trim() } });
      toast(`Creado: ${r.label} (${r.files.length} archivos).`);
      await refreshTree();
      closeAllOverlays();
      openWorkspace('code');
      if (r.files[0]) openFile(r.files[0]);
    });
    $('#quick-exec-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const r = await api('/api/exec', { method: 'POST', body: { command: $('#quick-exec-input').value.trim() } });
      renderRunOutput(r.run); closeAllOverlays(); openWorkspace('terminal');
      $$('[data-term-sub]').forEach((x) => x.classList.toggle('is-active', x.dataset.termSub === 'output'));
      $$('.term-subpane').forEach((p) => p.classList.toggle('is-active', p.id === 'term-sub-output'));
    });
    $('#drawer-export-bundle-btn').addEventListener('click', async () => {
      const r = await api('/api/project/export');
      const a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([JSON.stringify(r.bundle, null, 2)], { type: 'application/json' }));
      a.download = `strata-workspace-${Date.now()}.json`;
      a.click();
    });
    $('#drawer-create-snapshot-btn').addEventListener('click', async () => {
      const r = await api('/api/snapshot', { method: 'POST', body: { label: 'manual' } });
      toast(`Copia creada: ${r.id}`);
    });

    // Git Tab
    $('#git-clone-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const btn = $('#git-clone-btn'); btn.disabled = true; btn.textContent = 'Clonando…';
      try {
        const r = await api('/api/git/clone', { method: 'POST', body: { url: $('#git-clone-url').value.trim(), targetDir: $('#git-clone-dir').value.trim() } });
        if (!r.ok) throw new Error(r.stderr || 'Error clonando.');
        toast(`Clonado en ${r.targetDir}`);
        await refreshTree(); await refreshGitStatus();
      } catch (err) { toast(err.message, 'error'); }
      finally { btn.disabled = false; btn.textContent = 'Clonar'; }
    });
    $('#git-refresh-btn').addEventListener('click', () => refreshGitStatus());
    $('#git-diff-btn').addEventListener('click', async () => { $('#git-status-box').textContent = (await api('/api/git/diff')).diff; });
    $('#git-log-btn').addEventListener('click', async () => {
      const r = await api('/api/git/log');
      $('#git-status-box').textContent = r.commits?.length ? r.commits.map((c) => `${c.hash}  ${c.message}`).join('\n') : 'Sin commits.';
    });
    $('#git-init-btn').addEventListener('click', async () => { await api('/api/git/init', { method: 'POST', body: {} }); refreshGitStatus(); });
    $('#git-commit-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const r = await api('/api/git/commit', { method: 'POST', body: { message: $('#git-commit-msg').value.trim() } });
      if (!r.ok) return toast(r.stderr || 'Error al hacer commit.', 'error');
      $('#git-commit-msg').value = ''; toast('Commit creado.'); refreshGitStatus();
    });

    // Settings Tab
    $('#setting-font-size').addEventListener('input', (e) => { $('#font-size-value').textContent = `${e.target.value} px`; });
    $('#save-general-settings-btn').addEventListener('click', () => {
      state.config.autoDebugRetries = Number($('#setting-debug-retries').value) || 2;
      state.config.autoSnapshot = $('#setting-auto-snapshot').checked;
      state.config.fontSize = Number($('#setting-font-size').value) || 14;
      state.config.wordWrap = $('#setting-wordwrap').checked;
      state.config.autosave = $('#setting-autosave').checked;
      applyTheme($('#setting-theme').value);
      saveConfig();
      state.editor?.updateOptions({ fontSize: state.config.fontSize, wordWrap: state.config.wordWrap ? 'on' : 'off' });
      toast('Ajustes guardados.');
    });
    $('#export-config-btn').addEventListener('click', () => {
      const clean = { ...state.config, providers: state.config.providers.map((p) => ({ ...p, apiKey: '' })) };
      const a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([JSON.stringify(clean, null, 2)], { type: 'application/json' }));
      a.download = 'strata-config.json';
      a.click();
    });
    $('#import-config-btn').addEventListener('click', () => $('#import-config-file').click());
    $('#import-config-file').addEventListener('change', async (e) => {
      const f = e.target.files?.[0]; if (!f) return;
      try {
        state.config = { ...state.config, ...JSON.parse(await f.text()) };
        saveConfig(); renderProvidersList(); applyTheme(state.config.theme); toast('Configuración importada.');
      } catch { toast('JSON inválido.', 'error'); }
      e.target.value = '';
    });

    // Dialog & Keys
    $('#prompt-form').addEventListener('submit', (e) => {
      e.preventDefault();
      const v = $('#prompt-input').value;
      $('#prompt-dialog').close();
      const fn = state.promptResolve; state.promptResolve = null; fn?.(v);
    });
    $$('[data-close-dialog]').forEach((b) => b.addEventListener('click', () => document.getElementById(b.dataset.closeDialog)?.close()));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') closeAllOverlays();
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's' && state.workspaceOpen && state.wsTab === 'code') {
        e.preventDefault();
        saveTab(state.tabs.get(state.activePath)).then(() => toast('Guardado.')).catch(() => {});
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
