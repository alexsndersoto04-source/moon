"""Acceso a SQLite (incluida en Python, sin dependencias externas)."""

import os
import sqlite3
import threading

from .. import config

_LAZO = threading.local()
_RUTA_DB = None


def ruta_db():
    global _RUTA_DB
    if _RUTA_DB is None:
        config.asegurar_datos()
        _RUTA_DB = os.path.join(config.RUTA_DATOS, "mired.db")
    return _RUTA_DB


def conexion():
    if not hasattr(_LAZO, "conexion"):
        conn = sqlite3.connect(ruta_db())
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        _LAZO.conexion = conn
    return _LAZO.conexion


def consulta(sql, parametros=()):
    conn = conexion()
    cursor = conn.execute(sql, parametros)
    return cursor.fetchall()


def una(sql, parametros=()):
    filas = consulta(sql, parametros)
    return filas[0] if filas else None


def ejecutar(sql, parametros=()):
    conn = conexion()
    with conn:
        cursor = conn.execute(sql, parametros)
        return cursor.lastrowid


def esquema():
    """Crea las tablas si no existen."""
    conn = conexion()
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS paginas (
            id INTEGER PRIMARY KEY,
            slug TEXT UNIQUE NOT NULL,
            titulo TEXT NOT NULL,
            cuerpo TEXT NOT NULL,
            creado_en TEXT DEFAULT (datetime('now')),
            editado_en TEXT DEFAULT (datetime('now'))
        );
        CREATE TABLE IF NOT EXISTS revisiones (
            id INTEGER PRIMARY KEY,
            pagina_id INTEGER NOT NULL REFERENCES paginas(id) ON DELETE CASCADE,
            titulo TEXT NOT NULL,
            cuerpo TEXT NOT NULL,
            editor TEXT DEFAULT 'anonimo',
            editado_en TEXT DEFAULT (datetime('now'))
        );
        CREATE TABLE IF NOT EXISTS documentos (
            id INTEGER PRIMARY KEY,
            url TEXT UNIQUE NOT NULL,
            titulo TEXT NOT NULL,
            resumen TEXT NOT NULL,
            texto TEXT NOT NULL,
            rastreado_en TEXT DEFAULT (datetime('now'))
        );
        CREATE TABLE IF NOT EXISTS tokens (
            token TEXT NOT NULL,
            documento_id INTEGER NOT NULL REFERENCES documentos(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_tokens_token ON tokens(token);
        CREATE INDEX IF NOT EXISTS idx_tokens_doc ON tokens(documento_id);
        """
    )
