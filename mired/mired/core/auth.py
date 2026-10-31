"""Autenticación de MiRed: usuarios, contraseñas y sesiones.

* Contraseñas: PBKDF2-HMAC-SHA256 con sal aleatoria (200 000 iteraciones).
* Sesiones: cookies firmadas con HMAC-SHA256 y secreto del servidor
  (``data/secret.key``), sin tablas de sesión que crezcan sin control.
"""

import base64
import hashlib
import hmac
import os
import time

from .. import config
from . import db

ITERACIONES = 200_000
NOMBRE_COOKIE = "mired_sesion"
DURACION_DIAS = 30


# ---------- secreto del servidor ------------------------------------

def secreto():
    config.asegurar_datos()
    ruta = os.path.join(config.RUTA_DATOS, "secret.key")
    if not os.path.exists(ruta):
        with open(ruta, "wb") as fh:
            fh.write(os.urandom(48))
        try:
            os.chmod(ruta, 0o600)
        except OSError:
            pass
    with open(ruta, "rb") as fh:
        return fh.read()


# ---------- contraseñas ---------------------------------------------

def hash_password(clave, sal=None):
    if sal is None:
        sal = os.urandom(16)
    elif isinstance(sal, str):
        sal = bytes.fromhex(sal)
    h = hashlib.pbkdf2_hmac("sha256", clave.encode("utf-8"), sal, ITERACIONES)
    return h.hex(), sal.hex()


def verificar_password(clave, hash_hex, sal_hex):
    h, _ = hash_password(clave, sal_hex)
    return hmac.compare_digest(h, hash_hex)


# ---------- usuarios ------------------------------------------------

def crear_usuario(usuario, clave, es_admin=False):
    usuario = usuario.strip().lower()
    if not (3 <= len(usuario) <= 24) or not usuario.replace("_", "").isalnum():
        raise ValueError("El usuario debe tener 3-24 letras, números o guion bajo")
    if len(clave) < 6:
        raise ValueError("La contraseña debe tener al menos 6 caracteres")
    if db.una("SELECT id FROM usuarios WHERE usuario = ?", (usuario,)):
        raise ValueError("Ese nombre de usuario ya existe")
    hash_hex, sal_hex = hash_password(clave)
    return db.ejecutar(
        "INSERT INTO usuarios (usuario, hash, sal, es_admin) VALUES (?, ?, ?, ?)",
        (usuario, hash_hex, sal_hex, 1 if es_admin else 0))


def usuario_por_nombre(nombre):
    return db.una("SELECT * FROM usuarios WHERE usuario = ?", (nombre.strip().lower(),))


def usuario_por_id(uid):
    return db.una("SELECT * FROM usuarios WHERE id = ?", (int(uid),))


def autenticar(nombre, clave):
    u = usuario_por_nombre(nombre)
    if u and verificar_password(clave, u["hash"], u["sal"]):
        return u
    return None


def primer_usuario_es_admin():
    return db.una("SELECT COUNT(*) AS n FROM usuarios")["n"] == 0


def listar_usuarios():
    return db.consulta("SELECT id, usuario, es_admin, creado_en FROM usuarios ORDER BY usuario")


def eliminar_usuario(uid):
    db.ejecutar("DELETE FROM usuarios WHERE id = ?", (int(uid),))


def alternar_admin(uid):
    db.ejecutar("UPDATE usuarios SET es_admin = 1 - es_admin WHERE id = ?", (int(uid),))


# ---------- sesiones (cookies firmadas) ------------------------------

def crear_token(uid, dias=DURACION_DIAS):
    exp = int(time.time()) + dias * 86400
    datos = "%d:%d" % (uid, exp)
    firma = hmac.new(secreto(), datos.encode(), hashlib.sha256).hexdigest()[:40]
    return base64.urlsafe_b64encode(datos.encode()).decode() + "." + firma


def usuario_de_token(token):
    if not token or "." not in token:
        return None
    datos_b64, firma = token.rsplit(".", 1)
    try:
        datos = base64.urlsafe_b64decode(datos_b64.encode()).decode()
        uid, exp = datos.split(":")
        uid, exp = int(uid), int(exp)
    except (ValueError, TypeError):
        return None
    esperada = hmac.new(secreto(), ("%d:%d" % (uid, exp)).encode(),
                        hashlib.sha256).hexdigest()[:40]
    if not hmac.compare_digest(firma, esperada):
        return None
    if exp < time.time():
        return None
    return usuario_por_id(uid)


def cabecera_cookie(token, es_tls=False):
    extra = "; Secure" if es_tls else ""
    return ("%s=%s; Path=/; HttpOnly; SameSite=Lax; Max-Age=%d%s"
            % (NOMBRE_COOKIE, token, DURACION_DIAS * 86400, extra))


def token_interno():
    """Token para que el rastreador del buscador (dentro del propio
    servidor) atraviese el control de acceso sin sesión de usuario."""
    return hmac.new(secreto(), b"interno", hashlib.sha256).hexdigest()[:32]
