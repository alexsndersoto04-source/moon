import type * as MonacoNS from 'monaco-editor';

let monacoPromise: Promise<typeof MonacoNS> | null = null;

export function loadMonaco(): Promise<typeof MonacoNS> {
  if (monacoPromise) return monacoPromise;

  monacoPromise = new Promise((resolve, reject) => {
    const win = window as any;
    if (win.monaco?.editor) {
      resolve(win.monaco);
      return;
    }

    const startRequire = () => {
      const req = win.require;
      if (!req) {
        reject(new Error('Monaco AMD loader failed to initialize'));
        return;
      }
      req.config({ paths: { vs: '/monaco-vs' } });
      win.MonacoEnvironment = {
        getWorkerUrl(_moduleId: string, label: string) {
          const base = `${window.location.origin}/monaco-vs`;
          const code = `
            self.MonacoEnvironment = { baseUrl: '${base}/' };
            importScripts('${base}/base/worker/workerMain.js');
          `;
          return `data:text/javascript;charset=utf-8,${encodeURIComponent(code)}`;
        },
      };
      req(['vs/editor/editor.main'], () => {
        resolve(win.monaco);
      });
    };

    const existingScript = document.querySelector('script[data-monaco-loader]');
    if (existingScript) {
      startRequire();
      return;
    }

    const script = document.createElement('script');
    script.src = '/monaco-vs/loader.js';
    script.async = true;
    script.setAttribute('data-monaco-loader', 'true');
    script.onload = startRequire;
    script.onerror = () => reject(new Error('Failed to load /monaco-vs/loader.js'));
    document.head.appendChild(script);
  });

  return monacoPromise;
}
