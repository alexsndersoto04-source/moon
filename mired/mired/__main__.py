"""Punto de entrada de MiRed: ``python3 -m mired``.

Arranca todos los servicios de la Fase 1 en un solo proceso:
portal + wiki + buscador por HTTP, y el servidor DNS local en UDP.
"""

import argparse
import os
import signal
import sys

from . import config, netinfo
from .core import db
from .core.http import Aplicacion
from .services import (ajustes, api, archivos, buscar, chat, certificados,
                       conectar, correo, dns, foro, juegos, medios, portal,
                       sesion, wiki)


def construir_aplicacion():
    conf = config.cargar()
    app = Aplicacion(conf["nombre"])
    portal.registrar(app)
    wiki.registrar(app, "/wiki")
    buscar.registrar(app, "/buscar")
    chat.registrar(app, "/chat")
    correo.registrar(app, "/correo")
    foro.registrar(app, "/foro")
    archivos.registrar(app, "/nube")
    medios.registrar(app, "/streaming")
    juegos.registrar(app, "/juegos")
    api.registrar(app, "/api")
    conectar.registrar(app, "/conectar")
    sesion.registrar(app, "/sesion")
    ajustes.registrar(app, "/ajustes")
    # Hosts virtuales: wiki.mired → /wiki, buscar.mired → /buscar, etc.
    zona = conf["zona"]
    app.vhost("wiki." + zona, "/wiki")
    app.vhost("buscar." + zona, "/buscar")
    app.vhost("chat." + zona, "/chat")
    app.vhost("correo." + zona, "/correo")
    app.vhost("foro." + zona, "/foro")
    app.vhost("nube." + zona, "/nube")
    app.vhost("streaming." + zona, "/streaming")
    app.vhost("juegos." + zona, "/juegos")
    app.vhost("api." + zona, "/api")
    app.vhost("inicio." + zona, "/")
    return app


def iniciar(conf):
    config.asegurar_datos()
    db.esquema()
    wiki.inicializar()

    ip = conf["ip_manual"] if not conf.get("ip_auto", True) and conf.get("ip_manual") else netinfo.ip_local()
    app = construir_aplicacion()
    servidor = app.servir_para_siempre("0.0.0.0", conf["puerto_http"])

    estado_https = "deshabilitado"
    if conf.get("https", True):
        par = certificados.preparar()
        if par:
            app.servir_para_siempre("0.0.0.0", conf.get("puerto_https", 8443), tls=par)
            estado_https = "activo en https://%s:%d (CA en /sesion/ca.crt)" % (
                ip, conf.get("puerto_https", 8443))
        else:
            estado_https = "no disponible (instala openssl: pkg install openssl)"

    servidor_dns = None
    try:
        servidor_dns = dns.ServidorDNS(
            ip_destino=ip, zona=conf["zona"],
            puerto=conf["puerto_dns"], upstream=conf["dns_upstream"])
        servidor_dns.iniciar()
        estado_dns = "activo en udp/%d" % conf["puerto_dns"]
    except OSError as exc:
        estado_dns = "no disponible (%s)" % exc

    servidor_smtp = None
    try:
        servidor_smtp = correo.ServidorSMTP(conf.get("puerto_smtp", 2525))
        servidor_smtp.iniciar()
        estado_smtp = "activo en tcp/%d" % conf.get("puerto_smtp", 2525)
    except OSError as exc:
        estado_smtp = "no disponible (%s)" % exc

    # Rastreo inicial del buscador en segundo plano
    buscar.rastreo_en_segundo_plano("http://127.0.0.1:%d" % conf["puerto_http"])

    zona = conf["zona"]
    print("")
    print("  🌐  %s — tu internet independiente" % conf["nombre"])
    print("  ─────────────────────────────────────────────")
    print("  Portal    http://%s:%d/" % (ip, conf["puerto_http"]))
    print("  Wiki      http://%s:%d/wiki/   (o http://wiki.%s:%d/)" % (
        ip, conf["puerto_http"], zona, conf["puerto_http"]))
    print("  Buscador  http://%s:%d/buscar/ (o http://buscar.%s:%d/)" % (
        ip, conf["puerto_http"], zona, conf["puerto_http"]))
    print("  Chat      http://%s:%d/chat/   (o http://chat.%s:%d/)" % (
        ip, conf["puerto_http"], zona, conf["puerto_http"]))
    print("  Correo    http://%s:%d/correo/ (o http://correo.%s:%d/)" % (
        ip, conf["puerto_http"], zona, conf["puerto_http"]))
    print("  Foro      http://%s:%d/foro/   (o http://foro.%s:%d/)" % (
        ip, conf["puerto_http"], zona, conf["puerto_http"]))
    print("  Nube      http://%s:%d/nube/   (o http://nube.%s:%d/)" % (
        ip, conf["puerto_http"], zona, conf["puerto_http"]))
    print("  Streaming http://%s:%d/streaming/ (o http://streaming.%s:%d/)" % (
        ip, conf["puerto_http"], zona, conf["puerto_http"]))
    print("  DNS       %s  →  *.%s" % (estado_dns, zona))
    print("  SMTP      %s  →  *@%s" % (estado_smtp, zona))
    print("  HTTPS     %s" % estado_https)
    print("  Acceso    %s" % ("abierto (sin cuenta)" if conf.get("acceso_abierto", True)
                             else "privado (con cuenta — crea la tuya en /sesion/)"))
    print("  ─────────────────────────────────────────────")
    print("  Conecta otros dispositivos al hotspot y abre la URL del portal.")
    print("  Pulsa Ctrl+C para detener. (En el teléfono: ./detener.sh)")
    print("")

    def _apagar(*_args):
        print("\nDeteniendo MiRed…")
        if servidor_dns:
            servidor_dns.detener()
        servidor.shutdown()
        sys.exit(0)

    signal.signal(signal.SIGTERM, _apagar)
    signal.signal(signal.SIGINT, _apagar)

    # Señal para iniciar.sh: todo listo
    config.asegurar_datos()
    with open(os.path.join(config.RUTA_DATOS, "mired.pid"), "w") as fh:
        fh.write(str(os.getpid()))

    import threading
    threading.Event().wait()  # el servidor vive en su propio hilo


def main():
    parser = argparse.ArgumentParser(prog="mired",
                                     description="MiRed — tu internet independiente")
    parser.add_argument("--init", action="store_true",
                        help="Inicializar datos y salir (lo usa instalar.sh)")
    parser.add_argument("--version", action="version", version="MiRed 1.0.0")
    args = parser.parse_args()

    conf = config.cargar()
    if args.init:
        config.asegurar_datos()
        db.esquema()
        wiki.inicializar()
        print("Datos inicializados en", config.RUTA_DATOS)
        return

    iniciar(conf)


if __name__ == "__main__":
    main()
