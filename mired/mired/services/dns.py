"""Servidor DNS local (sin root, en puerto alto).

Resuelve todos los nombres de la zona (por defecto ``*.mired``) hacia la IP
del teléfono. El resto de consultas se reenvían al DNS upstream configurado
para que los clientes sigan teniendo internet normal si lo necesitan.

Funciona con la biblioteca estándar: parsea y construye paquetes DNS a mano.
"""

import socket
import struct
import threading

TIPO_A = 1
TIPO_AAAA = 28
CLASE_IN = 1


def _leer_nombre(datos, inicio):
    etiquetas = []
    salto = None
    i = inicio
    while True:
        if i >= len(datos):
            raise ValueError("nombre truncado")
        largo = datos[i]
        if largo == 0:
            i += 1
            break
        if largo & 0xC0 == 0xC0:          # puntero de compresión
            if salto is None:
                salto = i + 2
            i = ((largo & 0x3F) << 8) | datos[i + 1]
            continue
        etiquetas.append(datos[i + 1:i + 1 + largo].decode("latin-1"))
        i += 1 + largo
    return ".".join(etiquetas), (salto if salto is not None else i)


def _construir_nombre(nombre):
    partes = []
    for etiqueta in nombre.rstrip(".").split("."):
        crudo = etiqueta.encode("latin-1")
        partes.append(bytes([len(crudo)]) + crudo)
    return b"".join(partes) + b"\x00"


class ServidorDNS:
    def __init__(self, ip_destino, zona, puerto=5353, upstream="8.8.8.8"):
        self.ip_destino = ip_destino
        self.zona = zona.lower().rstrip(".")
        self.puerto = puerto
        self.upstream = upstream
        self._sock = None
        self._hilo = None
        self._activo = False

    # -- ciclo de vida -------------------------------------------------
    def iniciar(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(("0.0.0.0", self.puerto))
        # mDNS: unirse al grupo multicast para responder nombres
        # *.zona.local sin configurar nada en los dispositivos.
        try:
            grupo = socket.inet_aton("224.0.0.251") + socket.inet_aton("0.0.0.0")
            self._sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, grupo)
            self._sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
        except OSError:
            pass
        self._activo = True
        self._hilo = threading.Thread(target=self._bucle, daemon=True,
                                     name="mired-dns")
        self._hilo.start()

    def detener(self):
        self._activo = False
        if self._sock:
            try:
                self._sock.close()
            except OSError:
                pass

    # -- lógica --------------------------------------------------------
    def _bucle(self):
        while self._activo:
            try:
                datos, cliente = self._sock.recvfrom(4096)
            except OSError:
                break
            try:
                es_mdns = cliente[1] == 5353
                respuesta = self._responder(datos, es_mdns)
                if respuesta:
                    self._sock.sendto(respuesta, cliente)
                    if es_mdns:  # mDNS: la respuesta se difunde al grupo
                        try:
                            self._sock.sendto(respuesta, ("224.0.0.251", 5353))
                        except OSError:
                            pass
            except Exception:
                continue

    def _responder(self, consulta, es_mdns=False):
        if len(consulta) < 12:
            return None
        if consulta[2] & 0x80:      # es una respuesta (p. ej. la propia
            return None             # difusión mDNS): ignorarla
        identidad = consulta[:2]
        try:
            nombre, fin_pregunta = _leer_nombre(consulta, 12)
        except ValueError:
            return None
        if fin_pregunta + 4 > len(consulta):
            return None
        tipo, clase = struct.unpack("!HH", consulta[fin_pregunta:fin_pregunta + 4])
        pregunta = consulta[12:fin_pregunta + 4]

        nombre_bajo = nombre.lower().rstrip(".")
        local = nombre_bajo.endswith(".local")
        if local:
            # wiki.mired.local → wiki.mired  (mDNS pide la zona .local)
            nombre_bajo = nombre_bajo[:-len(".local")].rstrip(".")
        en_zona = (nombre_bajo == self.zona or
                   nombre_bajo.endswith("." + self.zona))

        if en_zona:
            return self._respuesta_local(identidad, pregunta, nombre,
                                         tipo, clase, es_mdns)
        if local:
            return None  # otros nombres .local no son nuestros

        # Fuera de zona → reenviar al upstream (si falla, rechazar)
        return self._reenviar(consulta) or self._rechazo(identidad, pregunta)

    def _respuesta_local(self, identidad, pregunta, nombre, tipo, clase,
                         es_mdns=False):
        banderas = 0x8400 if es_mdns else 0x8180  # mDNS: QR+AA
        cabecera = identidad + struct.pack("!HHHHH", banderas, 1, 0, 0, 0)
        if tipo == TIPO_A and clase == CLASE_IN:
            ip = socket.inet_aton(self.ip_destino)
            respuesta = (b"\xc0\x0c" + struct.pack("!HHIH", TIPO_A, CLASE_IN, 60, 4) + ip)
            cabecera = identidad + struct.pack("!HHHHH", banderas, 1, 1, 0, 0)
            return cabecera + pregunta + respuesta
        # AAAA u otros tipos dentro de la zona: sin registros (NODATA)
        return cabecera + pregunta

    def _rechazo(self, identidad, pregunta):
        cabecera = identidad + struct.pack("!HHHHH", 0x8185, 1, 0, 0, 0)  # REFUSED
        return cabecera + pregunta

    def _reenviar(self, consulta):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                sock.settimeout(3)
                sock.sendto(consulta, (self.upstream, 53))
                datos, _ = sock.recvfrom(4096)
                # Mantener el id original por si el upstream lo cambia
                return consulta[:2] + datos[2:]
        except OSError:
            return None
