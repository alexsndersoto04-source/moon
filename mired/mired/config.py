"""Configuración de MiRed.

Carga ``config.json`` (en la carpeta raíz del proyecto) y expone valores
sobrescribibles por variables de entorno MIREDS_*.
"""

import json
import os

_RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

RUTA_CONFIG = os.path.join(_RAIZ, "config.json")
RUTA_DATOS = os.path.join(_RAIZ, "data")
RUTA_PLANTILLAS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "plantillas")
RUTA_STATIC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")

_VALORES = {
    "nombre": "MiRed",
    "zona": "mired",
    "puerto_http": 8080,
    "puerto_dns": 5353,
    "dns_upstream": "8.8.8.8",
    "ip_auto": True,
    "ip_manual": "",
    "max_paginas_rastreo": 200,
}


def cargar():
    valores = dict(_VALORES)
    if os.path.exists(RUTA_CONFIG):
        try:
            with open(RUTA_CONFIG, "r", encoding="utf-8") as fh:
                valores.update(json.load(fh))
        except (OSError, ValueError):
            pass
    # Overrides por entorno (útil para pruebas)
    if os.environ.get("MIREDS_PUERTO_HTTP"):
        valores["puerto_http"] = int(os.environ["MIREDS_PUERTO_HTTP"])
    if os.environ.get("MIREDS_PUERTO_DNS"):
        valores["puerto_dns"] = int(os.environ["MIREDS_PUERTO_DNS"])
    if os.environ.get("MIREDS_IP"):
        valores["ip_manual"] = os.environ["MIREDS_IP"]
        valores["ip_auto"] = False
    return valores


def asegurar_datos():
    os.makedirs(RUTA_DATOS, exist_ok=True)
