"""Detección de la IP local del teléfono (sin root, sin iproute2).

Usa la ioctl SIOCGIFADDR sobre cada interfaz para obtener sus direcciones
IPv4 y elige la adecuada según el modo de red:

* hotspot del teléfono  → suele ser 192.168.43.x (se prefiere esta)
* conectado a un router → 192.168.x.x / 10.x.x.x
"""

import socket
import struct

try:
    import fcntl
except ImportError:  # improbable en Linux/Android
    fcntl = None

_SIOCGIFADDR = 0x8915


def direcciones():
    """Devuelve una lista de (interfaz, ip) para todas las IPv4 activas."""
    resultado = []
    if fcntl is None:
        return resultado
    try:
        interfaces = socket.if_nameindex()
    except OSError:
        return resultado
    for _idx, nombre in interfaces:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            paquete = struct.pack("256s", nombre.encode("utf-8")[:15])
            datos = fcntl.ioctl(sock.fileno(), _SIOCGIFADDR, paquete)
            ip = socket.inet_ntoa(datos[20:24])
            resultado.append((nombre, ip))
        except OSError:
            continue
        finally:
            sock.close()
    return resultado


def _puntaje(ip):
    """Menor = mejor. Prefiere la red del hotspot (192.168.43.x)."""
    if ip.startswith("127."):
        return 999
    if ip.startswith("192.168.43."):
        return 0
    if ip.startswith("192.168."):
        return 1
    if ip.startswith("10."):
        return 2
    partes = ip.split(".")
    if len(partes) == 4 and partes[0] == "172":
        try:
            if 16 <= int(partes[1]) <= 31:
                return 3
        except ValueError:
            pass
    return 4


def ip_local():
    """Devuelve la mejor IP local para que los demás dispositivos te alcancen."""
    candidatas = [(ip, _puntaje(ip)) for _n, ip in direcciones() if not ip.startswith("127.")]
    if not candidatas:
        return "192.168.43.1"  # valor habitual del hotspot Android
    candidatas.sort(key=lambda t: t[1])
    return candidatas[0][0]


def es_hotspot():
    return any(ip.startswith("192.168.43.") for _n, ip in direcciones())
