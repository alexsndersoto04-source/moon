"""API pública de MiRed: que cualquier dispositivo desarrolle sobre tu internet.

Documentación humana en ``/api/``. Endpoints JSON bajo ``/api/v1/``:

* Lecturas: abiertas si el acceso es abierto; con sesión o clave-API si es privado.
* Escrituras (p. ej. enviar al chat): exigen clave-API (``X-Mired-Clave``) o sesión.

La clave se muestra en ``/ajustes/`` y se deriva del secreto del servidor.
"""

import hmac
import json

from .. import config
from ..core import auth, db
from ..core.http import html, json as json_resp
from ..core.template import renderizar


def inicializar():
    pass


def clave_api():
    return auth.token_api()


def _autorizado(peticion, escritura=False):
    clave = peticion.cabeceras.get("X-Mired-Clave", "")
    if clave and hmac.compare_digest(clave, clave_api()):
        return True
    if peticion.usuario:
        return True
    if not escritura and config.cargar().get("acceso_abierto", True):
        return True
    return False


def _denegado():
    return json_resp({"error": "No autorizado. Usa el header X-Mired-Clave o inicia sesión."}, estado=401)


# ---------- documentación -------------------------------------------

def vista_docs(peticion):
    filas = [
        ("GET", "/api/v1/estado", "Estado general de la red"),
        ("GET", "/api/v1/wiki", "Lista de artículos de la wiki"),
        ("GET", "/api/v1/wiki/&lt;slug&gt;", "Un artículo concreto"),
        ("GET", "/api/v1/buscar?q=…", "Buscar en tu red"),
        ("GET", "/api/v1/chat/&lt;sala&gt;?desde=…", "Mensajes de una sala de chat"),
        ("POST", "/api/v1/chat", "Enviar al chat (clave-API)"),
        ("GET", "/api/v1/foro", "Hilos del foro"),
        ("GET", "/api/v1/nube?carpeta=…", "Listar la nube"),
    ]
    lista = "".join(
        "<li><span class='api-metodo'>%s</span><code>%s</code> <span class='meta'>%s</span></li>"
        % (m, ruta, desc) for m, ruta, desc in filas)
    return html(renderizar("api.html", {"titulo": "API", "endpoints": lista}))


# ---------- endpoints -----------------------------------------------

def v1_estado(peticion):
    if not _autorizado(peticion):
        return _denegado()
    return json_resp({
        "red": config.cargar()["nombre"],
        "zona": config.cargar()["zona"],
        "paginas_indexadas": db.una("SELECT COUNT(*) AS n FROM documentos")["n"],
        "usuarios": db.una("SELECT COUNT(*) AS n FROM usuarios")["n"],
    })


def v1_wiki(peticion):
    if not _autorizado(peticion):
        return _denegado()
    filas = db.consulta("SELECT slug, titulo, editado_en FROM paginas ORDER BY titulo")
    return json_resp([dict(f) for f in filas])


def v1_wiki_pagina(peticion, slug):
    if not _autorizado(peticion):
        return _denegado()
    p = db.una("SELECT slug, titulo, cuerpo, editado_en FROM paginas WHERE slug = ?", (slug,))
    return json_resp(dict(p) if p else {"error": "no existe"}, estado=200 if p else 404)


def v1_buscar(peticion):
    if not _autorizado(peticion):
        return _denegado()
    from . import buscar
    return json_resp(buscar.buscar(peticion.arg("q", ""), limite=10))


def v1_chat(peticion, sala):
    if not _autorizado(peticion):
        return _denegado()
    from . import chat
    return json_resp(chat.nuevos(sala, peticion.arg("desde", "0"), espera=False))


def v1_chat_enviar(peticion):
    if not _autorizado(peticion, escritura=True):
        return _denegado()
    from . import chat
    form = peticion.form()
    nick = form.get("nick", "")
    if not nick and peticion.usuario:
        nick = peticion.usuario["usuario"]
    mid = chat.enviar(form.get("sala", "general"), nick or "api", form.get("texto", ""))
    return json_resp({"ok": mid is not None, "id": mid})


def v1_foro(peticion):
    if not _autorizado(peticion):
        return _denegado()
    from . import foro
    return json_resp([dict(h) for h in foro.hilos()])


def v1_nube(peticion):
    if not _autorizado(peticion):
        return _denegado()
    from . import archivos
    import os
    carpeta = archivos.seguro(peticion.arg("carpeta", ""))
    directorio = archivos.absoluto(carpeta)
    salida = []
    for nombre in sorted(os.listdir(directorio), key=str.lower):
        ruta = os.path.join(directorio, nombre)
        rel = (carpeta + "/" + nombre) if carpeta else nombre
        salida.append({
            "nombre": nombre,
            "ruta": rel,
            "tipo": "carpeta" if os.path.isdir(ruta) else "archivo",
            "tamano": os.path.getsize(ruta) if os.path.isfile(ruta) else None,
        })
    return json_resp(salida)


def registrar(app, prefijo="/api"):
    r = app.enrutador
    r.ruta(r"^%s/?$" % prefijo)(vista_docs)
    r.ruta(r"^%s/v1/estado$" % prefijo)(v1_estado)
    r.ruta(r"^%s/v1/wiki$" % prefijo)(v1_wiki)
    r.ruta(r"^%s/v1/wiki/(?P<slug>[^/]+)$" % prefijo)(v1_wiki_pagina)
    r.ruta(r"^%s/v1/buscar$" % prefijo)(v1_buscar)
    r.ruta(r"^%s/v1/chat/(?P<sala>[^/]+)$" % prefijo)(v1_chat)
    r.ruta(r"^%s/v1/chat$" % prefijo, metodos=("POST",))(v1_chat_enviar)
    r.ruta(r"^%s/v1/foro$" % prefijo)(v1_foro)
    r.ruta(r"^%s/v1/nube$" % prefijo)(v1_nube)
