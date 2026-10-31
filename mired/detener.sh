#!/data/data/com.termux/files/usr/bin/bash
# MiRed — detener todos los servicios
cd "$(dirname "$0")"

if [ -f data/mired.pid ]; then
  PID="$(cat data/mired.pid)"
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    sleep 1
    kill -9 "$PID" 2>/dev/null || true
    echo "■ MiRed detenida (PID $PID)."
  else
    echo "MiRed no estaba en marcha."
  fi
  rm -f data/mired.pid
else
  echo "MiRed no estaba en marcha."
fi

command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock || true
