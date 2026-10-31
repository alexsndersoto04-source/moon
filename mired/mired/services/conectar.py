"""Página /conectar/: cómo conecta alguien más a tu red — en dos pasos.

Paso 1: unirse a tu Wi-Fi (código QR con la contraseña).
Paso 2: escanear el código del portal — se abre sin escribir nada.

Los datos de tu red Wi-Fi se guardan solo en el teléfono
(``data/conectar.json``).
"""

import json
import os

from .. import config, netinfo
from ..core import qr
from ..core.http import html, redirigir
from ..core.template import escapar, renderizar

_RUTA = os.path.join(config.RUTA_DATOS, "conectar.json")


def inicializar():
    pass


def _datos():
    try:
        with open(_RUTA, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {"red": "", "clave": ""}


def _guardar(datos):
    os.makedirs(config.RUTA_DATOS, exist_ok=True)
    with open(_RUTA, "w", encoding="utf-8") as fh:
        json.dump(datos, fh, ensure_ascii=False)


def _escapar_wifi(valor):
    salida = []
    for c in valor:
        if c in '\\;,:"':
            salida.append("\\")
        salida.append(c)
    return "".join(salida)


def _qr_wifi(datos):
    if not datos.get("red"):
        return ""
    ssid = _escapar_wifi(datos["red"])
    if datos.get("clave"):
        texto = "WIFI:S:%s;T:WPA;P:%s;;" % (ssid, _escapar_wifi(datos["clave"]))
    else:
        texto = "WIFI:S:%s;T:nopass;;" % ssid
    return qr.svg(texto, ancho=210)


def vista_conectar(peticion):
    conf = config.cargar()
    ip = netinfo.ip_local()
    datos = _datos()
    url = "http://%s:%s/" % (ip, conf["puerto_http"])
    if datos.get("red"):
        wifi = ("<div class='qr'>%s</div>" % _qr_wifi(datos) +
                "<p class='meta'>Tu red: <strong>%s</strong> — "
                "<a href='/conectar/?cambiar=1'>cambiar</a></p>"
                % escapar(datos["red"]))
    else:
        wifi = ("<form class='formulario' method='post' action='/conectar/guardar'>"
                "<label>Nombre de tu red Wi-Fi (SSID)"
                "<input name='red' maxlength='32' required></label>"
                "<label>Contraseña (déjala vacía si no tiene)"
                "<input name='clave' maxlength='63'></label>"
                "<button class='boton' type='submit'>Crear código</button>"
                "</form>")
    if peticion.arg("cambiar"):
        wifi = ("<form class='formulario' method='post' action='/conectar/guardar'>"
                "<label>Nombre de tu red Wi-Fi (SSID)"
                "<input name='red' maxlength='32' required value='%s'></label>"
                "<label>Contraseña (déjala vacía si no tiene)"
                "<input name='clave' maxlength='63' value='%s'></label>"
                "<button class='boton' type='submit'>Guardar</button>"
                "</form>" % (escapar(datos.get("red", "")),
                             escapar(datos.get("clave", ""))))
    return html(renderizar("conectar.html", {
        "titulo": "Conectar",
        "ip": ip,
        "puerto": conf["puerto_http"],
        "zona": conf["zona"],
        "url": escapar(url),
        "qr_portal": qr.svg(url, ancho=240),
        "wifi": wifi,
    }))


def vista_guardar(peticion):
    form = peticion.form()
    _guardar({"red": form.get("red", "").strip()[:32],
              "clave": form.get("clave", "")[:63]})
    return redirigir("/conectar/")


def registrar(app, prefijo="/conectar"):
    r = app.enrutador
    r.ruta(r"^%s/?$" % prefijo)(vista_conectar)
    r.ruta(r"^%s/guardar$" % prefijo, metodos=("POST",))(vista_guardar)
