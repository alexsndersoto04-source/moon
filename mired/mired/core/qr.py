"""Generador de códigos QR en SVG — solo biblioteca estándar.

Implementa el modo byte (versiones 1–4, corrección L/M, un solo bloque)
con Reed-Solomon real sobre GF(256). Suficiente para URLs y contraseñas
de red Wi-Fi. El resultado es un SVG nítido en cualquier pantalla.
"""

# ---------- Reed-Solomon sobre GF(256) --------------------------------

def _multiplicar(x, y):
    z = 0
    for i in range(7, -1, -1):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((y >> i) & 1) * x
    return z


def _divisor_rs(grado):
    resultado = [0] * (grado - 1) + [1]
    raiz = 1
    for _ in range(grado):
        for j in range(len(resultado)):
            resultado[j] = _multiplicar(resultado[j], raiz)
            if j + 1 < len(resultado):
                resultado[j] ^= resultado[j + 1]
        raiz = _multiplicar(raiz, 0x02)
    return resultado


def _resto_rs(datos, divisor):
    resultado = [0] * len(divisor)
    for byte in datos:
        factor = byte ^ resultado.pop(0)
        resultado.append(0)
        for i in range(len(resultado)):
            resultado[i] ^= _multiplicar(divisor[i], factor)
    return resultado


# ---------- tablas (versiones de un solo bloque) ----------------------
# versión → tamaño; (ec) → (codewords totales, datos, ec)
_TABLA = {
    1: {"L": (26, 19, 7), "M": (26, 16, 10)},
    2: {"L": (44, 34, 10), "M": (44, 28, 16)},
    3: {"L": (70, 55, 15), "M": (70, 44, 26)},
    4: {"L": (100, 80, 20)},   # V4-M usa dos bloques: solo L en V4
}
_BITS_EC = {"L": 1, "M": 0}
_ALINEAMIENTO = {1: [], 2: [18], 3: [22], 4: [26]}


def _elegir(texto):
    n = len(texto.encode("utf-8"))    # el conteo va en BYTES
    for ec in ("M", "L"):                 # M corrige más: se prefiere
        for version in (1, 2, 3, 4):
            if version == 4 and ec == "M":
                continue                  # V4-M usa dos bloques: no soportado
            _, datos, _ = _TABLA[version][ec]
            if n <= datos - 2:            # cabecera de 12 bits ≈ 2 bytes
                return version, ec
    raise ValueError("texto demasiado largo para el QR")


def _bits_texto(texto, version, ec):
    _, n_datos, _ = _TABLA[version][ec]
    capacidad = n_datos * 8
    datos_bytes = texto.encode("utf-8")
    bits = [0, 1, 0, 0]                          # modo byte
    bits += [(len(datos_bytes) >> i) & 1 for i in range(7, -1, -1)]
    for byte in datos_bytes:
        bits += [(byte >> i) & 1 for i in range(7, -1, -1)]
    bits += [0] * min(4, capacidad - len(bits))  # terminador
    bits += [0] * ((8 - len(bits) % 8) % 8)      # alinear byte
    bytes_datos = [int("".join(map(str, bits[i:i + 8])), 2)
                   for i in range(0, len(bits), 8)]
    relleno = (0xEC, 0x11)
    i = 0
    while len(bytes_datos) < n_datos:
        bytes_datos.append(relleno[i % 2])
        i += 1
    return bytes_datos


def _codewords(texto, version, ec):
    _, n_datos, n_ec = _TABLA[version][ec]
    datos = _bits_texto(texto, version, ec)
    divisor = _divisor_rs(n_ec)
    return datos + _resto_rs(datos, divisor)


# ---------- matriz ----------------------------------------------------

def _nueva(version):
    n = 17 + 4 * version
    m = [[False] * n for _ in range(n)]
    f = [[False] * n for _ in range(n)]      # módulos de función

    def pintar(x, y, oscuro):
        if 0 <= x < n and 0 <= y < n:
            m[y][x] = oscuro
            f[y][x] = True

    # buscadores (3 esquinas) con separador
    for cx, cy in ((0, 0), (n - 7, 0), (0, n - 7)):
        for dy in range(-1, 8):
            for dx in range(-1, 8):
                borde = (0 <= dx <= 6 and 0 <= dy <= 6 and
                         (dx in (0, 6) or dy in (0, 6) or
                          (2 <= dx <= 4 and 2 <= dy <= 4)))
                pintar(cx + dx, cy + dy, borde)
    # patrones de sincronía
    for i in range(8, n - 8):
        pintar(6, i, i % 2 == 0)
        pintar(i, 6, i % 2 == 0)
    # alineamientos
    pos = _ALINEAMIENTO[version]
    for cx in pos:
        for cy in pos:
            if f[cy][cx]:
                continue
            for dy in range(-2, 3):
                for dx in range(-2, 3):
                    anillo = max(abs(dx), abs(dy)) != 1
                    pintar(cx + dx, cy + dy, anillo)
    # módulo oscuro + reservar formatos
    pintar(8, n - 8, True)
    for i in range(9):
        if not f[8][i]:
            pintar(i, 8, False)
        if not f[i][8]:
            pintar(8, i, False)
    for i in range(8):
        pintar(n - 1 - i, 8, False)
        pintar(8, n - 1 - i, False)
    return m, f, n


def _colocar(m, f, n, codewords):
    i = 0
    total = len(codewords) * 8
    derecha = n - 1
    while derecha >= 1:
        if derecha == 6:
            derecha = 5
        for vert in range(n):
            for j in range(2):
                x = derecha - j
                arriba = ((derecha + 1) & 2) == 0
                y = n - 1 - vert if arriba else vert
                if not f[y][x] and i < total:
                    m[y][x] = (codewords[i >> 3] >> (7 - (i & 7))) & 1 == 1
                    i += 1
        derecha -= 2


def _mascara(x, y, k):
    return (
        (x + y) % 2 == 0, y % 2 == 0, x % 3 == 0, (x + y) % 3 == 0,
        (x // 3 + y // 2) % 2 == 0, x * y % 2 + x * y % 3 == 0,
        (x * y % 2 + x * y % 3) % 2 == 0,
        ((x + y) % 2 + x * y % 3) % 2 == 0,
    )[k]


def _formato(ec, mascara):
    datos = (_BITS_EC[ec] << 3) | mascara
    resto = datos
    for _ in range(10):
        resto = (resto << 1) ^ ((resto >> 9) * 0x537)
    return ((datos << 10) | resto) ^ 0x5412


def _pintar_formato(m, n, bits):
    for i in range(6):
        m[i][8] = (bits >> i) & 1 == 1
    m[7][8] = (bits >> 6) & 1 == 1
    m[8][8] = (bits >> 7) & 1 == 1
    m[8][7] = (bits >> 8) & 1 == 1
    for i in range(9, 15):
        m[8][14 - i] = (bits >> i) & 1 == 1
    for i in range(8):
        m[8][n - 1 - i] = (bits >> i) & 1 == 1
    for i in range(8, 15):
        m[n - 15 + i][8] = (bits >> i) & 1 == 1
    m[n - 8][8] = True   # módulo oscuro


def _penalizacion_linea(linea, n):
    """Reglas 1 (corridas) y 3 (patrones tipo buscador) en una fila/columna."""
    p = 0
    historial = [0] * 7
    color = False
    run = 0
    for celda in linea:
        if celda == color:
            run += 1
            if run == 5:
                p += 3
            elif run > 5:
                p += 1
        else:
            _historial(run, historial, n)
            if not color:
                p += 40 * _patrones(historial)
            color = celda
            run = 1
    if color:
        _historial(run, historial, n)
        run = 0
    run += n                      # borde claro final
    _historial(run, historial, n)
    p += 40 * _patrones(historial)
    return p


def _historial(run, historial, n):
    if historial[0] == 0:
        run += n                  # borde claro inicial
    historial.pop()
    historial.insert(0, run)


def _patrones(h):
    n = h[1]
    nucleo = (n > 0 and h[2] == n and h[3] == n * 3 and h[4] == n and h[5] == n)
    return ((1 if nucleo and h[0] >= n * 4 and h[6] >= n else 0) +
            (1 if nucleo and h[6] >= n * 4 and h[0] >= n else 0))


def _penalizacion(m, n):
    p = 0
    # reglas 1 y 3: filas y columnas
    for fila in m:
        p += _penalizacion_linea(fila, n)
    for col in zip(*m):
        p += _penalizacion_linea(col, n)
    # regla 2: bloques 2x2 del mismo color
    for y in range(n - 1):
        for x in range(n - 1):
            if m[y][x] == m[y][x + 1] == m[y + 1][x] == m[y + 1][x + 1]:
                p += 3
    # regla 4: balance de claros/oscuros
    oscuros = sum(fila.count(True) for fila in m)
    p += 10 * (abs(oscuros * 20 - n * n * 10) // (n * n))
    return p


def matriz(texto, mascara=None):
    """Devuelve (matriz booleana, tamaño) del código QR para `texto`.

    `mascara` (0–7) fuerza una máscara concreta; si se omite se elige
    la de menor penalización."""
    version, ec = _elegir(texto)
    cw = _codewords(texto, version, ec)
    m, f, n = _nueva(version)
    _colocar(m, f, n, cw)
    mejor, mejor_p = None, None
    for k in range(8):
        if mascara is not None and k != mascara:
            continue
        prueba = [fila[:] for fila in m]
        for y in range(n):
            for x in range(n):
                if not f[y][x] and _mascara(x, y, k):
                    prueba[y][x] = not prueba[y][x]
        _pintar_formato(prueba, n, _formato(ec, k))
        p = _penalizacion(prueba, n)
        if mejor_p is None or p < mejor_p:
            mejor, mejor_p = prueba, p
    return mejor, n


def svg(texto, ancho=240, margen=2):
    """Devuelve el código QR como SVG listo para insertar en HTML."""
    m, n = matriz(texto)
    total = n + margen * 2
    trozos = []
    for y in range(n):
        x = 0
        while x < n:
            if m[y][x]:
                inicio = x
                while x < n and m[y][x]:
                    x += 1
                trozos.append("M%d %dh%dv1h-%dz" % (inicio + margen, y + margen,
                                                    x - inicio, x - inicio))
            else:
                x += 1
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" '
            'width="%d" height="%d" shape-rendering="crispEdges" '
            'role="img" aria-label="Código QR">'
            '<rect width="%d" height="%d" fill="#ffffff"/>'
            '<path fill="#1d1d1f" d="%s"/></svg>'
            % (total, total, ancho, ancho, total, total, "".join(trozos)))
