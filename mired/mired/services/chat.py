"""El chat de MiRed (tu "Telegram/IRC" local).

Salas de conversación en tiempo real sobre la red local. Usa sondeo
largo (long-polling) con la biblioteca estándar: sin WebSockets, sin
dependencias, sin problemas en el Redmi 9C.
"""

import json
import threading
import time

from ..core import db
from ..core.http import html, json as json_resp, redirigir
from ..core.template import escapar, renderizar

SALAS_INICIALES = ["general", "ayuda", "random"]
ESPERA_MAX = 25        # segundos de espera del sondeo largo
LIMITE_TEXTO = 500


def inicializar():
    pass  # las tablas las crea core.db.esquema()


def listar_salas():
    filas = db.consulta("SELECT DISTINCT sala FROM chat_mensajes ORDER BY sala")
    salas = {f["sala"] for f in filas} | set(SALAS_INICIALES)
    return sorted(salas)


def enviar(sala, nick, texto):
    sala = (sala or "general").strip().lower()[:40] or "general"
    nick = (nick or "anonimo").strip()[:30] or "anonimo"
    texto = texto.strip()[:LIMITE_TEXTO]
    if not texto:
        return None
    return db.ejecutar(
        "INSERT INTO chat_mensajes (sala, nick, texto) VALUES (?, ?, ?)",
        (sala, nick, texto))


def nuevos(sala, desde_id, espera=True):
    """Devuelve los mensajes de ``sala`` con id > desde_id.

    Si no hay mensajes y ``espera`` es verdad, sondea hasta ESPERA_MAX
    segundos (long-polling) antes de responder con lista vacía.
    """
    sala = (sala or "general").strip().lower()[:40] or "general"
    inicio = time.time()
    while True:
        filas = db.consulta(
            "SELECT id, nick, texto, creado_en FROM chat_mensajes "
            "WHERE sala = ? AND id > ? ORDER BY id LIMIT 100",
            (sala, int(desde_id)))
        if filas or not espera or time.time() - inicio > ESPERA_MAX:
            return [dict(f) for f in filas]
        time.sleep(0.5)


# ---------- controladores --------------------------------------------

def vista_chat(peticion):
    salas = listar_salas()
    sala_actual = peticion.arg("sala", "general")
    opciones = "".join(
        '<option value="%s"%s>%s</option>' % (s, " selected" if s == sala_actual else "", s)
        for s in salas)
    return html(renderizar("chat.html", {
        "titulo": "Chat",
        "sala": escapar(sala_actual),
        "sala_json": json.dumps(sala_actual),
        "salas": opciones,
    }))


def api_mensajes(peticion):
    datos = nuevos(peticion.arg("sala", "general"), peticion.arg("desde", "0"))
    return json_resp({"mensajes": datos})


def api_enviar(peticion):
    form = peticion.form()
    mid = enviar(form.get("sala", "general"), form.get("nick", ""), form.get("texto", ""))
    return json_resp({"ok": mid is not None, "id": mid})


def registrar(app, prefijo="/chat"):
    r = app.enrutador
    r.ruta(r"^%s/?$" % prefijo)(vista_chat)
    r.ruta(r"^%s/mensajes$" % prefijo)(api_mensajes)
    r.ruta(r"^%s/enviar$" % prefijo, metodos=("POST",))(api_enviar)
