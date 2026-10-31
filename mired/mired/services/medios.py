"""El streaming de MiRed (tu "YouTube/Spotify" local).

Biblioteca multimedia sobre los archivos de la nube (``data/archivos/``):
música y vídeos con reproductor HTML5 y soporte de rangos HTTP para
adelantar/retrasar sin volver a descargar todo.
"""

import os

from ..core.http import html, redirigir
from ..core.template import escapar, renderizar, escapar as esc
from . import archivos


def inicializar():
    archivos.inicializar()


def _escanear():
    """Recorre la nube y devuelve (musicas, videos) como tuplas
    (nombre, ruta_rel, tamaño)."""
    musicas, videos = [], []
    base = archivos.BASE
    for dirpath, _dirs, ficheros in os.walk(base):
        for nombre in sorted(ficheros, key=str.lower):
            rel = os.path.relpath(os.path.join(dirpath, nombre), base)
            rel = rel.replace(os.sep, "/")
            tam = os.path.getsize(os.path.join(dirpath, nombre))
            if archivos.es_audio(nombre):
                musicas.append((nombre, rel, tam))
            elif archivos.es_video(nombre):
                videos.append((nombre, rel, tam))
    return musicas, videos


def _reproductor(rel, es_video):
    etiqueta = ("video" if es_video else "audio")
    clase = "reproductor-video" if es_video else "reproductor-audio"
    return ('<%s class="%s" controls autoplay playsinline preload="metadata">'
            '<source src="/streaming/archivo?ruta=%s" type="%s">'
            'Tu navegador no puede reproducir este archivo.'
            '</%s>' % (etiqueta, clase, rel, archivos.tipo_de(rel), etiqueta))


def vista_biblioteca(peticion):
    musicas, videos = _escanear()
    def fila(nombre, rel, tam):
        return ("<li><a href='/streaming/reproducir?ruta=%s'>%s</a>"
                "<span class='meta'>%s</span>"
                "<form class='fila-acc' method='post' action='/streaming/eliminar' "
                "onsubmit=\"return confirm('¿Eliminar este archivo?')\">"
                "<input type='hidden' name='ruta' value='%s'>"
                "<button class='boton-texto' type='submit'>Eliminar</button></form></li>"
                % (rel, esc(nombre), archivos.tamano_humano(tam), esc(rel)))
    lista_musica = "".join(fila(*m) for m in musicas) or "<li class='meta'>Sin música todavía.</li>"
    lista_video = "".join(fila(*v) for v in videos) or "<li class='meta'>Sin vídeos todavía.</li>"
    aviso = ""
    if peticion.arg("aviso") == "1":
        aviso = "<p class='aviso-ok'>Archivo añadido a la biblioteca.</p>"
    elif peticion.arg("aviso") == "eliminado":
        aviso = "<p class='aviso-ok'>Archivo eliminado.</p>"
    return html(renderizar("streaming.html", {
        "titulo": "Streaming",
        "musica": lista_musica,
        "videos": lista_video,
        "aviso": aviso,
    }))


def vista_reproducir(peticion):
    rel = archivos.seguro(peticion.arg("ruta", ""))
    completo = archivos.absoluto(rel)
    if not os.path.isfile(completo):
        return html(renderizar("error.html", {"titulo": "Streaming",
                                              "mensaje": "El archivo no existe."}))
    nombre = os.path.basename(rel)
    return html(renderizar("reproductor.html", {
        "titulo": nombre,
        "nombre": esc(nombre),
        "ruta": esc(rel),
        "tamano": archivos.tamano_humano(os.path.getsize(completo)),
        "reproductor": _reproductor(rel, archivos.es_video(nombre)),
    }))


def vista_archivo(peticion):
    rel = archivos.seguro(peticion.arg("ruta", ""))
    completo = archivos.absoluto(rel)
    if not os.path.isfile(completo):
        return html(renderizar("error.html", {"titulo": "Streaming",
                                              "mensaje": "El archivo no existe."}))
    return archivos.stream_rango(peticion, completo, archivos.tipo_de(rel))


def vista_subir(peticion):
    def destino(_campos, nombre):
        sub = "videos" if archivos.es_video(nombre) else "musica"
        return os.path.join(archivos.BASE, sub)
    _campos, guardado = archivos.procesar_subida(peticion, destino)
    return redirigir("/streaming/?aviso=1")


def vista_eliminar(peticion):
    rel = archivos.seguro(peticion.form().get("ruta", ""))
    if rel:
        completo = archivos.absoluto(rel)
        if os.path.isfile(completo):
            os.remove(completo)
    return redirigir("/streaming/?aviso=eliminado")


def registrar(app, prefijo="/streaming"):
    inicializar()
    r = app.enrutador
    r.ruta(r"^%s/?$" % prefijo)(vista_biblioteca)
    r.ruta(r"^%s/reproducir$" % prefijo)(vista_reproducir)
    r.ruta(r"^%s/archivo$" % prefijo)(vista_archivo)
    r.ruta(r"^%s/subir$" % prefijo, metodos=("POST",))(vista_subir)
    r.ruta(r"^%s/eliminar$" % prefijo, metodos=("POST",))(vista_eliminar)
    app.streaming(prefijo + "/subir")
