# 🌐 MiRed — tu internet independiente (Fase 1: el núcleo)

Un mini-internet completo que corre en tu **Redmi 9C con Termux** (sin root):
otros dispositivos se conectan a tu hotspot y acceden a **tu propio portal,
buscador, wiki y sistema de nombres**. Nada depende de internet real.

> Este proyecto vive en esta carpeta (`mired/`) y **no toca** el resto del
> repositorio (AirGesture Transfer).

---

## 1. Requisitos

- Teléfono **Xiaomi Redmi 9C** (o cualquier Android) con **Termux**
  (instalado desde [F-Droid](https://f-droid.org/packages/com.termux/) o
  GitHub, **no** de Play Store).
- ~150 MB de espacio libre.
- Sin root. No hace falta.

## 2. Instalación (5 minutos)

Abre Termux y ejecuta:

```bash
pkg install -y git
git clone https://github.com/alexsndersoto04-source/moon
cd moon/mired
chmod +x *.sh
./instalar.sh
```

> **¿Git te pide "Username for github.com"?** Eso pasa porque el repositorio
> es **privado** y `git clone` sin sesión no puede leerlo. Soluciones:
>
> 1. **Sin cuenta (recomendada):** descarga el ZIP/tarball del proyecto con
>    tu navegador (en GitHub: *Code → Download ZIP*; o pide el enlace de
>    descarga directa al asistente), llévalo a Termux y extráelo:
>
>    ```bash
>    # si descargaste con el navegador del teléfono:
>    pkg install -y unzip && termux-setup-storage
>    cd ~/storage/downloads && unzip moon-main.zip -d ~/
>    ```
>
> 2. **Hacer el repositorio público** (GitHub → Settings → Danger Zone →
>    Change visibility), para que `git clone` funcione sin login siempre.
>
> 3. **Clonar con tu sesión de GitHub**: crea un *Personal Access Token* en
>    GitHub → Settings → Developer settings y úsalo como contraseña de git
>    (nunca lo compartas en chats).

> Si no quieres git, también puedes descargar el ZIP del repositorio,
> copiarlo al teléfono y descomprimirlo con `termux-setup-storage` +
> `unzip`.

El instalador pone Python, crea las bases de datos y genera un archivo
`hosts.txt` con las direcciones de tu red.

## 3. Arrancar tu internet

```bash
./iniciar.sh     # arranca portal + wiki + buscador + DNS
./estado.sh      # muestra las URLs y la salud del sistema
./detener.sh     # lo apaga todo
```

## 4. Conectar tus otros dispositivos

1. Activa el **hotspot (punto de acceso)** del Redmi 9C.
2. Conecta tu computadora / tablet / otro teléfono a esa red Wi-Fi.
3. Abre la URL del portal que muestra `./estado.sh`
   (normalmente `http://192.168.43.1:8080`).

¡Listo! Ya estás navegando tu propio internet.

### Nombres bonitos (opcional)

En una computadora, agrega las líneas de `hosts.txt` al archivo hosts:

| Sistema   | Archivo                                          |
|-----------|--------------------------------------------------|
| Windows   | `C:\Windows\System32\drivers\etc\hosts` (admin)  |
| Linux     | `/etc/hosts`                                     |
| macOS     | `/etc/hosts`                                     |

Después usa `http://wiki.mired:8080`, `http://buscar.mired:8080`, etc.
El servidor DNS local (UDP `5353`) también resuelve `*.mired` para las
herramientas que aceptan puertos de DNS personalizados.

---

## 5. Qué incluye MiRed

| Servicio   | Qué es                                                | URL            |
|------------|-------------------------------------------------------|----------------|
| **Portal**    | Página de inicio con todo tu internet              | `/`            |
| **Buscador**  | Tu "Google" local: rastrea e indexa tu red         | `/buscar/`     |
| **Wiki**      | Enciclopedia con Markdown e historial de ediciones  | `/wiki/`       |
| **Chat**      | Conversaciones en tiempo real por salas             | `/chat/`       |
| **Correo**    | Correo interno `@tu-zona` con webmail y servidor SMTP (puerto 2525) | `/correo/` |
| **Foro**      | Hilos de conversación por categorías                | `/foro/`       |
| **Nube**      | Carpetas, subida de archivos, descarga y borrado    | `/nube/`       |
| **Streaming** | Biblioteca de música y vídeos con reproductor       | `/streaming/`  |
| **Estado**    | Salud del sistema (RAM, tiempo activo, índice)     | `/estado/`     |
| **Ayuda**     | Instrucciones de conexión y DNS                    | `/ayuda/`      |
| **DNS**       | Resuelve `*.mired` hacia tu teléfono (UDP 5353)    | —              |
| **SMTP**      | Clientes de correo externos pueden enviar al puerto 2525 | —       |

La wiki viene con artículos iniciales: [[Qué_es_MiRed]],
[[Cómo_conectar_tus_dispositivos]], [[Guía_de_edición]]…

**Diseño**: interfaz minimalista y sobria — tipografía cuidada, espacio en
blanco, sin adornos innecesarios, a la altura de las buenas webs del
internet real.

**Sobre el correo**: solo se entrega correo interno entre direcciones
`@tu-zona` (tu internet no hace de reley a internet — eso llegaría en la
Fase 4 con configuración explícita). No hay contraseñas todavía: tu red
es de confianza. Un cliente de correo (Thunderbird, K-9 Mail…) puede
enviar a tu servidor SMTP en `IP:2525` sin autenticación.

## 6. Estructura del proyecto

```
mired/
├── instalar.sh          # instalación en Termux
├── iniciar.sh           # arranca todo (con wake-lock)
├── detener.sh           # detiene todo
├── estado.sh            # URLs y estado
├── config.json          # zona, puertos, IP…
├── mired/               # el código Python (solo biblioteca estándar)
│   ├── core/            # micro-framework HTTP, SQLite, Markdown, plantillas
│   ├── services/        # portal, wiki, buscador, DNS
│   ├── plantillas/      # HTML
│   └── static/          # CSS
└── data/                # se crea al instalar: bases de datos y logs
```

Todo con la **biblioteca estándar de Python**: cero dependencias, mínimo
consumo de RAM, ideal para el Redmi 9C.

## 7. Preguntas frecuentes

**¿Se apaga cuando el teléfono se duerme?**
`iniciar.sh` ejecuta `termux-wake-lock` si tienes Termux:API instalado.
También puedes desactivar la optimización de batería para Termux en
Ajustes → Apps → Termux.

**¿Se pierden mis datos?** No: todo vive en `data/mired.db` (SQLite).
Haz copias de esa carpeta si quieres respaldo.

**¿Funciona sin datos móviles?** Sí. Tu internet es la red local del
hotspot; no necesita internet (el DNS solo reenvía a internet si lo hay).

**¿Y la seguridad?** Fase 1 es una red de confianza (como tu casa). Las
contraseñas y HTTPS con certificados propios llegan en la Fase 4.

## 8. Hoja de ruta

- ✅ **Fase 1 — Núcleo**: portal, buscador, wiki, DNS
- ✅ **Fase 2 — Comunicación**: chat, correo `@mired` con webmail y SMTP, foro
- ✅ **Fase 3 — Archivos y medios**: nube de archivos y streaming con reproductor
- ⬜ **Fase 4 — Seguridad**: usuarios y contraseñas, HTTPS con CA propia, acceso remoto
- ⬜ **Fase 5 — Extras**: juegos multijugador, API pública, automatizaciones

### Nube y streaming

- La nube guarda todo en `data/archivos/` — crea carpetas, sube archivos
  (el límite lo pones tú en `config.json`) y comparte con el enlace de descarga.
- La música y los vídeos se organizan solos en `musica/` y `videos/`;
  el reproductor permite adelantar/retrasar (soporte HTTP Range).
