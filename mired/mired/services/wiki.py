"""La wiki de MiRed (tu "Wikipedia" local).

Páginas en Markdown con historial de ediciones, búsqueda, cambios recientes
y enlaces tipo [[Pagina]]. Todo en SQLite, sin dependencias externas.
"""

from ..core import db
from ..core import markdown
from ..core.http import html, no_encontrado, redirigir
from ..core.template import escapar, renderizar

SEMILLA = [
    ("Portada", """# Bienvenido a tu propio internet

Esta es la **portada de la wiki de MiRed**, la enciclopedia de tu red
independiente. Funciona sin internet: todo vive dentro de tu teléfono.

## Explora

- [[Cómo_conectar_tus_dispositivos]] — pon tus otros aparatos en línea
- [[Qué_es_MiRed]] — qué es esto que estás usando
- [[Guía_de_edición]] — aprende a escribir artículos
- [[Sobre_Mi_Red]] — la filosofía de tener tu propio internet

## Consejo

Escribe cualquier nombre entre corchetes dobles, así: [[mi_pagina]], y
al guardar se creará un enlace rojo hacia un artículo nuevo.
"""),
    ("Qué_es_MiRed", """# Qué es MiRed

MiRed es tu **internet independiente**: un conjunto de servicios que corren
en tu teléfono Redmi 9C con Termux y a los que se accede desde cualquier
dispositivo conectado a tu red, sin depender de internet.

## Servicios de la Fase 1

- **Portal** — la página de inicio con todo lo disponible
- **Buscador** — tu propio "Google" que indexa tu red
- **Wiki** — esta enciclopedia que estás leyendo
- **DNS local** — para usar nombres como wiki.mired en vez de números

## Próximamente (Fase 2+)

Chat, correo, nube de archivos, streaming, foros y más.
"""),
    ("Cómo_conectar_tus_dispositivos", """# Cómo conectar tus dispositivos

1. Activa el **punto de acceso (hotspot)** de tu Redmi 9C.
2. Conecta tu computadora, tablet u otro teléfono a la red Wi-Fi del hotspot.
3. Abre el navegador y entra a la dirección que aparece en el portal
   (algo como http://192.168.43.1:8080).

## Usar nombres en vez de números

En computadoras puedes editar el archivo hosts:

- **Windows**: C:\\Windows\\System32\\drivers\\etc\\hosts
- **GNU/Linux y macOS**: /etc/hosts

Agrega las líneas del archivo `hosts.txt` que genera MiRed y podrás usar
direcciones como http://wiki.mired:8080.
"""),
    ("Guía_de_edición", """# Guía de edición

Pulsa **Editar** en cualquier página. El texto usa un Markdown sencillo:

```
# Título grande
## Subtítulo
**negrita** y *cursiva*
- lista con viñetas
1. lista numerada
> una cita
[enlace](http://ejemplo.com)
[[Otra_pagina]]  ← enlace interno de la wiki
```

Cada vez que guardas, MiRed guarda una **revisión**. Puedes ver todo el
historial en la pestaña Historial de cada artículo.
"""),
    ("Sobre_Mi_Red", """# Sobre Mi Red

> "La mejor manera de predecir el futuro es inventarlo." — Alan Kay

Tener tu propio internet no es nostalgia: es **independencia**. Tus datos
viajan por tu red, tus servicios responden aunque no haya internet, y tú
decides las reglas.

Este proyecto crece por fases. La Fase 1 pone los cimientos: nombres,
portal, buscador y enciclopedia. Las demás fases añaden comunicación,
archivos y medios.
"""),
]


def slugificar(texto):
    texto = texto.strip().replace(" ", "_")
    return "".join(c for c in texto if c.isalnum() or c in "_-áéíóúñÁÉÍÓÚÑ")


def inicializar():
    if db.una("SELECT COUNT(*) AS n FROM paginas")["n"] == 0:
        for titulo, cuerpo in SEMILLA:
            guardar(titulo, cuerpo, editor="sistema", inicial=True)


def guardar(titulo, cuerpo, editor="anonimo", inicial=False):
    titulo = titulo.strip() or "Sin_titulo"
    slug = slugificar(titulo) or "Sin_titulo"
    existente = db.una("SELECT * FROM paginas WHERE slug = ?", (slug,))
    if existente:
        db.ejecutar(
            "UPDATE paginas SET titulo = ?, cuerpo = ?, editado_en = datetime('now') WHERE id = ?",
            (titulo, cuerpo, existente["id"]))
        pagina_id = existente["id"]
    else:
        pagina_id = db.ejecutar(
            "INSERT INTO paginas (slug, titulo, cuerpo) VALUES (?, ?, ?)",
            (slug, titulo, cuerpo))
    db.ejecutar(
        "INSERT INTO revisiones (pagina_id, titulo, cuerpo, editor) VALUES (?, ?, ?, ?)",
        (pagina_id, titulo, cuerpo, editor))
    return slug


def obtener(slug):
    return db.una("SELECT * FROM paginas WHERE slug = ?", (slug,))


def todas():
    return db.consulta("SELECT * FROM paginas ORDER BY titulo COLLATE NOCASE")


def recientes(limite=10):
    return db.consulta(
        """SELECT p.slug, p.titulo, r.editor, r.editado_en
           FROM revisiones r JOIN paginas p ON p.id = r.pagina_id
           ORDER BY r.editado_en DESC, r.id DESC LIMIT ?""", (limite,))


def revisiones(slug):
    return db.consulta(
        """SELECT r.* FROM revisiones r JOIN paginas p ON p.id = r.pagina_id
           WHERE p.slug = ? ORDER BY r.id DESC""", (slug,))


def revision_por_id(rid):
    return db.una("SELECT * FROM revisiones WHERE id = ?", (rid,))


# ---------- controladores (devuelven Respuesta) ----------------------

def pagina_inicio(peticion):
    lista = "".join(
        '<li><a href="/wiki/pagina/%s">%s</a></li>' % (p["slug"], escapar(p["titulo"]))
        for p in todas())
    cambios = "".join(
        "<li><a href=\"/wiki/pagina/%s\">%s</a> <span class='meta'>%s · %s</span></li>"
        % (r["slug"], escapar(r["titulo"]), escapar(r["editor"] or "anonimo"),
           escapar(r["editado_en"] or ""))
        for r in recientes())
    cuerpo = renderizar("wiki_inicio.html", {
        "titulo": "Wiki",
        "lista_paginas": lista or "<li>(aún no hay páginas)</li>",
        "cambios_recientes": cambios or "<li>(sin cambios)</li>",
    })
    return html(cuerpo)


def pagina_ver(peticion, slug):
    pagina = obtener(slug)
    if not pagina:
        return html(renderizar("wiki_editar.html", {
            "titulo": "Crear: " + slug,
            "slug": escapar(slug),
            "titulo_pagina": escapar(slug.replace("_", " ")),
            "cuerpo_pagina": "",
            "editor": "",
            "aviso": "Ese artículo no existe todavía. Escríbelo abajo para crearlo.",
        }))
    contenido = markdown.convertir(pagina["cuerpo"])
    cuerpo = renderizar("wiki_pagina.html", {
        "titulo": pagina["titulo"],
        "titulo_pagina": escapar(pagina["titulo"]),
        "slug": escapar(slug),
        "contenido": contenido,
        "editado_en": escapar(pagina["editado_en"] or ""),
    })
    return html(cuerpo)


def pagina_editar(peticion, slug):
    pagina = obtener(slug)
    cuerpo = renderizar("wiki_editar.html", {
        "titulo": "Editar: " + slug,
        "slug": escapar(slug),
        "titulo_pagina": escapar((pagina["titulo"] if pagina else slug).replace("_", " ")),
        "cuerpo_pagina": escapar(pagina["cuerpo"] if pagina else ""),
        "editor": "",
        "aviso": "",
    })
    return html(cuerpo)


def pagina_guardar(peticion):
    form = peticion.form()
    slug = guardar(form.get("titulo", ""), form.get("cuerpo", ""),
                   editor=form.get("editor", "") or "anonimo")
    return redirigir("/wiki/pagina/" + slug)


def pagina_historial(peticion, slug):
    pagina = obtener(slug)
    if not pagina:
        return no_encontrado("Artículo inexistente: " + slug)
    filas = "".join(
        "<li><a href=\"/wiki/revision/%d\">%s</a> <span class='meta'>%s · %s</span></li>"
        % (r["id"], escapar(r["editado_en"]), escapar(r["editor"] or "anonimo"),
           escapar(r["titulo"]))
        for r in revisiones(slug))
    return html(renderizar("wiki_historial.html", {
        "titulo": "Historial: " + pagina["titulo"],
        "titulo_pagina": escapar(pagina["titulo"]),
        "slug": escapar(slug),
        "revisiones": filas or "<li>(sin revisiones)</li>",
    }))


def pagina_revision(peticion, revision_id):
    rev = revision_por_id(int(revision_id))
    if not rev:
        return no_encontrado("Revisión inexistente")
    contenido = markdown.convertir(rev["cuerpo"])
    return html(renderizar("wiki_pagina.html", {
        "titulo": rev["titulo"] + " (revisión)",
        "titulo_pagina": escapar(rev["titulo"]) + " <span class='meta'>(revisión del "
                        + escapar(rev["editado_en"] or "") + ")</span>",
        "slug": escapar(slugificar(rev["titulo"])),
        "contenido": contenido,
        "editado_en": escapar(rev["editado_en"] or ""),
    }))


def registrar(app, prefijo="/wiki"):
    r = app.enrutador
    r.ruta(r"^%s/?$" % prefijo)(pagina_inicio)
    r.ruta(r"^%s/pagina/(?P<slug>[^/]+)$" % prefijo)(pagina_ver)
    r.ruta(r"^%s/editar/(?P<slug>[^/]+)$" % prefijo)(pagina_editar)
    r.ruta(r"^%s/guardar$" % prefijo, metodos=("POST",))(pagina_guardar)
    r.ruta(r"^%s/historial/(?P<slug>[^/]+)$" % prefijo)(pagina_historial)
    r.ruta(r"^%s/revision/(?P<revision_id>\d+)$" % prefijo)(pagina_revision)
