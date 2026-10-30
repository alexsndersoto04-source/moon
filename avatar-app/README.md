# Moon Avatar 🌙

Tu avatar humano personalizado con cerebro de IA. **App independiente de
AirGesture Transfer**: otro `applicationId` (`com.moon.avatar`), otro APK,
otro workflow. Instalarla no toca la app de gestos.

## Qué es (y qué NO es)

No es un chatbot: no hay lista de mensajes ni burbujas. El avatar ocupa toda
la pantalla — respira, parpadea, se balancea, mueve la boca al hablar
(lip-sync), expresa emociones y hace gestos (saludar, celebrar, asentir,
negar, pensar, reverencia). Le hablas por voz y te responde **actuando**.

## El cerebro

Flujo: 🎤 tu voz → texto (SpeechRecognizer de Android) → 🧠 IA en la nube →
respuesta conversacional + emoción/gesto → 🗣️ voz del teléfono o voz Gemini.

- Proveedores compatibles en esta versión: **Gemini** (modelo Flash elegido
  automáticamente) y **Groq** (`llama-3.3-70b-versatile`). Una API key no es
  universal: una clave de OpenAI/ChatGPT, OpenRouter u otro proveedor no funciona
  si no eliges un conector compatible.
- La API key se pega una vez en ⚙️ Ajustes y se guarda **solo en el teléfono**.
  Nunca va en el código ni en GitHub; no la compartas por chat.
- Sin clave o si la API rechaza la petición, el avatar usa respuestas locales
  básicas y muestra el motivo. Eso no es la IA en la nube.
- Memoria: las últimas conversaciones viven en el teléfono (SharedPreferences
  privadas). Botón «Borrar memoria» en Ajustes.
- La respuesta pide al modelo varias frases completas y naturales. La voz
  generada por Gemini es una **segunda petición a internet**; para empezar a oír
  antes, en Ajustes elige «Sistema del teléfono (rápida, local)». La voz española
  disponible depende de Android y se puede descargar desde sus ajustes.

### Sacar tu clave gratuita (2 minutos)

- Gemini: https://aistudio.google.com → «Get API key»
- Groq: https://console.groq.com → «API Keys»

## Personalización (⚙️ Ajustes)

Nombre del avatar, tu nombre, su personalidad, tono de piel, estilo y color
de pelo, y color de ropa. Todo se aplica y se guarda al instante.

## Instalar el APK

GitHub Actions (workflow `Build Moon Avatar APK`) compila en cada push que
toque `avatar-app/**` y publica un Release `avatar-v0.1.0-buildN` con
`MoonAvatar-0.1.0.apk`. Descárgalo en el teléfono e instálalo (permite
«Instalar apps de origen desconocido» en tu navegador).

## Vista previa sin instalar

`preview/index.html` es el mismo avatar en versión navegador (voz y cerebro
incluidos, con las claves guardadas solo en tu navegador). Sirve para verlo
y probarlo antes de instalar el APK.

## Estructura

```
avatar-app/
├── build.gradle                  # módulo :avatar, applicationId com.moon.avatar
├── preview/index.html            # vista previa navegable
└── src/main/
    ├── AndroidManifest.xml       # INTERNET + RECORD_AUDIO
    ├── java/com/moon/avatar/
    │   ├── MainActivity.kt       # pantalla única: voz (STT/TTS) + UI
    │   ├── AvatarView.kt         # el avatar animado (Canvas, sin assets)
    │   └── Brain.kt              # Gemini/Groq + memoria + cerebro local
    └── res/layout/…              # la interfaz (el avatar es el protagonista)
```

## v0.2 — Avatar 3D real (sustituye a la caricatura)

La primera versión dibujaba un avatar 2D en Canvas: se descartó por completo
(`AvatarView.kt` eliminado). Ahora el avatar es un **modelo humano 3D masculino**
(GLB con esqueleto y animaciones reales de Mixamo, servido por threejs.org),
renderizado con Three.js/WebGL dentro de la app (`assets/avatar3d.html`):

- Vida idle automática: respiración, balanceo, cabeceo — nunca una estatua.
- Al hablar: cabeceo sincronizado con la voz.
- **Sin gestos manuales**: el botón «Gestos» desapareció. Las reacciones las
  decide el cerebro en cada respuesta (asentir `agree`, negar `headShake`,
  pensar, celebrar…) y el avatar las actúa solo.

### Tu propio avatar desde una foto (lo más "tú" y realista posible)

Un humano fotorrealista nivel película no es viable en un teléfono modesto.
La vía real para que se parezca a ti: crea tu avatar gratis en
**readyplayer.me** (desde una selfie), copia el enlace `.glb` de tu avatar y
pégalo en ⚙️ Ajustes → «Su cuerpo 3D». La app lo cargará con las mismas
reacciones automáticas.

## v0.5 — el modelo va EN el repositorio (cero descargas)

Las v0.3/v0.4 fallaron porque el modelo se bajaba de un servidor externo
(al abrir la app o al compilar) y esos enlaces están caídos. Ahora
`assets/avatar_model.glb` (8 MB) está commiteado: un avatar masculino
Ready Player Me (texturas PBR, 52 morphs faciales ARKit, esqueleto RPM
Hips/Spine/Head — el mismo de las animaciones mocap empaquetadas),
tomado del repo MIT GodotXRAnimationRecorder (Malcolmnixon), que a su vez
lo exportó de Ready Player Me. El APK es 100% autónomo: motor three.js,
modelo y animaciones viajan dentro y funcionan sin internet.
