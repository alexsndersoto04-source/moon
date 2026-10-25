#!/usr/bin/env python3
"""
Sintetizador de los efectos de agua de MoonShare.

Genera los .wav que van en app/src/main/res/raw/. No usa dependencias externas
(solo stdlib) para que se pueda regenerar en cualquier maquina:

    python3 tools/make_sfx.py

Diseno sonoro
-------------
La brief pide "un sonido muy elegante y sutil, como de agua, cuando pasa un
archivo". Eso es una gota: un transitorio corto + un resonador de cavidad que
barre la frecuencia hacia abajo (glissando descendente) + una cola de
reverberacion generada con peines de Schroeder, todo con envolvente exponencial.

  sfx_water_in.wav   gota brillante, registro medio-alto  -> archivo RECIBIDO
  sfx_water_out.wav  gota mas grave y oscura               -> archivo ENVIADO
  sfx_pair.wav       dos gotas consecutivas (acople ok)    -> pareo NFC exitoso
"""

from __future__ import annotations

import math
import os
import struct
import wave

SR = 44100
OUT_DIR = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
)


# --------------------------------------------------------------------------- utils
def clamp(x: float, lo: float = -1.0, hi: float = 1.0) -> float:
    return lo if x < lo else (hi if x > hi else x)


def one_pole_lp(samples: list[float], cutoff_ratio: float) -> list[float]:
    """Filtro paso-bajo de un polo. cutoff_ratio en (0, 1], 1 = sin filtro."""
    a = clamp(cutoff_ratio, 0.0005, 1.0)
    out, y = [], 0.0
    for x in samples:
        y += a * (x - y)
        out.append(y)
    return out


def comb(input_sig: list[float], delay: int, feedback: float, damp: float) -> list[float]:
    """Peine de Schroeder con amortiguamiento interno (estereo mono-aqui)."""
    n = len(input_sig)
    buf = [0.0] * delay
    idx = 0
    lp = 0.0
    out = [0.0] * n
    for i in range(n):
        delayed = buf[idx]
        lp = (1.0 - damp) * delayed + damp * lp
        buf[idx] = input_sig[i] + lp * feedback
        out[i] = lp
        idx += 1
        if idx >= delay:
            idx = 0
    return out


def allpass(input_sig: list[float], delay: int, g: float) -> list[float]:
    n = len(input_sig)
    buf = [0.0] * delay
    idx = 0
    out = [0.0] * n
    for i in range(n):
        delayed = buf[idx]
        y = -g * input_sig[i] + delayed
        buf[idx] = input_sig[i] + g * delayed
        out[i] = y
        idx += 1
        if idx >= delay:
            idx = 0
    return out


def reverb(samples: list[float], mix: float = 0.35, decay: float = 0.62) -> list[float]:
    """4 peines en paralelo + 2 all-pass en serie. Da la sensacion de gota en
    un recipiente de vidrio sin necesitar un motor de efectos."""
    delays = [1557, 1617, 1491, 1422]
    wet = [0.0] * len(samples)
    for d in delays:
        c = comb(samples, d, decay, 0.30)
        for i in range(len(wet)):
            wet[i] += c[i]
    for i in range(len(wet)):
        wet[i] *= 0.25
    wet = allpass(wet, 227, 0.42)
    wet = allpass(wet, 311, 0.31)
    return [(1.0 - mix) * samples[i] + mix * wet[i] for i in range(len(samples))]


def normalize(samples: list[float], peak: float = 0.89) -> list[float]:
    m = max((abs(s) for s in samples), default=0.0)
    if m < 1e-6:
        return samples
    k = peak / m
    return [s * k for s in samples]


def fade_out(samples: list[float], sec: float = 0.04) -> list[float]:
    n = len(samples)
    f = min(int(SR * sec), n)
    for i in range(f):
        samples[n - 1 - i] *= (i / f) ** 2
    return samples


def write_wav(path: str, samples: list[float]) -> None:
    frames = bytearray()
    for s in samples:
        frames += struct.pack("<h", int(clamp(s) * 32767.0))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(bytes(frames))
    kb = os.path.getsize(path) / 1024.0
    print(f"  · {os.path.basename(path):<18} {len(samples)/SR*1000:6.1f} ms   {kb:6.1f} KB")


# --------------------------------------------------------------------------- voces
def drop(
    dur: float,
    f0: float,
    f1: float,
    glide: float,
    body: float,
    bloom: float,
    noise: float,
    overtone: float,
) -> list[float]:
    """Una gota de agua.

    f0/f1   : glissando descendente de la resonancia principal
    body    : peso del seno fundamental
    bloom   : "flor" de frecuencia al inicio (el chispaso agudo del impacto)
    noise   : nivel del transitorio de burbuja (ruido filtrado)
    overtone: segundo y tercer modo de la cavidad (dan el timbre de vidrio)
    """
    n = int(SR * dur)
    out: list[float] = [0.0] * n

    # Transitorio de impacto: ruido "deterministico" filtrado + envolvente de 1 ms
    if noise > 0:
        nz = [math.sin(i * 12.9898 + 78.233) * 43758.5453 % 2.0 - 1.0 for i in range(n)]
        nz = one_pole_lp(nz, 0.28)
        for i in range(n):
            e = math.exp(-i / (SR * 0.0035))
            out[i] += nz[i] * e * noise

    phase = 0.0
    att = max(1, int(SR * 0.0004))
    for i in range(n):
        t = i / SR
        g = min(1.0, t / glide)
        f = f1 + (f0 - f1) * math.exp(-3.4 * g)
        f *= 1.0 + bloom * math.exp(-t / 0.010)
        phase += 2.0 * math.pi * f / SR

        a = min(1.0, t / (att / SR))
        env = a * math.exp(-t / (dur * 0.30))
        env2 = a * math.exp(-t / (dur * 0.12))

        s = body * math.sin(phase) * env
        s += 0.38 * overtone * math.sin(phase * 2.017) * env2
        s += 0.16 * overtone * math.sin(phase * 3.011) * env2 * math.exp(-t / 0.05)
        out[i] += s

    return one_pole_lp(out, 0.62)


def make_water(dur: float, f0: float, f1: float, mix: float) -> list[float]:
    s = drop(
        dur=dur, f0=f0, f1=f1,
        glide=0.055, body=1.0, bloom=0.34, noise=0.20, overtone=0.85,
    )
    s = reverb(s, mix=mix, decay=0.52)
    return fade_out(normalize(s, 0.88))


def make_pair() -> list[float]:
    """Dos gotas: la segunda una 5a arriba, imitando el 'clic' de conexion."""
    a = make_water(0.30, 1180.0, 700.0, 0.34)
    b = drop(dur=0.42, f0=1770.0, f1=1050.0, glide=0.05,
             body=0.9, bloom=0.28, noise=0.14, overtone=0.7)
    b = reverb(b, mix=0.44, decay=0.58)
    b = normalize(b, 0.62)

    gap = int(SR * 0.10)
    off = len(a) + gap
    total = off + len(b)
    out = [0.0] * total
    for i, v in enumerate(a):
        out[i] += v
    for i, v in enumerate(b):
        out[off + i] += v * 0.85
    return fade_out(normalize(out, 0.86))


# --------------------------------------------------------------------------- main
def main() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    print("MoonShare · sintetizando SFX de agua\n")

    targets = {
        # entrante: mas clara y viva, invita
        "sfx_water_in.wav": make_water(0.52, 1320.0, 660.0, 0.38),
        # saliente: mas grave y corta, no fatiga en rafaga de 30 fotos
        "sfx_water_out.wav": make_water(0.40, 990.0, 520.0, 0.30),
        "sfx_pair.wav": make_pair(),
    }
    for name, data in targets.items():
        write_wav(os.path.join(OUT_DIR, name), data)

    print(f"\nListo → {OUT_DIR}")


if __name__ == "__main__":
    main()
