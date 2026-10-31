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


def _icono(nombre):
    """Iconos de línea minimalistas (SVG 24×24, trazo 1.5)."""
    trazos = {
        "buscar": '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.6-3.6"/>',
        "wiki": ('<path d="M2 4.5h5.5A3.5 3.5 0 0 1 11 8v12a2.5 2.5 0 0 0-2.5-2.5H2z"/>'
                 '<path d="M22 4.5h-5.5A3.5 3.5 0 0 0 13 8v12a2.5 2.5 0 0 1 2.5-2.5H22z"/>'),
        "chat": '<path d="M21 14.5a2 2 0 0 1-2 2H8l-4 3.5v-15a2 2 0 0 1 2-2h13a2 2 0 0 1 2 2z"/>',
        "correo": '<rect x="2.5" y="5" width="19" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>',
        "foro": '<path d="M10.5 8.5H21M10.5 12.5H18M10.5 16.5H15"/><rect x="3" y="5.5" width="4.5" height="13" rx="1.2"/>',
        "estado": '<path d="M3 12.5h4l2.8-8 4.4 15 2.8-7h4"/>',
        "nube": '<path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"/>',
        "streaming": '<circle cx="12" cy="12" r="9"/><path d="M10 8.5v7l6-3.5z"/>',
        "ayuda": ('<circle cx="12" cy="12" r="9"/>'
                  '<path d="M9.6 9.6a2.5 2.5 0 1 1 3.3 2.4c-.8.3-1.3 1-1.3 1.9v.3"/>'
                  '<circle cx="11.9" cy="17" r="0.6" fill="currentColor" stroke="none"/>'),
    }
    return ('<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" '
            'stroke-linecap="round" stroke-linejoin="round">%s</svg>' % trazos.get(nombre, ""))


def _tarjeta(ruta, nombre, descripcion, icono, estado="activo"):
    return renderizar("tarjeta.html", {
        "ruta": ruta, "nombre": nombre, "descripcion": descripcion,
        "icono": _icono(icono), "estado": estado,
    }, usar_base=False)


def inicio(peticion):
    conf = config.cargar()
    ip = netinfo.ip_local()
    tarjetas = "".join([
        _tarjeta("/buscar/", "Buscador", "Busca en todas las páginas de tu red", "buscar"),
        _tarjeta("/wiki/", "Wiki", "Tu enciclopedia personal con historial", "wiki"),
        _tarjeta("/chat/", "Chat", "Conversaciones en tiempo real por salas", "chat"),
        _tarjeta("/correo/", "Correo", "Tu correo interno @%s con webmail" % conf["zona"], "correo"),
        _tarjeta("/foro/", "Foro", "Hilos de conversación con categorías", "foro"),
        _tarjeta("/nube/", "Nube", "Sube, organiza y comparte tus archivos", "nube"),
        _tarjeta("/streaming/", "Streaming", "Tu biblioteca de música y vídeos", "streaming"),
        _tarjeta("/juegos/", "Juegos", "Ta-Te-Ti multijugador en tiempo real", "streaming"),
        _tarjeta("/api/", "API", "Desarrolla sobre tu propio internet", "estado"),
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
        "puerto_smtp": conf.get("puerto_smtp", 2525),
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
    import os
    from . import archivos as _arch
    cuenta_archivos = 0
    bytes_totales = 0
    cuenta_medios = 0
    for _raiz, _dirs, ficheros in os.walk(_arch.BASE):
        for nombre in ficheros:
            cuenta_archivos += 1
            try:
                bytes_totales += os.path.getsize(os.path.join(_raiz, nombre))
            except OSError:
                pass
            if _arch.es_audio(nombre) or _arch.es_video(nombre):
                cuenta_medios += 1
    filas = "".join(
        "<tr><td>%s</td><td>%s</td></tr>" % (escapar(k), escapar(v))
        for k, v in [
            ("Red", datos["red"]),
            ("Zona", "*." + datos["zona"]),
            ("IP del servidor", datos["ip"]),
            ("Puerto HTTP", datos["puerto_http"]),
            ("Puerto DNS (UDP)", datos["puerto_dns"]),
            ("Puerto SMTP", datos["puerto_smtp"]),
            ("Modo de red", datos["modo"]),
            ("Tiempo activo", "%dh %dm %ds" % (horas, minutos, segundos)),
            ("Memoria total", "%s MB" % datos["memoria_total_mb"]),
            ("Memoria disponible", "%s MB" % datos["memoria_disponible_mb"]),
            ("Páginas indexadas", datos["buscador"]["documentos"]),
            ("Archivos en la nube", "%d (%s)" % (cuenta_archivos, _arch.tamano_humano(bytes_totales))),
            ("Medios (música/vídeo)", cuenta_medios),
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
        "puerto_https": conf.get("puerto_https", 8443),
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
           '<rect width="64" height="64" rx="14" fill="#ffffff"/>'
           '<circle cx="32" cy="32" r="17" fill="none" stroke="#171717" stroke-width="5"/>'
           '<circle cx="32" cy="32" r="5" fill="#3b5bdb"/></svg>')
    return Respuesta(svg, tipo="image/svg+xml")


def registrar(app, prefijo=""):
    r = app.enrutador
    r.ruta(r"^/$")(inicio)
    r.ruta(r"^/estado/?$")(estado)
    r.ruta(r"^/ayuda/?$")(ayuda)
    r.ruta(r"^/favicon.svg$")(favicon)
    r.ruta(r"^/static/(?P<ruta>.+)$")(estatico)
