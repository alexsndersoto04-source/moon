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
        self.usuario = None                 # dict del usuario autenticado (o None)
        self.es_tls = False
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
    def __init__(self, cuerpo=b"", estado=200, cabeceras=None, tipo="text/html; charset=utf-8",
                 stream=None, largo=0):
        if isinstance(cuerpo, str):
            cuerpo = cuerpo.encode("utf-8")
        self.cuerpo = cuerpo
        self.stream = stream      # iterable de bytes para respuestas grandes (archivos)
        self.largo = largo        # tamaño total cuando se usa stream
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
        app = self.server.app
        if app.es_streaming(ruta):
            cuerpo = b""   # el servicio leerá peticion.rfile directamente
        else:
            cuerpo = self.rfile.read(largo) if largo else b""
        peticion = Peticion(metodo, ruta, consulta, self.headers, cuerpo,
                            self.client_address[0],
                            self.headers.get("Host", ""))
        peticion.rfile = self.rfile
        peticion.largo_cuerpo = largo
        try:
            respuesta = app.despachar(peticion)
        except Exception as exc:  # pragma: no cover - red de seguridad
            import traceback
            traceback.print_exc()
            respuesta = html("<h1>Error interno</h1><pre>%s</pre>" % exc, estado=500)
        self.send_response(respuesta.estado)
        for clave, valor in respuesta.cabeceras.items():
            self.send_header(clave, valor)
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "same-origin")
        if respuesta.stream is not None:
            self.send_header("Content-Length", str(respuesta.largo))
            self.end_headers()
            if metodo != "HEAD":
                for trozo in respuesta.stream:
                    self.wfile.write(trozo)
        else:
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
        self._streaming = []        # prefijos cuyo cuerpo se lee en streaming
        self._httpd = None

    def streaming(self, *prefijos):
        """Marca rutas cuyo cuerpo (subidas) se procesa sin cargarlo en RAM."""
        self._streaming.extend(p.rstrip("/") for p in prefijos)

    def es_streaming(self, ruta):
        return any(ruta.startswith(p) for p in self._streaming)

    def vhost(self, host, prefijo):
        """Asocia un nombre de host virtual a un prefijo de ruta.

        Ej.: vhost('wiki.mired', '/wiki') → 'wiki.mired/x' se sirve como '/wiki/x'.
        """
        self._prefijos_vhost[host.lower()] = prefijo.rstrip("/")

    def despachar(self, peticion):
        self._resolver_sesion(peticion)
        ruta = peticion.ruta
        for host, prefijo in self._prefijos_vhost.items():
            if peticion.host == host or peticion.host == host.split(".", 1)[-1]:
                if not (ruta == prefijo or ruta.startswith(prefijo + "/")):
                    ruta = prefijo + (ruta if ruta.startswith("/") else "/" + ruta)
                break
        if not self._acceso_permitido(peticion, ruta):
            from urllib.parse import quote
            return redirigir("/sesion/?volver=" + quote(peticion.ruta))
        funcion, kwargs = self.enrutador.resolver(peticion.metodo, ruta)
        if funcion is None:
            return no_encontrado("No existe: " + ruta)
        resultado = funcion(peticion, **kwargs)
        if not isinstance(resultado, Respuesta):
            resultado = html(str(resultado))
        return self._rellenar_sesion(resultado, peticion)

    # -- sesiones y control de acceso ----------------------------------

    def _resolver_sesion(self, peticion):
        from http.cookies import SimpleCookie
        from . import auth
        crudo = peticion.cabeceras.get("Cookie", "")
        if not crudo:
            return
        try:
            galleta = SimpleCookie()
            galleta.load(crudo)
            morsel = galleta.get(auth.NOMBRE_COOKIE)
            if morsel:
                peticion.usuario = auth.usuario_de_token(morsel.value)
        except Exception:
            peticion.usuario = None

    def _acceso_permitido(self, peticion, ruta):
        from .. import config
        from . import auth
        import hmac as _hmac
        token_interno = peticion.cabeceras.get("X-Mired-Interno", "")
        if token_interno and _hmac.compare_digest(token_interno, auth.token_interno()):
            return True  # el propio servidor (rastreador del buscador)
        if config.cargar().get("acceso_abierto", True):
            return True
        if ruta.startswith(("/sesion", "/static")) or ruta == "/favicon.svg":
            return True
        return peticion.usuario is not None

    def _rellenar_sesion(self, respuesta, peticion):
        if not respuesta.cabeceras.get("Content-Type", "").startswith("text/html"):
            return respuesta
        from .template import escapar
        u = peticion.usuario
        if u:
            nav = ('<a href="/ajustes/">Ajustes</a>'
                   '<span class="chip-usuario">%s</span>'
                   '<a href="/sesion/salir">Salir</a>' % escapar(u["usuario"]))
            nombre_js = escapar(u["usuario"])
        else:
            nav = '<a href="/sesion/">Entrar</a>'
            nombre_js = ""
        respuesta.cuerpo = (respuesta.cuerpo
                            .replace(b"{{usuario_nav}}", nav.encode("utf-8"))
                            .replace(b"{{usuario_js}}", nombre_js.encode("utf-8")))
        return respuesta

    def servir(self, ip, puerto, tls=None):
        servidor = ThreadingHTTPServer((ip, puerto), _Manejador)
        servidor.app = self
        servidor.daemon_threads = True
        if tls:
            import ssl
            contexto = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            contexto.load_cert_chain(tls[0], tls[1])
            servidor.socket = contexto.wrap_socket(servidor.socket, server_side=True)
        self._httpd = servidor
        return servidor

    def servir_para_siempre(self, ip, puerto, tls=None):
        servidor = self.servir(ip, puerto, tls=tls)
        hilo = threading.Thread(target=servidor.serve_forever, daemon=True)
        hilo.start()
        return servidor
