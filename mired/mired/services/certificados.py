"""HTTPS de MiRed con tu propia autoridad certificadora.

Genera en ``data/certificados/``:

* ``ca.crt`` / ``ca.key`` — tu CA personal (la instalan los dispositivos
  de tu red para que el candado salga verde).
* ``mired.crt`` / ``mired.key`` — el certificado del servidor, firmado
  por tu CA, con SANs para localhost, *.mired y la IP actual.

Usa el binario ``openssl`` (``pkg install openssl`` en Termux). Si no
está disponible, MiRed funciona igualmente solo con HTTP.
"""

import os
import shutil
import subprocess

from .. import config, netinfo

CA_DIAS = 3650
SERVIDOR_DIAS = 825


def carpeta():
    config.asegurar_datos()
    ruta = os.path.join(config.RUTA_DATOS, "certificados")
    os.makedirs(ruta, exist_ok=True)
    return ruta


def _ejecutar(args):
    return subprocess.run(args, capture_output=True, text=True, timeout=60)


def preparar():
    """Devuelve (certfile, keyfile) del servidor, o None si no hay openssl.

    Genera la CA una vez y el certificado del servidor se re-emite si la
    IP del teléfono cambia (los SAN cubren la nueva IP)."""
    if not shutil.which("openssl"):
        return None
    base = carpeta()
    ca_crt = os.path.join(base, "ca.crt")
    ca_key = os.path.join(base, "ca.key")
    srv_crt = os.path.join(base, "mired.crt")
    srv_key = os.path.join(base, "mired.key")
    ip = netinfo.ip_local()

    if not (os.path.exists(ca_crt) and os.path.exists(ca_key)):
        _ejecutar(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                   "-keyout", ca_key, "-out", ca_crt, "-days", str(CA_DIAS),
                   "-subj", "/O=MiRed/CN=MiRed CA",
                   "-addext", "basicConstraints=critical,CA:TRUE"])

    # ¿El certificado del servidor cubre la IP actual?
    ip_guardada = os.path.join(base, "ip.txt")
    if os.path.exists(srv_crt) and os.path.exists(ip_guardada):
        with open(ip_guardada) as fh:
            if fh.read().strip() == ip:
                return (srv_crt, srv_key)

    ext = os.path.join(base, "ext.cnf")
    with open(ext, "w") as fh:
        fh.write("subjectAltName=DNS:localhost,DNS:mired,DNS:*.mired,"
                 "DNS:wiki.mired,DNS:buscar.mired,DNS:chat.mired,"
                 "DNS:correo.mired,DNS:foro.mired,DNS:nube.mired,"
                 "DNS:streaming.mired,DNS:inicio.mired,"
                 "IP:127.0.0.1,IP:%s\n" % ip)
    csr = os.path.join(base, "mired.csr")
    _ejecutar(["openssl", "req", "-newkey", "rsa:2048", "-nodes",
               "-keyout", srv_key, "-out", csr,
               "-subj", "/O=MiRed/CN=mired"])
    _ejecutar(["openssl", "x509", "-req", "-in", csr, "-CA", ca_crt,
               "-CAkey", ca_key, "-CAcreateserial", "-out", srv_crt,
               "-days", str(SERVIDOR_DIAS), "-extfile", ext])
    with open(ip_guardada, "w") as fh:
        fh.write(ip)
    if os.path.exists(srv_crt) and os.path.exists(srv_key):
        return (srv_crt, srv_key)
    return None


def ruta_ca():
    return os.path.join(carpeta(), "ca.crt")
