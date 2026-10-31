#!/data/data/com.termux/files/usr/bin/bash
# MiRed — arrancar todos los servicios
set -e
cd "$(dirname "$0")"

if [ -f data/mired.pid ] && kill -0 "$(cat data/mired.pid)" 2>/dev/null; then
  echo "MiRed ya está en marcha (PID $(cat data/mired.pid)). Usa ./estado.sh"
  exit 0
fi

# Evita que Android duerma el teléfono mientras sirve la red
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true

mkdir -p data
nohup python3 -m mired > data/mired.log 2>&1 &
echo $! > data/mired.pid

# Esperar a que el portal responda
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if python3 -c "
import socket, sys
s = socket.socket()
s.settimeout(1)
try:
    s.connect(('127.0.0.1', __import__('json').load(open('config.json'))['puerto_http']))
    sys.exit(0)
except OSError:
    sys.exit(1)
" 2>/dev/null; then
    break
  fi
  sleep 0.5
done

echo "✅ MiRed está en marcha. Detalles:"
./estado.sh
