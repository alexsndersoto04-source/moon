#!/data/data/com.termux/files/usr/bin/bash
# MiRed — mostrar estado de la red y de los servicios
cd "$(dirname "$0")"

IP=$(python3 -c "from mired import netinfo; print(netinfo.ip_local())" 2>/dev/null || echo "?")
ZONA=$(python3 -c "import json; print(json.load(open('config.json')).get('zona','mired'))" 2>/dev/null || echo "mired")
PUERTO=$(python3 -c "import json; print(json.load(open('config.json')).get('puerto_http',8080))" 2>/dev/null || echo "8080")

echo "══════════════════════════════════════════"
echo "  🌐 MiRed — estado"
echo "══════════════════════════════════════════"

if [ -f data/mired.pid ] && kill -0 "$(cat data/mired.pid)" 2>/dev/null; then
  echo "Estado:   ✅ en marcha (PID $(cat data/mired.pid))"
else
  echo "Estado:   ⬜ detenida (arranca con ./iniciar.sh)"
fi

echo ""
echo "URLs para los dispositivos conectados:"
echo "  Portal    http://$IP:$PUERTO/"
echo "  Wiki      http://$IP:$PUERTO/wiki/     (o http://wiki.$ZONA:$PUERTO/)"
echo "  Buscador  http://$IP:$PUERTO/buscar/   (o http://buscar.$ZONA:$PUERTO/)"
echo ""
echo "Últimas líneas del log:"
tail -n 8 data/mired.log 2>/dev/null || echo "  (sin log todavía)"
