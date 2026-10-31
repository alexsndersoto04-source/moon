"""Páginas de sesión de MiRed: entrar, crear cuenta, salir y la CA.

Con `acceso_abierto: false` en config.json, todo MiRed pide cuenta.
La **primera cuenta creada se convierte en administradora**.
"""

from urllib.parse import quote

from ..core import auth
from ..core.http import html, redirigir, Respuesta
from ..core.template import escapar, renderizar
from . import certificados


def inicializar():
    pass


def _volver_seguro(volver):
    if volver and volver.startswith("/") and not volver.startswith("//"):
        return volver
    return "/"


def vista_sesion(peticion):
    volver = peticion.arg("volver", "/")
    aviso = ""
    if peticion.arg("error") == "clave":
        aviso = "<p class='aviso-error'>Usuario o contraseña incorrectos.</p>"
    elif peticion.arg("error") == "registro":
        aviso = "<p class='aviso-error'>%s</p>" % escapar(
            peticion.arg("mensaje", "No se pudo crear la cuenta."))
    elif peticion.arg("ok") == "registro":
        aviso = "<p class='aviso-ok'>Cuenta creada. Ya puedes entrar.</p>"
    if peticion.usuario:
        return redirigir(_volver_seguro(volver))
    return html(renderizar("sesion.html", {
        "titulo": "Entrar",
        "volver": escapar(volver),
        "aviso": aviso,
        "primera_cuenta_txt": (" La primera cuenta creada será administradora."
                               if auth.primer_usuario_es_admin() else ""),
    }))


def vista_entrar(peticion):
    form = peticion.form()
    u = auth.autenticar(form.get("usuario", ""), form.get("clave", ""))
    if not u:
        return redirigir("/sesion/?volver=%s&error=clave" % quote(form.get("volver", "/")))
    token = auth.crear_token(u["id"])
    destino = _volver_seguro(form.get("volver", "/"))
    respuesta = redirigir(destino)
    respuesta.cabeceras["Set-Cookie"] = auth.cabecera_cookie(token, peticion.es_tls)
    return respuesta


def vista_registro(peticion):
    form = peticion.form()
    try:
        es_admin = auth.primer_usuario_es_admin()
        auth.crear_usuario(form.get("usuario", ""), form.get("clave", ""),
                           es_admin=es_admin)
    except ValueError as exc:
        return redirigir("/sesion/?volver=%s&error=registro&mensaje=%s"
                         % (quote(form.get("volver", "/")), quote(str(exc))))
    return redirigir("/sesion/?ok=registro&volver=%s" % quote(form.get("volver", "/")))


def vista_salir(peticion):
    respuesta = redirigir("/sesion/")
    respuesta.cabeceras["Set-Cookie"] = (
        "%s=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0" % auth.NOMBRE_COOKIE)
    return respuesta


def vista_ca(peticion):
    ruta = certificados.ruta_ca()
    try:
        with open(ruta, "rb") as fh:
            cuerpo = fh.read()
    except OSError:
        return html("<p>La CA aún no está generada (se crea al arrancar con HTTPS).</p>")
    return Respuesta(cuerpo, tipo="application/x-x509-ca-cert",
                     cabeceras={"Content-Disposition":
                                'attachment; filename="mired-ca.crt"'})


def registrar(app, prefijo="/sesion"):
    r = app.enrutador
    r.ruta(r"^%s/?$" % prefijo)(vista_sesion)
    r.ruta(r"^%s/entrar$" % prefijo, metodos=("POST",))(vista_entrar)
    r.ruta(r"^%s/registro$" % prefijo, metodos=("POST",))(vista_registro)
    r.ruta(r"^%s/salir$" % prefijo)(vista_salir)
    r.ruta(r"^%s/ca\.crt$" % prefijo)(vista_ca)
