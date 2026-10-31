"""El foro de MiRed (tu "Reddit / foro de los 2000" local).

Categorías, hilos y respuestas con Markdown. Todo en SQLite.
"""

from ..core import db
from ..core import markdown
from ..core.http import html, redirigir
from ..core.template import escapar, renderizar

CATEGORIAS = ["General", "Anuncios", "Ayuda", "Off-topic"]


def inicializar():
    pass


def hilos():
    return db.consulta(
        """SELECT h.*, COUNT(p.id) AS respuestas
           FROM foro_hilos h LEFT JOIN foro_posts p ON p.hilo_id = h.id
           GROUP BY h.id ORDER BY h.id DESC""")


def hilo(hilo_id):
    return db.una("SELECT * FROM foro_hilos WHERE id = ?", (int(hilo_id),))


def posts(hilo_id):
    return db.consulta(
        "SELECT * FROM foro_posts WHERE hilo_id = ? ORDER BY id", (int(hilo_id),))


def crear_hilo(categoria, titulo, autor, cuerpo):
    categoria = categoria if categoria in CATEGORIAS else "General"
    titulo = titulo.strip()[:120] or "Sin título"
    autor = autor.strip()[:30] or "anonimo"
    hilo_id = db.ejecutar(
        "INSERT INTO foro_hilos (categoria, titulo, autor) VALUES (?, ?, ?)",
        (categoria, titulo, autor))
    db.ejecutar(
        "INSERT INTO foro_posts (hilo_id, autor, cuerpo) VALUES (?, ?, ?)",
        (hilo_id, autor, cuerpo))
    return hilo_id


def responder(hilo_id, autor, cuerpo):
    autor = autor.strip()[:30] or "anonimo"
    if not cuerpo.strip():
        return None
    return db.ejecutar(
        "INSERT INTO foro_posts (hilo_id, autor, cuerpo) VALUES (?, ?, ?)",
        (int(hilo_id), autor, cuerpo))


# ---------- controladores --------------------------------------------

def vista_indice(peticion):
    por_categoria = {c: [] for c in CATEGORIAS}
    for h in hilos():
        por_categoria.setdefault(h["categoria"], []).append(h)
    bloques = ""
    for categoria in CATEGORIAS:
        filas = "".join(
            "<li><a href='/foro/hilo/%d'>%s</a> <span class='meta'>por %s · %d respuestas</span></li>"
            % (h["id"], escapar(h["titulo"]), escapar(h["autor"]), h["respuestas"] or 0)
            for h in por_categoria.get(categoria, []))
        bloques += ("<div class='foro-cat'><h2>%s</h2><ul class='lista'>%s</ul></div>"
                    % (categoria, filas or "<li class='meta'>(vacío)</li>"))
    opciones = "".join("<option>%s</option>" % c for c in CATEGORIAS)
    return html(renderizar("foro.html", {
        "titulo": "Foro",
        "categorias": bloques,
        "opciones_categoria": opciones,
    }))


def vista_hilo(peticion, hilo_id):
    h = hilo(hilo_id)
    if not h:
        return html("<p>Hilo inexistente.</p>")
    cuerpo_posts = ""
    for i, p in enumerate(posts(hilo_id)):
        cuerpo_posts += renderizar("foro_post.html", {
            "autor": escapar(p["autor"]),
            "creado_en": escapar(p["creado_en"] or ""),
            "cuerpo": markdown.convertir(p["cuerpo"]),
            "numero": i + 1,
        }, usar_base=False)
    return html(renderizar("foro_hilo.html", {
        "titulo": h["titulo"],
        "titulo_hilo": escapar(h["titulo"]),
        "categoria": escapar(h["categoria"]),
        "autor": escapar(h["autor"]),
        "hilo_id": h["id"],
        "posts": cuerpo_posts,
    }))


def vista_nuevo(peticion):
    form = peticion.form()
    hilo_id = crear_hilo(form.get("categoria", "General"), form.get("titulo", ""),
                         form.get("autor", ""), form.get("cuerpo", ""))
    return redirigir("/foro/hilo/%d" % hilo_id)


def vista_responder(peticion):
    form = peticion.form()
    responder(form.get("hilo_id", "0"), form.get("autor", ""), form.get("cuerpo", ""))
    return redirigir("/foro/hilo/%s" % form.get("hilo_id", "0"))


def registrar(app, prefijo="/foro"):
    r = app.enrutador
    r.ruta(r"^%s/?$" % prefijo)(vista_indice)
    r.ruta(r"^%s/hilo/(?P<hilo_id>\d+)$" % prefijo)(vista_hilo)
    r.ruta(r"^%s/nuevo$" % prefijo, metodos=("POST",))(vista_nuevo)
    r.ruta(r"^%s/responder$" % prefijo, metodos=("POST",))(vista_responder)
