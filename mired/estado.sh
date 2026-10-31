#!/data/data/com.termux/files/usr/bin/bash
# MiRed — mostrar estado de la red y de los servicios
cd "$(dirname "$0")"

IP=$(python3 -c "from mired import netinfo; print(netinfo.ip_local())" 2>/dev/null || echo "?")
ZONA=$(python3 -c "import json; print(json.load(open('config.json')).get('zona','mired'))" 2>/dev/null || echo "mired")
PUERTO=$(python3 -c "import json; print(json.load(open('config.json')).get('puerto_http',8080))" 2>/dev/null || echo "8080")
PUERTO_DNS=$(python3 -c "import json; print(json.load(open('config.json')).get('puerto_dns',5353))" 2>/dev/null || echo "5353")
PUERTO_HTTPS=$(python3 -c "import json; print(json.load(open('config.json')).get('puerto_https',8443))" 2>/dev/null || echo "8443")

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
echo "  Chat      http://$IP:$PUERTO/chat/     (o http://chat.$ZONA:$PUERTO/)"
echo "  Correo    http://$IP:$PUERTO/correo/   (o http://correo.$ZONA:$PUERTO/)"
echo "  Foro      http://$IP:$PUERTO/foro/     (o http://foro.$ZONA:$PUERTO/)"
echo "  Nube      http://$IP:$PUERTO/nube/     (o http://nube.$ZONA:$PUERTO/)"
echo "  Streaming http://$IP:$PUERTO/streaming/ (o http://streaming.$ZONA:$PUERTO/)"
echo "  Juegos    http://$IP:$PUERTO/juegos/   (o http://juegos.$ZONA:$PUERTO/)"
echo "  API       http://$IP:$PUERTO/api/      (o http://api.$ZONA:$PUERTO/)"
echo ""
echo "Nombres sin escribir la IP (mDNS — sin configurar nada):"
echo "  http://wiki.$ZONA.local:$PUERTO/   http://chat.$ZONA.local:$PUERTO/   …"
echo ""
echo "Otros servicios:"
echo "  DNS       udp/$PUERTO_DNS  →  *.$ZONA y *.$ZONA.local"
echo "  SMTP      $IP:2525  (para clientes de correo)"
echo "  HTTPS     https://$IP:$PUERTO_HTTPS  (certificado en /sesion/ca.crt)"
echo ""
echo "Últimas líneas del log:"
tail -n 8 data/mired.log 2>/dev/null || echo "  (sin log todavía)"
