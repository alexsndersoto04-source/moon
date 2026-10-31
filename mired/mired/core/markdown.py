"""Mini-Markdown para la wiki: lo justo para escribir cómodo, sin riesgos.

Todo el texto se escapa antes de convertirlo, así que nunca hay HTML
inyectado. Soporta: títulos, negrita, cursiva, código, bloques de código,
listas, citas, reglas, enlaces [texto](url), enlaces de wiki [[Pagina]],
imágenes ![alt](url) y párrafos.
"""

import re

from .template import escapar


def convertir(texto):
    lineas = escapar(texto).split("\n")
    salida = []
    en_codigo = False
    buffer_codigo = []
    en_lista = None  # 'ul' | 'ol'
    parrafo = []

    def cerrar_parrafo():
        if parrafo:
            salida.append("<p>" + " ".join(parrafo) + "</p>")
            parrafo.clear()

    def cerrar_lista():
        nonlocal en_lista
        if en_lista:
            salida.append("</%s>" % en_lista)
            en_lista = None

    for linea in lineas:
        # Bloques de código ``` ... ```
        if linea.strip().startswith("```"):
            if en_codigo:
                salida.append("<pre><code>" + "\n".join(buffer_codigo) + "</code></pre>")
                buffer_codigo = []
                en_codigo = False
            else:
                cerrar_parrafo()
                cerrar_lista()
                en_codigo = True
            continue
        if en_codigo:
            buffer_codigo.append(linea)
            continue

        r = linea.rstrip()

        if not r.strip():
            cerrar_parrafo()
            cerrar_lista()
            continue

        # Títulos
        m = re.match(r"^(#{1,4})\s+(.*)$", r)
        if m:
            cerrar_parrafo()
            cerrar_lista()
            nivel = len(m.group(1))
            salida.append("<h%d>%s</h%d>" % (nivel, _en_linea(m.group(2)), nivel))
            continue

        # Regla horizontal
        if re.match(r"^(-{3,}|\*{3,})$", r.strip()):
            cerrar_parrafo()
            cerrar_lista()
            salida.append("<hr>")
            continue

        # Cita
        if r.lstrip().startswith("&gt; "):
            cerrar_parrafo()
            cerrar_lista()
            salida.append("<blockquote>%s</blockquote>" % _en_linea(r.lstrip()[5:]))
            continue

        # Listas
        m = re.match(r"^(\s*)[-*+]\s+(.*)$", r)
        tipo_lista = "ul"
        if not m:
            m = re.match(r"^(\s*)\d+\.\s+(.*)$", r)
            tipo_lista = "ol"
        if m:
            cerrar_parrafo()
            if en_lista != tipo_lista:
                cerrar_lista()
                salida.append("<%s>" % tipo_lista)
                en_lista = tipo_lista
            salida.append("<li>%s</li>" % _en_linea(m.group(2)))
            continue

        cerrar_lista()
        parrafo.append(_en_linea(r))

    if en_codigo:
        salida.append("<pre><code>" + "\n".join(buffer_codigo) + "</code></pre>")
    cerrar_parrafo()
    cerrar_lista()
    return "\n".join(salida)


def _en_linea(t):
    # Bloque de código `x`
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    # Imágenes ![alt](url) — solo rutas relativas o http(s)
    t = re.sub(r"!\[([^\]]*)\]\(([^)]+)\)",
               lambda m: '<img alt="%s" src="%s">' % (m.group(1), _url_segura(m.group(2)))
               if _url_segura(m.group(2)) else "", t)
    # Enlaces [texto](url)
    t = re.sub(r"\[([^\]]+)\]\(([^)]+)\)",
               lambda m: '<a href="%s">%s</a>' % (_url_segura(m.group(2)) or "#", m.group(1))
               if _url_segura(m.group(2)) else m.group(1), t)
    # Enlaces de wiki [[Pagina]] o [[Pagina|texto]]
    def _wiki(m):
        partes = m.group(1).split("|", 1)
        slug = partes[0].strip().replace(" ", "_")
        etiqueta = partes[1].strip() if len(partes) > 1 else partes[0].strip()
        return '<a class="wikilink" href="/wiki/pagina/%s">%s</a>' % (slug, etiqueta)
    t = re.sub(r"\[\[([^\]]+)\]\]", _wiki, t)
    # Negrita y cursiva
    t = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", r"<em>\1</em>", t)
    return t


def _url_segura(url):
    url = url.strip()
    if url.startswith(("http://", "https://", "/", "#")):
        return url
    return None


def texto_plano(texto):
    """Reduce el Markdown a texto plano (para indexar y resúmenes)."""
    t = re.sub(r"```.*?```", " ", texto, flags=re.S)
    t = re.sub(r"`([^`]+)`", r"\1", t)
    t = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", t)
    t = re.sub(r"\[\[([^\]|]+)(\|[^\]]+)?\]\]", r"\1", t)
    t = re.sub(r"[#*>\-]+", " ", t)
    return re.sub(r"\s+", " ", t).strip()
