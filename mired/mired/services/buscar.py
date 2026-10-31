"""El buscador de MiRed (tu "Google" local).

Un rastreador recorre las páginas de tu red (wiki + portal + lo que vaya
apareciendo), las indexa en SQLite con un índice invertido y las consulta
con ranking por relevancia. Todo en la biblioteca estándar.
"""

import html as _html
import re
import threading
import unicodedata
import urllib.request
from urllib.parse import urljoin, urlsplit

from .. import config
from ..core import db
from ..core.auth import token_interno as _token_interno
from ..core.http import html, json, redirigir
from ..core.template import escapar, renderizar
from ..core.markdown import texto_plano

_EXTENSIONES_BINARIAS = (".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg",
                         ".css", ".js", ".ico", ".zip", ".mp3", ".mp4", ".pdf")


def _normalizar(texto):
    texto = unicodedata.normalize("NFKD", texto.lower())
    return "".join(c for c in texto if not unicodedata.combining(c))


def tokenizar(texto):
    return [t for t in re.split(r"[^a-z0-9_]+", _normalizar(texto)) if len(t) > 2]


def inicializar():
    pass  # las tablas las crea core.db.esquema()


# ---------- indexado --------------------------------------------------

def indexar(url, titulo, texto):
    resumen = texto_plano(texto)[:300]
    plano = texto_plano(texto)
    existente = db.una("SELECT id FROM documentos WHERE url = ?", (url,))
    if existente:
        doc_id = existente["id"]
        db.ejecutar(
            "UPDATE documentos SET titulo = ?, resumen = ?, texto = ?, rastreado_en = datetime('now') WHERE id = ?",
            (titulo, resumen, plano, doc_id))
        db.ejecutar("DELETE FROM tokens WHERE documento_id = ?", (doc_id,))
    else:
        doc_id = db.ejecutar(
            "INSERT INTO documentos (url, titulo, resumen, texto) VALUES (?, ?, ?, ?)",
            (url, titulo, resumen, plano))
    tokens = set(tokenizar(titulo + " " + plano))
    if tokens:
        conn = db.conexion()
        with conn:
            conn.executemany(
                "INSERT INTO tokens (token, documento_id) VALUES (?, ?)",
                [(t, doc_id) for t in tokens])
    return doc_id


def buscar(consulta_texto, limite=20):
    terminos = tokenizar(consulta_texto)
    if not terminos:
        return []
    marcadores = ",".join("?" for _ in terminos)
    filas = db.consulta(
        """SELECT d.id, d.url, d.titulo, d.resumen, d.texto,
                  COUNT(DISTINCT t.token) AS aciertos
           FROM documentos d JOIN tokens t ON t.documento_id = d.id
           WHERE t.token IN (%s)
           GROUP BY d.id
           ORDER BY aciertos DESC, d.titulo
           LIMIT ?""" % marcadores,
        tuple(terminos) + (limite,))
    resultados = []
    for f in filas:
        resultados.append({
            "url": f["url"],
            "titulo": f["titulo"],
            "fragmento": _fragmento(f["texto"] or f["resumen"], terminos),
            "puntos": f["aciertos"],
        })
    return resultados


def _fragmento(texto, terminos, radio=90):
    bajo = _normalizar(texto)
    posiciones = [bajo.find(t) for t in terminos if bajo.find(t) >= 0]
    if not posiciones:
        trozo = texto[:radio * 2]
    else:
        centro = min(posiciones)
        inicio = max(0, centro - radio)
        trozo = texto[inicio:inicio + radio * 2]
    resultado = escapar(trozo)
    for t in terminos:
        resultado = re.sub("(?i)(%s)" % re.escape(t), r"<mark>\1</mark>", resultado)
    return ("…" if posiciones and posiciones[0] > radio else "") + resultado + "…"


# ---------- rastreador ------------------------------------------------

_hilo_rastreo = None


def rastrear(base_url, max_paginas=None):
    """Recorre la red local empezando por ``base_url`` e indexa cada página."""
    max_paginas = max_paginas or config.cargar().get("max_paginas_rastreo", 200)
    visitadas = set()
    cola = [base_url.rstrip("/") + "/"]
    anfitrion = urlsplit(base_url).netloc
    indice = 0
    while cola and indice < max_paginas:
        url = cola.pop(0)
        if url in visitadas:
            continue
        visitadas.add(url)
        try:
            peticion_http = urllib.request.Request(
                url, headers={"X-Mired-Interno": _token_interno()})
            with urllib.request.urlopen(peticion_http, timeout=5) as respuesta:
                if respuesta.status != 200:
                    continue
                tipo = respuesta.headers.get("Content-Type", "")
                if "html" not in tipo:
                    continue
                cuerpo = respuesta.read(200_000).decode("utf-8", "replace")
        except Exception:
            continue
        indice += 1
        titulo_m = re.search(r"<title[^>]*>(.*?)</title>", cuerpo, re.S | re.I)
        titulo = _html.unescape(titulo_m.group(1).strip()) if titulo_m else url
        contenido_m = re.search(r"<body[^>]*>(.*?)</body>", cuerpo, re.S | re.I)
        crudo = contenido_m.group(1) if contenido_m else cuerpo
        crudo = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", crudo, flags=re.S | re.I)
        texto = _html.unescape(re.sub(r"<[^>]+>", " ", crudo))
        texto = re.sub(r"\s+", " ", texto).strip()
        indexar(url, titulo, texto)

        for href in re.findall(r'href="([^"#]+)"', cuerpo):
            absoluta = urljoin(url, href)
            partes = urlsplit(absoluta)
            if partes.netloc != anfitrion:
                continue
            if partes.path.startswith("/static") or partes.path.endswith(_EXTENSIONES_BINARIAS):
                continue
            if partes.path.startswith("/buscar/rastrear"):
                continue
            normalizada = absoluta.split("#")[0]
            if normalizada not in visitadas:
                cola.append(normalizada)
    # Asegurar que todas las páginas de la wiki estén indexadas aunque el
    # rastreador no las haya alcanzado por enlaces.
    conn = db.conexion()
    for p in conn.execute("SELECT slug, titulo, cuerpo FROM paginas").fetchall():
        url = "%s/wiki/pagina/%s" % (base_url.rstrip("/"), p["slug"])
        if url not in visitadas:
            indexar(url, p["titulo"], p["cuerpo"])
    return indice


def rastreo_en_segundo_plano(base_url):
    global _hilo_rastreo
    if _hilo_rastreo and _hilo_rastreo.is_alive():
        return False

    def _tarea():
        try:
            rastrear(base_url)
        except Exception:
            pass

    _hilo_rastreo = threading.Thread(target=_tarea, daemon=True, name="mired-rastreo")
    _hilo_rastreo.start()
    return True


def estatus():
    total = db.una("SELECT COUNT(*) AS n FROM documentos")["n"]
    return {"documentos": total}


# ---------- controladores --------------------------------------------

def vista_buscar(peticion):
    consulta_texto = peticion.arg("q", "").strip()
    resultados_html = ""
    mensaje = ""
    if consulta_texto:
        resultados = buscar(consulta_texto)
        if resultados:
            for r in resultados:
                resultados_html += renderizar("buscar_resultado.html", {
                    "url": escapar(r["url"]),
                    "titulo": escapar(r["titulo"]),
                    "fragmento": r["fragmento"],
                    "puntos": r["puntos"],
                }, usar_base=False)
        else:
            mensaje = "Sin resultados para «%s»." % escapar(consulta_texto)
    else:
        mensaje = "Busca algo en tu red: artículos de la wiki, páginas del portal…"

    doc_total = db.una("SELECT COUNT(*) AS n FROM documentos")["n"]
    return html(renderizar("buscar.html", {
        "titulo": "Buscador",
        "q": escapar(consulta_texto),
        "resultados": resultados_html,
        "mensaje": mensaje,
        "total_documentos": doc_total,
    }))


def vista_rastrear(peticion):
    base = "http://127.0.0.1:%d" % config.cargar()["puerto_http"]
    en_curso = rastreo_en_segundo_plano(base)
    destino = peticion.arg("volver", "/buscar")
    if peticion.arg("formato") == "json":
        return json({"rastreo_iniciado": en_curso, **estatus()})
    return redirigir(destino)


def vista_estado(peticion):
    return json(estatus())


def registrar(app, prefijo="/buscar"):
    r = app.enrutador
    r.ruta(r"^%s/?$" % prefijo)(vista_buscar)
    r.ruta(r"^%s/rastrear$" % prefijo, metodos=("GET", "POST"))(vista_rastrear)
    r.ruta(r"^%s/estado$" % prefijo)(vista_estado)
