#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  if command -v pkg >/dev/null 2>&1; then
    echo "Termux detectado: instalando Node.js LTS..."
    pkg update -y
    pkg install -y nodejs-lts
  else
    echo "Error: Node.js (>= 18.18) y npm son necesarios para ejecutar Strata Engineering."
    exit 1
  fi
fi

if [ ! -d "node_modules" ]; then
  echo "Instalando dependencias de Strata Engineering (sin módulos nativos opcionales)..."
  npm install --omit=optional --no-audit --no-fund
fi

export PORT="${PORT:-5000}"
export HOST="${HOST:-0.0.0.0}"
echo "Iniciando Strata Engineering... Abre http://127.0.0.1:${PORT} en tu navegador."
exec node server/index.js
