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
JSON `{"text", "emotion", "gesture"}` → 🗣️ el avatar lo dice con voz
(TextToSpeech en español), expresión y gesto a la vez.

- Proveedores: **Gemini** (`gemini-2.0-flash`) o **Groq**
  (`llama-3.3-70b-versatile`). Ambos con nivel gratuito.
- La API key se pega una vez en ⚙️ Ajustes y se guarda **solo en el teléfono**.
  Nunca va en el código ni en GitHub.
- Sin clave o sin conexión: cerebro local de respaldo con respuestas y
  gestos básicos — el avatar nunca se queda mudo.
- Memoria: las últimas conversaciones y tus datos viven en el teléfono
  (SharedPreferences privadas). Botón «Borrar memoria» en Ajustes.

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
