#!/data/data/com.termux/files/usr/bin/bash
# MiRed — instalador para Termux (sin root)
# Ejecutar:  ./instalar.sh

set -e
cd "$(dirname "$0")"

echo "══════════════════════════════════════════"
echo "  🌐 MiRed — instalación en Termux"
echo "══════════════════════════════════════════"

case "$PREFIX" in
  *com.termux*) ;;
  *)
    echo "⚠ Este instalador está pensado para Termux (no se detectó \$PREFIX de Termux)."
    echo "  Continuaré de todos modos, pero algunos pasos pueden fallar."
    ;;
esac

echo ""
echo "▸ Actualizando paquetes…"
pkg update -y || true

echo ""
echo "▸ Instalando Python y openssl (para HTTPS)…"
pkg install -y python openssl

# Opcional: termux-api para termux-wake-lock (mantiene el teléfono despierto)
if ! command -v termux-wake-lock >/dev/null 2>&1; then
  echo "▸ Instalando termux-api (opcional, para mantener el teléfono despierto)…"
  pkg install -y termux-api || echo "  (opcional: se omite)"
fi

echo ""
echo "▸ Inicializando datos de MiRed…"
python3 -m mired --init

echo ""
echo "▸ Generando hosts.txt para tus computadoras…"
IP=$(python3 -c "from mired import netinfo; print(netinfo.ip_local())")
ZONA=$(python3 -c "import json; print(json.load(open('config.json')).get('zona','mired'))")
PUERTO=$(python3 -c "import json; print(json.load(open('config.json')).get('puerto_http',8080))")
{
  echo "# Agrega estas líneas al archivo hosts de tus computadoras"
  echo "# (Windows: C:\\Windows\\System32\\drivers\\etc\\hosts · Linux/macOS: /etc/hosts)"
  echo "$IP inicio.$ZONA buscar.$ZONA wiki.$ZONA chat.$ZONA correo.$ZONA foro.$ZONA nube.$ZONA streaming.$ZONA $ZONA"
} > hosts.txt
cat hosts.txt

echo ""
echo "✅ ¡Instalación completa!"
echo ""
echo "Para arrancar tu internet:   ./iniciar.sh"
echo "Para ver el estado:          ./estado.sh"
echo "Para detener:                ./detener.sh"
