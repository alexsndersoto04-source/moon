'use strict';

(() => {
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const STORAGE = {
    settings: 'codeforge.settings.v2',
    session: 'codeforge.agent-session.v2',
    fontSize: 'codeforge.editor-font-size.v2'
  };
  const ICONS = {
    file: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3.5h8l4.5 4.5v12H6z"/><path d="M14 3.5V9h5"/></svg>',
    folder: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3.5 6h6l2 2h9v11h-17z"/></svg>',
    chevron: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7"/></svg>',
    agent: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2.2 2.2m9.6 9.6L19 19M19 5l-2.2 2.2m-9.6 9.6L5 19"/></svg>',
    tool: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14.5 6.5 18 3l3 3-3.5 3.5m-3-3L5 16l3 3 9.5-9.5M4 20l2-1"/></svg>'
  };
  let modelCatalogRevision = 0;
  let modelInputEdited = false;
  let apiKeyOrigin = '';
  const state = {
    health: null,
    tree: [],
    treeLookup: new Map(),
    expanded: new Set(),
    tabs: new Map(),
    activePath: null,
    editor: null,
    monacoReady: false,
    view: 'explorer',
    selectedDir: '',
    searchTimer: null,
    quickFiles: [],
    quickIndex: 0,
    panel: 'terminal',
    panelVisible: true,
    panelMaximized: false,
    terminals: [],
    activeTerminalId: null,
    terminalSequence: 0,
    agentBusy: false,
    agentAbort: null,
    agentSessionId: '',
    promptSubmit: null,
    promptResolve: null,
    settings: {
      baseUrl: 'https://api.openai.com/v1', model: '', apiKey: '', temperature: 0.2,
      maxSteps: 16, tools: true, fontSize: 14, minimap: true, autosave: false
    }
  };

  class ApiError extends Error {
    constructor(message, status) { super(message); this.name = 'ApiError'; this.status = status; }
  }

  async function api(url, options = {}) {
    const headers = new Headers(options.headers || {});
    let body = options.body;
    if (body != null && typeof body !== 'string' && !(body instanceof FormData)) {
      headers.set('Content-Type', 'application/json');
      body = JSON.stringify(body);
    }
    const response = await fetch(url, { ...options, headers, body, credentials: 'same-origin' });
    const type = response.headers.get('content-type') || '';
    let result;
    if (type.includes('application/json')) result = await response.json().catch(() => ({}));
    else result = { message: await response.text().catch(() => '') };
    if (!response.ok || result.ok === false) throw new ApiError(result.error || result.message || `Error HTTP ${response.status}`, response.status);
    return result;
  }

  function escapeHTML(value) {
    return String(value).replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
  }

  function languageFor(path) {
    const base = String(path || '').split('/').pop().toLowerCase();
    const ext = base.includes('.') ? base.slice(base.lastIndexOf('.')) : '';
    const map = {
      '.js': ['javascript', 'language-js'], '.mjs': ['javascript', 'language-js'], '.cjs': ['javascript', 'language-js'],
      '.ts': ['typescript', 'language-ts'], '.tsx': ['typescript', 'language-ts'], '.mts': ['typescript', 'language-ts'],
      '.py': ['python', 'language-py'], '.html': ['html', 'language-html'], '.htm': ['html', 'language-html'],
      '.css': ['css', 'language-css'], '.scss': ['scss', 'language-css'], '.json': ['json', 'language-json'],
      '.md': ['markdown', 'language-md'], '.mdx': ['markdown', 'language-md'], '.rs': ['rust', 'language-rust'],
      '.go': ['go', 'language-go'], '.java': ['java', 'language-java'], '.rb': ['ruby', 'language-ruby'],
      '.php': ['php', 'language-php'], '.sh': ['shell', 'language-shell'], '.bash': ['shell', 'language-shell'],
      '.c': ['c', 'language-c'], '.h': ['c', 'language-c'], '.cpp': ['cpp', 'language-cpp'], '.cc': ['cpp', 'language-cpp'],
      '.sql': ['sql', 'language-sql'], '.xml': ['xml', 'language-xml'], '.svg': ['xml', 'language-xml'],
      '.yaml': ['yaml', 'language-yaml'], '.yml': ['yaml', 'language-yaml'], '.toml': ['ini', 'language-ini'],
      '.ini': ['ini', 'language-ini'], '.kt': ['kotlin', 'language-kotlin'], '.swift': ['swift', 'language-swift'],
      '.lua': ['lua', 'language-lua'], '.pl': ['perl', 'language-perl'], '.txt': ['plaintext', 'language-text']
    };
    if (base === 'dockerfile') return ['dockerfile', 'language-docker'];
    if (base === '.gitignore' || base === '.env' || base.startsWith('.env.')) return ['ini', 'language-config'];
    return map[ext] || ['plaintext', 'language-text'];
  }

  function fileIconMarkup(path, isDir = false) {
    if (isDir) return `<span class="file-icon folder">${ICONS.folder}</span>`;
    const [, cssClass] = languageFor(path);
    return `<span class="file-icon ${cssClass}">${ICONS.file}</span>`;
  }

  function toast(message, kind = 'success', duration = 3200) {
    const root = $('#toast-root');
    const item = document.createElement('div');
    item.className = `toast ${kind === 'error' ? 'error' : kind === 'warning' ? 'warning' : ''}`;
    const icon = kind === 'error'
      ? '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="m9 9 6 6m0-6-6 6"/></svg>'
      : kind === 'warning'
        ? '<svg viewBox="0 0 24 24"><path d="M12 3 2.8 20h18.4z"/><path d="M12 9v5m0 3h.01"/></svg>'
        : '<svg viewBox="0 0 24 24"><path d="m5 12 4 4L19 6"/></svg>';
    const iconWrap = document.createElement('span');
    iconWrap.innerHTML = icon;
    const text = document.createElement('span');
    text.textContent = message;
    item.append(iconWrap.firstElementChild, text);
    root.append(item);
    window.setTimeout(() => {
      item.classList.add('leaving');
      window.setTimeout(() => item.remove(), 200);
    }, duration);
  }

  function setStatus(message, kind = '') {
    const node = $('#status-message');
    node.textContent = message;
    node.classList.toggle('status-error', kind === 'error');
  }

  function dialog(id) { return document.getElementById(id); }
  function showDialog(id) {
    const element = dialog(id);
    if (element && !element.open) element.showModal();
  }
  function closeDialog(id) {
    const element = dialog(id);
    if (element?.open) element.close();
  }

  function loadSettings() {
    try {
      const stored = JSON.parse(localStorage.getItem(STORAGE.settings) || '{}');
      state.settings = { ...state.settings, ...stored };
    } catch { /* Ignore stale/corrupt local preferences. */ }
    const font = Number(localStorage.getItem(STORAGE.fontSize));
    if (font >= 11 && font <= 22) state.settings.fontSize = font;
    try {
      state.agentSessionId = sessionStorage.getItem(STORAGE.session) || crypto.randomUUID();
      sessionStorage.setItem(STORAGE.session, state.agentSessionId);
    } catch {
      state.agentSessionId = `session-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    }
  }

  function persistSettings() {
    const { baseUrl, model, apiKey, temperature, maxSteps, tools, fontSize, minimap, autosave } = state.settings;
    localStorage.setItem(STORAGE.settings, JSON.stringify({ baseUrl, model, apiKey, temperature, maxSteps, tools, fontSize, minimap, autosave }));
    localStorage.setItem(STORAGE.fontSize, String(fontSize));
  }

  function initMonaco() {
    if (!window.require) {
      toast('No se cargó Monaco. Comprueba la instalación de dependencias.', 'error', 7000);
      setStatus('Monaco no disponible', 'error');
      return;
    }
    const vsPath = '/vendor/monaco/vs';
    let monacoWorkerUrl = null;
    window.MonacoEnvironment = {
      getWorkerUrl() {
        if (!monacoWorkerUrl) {
          const script = `self.MonacoEnvironment={baseUrl:location.origin+'/vendor/monaco/'};importScripts('${vsPath}/base/worker/workerMain.js');`;
          monacoWorkerUrl = URL.createObjectURL(new Blob([script], { type: 'text/javascript' }));
        }
        return monacoWorkerUrl;
      }
    };
    window.require.config({ paths: { vs: vsPath } });
    window.require(['vs/editor/editor.main'], () => {
      const monaco = window.monaco;
      if (!monaco) {
        toast('Monaco se cargó sin su API de editor.', 'error');
        return;
      }
      monaco.editor.defineTheme('codeforge-dark', {
        base: 'vs-dark', inherit: true,
        rules: [
          { token: 'comment', foreground: '718391', fontStyle: 'italic' },
          { token: 'keyword', foreground: 'c792ea' },
          { token: 'string', foreground: 'a7d99a' },
          { token: 'number', foreground: 'f4b66a' },
          { token: 'type', foreground: '68c9ce' },
          { token: 'function', foreground: '83b6ee' }
        ],
        colors: {
          'editor.background': '#0d131b', 'editor.foreground': '#cbd6df',
          'editorLineNumber.foreground': '#536373', 'editorLineNumber.activeForeground': '#a8bac7',
          'editorCursor.foreground': '#5de2bd', 'editor.selectionBackground': '#1d4750',
          'editor.inactiveSelectionBackground': '#273b46', 'editor.lineHighlightBackground': '#111b24',
          'editorIndentGuide.background1': '#1c2934', 'editorIndentGuide.activeBackground1': '#354957',
          'editorGutter.background': '#0d131b', 'editorWidget.background': '#17212b',
          'editorWidget.border': '#2d3b48', 'editorSuggestWidget.background': '#17212b',
          'editorSuggestWidget.border': '#31404c', 'editorSuggestWidget.selectedBackground': '#233541',
          'scrollbarSlider.background': '#60708033', 'scrollbarSlider.hoverBackground': '#75889a55',
          'minimap.background': '#0d131b'
        }
      });
      state.editor = monaco.editor.create($('#monaco-editor'), {
        theme: 'codeforge-dark', language: 'plaintext', value: '',
        automaticLayout: true, fontSize: state.settings.fontSize || 14,
        fontFamily: "'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace",
        lineHeight: Math.round((state.settings.fontSize || 14) * 1.65), tabSize: 2, insertSpaces: true, detectIndentation: true,
        minimap: { enabled: state.settings.minimap !== false, scale: 1, showSlider: 'mouseover' },
        scrollBeyondLastLine: false, smoothScrolling: true, cursorBlinking: 'smooth',
        cursorSmoothCaretAnimation: 'on', renderLineHighlight: 'line',
        bracketPairColorization: { enabled: true }, guides: { indentation: true, bracketPairs: true },
        folding: true, foldingHighlight: true, showFoldingControls: 'mouseover',
        wordWrap: 'off', padding: { top: 10, bottom: 10 },
        renderWhitespace: 'selection', roundedSelection: true, matchBrackets: 'always',
        links: true, contextmenu: true, accessibilitySupport: 'auto',
        suggest: { preview: true, showWords: true }, quickSuggestions: { other: true, comments: false, strings: false }
      });
      state.editor.onDidChangeModelContent(() => {
        const tab = state.tabs.get(state.activePath);
        if (!tab) return;
        tab.content = state.editor.getValue();
        tab.dirty = tab.content !== tab.savedContent;
        renderTabs();
        updateEditorStatus();
      });
      state.editor.onDidChangeCursorPosition((event) => {
        if (!state.activePath) return;
        $('#cursor-status').textContent = `Ln ${event.position.lineNumber}, Col ${event.position.column}`;
        $('#cursor-status').classList.remove('hidden');
      });
      state.monacoReady = true;
      $('#monaco-editor').classList.remove('hidden');
      window.addEventListener('resize', () => state.editor?.layout());
      updateEditorStatus();
      if (state.activePath) activateTab(state.activePath);
    }, (error) => {
      console.error('Monaco loader error:', error);
      toast('No se pudo inicializar Monaco.', 'error');
    });
  }

  async function initialize() {
    loadSettings();
    fillSettingsForm();
    initMonaco();
    bindEvents();
    try {
      const health = await api('/api/health');
      state.health = health;
      $('#workspace-name').textContent = health.workspace || 'Workspace';
      $('#root-label').textContent = String(health.workspace || 'WORKSPACE').toUpperCase();
      const available = (health.runtimes || []).filter((runtime) => runtime.available).map((runtime) => runtime.label);
      $('#runtime-status').textContent = available.length ? `${available.length} runtimes` : 'Sin runtimes detectados';
      $('#runtime-status').title = available.length ? available.join(' · ') : 'Node.js y la shell siempre están disponibles.';
      setStatus(`Conectado · Node ${health.node || ''}`);
    } catch (error) {
      setStatus('No se pudo conectar con el servidor', 'error');
      toast(error.message, 'error', 7000);
    }
    await refreshTree().catch((error) => toast(error.message, 'error'));
    await createTerminal();
  }

  async function refreshTree() {
    const result = await api('/api/tree?depth=14');
    state.tree = result.tree || [];
    state.treeLookup.clear();
    let fileCount = 0;
    const indexNodes = (nodes) => {
      for (const node of nodes) {
        state.treeLookup.set(node.path, node);
        if (node.type === 'file') fileCount++;
        if (node.children) indexNodes(node.children);
      }
    };
    indexNodes(state.tree);
    $('#file-count').textContent = fileCount ? String(fileCount) : '';
    $('#tree-empty').classList.toggle('hidden', state.tree.length > 0);
    $('#file-tree').classList.toggle('hidden', state.tree.length === 0);
    renderTree();
    if ($('#quick-open-dialog').open) refreshQuickFiles();
  }

  function renderTree() {
    const container = $('#file-tree');
    container.replaceChildren();
    for (const node of state.tree) container.append(renderTreeNode(node, 0));
    if (state.activePath) {
      const active = container.querySelector(`[data-path="${CSS.escape(state.activePath)}"]`);
      active?.classList.add('is-selected');
    }
  }

  function renderTreeNode(node, depth) {
    const wrap = document.createElement('div');
    wrap.className = `tree-node ${node.type === 'dir' && state.expanded.has(node.path) ? 'is-expanded' : ''}`;
    const row = document.createElement('button');
    row.type = 'button';
    row.className = `tree-row ${node.path === state.activePath ? 'is-selected' : ''}`;
    row.dataset.path = node.path;
    row.dataset.type = node.type;
    row.setAttribute('role', 'treeitem');
    row.setAttribute('aria-selected', node.path === state.activePath ? 'true' : 'false');
    if (node.type === 'dir') row.setAttribute('aria-expanded', state.expanded.has(node.path) ? 'true' : 'false');
    row.innerHTML = `<span class="tree-indent" style="width:${Math.min(depth * 10, 100)}px"></span>${node.type === 'dir' ? `<span class="tree-chevron">${ICONS.chevron}</span>` : '<span class="tree-chevron-spacer"></span>'}${fileIconMarkup(node.path, node.type === 'dir')}<span class="tree-name"></span>${node.type === 'file' && state.tabs.get(node.path)?.dirty ? '<span class="tree-badge">●</span>' : ''}`;
    $('.tree-name', row).textContent = node.name;
    row.addEventListener('click', () => {
      if (node.type === 'dir') {
        state.selectedDir = node.path;
        if (state.expanded.has(node.path)) state.expanded.delete(node.path); else state.expanded.add(node.path);
        renderTree();
      } else {
        state.selectedDir = node.path.includes('/') ? node.path.slice(0, node.path.lastIndexOf('/')) : '';
        openFile(node.path).catch((error) => toast(error.message, 'error'));
      }
    });
    row.addEventListener('contextmenu', (event) => {
      event.preventDefault();
      state.selectedDir = node.type === 'dir' ? node.path : node.path.includes('/') ? node.path.slice(0, node.path.lastIndexOf('/')) : '';
      showContextMenu(event.clientX, event.clientY, node);
    });
    wrap.append(row);
    if (node.type === 'dir' && state.expanded.has(node.path)) {
      const children = document.createElement('div');
      children.className = 'tree-children';
      children.setAttribute('role', 'group');
      for (const child of node.children || []) children.append(renderTreeNode(child, depth + 1));
      wrap.append(children);
    }
    return wrap;
  }

  function revealTreePath(path) {
    const parts = String(path).split('/');
    let acc = '';
    for (let index = 0; index < parts.length - 1; index++) {
      acc = acc ? `${acc}/${parts[index]}` : parts[index];
      state.expanded.add(acc);
    }
    state.selectedDir = parts.length > 1 ? parts.slice(0, -1).join('/') : '';
    renderTree();
    const row = $('#file-tree').querySelector(`[data-path="${CSS.escape(path)}"]`);
    row?.scrollIntoView({ block: 'nearest' });
  }

  async function openFile(path, line) {
    if (!path) return;
    hideContextMenu();
    let tab = state.tabs.get(path);
    if (!tab) {
      setStatus(`Abriendo ${path}…`);
      const result = await api(`/api/file?path=${encodeURIComponent(path)}`);
      tab = {
        path: result.path,
        name: path.split('/').pop(),
        content: result.content,
        savedContent: result.content,
        version: result.version,
        language: result.language || languageFor(path)[0],
        model: null,
        dirty: false
      };
      state.tabs.set(path, tab);
      if (state.monacoReady) createModel(tab);
    }
    await activateTab(path);
    if (line && state.editor) {
      state.editor.revealLineInCenter(Math.max(1, Number(line) || 1));
      state.editor.setPosition({ lineNumber: Math.max(1, Number(line) || 1), column: 1 });
      state.editor.focus();
    }
    revealTreePath(path);
    return tab;
  }

  function createModel(tab) {
    if (!state.monacoReady || tab.model) return;
    const uri = window.monaco.Uri.parse('file:///codeforge/' + tab.path.split('/').map(encodeURIComponent).join('/'));
    tab.model = window.monaco.editor.createModel(tab.content, tab.language, uri);
    tab.model.onDidChangeContent(() => {
      if (state.activePath !== tab.path || !state.editor) return;
      tab.content = tab.model.getValue();
      tab.dirty = tab.content !== tab.savedContent;
      renderTabs();
      renderTree();
      updateEditorStatus();
    });
  }

  async function activateTab(path) {
    if (!state.tabs.has(path)) return;
    if (state.activePath && state.activePath !== path && state.settings.autosave) {
      const previous = state.tabs.get(state.activePath);
      if (previous?.dirty) await saveTab(previous).catch((error) => toast(error.message, 'error'));
    }
    state.activePath = path;
    const tab = state.tabs.get(path);
    if (state.monacoReady) {
      createModel(tab);
      $('#welcome-view').classList.add('hidden');
      $('#monaco-editor').classList.remove('hidden');
      state.editor.setModel(tab.model);
      state.editor.focus();
      state.editor.layout();
    }
    renderTabs();
    renderBreadcrumbs(path);
    updateEditorStatus();
    $('#run-button').disabled = false;
    $('#save-button').disabled = !tab.dirty;
    renderTree();
    setStatus(tab.dirty ? 'Cambios sin guardar' : `Abierto ${path}`);
  }

  function renderTabs() {
    const container = $('#editor-tabs');
    container.replaceChildren();
    if (!state.tabs.size) {
      const placeholder = document.createElement('div');
      placeholder.className = 'tab-placeholder';
      placeholder.textContent = 'Abre un archivo desde el explorador para comenzar';
      container.append(placeholder);
      return;
    }
    for (const [path, tab] of state.tabs) {
      const button = document.createElement('div');
      button.className = `editor-tab ${path === state.activePath ? 'is-active' : ''}`;
      button.setAttribute('role', 'tab');
      button.setAttribute('aria-selected', path === state.activePath ? 'true' : 'false');
      button.title = path + (tab.dirty ? ' · Cambios sin guardar' : '');
      button.innerHTML = `${fileIconMarkup(path)}<span class="editor-tab-name"></span>${tab.dirty ? '<span class="tab-dirty" aria-label="Cambios sin guardar"></span>' : ''}<button class="tab-close" type="button" aria-label="Cerrar pestaña"><svg viewBox="0 0 24 24"><path d="m6 6 12 12M18 6 6 18"/></svg></button>`;
      $('.editor-tab-name', button).textContent = tab.name;
      button.addEventListener('click', (event) => {
        if (event.target.closest('.tab-close')) {
          event.stopPropagation();
          closeTab(path);
        } else activateTab(path).catch((error) => toast(error.message, 'error'));
      });
      button.addEventListener('auxclick', (event) => { if (event.button === 1) closeTab(path); });
      container.append(button);
    }
    const active = container.querySelector('.editor-tab.is-active');
    active?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }

  function renderBreadcrumbs(path) {
    const container = $('#breadcrumbs');
    container.replaceChildren();
    const root = document.createElement('span');
    root.className = 'breadcrumb-workspace';
    root.textContent = 'workspace';
    container.append(root);
    for (const [index, part] of path.split('/').entries()) {
      const slash = document.createElement('span');
      slash.className = 'breadcrumb-sep';
      slash.textContent = '/';
      container.append(slash);
      const item = document.createElement('span');
      item.className = `breadcrumb-item ${index === path.split('/').length - 1 ? 'breadcrumb-current' : ''}`;
      item.textContent = part;
      item.title = part;
      container.append(item);
    }
  }

  function updateEditorStatus() {
    const tab = state.tabs.get(state.activePath);
    $('#language-status').textContent = tab ? tab.language : 'CodeForge';
    $('#language-status').title = tab ? `Lenguaje: ${tab.language}` : 'CodeForge';
    $('#encoding-status').classList.toggle('hidden', !tab);
    $('#cursor-status').classList.toggle('hidden', !tab);
    $('#save-button').disabled = !tab?.dirty;
    $('#save-button').classList.toggle('has-changes', !!tab?.dirty);
    $('#run-button').disabled = !tab;
  }

  async function saveTab(tab) {
    if (!tab) return;
    const latest = tab.model ? tab.model.getValue() : tab.content;
    if (!tab.dirty && latest === tab.savedContent) return;
    setStatus(`Guardando ${tab.path}…`);
    const result = await api('/api/file', {
      method: 'PUT',
      body: { path: tab.path, content: latest, expectedVersion: tab.version }
    });
    tab.content = latest;
    tab.savedContent = latest;
    tab.version = result.version;
    tab.dirty = false;
    renderTabs();
    renderTree();
    updateEditorStatus();
    setStatus(`Guardado ${tab.path}`);
    return result;
  }

  async function saveActive() {
    const tab = state.tabs.get(state.activePath);
    if (!tab) return toast('No hay archivo activo para guardar.', 'warning');
    try { await saveTab(tab); toast('Archivo guardado.'); }
    catch (error) {
      if (error.status === 409) {
        const reload = window.confirm(`${error.message}\n\n¿Quieres descartar tu buffer y volver a cargar la versión del servidor?`);
        if (reload) {
          await closeTab(tab.path, true);
          await openFile(tab.path);
        }
      } else toast(error.message, 'error');
    }
  }

  async function closeTab(path, force = false) {
    const tab = state.tabs.get(path);
    if (!tab) return;
    if (tab.dirty && !force) {
      const action = window.confirm(`«${tab.name}» tiene cambios sin guardar.\n\nAceptar: guardar y cerrar · Cancelar: mantener abierto.`);
      if (action) {
        try { await saveTab(tab); }
        catch (error) { toast(error.message, 'error'); return; }
      } else if (!window.confirm('¿Descartar los cambios y cerrar la pestaña?')) return;
    }
    tab.model?.dispose();
    state.tabs.delete(path);
    if (state.activePath === path) {
      const next = [...state.tabs.keys()].at(-1);
      state.activePath = null;
      if (next) await activateTab(next);
      else {
        $('#welcome-view').classList.remove('hidden');
        $('#monaco-editor').classList.add('hidden');
        $('#breadcrumbs').innerHTML = '<span class="breadcrumb-workspace">workspace</span><span class="breadcrumb-sep">/</span><span class="breadcrumb-placeholder">Sin archivo abierto</span>';
        updateEditorStatus();
      }
    }
    renderTabs();
    renderTree();
  }

  async function closeAllTabs(force = false) {
    for (const path of [...state.tabs.keys()]) await closeTab(path, force);
  }

  async function runActiveFile() {
    const tab = state.tabs.get(state.activePath);
    if (!tab) return toast('Abre un archivo ejecutable primero.', 'warning');
    try {
      if (tab.dirty) await saveTab(tab);
      showPanel('output');
      $('#run-output').innerHTML = '<div class="output-empty">Ejecutando…</div>';
      setStatus(`Ejecutando ${tab.path}…`);
      const response = await api('/api/run', { method: 'POST', body: { path: tab.path } });
      renderRunOutput(response.run);
      setStatus(response.run.ok ? `Ejecución completada · ${response.run.durationMs} ms` : `Ejecución finalizó con código ${response.run.code}`, response.run.ok ? '' : 'error');
    } catch (error) {
      $('#run-output').textContent = error.message;
      toast(error.message, 'error');
      setStatus('No se pudo ejecutar el archivo', 'error');
    }
  }

  function renderRunOutput(result) {
    const root = $('#run-output');
    root.replaceChildren();
    const header = document.createElement('div');
    header.className = 'run-output-header';
    const status = document.createElement('span');
    status.className = `run-output-status ${result.ok ? '' : 'is-error'}`;
    status.textContent = result.ok ? '✓ EJECUCIÓN CORRECTA' : result.blocked ? '⛔ BLOQUEADO' : result.timedOut ? '⌛ TIEMPO AGOTADO' : `✕ CÓDIGO ${result.code}`;
    const runtime = document.createElement('span');
    runtime.textContent = result.runtime || result.command || 'Proceso';
    const meta = document.createElement('span');
    meta.className = 'run-output-meta';
    meta.textContent = `${result.durationMs ?? 0} ms${result.compileMs ? ` · compilación ${result.compileMs} ms` : ''}`;
    header.append(status, runtime, meta);
    root.append(header);
    if (result.path) {
      const pathLine = document.createElement('div');
      pathLine.className = 'run-output-section';
      pathLine.textContent = result.path + (result.stage ? ` · ${result.stage}` : '');
      root.append(pathLine);
    }
    if (result.stdout) appendOutputSection(root, 'STDOUT', result.stdout, false);
    if (result.stderr) appendOutputSection(root, 'STDERR', result.stderr, true);
    if (!result.stdout && !result.stderr) {
      const empty = document.createElement('div');
      empty.className = 'output-empty';
      empty.textContent = result.ok ? 'El proceso terminó sin salida.' : result.message || 'El proceso no produjo salida.';
      root.append(empty);
    }
    $('#output-tab').classList.toggle('has-error', !result.ok);
  }

  function appendOutputSection(root, title, text, isError) {
    const section = document.createElement('div');
    section.className = `run-output-section ${isError ? 'stderr' : ''}`;
    const label = document.createElement('span');
    label.className = 'run-output-label';
    label.textContent = title;
    const output = document.createElement('div');
    output.textContent = text;
    section.append(label, output);
    root.append(section);
  }

  function showPanel(panel = 'terminal') {
    state.panel = panel;
    state.panelVisible = true;
    $('#editor-column').classList.remove('panel-hidden');
    $('#terminal-panel').classList.toggle('hidden', panel !== 'terminal');
    $('#output-panel').classList.toggle('hidden', panel !== 'output');
    $('#terminal-tab').classList.toggle('is-active', panel === 'terminal');
    $('#output-tab').classList.toggle('is-active', panel === 'output');
    $('#terminal-tab').setAttribute('aria-selected', panel === 'terminal' ? 'true' : 'false');
    $('#output-tab').setAttribute('aria-selected', panel === 'output' ? 'true' : 'false');
    window.setTimeout(() => fitActiveTerminal(), 60);
  }

  function hidePanel() {
    state.panelVisible = false;
    $('#editor-column').classList.add('panel-hidden');
  }

  function togglePanel() {
    if (state.panelVisible) hidePanel(); else showPanel(state.panel || 'terminal');
  }

  function toggleMaxPanel() {
    state.panelMaximized = !state.panelMaximized;
    $('#editor-column').classList.toggle('panel-maximized', state.panelMaximized);
    $('#maximize-panel-button').title = state.panelMaximized ? 'Restaurar panel' : 'Maximizar panel';
    window.setTimeout(() => { state.editor?.layout(); fitActiveTerminal(); }, 80);
  }

  async function createTerminal() {
    if (!window.Terminal || !window.FitAddon?.FitAddon) {
      $('#terminal-state').textContent = 'xterm no disponible';
      toast('No se cargó xterm.js. Reinstala las dependencias del proyecto.', 'error', 6000);
      return null;
    }
    const id = ++state.terminalSequence;
    const terminal = new window.Terminal({
      cursorBlink: true,
      cursorStyle: 'bar',
      fontSize: 12,
      fontFamily: "'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace",
      lineHeight: 1.35,
      scrollback: 4000,
      convertEol: true,
      allowProposedApi: true,
      theme: {
        background: '#0e151d', foreground: '#c5d2dc', cursor: '#58ddb9',
        cursorAccent: '#0e151d', selectionBackground: '#29505c',
        black: '#17212b', red: '#ff7982', green: '#72d6a0', yellow: '#e9c06e',
        blue: '#7eb8ef', magenta: '#c69be5', cyan: '#61cfca', white: '#d7e2e9',
        brightBlack: '#71808e', brightRed: '#ff9ca3', brightGreen: '#91e2b3', brightYellow: '#f3d492',
        brightBlue: '#9ac9f3', brightMagenta: '#dbb5f3', brightCyan: '#81e0dc', brightWhite: '#f2f7f9'
      }
    });
    const fit = new window.FitAddon.FitAddon();
    terminal.loadAddon(fit);
    const host = document.createElement('div');
    host.className = 'terminal-host';
    host.dataset.terminalId = String(id);
    $('#terminal-host-wrap').append(host);
    terminal.open(host);
    const item = { id, name: `Terminal ${id}`, terminal, fit, ws: null, host, connected: false, ready: false };
    state.terminals.push(item);
    state.activeTerminalId = id;
    renderTerminalTabs();
    connectTerminal(item);
    terminal.onData((data) => {
      if (item.compat) echoCompatibleInput(terminal, data);
      if (item.ws?.readyState === WebSocket.OPEN) item.ws.send(JSON.stringify({ type: 'input', data }));
    });
    terminal.onResize(({ cols, rows }) => {
      if (item.ws?.readyState === WebSocket.OPEN) item.ws.send(JSON.stringify({ type: 'resize', cols, rows }));
    });
    if (!$('#terminal-panel').classList.contains('hidden') && state.panelVisible) {
      window.setTimeout(() => { try { fit.fit(); terminal.focus(); } catch { /* element not laid out */ } }, 70);
    }
    return item;
  }

  function connectTerminal(item) {
    if (!item || state.health?.terminal === false) {
      setTerminalState('Terminal desactivada', false);
      return;
    }
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const query = new URLSearchParams({ cwd: '' });
    let socket;
    try { socket = new WebSocket(`${protocol}//${location.host}/ws/terminal?${query}`); }
    catch (error) { item.terminal.write(`\r\nNo se pudo conectar: ${error.message}\r\n`); return; }
    item.ws = socket;
    socket.addEventListener('open', () => {
      item.connected = true;
      setTerminalState(state.health?.terminalMode === 'pty' ? 'Conectada · PTY' : 'Conectada · compatible', true);
      $('#terminal-tab .terminal-indicator')?.classList.add('is-connected');
      window.setTimeout(() => { try { item.fit.fit(); } catch { /* hidden terminal */ } }, 50);
    });
    socket.addEventListener('message', (event) => {
      const writeOutput = (text) => {
        if (text.includes('modo compatible sin PTY')) item.compat = true;
        item.terminal.write(text);
      };
      if (typeof event.data === 'string') writeOutput(event.data);
      else if (event.data instanceof Blob) event.data.text().then(writeOutput);
    });
    socket.addEventListener('error', () => {
      item.terminal.write('\r\n[Error de conexión con la terminal]\r\n');
      setTerminalState('Error', false);
    });
    socket.addEventListener('close', (event) => {
      item.connected = false;
      setTerminalState(event.code === 1000 ? 'Shell cerrada' : 'Desconectada', false);
      $('#terminal-tab .terminal-indicator')?.classList.remove('is-connected');
      if (event.code === 1006 && item.ready) item.terminal.write('\r\n[Conexión cerrada. Pulsa “Nueva terminal” para abrir otra.]\r\n');
      item.ready = true;
    });
  }

  function echoCompatibleInput(terminal, data) {
    let output = '';
    for (const character of data) {
      if (character === '\r' || character === '\n') output += '\r\n';
      else if (character === '\x7f' || character === '\b') output += '\b \b';
      else if (character === '\x03') output += '^C\r\n';
      else if (character === '\t') output += '    ';
      else if (character >= ' ') output += character;
    }
    if (output) terminal.write(output);
  }

  function setTerminalState(text, connected) {
    const node = $('#terminal-state');
    node.textContent = '';
    const dot = document.createElement('i');
    const label = document.createElement('span');
    label.textContent = text;
    node.append(dot, label);
    node.classList.toggle('is-connected', !!connected);
  }

  function renderTerminalTabs() {
    const tabs = $('#terminal-tabs');
    const hosts = $('#terminal-host-wrap');
    tabs.replaceChildren();
    if (!state.terminals.length) {
      hosts.replaceChildren();
      return;
    }
    for (const item of state.terminals) {
      const button = document.createElement('button');
      button.className = `terminal-tab ${item.id === state.activeTerminalId ? 'is-active' : ''}`;
      button.type = 'button';
      button.textContent = item.name;
      const close = document.createElement('span');
      close.className = 'terminal-tab-close';
      close.title = 'Cerrar terminal';
      close.innerHTML = '<svg viewBox="0 0 24 24"><path d="m6 6 12 12M18 6 6 18"/></svg>';
      close.addEventListener('click', (event) => { event.stopPropagation(); closeTerminal(item.id); });
      button.append(close);
      button.addEventListener('click', () => activateTerminal(item.id));
      tabs.append(button);
      item.host.classList.toggle('hidden', item.id !== state.activeTerminalId);
    }
  }

  function activateTerminal(id) {
    const item = state.terminals.find((term) => term.id === id);
    if (!item) return;
    state.activeTerminalId = id;
    renderTerminalTabs();
    window.setTimeout(() => {
      try { item.fit.fit(); item.terminal.focus(); } catch { /* no visible terminal */ }
    }, 50);
  }

  function closeTerminal(id) {
    const index = state.terminals.findIndex((term) => term.id === id);
    if (index < 0) return;
    const [item] = state.terminals.splice(index, 1);
    try { item.ws?.close(1000, 'Terminal cerrada por el usuario'); } catch { /* ignore */ }
    item.terminal.dispose();
    item.host.remove();
    if (state.activeTerminalId === id) state.activeTerminalId = state.terminals[Math.max(0, index - 1)]?.id ?? null;
    if (!state.terminals.length) {
      setTerminalState('Desconectada', false);
      $('#terminal-tab .terminal-indicator')?.classList.remove('is-connected');
    }
    renderTerminalTabs();
    if (state.activeTerminalId) activateTerminal(state.activeTerminalId);
  }

  function fitActiveTerminal() {
    const item = state.terminals.find((term) => term.id === state.activeTerminalId);
    if (!item || !state.panelVisible || state.panel !== 'terminal') return;
    try { item.fit.fit(); } catch { /* hidden or detached */ }
  }

  async function createFile(isDirectory = false, parent = state.selectedDir) {
    const initial = parent ? `${parent}/` : '';
    const result = await promptUser({
      eyebrow: isDirectory ? 'NUEVA CARPETA' : 'NUEVO ARCHIVO',
      title: isDirectory ? 'Crear carpeta' : 'Crear archivo',
      description: `Se creará dentro de ${parent || 'la raíz del workspace'}. Puedes escribir una ruta relativa completa.`,
      label: isDirectory ? 'Ruta de la carpeta' : 'Ruta del archivo',
      value: initial,
      placeholder: isDirectory ? 'src/components' : 'src/index.js',
      submitText: 'Crear'
    });
    if (result == null) return;
    const path = result.trim().replace(/^\/+/, '').replace(/\/$/, '');
    if (!path) return toast('Indica una ruta.', 'warning');
    try {
      if (isDirectory) {
        await api('/api/mkdir', { method: 'POST', body: { path } });
        toast(`Carpeta creada: ${path}`);
        const parts = path.split('/');
        let acc = '';
        for (const part of parts) { acc = acc ? `${acc}/${part}` : part; state.expanded.add(acc); }
      } else {
        if (state.treeLookup.has(path)) return toast('Ya existe un archivo o carpeta con esa ruta.', 'warning');
        await api('/api/file', { method: 'POST', body: { path, content: '' } });
        toast(`Archivo creado: ${path}`);
        await refreshTree();
        await openFile(path);
        return;
      }
      await refreshTree();
    } catch (error) { toast(error.message, 'error'); }
  }

  async function renamePath(node) {
    const initial = node.path;
    const newPath = await promptUser({
      eyebrow: node.type === 'dir' ? 'RENOMBRAR CARPETA' : 'RENOMBRAR ARCHIVO',
      title: 'Mover o renombrar',
      description: 'Escribe el nuevo nombre o una ruta relativa dentro del workspace.',
      label: 'Nueva ruta', value: initial, submitText: 'Mover'
    });
    if (newPath == null || !newPath.trim() || newPath.trim() === initial) return;
    const openTabs = [...state.tabs.values()].filter((tab) => tab.path === initial || tab.path.startsWith(initial + '/'));
    if (openTabs.some((tab) => tab.dirty) && !window.confirm('Hay cambios sin guardar en esta ruta. ¿Guardarlos antes de mover?')) return;
    try {
      for (const tab of openTabs) if (tab.dirty) await saveTab(tab);
      const response = await api('/api/move', { method: 'POST', body: { from: initial, to: newPath.trim() } });
      for (const tab of openTabs) {
        state.tabs.delete(tab.path);
        tab.path = tab.path === initial ? response.to : response.to + tab.path.slice(initial.length);
        tab.name = tab.path.split('/').pop();
        tab.model?.dispose();
        tab.model = null;
        state.tabs.set(tab.path, tab);
      }
      if (state.activePath === initial || state.activePath?.startsWith(initial + '/')) state.activePath = response.to + state.activePath.slice(initial.length);
      await refreshTree();
      if (state.activePath) await activateTab(state.activePath);
      toast(`Movido a ${response.to}`);
    } catch (error) { toast(error.message, 'error'); }
  }

  async function deletePath(node) {
    const noun = node.type === 'dir' ? 'carpeta y todo su contenido' : 'archivo';
    if (!window.confirm(`¿Eliminar ${noun} «${node.path}»?\n\nEsta acción no se puede deshacer.`)) return;
    try {
      const affected = [...state.tabs.keys()].filter((path) => path === node.path || (node.type === 'dir' && path.startsWith(node.path + '/')));
      if (affected.some((path) => state.tabs.get(path)?.dirty) && !window.confirm('Hay cambios sin guardar en esta ruta. ¿Descartarlos y continuar?')) return;
      for (const path of affected) await closeTab(path, true);
      await api('/api/delete', { method: 'POST', body: { path: node.path } });
      await refreshTree();
      toast('Elemento eliminado.');
    } catch (error) { toast(error.message, 'error'); }
  }

  function showContextMenu(x, y, node = null) {
    const menu = $('#context-menu');
    menu.replaceChildren();
    const add = (label, icon, action, danger = false) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `context-item ${danger ? 'danger' : ''}`;
      button.setAttribute('role', 'menuitem');
      button.innerHTML = `${icon}<span></span>`;
      $('span', button).textContent = label;
      button.addEventListener('click', () => { hideContextMenu(); action(); });
      menu.append(button);
    };
    if (node?.type === 'file') add('Abrir', ICONS.file, () => openFile(node.path).catch((error) => toast(error.message, 'error')));
    add('Nuevo archivo', '<svg viewBox="0 0 24 24"><path d="M13 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7m-8 1h4m-2-2v4m5-12v6m-3-3h6"/></svg>', () => createFile(false, node?.type === 'dir' ? node.path : state.selectedDir));
    add('Nueva carpeta', ICONS.folder, () => createFile(true, node?.type === 'dir' ? node.path : state.selectedDir));
    if (node) {
      const separator = document.createElement('div'); separator.className = 'context-separator'; menu.append(separator);
      add('Renombrar / mover', '<svg viewBox="0 0 24 24"><path d="m14 5 5 5M4 20l4.5-1 10-10a2.1 2.1 0 0 0-3-3l-10 10z"/></svg>', () => renamePath(node));
      add('Eliminar', '<svg viewBox="0 0 24 24"><path d="M4 7h16M9 7V4h6v3m-8 0 1 13h8l1-13m-7 3v7m3-7v7"/></svg>', () => deletePath(node), true);
    }
    menu.classList.remove('hidden');
    const width = menu.offsetWidth, height = menu.offsetHeight;
    menu.style.left = `${Math.min(x, window.innerWidth - width - 8)}px`;
    menu.style.top = `${Math.min(y, window.innerHeight - height - 8)}px`;
  }

  function hideContextMenu() { $('#context-menu').classList.add('hidden'); }

  function promptUser(options) {
    $('#prompt-eyebrow').textContent = options.eyebrow || 'WORKSPACE';
    $('#prompt-title').textContent = options.title || 'Nombre';
    $('#prompt-description').textContent = options.description || '';
    $('#prompt-label').textContent = options.label || 'Nombre';
    $('#prompt-input').value = options.value || '';
    $('#prompt-input').placeholder = options.placeholder || '';
    $('#prompt-submit').textContent = options.submitText || 'Aceptar';
    showDialog('prompt-dialog');
    requestAnimationFrame(() => { $('#prompt-input').focus(); $('#prompt-input').select(); });
    return new Promise((resolve) => { state.promptResolve = resolve; });
  }

  async function runSearch() {
    const query = $('#search-query').value.trim();
    if (!query) { $('#search-results').replaceChildren(); $('#search-summary').textContent = ''; return; }
    $('#search-summary').textContent = 'Buscando…';
    const params = new URLSearchParams({ q: query, max: '500' });
    if ($('#search-case').checked) params.set('case', '1');
    if ($('#search-regex').checked) params.set('regex', '1');
    if ($('#search-glob').value.trim()) params.set('glob', $('#search-glob').value.trim());
    try {
      const result = await api('/api/search?' + params.toString());
      renderSearchResults(result);
    } catch (error) {
      $('#search-summary').textContent = error.message;
      $('#search-results').replaceChildren();
    }
  }

  function renderSearchResults(result) {
    const container = $('#search-results');
    container.replaceChildren();
    const groups = new Map();
    for (const match of result.results || []) {
      if (!groups.has(match.file)) groups.set(match.file, []);
      groups.get(match.file).push(match);
    }
    $('#search-summary').textContent = `${result.results?.length || 0} coincidencia(s) en ${result.scanned || 0} archivo(s)${result.truncated ? ' · límite alcanzado' : ''}`;
    if (!groups.size) {
      const empty = document.createElement('div');
      empty.className = 'search-empty';
      empty.textContent = 'No se encontraron coincidencias.';
      container.append(empty);
      return;
    }
    for (const [file, matches] of groups) {
      const group = document.createElement('div');
      group.className = 'search-result-group';
      const heading = document.createElement('button');
      heading.type = 'button';
      heading.className = 'search-file-heading';
      heading.innerHTML = `${fileIconMarkup(file)}<strong></strong><span>${matches.length}</span>`;
      $('strong', heading).textContent = file;
      heading.addEventListener('click', () => openFile(file).catch((error) => toast(error.message, 'error')));
      group.append(heading);
      for (const match of matches) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'search-match';
        button.innerHTML = '<span class="search-line-number"></span><span class="search-match-text"></span>';
        $('.search-line-number', button).textContent = String(match.line);
        $('.search-match-text', button).textContent = match.text;
        button.title = `${file}:${match.line}`;
        button.addEventListener('click', () => openFile(file, match.line).catch((error) => toast(error.message, 'error')));
        group.append(button);
      }
      container.append(group);
    }
  }

  function switchView(view) {
    state.view = view;
    $$('.activity-button[data-view]').forEach((button) => button.classList.toggle('is-active', button.dataset.view === view));
    $('#explorer-view').classList.toggle('hidden', view !== 'explorer');
    $('#search-view').classList.toggle('hidden', view !== 'search');
    $('#snapshots-view').classList.toggle('hidden', view !== 'snapshots');
    $('#explorer-actions').classList.toggle('hidden', view !== 'explorer');
    $('#search-actions').classList.toggle('hidden', view !== 'search');
    $('#snapshot-actions').classList.toggle('hidden', view !== 'snapshots');
    $('#sidebar-title').textContent = view === 'explorer' ? 'EXPLORADOR' : view === 'search' ? 'BÚSQUEDA' : 'SNAPSHOTS';
    if (view === 'snapshots') refreshSnapshots().catch((error) => toast(error.message, 'error'));
    if (window.innerWidth <= 620) $('#workbench').classList.add('sidebar-open');
  }

  async function refreshSnapshots() {
    const result = await api('/api/snapshots');
    const container = $('#snapshot-list');
    container.replaceChildren();
    if (!result.snapshots?.length) {
      const empty = document.createElement('div');
      empty.className = 'search-empty';
      empty.textContent = 'Todavía no hay snapshots. Crea uno antes de cambios grandes.';
      container.append(empty);
      return;
    }
    for (const id of result.snapshots) {
      const card = document.createElement('article');
      card.className = 'snapshot-card';
      card.innerHTML = `<div class="snapshot-card-head"><svg viewBox="0 0 24 24"><path d="M4 7.5h16v12H4zM8 7.5V5h8v2.5M8 12h8m-8 3.5h5"/></svg><strong></strong></div><small></small><div class="snapshot-card-actions"><button type="button" class="snapshot-restore">Restaurar</button></div>`;
      $('strong', card).textContent = id;
      $('small', card).textContent = formatSnapshotDate(id);
      $('.snapshot-restore', card).addEventListener('click', () => restoreSnapshot(id));
      container.append(card);
    }
  }

  function formatSnapshotDate(id) {
    const match = String(id).match(/^(\d{4}-\d\d-\d\dT\d\d-\d\d-\d\d-\d{3})/);
    if (!match) return 'Punto de restauración del workspace';
    const date = new Date(match[1].replace(/T(\d\d)-(\d\d)-(\d\d)-(\d{3})/, 'T$1:$2:$3.$4Z'));
    return Number.isNaN(date.getTime()) ? 'Punto de restauración' : date.toLocaleString();
  }

  async function createSnapshot() {
    const label = await promptUser({
      eyebrow: 'PUNTO DE RESTAURACIÓN', title: 'Crear snapshot',
      description: 'Se guardará una copia de los archivos del workspace. node_modules, .git y carpetas de build se excluyen.',
      label: 'Etiqueta', value: 'manual', placeholder: 'antes de refactorizar', submitText: 'Crear snapshot'
    });
    if (label == null) return;
    try {
      const dirtyTabs = [...state.tabs.values()].filter((tab) => tab.dirty);
      if (dirtyTabs.length && !window.confirm(`Hay ${dirtyTabs.length} archivo(s) con cambios sin guardar. ¿Guardarlos antes del snapshot?`)) return;
      for (const tab of dirtyTabs) await saveTab(tab);
      const result = await api('/api/snapshot', { method: 'POST', body: { label } });
      toast(`Snapshot guardado: ${result.id}`);
      switchView('snapshots');
    } catch (error) { toast(error.message, 'error'); }
  }

  async function restoreSnapshot(id) {
    if (!window.confirm(`¿Restaurar el workspace desde «${id}»?\n\nSe creará automáticamente un snapshot de seguridad del estado actual.`)) return;
    if ([...state.tabs.values()].some((tab) => tab.dirty) && !window.confirm('Hay archivos con cambios sin guardar. ¿Descartarlos y continuar con la restauración?')) return;
    try {
      await closeAllTabs(true);
      const result = await api('/api/restore', { method: 'POST', body: { id } });
      state.expanded.clear();
      await refreshTree();
      await refreshSnapshots();
      toast(`Workspace restaurado. Copia previa: ${result.safetySnapshot}`);
      setStatus('Snapshot restaurado');
    } catch (error) { toast(error.message, 'error'); }
  }

  function flattenFiles(nodes, out = []) {
    for (const node of nodes) {
      if (node.type === 'file') out.push(node.path);
      else flattenFiles(node.children || [], out);
    }
    return out;
  }

  function refreshQuickFiles(query = $('#quick-open-input').value) {
    state.quickFiles = flattenFiles(state.tree).filter((path) => {
      if (!query) return true;
      const needle = query.toLowerCase();
      return path.toLowerCase().includes(needle);
    }).slice(0, 100);
    state.quickIndex = Math.min(state.quickIndex, Math.max(0, state.quickFiles.length - 1));
    renderQuickFiles();
  }

  function renderQuickFiles() {
    const container = $('#quick-open-results');
    container.replaceChildren();
    if (!state.quickFiles.length) {
      const empty = document.createElement('div');
      empty.className = 'quick-open-empty';
      empty.textContent = 'No hay archivos que coincidan. Crea uno desde el explorador.';
      container.append(empty);
      return;
    }
    state.quickFiles.forEach((path, index) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `quick-open-result ${index === state.quickIndex ? 'is-selected' : ''}`;
      button.innerHTML = `${fileIconMarkup(path)}<span class="quick-open-result-text"><strong></strong><small></small></span>`;
      $('strong', button).textContent = path.split('/').pop();
      $('small', button).textContent = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : 'workspace';
      button.addEventListener('mouseenter', () => { state.quickIndex = index; renderQuickFiles(); });
      button.addEventListener('click', () => openQuickFile(path));
      container.append(button);
    });
  }

  function openQuickFile(path) {
    closeDialog('quick-open-dialog');
    $('#quick-open-input').value = '';
    openFile(path).catch((error) => toast(error.message, 'error'));
  }

  function openQuickOpen() {
    refreshQuickFiles('');
    showDialog('quick-open-dialog');
    requestAnimationFrame(() => $('#quick-open-input').focus());
  }

  function providerOrigin(value) {
    try {
      const url = new URL(String(value || '').trim() || 'https://api.openai.com/v1');
      return ['http:', 'https:'].includes(url.protocol) ? url.origin : '';
    } catch { return ''; }
  }

  function setModelDiscoveryNote(message, kind = '') {
    const note = $('#model-discovery-note');
    note.textContent = message;
    note.classList.toggle('success', kind === 'success');
    note.classList.toggle('error', kind === 'error');
  }

  function resetModelCatalog(message = 'Busca el catálogo para elegir un modelo disponible. Si el proveedor marca alguno como gratis, CodeForge lo prioriza; también puedes escribir un ID manualmente.') {
    modelCatalogRevision++;
    $('#setting-model-options').replaceChildren();
    setModelDiscoveryNote(message);
  }

  function handleProviderUrlInput() {
    const nextOrigin = providerOrigin($('#setting-base-url').value);
    const keyInput = $('#setting-api-key');
    if (keyInput.value && nextOrigin && (!apiKeyOrigin || apiKeyOrigin !== nextOrigin)) {
      keyInput.value = '';
      apiKeyOrigin = '';
      toast('Cambió el host del proveedor: borré la API key anterior para no enviarla a otro servicio. Pega la key del nuevo proveedor.', 'warning', 6500);
    } else if (!keyInput.value) {
      apiKeyOrigin = nextOrigin;
    }
    resetModelCatalog('Cambió la URL del proveedor. Busca su catálogo; la selección manual sigue disponible.');
  }

  function fillSettingsForm() {
    const settings = state.settings;
    $('#setting-base-url').value = settings.baseUrl || 'https://api.openai.com/v1';
    $('#setting-model').value = settings.model || '';
    $('#setting-api-key').value = settings.apiKey || '';
    apiKeyOrigin = settings.apiKey ? providerOrigin(settings.baseUrl) : '';
    modelInputEdited = false;
    $('#setting-temperature').value = settings.temperature ?? 0.2;
    $('#setting-max-steps').value = settings.maxSteps || 16;
    $('#setting-tools').checked = settings.tools !== false;
    $('#setting-font-size').value = settings.fontSize || 14;
    $('#font-size-value').textContent = `${settings.fontSize || 14} px`;
    $('#setting-minimap').checked = settings.minimap !== false;
    $('#setting-autosave').checked = settings.autosave === true;
    $('#agent-model-label').textContent = settings.model || 'Configura un modelo';
    resetModelCatalog();
  }

  function readSettingsForm() {
    state.settings = {
      ...state.settings,
      baseUrl: $('#setting-base-url').value.trim() || 'https://api.openai.com/v1',
      model: $('#setting-model').value.trim(),
      apiKey: $('#setting-api-key').value.trim(),
      temperature: Math.min(Math.max(Number($('#setting-temperature').value) || 0, 0), 2),
      maxSteps: Math.min(Math.max(Number($('#setting-max-steps').value) || 16, 1), 40),
      tools: $('#setting-tools').checked,
      fontSize: Math.min(Math.max(Number($('#setting-font-size').value) || 14, 11), 22),
      minimap: $('#setting-minimap').checked,
      autosave: $('#setting-autosave').checked
    };
    persistSettings();
    fillSettingsForm();
    if (state.editor) {
      state.editor.updateOptions({ fontSize: state.settings.fontSize, lineHeight: Math.round(state.settings.fontSize * 1.65), minimap: { enabled: state.settings.minimap } });
      state.editor.layout();
    }
    toast('Ajustes guardados en este navegador.');
    closeDialog('settings-dialog');
  }

  function agentConfig() {
    const { baseUrl, model, apiKey, temperature, maxSteps, tools } = state.settings;
    return { baseUrl, model, apiKey, temperature, maxSteps, tools, requestTimeout: 180000 };
  }

  function agentConfigFromInputs() {
    return {
      ...agentConfig(),
      baseUrl: $('#setting-base-url').value.trim() || 'https://api.openai.com/v1',
      model: $('#setting-model').value.trim(),
      apiKey: $('#setting-api-key').value.trim(),
      temperature: Math.min(Math.max(Number($('#setting-temperature').value) || 0, 0), 2),
      maxSteps: Math.min(Math.max(Number($('#setting-max-steps').value) || 16, 1), 40),
      tools: $('#setting-tools').checked
    };
  }

  async function discoverAgentModels(providerLabel = 'este proveedor') {
    const button = $('#discover-models-button');
    const preset = $('#openrouter-preset-button');
    if (button.disabled) return;
    const config = agentConfigFromInputs();
    const revision = modelCatalogRevision;
    const oldButtonText = button.textContent;
    const oldPresetText = preset.textContent;
    button.disabled = true;
    preset.disabled = true;
    button.textContent = 'Buscando…';
    preset.textContent = 'Consultando catálogo…';
    setModelDiscoveryNote(`Consultando los modelos de ${providerLabel}…`);
    try {
      const response = await api('/api/agent/models', { method: 'POST', body: { config } });
      if (revision !== modelCatalogRevision || providerOrigin($('#setting-base-url').value) !== providerOrigin(config.baseUrl) || $('#setting-api-key').value.trim() !== config.apiKey) return;
      const models = Array.isArray(response.models) ? response.models : [];
      const list = $('#setting-model-options');
      list.replaceChildren();
      for (const model of models) {
        const option = document.createElement('option');
        option.value = model.id;
        option.label = model.free ? 'GRATIS según el catálogo del proveedor' : 'El proveedor no lo marca GRATIS';
        list.append(option);
      }

      const modelInput = $('#setting-model');
      const currentModel = modelInput.value.trim();
      const currentInfo = models.find((item) => item.id === currentModel);
      let selectedModel = currentModel;
      let autoSelected = false;
      if (models.length && !currentInfo && (!currentModel || !modelInputEdited)) {
        const choice = models.find((item) => item.free) || models[0];
        modelInput.value = choice.id;
        selectedModel = choice.id;
        modelInputEdited = false;
        autoSelected = true;
      }

      if (!models.length) {
        setModelDiscoveryNote(`El catálogo de ${providerLabel} respondió, pero no devolvió modelos. Puedes escribir el ID manualmente y probar la conexión.`, 'success');
        return;
      }
      const freeCount = Number(response.freeCount) || models.filter((item) => item.free).length;
      let detail;
      if (autoSelected) {
        const chosen = models.find((item) => item.id === selectedModel);
        detail = chosen?.free
          ? `Seleccioné automáticamente ${selectedModel}, marcado GRATIS por el catálogo.`
          : `Seleccioné automáticamente ${selectedModel}; el catálogo no marca ningún modelo como GRATIS.`;
      } else if (currentInfo) {
        detail = currentInfo.free
          ? `Se conserva ${selectedModel}, marcado GRATIS por el catálogo.`
          : `Se conserva ${selectedModel}; el proveedor no lo marca GRATIS.`;
      } else if (currentModel && modelInputEdited) {
        detail = `Conservé el ID escrito (${currentModel}); no aparece en el catálogo y no pude confirmar que esté disponible.`;
      } else {
        detail = `Modelo seleccionado: ${selectedModel}.`;
      }
      const freeSummary = freeCount
        ? `${freeCount} de ${models.length} modelos marcados GRATIS.`
        : `Ninguno de los ${models.length} modelos está marcado GRATIS; consulta los precios del proveedor.`;
      setModelDiscoveryNote(`Catálogo encontrado: ${models.length} modelos. ${freeSummary} ${detail}`, 'success');
    } catch (error) {
      if (revision !== modelCatalogRevision) return;
      const hint = error.status === 404
        ? ' El proveedor no publica un catálogo en /models; escribe el ID manualmente y prueba la conexión.'
        : error.status === 401 || error.status === 403
          ? ' Revisa que la API key corresponda a este proveedor.'
          : error.status === 502
            ? ' Verifica la URL base y que el servidor del proveedor esté accesible; también puedes introducir el ID manualmente.'
            : '';
      setModelDiscoveryNote(`${error.message}${hint}`, 'error');
    } finally {
      button.disabled = false;
      preset.disabled = false;
      button.textContent = oldButtonText;
      preset.textContent = oldPresetText;
    }
  }

  async function testAgentConnection() {
    const config = agentConfigFromInputs();
    if (!config.model) {
      $('#agent-test-result').className = 'agent-test-result error';
      $('#agent-test-result').textContent = 'Selecciona un modelo del catálogo o escribe su ID antes de probar.';
      return;
    }
    const button = $('#test-agent-button');
    const old = button.innerHTML;
    button.disabled = true;
    button.textContent = 'Probando conexión…';
    const result = $('#agent-test-result');
    result.className = 'agent-test-result hidden';
    try {
      const response = await api('/api/agent/test', { method: 'POST', body: { config } });
      result.className = 'agent-test-result success';
      result.textContent = `Conexión correcta · ${response.model} · herramientas ${response.tools ? 'disponibles' : 'no disponibles'}${response.reply ? ` · Respuesta: ${response.reply}` : ''}`;
    } catch (error) {
      result.className = 'agent-test-result error';
      result.textContent = error.message;
    } finally {
      button.disabled = false;
      button.innerHTML = old;
    }
  }

  function renderMarkdown(text) {
    const source = String(text || '').replace(/\r\n/g, '\n');
    const blocks = [];
    let safe = escapeHTML(source).replace(/```([\w-]*)\n?([\s\S]*?)```/g, (_all, lang, code) => {
      const index = blocks.push(`<pre><code${lang ? ` data-language="${escapeHTML(lang)}"` : ''}>${code.replace(/^\n|\n$/g, '')}</code></pre>`) - 1;
      return `\u0000BLOCK${index}\u0000`;
    });
    safe = safe.replace(/`([^`\n]+)`/g, '<code>$1</code>')
      .replace(/^###\s+(.+)$/gm, '<h3>$1</h3>')
      .replace(/^##\s+(.+)$/gm, '<h2>$1</h2>')
      .replace(/^#\s+(.+)$/gm, '<h1>$1</h1>')
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
    const lines = safe.split('\n');
    const output = [];
    let paragraph = [];
    let list = null;
    const flushParagraph = () => {
      if (paragraph.length) { output.push(`<p>${paragraph.join('<br>')}</p>`); paragraph = []; }
    };
    const flushList = () => {
      if (list) { output.push(`<${list.tag}>${list.items.map((item) => `<li>${item}</li>`).join('')}</${list.tag}>`); list = null; }
    };
    for (const line of lines) {
      const block = line.match(/^\u0000BLOCK(\d+)\u0000$/);
      if (block) { flushParagraph(); flushList(); output.push(blocks[Number(block[1])]); continue; }
      const heading = line.match(/^<h[1-3]>.*<\/h[1-3]>$/);
      if (heading) { flushParagraph(); flushList(); output.push(line); continue; }
      const bullet = line.match(/^\s*[-*]\s+(.+)$/);
      const ordered = line.match(/^\s*\d+[.)]\s+(.+)$/);
      if (bullet || ordered) {
        flushParagraph();
        const tag = ordered ? 'ol' : 'ul';
        if (list && list.tag !== tag) flushList();
        if (!list) list = { tag, items: [] };
        list.items.push((bullet || ordered)[1]);
        continue;
      }
      if (!line.trim()) { flushParagraph(); flushList(); continue; }
      flushList();
      paragraph.push(line);
    }
    flushParagraph(); flushList();
    return output.join('') || '<p></p>';
  }

  function appendAgentMessage(role, text) {
    $('#agent-welcome').classList.add('hidden');
    const card = document.createElement('article');
    card.className = `agent-message ${role}`;
    const label = document.createElement('div');
    label.className = 'message-role';
    const roleIcon = document.createElement('span');
    roleIcon.className = 'role-icon';
    roleIcon.innerHTML = role === 'assistant' ? ICONS.agent : '<svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3"/><path d="M5 20c.5-4 3-6 7-6s6.5 2 7 6"/></svg>';
    const roleText = document.createElement('span');
    roleText.textContent = role === 'assistant' ? 'CodeForge Agent' : 'Tú';
    label.append(roleIcon, roleText);
    const content = document.createElement('div');
    content.className = 'message-content';
    if (role === 'assistant') content.innerHTML = renderMarkdown(text);
    else content.textContent = text;
    card.append(label, content);
    $('#agent-transcript').append(card);
    scrollAgentToBottom();
    return { card, content };
  }

  function scrollAgentToBottom() {
    const transcript = $('#agent-transcript');
    transcript.scrollTop = transcript.scrollHeight;
  }

  function showAgentStatus(text) {
    let status = $('.agent-status', $('#agent-transcript'));
    if (!status) {
      status = document.createElement('div');
      status.className = 'agent-status';
      $('#agent-transcript').append(status);
    }
    status.innerHTML = `<span class="typing-dots"><i></i><i></i><i></i></span><span></span>`;
    $('span:last-child', status).textContent = text;
    scrollAgentToBottom();
  }

  function clearAgentStatus() { $('.agent-status', $('#agent-transcript'))?.remove(); }

  function appendToolEvent(name, args, id) {
    const card = document.createElement('div');
    card.className = 'agent-tool-event is-running';
    card.dataset.toolId = id;
    const head = document.createElement('div');
    head.className = 'agent-tool-event-head';
    head.innerHTML = `${ICONS.tool}<strong></strong><span></span>`;
    $('strong', head).textContent = name || 'herramienta';
    $('span', head).textContent = 'ejecutando';
    const detail = document.createElement('div');
    detail.className = 'agent-tool-event-detail';
    detail.textContent = JSON.stringify(args || {}).slice(0, 260);
    card.append(head, detail);
    $('#agent-transcript').append(card);
    scrollAgentToBottom();
    return card;
  }

  async function sendAgentMessage(message) {
    const text = String(message || '').trim();
    if (!text) return;
    if (state.agentBusy) {
      state.agentAbort?.abort();
      return;
    }
    if (!state.settings.model) {
      toast('Configura el proveedor y el modelo del agente primero.', 'warning');
      showAgentSettings();
      return;
    }
    const dirtyTabs = [...state.tabs.values()].filter((tab) => tab.dirty);
    if (dirtyTabs.length && !window.confirm(`El agente trabaja sobre los archivos guardados en el servidor. ¿Guardar primero los ${dirtyTabs.length} archivo(s) con cambios pendientes?`)) return;
    try {
      for (const tab of dirtyTabs) await saveTab(tab);
    } catch (error) {
      toast(error.message, 'error');
      return;
    }
    appendAgentMessage('user', text);
    $('#agent-input').value = '';
    $('#agent-input').style.height = '';
    state.agentBusy = true;
    const controller = new AbortController();
    state.agentAbort = controller;
    $('#agent-send-button').innerHTML = '<svg viewBox="0 0 24 24"><path d="M7 7h10v10H7z"/></svg>';
    $('#agent-send-button').setAttribute('aria-label', 'Detener respuesta');
    showAgentStatus('Pensando…');
    const toolEvents = new Map();
    let assistantMessage = null;
    let assistantText = '';
    try {
      const response = await fetch('/api/agent/chat', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
        body: JSON.stringify({ sessionId: state.agentSessionId, message: text, config: agentConfig(), openFile: state.activePath || '' }),
        signal: controller.signal
      });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new ApiError(body.error || `Error HTTP ${response.status}`, response.status);
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let done = false;
      while (!done) {
        const chunk = await reader.read();
        done = chunk.done;
        buffer += decoder.decode(chunk.value || new Uint8Array(), { stream: !done });
        const events = buffer.split(/\r?\n\r?\n/);
        buffer = events.pop() || '';
        for (const raw of events) {
          const data = raw.split(/\r?\n/).filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).join('\n');
          if (!data) continue;
          let event;
          try { event = JSON.parse(data); } catch { continue; }
          await handleAgentEvent(event, toolEvents, (text) => {
            assistantText += (assistantText ? '\n\n' : '') + text;
            if (!assistantMessage) assistantMessage = appendAgentMessage('assistant', '');
            assistantMessage.content.innerHTML = renderMarkdown(assistantText);
          });
          if (event.type === 'final') {
            if (!assistantMessage) assistantMessage = appendAgentMessage('assistant', event.text || '');
            else {
              const finalText = event.text || '';
              const combined = assistantText && finalText && !assistantText.endsWith(finalText) ? `${assistantText}\n\n${finalText}` : finalText || assistantText;
              assistantText = combined;
              assistantMessage.content.innerHTML = renderMarkdown(combined);
            }
          }
        }
      }
      if (buffer.trim().startsWith('data:')) {
        try { await handleAgentEvent(JSON.parse(buffer.trim().slice(5)), toolEvents, () => {}); } catch { /* final frame */ }
      }
    } catch (error) {
      if (error.name === 'AbortError') {
        if (!assistantMessage) assistantMessage = appendAgentMessage('assistant', 'Respuesta detenida.');
        else assistantMessage.content.innerHTML = renderMarkdown((assistantText ? assistantText + '\n\n' : '') + 'Respuesta detenida.');
      } else {
        const box = document.createElement('div');
        box.className = 'agent-error';
        box.textContent = error.message || String(error);
        $('#agent-transcript').append(box);
        toast(error.message || 'Falló el agente.', 'error');
      }
    } finally {
      clearAgentStatus();
      state.agentBusy = false;
      state.agentAbort = null;
      $('#agent-send-button').innerHTML = '<svg viewBox="0 0 24 24"><path d="m21 3-7.5 18-3.2-7.3L3 10.5z"/><path d="M10.3 13.7 21 3"/></svg>';
      $('#agent-send-button').setAttribute('aria-label', 'Enviar mensaje');
      $('#agent-input').focus();
      scrollAgentToBottom();
    }
  }

  async function handleAgentEvent(event, toolEvents, addAssistantText) {
    switch (event.type) {
      case 'step': showAgentStatus(`Paso ${event.step} de ${event.max}…`); break;
      case 'notice': showAgentStatus(event.text || 'Nota del proveedor'); break;
      case 'assistant_text': clearAgentStatus(); addAssistantText(event.text || ''); break;
      case 'tool_start': {
        clearAgentStatus();
        const card = appendToolEvent(event.name, event.args, event.id);
        toolEvents.set(event.id, card);
        showAgentStatus(`Ejecutando ${event.name}…`);
        break;
      }
      case 'tool_end': {
        const card = toolEvents.get(event.id);
        if (card) {
          card.classList.remove('is-running');
          const label = $('.agent-tool-event-head span', card);
          if (label) label.textContent = 'completado';
          const detail = $('.agent-tool-event-detail', card);
          if (detail) detail.textContent = String(event.preview || '').slice(0, 280);
        }
        clearAgentStatus();
        break;
      }
      case 'usage': showAgentStatus(`Tokens usados: ${event.total || event.usage?.total_tokens || 0}`); break;
      case 'ui': await handleAgentUi(event.ui); break;
      case 'error': {
        clearAgentStatus();
        const box = document.createElement('div');
        box.className = 'agent-error';
        box.textContent = event.text || 'Error del agente.';
        $('#agent-transcript').append(box);
        break;
      }
      case 'final': clearAgentStatus(); break;
      case 'done': clearAgentStatus(); break;
    }
    scrollAgentToBottom();
  }

  async function reloadCleanTab(path) {
    const tab = state.tabs.get(path);
    if (!tab || tab.dirty) return false;
    const fresh = await api(`/api/file?path=${encodeURIComponent(path)}`);
    tab.content = fresh.content;
    tab.savedContent = fresh.content;
    tab.version = fresh.version;
    tab.language = fresh.language || languageFor(path)[0];
    if (tab.model) {
      if (tab.model.getValue() !== fresh.content) tab.model.setValue(fresh.content);
      window.monaco?.editor.setModelLanguage(tab.model, tab.language);
    }
    renderTabs();
    updateEditorStatus();
    return true;
  }

  async function refreshOpenTabs() {
    let preservedDirty = false;
    for (const [path, tab] of [...state.tabs]) {
      if (tab.dirty) { preservedDirty = true; continue; }
      try { await reloadCleanTab(path); }
      catch (error) {
        if (error.status === 404) await closeTab(path, true);
        else throw error;
      }
    }
    if (preservedDirty) toast('Hay cambios locales sin guardar; se conservaron. Revisa posibles conflictos con las acciones del agente.', 'warning', 6000);
  }

  async function handleAgentUi(ui) {
    if (!ui) return;
    if (ui.kind === 'move' && ui.from && ui.to) {
      const affected = [...state.tabs.values()].filter((tab) => tab.path === ui.from || tab.path.startsWith(ui.from + '/'));
      const nextActive = state.activePath === ui.from || state.activePath?.startsWith(ui.from + '/')
        ? ui.to + state.activePath.slice(ui.from.length) : state.activePath;
      for (const tab of affected) {
        state.tabs.delete(tab.path);
        tab.model?.dispose();
        tab.model = null;
        tab.path = tab.path === ui.from ? ui.to : ui.to + tab.path.slice(ui.from.length);
        tab.name = tab.path.split('/').pop();
        state.tabs.set(tab.path, tab);
      }
      state.activePath = nextActive;
      await refreshTree();
      for (const tab of affected) if (!tab.dirty) await reloadCleanTab(tab.path).catch((error) => toast(error.message, 'error'));
      if (state.activePath && state.tabs.has(state.activePath)) await activateTab(state.activePath);
      renderTabs();
      toast(`Ruta movida: ${ui.to}`);
    } else if (ui.kind === 'open' && ui.path) await openFile(ui.path, ui.line).catch((error) => toast(error.message, 'error'));
    else if (ui.kind === 'file' && ui.path) {
      await refreshTree();
      const existing = state.tabs.get(ui.path);
      if (existing?.dirty) toast(`El agente modificó ${ui.path}; tu buffer tiene cambios sin guardar y se mantiene local.`, 'warning', 6000);
      else if (existing) await reloadCleanTab(ui.path).catch((error) => toast(error.message, 'error'));
      if (ui.open) await openFile(ui.path).catch((error) => toast(error.message, 'error'));
    } else if (ui.kind === 'tree') {
      await refreshTree();
      await refreshOpenTabs().catch((error) => toast(error.message, 'error'));
    } else if (ui.kind === 'ran') {
      showPanel('output');
      if (ui.run) renderRunOutput(ui.run);
    }
  }

  function showAgentSettings() {
    fillSettingsForm();
    $('#agent-test-result').className = 'agent-test-result hidden';
    showDialog('settings-dialog');
    requestAnimationFrame(() => $('#setting-model').focus());
  }

  function toggleAgent() {
    if (window.innerWidth <= 860) {
      $('#workbench').classList.toggle('agent-open');
    } else {
      $('#workbench').classList.toggle('agent-collapsed');
    }
  }

  function bindEvents() {
    $('#toggle-sidebar').addEventListener('click', () => $('#workbench').classList.toggle('sidebar-open'));
    $('#new-file-button').addEventListener('click', () => createFile(false));
    $('#empty-create-file').addEventListener('click', () => createFile(false, ''));
    $('#welcome-new-file').addEventListener('click', () => createFile(false, ''));
    $('#new-folder-button').addEventListener('click', () => createFile(true));
    $('#refresh-tree-button').addEventListener('click', () => refreshTree().then(() => toast('Explorador actualizado.')).catch((error) => toast(error.message, 'error')));
    $('#root-menu-button').addEventListener('click', (event) => { state.selectedDir = ''; const rect = event.currentTarget.getBoundingClientRect(); showContextMenu(rect.right - 10, rect.bottom, null); });
    $('#file-tree').addEventListener('contextmenu', (event) => {
      if (event.target.closest('.tree-row')) return;
      event.preventDefault();
      state.selectedDir = '';
      showContextMenu(event.clientX, event.clientY, null);
    });
    $('#save-button').addEventListener('click', saveActive);
    $('#run-button').addEventListener('click', runActiveFile);
    $('#quick-open-button').addEventListener('click', openQuickOpen);
    $('#toggle-terminal-button').addEventListener('click', () => { if (!state.panelVisible) showPanel('terminal'); else togglePanel(); });
    $('#welcome-open-terminal').addEventListener('click', () => showPanel('terminal'));
    $('#toggle-agent-button').addEventListener('click', toggleAgent);
    $('#open-agent-button').addEventListener('click', toggleAgent);
    $('#activity-agent').addEventListener('click', toggleAgent);
    $('#close-agent-button').addEventListener('click', () => {
      if (window.innerWidth <= 860) $('#workbench').classList.remove('agent-open');
      else { $('#workbench').style.gridTemplateColumns = 'var(--sidebar-width) minmax(0,1fr) 0px'; $('#agent-panel').classList.add('hidden'); }
    });
    $('#settings-button').addEventListener('click', showAgentSettings);
    $('#agent-settings-button').addEventListener('click', showAgentSettings);
    $('#agent-welcome-settings').addEventListener('click', showAgentSettings);
    $('#agent-form').addEventListener('submit', (event) => { event.preventDefault(); sendAgentMessage($('#agent-input').value); });
    $('#agent-input').addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); sendAgentMessage($('#agent-input').value); }
    });
    $('#agent-input').addEventListener('input', (event) => {
      const textarea = event.currentTarget;
      textarea.style.height = 'auto';
      textarea.style.height = `${Math.min(textarea.scrollHeight, 180)}px`;
    });
    $$('.suggestion-chip').forEach((button) => button.addEventListener('click', () => { $('#agent-input').value = button.dataset.prompt; $('#agent-input').focus(); }));
    $('#agent-new-chat-button').addEventListener('click', async () => {
      if (state.agentBusy) state.agentAbort?.abort();
      try { await api('/api/agent/reset', { method: 'POST', body: { sessionId: state.agentSessionId } }); } catch { /* clear local view anyway */ }
      $('#agent-transcript').replaceChildren();
      const welcome = $('#agent-welcome');
      welcome.classList.remove('hidden');
      $('#agent-transcript').append(welcome);
      toast('Conversación nueva.');
    });
    $('#activity-agent').setAttribute('aria-pressed', 'false');
    $('#search-form').addEventListener('submit', (event) => { event.preventDefault(); runSearch(); });
    $('#search-query').addEventListener('input', () => {
      clearTimeout(state.searchTimer);
      state.searchTimer = setTimeout(() => { if ($('#search-query').value.trim()) runSearch(); else { $('#search-results').replaceChildren(); $('#search-summary').textContent = ''; } }, 280);
    });
    $('#clear-search-button').addEventListener('click', () => { $('#search-query').value = ''; $('#search-results').replaceChildren(); $('#search-summary').textContent = ''; $('#search-query').focus(); });
    $('#create-snapshot-button').addEventListener('click', createSnapshot);
    $('#refresh-snapshots-button').addEventListener('click', () => refreshSnapshots().catch((error) => toast(error.message, 'error')));
    $$('.activity-button[data-view]').forEach((button) => button.addEventListener('click', () => switchView(button.dataset.view)));
    $$('.bottom-tab').forEach((button) => button.addEventListener('click', () => showPanel(button.dataset.panel)));
    $('#new-terminal-button').addEventListener('click', () => { showPanel('terminal'); createTerminal(); });
    $('#clear-output-button').addEventListener('click', () => {
      if (state.panel === 'terminal') state.terminals.find((term) => term.id === state.activeTerminalId)?.terminal.clear();
      else $('#run-output').replaceChildren(Object.assign(document.createElement('div'), { className: 'output-empty', textContent: 'La salida de ejecución aparecerá aquí.' }));
    });
    $('#maximize-panel-button').addEventListener('click', toggleMaxPanel);
    $('#close-panel-button').addEventListener('click', hidePanel);
    $('#panel-resize-handle').addEventListener('mousedown', startPanelResize);
    $('#panel-resize-handle').addEventListener('keydown', (event) => {
      if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
        const current = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--bottom-height'), 10) || 224;
        const delta = event.key === 'ArrowUp' ? 20 : -20;
        document.documentElement.style.setProperty('--bottom-height', `${Math.max(120, Math.min(window.innerHeight - 180, current + delta))}px`);
        window.setTimeout(() => { state.editor?.layout(); fitActiveTerminal(); }, 50);
      }
    });
    $('#prompt-form').addEventListener('submit', (event) => {
      event.preventDefault();
      const value = $('#prompt-input').value;
      closeDialog('prompt-dialog');
      const resolve = state.promptResolve;
      state.promptResolve = null;
      resolve?.(value);
    });
    $('#prompt-dialog').addEventListener('close', () => {
      if (state.promptResolve) { state.promptResolve(null); state.promptResolve = null; }
    });
    $('#settings-form').addEventListener('submit', (event) => { event.preventDefault(); readSettingsForm(); });
    $('#setting-font-size').addEventListener('input', (event) => { $('#font-size-value').textContent = `${event.target.value} px`; });
    $('#setting-base-url').addEventListener('input', handleProviderUrlInput);
    $('#setting-api-key').addEventListener('input', (event) => {
      apiKeyOrigin = event.currentTarget.value ? providerOrigin($('#setting-base-url').value) : '';
      resetModelCatalog('Cambió la API key. Vuelve a buscar el catálogo si este proveedor la requiere.');
    });
    $('#setting-model').addEventListener('input', () => {
      modelInputEdited = true;
      $('#agent-test-result').className = 'agent-test-result hidden';
    });
    $('#discover-models-button').addEventListener('click', () => discoverAgentModels());
    $('#openrouter-preset-button').addEventListener('click', () => {
      $('#setting-base-url').value = 'https://openrouter.ai/api/v1';
      handleProviderUrlInput();
      discoverAgentModels('OpenRouter');
    });
    $('#test-agent-button').addEventListener('click', testAgentConnection);
    $('#toggle-api-key').addEventListener('click', (event) => {
      const input = $('#setting-api-key');
      input.type = input.type === 'password' ? 'text' : 'password';
      event.currentTarget.textContent = input.type === 'password' ? 'Mostrar' : 'Ocultar';
    });
    $$('.modal [data-close-dialog]').forEach((button) => button.addEventListener('click', () => closeDialog(button.dataset.closeDialog)));
    $('#quick-open-input').addEventListener('input', (event) => { state.quickIndex = 0; refreshQuickFiles(event.target.value); });
    $('#quick-open-input').addEventListener('keydown', (event) => {
      if (event.key === 'ArrowDown') { event.preventDefault(); state.quickIndex = Math.min(state.quickIndex + 1, state.quickFiles.length - 1); renderQuickFiles(); }
      if (event.key === 'ArrowUp') { event.preventDefault(); state.quickIndex = Math.max(0, state.quickIndex - 1); renderQuickFiles(); }
      if (event.key === 'Enter' && state.quickFiles[state.quickIndex]) { event.preventDefault(); openQuickFile(state.quickFiles[state.quickIndex]); }
      if (event.key === 'Escape') closeDialog('quick-open-dialog');
    });
    $('#quick-open-dialog').addEventListener('click', (event) => { if (event.target === $('#quick-open-dialog')) closeDialog('quick-open-dialog'); });
    document.addEventListener('click', (event) => {
      if (!event.target.closest('#context-menu') && !event.target.closest('#root-menu-button')) hideContextMenu();
      if (window.innerWidth <= 620 && $('#workbench').classList.contains('sidebar-open') && !event.target.closest('.sidebar') && !event.target.closest('#toggle-sidebar')) $('#workbench').classList.remove('sidebar-open');
    });
    document.addEventListener('keydown', handleGlobalKeydown);
    window.addEventListener('beforeunload', (event) => {
      if ([...state.tabs.values()].some((tab) => tab.dirty)) { event.preventDefault(); event.returnValue = ''; }
    });
    window.addEventListener('resize', () => { state.editor?.layout(); fitActiveTerminal(); });
  }

  function startPanelResize(event) {
    event.preventDefault();
    const startY = event.clientY;
    const startHeight = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--bottom-height'), 10) || 224;
    const handle = $('#panel-resize-handle');
    handle.classList.add('is-dragging');
    const move = (moveEvent) => {
      const next = Math.max(115, Math.min(window.innerHeight - 180, startHeight + startY - moveEvent.clientY));
      document.documentElement.style.setProperty('--bottom-height', `${next}px`);
      state.editor?.layout();
      fitActiveTerminal();
    };
    const done = () => {
      handle.classList.remove('is-dragging');
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', done);
    };
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', done);
  }

  function handleGlobalKeydown(event) {
    const command = event.ctrlKey || event.metaKey;
    if (command && event.key.toLowerCase() === 's') { event.preventDefault(); saveActive(); }
    if (command && event.key.toLowerCase() === 'p') { event.preventDefault(); openQuickOpen(); }
    if (command && event.shiftKey && event.key.toLowerCase() === 'f') { event.preventDefault(); switchView('search'); $('#search-query').focus(); }
    if (command && event.key === '`') { event.preventDefault(); showPanel('terminal'); state.terminals.find((term) => term.id === state.activeTerminalId)?.terminal.focus(); }
    if (event.key === 'F5') { event.preventDefault(); runActiveFile(); }
    if (event.key === 'Escape') hideContextMenu();
  }

  function openQuickOpenWithData() { openQuickOpen(); }

  function start() {
    initialize().catch((error) => {
      console.error(error);
      toast(error.message || 'No se pudo iniciar CodeForge.', 'error', 7000);
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true });
  else start();
})();
