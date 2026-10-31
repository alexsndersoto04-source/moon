"""Plantillas HTML mínimas: sustitución de {{variables}} sobre archivos.

Los fragmentos con listas o bucles se construyen en Python y se inyectan
como una variable más; así el motor sigue siendo trivial y fiable.
"""

import os

from .. import config


def renderizar(plantilla, contexto=None, usar_base=True):
    contexto = contexto or {}
    ruta = os.path.join(config.RUTA_PLANTILLAS, plantilla)
    with open(ruta, "r", encoding="utf-8") as fh:
        contenido = fh.read()
    contenido = _sustituir(contenido, contexto)
    if usar_base:
        with open(os.path.join(config.RUTA_PLANTILLAS, "base.html"), "r", encoding="utf-8") as fh:
            base = fh.read()
        contexto_completo = dict(contexto)
        contexto_completo["contenido"] = contenido
        return _sustituir(base, contexto_completo)
    return contenido


def _sustituir(texto, contexto):
    for clave, valor in contexto.items():
        texto = texto.replace("{{" + clave + "}}", str(valor))
    return texto


def escapar(texto):
    return (str(texto)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace('"', "&quot;"))
