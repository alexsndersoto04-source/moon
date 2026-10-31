"""Panel de administración de MiRed (solo administradores).

Gestiona usuarios y el modo de acceso, y ofrece la descarga de la CA
para instalarla en los dispositivos de tu red.
"""

import json

from .. import config
from ..core import auth
from ..core.http import html, no_encontrado, redirigir
from ..core.template import escapar, renderizar

RUTA_CONFIG = config.RUTA_CONFIG


def inicializar():
    pass


def _guardar_acceso(abierto):
    valores = {}
    try:
        with open(RUTA_CONFIG, "r", encoding="utf-8") as fh:
            valores = json.load(fh)
    except (OSError, ValueError):
        pass
    valores["acceso_abierto"] = bool(abierto)
    with open(RUTA_CONFIG, "w", encoding="utf-8") as fh:
        json.dump(valores, fh, indent=2, ensure_ascii=False)


def vista_ajustes(peticion):
    u = peticion.usuario
    if not u:
        return redirigir("/sesion/?volver=/ajustes/")
    if not u["es_admin"]:
        return no_encontrado("Esta página es solo para administradores.")
    conf = config.cargar()
    filas = ""
    for x in auth.listar_usuarios():
        acciones = ("<form class='fila-acc' method='post' action='/ajustes/usuario'>"
                    "<input type='hidden' name='id' value='%d'>"
                    "<button class='boton-texto' name='accion' value='admin' type='submit'>%s</button>"
                    "<button class='boton-texto' name='accion' value='eliminar' type='submit'"
                    " onclick=\"return confirm('¿Eliminar este usuario?')\">Eliminar</button>"
                    "</form>"
                    % (x["id"], "Quitar admin" if x["es_admin"] else "Hacer admin"))
        filas += ("<li><strong>%s</strong>%s<span class='meta'>%s · desde %s</span>%s</li>"
                  % (escapar(x["usuario"]),
                     " <span class='chip-usuario'>admin</span>" if x["es_admin"] else "",
                     escapar(x["usuario"]), escapar(x["creado_en"] or ""), acciones))
    abierto = conf.get("acceso_abierto", True)
    return html(renderizar("ajustes.html", {
        "titulo": "Ajustes",
        "usuarios": filas or "<li class='meta'>(sin usuarios)</li>",
        "acceso": "Abierto" if abierto else "Privado (con cuenta)",
        "accion_acceso": "Cerrar acceso (pedir cuenta)" if abierto else "Abrir acceso (sin cuenta)",
        "valor_acceso": "0" if abierto else "1",
        "aviso": "<p class='aviso-ok'>Cambios guardados.</p>"
                 if peticion.arg("ok") else "",
    }))


def vista_acceso(peticion):
    if not (peticion.usuario and peticion.usuario["es_admin"]):
        return redirigir("/sesion/")
    _guardar_acceso(peticion.form().get("valor") == "1")
    return redirigir("/ajustes/?ok=1")


def vista_usuario(peticion):
    if not (peticion.usuario and peticion.usuario["es_admin"]):
        return redirigir("/sesion/")
    form = peticion.form()
    uid = form.get("id", "0")
    if form.get("accion") == "eliminar" and int(uid) != peticion.usuario["id"]:
        auth.eliminar_usuario(uid)
    elif form.get("accion") == "admin" and int(uid) != peticion.usuario["id"]:
        auth.alternar_admin(uid)
    return redirigir("/ajustes/?ok=1")


def registrar(app, prefijo="/ajustes"):
    r = app.enrutador
    r.ruta(r"^%s/?$" % prefijo)(vista_ajustes)
    r.ruta(r"^%s/acceso$" % prefijo, metodos=("POST",))(vista_acceso)
    r.ruta(r"^%s/usuario$" % prefijo, metodos=("POST",))(vista_usuario)
