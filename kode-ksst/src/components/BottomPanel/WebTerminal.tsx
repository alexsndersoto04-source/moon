import React, { useEffect, useRef, useState } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { RotateCw, Trash2 } from 'lucide-react';
import { useIdeStore } from '../../store/useIdeStore';

export const WebTerminal: React.FC = () => {
  const containerRef = useRef<HTMLDivElement>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const termRef = useRef<Terminal | null>(null);
  const { activeProjectId } = useIdeStore();
  const [sessionKey, setSessionKey] = useState(1);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    if (!containerRef.current || !activeProjectId) return;

    const term = new Terminal({
      cursorBlink: true,
      fontSize: 12.5,
      fontFamily: '"JetBrains Mono", "Fira Code", ui-monospace, monospace',
      theme: {
        background: '#080c13',
        foreground: '#e2e8f0',
        cursor: '#38bdf8',
        selectionBackground: '#0284c755',
      },
      convertEol: true,
    });
    const fitAddon = new FitAddon();
    term.loadAddon(fitAddon);
    term.open(containerRef.current);
    setTimeout(() => {
      try {
        fitAddon.fit();
      } catch {}
    }, 50);

    termRef.current = term;

    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${proto}//${window.location.host}/ws/terminal?projectId=${encodeURIComponent(activeProjectId)}`;
    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    ws.onopen = () => {
      setConnected(true);
      fitAddon.fit();
      ws.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows }));
    };

    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data);
        if (msg.type === 'output') {
          term.write(msg.data);
        } else if (msg.type === 'exit') {
          term.writeln(`\r\n\x1b[33m[Process exited with code ${msg.code}]\x1b[0m`);
          setConnected(false);
        }
      } catch {}
    };

    ws.onclose = () => setConnected(false);

    const onDataDisp = term.onData((data) => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'input', data }));
      }
    });

    const resizeObserver = new ResizeObserver(() => {
      try {
        fitAddon.fit();
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows }));
        }
      } catch {}
    });
    resizeObserver.observe(containerRef.current);

    return () => {
      resizeObserver.disconnect();
      onDataDisp.dispose();
      ws.close();
      term.dispose();
    };
  }, [activeProjectId, sessionKey]);

  return (
    <div className="flex flex-col h-full bg-[#080c13]">
      <div className="flex items-center justify-between px-3 py-1 border-b border-slate-800/70 text-[11px] text-slate-400 shrink-0">
        <div className="flex items-center gap-2">
          <span className={`w-2 h-2 rounded-full ${connected ? 'bg-emerald-400' : 'bg-rose-400'}`} />
          <span className="font-mono">bash · /workspaces/{activeProjectId}</span>
        </div>
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => termRef.current?.clear()}
            className="flex items-center gap-1 px-2 py-0.5 rounded hover:bg-slate-800 text-slate-400 hover:text-slate-200"
            title="Limpiar terminal"
          >
            <Trash2 className="w-3 h-3" />
            <span>Limpiar</span>
          </button>
          <button
            onClick={() => setSessionKey((k) => k + 1)}
            className="flex items-center gap-1 px-2 py-0.5 rounded hover:bg-slate-800 text-slate-400 hover:text-slate-200"
            title="Reiniciar sesión PTY"
          >
            <RotateCw className="w-3 h-3" />
            <span>Reiniciar</span>
          </button>
        </div>
      </div>
      <div ref={containerRef} className="flex-1 min-h-0 p-1.5" />
    </div>
  );
};
