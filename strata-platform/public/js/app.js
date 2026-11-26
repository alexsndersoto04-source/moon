'use strict';

(() => {
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const STORAGE_KEY = 'strata.engineering.v1';
  const SESSION_KEY = 'strata.engineering.session.v1';

  const state = {
    view: 'missions',
    theme: 'light',
    health: null,
    presets: [],
    divisions: [],
    agents: [],
    agentFilter: { q: '', division: '', phase: '' },
    selectedSquad: new Set(),

    // Multi-LLM Pool & Settings (persisted in browser localStorage)
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
      minimap: false,
      autosave: false
    },

    // Missions
    missions: [],
    activeMissionId: null,
    missionStream: null,

    // Direct 1-on-1 Specialist Chat
    chatAgentId: 'AG-141',
    chatSessionId: '',
    chatBusy: false,
    chatAbort: null,
    chatTranscripts: new Map(),

    // Code Studio
    tree: [],
    treeLookup: new Map(),
    expanded: new Set(),
    selectedDir: '',
    tabs: new Map(),
    activePath: null,
    editor: null,
    monacoReady: false,
    quickFiles: [],
    quickIndex: 0,
    promptResolve: null,

    // Terminal & Diagnostics
    terminals: [],
    activeTerminalId: null,
    termSeq: 0,
    lastDiagnostics: [],
    lastSecurity: null,
    lastSyntax: null,
    globalStream: null
  };

  class ApiError extends Error {
    constructor(message, status) {
      super(message);
      this.name = 'ApiError';
      this.status = status;
    }
  }

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
      throw new ApiError(data.error || data.message || `Error HTTP ${res.status}`, res.status);
    }
    return data;
  }

  function escapeHTML(str) {
    return String(str == null ? '' : str).replace(/[&<>"']/g, (c) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[c]);
  }

  function renderMarkdown(text) {
    const source = String(text || '').replace(/\r\n/g, '\n');
    const blocks = [];
    let safe = escapeHTML(source).replace(/```([\w-]*)\n?([\s\S]*?)```/g, (_m, lang, code) => {
      const idx = blocks.push(`<pre><code${lang ? ` data-lang="${escapeHTML(lang)}"` : ''}>${code.replace(/^\n|\n$/g, '')}</code></pre>`) - 1;
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
    return out.join('') || '<p></p>';
  }

  function toast(message, kind = 'success', ms = 3600) {
    const root = $('#toast-root');
    const el = document.createElement('div');
    el.className = `toast ${kind}`;
    el.textContent = message;
    root.append(el);
    setTimeout(() => el.remove(), ms);
  }

  function setStatus(msg) {
    const el = $('#status-message');
    if (el) el.textContent = msg;
  }

  function langFor(rel) {
    const base = String(rel || '').split('/').pop().toLowerCase();
    const ext = base.includes('.') ? base.slice(base.lastIndexOf('.')) : '';
    const map = {
      '.js': 'javascript', '.mjs': 'javascript', '.cjs': 'javascript',
      '.ts': 'typescript', '.tsx': 'typescript', '.mts': 'typescript',
      '.py': 'python', '.html': 'html', '.htm': 'html', '.css': 'css', '.scss': 'scss',
      '.json': 'json', '.md': 'markdown', '.rs': 'rust', '.go': 'go', '.java': 'java',
      '.rb': 'ruby', '.php': 'php', '.sh': 'shell', '.bash': 'shell', '.c': 'c', '.h': 'c',
      '.cpp': 'cpp', '.cc': 'cpp', '.sql': 'sql', '.xml': 'xml', '.svg': 'xml',
      '.yml': 'yaml', '.yaml': 'yaml', '.toml': 'ini', '.ini': 'ini', '.kt': 'kotlin', '.swift': 'swift'
    };
    return map[ext] || 'plaintext';
  }

  /* ===================== PERSISTENCE & THEME ===================== */

  function loadConfig() {
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
      state.config = { ...state.config, ...saved };
      if (!Array.isArray(state.config.providers)) state.config.providers = [];
      if (!state.config.divisionMap || typeof state.config.divisionMap !== 'object') state.config.divisionMap = {};
      if (!state.config.agentMap || typeof state.config.agentMap !== 'object') state.config.agentMap = {};
    } catch { /* ignore */ }

    try {
      state.chatSessionId = sessionStorage.getItem(SESSION_KEY) || `strata-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      sessionStorage.setItem(SESSION_KEY, state.chatSessionId);
    } catch {
      state.chatSessionId = `strata-${Date.now()}`;
    }
    applyTheme(state.config.theme || 'light');
  }

  function saveConfig() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state.config));
    updatePoolStatusUI();
    renderDivisionMatrix();
  }

  function applyTheme(theme) {
    state.theme = theme === 'dark' ? 'dark' : 'light';
    state.config.theme = state.theme;
    document.documentElement.setAttribute('data-theme', state.theme);
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = state.theme === 'dark' ? '#151c25' : '#ffffff';
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
    const dot = $('#pool-status-dot');
    dot.classList.toggle('is-ready', count > 0);
    $('#pool-status-text').textContent = count > 0
      ? `Pool LLM: ${count} API${count > 1 ? 's' : ''} activa${count > 1 ? 's' : ''} (${state.config.strategy})`
      : 'Pool LLM: Motor Local · Conectar APIs Gratis';
    $('#kpi-pool').textContent = count > 0 ? `${count} API${count > 1 ? 's' : ''}` : 'Motor Local';
    $('#kpi-pool-sub').textContent = count > 0
      ? `${active.slice(0, 2).map((p) => p.name).join(' + ')}${count > 2 ? ` +${count - 2}` : ''}`
      : 'Toca para conectar OpenRouter, Groq, Gemini…';
    const hint = $('#mission-engine-hint');
    if (hint) {
      hint.innerHTML = count > 0
        ? `Motor activo: <strong>Flota Autónoma Multi-LLM (${count} API${count > 1 ? 's' : ''} · ${escapeHTML(state.config.strategy)})</strong> con failover automático y herramientas reales.`
        : 'Motor actual: <strong>Local Determinista</strong> (ejecuta cartografía, sintaxis, seguridad y tests reales). Conecta 1 o más APIs LLM gratis en <strong>Herramientas y Config</strong> para programación autónoma con IA.';
    }
  }

  /* ===================== NAVIGATION & DRAWERS ===================== */

  function switchView(view) {
    state.view = view;
    $$('.page-view').forEach((el) => el.classList.toggle('is-active', el.id === `view-${view}`));
    $$('[data-nav]').forEach((btn) => btn.classList.toggle('is-active', btn.dataset.nav === view));
    if (view === 'code') {
      setTimeout(() => state.editor?.layout(), 60);
    }
    if (view === 'runtime') {
      setTimeout(() => fitActiveTerminal(), 80);
    }
  }

  function openToolsDrawer(tab = 'providers') {
    closeChatDrawer();
    $('#drawer-backdrop').classList.remove('hidden');
    $('#tools-drawer').classList.remove('hidden');
    $('#open-tools-drawer-btn').setAttribute('aria-expanded', 'true');
    switchDrawerTab(tab);
  }

  function closeToolsDrawer() {
    $('#tools-drawer').classList.add('hidden');
    $('#open-tools-drawer-btn').setAttribute('aria-expanded', 'false');
    if ($('#agent-chat-drawer').classList.contains('hidden')) {
      $('#drawer-backdrop').classList.add('hidden');
    }
  }

  function switchDrawerTab(tab) {
    $$('.drawer-tab').forEach((b) => b.classList.toggle('is-active', b.dataset.drawerTab === tab));
    $$('.drawer-pane').forEach((p) => p.classList.toggle('is-active', p.id === `drawer-pane-${tab}`));
    if (tab === 'git') refreshGitStatus().catch(() => {});
  }

  function openChatDrawer(agentId = state.chatAgentId || 'AG-141') {
    closeToolsDrawer();
    const agent = state.agents.find((a) => a.id === agentId) || state.agents[0];
    if (!agent) return;
    state.chatAgentId = agent.id;
    $('#chat-agent-id').textContent = agent.id;
    $('#chat-agent-division').textContent = agent.divisionShort;
    $('#chat-agent-name').textContent = `${agent.name}`;
    $('#chat-agent-specialty').textContent = `${agent.tier} · ${agent.specialty}`;

    const checklistEl = $('#chat-agent-checklist');
    checklistEl.innerHTML = (agent.checklist || []).map((c) => `<li>${escapeHTML(c)}</li>`).join('');
    const toolsEl = $('#chat-agent-tools');
    toolsEl.innerHTML = (agent.tools || []).map((t) => `<span class="tag-mini">${escapeHTML(t)}</span>`).join('');

    renderChatProviderPin(agent.id);
    updateChatSquadButton(agent.id);
    renderChatTranscript(agent.id);

    $('#drawer-backdrop').classList.remove('hidden');
    $('#agent-chat-drawer').classList.remove('hidden');
    setTimeout(() => $('#agent-input').focus(), 80);
  }

  function closeChatDrawer() {
    $('#agent-chat-drawer').classList.add('hidden');
    if ($('#tools-drawer').classList.contains('hidden')) {
      $('#drawer-backdrop').classList.add('hidden');
    }
  }

  function renderChatProviderPin(agentId) {
    const sel = $('#chat-agent-provider-pin');
    const current = state.config.agentMap[agentId] || '';
    const opts = ['<option value="">Automático (Pool Multi-LLM)</option>'];
    for (const p of state.config.providers) {
      opts.push(`<option value="${escapeHTML(p.id)}" ${p.id === current ? 'selected' : ''}>${escapeHTML(p.name)} (${escapeHTML(p.model || 'sin modelo')})</option>`);
    }
    sel.innerHTML = opts.join('');
  }

  function updateChatSquadButton(agentId) {
    const btn = $('#chat-add-squad-btn');
    const inSquad = state.selectedSquad.has(agentId);
    btn.textContent = inSquad ? '✓ En el Escuadrón (Quitar)' : '+ Añadir al Escuadrón';
  }

  /* ===================== MULTI-LLM FREE PROVIDER POOL UI ===================== */

  function renderProviderPresets() {
    const container = $('#provider-presets-grid');
    container.replaceChildren();
    for (const preset of state.presets) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'preset-provider-btn';
      if (preset.id === 'openrouter-free') btn.id = 'openrouter-preset-button';
      btn.title = preset.description || '';
      btn.innerHTML = `<strong>${escapeHTML(preset.id === 'openrouter-free' ? 'OpenRouter: buscar modelos GRATIS' : preset.name)}</strong><span>${escapeHTML(preset.tier || 'OpenAI-compatible')}</span>`;
      btn.addEventListener('click', () => addProviderFromPreset(preset));
      container.append(btn);
    }
  }

  function addProviderFromPreset(preset) {
    const id = `prov-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 5)}`;
    const nextPriority = state.config.providers.length + 1;
    const item = {
      id,
      name: preset ? preset.name : `Proveedor #${nextPriority}`,
      baseUrl: preset ? preset.baseUrl : 'https://openrouter.ai/api/v1',
      model: preset ? (preset.defaultModel || '') : '',
      apiKey: '',
      enabled: true,
      priority: nextPriority,
      docsUrl: preset?.docsUrl || ''
    };
    state.config.providers.push(item);
    saveConfig();
    renderProvidersList();
    toast(`Proveedor agregado: ${item.name}. Pega tu API key y pulsa Buscar Modelos o Probar.`);
  }

  function renderProvidersList() {
    const container = $('#providers-list');
    container.replaceChildren();
    if (!state.config.providers.length) {
      container.innerHTML = '<div class="empty-inline">Aún no hay APIs LLM configuradas. Selecciona arriba un proveedor gratuito (como OpenRouter, Groq o Google Gemini) para conectar los 150 agentes.</div>';
      return;
    }

    for (const provider of state.config.providers) {
      const card = document.createElement('article');
      card.className = `provider-card ${provider.enabled === false ? 'is-disabled' : ''}`;
      const listId = `models-${provider.id}`;
      card.innerHTML = `
        <div class="provider-card-head">
          <label class="provider-card-title">
            <input type="checkbox" class="prov-enabled" ${provider.enabled !== false ? 'checked' : ''}>
            <span>${escapeHTML(provider.name || 'Proveedor')}</span>
          </label>
          <div class="inline-actions">
            <span class="badge badge-neutral">Prioridad #${escapeHTML(provider.priority || 1)}</span>
            <button type="button" class="btn btn-ghost btn-xs prov-remove">Eliminar</button>
          </div>
        </div>
        <div class="form-grid-2">
          <div class="field-group">
            <label>Nombre visible</label>
            <input type="text" class="input-field sm prov-name" value="${escapeHTML(provider.name || '')}">
          </div>
          <div class="field-group">
            <label>Prioridad (1 = mayor)</label>
            <input type="number" min="1" max="50" class="input-field sm prov-priority" value="${escapeHTML(provider.priority || 1)}">
          </div>
        </div>
        <div class="field-group">
          <label>URL Base API (OpenAI-compatible)</label>
          <input type="url" class="input-field sm prov-url" value="${escapeHTML(provider.baseUrl || '')}" placeholder="https://openrouter.ai/api/v1">
        </div>
        <div class="field-group">
          <label>API Key ${provider.docsUrl ? `<a href="${escapeHTML(provider.docsUrl)}" target="_blank" rel="noopener" style="margin-left:6px;color:var(--accent);font-weight:500;">Obtener key gratis ↗</a>` : ''}</label>
          <div class="secret-row">
            <input type="password" class="input-field sm prov-key" value="${escapeHTML(provider.apiKey || '')}" placeholder="Opcional para Ollama local · Requerida en APIs cloud" autocomplete="new-password">
            <button type="button" class="btn btn-ghost btn-xs prov-toggle-key">Ver</button>
          </div>
        </div>
        <div class="field-group">
          <label>ID del Modelo</label>
          <div class="inline-form compact">
            <input type="text" class="input-field sm prov-model" list="${listId}" value="${escapeHTML(provider.model || '')}" placeholder="Pulsa Buscar Modelos o escribe ID">
            <datalist id="${listId}"></datalist>
            <button type="button" class="btn btn-secondary btn-xs prov-discover">Buscar Modelos</button>
          </div>
        </div>
        <div class="inline-actions wrap">
          <button type="button" class="btn btn-secondary btn-xs prov-test">Probar Conexión</button>
          <span class="muted-small prov-meta"></span>
        </div>
        <div class="provider-test-msg hidden"></div>
      `;

      const sync = () => {
        provider.enabled = $('.prov-enabled', card).checked;
        provider.name = $('.prov-name', card).value.trim() || 'Proveedor';
        provider.priority = Math.max(1, Number($('.prov-priority', card).value) || 1);
        provider.baseUrl = $('.prov-url', card).value.trim();
        provider.apiKey = $('.prov-key', card).value.trim();
        provider.model = $('.prov-model', card).value.trim();
        card.classList.toggle('is-disabled', !provider.enabled);
        saveConfig();
      };

      ['.prov-enabled', '.prov-name', '.prov-priority', '.prov-url', '.prov-key', '.prov-model'].forEach((s) => {
        $(s, card).addEventListener('change', sync);
        $(s, card).addEventListener('input', sync);
      });

      $('.prov-toggle-key', card).addEventListener('click', (e) => {
        const inp = $('.prov-key', card);
        inp.type = inp.type === 'password' ? 'text' : 'password';
        e.currentTarget.textContent = inp.type === 'password' ? 'Ver' : 'Ocultar';
      });

      $('.prov-remove', card).addEventListener('click', () => {
        state.config.providers = state.config.providers.filter((p) => p.id !== provider.id);
        saveConfig();
        renderProvidersList();
      });

      $('.prov-discover', card).addEventListener('click', async (e) => {
        sync();
        const btn = e.currentTarget;
        const msg = $('.provider-test-msg', card);
        btn.disabled = true;
        btn.textContent = 'Buscando…';
        try {
          const res = await api('/api/providers/models', { method: 'POST', body: { config: provider } });
          const dl = $(`#${listId}`, card);
          dl.replaceChildren();
          for (const m of res.models || []) {
            const opt = document.createElement('option');
            opt.value = m.id;
            opt.label = m.free ? 'GRATIS' : 'Catálogo';
            dl.append(opt);
          }
          if (res.models?.length) {
            const best = res.models.find((m) => m.free) || res.models[0];
            if (!provider.model || !res.models.some((m) => m.id === provider.model)) {
              $('.prov-model', card).value = best.id;
              sync();
            }
          }
          msg.className = 'provider-test-msg ok';
          msg.textContent = `Catálogo cargado: ${res.models.length} modelos (${res.freeCount} marcados GRATIS). Seleccionado: ${provider.model || 'ninguno'}`;
        } catch (err) {
          msg.className = 'provider-test-msg err';
          msg.textContent = err.message;
        } finally {
          btn.disabled = false;
          btn.textContent = 'Buscar Modelos';
        }
      });

      $('.prov-test', card).addEventListener('click', async (e) => {
        sync();
        const btn = e.currentTarget;
        const msg = $('.provider-test-msg', card);
        btn.disabled = true;
        btn.textContent = 'Probando…';
        try {
          const res = await api('/api/providers/test', { method: 'POST', body: { config: provider } });
          msg.className = 'provider-test-msg ok';
          msg.textContent = `Conectado · ${res.model} · ${res.latencyMs || 0} ms · Herramientas: ${res.tools ? 'Nativas OK' : 'Protocolo JSON'}`;
        } catch (err) {
          msg.className = 'provider-test-msg err';
          msg.textContent = err.message;
        } finally {
          btn.disabled = false;
          btn.textContent = 'Probar Conexión';
        }
      });

      container.append(card);
    }
  }

  function renderDivisionMatrix() {
    const container = $('#division-provider-matrix');
    if (!container) return;
    container.replaceChildren();
    for (const div of state.divisions) {
      const row = document.createElement('div');
      row.className = 'division-matrix-row';
      const current = state.config.divisionMap[div.id] || '';
      const opts = ['<option value="">Automático (estrategia global)</option>'];
      for (const p of state.config.providers) {
        opts.push(`<option value="${escapeHTML(p.id)}" ${p.id === current ? 'selected' : ''}>${escapeHTML(p.name)} · ${escapeHTML(p.model || 'sin modelo')}</option>`);
      }
      row.innerHTML = `<span><strong>${escapeHTML(div.shortName)}</strong> (${div.count})</span><select class="select-field compact">${opts.join('')}</select>`;
      $('select', row).addEventListener('change', (e) => {
        if (e.target.value) state.config.divisionMap[div.id] = e.target.value;
        else delete state.config.divisionMap[div.id];
        saveConfig();
      });
      container.append(row);
    }
  }

  function syncSettingsForms() {
    $('#pool-strategy').value = state.config.strategy || 'round-robin';
    $('#pool-concurrency').value = state.config.concurrency || 4;
    $('#pool-max-steps').value = state.config.maxSteps || 18;
    $('#pool-temperature').value = state.config.temperature ?? 0.2;
    $('#pool-tools-enabled').checked = state.config.tools !== false;
    $('#setting-debug-retries').value = state.config.autoDebugRetries ?? 2;
    $('#setting-auto-snapshot').checked = state.config.autoSnapshot !== false;
    $('#setting-theme').value = state.config.theme || 'light';
    $('#setting-font-size').value = state.config.fontSize || 14;
    $('#font-size-value').textContent = `${state.config.fontSize || 14} px`;
    $('#setting-wordwrap').checked = state.config.wordWrap !== false;
    $('#setting-minimap').checked = state.config.minimap === true;
    $('#setting-autosave').checked = state.config.autosave === true;
  }

  /* ===================== 150 AGENTS FLEET VIEW ===================== */

  async function loadAgentsAndDivisions() {
    const res = await api('/api/agents');
    state.agents = res.agents || [];
    state.divisions = res.divisions || [];
    $('#nav-agents-count').textContent = String(state.agents.length);
    renderDivisionPills();
    renderMissionDivisionSelect();
    renderDivisionMatrix();
    renderAgentsGrid();
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

  function renderMissionDivisionSelect() {
    const sel = $('#mission-division-filter');
    const current = sel.value;
    sel.innerHTML = '<option value="">Todas las divisiones relevantes (Automático)</option>' +
      state.divisions.map((d) => `<option value="${escapeHTML(d.id)}">${escapeHTML(d.name)} (${d.count} especialistas)</option>`).join('');
    sel.value = current;
  }

  function renderAgentsGrid() {
    const container = $('#agents-grid');
    const q = state.agentFilter.q.trim().toLowerCase();
    const div = state.agentFilter.division;
    const phase = state.agentFilter.phase;

    const filtered = state.agents.filter((a) => {
      if (div && a.divisionId !== div) return false;
      if (phase && a.phase !== phase) return false;
      if (q) {
        const hay = `${a.id} ${a.name} ${a.divisionName} ${a.specialty} ${a.tier} ${(a.focusTags || []).join(' ')}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });

    $('#agents-filtered-count').textContent = `Mostrando ${filtered.length} de ${state.agents.length}`;
    container.replaceChildren();

    const frag = document.createDocumentFragment();
    for (const a of filtered) {
      const card = document.createElement('article');
      const selected = state.selectedSquad.has(a.id);
      const status = a.runtime?.status || 'idle';
      const statusBadge = status === 'working'
        ? '<span class="badge badge-accent">Trabajando</span>'
        : status === 'queued'
          ? '<span class="badge badge-warning">En cola</span>'
          : '<span class="badge badge-success">Disponible</span>';
      card.className = `agent-card ${selected ? 'is-selected' : ''}`;
      card.innerHTML = `
        <div class="agent-card-top">
          <div class="agent-card-meta">
            <span class="agent-id-code">${escapeHTML(a.id)}</span>
            <span class="badge badge-neutral">${escapeHTML(a.divisionShort)}</span>
          </div>
          ${statusBadge}
        </div>
        <h3>${escapeHTML(a.name)}</h3>
        <p class="agent-card-spec">${escapeHTML(a.specialty)}</p>
        <div class="agent-card-tags">
          <span class="tag-mini">${escapeHTML(a.tier)}</span>
          ${(a.focusTags || []).slice(0, 3).map((t) => `<span class="tag-mini">${escapeHTML(t)}</span>`).join('')}
        </div>
        <div class="agent-card-actions">
          <button type="button" class="btn btn-ghost btn-xs squad-toggle-btn">${selected ? '✓ En Escuadrón' : '+ Escuadrón'}</button>
          <button type="button" class="btn btn-secondary btn-xs chat-open-btn">Abrir Especialista</button>
        </div>
      `;
      $('.squad-toggle-btn', card).addEventListener('click', () => toggleSquadAgent(a.id));
      $('.chat-open-btn', card).addEventListener('click', () => openChatDrawer(a.id));
      frag.append(card);
    }
    container.append(frag);
    updateSquadToolbar();
  }

  function toggleSquadAgent(agentId) {
    if (state.selectedSquad.has(agentId)) state.selectedSquad.delete(agentId);
    else state.selectedSquad.add(agentId);
    renderAgentsGrid();
    updateChatSquadButton(agentId);
  }

  function updateSquadToolbar() {
    const count = state.selectedSquad.size;
    $('#squad-count-label').textContent = String(count);
    $('#clear-squad-btn').classList.toggle('hidden', count === 0);
    $('#dispatch-squad-btn').classList.toggle('hidden', count === 0);
    $('#selected-squad-badge').textContent = count > 0 ? `Escuadrón manual: ${count} especialista(s)` : 'Selección inteligente';
  }

  /* ===================== MISSIONS & LIVE ORCHESTRATION ===================== */

  async function refreshMissions() {
    const res = await api('/api/missions');
    state.missions = res.missions || [];
    $('#kpi-missions').textContent = String(state.missions.length);
    renderMissionsHistory();
  }

  function renderMissionsHistory() {
    const container = $('#missions-history-list');
    container.replaceChildren();
    if (!state.missions.length) {
      container.innerHTML = '<div class="empty-inline">Aún no se han lanzado misiones. Escribe un objetivo arriba o usa una plantilla para desplegar la flota.</div>';
      return;
    }
    for (const m of state.missions) {
      const row = document.createElement('div');
      row.className = 'mission-history-item';
      const badgeCls = m.status === 'completed' ? 'badge-success' : m.status === 'running' ? 'badge-accent' : 'badge-danger';
      row.innerHTML = `
        <div class="mission-history-main">
          <div class="mission-history-title">
            <span class="agent-id-code">${escapeHTML(m.id)}</span>
            <span class="badge ${badgeCls}">${escapeHTML(m.status)}</span>
            <span class="badge badge-neutral">${m.agentCount} especialistas</span>
          </div>
          <div class="mission-history-sub">${escapeHTML(m.objective)}</div>
        </div>
        <div class="muted-small">${m.filesChangedCount} archivo(s) · ${Math.round((m.durationMs || 0) / 1000)}s</div>
      `;
      row.addEventListener('click', () => inspectMission(m.id));
      container.append(row);
    }
  }

  async function launchMission(overrideParams = null) {
    const objective = overrideParams?.objective || $('#mission-objective').value.trim();
    if (!objective) {
      toast('Describe el objetivo técnico de la misión.', 'warning');
      return;
    }
    const mode = overrideParams?.mode || $('#mission-mode').value;
    const scale = overrideParams?.scale || $('#mission-scale').value;
    const divisionVal = overrideParams?.divisionId !== undefined ? overrideParams.divisionId : $('#mission-division-filter').value;

    const payload = {
      objective,
      mode,
      scale,
      pool: buildPoolPayload(),
      concurrency: Number(state.config.concurrency || 4),
      maxStepsPerAgent: Number(state.config.maxSteps || 18),
      autoSnapshot: state.config.autoSnapshot !== false,
      autoDebugRetries: Number(state.config.autoDebugRetries ?? 2)
    };
    if (scale === 'custom' && state.selectedSquad.size) {
      payload.agentIds = [...state.selectedSquad];
    } else if (divisionVal) {
      payload.divisionIds = [divisionVal];
    }

    try {
      $('#launch-mission-btn').disabled = true;
      const res = await api('/api/missions', { method: 'POST', body: payload });
      toast(`Misión ${res.mission.id} desplegada con ${res.mission.agentCount} especialista(s).`);
      await refreshMissions();
      connectMissionStream(res.mission.id);
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      $('#launch-mission-btn').disabled = false;
    }
  }

  async function inspectMission(missionId) {
    try {
      const res = await api(`/api/missions/${encodeURIComponent(missionId)}`);
      renderMissionSnapshot(res.mission);
      if (res.mission.status === 'running') connectMissionStream(missionId);
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  function connectMissionStream(missionId) {
    if (state.missionStream) {
      state.missionStream.close();
      state.missionStream = null;
    }
    state.activeMissionId = missionId;
    $('#mission-monitor-card').classList.remove('hidden');
    $('#cancel-mission-btn').classList.remove('hidden');
    $('#monitor-log-feed').replaceChildren();
    $('#mission-report-box').classList.add('hidden');

    const es = new EventSource(`/api/missions/${encodeURIComponent(missionId)}/stream`);
    state.missionStream = es;
    es.onmessage = (msg) => {
      let ev;
      try { ev = JSON.parse(msg.data); } catch { return; }
      handleMissionEvent(ev);
    };
    es.onerror = () => {
      if (es.readyState === EventSource.CLOSED) {
        $('#cancel-mission-btn').classList.add('hidden');
      }
    };
  }

  let currentMissionData = null;

  function renderMissionSnapshot(mission) {
    currentMissionData = mission;
    state.activeMissionId = mission.id;
    $('#mission-monitor-card').classList.remove('hidden');
    $('#monitor-mission-title').textContent = `Misión ${mission.id}`;
    $('#monitor-mission-objective').textContent = mission.objective;
    updateMonitorStatus(mission.status, mission.engineMode);
    updatePhaseStepper(mission.phase);
    $('#cancel-mission-btn').classList.toggle('hidden', mission.status !== 'running');
    renderDeployedAgents(mission.agents || []);
    renderMissionFiles(mission.filesChanged || []);
    $('#monitor-agents-stat').textContent = `${(mission.agents || []).filter((a) => a.status === 'completed').length}/${(mission.agents || []).length} agentes`;
    $('#monitor-files-stat').textContent = `${(mission.filesChanged || []).length} archivos`;

    const logFeed = $('#monitor-log-feed');
    logFeed.replaceChildren();
    for (const ev of (mission.events || []).slice(-60)) {
      appendMissionLog(ev);
    }
    if (mission.report) {
      $('#mission-report-box').classList.remove('hidden');
      $('#mission-report-content').innerHTML = renderMarkdown(mission.report);
    } else {
      $('#mission-report-box').classList.add('hidden');
    }
  }

  function updateMonitorStatus(status, engineMode) {
    const badge = $('#monitor-status-badge');
    badge.textContent = status === 'running' ? 'En ejecución' : status === 'completed' ? 'Completada' : status === 'cancelled' ? 'Cancelada' : 'Con fallo';
    badge.className = `badge ${status === 'completed' ? 'badge-success' : status === 'running' ? 'badge-accent' : 'badge-danger'}`;
    if (engineMode) {
      $('#monitor-engine-badge').textContent = engineMode === 'llm-pool' ? 'Pool Multi-LLM' : 'Motor Local Real';
    }
  }

  function updatePhaseStepper(phase) {
    const order = ['analyze', 'plan', 'architect', 'implement', 'execute', 'verify-wave', 'verify', 'completed'];
    const mapStep = (p) => {
      if (p === 'analyze') return 0;
      if (p === 'plan' || p === 'architect') return 1;
      if (p === 'implement' || p === 'execute') return 2;
      if (p === 'verify' || p === 'verify-wave') return 3;
      if (p === 'completed') return 4;
      return 0;
    };
    const activeIdx = mapStep(phase);
    $$('.phase-step').forEach((el, idx) => {
      el.classList.toggle('is-done', idx < activeIdx || phase === 'completed');
      el.classList.toggle('is-active', idx === activeIdx && phase !== 'completed');
    });
  }

  function renderDeployedAgents(agents) {
    const list = $('#monitor-agents-list');
    list.replaceChildren();
    let activeCount = 0;
    for (const a of agents.slice(0, 60)) {
      if (a.status === 'working') activeCount++;
      const row = document.createElement('div');
      row.className = 'deployed-agent-row';
      const badgeCls = a.status === 'completed' ? 'badge-success' : a.status === 'working' ? 'badge-accent' : a.status === 'error' ? 'badge-danger' : 'badge-neutral';
      row.innerHTML = `
        <div class="deployed-agent-main">
          <div class="deployed-agent-title"><span class="agent-id-code">${escapeHTML(a.id)}</span><span>${escapeHTML(a.name)}</span></div>
          <div class="deployed-agent-task">${escapeHTML(a.task || a.summary || a.specialty)}</div>
        </div>
        <span class="badge ${badgeCls}">${escapeHTML(a.status)}</span>
      `;
      row.addEventListener('click', () => openChatDrawer(a.id));
      list.append(row);
    }
    if (agents.length > 60) {
      const more = document.createElement('div');
      more.className = 'muted-small';
      more.style.padding = '6px';
      more.textContent = `+ ${agents.length - 60} especialistas adicionales en la flota desplegada.`;
      list.append(more);
    }
    $('#monitor-active-count').textContent = `${activeCount} activos`;
  }

  function renderMissionFiles(files) {
    const wrap = $('#monitor-files-list');
    wrap.replaceChildren();
    for (const fp of files) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'file-pill-btn';
      btn.textContent = fp;
      btn.addEventListener('click', () => { switchView('code'); openFile(fp); });
      wrap.append(btn);
    }
  }

  function appendMissionLog(ev) {
    const feed = $('#monitor-log-feed');
    const relevant = ['phase', 'tool_start', 'tool_end', 'failover', 'file_changed', 'diagnostics', 'notice', 'error'];
    if (!relevant.includes(ev.type)) return;
    const item = document.createElement('div');
    item.className = `log-entry ${ev.type === 'error' ? 'is-error' : ev.type === 'failover' ? 'is-failover' : ''}`;
    let head = ev.type;
    let body = '';
    if (ev.type === 'phase') { head = 'Cambio de Fase'; body = ev.label || ev.phase; }
    else if (ev.type === 'tool_start') { head = `${ev.agentId || 'AG'} → ${ev.name}`; body = JSON.stringify(ev.args || {}).slice(0, 220); }
    else if (ev.type === 'tool_end') { head = `${ev.agentId || 'AG'} ✓ ${ev.name}`; body = String(ev.preview || '').slice(0, 220); }
    else if (ev.type === 'failover') { head = `Failover Automático (${ev.agentId || ''})`; body = `${ev.from?.name} → ${ev.to?.name}\nMotivo: ${ev.reason}`; }
    else if (ev.type === 'file_changed') { head = 'Archivo Modificado'; body = ev.path; }
    else if (ev.type === 'diagnostics') {
      head = `Diagnóstico Real (${ev.stage === 'after' ? 'Post-Ejecución' : 'Inicial'})`;
      body = `Sintaxis: ${ev.syntax?.ok ? 'OK' : `${ev.syntax?.errorCount} error(es)`} | Seguridad: ${ev.security?.score}/100`;
    }
    else if (ev.type === 'notice' || ev.type === 'error') { head = ev.type === 'error' ? 'Error' : 'Aviso'; body = ev.text || ''; }

    item.innerHTML = `<div class="log-entry-head"><span>${escapeHTML(head)}</span></div><div class="log-entry-body">${escapeHTML(body)}</div>`;
    feed.append(item);
    while (feed.children.length > 100) feed.firstElementChild.remove();
    feed.scrollTop = feed.scrollHeight;
  }

  function handleMissionEvent(ev) {
    if (ev.type === 'snapshot' && ev.mission) {
      renderMissionSnapshot(ev.mission);
      return;
    }
    appendMissionLog(ev);
    if (ev.type === 'phase') updatePhaseStepper(ev.phase);
    if (ev.type === 'agent_status' && currentMissionData) {
      const slot = (currentMissionData.agents || []).find((a) => a.id === ev.agentId);
      if (slot) {
        slot.status = ev.status;
        if (ev.task) slot.task = ev.task;
        if (ev.provider) slot.provider = ev.provider;
        renderDeployedAgents(currentMissionData.agents);
        $('#monitor-agents-stat').textContent = `${currentMissionData.agents.filter((a) => a.status === 'completed').length}/${currentMissionData.agents.length} agentes`;
      }
    }
    if (ev.type === 'file_changed' && currentMissionData) {
      if (!currentMissionData.filesChanged.includes(ev.path)) currentMissionData.filesChanged.push(ev.path);
      renderMissionFiles(currentMissionData.filesChanged);
      $('#monitor-files-stat').textContent = `${currentMissionData.filesChanged.length} archivos`;
      refreshTree().catch(() => {});
    }
    if (ev.type === 'report') {
      $('#mission-report-box').classList.remove('hidden');
      $('#mission-report-content').innerHTML = renderMarkdown(ev.report);
    }
    if (ev.type === 'mission_status') {
      updateMonitorStatus(ev.status, ev.engineMode);
      if (['completed', 'failed', 'cancelled'].includes(ev.status)) {
        $('#cancel-mission-btn').classList.add('hidden');
        refreshMissions().catch(() => {});
        refreshTree().catch(() => {});
        refreshProjectHealthKPI().catch(() => {});
        if (state.missionStream) { state.missionStream.close(); state.missionStream = null; }
      }
    }
  }

  /* ===================== DIRECT 1-ON-1 SPECIALIST CHAT ===================== */

  function renderChatTranscript(agentId) {
    const container = $('#agent-transcript');
    container.replaceChildren();
    const entries = state.chatTranscripts.get(agentId) || [];
    if (!entries.length) {
      const agent = state.agents.find((a) => a.id === agentId);
      container.innerHTML = `<div class="empty-inline">Sesión directa con <strong>${escapeHTML(agent?.name || agentId)}</strong>.<br>Este especialista tiene acceso a las 24 herramientas reales del workspace.</div>`;
      return;
    }
    for (const item of entries) {
      const el = document.createElement('div');
      if (item.kind === 'tool') {
        el.className = 'chat-tool-card';
        el.textContent = `${item.name}: ${item.detail}`;
      } else {
        el.className = `chat-bubble ${item.role}`;
        el.innerHTML = `<div class="chat-bubble-role">${item.role === 'user' ? 'Tú' : escapeHTML(item.label || agentId)}</div><div class="markdown-body" style="padding:0;background:transparent">${item.role === 'user' ? escapeHTML(item.text) : renderMarkdown(item.text)}</div>`;
      }
      container.append(el);
    }
    container.scrollTop = container.scrollHeight;
  }

  async function sendSpecialistMessage(text) {
    const msg = String(text || '').trim();
    if (!msg) return;
    if (state.chatBusy) {
      state.chatAbort?.abort();
      return;
    }
    const pool = buildPoolPayload();
    if (!pool.providers.length) {
      toast('Conecta al menos una API LLM gratuita en Herramientas y Config para conversar con los especialistas.', 'warning', 5000);
      openToolsDrawer('providers');
      return;
    }

    const agentId = state.chatAgentId;
    const agentObj = state.agents.find((a) => a.id === agentId);
    const list = state.chatTranscripts.get(agentId) || [];
    state.chatTranscripts.set(agentId, list);
    list.push({ role: 'user', text: msg });
    const assistantEntry = { role: 'assistant', label: `${agentId} · ${agentObj?.name || ''}`, text: 'Procesando solicitud…' };
    list.push(assistantEntry);
    renderChatTranscript(agentId);

    $('#agent-input').value = '';
    state.chatBusy = true;
    const controller = new AbortController();
    state.chatAbort = controller;
    $('#agent-send-button').textContent = 'Detener';

    let accText = '';
    try {
      const response = await fetch(`/api/agents/${encodeURIComponent(agentId)}/chat`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({
          sessionId: state.chatSessionId,
          message: msg,
          pool,
          openFile: state.activePath || '',
          preferredProviderId: state.config.agentMap[agentId] || ''
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
            list.splice(list.length - 1, 0, { kind: 'tool', name: `▶ ${ev.name}`, detail: JSON.stringify(ev.args || {}).slice(0, 180) });
          } else if (ev.type === 'tool_end') {
            list.splice(list.length - 1, 0, { kind: 'tool', name: `✓ ${ev.name}`, detail: String(ev.preview || '').slice(0, 180) });
          } else if (ev.type === 'failover') {
            list.splice(list.length - 1, 0, { kind: 'tool', name: '↻ Failover LLM', detail: `${ev.from?.name} → ${ev.to?.name}` });
          } else if (ev.type === 'assistant_text') {
            accText += (accText ? '\n\n' : '') + (ev.text || '');
            assistantEntry.text = accText;
          } else if (ev.type === 'final') {
            assistantEntry.text = ev.text || accText || 'Completado.';
          } else if (ev.type === 'error') {
            assistantEntry.text = `**Error:** ${ev.text}`;
          } else if (ev.type === 'ui') {
            handleAgentUi(ev.ui).catch(() => {});
          }
          renderChatTranscript(agentId);
        }
      }
    } catch (err) {
      assistantEntry.text = err.name === 'AbortError' ? 'Respuesta detenida.' : `**Error:** ${err.message}`;
      renderChatTranscript(agentId);
    } finally {
      state.chatBusy = false;
      state.chatAbort = null;
      $('#agent-send-button').textContent = 'Enviar';
    }
  }

  async function handleAgentUi(ui) {
    if (!ui) return;
    if (ui.kind === 'file' && ui.path) {
      await refreshTree();
      const existing = state.tabs.get(ui.path);
      if (existing && !existing.dirty) await reloadCleanTab(ui.path);
    } else if (ui.kind === 'tree' || ui.kind === 'move') {
      await refreshTree();
    } else if (ui.kind === 'ran' && ui.run) {
      renderRunOutput(ui.run);
    }
  }

  /* ===================== CODE STUDIO (MONACO + EXPLORER) ===================== */

  function initMonaco() {
    if (!window.require) return;
    const vsPath = '/vendor/monaco/vs';
    let workerUrl = null;
    window.MonacoEnvironment = {
      getWorkerUrl() {
        if (!workerUrl) {
          const script = `self.MonacoEnvironment={baseUrl:location.origin+'/vendor/monaco/'};importScripts('${vsPath}/base/worker/workerMain.js');`;
          workerUrl = URL.createObjectURL(new Blob([script], { type: 'text/javascript' }));
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
        colors: { 'editor.background': '#ffffff', 'editor.lineHighlightBackground': '#f8fafc', 'editorGutter.background': '#ffffff' }
      });
      monaco.editor.defineTheme('strata-dark', {
        base: 'vs-dark', inherit: true, rules: [],
        colors: { 'editor.background': '#151c25', 'editor.lineHighlightBackground': '#1b2430', 'editorGutter.background': '#151c25' }
      });
      state.editor = monaco.editor.create($('#monaco-editor'), {
        theme: state.theme === 'dark' ? 'strata-dark' : 'strata-light',
        language: 'plaintext',
        value: '',
        automaticLayout: true,
        fontSize: state.config.fontSize || 14,
        wordWrap: state.config.wordWrap !== false ? 'on' : 'off',
        minimap: { enabled: state.config.minimap === true },
        scrollBeyondLastLine: false,
        tabSize: 2,
        padding: { top: 10, bottom: 10 }
      });
      state.editor.onDidChangeCursorPosition((ev) => {
        if (!state.activePath) return;
        $('#cursor-status').textContent = `Ln ${ev.position.lineNumber}, Col ${ev.position.column}`;
        $('#cursor-status').classList.remove('hidden');
      });
      state.monacoReady = true;
      if (state.activePath) activateTab(state.activePath);
    });
  }

  async function refreshTree() {
    const res = await api('/api/tree?depth=16');
    state.tree = res.tree || [];
    state.treeLookup.clear();
    let count = 0;
    const walk = (nodes) => {
      for (const n of nodes) {
        state.treeLookup.set(n.path, n);
        if (n.type === 'file') count++;
        if (n.children) walk(n.children);
      }
    };
    walk(state.tree);
    $('#nav-files-count').textContent = String(count);
    $('#tree-empty').classList.toggle('hidden', state.tree.length > 0);
    $('#file-tree').classList.toggle('hidden', state.tree.length === 0);
    renderTree();
  }

  function renderTree() {
    const root = $('#file-tree');
    root.replaceChildren();
    for (const n of state.tree) root.append(buildTreeNode(n, 0));
  }

  function buildTreeNode(node, depth) {
    const wrap = document.createElement('div');
    wrap.className = `tree-node ${node.type === 'dir' && state.expanded.has(node.path) ? 'is-expanded' : ''}`;
    const row = document.createElement('button');
    row.type = 'button';
    row.className = `tree-row ${node.path === state.activePath ? 'is-selected' : ''}`;
    row.style.paddingLeft = `${8 + Math.min(depth * 12, 96)}px`;
    const isDirty = node.type === 'file' && state.tabs.get(node.path)?.dirty;
    row.innerHTML = `${node.type === 'dir' ? '<span class="tree-chevron"><svg viewBox="0 0 24 24"><path d="m9 5 7 7-7 7"/></svg></span>' : '<span class="tree-chevron"></span>'}<span class="tree-name">${escapeHTML(node.name)}</span>${isDirty ? '<span class="tree-dirty-dot"></span>' : ''}`;
    row.addEventListener('click', () => {
      if (node.type === 'dir') {
        state.selectedDir = node.path;
        if (state.expanded.has(node.path)) state.expanded.delete(node.path);
        else state.expanded.add(node.path);
        renderTree();
      } else {
        state.selectedDir = node.path.includes('/') ? node.path.slice(0, node.path.lastIndexOf('/')) : '';
        $('#code-workbench').classList.remove('explorer-open');
        openFile(node.path).catch((err) => toast(err.message, 'error'));
      }
    });
    row.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      showContextMenu(e.clientX, e.clientY, node);
    });
    wrap.append(row);
    if (node.type === 'dir' && state.expanded.has(node.path)) {
      const children = document.createElement('div');
      children.className = 'tree-children';
      for (const ch of node.children || []) children.append(buildTreeNode(ch, depth + 1));
      wrap.append(children);
    }
    return wrap;
  }

  async function openFile(relPath, line) {
    if (!relPath) return;
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
      state.editor.focus();
    }
  }

  function ensureModel(tab) {
    if (!state.monacoReady || tab.model) return;
    const uri = window.monaco.Uri.parse('file:///strata/' + tab.path.split('/').map(encodeURIComponent).join('/'));
    tab.model = window.monaco.editor.createModel(tab.content, tab.language, uri);
    tab.model.onDidChangeContent(() => {
      if (state.activePath !== tab.path) return;
      tab.content = tab.model.getValue();
      tab.dirty = tab.content !== tab.savedContent;
      renderTabs();
      renderTree();
      updateEditorButtons();
    });
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
      ensureModel(tab);
      $('#welcome-view').classList.add('hidden');
      $('#monaco-editor').classList.remove('hidden');
      state.editor.setModel(tab.model);
      state.editor.layout();
    }
    $('#breadcrumbs').innerHTML = `<span>workspace</span> / ${tab.path.split('/').map((p) => `<span>${escapeHTML(p)}</span>`).join(' / ')}`;
    $('#language-status').textContent = tab.language;
    renderTabs();
    renderTree();
    updateEditorButtons();
    setStatus(`Abierto: ${tab.path}`);
  }

  function updateEditorButtons() {
    const tab = state.tabs.get(state.activePath);
    $('#save-button').disabled = !tab?.dirty;
    $('#run-button').disabled = !tab;
    $('#verify-file-button').disabled = !tab;
  }

  function renderTabs() {
    const bar = $('#editor-tabs');
    bar.replaceChildren();
    if (!state.tabs.size) {
      bar.innerHTML = '<div class="tab-empty-hint">Selecciona un archivo del explorador para editar</div>';
      return;
    }
    for (const [p, tab] of state.tabs) {
      const el = document.createElement('div');
      el.className = `editor-tab ${p === state.activePath ? 'is-active' : ''}`;
      el.innerHTML = `<span>${escapeHTML(tab.name)}</span>${tab.dirty ? '<span class="tree-dirty-dot"></span>' : ''}<button type="button" class="tab-close-btn" aria-label="Cerrar"><svg viewBox="0 0 24 24"><path d="m6 6 12 12M18 6 6 18"/></svg></button>`;
      el.addEventListener('click', (e) => {
        if (e.target.closest('.tab-close-btn')) closeTab(p);
        else activateTab(p);
      });
      bar.append(el);
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
    renderTree();
    updateEditorButtons();
    setStatus(`Guardado: ${tab.path}`);
  }

  async function saveActive() {
    const tab = state.tabs.get(state.activePath);
    if (!tab) return;
    try { await saveTab(tab); toast('Archivo guardado.'); }
    catch (err) { toast(err.message, 'error'); }
  }

  async function reloadCleanTab(relPath) {
    const tab = state.tabs.get(relPath);
    if (!tab || tab.dirty) return;
    const fresh = await api(`/api/file?path=${encodeURIComponent(relPath)}`);
    tab.content = fresh.content;
    tab.savedContent = fresh.content;
    tab.version = fresh.version;
    if (tab.model && tab.model.getValue() !== fresh.content) tab.model.setValue(fresh.content);
  }

  async function closeTab(relPath, force = false) {
    const tab = state.tabs.get(relPath);
    if (!tab) return;
    if (tab.dirty && !force && !window.confirm(`«${tab.name}» tiene cambios sin guardar. ¿Cerrar igualmente?`)) return;
    tab.model?.dispose();
    state.tabs.delete(relPath);
    if (state.activePath === relPath) {
      const next = [...state.tabs.keys()].at(-1) || null;
      state.activePath = null;
      if (next) await activateTab(next);
      else {
        $('#welcome-view').classList.remove('hidden');
        $('#monaco-editor').classList.add('hidden');
        $('#breadcrumbs').innerHTML = '<span>workspace</span> / <span class="muted">Sin archivo abierto</span>';
        updateEditorButtons();
      }
    }
    renderTabs();
    renderTree();
  }

  async function verifyActiveFile() {
    const tab = state.tabs.get(state.activePath);
    if (!tab) return;
    if (tab.dirty) await saveTab(tab).catch(() => {});
    try {
      const res = await api('/api/tools/verify-file', { method: 'POST', body: { path: tab.path } });
      const v = res.verification;
      const banner = $('#file-diagnostic-banner');
      banner.classList.remove('hidden', 'is-ok', 'is-error');
      if (v.ok && v.totalIssues === 0) {
        banner.classList.add('is-ok');
        banner.textContent = `✓ ${v.path}: Sintaxis y seguridad verificadas sin problemas.`;
      } else {
        banner.classList.add('is-error');
        const first = v.syntax[0] || v.security[0];
        banner.textContent = `✕ ${v.totalIssues} problema(s) en ${v.path} — Línea ${first?.line || 1}: ${first?.message || first?.title}`;
      }
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  async function runActiveFile() {
    const tab = state.tabs.get(state.activePath);
    if (!tab) return;
    try {
      if (tab.dirty) await saveTab(tab);
      const res = await api('/api/run', { method: 'POST', body: { path: tab.path } });
      renderRunOutput(res.run);
      switchView('runtime');
      switchRuntimeTab('output');
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  /* ===================== TERMINAL, OUTPUT, DIAGNOSTICS & PROCS ===================== */

  function switchRuntimeTab(tab) {
    $$('.runtime-tab').forEach((b) => b.classList.toggle('is-active', b.dataset.rtTab === tab));
    $$('.runtime-pane').forEach((p) => p.classList.toggle('is-active', p.id === `rt-pane-${tab}`));
    if (tab === 'terminal') setTimeout(() => fitActiveTerminal(), 60);
    if (tab === 'processes') refreshProcesses().catch(() => {});
  }

  function renderRunOutput(run) {
    const box = $('#run-output');
    box.replaceChildren();
    const head = document.createElement('div');
    head.className = 'run-header-line';
    head.innerHTML = `<span class="${run.ok ? 'run-ok' : 'run-fail'}">${run.ok ? '✓ EJECUCIÓN CORRECTA' : `✕ FALLO (código ${run.code ?? -1})`}</span><span>${escapeHTML(run.runtime || run.command || 'Proceso')} · ${run.durationMs || 0} ms</span>`;
    box.append(head);
    if (run.stdout) {
      const out = document.createElement('div');
      out.textContent = run.stdout;
      box.append(out);
    }
    if (run.stderr) {
      const err = document.createElement('div');
      err.className = 'run-stderr';
      err.textContent = run.stderr;
      box.append(err);
    }
    if (!run.stdout && !run.stderr) {
      const emp = document.createElement('div');
      emp.textContent = '(Sin salida en stdout/stderr)';
      box.append(emp);
    }
  }

  function createTerminal() {
    if (!window.Terminal || !window.FitAddon?.FitAddon) return;
    const id = ++state.termSeq;
    const terminal = new window.Terminal({
      cursorBlink: true,
      fontSize: 13,
      fontFamily: "'SFMono-Regular', Menlo, Consolas, monospace",
      convertEol: true,
      scrollback: 3000,
      theme: { background: '#111827', foreground: '#e5e7eb', cursor: '#60a5fa' }
    });
    const fit = new window.FitAddon.FitAddon();
    terminal.loadAddon(fit);
    const host = document.createElement('div');
    host.className = 'terminal-host';
    $('#terminal-host-wrap').append(host);
    terminal.open(host);

    const item = { id, name: `Shell ${id}`, terminal, fit, ws: null, host, compat: false };
    state.terminals.push(item);
    state.activeTerminalId = id;
    renderTerminalTabs();
    connectTerminalWs(item);

    terminal.onData((data) => {
      if (item.compat) {
        if (data === '\r' || data === '\n') terminal.write('\r\n');
        else if (data === '\x7f' || data === '\b') terminal.write('\b \b');
        else if (data >= ' ') terminal.write(data);
      }
      if (item.ws?.readyState === WebSocket.OPEN) item.ws.send(JSON.stringify({ type: 'input', data }));
    });
    terminal.onResize(({ cols, rows }) => {
      if (item.ws?.readyState === WebSocket.OPEN) item.ws.send(JSON.stringify({ type: 'resize', cols, rows }));
    });
    setTimeout(() => { try { fit.fit(); } catch {} }, 80);
  }

  function connectTerminalWs(item) {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${proto}//${location.host}/ws/terminal?cwd=`);
    item.ws = ws;
    ws.addEventListener('open', () => {
      $('#terminal-state').textContent = 'Conectada';
      $('#term-conn-dot').classList.add('is-on');
      try { item.fit.fit(); } catch {}
    });
    ws.addEventListener('message', (e) => {
      const write = (t) => {
        if (t.includes('modo compatible sin PTY')) item.compat = true;
        item.terminal.write(t);
      };
      if (typeof e.data === 'string') write(e.data);
      else if (e.data instanceof Blob) e.data.text().then(write);
    });
    ws.addEventListener('close', () => {
      $('#terminal-state').textContent = 'Desconectada';
      $('#term-conn-dot').classList.remove('is-on');
    });
  }

  function renderTerminalTabs() {
    const bar = $('#terminal-tabs');
    bar.replaceChildren();
    for (const t of state.terminals) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = `terminal-tab-pill ${t.id === state.activeTerminalId ? 'is-active' : ''}`;
      btn.textContent = t.name;
      btn.addEventListener('click', () => {
        state.activeTerminalId = t.id;
        renderTerminalTabs();
        fitActiveTerminal();
      });
      bar.append(btn);
      t.host.classList.toggle('hidden', t.id !== state.activeTerminalId);
    }
  }

  function fitActiveTerminal() {
    const item = state.terminals.find((t) => t.id === state.activeTerminalId);
    if (!item) return;
    try { item.fit.fit(); } catch {}
  }

  function sendToActiveTerminal(data) {
    const item = state.terminals.find((t) => t.id === state.activeTerminalId);
    if (!item || item.ws?.readyState !== WebSocket.OPEN) {
      toast('La terminal no está conectada.', 'warning');
      return;
    }
    if (item.compat && data !== '\x03') item.terminal.write(data.replace(/\n/g, '\r\n'));
    item.ws.send(JSON.stringify({ type: 'input', data }));
  }

  /* ===================== DIAGNOSTICS & TOOLS ACTIONS ===================== */

  async function runSyntaxCheckAll(jumpToRuntime = false) {
    try {
      const res = await api('/api/tools/verify-syntax', { method: 'POST', body: {} });
      state.lastSyntax = res.verification;
      renderDiagnosticsTable();
      toast(res.verification.ok
        ? `Sintaxis verificada: ${res.verification.checkedFiles} archivo(s), 0 errores.`
        : `Se detectaron ${res.verification.errorCount} error(es) de sintaxis.`,
        res.verification.ok ? 'success' : 'warning');
      if (jumpToRuntime) { switchView('runtime'); switchRuntimeTab('diagnostics'); closeToolsDrawer(); }
      await refreshProjectHealthKPI();
      return res.verification;
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  async function runSecurityAuditAll(jumpToRuntime = false) {
    try {
      const res = await api('/api/tools/security-audit', { method: 'POST', body: {} });
      state.lastSecurity = res.audit;
      renderDiagnosticsTable();
      toast(`Auditoría OWASP: puntuación ${res.audit.score}/100 (${res.audit.findings.length} hallazgos).`, res.audit.score >= 85 ? 'success' : 'warning');
      if (jumpToRuntime) { switchView('runtime'); switchRuntimeTab('diagnostics'); closeToolsDrawer(); }
      await refreshProjectHealthKPI();
      return res.audit;
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  function renderDiagnosticsTable() {
    const container = $('#diagnostics-list');
    container.replaceChildren();
    const combined = [];
    for (const d of (state.lastSyntax?.diagnostics || [])) {
      combined.push({ kind: 'Sintaxis', severity: d.severity || 'error', file: d.file, line: d.line || 1, title: `[${d.runtime}] ${d.message}` });
    }
    for (const f of (state.lastSecurity?.findings || [])) {
      combined.push({ kind: 'Seguridad', severity: f.severity, file: f.file, line: f.line || 1, title: `[${f.id}] ${f.title} — ${f.snippet || ''}` });
    }
    state.lastDiagnostics = combined;
    $('#diag-count-badge').textContent = String(combined.length);
    const sText = state.lastSyntax ? `Sintaxis: ${state.lastSyntax.errorCount} error(es) en ${state.lastSyntax.checkedFiles} archivo(s)` : 'Sintaxis pendiente';
    const secText = state.lastSecurity ? `Seguridad: ${state.lastSecurity.score}/100` : 'Seguridad pendiente';
    $('#diagnostics-summary-line').textContent = `${sText} · ${secText}`;

    if (!combined.length) {
      container.innerHTML = '<div class="empty-inline">✓ No se detectaron errores de sintaxis ni vulnerabilidades en los análisis realizados.</div>';
      return;
    }
    for (const item of combined.slice(0, 200)) {
      const row = document.createElement('div');
      row.className = 'diag-row';
      const badgeCls = ['error', 'critical', 'high'].includes(item.severity) ? 'badge-danger' : item.severity === 'medium' || item.severity === 'warning' ? 'badge-warning' : 'badge-neutral';
      row.innerHTML = `
        <div class="diag-main">
          <div class="diag-loc">${escapeHTML(item.file)}:${item.line}</div>
          <div class="diag-msg">${escapeHTML(item.title)}</div>
        </div>
        <div class="inline-actions">
          <span class="badge badge-neutral">${escapeHTML(item.kind)}</span>
          <span class="badge ${badgeCls}">${escapeHTML(item.severity)}</span>
        </div>
      `;
      row.addEventListener('click', () => { switchView('code'); openFile(item.file, item.line); });
      container.append(row);
    }
  }

  async function refreshProjectHealthKPI() {
    try {
      const [cartoRes, synRes, secRes] = await Promise.all([
        api('/api/tools/cartography'),
        api('/api/tools/verify-syntax', { method: 'POST', body: {} }),
        api('/api/tools/security-audit', { method: 'POST', body: {} })
      ]);
      state.lastSyntax = synRes.verification;
      state.lastSecurity = secRes.audit;
      renderDiagnosticsTable();
      const c = cartoRes.cartography;
      $('#kpi-health').textContent = synRes.verification.ok ? `${secRes.audit.score}/100` : `${synRes.verification.errorCount} err`;
      $('#kpi-health-sub').textContent = `${c.totalFiles} arch · ${c.totalLines} líneas · Seg ${secRes.audit.score}/100`;
    } catch { /* ignore */ }
  }

  async function refreshProcesses() {
    const res = await api('/api/processes');
    const container = $('#processes-list');
    container.replaceChildren();
    const list = res.processes || [];
    if (!list.length) {
      container.innerHTML = '<div class="empty-inline">No hay procesos en segundo plano ejecutándose.</div>';
      return;
    }
    for (const p of list) {
      const card = document.createElement('div');
      card.className = 'proc-card';
      card.innerHTML = `
        <div class="proc-head">
          <div><strong>${escapeHTML(p.name)}</strong> <span class="muted-small mono">${escapeHTML(p.command)}</span></div>
          <div class="inline-actions">
            <span class="badge ${p.status === 'running' ? 'badge-success' : 'badge-neutral'}">${escapeHTML(p.status)}</span>
            ${p.status === 'running' ? '<button type="button" class="btn btn-danger btn-xs stop-proc-btn">Detener</button>' : ''}
          </div>
        </div>
        <div class="proc-logs">${escapeHTML(p.logTail || '(sin salida aún)')}</div>
      `;
      $('.stop-proc-btn', card)?.addEventListener('click', async () => {
        await api(`/api/processes/${encodeURIComponent(p.id)}/stop`, { method: 'POST' });
        refreshProcesses();
      });
      container.append(card);
    }
  }

  /* ===================== GIT, SNAPSHOTS, SEARCH, UPLOAD ===================== */

  async function refreshGitStatus() {
    const box = $('#git-status-box');
    const res = await api('/api/git/status');
    const g = res.git;
    if (!g.initialized) {
      box.textContent = g.summary;
      return;
    }
    const list = g.files.length ? g.files.map((f) => `${f.status.padEnd(3)} ${f.path}`).join('\n') : 'Working tree limpio (sin cambios pendientes).';
    box.textContent = `Rama: ${g.branch}\n\n${list}`;
  }

  async function refreshSnapshots() {
    const res = await api('/api/snapshots');
    const list = $('#snapshot-list');
    list.replaceChildren();
    if (!res.snapshots?.length) {
      list.innerHTML = '<div class="empty-inline">Sin snapshots guardados.</div>';
      return;
    }
    for (const id of res.snapshots) {
      const card = document.createElement('div');
      card.className = 'snapshot-card';
      card.innerHTML = `<strong>${escapeHTML(id)}</strong><button type="button" class="btn btn-secondary btn-xs">Restaurar</button>`;
      $('button', card).addEventListener('click', async () => {
        if (!window.confirm(`¿Restaurar el workspace desde ${id}? Se guardará una copia de seguridad previa.`)) return;
        const r = await api('/api/restore', { method: 'POST', body: { id } });
        toast(`Restaurado. Copia previa: ${r.safetySnapshot}`);
        await refreshTree();
        await refreshSnapshots();
      });
      list.append(card);
    }
  }

  async function runCodeSearch() {
    const q = $('#search-query').value.trim();
    if (!q) return;
    const params = new URLSearchParams({ q, max: '300' });
    if ($('#search-case').checked) params.set('case', '1');
    if ($('#search-regex').checked) params.set('regex', '1');
    if ($('#search-glob').value.trim()) params.set('glob', $('#search-glob').value.trim());
    try {
      const res = await api('/api/search?' + params.toString());
      $('#search-summary').textContent = `${res.results.length} resultado(s) (${res.engine || 'node'})`;
      const container = $('#search-results');
      container.replaceChildren();
      for (const hit of res.results.slice(0, 150)) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'search-hit';
        btn.innerHTML = `<strong>${escapeHTML(hit.file)}:${hit.line}</strong><span>${escapeHTML(hit.text)}</span>`;
        btn.addEventListener('click', () => {
          $('#code-workbench').classList.remove('explorer-open');
          openFile(hit.file, hit.line);
        });
        container.append(btn);
      }
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  function promptUser(opts) {
    $('#prompt-eyebrow').textContent = opts.eyebrow || 'WORKSPACE';
    $('#prompt-title').textContent = opts.title || 'Nombre';
    $('#prompt-description').textContent = opts.description || '';
    $('#prompt-label').textContent = opts.label || 'Ruta';
    $('#prompt-input').value = opts.value || '';
    $('#prompt-input').placeholder = opts.placeholder || '';
    $('#prompt-submit').textContent = opts.submitText || 'Aceptar';
    const dlg = $('#prompt-dialog');
    if (!dlg.open) dlg.showModal();
    requestAnimationFrame(() => $('#prompt-input').focus());
    return new Promise((resolve) => { state.promptResolve = resolve; });
  }

  async function createFileOrDir(isDir, parent = state.selectedDir) {
    const val = await promptUser({
      eyebrow: isDir ? 'NUEVA CARPETA' : 'NUEVO ARCHIVO',
      title: isDir ? 'Crear carpeta' : 'Crear archivo',
      description: `Se creará en ${parent || 'la raíz del workspace'}.`,
      value: parent ? `${parent}/` : '',
      placeholder: isDir ? 'src/modules' : 'src/index.js',
      submitText: 'Crear'
    });
    if (!val || !val.trim()) return;
    const clean = val.trim().replace(/^\/+|\/+$/g, '');
    try {
      if (isDir) {
        await api('/api/mkdir', { method: 'POST', body: { path: clean } });
        toast(`Carpeta creada: ${clean}`);
      } else {
        await api('/api/file', { method: 'POST', body: { path: clean, content: '' } });
        toast(`Archivo creado: ${clean}`);
        await refreshTree();
        await openFile(clean);
        return;
      }
      await refreshTree();
    } catch (err) { toast(err.message, 'error'); }
  }

  function showContextMenu(x, y, node) {
    const menu = $('#context-menu');
    menu.replaceChildren();
    const add = (label, fn, danger = false) => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = `context-item ${danger ? 'danger' : ''}`;
      btn.textContent = label;
      btn.addEventListener('click', () => { menu.classList.add('hidden'); fn(); });
      menu.append(btn);
    };
    if (node.type === 'file') add('Abrir', () => openFile(node.path));
    add('Nuevo archivo aquí', () => createFileOrDir(false, node.type === 'dir' ? node.path : ''));
    add('Nueva carpeta aquí', () => createFileOrDir(true, node.type === 'dir' ? node.path : ''));
    add('Renombrar / Mover', async () => {
      const to = await promptUser({ title: 'Mover o renombrar', value: node.path, submitText: 'Mover' });
      if (!to || to.trim() === node.path) return;
      await api('/api/move', { method: 'POST', body: { from: node.path, to: to.trim() } });
      await refreshTree();
      toast('Elemento movido.');
    });
    add('Eliminar', async () => {
      if (!window.confirm(`¿Eliminar «${node.path}»?`)) return;
      await api('/api/delete', { method: 'POST', body: { path: node.path } });
      if (state.tabs.has(node.path)) await closeTab(node.path, true);
      await refreshTree();
      toast('Elemento eliminado.');
    }, true);
    menu.classList.remove('hidden');
    menu.style.left = `${Math.min(x, window.innerWidth - 190)}px`;
    menu.style.top = `${Math.min(y, window.innerHeight - 200)}px`;
  }

  function openQuickOpen() {
    const allFiles = [];
    const collect = (nodes) => {
      for (const n of nodes) {
        if (n.type === 'file') allFiles.push(n.path);
        else collect(n.children || []);
      }
    };
    collect(state.tree);
    state.quickFiles = allFiles;
    renderQuickOpen('');
    const dlg = $('#quick-open-dialog');
    if (!dlg.open) dlg.showModal();
    requestAnimationFrame(() => $('#quick-open-input').focus());
  }

  function renderQuickOpen(query) {
    const q = String(query || '').toLowerCase();
    const list = $('#quick-open-results');
    list.replaceChildren();
    const hits = state.quickFiles.filter((p) => !q || p.toLowerCase().includes(q)).slice(0, 80);
    if (!hits.length) {
      list.innerHTML = '<div class="empty-inline">Sin archivos coincidentes.</div>';
      return;
    }
    for (const p of hits) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'quick-open-item';
      btn.innerHTML = `<strong>${escapeHTML(p.split('/').pop())}</strong><small>${escapeHTML(p)}</small>`;
      btn.addEventListener('click', () => {
        $('#quick-open-dialog').close();
        openFile(p);
      });
      list.append(btn);
    }
  }

  async function handleFileUpload(fileList) {
    const files = [...fileList];
    if (!files.length) return;
    const payload = [];
    for (const f of files.slice(0, 60)) {
      if (f.size > 4 * 1024 * 1024) { toast(`Archivo omitido (>4MB): ${f.name}`, 'warning'); continue; }
      const content = await f.text();
      payload.push({ path: f.webkitRelativePath || f.name, content });
    }
    if (!payload.length) return;
    try {
      const res = await api('/api/project/import', { method: 'POST', body: { files: payload } });
      toast(`Importados ${res.count} archivo(s) al workspace.`);
      await refreshTree();
      await refreshProjectHealthKPI();
    } catch (err) { toast(err.message, 'error'); }
  }

  /* ===================== GLOBAL SSE TELEMETRY ===================== */

  function connectGlobalStream() {
    if (state.globalStream) state.globalStream.close();
    const es = new EventSource('/api/events');
    state.globalStream = es;
    es.onmessage = (msg) => {
      let ev;
      try { ev = JSON.parse(msg.data); } catch { return; }
      if (ev.type === 'fleet_stats' && ev.stats) {
        updateFleetKPIs(ev.stats);
      } else if (ev.type === 'agent_status') {
        const a = state.agents.find((x) => x.id === ev.agentId);
        if (a && a.runtime) {
          a.runtime.status = ev.status;
          a.runtime.currentTask = ev.task || '';
        }
        api('/api/fleet/stats').then((r) => updateFleetKPIs(r.stats)).catch(() => {});
      }
    };
  }

  function updateFleetKPIs(stats) {
    if (!stats) return;
    $('#kpi-fleet').textContent = String(stats.totalAgents || 150);
    $('#kpi-fleet-sub').textContent = `${stats.totalDivisions || 15} divisiones · ${stats.activeAgents || 0} activos ahora`;
    $('#fleet-header-stat').textContent = stats.activeAgents > 0
      ? `${stats.activeAgents} de ${stats.totalAgents} especialistas trabajando`
      : `${stats.totalAgents} especialistas listos`;
  }

  /* ===================== EVENT BINDINGS ===================== */

  function bindEvents() {
    // Navigation
    $$('[data-nav]').forEach((btn) => btn.addEventListener('click', () => switchView(btn.dataset.nav)));
    $$('[data-nav-jump]').forEach((el) => el.addEventListener('click', () => switchView(el.dataset.navJump)));
    $$('[data-open-drawer]').forEach((el) => el.addEventListener('click', () => openToolsDrawer(el.dataset.openDrawer)));
    $('#open-tools-drawer-btn').addEventListener('click', () => openToolsDrawer('providers'));
    $('#mobile-drawer-btn').addEventListener('click', () => openToolsDrawer('providers'));
    $('#pool-status-pill').addEventListener('click', () => openToolsDrawer('providers'));
    $('#close-tools-drawer-btn').addEventListener('click', closeToolsDrawer);
    $('#close-chat-drawer-btn').addEventListener('click', closeChatDrawer);
    $('#drawer-backdrop').addEventListener('click', () => { closeToolsDrawer(); closeChatDrawer(); });
    $$('.drawer-tab').forEach((b) => b.addEventListener('click', () => switchDrawerTab(b.dataset.drawerTab)));
    $('#rail-direct-agent-btn').addEventListener('click', () => openChatDrawer('AG-141'));

    $('#theme-toggle-btn').addEventListener('click', () => {
      applyTheme(state.theme === 'dark' ? 'light' : 'dark');
      saveConfig();
    });

    // Mission Command Center
    $$('[data-mission-preset]').forEach((chip) => {
      chip.addEventListener('click', () => {
        $('#mission-objective').value = chip.dataset.missionPreset;
        $('#mission-objective').focus();
      });
    });
    $('#mission-form').addEventListener('submit', (e) => { e.preventDefault(); launchMission(); });
    $('#quick-audit-btn').addEventListener('click', () => {
      launchMission({
        objective: 'Auditoría integral del workspace: cartografía, verificación de sintaxis multi-lenguaje, auditoría de seguridad OWASP, pruebas automatizadas y corrección de fallos detectados.',
        mode: 'audit-and-heal',
        scale: 'standard'
      });
    });
    $('#cancel-mission-btn').addEventListener('click', async () => {
      if (!state.activeMissionId) return;
      await api(`/api/missions/${encodeURIComponent(state.activeMissionId)}/cancel`, { method: 'POST' });
      toast('Misión detenida.', 'warning');
    });
    $('#refresh-missions-btn').addEventListener('click', () => refreshMissions());

    // 150 Agents Directory
    $('#agent-search-input').addEventListener('input', (e) => { state.agentFilter.q = e.target.value; renderAgentsGrid(); });
    $('#agent-phase-filter').addEventListener('change', (e) => { state.agentFilter.phase = e.target.value; renderAgentsGrid(); });
    $('#clear-squad-btn').addEventListener('click', () => { state.selectedSquad.clear(); renderAgentsGrid(); });
    $('#dispatch-squad-btn').addEventListener('click', () => {
      $('#mission-scale').value = 'custom';
      switchView('missions');
      $('#mission-objective').focus();
      toast(`Escuadrón de ${state.selectedSquad.size} especialistas listo en el Centro de Misiones.`);
    });

    // Direct Specialist Chat
    $('#agent-chat-form').addEventListener('submit', (e) => { e.preventDefault(); sendSpecialistMessage($('#agent-input').value); });
    $('#agent-input').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); sendSpecialistMessage($('#agent-input').value); }
    });
    $('#chat-reset-btn').addEventListener('click', async () => {
      await api(`/api/agents/${encodeURIComponent(state.chatAgentId)}/reset`, { method: 'POST', body: { sessionId: state.chatSessionId } }).catch(() => {});
      state.chatTranscripts.delete(state.chatAgentId);
      renderChatTranscript(state.chatAgentId);
      toast('Conversación reiniciada.');
    });
    $('#chat-agent-provider-pin').addEventListener('change', (e) => {
      if (e.target.value) state.config.agentMap[state.chatAgentId] = e.target.value;
      else delete state.config.agentMap[state.chatAgentId];
      saveConfig();
      toast('Preferencia de modelo guardada para este especialista.');
    });
    $('#chat-add-squad-btn').addEventListener('click', () => toggleSquadAgent(state.chatAgentId));

    // Provider Pool & Quick Discovery
    $('#add-custom-provider-btn').addEventListener('click', () => addProviderFromPreset(null));
    $('#test-all-providers-btn').addEventListener('click', async (e) => {
      const active = state.config.providers.filter((p) => p.enabled !== false);
      if (!active.length) return toast('Agrega al menos un proveedor primero.', 'warning');
      const btn = e.currentTarget;
      btn.disabled = true;
      btn.textContent = 'Probando pool…';
      try {
        const res = await api('/api/providers/test-pool', { method: 'POST', body: { providers: active } });
        toast(`Pool verificado: ${res.healthy} de ${res.total} proveedores operativos.`, res.healthy === res.total ? 'success' : 'warning', 5000);
      } catch (err) { toast(err.message, 'error'); }
      finally { btn.disabled = false; btn.textContent = 'Probar Todo el Pool'; }
    });
    $('#save-pool-config-btn').addEventListener('click', () => {
      state.config.strategy = $('#pool-strategy').value;
      state.config.concurrency = Math.min(16, Math.max(1, Number($('#pool-concurrency').value) || 4));
      state.config.maxSteps = Math.min(60, Math.max(2, Number($('#pool-max-steps').value) || 18));
      state.config.temperature = Math.min(2, Math.max(0, Number($('#pool-temperature').value) || 0.2));
      state.config.tools = $('#pool-tools-enabled').checked;
      saveConfig();
      toast('Configuración del Pool Multi-LLM guardada.');
    });
    $('#toggle-api-key').addEventListener('click', (e) => {
      const inp = $('#setting-api-key');
      inp.type = inp.type === 'password' ? 'text' : 'password';
      e.currentTarget.textContent = inp.type === 'password' ? 'Mostrar' : 'Ocultar';
    });
    $('#discover-models-button').addEventListener('click', async (e) => {
      const btn = e.currentTarget;
      btn.disabled = true;
      btn.textContent = 'Buscando…';
      try {
        const res = await api('/api/agent/models', {
          method: 'POST',
          body: { config: { baseUrl: $('#setting-base-url').value.trim(), apiKey: $('#setting-api-key').value.trim() } }
        });
        const dl = $('#setting-model-options');
        dl.replaceChildren();
        for (const m of res.models || []) {
          const opt = document.createElement('option');
          opt.value = m.id;
          opt.label = m.free ? 'GRATIS' : 'Catálogo';
          dl.append(opt);
        }
        if (res.models?.length) {
          const choice = res.models.find((m) => m.free) || res.models[0];
          $('#setting-model').value = choice.id;
        }
        $('#model-discovery-note').textContent = `Encontrados ${res.models.length} modelos (${res.freeCount} marcados GRATIS).`;
      } catch (err) {
        $('#model-discovery-note').textContent = err.message;
      } finally {
        btn.disabled = false;
        btn.textContent = 'Buscar modelos';
      }
    });
    $('#quick-test-single-btn').addEventListener('click', async () => {
      const banner = $('#agent-test-result');
      banner.className = 'test-banner';
      banner.textContent = 'Probando conexión…';
      try {
        const res = await api('/api/agent/test', {
          method: 'POST',
          body: { config: { baseUrl: $('#setting-base-url').value.trim(), apiKey: $('#setting-api-key').value.trim(), model: $('#setting-model').value.trim() } }
        });
        banner.className = 'test-banner success';
        banner.textContent = `✓ Conexión correcta · ${res.model} · Herramientas: ${res.tools ? 'disponibles' : 'JSON fallback'}`;
      } catch (err) {
        banner.className = 'test-banner error';
        banner.textContent = err.message;
      }
    });
    $('#quick-add-discovered-btn').addEventListener('click', () => {
      const model = $('#setting-model').value.trim();
      const baseUrl = $('#setting-base-url').value.trim() || 'https://openrouter.ai/api/v1';
      if (!model) return toast('Indica o descubre un modelo primero.', 'warning');
      state.config.providers.push({
        id: `prov-${Date.now().toString(36)}`,
        name: model.split('/').pop(),
        baseUrl,
        apiKey: $('#setting-api-key').value.trim(),
        model,
        enabled: true,
        priority: state.config.providers.length + 1
      });
      saveConfig();
      renderProvidersList();
      toast(`Añadido ${model} al Pool Multi-LLM.`);
    });

    // Engineering Toolbox Actions
    $('#tool-verify-syntax').addEventListener('click', () => runSyntaxCheckAll(true));
    $('#tool-security-audit').addEventListener('click', () => runSecurityAuditAll(true));
    $('#tool-cartography').addEventListener('click', async () => {
      const res = await api('/api/tools/cartography');
      const box = $('#tool-output-preview');
      box.classList.remove('hidden');
      box.textContent = JSON.stringify(res.cartography, null, 2);
      toast('Cartografía completada.');
    });
    $('#tool-auto-repair').addEventListener('click', async () => {
      const res = await api('/api/tools/auto-repair', { method: 'POST', body: {} });
      const box = $('#tool-output-preview');
      box.classList.remove('hidden');
      box.textContent = JSON.stringify(res.repair, null, 2);
      await refreshTree();
      toast(`Auto-reparación: ${res.repair.repaired.length} archivo(s) ajustados.`);
    });
    $('#tool-run-tests').addEventListener('click', async () => {
      const res = await api('/api/tools/run-tests', { method: 'POST', body: {} });
      renderRunOutput(res.run);
      closeToolsDrawer();
      switchView('runtime');
      switchRuntimeTab('output');
    });
    $('#tool-list-runtimes').addEventListener('click', async () => {
      const res = await api('/api/runtimes');
      const box = $('#tool-output-preview');
      box.classList.remove('hidden');
      const avail = res.runtimes.filter((r) => r.available).map((r) => `✓ ${r.label} (${r.bin})`);
      const tools = res.tools.filter((t) => t.available).map((t) => `✓ ${t.name}`);
      box.textContent = `Runtimes activos:\n${avail.join('\n')}\n\nHerramientas CLI:\n${tools.join('\n')}`;
    });
    $('#quick-exec-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const cmd = $('#quick-exec-input').value.trim();
      if (!cmd) return;
      try {
        const res = await api('/api/exec', { method: 'POST', body: { command: cmd } });
        renderRunOutput(res.run);
        closeToolsDrawer();
        switchView('runtime');
        switchRuntimeTab('output');
      } catch (err) { toast(err.message, 'error'); }
    });
    $('#scaffold-create-btn').addEventListener('click', async () => {
      try {
        const res = await api('/api/project/scaffold', {
          method: 'POST',
          body: { template: $('#scaffold-template').value, targetDir: $('#scaffold-dir').value.trim() }
        });
        toast(`Proyecto creado: ${res.label} (${res.files.length} archivos).`);
        await refreshTree();
        await refreshProjectHealthKPI();
        closeToolsDrawer();
        switchView('code');
        if (res.files[0]) openFile(res.files[0]);
      } catch (err) { toast(err.message, 'error'); }
    });
    $('#drawer-upload-files-btn').addEventListener('click', () => $('#hidden-file-upload').click());
    $('#upload-files-trigger').addEventListener('click', () => $('#hidden-file-upload').click());
    $('#hidden-file-upload').addEventListener('change', (e) => {
      handleFileUpload(e.target.files);
      e.target.value = '';
    });
    $('#drawer-export-bundle-btn').addEventListener('click', async () => {
      const res = await api('/api/project/export');
      const blob = new Blob([JSON.stringify(res.bundle, null, 2)], { type: 'application/json' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = `strata-workspace-${Date.now()}.json`;
      a.click();
      toast(`Workspace exportado (${res.bundle.fileCount} archivos).`);
    });
    $('#drawer-create-snapshot-btn').addEventListener('click', async () => {
      const r = await api('/api/snapshot', { method: 'POST', body: { label: 'manual' } });
      toast(`Snapshot creado: ${r.id}`);
      refreshSnapshots().catch(() => {});
    });

    // Git Suite
    $('#git-clone-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const btn = $('#git-clone-btn');
      btn.disabled = true;
      btn.textContent = 'Clonando…';
      try {
        const res = await api('/api/git/clone', {
          method: 'POST',
          body: { url: $('#git-clone-url').value.trim(), targetDir: $('#git-clone-dir').value.trim() }
        });
        if (!res.ok) throw new Error(res.stderr || 'No se pudo clonar el repositorio.');
        toast(`Repositorio clonado en ${res.targetDir}`);
        await refreshTree();
        await refreshProjectHealthKPI();
        await refreshGitStatus();
      } catch (err) { toast(err.message, 'error', 5000); }
      finally { btn.disabled = false; btn.textContent = 'Clonar Repo'; }
    });
    $('#git-refresh-btn').addEventListener('click', () => refreshGitStatus());
    $('#git-init-btn').addEventListener('click', async () => {
      await api('/api/git/init', { method: 'POST', body: {} });
      toast('Repositorio Git inicializado.');
      refreshGitStatus();
    });
    $('#git-diff-btn').addEventListener('click', async () => {
      const res = await api('/api/git/diff');
      $('#git-status-box').textContent = res.diff;
    });
    $('#git-log-btn').addEventListener('click', async () => {
      const res = await api('/api/git/log');
      $('#git-status-box').textContent = res.commits?.length
        ? res.commits.map((c) => `${c.hash}  ${c.date}  ${c.message}`).join('\n')
        : (res.error || 'Sin commits.');
    });
    $('#git-commit-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const msg = $('#git-commit-msg').value.trim();
      if (!msg) return;
      try {
        const res = await api('/api/git/commit', { method: 'POST', body: { message: msg } });
        if (!res.ok) throw new Error(res.stderr || 'No se pudo crear el commit.');
        $('#git-commit-msg').value = '';
        toast('Commit creado correctamente.');
        refreshGitStatus();
      } catch (err) { toast(err.message, 'error'); }
    });

    // General Settings
    $('#setting-font-size').addEventListener('input', (e) => { $('#font-size-value').textContent = `${e.target.value} px`; });
    $('#save-general-settings-btn').addEventListener('click', () => {
      state.config.autoDebugRetries = Math.min(5, Math.max(0, Number($('#setting-debug-retries').value) || 0));
      state.config.autoSnapshot = $('#setting-auto-snapshot').checked;
      state.config.fontSize = Number($('#setting-font-size').value) || 14;
      state.config.wordWrap = $('#setting-wordwrap').checked;
      state.config.minimap = $('#setting-minimap').checked;
      state.config.autosave = $('#setting-autosave').checked;
      applyTheme($('#setting-theme').value);
      saveConfig();
      if (state.editor) {
        state.editor.updateOptions({
          fontSize: state.config.fontSize,
          wordWrap: state.config.wordWrap ? 'on' : 'off',
          minimap: { enabled: state.config.minimap }
        });
      }
      toast('Preferencias guardadas.');
    });
    $('#export-config-btn').addEventListener('click', () => {
      const sanitized = {
        ...state.config,
        providers: state.config.providers.map((p) => ({ ...p, apiKey: '' }))
      };
      const blob = new Blob([JSON.stringify(sanitized, null, 2)], { type: 'application/json' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = 'strata-engineering-config.json';
      a.click();
      toast('Configuración exportada (sin claves API).');
    });
    $('#import-config-btn').addEventListener('click', () => $('#import-config-file').click());
    $('#import-config-file').addEventListener('change', async (e) => {
      const file = e.target.files?.[0];
      if (!file) return;
      try {
        const parsed = JSON.parse(await file.text());
        state.config = { ...state.config, ...parsed };
        saveConfig();
        syncSettingsForms();
        renderProvidersList();
        applyTheme(state.config.theme);
        toast('Configuración importada.');
      } catch { toast('Archivo JSON inválido.', 'error'); }
      e.target.value = '';
    });

    // Code Studio Events
    $$('[data-code-tab]').forEach((b) => b.addEventListener('click', () => {
      const t = b.dataset.codeTab;
      $$('[data-code-tab]').forEach((x) => x.classList.toggle('is-active', x.dataset.codeTab === t));
      $$('.code-sidebar-pane').forEach((p) => p.classList.toggle('is-active', p.id === `code-pane-${t}`));
      if (t === 'snapshots') refreshSnapshots();
    }));
    $('#toggle-explorer-btn').addEventListener('click', () => $('#code-workbench').classList.toggle('explorer-open'));
    $('#close-explorer-btn').addEventListener('click', () => $('#code-workbench').classList.remove('explorer-open'));
    $('#new-file-button').addEventListener('click', () => createFileOrDir(false));
    $('#empty-create-file').addEventListener('click', () => createFileOrDir(false, ''));
    $('#welcome-new-file').addEventListener('click', () => createFileOrDir(false, ''));
    $('#new-folder-button').addEventListener('click', () => createFileOrDir(true));
    $('#refresh-tree-button').addEventListener('click', () => refreshTree().then(() => toast('Explorador actualizado.')));
    $('#save-button').addEventListener('click', saveActive);
    $('#run-button').addEventListener('click', runActiveFile);
    $('#verify-file-button').addEventListener('click', verifyActiveFile);
    $('#quick-open-button').addEventListener('click', openQuickOpen);
    $('#search-form').addEventListener('submit', (e) => { e.preventDefault(); runCodeSearch(); });
    $('#create-snapshot-button').addEventListener('click', async () => {
      const r = await api('/api/snapshot', { method: 'POST', body: { label: 'manual' } });
      toast(`Snapshot: ${r.id}`);
      refreshSnapshots();
    });
    $('#refresh-snapshots-button').addEventListener('click', () => refreshSnapshots());

    // Runtime & Terminal Events
    $$('.runtime-tab').forEach((b) => b.addEventListener('click', () => switchRuntimeTab(b.dataset.rtTab)));
    $('#rt-run-syntax-btn').addEventListener('click', () => runSyntaxCheckAll(true));
    $('#rt-run-security-btn').addEventListener('click', () => runSecurityAuditAll(true));
    $('#rt-run-tests-btn').addEventListener('click', async () => {
      const res = await api('/api/tools/run-tests', { method: 'POST', body: {} });
      renderRunOutput(res.run);
      switchRuntimeTab('output');
    });
    $('#new-terminal-button').addEventListener('click', () => createTerminal());
    $('#clear-terminal-button').addEventListener('click', () => {
      state.terminals.find((t) => t.id === state.activeTerminalId)?.terminal.clear();
    });
    $('#clear-output-button').addEventListener('click', () => {
      $('#run-output').innerHTML = '<div class="empty-inline">Salida limpiada.</div>';
    });
    $$('[data-term-key="ctrl-c"]').forEach((b) => b.addEventListener('click', () => sendToActiveTerminal('\x03')));
    $$('[data-term-cmd]').forEach((b) => b.addEventListener('click', () => sendToActiveTerminal(b.dataset.termCmd + '\n')));
    $('#mobile-term-form').addEventListener('submit', (e) => {
      e.preventDefault();
      const cmd = $('#mobile-term-input').value;
      if (!cmd.trim()) return;
      sendToActiveTerminal(cmd + '\n');
      $('#mobile-term-input').value = '';
    });
    $('#auto-repair-safe-btn').addEventListener('click', async () => {
      const res = await api('/api/tools/auto-repair', { method: 'POST', body: {} });
      state.lastSyntax = res.repair.syntaxAfter;
      renderDiagnosticsTable();
      toast(`Reparación determinista: ${res.repair.repaired.length} archivo(s) actualizados.`);
    });
    $('#send-diags-to-debuggers-btn').addEventListener('click', () => {
      const summary = state.lastDiagnostics.slice(0, 15).map((d) => `${d.file}:${d.line} ${d.title}`).join('; ');
      switchView('missions');
      launchMission({
        objective: `Corrige todos los errores de sintaxis y hallazgos de seguridad detectados y verifica con pruebas reales: ${summary || 'auditoría completa del proyecto'}`,
        mode: 'audit-and-heal',
        scale: 'standard'
      });
    });
    $('#refresh-procs-btn').addEventListener('click', () => refreshProcesses());
    $('#start-proc-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      try {
        await api('/api/processes/start', {
          method: 'POST',
          body: { name: $('#proc-name-input').value.trim(), command: $('#proc-cmd-input').value.trim() }
        });
        $('#proc-name-input').value = '';
        $('#proc-cmd-input').value = '';
        toast('Proceso iniciado.');
        refreshProcesses();
      } catch (err) { toast(err.message, 'error'); }
    });

    // Modals & Global Shortcuts
    $('#prompt-form').addEventListener('submit', (e) => {
      e.preventDefault();
      const v = $('#prompt-input').value;
      $('#prompt-dialog').close();
      const res = state.promptResolve;
      state.promptResolve = null;
      res?.(v);
    });
    $('#prompt-dialog').addEventListener('close', () => {
      if (state.promptResolve) { state.promptResolve(null); state.promptResolve = null; }
    });
    $('#quick-open-input').addEventListener('input', (e) => renderQuickOpen(e.target.value));
    $$('[data-close-dialog]').forEach((b) => b.addEventListener('click', () => {
      document.getElementById(b.dataset.closeDialog)?.close();
    }));
    document.addEventListener('click', (e) => {
      if (!e.target.closest('#context-menu')) $('#context-menu').classList.add('hidden');
    });
    document.addEventListener('keydown', (e) => {
      const cmd = e.ctrlKey || e.metaKey;
      if (cmd && e.key.toLowerCase() === 's' && state.view === 'code') { e.preventDefault(); saveActive(); }
      if (cmd && e.key.toLowerCase() === 'p') { e.preventDefault(); openQuickOpen(); }
      if (e.key === 'F5' && state.view === 'code') { e.preventDefault(); runActiveFile(); }
      if (e.key === 'Escape') { closeToolsDrawer(); closeChatDrawer(); $('#context-menu').classList.add('hidden'); }
    });
    window.addEventListener('resize', () => { state.editor?.layout(); fitActiveTerminal(); });
  }

  /* ===================== BOOTSTRAP ===================== */

  async function init() {
    loadConfig();
    syncSettingsForms();
    bindEvents();
    initMonaco();

    try {
      const [health, presetsRes] = await Promise.all([
        api('/api/health'),
        api('/api/providers/presets')
      ]);
      state.health = health;
      state.presets = presetsRes.presets || [];
      $('#workspace-name').textContent = health.workspace || 'workspace';
      const avail = (health.runtimes || []).filter((r) => r.available).map((r) => r.label);
      $('#runtime-status').textContent = `${avail.length} runtimes activos`;
      $('#runtime-pill').title = avail.join(' · ');
      updateFleetKPIs(health.fleet);
      renderProviderPresets();
      renderProvidersList();
      updatePoolStatusUI();
    } catch (err) {
      toast('Error conectando con el servidor: ' + err.message, 'error', 6000);
    }

    await Promise.all([
      loadAgentsAndDivisions().catch(() => {}),
      refreshTree().catch(() => {}),
      refreshMissions().catch(() => {}),
      refreshProjectHealthKPI().catch(() => {})
    ]);
    createTerminal();
    connectGlobalStream();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();
})();
