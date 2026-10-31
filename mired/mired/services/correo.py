"""El correo de MiRed (tu "Gmail" local).

Dos piezas:

* **Servidor SMTP** en el puerto 2525 (sin root): acepta mensajes de
  clientes de correo y los entrega en buzones locales (@tu-zona).
* **Webmail** en el navegador: bandeja de entrada, lectura y redacción.

Solo se entrega correo **interno** de tu internet (no hay reley a
internet — eso sería Fase 4 con configuración explícita). La red es de
confianza: sin contraseñas todavía.
"""

import re
import socket
import socketserver
import threading

from .. import config
from ..core import db
from ..core.http import html, redirigir
from ..core.template import escapar, renderizar


def inicializar():
    pass


# ---------- entrega local --------------------------------------------

def _es_local(direccion, zona):
    d = direccion.strip().lower()
    return d.endswith("@" + zona) or d.endswith("@mired")


def entregar(de, destinatarios, asunto, cuerpo):
    """Guarda el mensaje en el buzón de cada destinatario local.

    Devuelve (entregados, rechazados).
    """
    zona = config.cargar()["zona"]
    entregados, rechazados = [], []
    for para in destinatarios:
        para = para.strip()
        if not para:
            continue
        if _es_local(para, zona):
            db.ejecutar(
                "INSERT INTO correo_mensajes (para, de, asunto, cuerpo) VALUES (?, ?, ?, ?)",
                (para.lower(), de.strip(), asunto.strip() or "(sin asunto)", cuerpo))
            entregados.append(para)
        else:
            rechazados.append(para)
    return entregados, rechazados


def bandeja(cuenta):
    return db.consulta(
        "SELECT id, de, asunto, creado_en, leido FROM correo_mensajes "
        "WHERE para = ? ORDER BY id DESC LIMIT 50", (cuenta.lower(),))


def mensaje(mid):
    return db.una("SELECT * FROM correo_mensajes WHERE id = ?", (int(mid),))


# ---------- servidor SMTP (puerto 2525) ------------------------------

class _ManejadorSMTP(socketserver.StreamRequestHandler):
    def _enviar(self, texto):
        self.wfile.write((texto + "\r\n").encode("utf-8"))

    def handle(self):
        zona = config.cargar()["zona"]
        de, para, asunto = "", [], "(sin asunto)"
        try:
            self._bucle_smtp(zona)
        except (ConnectionResetError, BrokenPipeError, TimeoutError, OSError):
            pass  # el cliente se desconectó: normal en una red abierta

    def _bucle_smtp(self, zona):
        de, para, asunto = "", [], "(sin asunto)"
        self._enviar("220 mired ESMTP listo")
        while True:
            linea = self.rfile.readline()
            if not linea:
                break
            comando = linea.decode("utf-8", "replace").rstrip("\r\n")
            may = comando.upper()

            if may.startswith("EHLO"):
                self._enviar("250-mired")
                self._enviar("250 8BITMIME")
            elif may.startswith("HELO"):
                self._enviar("250 mired")
            elif may.startswith("MAIL FROM"):
                m = re.search(r"<([^>]*)>", comando)
                de = m.group(1) if m else ""
                self._enviar("250 OK")
            elif may.startswith("RCPT TO"):
                m = re.search(r"<([^>]*)>", comando)
                if m:
                    para.append(m.group(1))
                self._enviar("250 OK")
            elif may == "DATA":
                self._enviar("354 Termina con <CR><LF>.<CR><LF>")
                lineas = []
                while True:
                    trozo = self.rfile.readline()
                    if not trozo:
                        break
                    t = trozo.decode("utf-8", "replace").rstrip("\r\n")
                    if t == ".":
                        break
                    lineas.append(t[1:] if t.startswith("..") else t)
                # Separar cabeceras del cuerpo
                cuerpo_lineas, i = [], 0
                while i < len(lineas) and lineas[i].strip():
                    if lineas[i].lower().startswith("subject:"):
                        asunto = lineas[i][8:].strip() or asunto
                    if lineas[i].lower().startswith("from:") and not de:
                        m = re.search(r"<([^>]*)>", lineas[i])
                        de = m.group(1) if m else lineas[i][5:].strip()
                    i += 1
                cuerpo_lineas = lineas[i + 1:]
                cuerpo = "\n".join(cuerpo_lineas)
                if not para:
                    self._enviar("554 Sin destinatarios")
                    continue
                no_locales = [p for p in para if not _es_local(p, zona)]
                if len(no_locales) == len(para):
                    self._enviar("550 Relay denegado: solo correo @%s" % zona)
                    continue
                entregar(de, para, asunto, cuerpo)
                self._enviar("250 OK mensaje entregado")
            elif may.startswith("RSET"):
                de, para, asunto = "", [], "(sin asunto)"
                self._enviar("250 OK")
            elif may.startswith("NOOP"):
                self._enviar("250 OK")
            elif may.startswith("QUIT"):
                self._enviar("221 mired adios")
                break
            else:
                self._enviar("502 Comando no implementado")


class ServidorSMTP(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, puerto):
        super().__init__(("0.0.0.0", puerto), _ManejadorSMTP)

    def iniciar(self):
        hilo = threading.Thread(target=self.serve_forever, daemon=True,
                                name="mired-smtp")
        hilo.start()
        return hilo


# ---------- webmail --------------------------------------------------

def vista_bandeja(peticion):
    cuenta = peticion.arg("cuenta", "").strip().lower()
    if not cuenta:
        zona = config.cargar()["zona"]
        return html(renderizar("correo_elegir.html", {"titulo": "Correo", "zona": zona}))
    mensajes = bandeja(cuenta)
    filas = ""
    for m in mensajes:
        clase = "" if m["leido"] else " class='noleido'"
        filas += ("<li%s><a href='/correo/mensaje/%d?cuenta=%s'>%s</a> "
                  "<span class='meta'>%s · %s</span></li>"
                  % (clase, m["id"], escapar(cuenta), escapar(m["asunto"]),
                     escapar(m["de"]), escapar(m["creado_en"] or "")))
    aviso = ""
    if peticion.arg("aviso") == "1":
        aviso = "<p class='aviso-ok'>Mensaje enviado.</p>"
    elif peticion.arg("aviso") == "rechazado":
        aviso = ("<p class='aviso-error'>Esa dirección no es local (@%s). "
                 "Tu internet solo entrega correo interno.</p>"
                 % config.cargar()["zona"])
    return html(renderizar("correo_bandeja.html", {
        "titulo": "Correo",
        "cuenta": escapar(cuenta),
        "mensajes": filas or "<li class='meta'>(buzón vacío)</li>",
        "aviso": aviso,
    }))


def vista_mensaje(peticion, mid):
    cuenta = peticion.arg("cuenta", "").strip().lower()
    m = mensaje(mid)
    if not m:
        return html("<p>Mensaje inexistente.</p>")
    db.ejecutar("UPDATE correo_mensajes SET leido = 1 WHERE id = ?", (int(mid),))
    return html(renderizar("correo_mensaje.html", {
        "titulo": m["asunto"],
        "cuenta": escapar(cuenta),
        "de": escapar(m["de"]),
        "para": escapar(m["para"]),
        "asunto": escapar(m["asunto"]),
        "creado_en": escapar(m["creado_en"] or ""),
        "cuerpo": escapar(m["cuerpo"]),
    }))


def vista_redactar(peticion):
    cuenta = peticion.arg("cuenta", "").strip().lower()
    return html(renderizar("correo_redactar.html", {
        "titulo": "Redactar",
        "cuenta": escapar(cuenta),
    }))


def vista_enviar(peticion):
    form = peticion.form()
    de = form.get("de", "").strip()
    para = [p.strip() for p in form.get("para", "").split(",") if p.strip()]
    entregados, rechazados = entregar(
        de, para, form.get("asunto", ""), form.get("cuerpo", ""))
    if not entregados and rechazados:
        return redirigir("/correo/?cuenta=%s&aviso=rechazado" % de)
    return redirigir("/correo/?cuenta=%s&aviso=1" % de)


def registrar(app, prefijo="/correo"):
    r = app.enrutador
    r.ruta(r"^%s/?$" % prefijo)(vista_bandeja)
    r.ruta(r"^%s/mensaje/(?P<mid>\d+)$" % prefijo)(vista_mensaje)
    r.ruta(r"^%s/redactar$" % prefijo)(vista_redactar)
    r.ruta(r"^%s/enviar$" % prefijo, metodos=("POST",))(vista_enviar)
