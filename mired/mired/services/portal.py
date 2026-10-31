"""El portal de MiRed: la página de inicio de tu internet.

Muestra los servicios disponibles, el estado del servidor, un buscador y
las instrucciones de conexión. También sirve los archivos estáticos.
"""

import os
import time

from .. import config, netinfo
from ..core.http import html, json, texto, Respuesta, no_encontrado
from ..core.template import escapar, renderizar
from . import buscar

_INICIO = time.time()

_TIPOS = {
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".svg": "image/svg+xml",
    ".ico": "image/x-icon",
    ".woff2": "font/woff2",
}


def _tarjeta(ruta, nombre, descripcion, icono, estado="activo"):
    return renderizar("tarjeta.html", {
        "ruta": ruta, "nombre": nombre, "descripcion": descripcion,
        "icono": icono, "estado": estado,
    }, usar_base=False)


def inicio(peticion):
    conf = config.cargar()
    ip = netinfo.ip_local()
    tarjetas = "".join([
        _tarjeta("/buscar/", "Buscador", "Busca en todas las páginas de tu red", "🔍"),
        _tarjeta("/wiki/", "Wiki", "Tu enciclopedia personal con historial", "📚"),
        _tarjeta("/estado/", "Estado", "Salud de tu internet independiente", "📊"),
        _tarjeta("/ayuda/", "Ayuda", "Cómo conectar dispositivos y usar DNS", "🛟"),
        _tarjeta("#", "Chat", "Mensajería en tiempo real (Fase 2)", "💬", "proximamente"),
        _tarjeta("#", "Correo", "Tu propio correo @%s (Fase 2)" % conf["zona"], "📧", "proximamente"),
        _tarjeta("#", "Nube", "Archivos y compartición (Fase 3)", "🗂️", "proximamente"),
        _tarjeta("#", "Streaming", "Tu YouTube/Spotify local (Fase 3)", "🎬", "proximamente"),
    ])
    return html(renderizar("portal.html", {
        "titulo": conf["nombre"],
        "nombre_red": conf["nombre"],
        "zona": conf["zona"],
        "ip": ip,
        "puerto": conf["puerto_http"],
        "tarjetas": tarjetas,
        "modo": "hotspot" if netinfo.es_hotspot() else "router",
    }))


def estado(peticion):
    try:
        with open("/proc/meminfo") as fh:
            mem = {}
            for linea in fh:
                partes = linea.split()
                if partes[0] in ("MemTotal:", "MemAvailable:"):
                    mem[partes[0].rstrip(":")] = int(partes[1]) // 1024  # MB
    except OSError:
        mem = {}
    conf = config.cargar()
    datos = {
        "red": conf["nombre"],
        "zona": conf["zona"],
        "ip": netinfo.ip_local(),
        "puerto_http": conf["puerto_http"],
        "puerto_dns": conf["puerto_dns"],
        "uptime_segundos": int(time.time() - _INICIO),
        "memoria_total_mb": mem.get("MemTotal"),
        "memoria_disponible_mb": mem.get("MemAvailable"),
        "modo": "hotspot" if netinfo.es_hotspot() else "router",
        "buscador": buscar.estatus(),
    }
    if peticion.arg("formato") == "json":
        return json(datos)
    minutos, segundos = divmod(datos["uptime_segundos"], 60)
    horas, minutos = divmod(minutos, 60)
    filas = "".join(
        "<tr><td>%s</td><td>%s</td></tr>" % (escapar(k), escapar(v))
        for k, v in [
            ("Red", datos["red"]),
            ("Zona", "*." + datos["zona"]),
            ("IP del servidor", datos["ip"]),
            ("Puerto HTTP", datos["puerto_http"]),
            ("Puerto DNS (UDP)", datos["puerto_dns"]),
            ("Modo de red", datos["modo"]),
            ("Tiempo activo", "%dh %dm %ds" % (horas, minutos, segundos)),
            ("Memoria total", "%s MB" % datos["memoria_total_mb"]),
            ("Memoria disponible", "%s MB" % datos["memoria_disponible_mb"]),
            ("Páginas indexadas", datos["buscador"]["documentos"]),
        ])
    return html(renderizar("estado.html", {"titulo": "Estado", "filas": filas}))


def ayuda(peticion):
    conf = config.cargar()
    ip = netinfo.ip_local()
    return html(renderizar("ayuda.html", {
        "titulo": "Ayuda",
        "ip": ip,
        "puerto": conf["puerto_http"],
        "puerto_dns": conf["puerto_dns"],
        "zona": conf["zona"],
    }))


def estatico(peticion, ruta):
    ruta_segura = os.path.normpath(ruta).lstrip("/")
    completo = os.path.join(config.RUTA_STATIC, ruta_segura)
    if not completo.startswith(config.RUTA_STATIC) or not os.path.isfile(completo):
        return no_encontrado("Recurso inexistente")
    extension = os.path.splitext(completo)[1]
    with open(completo, "rb") as fh:
        return Respuesta(fh.read(), tipo=_TIPOS.get(extension, "application/octet-stream"))


def favicon(peticion):
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">'
           '<rect width="64" height="64" rx="14" fill="#0f172a"/>'
           '<circle cx="32" cy="32" r="18" fill="#38bdf8"/>'
           '<circle cx="40" cy="26" r="14" fill="#0f172a"/></svg>')
    return Respuesta(svg, tipo="image/svg+xml")


def registrar(app, prefijo=""):
    r = app.enrutador
    r.ruta(r"^/$")(inicio)
    r.ruta(r"^/estado/?$")(estado)
    r.ruta(r"^/ayuda/?$")(ayuda)
    r.ruta(r"^/favicon.svg$")(favicon)
    r.ruta(r"^/static/(?P<ruta>.+)$")(estatico)
