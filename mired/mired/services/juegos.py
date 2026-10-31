"""Juegos de MiRed: Ta-Te-Ti multijugador en tiempo real por tu red.

Dos jugadores por partida, tablero en SQLite y sondeo largo (como el
chat) para que los movimientos aparezcan al instante.
"""

import json
import time

from ..core import db
from ..core.http import html, json as json_resp, redirigir
from ..core.template import escapar, renderizar

GANADORES = (
    (0, 1, 2), (3, 4, 5), (6, 7, 8),   # filas
    (0, 3, 6), (1, 4, 7), (2, 5, 8),   # columnas
    (0, 4, 8), (2, 4, 6),              # diagonales
)


def inicializar():
    pass


def crear(nick):
    nick = (nick or "anonimo").strip()[:30] or "anonimo"
    return db.ejecutar(
        "INSERT INTO juego_partidas (tablero, turno, jugador_x, version) "
        "VALUES ('.........', 'X', ?, 0)", (nick,))


def partida(pid):
    return db.una("SELECT * FROM juego_partidas WHERE id = ?", (int(pid),))


def unirse(pid, nick):
    p = partida(pid)
    if not p or p["estado"] != "esperando" or p["jugador_x"] == nick:
        return None
    db.ejecutar("UPDATE juego_partidas SET jugador_o = ?, estado = 'jugando' "
                "WHERE id = ? AND estado = 'esperando'", ((nick or "anonimo").strip()[:30], int(pid)))
    return partida(pid)


def mover(pid, posicion, nick):
    p = partida(pid)
    if not p or p["estado"] != "jugando":
        return None
    posicion = int(posicion)
    tablero = p["tablero"]
    if not (0 <= posicion <= 8) or tablero[posicion] != ".":
        return None
    ficha = p["turno"]
    esperado = p["jugador_x"] if ficha == "X" else p["jugador_o"]
    if (nick or "") != (esperado or ""):
        return None
    nuevo = tablero[:posicion] + ficha + tablero[posicion + 1:]
    estado, linea = _evaluar(nuevo)
    db.ejecutar(
        "UPDATE juego_partidas SET tablero = ?, turno = ?, estado = ?, "
        "linea = ?, version = version + 1 WHERE id = ?",
        (nuevo, "O" if ficha == "X" else "X", estado,
         json.dumps(linea) if linea else "", int(pid)))
    return partida(pid)


def _evaluar(t):
    for a, b, c in GANADORES:
        if t[a] != "." and t[a] == t[b] == t[c]:
            return "finalizada", [a, b, c]
    if "." not in t:
        return "empate", []
    return "jugando", []


def partidas_abiertas():
    return db.consulta(
        "SELECT * FROM juego_partidas WHERE estado IN ('esperando','jugando') "
        "ORDER BY id DESC LIMIT 20")


def esperar(pid, desde_version, espera=True):
    inicio = time.time()
    while True:
        p = partida(pid)
        if not p:
            return None
        if p["version"] > int(desde_version) or not espera \
                or time.time() - inicio > 25:
            return p
        time.sleep(0.5)


# ---------- controladores --------------------------------------------

def vista_juegos(peticion):
    abiertas = ""
    for p in partidas_abiertas():
        if p["estado"] == "esperando":
            accion = ("<form class='fila-acc' method='post' action='/juegos/unirse'>"
                      "<input type='hidden' name='id' value='%d'>"
                      "<button class='boton-texto' type='submit'>Unirse</button></form>" % p["id"])
            estado = "esperando rival"
        else:
            accion = ("<a class='meta' href='/juegos/jugar?partida=%d'>ver</a>" % p["id"])
            estado = "%s (X) vs %s (O)" % (p["jugador_x"], p["jugador_o"] or "…")
        abiertas += ("<li><strong>Partida #%d</strong> <span class='meta'>%s</span>%s</li>"
                     % (p["id"], escapar(estado), accion))
    return html(renderizar("juegos.html", {
        "titulo": "Juegos",
        "partidas": abiertas or "<li class='meta'>No hay partidas abiertas. Crea una.</li>",
    }))


def vista_crear(peticion):
    nick = peticion.form().get("nick", "")
    if not nick and peticion.usuario:
        nick = peticion.usuario["usuario"]
    pid = crear(nick)
    return redirigir("/juegos/jugar?partida=%d" % pid)


def vista_unirse(peticion):
    form = peticion.form()
    nick = form.get("nick", "")
    if not nick and peticion.usuario:
        nick = peticion.usuario["usuario"]
    p = unirse(form.get("id", "0"), nick)
    return redirigir("/juegos/jugar?partida=%s" % (p["id"] if p else form.get("id", "0")))


def vista_jugar(peticion):
    pid = peticion.arg("partida", "0")
    p = partida(pid)
    if not p:
        return redirigir("/juegos/")
    return html(renderizar("juego_tablero.html", {
        "titulo": "Partida #%s" % pid,
        "partida_id": p["id"],
        "estado": escapar(p["estado"]),
    }))


def vista_mover(peticion):
    form = peticion.form()
    nick = form.get("nick", "")
    if not nick and peticion.usuario:
        nick = peticion.usuario["usuario"]
    mover(form.get("partida", "0"), form.get("posicion", "-1"), nick)
    return json_resp({"ok": True})


def api_estado(peticion):
    p = esperar(peticion.arg("partida", "0"), peticion.arg("desde", "0"))
    if not p:
        return json_resp({"error": "no existe"})
    return json_resp({
        "id": p["id"], "tablero": p["tablero"], "turno": p["turno"],
        "estado": p["estado"], "jugador_x": p["jugador_x"],
        "jugador_o": p["jugador_o"], "linea": json.loads(p["linea"] or "[]"),
        "version": p["version"],
    })


def registrar(app, prefijo="/juegos"):
    r = app.enrutador
    r.ruta(r"^%s/?$" % prefijo)(vista_juegos)
    r.ruta(r"^%s/crear$" % prefijo, metodos=("POST",))(vista_crear)
    r.ruta(r"^%s/unirse$" % prefijo, metodos=("POST",))(vista_unirse)
    r.ruta(r"^%s/jugar$" % prefijo)(vista_jugar)
    r.ruta(r"^%s/mover$" % prefijo, metodos=("POST",))(vista_mover)
    r.ruta(r"^%s/estado$" % prefijo)(api_estado)
