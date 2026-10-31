"""La nube de archivos de MiRed (tu "Drive" local).

Almacenamiento en ``data/archivos/`` con carpetas. El sistema de archivos
es la fuente de verdad (sin tablas que se desincronicen). Subidas
grandes procesadas en streaming: nunca se carga el archivo en memoria.

Incluye también el parser de multipart/form-data en streaming y el
servidor de archivos con soporte de rangos HTTP (para vídeo/audio),
que reutiliza el servicio de streaming.
"""

import os
import re
import shutil
from urllib.parse import quote

from .. import config
from ..core.http import html, redirigir, Respuesta
from ..core.template import escapar, renderizar

BASE = os.path.join(config.RUTA_DATOS, "archivos")

TIPOS = {
    ".txt": "text/plain; charset=utf-8", ".md": "text/plain; charset=utf-8",
    ".pdf": "application/pdf", ".csv": "text/csv; charset=utf-8",
    ".json": "application/json", ".html": "text/html; charset=utf-8",
    ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
    ".gif": "image/gif", ".webp": "image/webp", ".svg": "image/svg+xml",
    ".mp3": "audio/mpeg", ".ogg": "audio/ogg", ".opus": "audio/opus",
    ".flac": "audio/flac", ".wav": "audio/wav", ".m4a": "audio/mp4",
    ".aac": "audio/aac", ".mp4": "video/mp4", ".webm": "video/webm",
    ".mkv": "video/x-matroska", ".avi": "video/x-msvideo",
    ".mov": "video/quicktime", ".m4v": "video/x-m4v", ".3gp": "video/3gpp",
    ".zip": "application/zip", ".epub": "application/epub+zip",
}

AUDIO = (".mp3", ".ogg", ".opus", ".flac", ".wav", ".m4a", ".aac")
VIDEO = (".mp4", ".webm", ".mkv", ".avi", ".mov", ".m4v", ".3gp")


def tipo_de(nombre):
    return TIPOS.get(os.path.splitext(nombre)[1].lower(), "application/octet-stream")


def es_audio(nombre):
    return nombre.lower().endswith(AUDIO)


def es_video(nombre):
    return nombre.lower().endswith(VIDEO)


def inicializar():
    os.makedirs(BASE, exist_ok=True)


# ---------- rutas seguras --------------------------------------------

def seguro(rel):
    """Normaliza una ruta relativa y garantiza que no escape de la base."""
    rel = (rel or "").strip().replace("\\", "/")
    partes = [p for p in rel.split("/") if p and p != "."]
    if any(p == ".." for p in partes):
        raise ValueError("Ruta no permitida")
    return "/".join(partes)


def absoluto(rel):
    rel = seguro(rel)
    base = os.path.normpath(BASE)
    completo = os.path.normpath(os.path.join(base, rel))
    if completo != base and not completo.startswith(base + os.sep):
        raise ValueError("Ruta no permitida")
    return completo


def tamano_humano(n):
    for unidad in ("B", "KB", "MB", "GB"):
        if n < 1024 or unidad == "GB":
            return "%.1f %s" % (n, unidad) if unidad != "B" else "%d B" % n
        n /= 1024.0


# ---------- multipart/form-data en streaming -------------------------

class _Lector:
    def __init__(self, rfile, limite):
        self.rfile = rfile
        self.restante = limite

    def leer(self, n):
        if self.restante <= 0:
            return b""
        trozo = self.rfile.read(min(n, self.restante)) or b""
        self.restante -= len(trozo)
        return trozo


class _Multipart:
    def __init__(self, rfile, largo, boundary):
        self.lector = _Lector(rfile, largo)
        self.boundary = boundary
        self.resto = b""

    def buscar(self, marca):
        resto = self.resto
        while marca not in resto:
            trozo = self.lector.leer(65536)
            if not trozo:
                raise ValueError("Subida incompleta")
            resto += trozo
            if len(resto) > len(marca) + 65536:
                resto = resto[-(len(marca) + 65536):]
        i = resto.index(marca)
        self.resto = resto[i + len(marca):]

    def consumir(self, escribir):
        """Escribe el cuerpo de la parte actual hasta el siguiente boundary."""
        marca = b"\r\n" + self.boundary
        resto = self.resto
        while True:
            i = resto.find(marca)
            if i >= 0:
                if i:
                    escribir(resto[:i])
                self.resto = resto[i + len(marca):]
                return
            trozo = self.lector.leer(65536)
            if not trozo:
                raise ValueError("Subida incompleta")
            resto += trozo
            if len(resto) > len(marca) + 65536:
                corte = len(resto) - len(marca) - 1
                escribir(resto[:corte])
                resto = resto[corte:]


def nombre_final_de(fname):
    base = os.path.basename((fname or "").replace("\\", "/")).strip()
    return base or "archivo"


def procesar_subida(peticion, destino_de, extensiones=None):
    """Parsea el formulario multipart en streaming.

    ``destino_de(campos, nombre_archivo)`` devuelve la carpeta destino.
    Devuelve (campos_de_texto, nombre_archivo|None, ruta_guardada|None).
    """
    ctype = peticion.cabeceras.get("Content-Type", "")
    m = re.search(r'boundary="?([^";,\s]+)"?', ctype)
    if not m:
        raise ValueError("El formulario no es multipart/form-data")
    sub = _Multipart(peticion.rfile, peticion.largo_cuerpo,
                     b"--" + m.group(1).encode("latin-1"))
    sub.buscar(sub.boundary)
    campos, guardado = {}, None
    while not sub.resto.startswith(b"--"):
        if sub.resto.startswith(b"\r\n"):
            sub.resto = sub.resto[2:]
        while b"\r\n\r\n" not in sub.resto:
            trozo = sub.lector.leer(4096)
            if not trozo:
                raise ValueError("Subida incompleta")
            sub.resto += trozo
        cabeceras, sub.resto = sub.resto.split(b"\r\n\r\n", 1)
        texto = cabeceras.decode("utf-8", "replace")
        nombre_campo = ""
        fname = None
        mc = re.search(r'name="([^"]*)"', texto)
        if mc:
            nombre_campo = mc.group(1)
        mf = re.search(r'filename="([^"]*)"', texto)
        if mf:
            fname = mf.group(1)
        if fname is not None:
            nombre = nombre_final_de(fname)
            if extensiones and not nombre.lower().endswith(extensiones):
                sub.consumir(lambda b: None)  # tipo no permitido: descartar
                continue
            carpeta = destino_de(campos, nombre)
            os.makedirs(carpeta, exist_ok=True)
            destino = os.path.join(carpeta, nombre)
            with open(destino, "wb") as fh:
                sub.consumir(fh.write)
            guardado = (nombre, destino)
        else:
            acumulado = []
            sub.consumir(acumulado.append)
            campos[nombre_campo] = b"".join(acumulado).decode("utf-8", "replace")
    return campos, guardado


# ---------- respuestas de archivo ------------------------------------

def stream_archivo(abs_path, tipo, como_adjunto=False, nombre=None):
    tam = os.path.getsize(abs_path)
    cabeceras = {"Accept-Ranges": "bytes"}
    if como_adjunto:
        cabeceras["Content-Disposition"] = (
            "attachment; filename*=UTF-8''" + quote(nombre or os.path.basename(abs_path)))

    def generador():
        with open(abs_path, "rb") as fh:
            while True:
                trozo = fh.read(65536)
                if not trozo:
                    break
                yield trozo

    return Respuesta(b"", stream=generador(), largo=tam, tipo=tipo,
                     cabeceras=cabeceras)


def stream_rango(peticion, abs_path, tipo):
    """Sirve un archivo soportando la cabecera Range (necesaria para
    poder adelantar/retrasar el vídeo y el audio en el navegador)."""
    tam = os.path.getsize(abs_path)
    inicio, fin = 0, max(tam - 1, 0)
    estado = 200
    cabeceras = {"Accept-Ranges": "bytes"}
    rango = peticion.cabeceras.get("Range")
    if rango and tam:
        m = re.match(r"bytes=(\d*)-(\d*)", rango.strip())
        if m and (m.group(1) or m.group(2)):
            if m.group(1):
                inicio = int(m.group(1))
                if m.group(2):
                    fin = min(int(m.group(2)), tam - 1)
            else:
                inicio = max(0, tam - int(m.group(2)))
            if inicio >= tam:
                return Respuesta(b"", estado=416,
                                 cabeceras={"Content-Range": "bytes */%d" % tam})
            estado = 206
            cabeceras["Content-Range"] = "bytes %d-%d/%d" % (inicio, fin, tam)
    largo = fin - inicio + 1 if tam else 0

    def generador():
        with open(abs_path, "rb") as fh:
            fh.seek(inicio)
            restante = largo
            while restante > 0:
                trozo = fh.read(min(65536, restante))
                if not trozo:
                    break
                restante -= len(trozo)
                yield trozo

    return Respuesta(b"", estado=estado, stream=generador(), largo=largo,
                     tipo=tipo, cabeceras=cabeceras)


# ---------- vista de la nube -----------------------------------------

def _migas(carpeta):
    partes = [p for p in carpeta.split("/") if p]
    trozos = ['<a href="/nube/">Nube</a>']
    acumulado = ""
    for p in partes:
        acumulado = (acumulado + "/" + p) if acumulado else p
        trozos.append('<a href="/nube/?carpeta=%s">%s</a>'
                      % (quote(acumulado), escapar(p)))
    return " / ".join(trozos)


def vista_nube(peticion):
    carpeta = seguro(peticion.arg("carpeta", ""))
    directorio = absoluto(carpeta)
    if not os.path.isdir(directorio):
        return html(renderizar("error.html", {"titulo": "Nube",
                                              "mensaje": "La carpeta no existe."}))
    carpetas, archivos = [], []
    for nombre in sorted(os.listdir(directorio), key=str.lower):
        ruta = os.path.join(directorio, nombre)
        rel = (carpeta + "/" + nombre) if carpeta else nombre
        if os.path.isdir(ruta):
            carpetas.append((nombre, rel))
        else:
            archivos.append((nombre, rel, os.path.getsize(ruta)))
    elementos = ""
    for nombre, rel in carpetas:
        elementos += (
            "<li><a class='carpeta' href='/nube/?carpeta=%s'>%s/</a>"
            "<span class='meta'>carpeta</span>"
            "<form class='fila-acc' method='post' action='/nube/eliminar' "
            "onsubmit=\"return confirm('¿Eliminar la carpeta y su contenido?')\">"
            "<input type='hidden' name='ruta' value='%s'>"
            "<button class='boton-texto' type='submit'>Eliminar</button></form></li>"
            % (quote(rel), escapar(nombre), escapar(rel)))
    for nombre, rel, tam in archivos:
        elementos += (
            "<li><a href='/nube/descargar?ruta=%s'>%s</a>"
            "<span class='meta'>%s</span>"
            "<form class='fila-acc' method='post' action='/nube/eliminar' "
            "onsubmit=\"return confirm('¿Eliminar este archivo?')\">"
            "<input type='hidden' name='ruta' value='%s'>"
            "<button class='boton-texto' type='submit'>Eliminar</button></form></li>"
            % (quote(rel), escapar(nombre), tamano_humano(tam), escapar(rel)))
    if not elementos:
        elementos = "<li class='meta'>Esta carpeta está vacía.</li>"
    aviso = ""
    if peticion.arg("aviso") == "1":
        aviso = "<p class='aviso-ok'>Archivo subido.</p>"
    elif peticion.arg("aviso") == "carpeta":
        aviso = "<p class='aviso-ok'>Carpeta creada.</p>"
    elif peticion.arg("aviso") == "eliminado":
        aviso = "<p class='aviso-ok'>Elemento eliminado.</p>"
    return html(renderizar("nube.html", {
        "titulo": "Nube",
        "carpeta": escapar(carpeta),
        "migas": _migas(carpeta),
        "elementos": elementos,
        "aviso": aviso,
    }))


def vista_subir(peticion):
    campos, guardado = procesar_subida(
        peticion,
        lambda campos, nombre: absoluto(campos.get("carpeta", "")))
    return redirigir("/nube/?carpeta=%s&aviso=1"
                     % quote(seguro(campos.get("carpeta", ""))))


def vista_crear_carpeta(peticion):
    form = peticion.form()
    dentro = seguro(form.get("dentro", ""))
    nombre = form.get("nombre", "").strip().replace("/", "_")
    if nombre:
        os.makedirs(absoluto((dentro + "/" + nombre) if dentro else nombre),
                    exist_ok=True)
    return redirigir("/nube/?carpeta=%s&aviso=carpeta" % quote(dentro))


def vista_eliminar(peticion):
    rel = seguro(peticion.form().get("ruta", ""))
    if rel:
        completo = absoluto(rel)
        if os.path.isdir(completo):
            shutil.rmtree(completo, ignore_errors=True)
        elif os.path.isfile(completo):
            os.remove(completo)
    carpeta = "/".join(rel.split("/")[:-1])
    return redirigir("/nube/?carpeta=%s&aviso=eliminado" % quote(carpeta))


def vista_descargar(peticion):
    rel = seguro(peticion.arg("ruta", ""))
    completo = absoluto(rel)
    if not os.path.isfile(completo):
        return html(renderizar("error.html", {"titulo": "Nube",
                                              "mensaje": "El archivo no existe."}))
    return stream_archivo(completo, tipo_de(rel), como_adjunto=True,
                          nombre=os.path.basename(rel))


def registrar(app, prefijo="/nube"):
    inicializar()
    r = app.enrutador
    r.ruta(r"^%s/?$" % prefijo)(vista_nube)
    r.ruta(r"^%s/subir$" % prefijo, metodos=("POST",))(vista_subir)
    r.ruta(r"^%s/carpeta$" % prefijo, metodos=("POST",))(vista_crear_carpeta)
    r.ruta(r"^%s/eliminar$" % prefijo, metodos=("POST",))(vista_eliminar)
    r.ruta(r"^%s/descargar$" % prefijo)(vista_descargar)
    app.streaming(prefijo + "/subir")
