"""Micro-framework HTTP basado en la biblioteca estándar.

Suficiente para MiRed: enrutado por expresiones regulares, peticiones con
formulario, respuestas HTML/JSON/redirecciones y servidor multihilo.
"""

import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit, unquote


class Peticion:
    def __init__(self, metodo, ruta, consulta, cabeceras, cuerpo, cliente, host):
        self.metodo = metodo
        self.ruta = ruta
        self.consulta = consulta            # dict de parámetros GET
        self.cabeceras = cabeceras
        self.cuerpo = cuerpo                # bytes
        self.cliente = cliente              # IP del cliente
        self.host = host.lower().split(":")[0]
        self._form = None

    def form(self):
        """Parámetros del cuerpo (application/x-www-form-urlencoded)."""
        if self._form is None:
            texto = self.cuerpo.decode("utf-8", "replace")
            self._form = {k: v[0] for k, v in parse_qs(texto).items()}
        return self._form

    def arg(self, nombre, por_defecto=""):
        return self.consulta.get(nombre, [por_defecto])[0]


class Respuesta:
    def __init__(self, cuerpo=b"", estado=200, cabeceras=None, tipo="text/html; charset=utf-8"):
        if isinstance(cuerpo, str):
            cuerpo = cuerpo.encode("utf-8")
        self.cuerpo = cuerpo
        self.estado = estado
        self.cabeceras = dict(cabeceras or {})
        self.cabeceras.setdefault("Content-Type", tipo)


def html(texto, estado=200):
    return Respuesta(texto, estado=estado)


def texto(texto_plano, estado=200):
    return Respuesta(texto_plano, estado=estado, tipo="text/plain; charset=utf-8")


def json(datos, estado=200):
    import json as _json
    return Respuesta(_json.dumps(datos, ensure_ascii=False), estado=estado,
                     tipo="application/json; charset=utf-8")


def redirigir(destino, estado=302):
    return Respuesta(b"", estado=estado, cabeceras={"Location": destino})


def no_encontrado(mensaje="Página no encontrada"):
    from .template import renderizar
    return Respuesta(renderizar("error.html", {"titulo": "404", "mensaje": mensaje}),
                     estado=404)


class Enrutador:
    def __init__(self):
        self._rutas = []

    def ruta(self, patron, metodos=("GET",)):
        patron = re.compile(patron)

        def decorador(funcion):
            self._rutas.append((patron, tuple(m.upper() for m in metodos), funcion))
            return funcion
        return decorador

    def resolver(self, metodo, ruta):
        for patron, metodos, funcion in self._rutas:
            if metodo.upper() not in metodos:
                continue
            m = patron.match(ruta)
            if m:
                return funcion, m.groupdict()
        return None, {}


class _Manejador(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "MiRed/1.0"

    def _procesar(self, metodo):
        partes = urlsplit(self.path)
        ruta = unquote(partes.path) or "/"
        consulta = parse_qs(partes.query)
        largo = int(self.headers.get("Content-Length") or 0)
        cuerpo = self.rfile.read(largo) if largo else b""
        peticion = Peticion(metodo, ruta, consulta, self.headers, cuerpo,
                            self.client_address[0],
                            self.headers.get("Host", ""))
        try:
            respuesta = self.server.app.despachar(peticion)
        except Exception as exc:  # pragma: no cover - red de seguridad
            import traceback
            traceback.print_exc()
            respuesta = html("<h1>Error interno</h1><pre>%s</pre>" % exc, estado=500)
        self.send_response(respuesta.estado)
        for clave, valor in respuesta.cabeceras.items():
            self.send_header(clave, valor)
        self.send_header("Content-Length", str(len(respuesta.cuerpo)))
        self.end_headers()
        if metodo != "HEAD":
            self.wfile.write(respuesta.cuerpo)

    def do_GET(self):
        self._procesar("GET")

    def do_POST(self):
        self._procesar("POST")

    def do_HEAD(self):
        self._procesar("HEAD")

    def log_message(self, formato, *args):
        pass  # silencioso: MiRed registra lo importante por su cuenta


class Aplicacion:
    """Punto único de entrada HTTP: reescribe hosts virtuales y enruta."""

    def __init__(self, nombre="MiRed"):
        self.nombre = nombre
        self.enrutador = Enrutador()
        self._prefijos_vhost = {}   # host -> prefijo de ruta
        self._httpd = None

    def vhost(self, host, prefijo):
        """Asocia un nombre de host virtual a un prefijo de ruta.

        Ej.: vhost('wiki.mired', '/wiki') → 'wiki.mired/x' se sirve como '/wiki/x'.
        """
        self._prefijos_vhost[host.lower()] = prefijo.rstrip("/")

    def despachar(self, peticion):
        ruta = peticion.ruta
        for host, prefijo in self._prefijos_vhost.items():
            if peticion.host == host or peticion.host == host.split(".", 1)[-1]:
                if not (ruta == prefijo or ruta.startswith(prefijo + "/")):
                    ruta = prefijo + (ruta if ruta.startswith("/") else "/" + ruta)
                break
        funcion, kwargs = self.enrutador.resolver(peticion.metodo, ruta)
        if funcion is None:
            return no_encontrado("No existe: " + ruta)
        resultado = funcion(peticion, **kwargs)
        if isinstance(resultado, Respuesta):
            return resultado
        return html(str(resultado))

    def servir(self, ip, puerto):
        servidor = ThreadingHTTPServer((ip, puerto), _Manejador)
        servidor.app = self
        servidor.daemon_threads = True
        self._httpd = servidor
        return servidor

    def servir_para_siempre(self, ip, puerto):
        servidor = self.servir(ip, puerto)
        hilo = threading.Thread(target=servidor.serve_forever, daemon=True)
        hilo.start()
        return servidor
