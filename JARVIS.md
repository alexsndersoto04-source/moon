# JARVIS 2.0.0 — Asistente personal con IA para Android

JARVIS es un asistente nativo para móvil, muy potente y totalmente funcional:
habla en español con voz realista, entiende por micrófono, conversa con IA
(OpenRouter + Groq con fallback automático) y **actúa en tu teléfono**:
abre apps, pone alarmas, crea recordatorios, gestiona tus proyectos y tareas,
enciende la linterna, busca en Maps/Google/YouTube, calcula, llama, manda
mensajes y mucho más.

> Funciona **sin claves** en modo local para las acciones del móvil.
> Con tus claves de OpenRouter y/o Groq, además conversa con IA de verdad.

---

## ⬇️ Descargar e instalar (lista para usar)

**Enlace directo al APK (Releases):**

👉 **https://github.com/alexsndersoto04-source/moon/releases/latest**

1. Abre el enlace de arriba en el teléfono y descarga **`JARVIS-2.0.0.apk`**.
2. Al abrirlo, Android pedirá permitir instalar apps de esta fuente:
   *Ajustes → Instalar apps desconocidas → tu navegador → Permitir*.
3. Instala y abre JARVIS. Concede **micrófono** (para la voz) y **notificaciones**
   (para los recordatorios).
4. Toca el banner amarillo **«Configura tus claves»** y sigue la sesión de
   configuración (1 minuto). Ya puedes usarlo.

Alternativa (si Releases aún está compilando):
**Actions → Build APK → último run → Artifacts → `JARVIS-apk`**
👉 https://github.com/alexsndersoto04-source/moon/actions

> Requisitos: Android 10+ (API 29), micrófono, conexión a internet para la IA
> y para el reconocimiento de voz. Se instala **encima** de versiones
> anteriores (firma estable de CI).

---

## 🔑 Configurar tus dos claves API (sesión guiada)

En **Ajustes / Claves IA** (pantalla de configuración):

### OpenRouter (muchos modelos: GPT, Claude, Gemini, Llama…)
1. Entra a **https://openrouter.ai/keys** y crea una clave (empieza por `sk-or-v1-…`).
2. Pégala en «Clave API de OpenRouter».
3. Modelo sugerido: `openai/gpt-4o-mini` (barato y bueno).
   Otros: `google/gemini-2.0-flash-001`, `meta-llama/llama-3.3-70b-instruct`,
   `deepseek/deepseek-chat`, `anthropic/claude-3.5-sonnet`.
4. Toca **«Probar conexión OpenRouter»** → debe decir `OK`.

### Groq (rapidísimo, ideal para voz en tiempo real)
1. Entra a **https://console.groq.com/keys** y crea una clave (empieza por `gsk_…`).
2. Pégala en «Clave API de Groq».
3. Modelo sugerido: `llama-3.3-70b-versatile`.
   Otros: `llama-3.1-8b-instant`, `mixtral-8x7b-32768`, `gemma2-9b-it`.
4. Toca **«Probar conexión Groq»** → debe decir `OK`.

### Proveedor
- **Automático (OpenRouter → Groq)**: si uno falla, prueba el otro solo.
- Solo OpenRouter / Solo Groq: usa únicamente ese.
- Con **una sola clave ya funciona**. Con las dos, nunca te quedas sin IA.

🔒 **Seguridad:** las claves se guardan solo en tu teléfono
(`SharedPreferences` privadas). Solo se envían a OpenRouter/Groq en la
cabecera `Authorization`. JARVIS no tiene servidor propio.

---

## 🎙️ Motor de voz realista

- **Escuchar (STT):** reconocimiento de voz en español (`es-ES`) con
  resultados parciales en vivo mientras hablas.
- **Hablar (TTS):** voz española del sistema, con velocidad (0.5–1.8x) y tono
  (0.6–1.5x) ajustables, habla por frases cortas para sonar natural.
- **Wake-word opcional:** activa «Escucha continua: di Jarvis» y JARVIS te
  escucha siempre; di **«Jarvis, …»** para hablarle sin tocar nada.
- Botón **MIC** grande + botón **Enviar** + escribir. Orbe animado con estados:
  escuchando / pensando / hablando.

Prueba la voz en Ajustes → **«Probar la voz»**.

---

## ⚡ Acciones en tu móvil (lo que puede hacer por ti)

Pídelas por voz o escribiéndolas. Las de dispositivo funcionan **sin internet**;
con IA, JARVIS también las entiende con otras palabras y te ayuda con ideas.

| Di esto… | JARVIS hace… |
|---|---|
| «Abre WhatsApp / YouTube / Spotify / Maps / Gmail…» | Abre la app (busca por nombre si no está en la lista) |
| «Pon una alarma a las 7» / «Pon alarma a las 7:30» | Crea la alarma en el reloj |
| «Temporizador de 10 minutos» | Inicia cuenta atrás |
| «Recuérdame comprar pan en 10 minutos» | Recordatorio con notificación a esa hora |
| «Recuérdame llamar a Ana a las 18:00» | Recordatorio programado |
| «Crea un proyecto llamado Mi app» | Crea el proyecto |
| «Añade tarea diseñar logo al proyecto Mi app» | Añade la tarea |
| «Completa la tarea diseñar logo» | Marca la tarea como hecha |
| «Qué proyectos tengo» | Lista proyectos y pendientes |
| «Apunta comprar cables USB» | Guarda una nota |
| «Enciende / apaga la linterna» | Linterna real |
| «Sube / baja el volumen», «Pon el volumen al 70» | Volumen de música |
| «Sube / baja el brillo» | Brillo de pantalla (pide permiso una vez) |
| «Enciende el Wi-Fi / Bluetooth» | Lo activa o abre el ajuste (Android 10+ lo limita) |
| «Pon modo no molestar» | Activa No molestar |
| «Llama al 612345678» | Abre el marcador con el número |
| «Manda un SMS al 612… que diga hola» | Abre mensajes con el texto |
| «WhatsApp al 612…» | Abre el chat de WhatsApp |
| «Busca pizza en Maps» / «Busca… en Google/YouTube» | Búsqueda en Maps/Google/YouTube |
| «Cómo llegar a…» | Ruta en Maps |
| «Pon música» / «Reproduce reggaetón» | Abre YouTube Music con tu búsqueda |
| «Haz una foto» / «Graba un vídeo» | Abre la cámara |
| «Calcula 120 por 3 más 50» | Calculadora integrada (sin abrir otra app) |
| «Qué hora es» / «Qué fecha es» / «Cuánta batería tengo» | Hora, fecha, batería |
| «Copia …» / «Comparte …» | Portapapeles / menú compartir |
| «Añade al calendario…» | Crea el evento en el calendario |
| «Abre los ajustes de Wi-Fi» | Abre el ajuste exacto |
| «Vibra» | Vibra el teléfono |

Con IA además: pregunta, pide consejos, ideas para tus proyectos,
resúmenes, textos, traducciones… y JARVIS puede **combinar** conversación
con acciones (ej.: «crea un proyecto para mi tienda y añade 3 tareas»).

Pantalla **Proyectos**: ve y gestiona proyectos, tareas (toca ☐ para completar),
recordatorios y notas. Todo queda guardado en el teléfono.

---

## 🧠 Cómo funciona por dentro

```
Voz / texto → Cerebro JARVIS
                ├─ 1) Comando local en español (rápido, sin internet)
                ├─ 2) IA OpenRouter/Groq (con fallback automático)
                │      └─ si pide acción → JSON → Motor de acciones en el móvil
                │         → resultado → IA redacta la respuesta final
                └─ 3) Sin claves → modo local + ayuda
Voz (TTS) ← respuesta
```

- `jarvis/ai/AiClient.kt` — OpenRouter + Groq (dialecto OpenAI), timeouts y errores claros.
- `jarvis/ai/JarvisBrain.kt` — prompt del asistente, historial y bucle acción→respuesta.
- `jarvis/actions/ActionEngine.kt` — 30+ acciones reales + parser local en español + calculadora.
- `jarvis/voice/VoiceEngine.kt` — STT + TTS en español, wake-word «Jarvis».
- `jarvis/data/` — claves, preferencias, proyectos/tareas/recordatorios/notas (local).
- `jarvis/ui/` — pantalla principal (orbe + chat + MIC), configuración, proyectos.
- AirGesture (transferencia por gestos) sigue incluido: botón **«Gestos»** en JARVIS.

---

## ❓ Problemas frecuentes

- **«No te he oído»** → acerca el móvil, habla claro y concede el micrófono.
- **La voz no reconoce** → necesita internet (Google Speech). Escribe mientras tanto.
- **Clave rechazada (401)** → copia la clave completa, sin espacios.
- **Modelo no encontrado (404)** → cambia el modelo en Ajustes por uno sugerido.
- **Límite alcanzado (429)** → espera 1 minuto o cambia de proveedor.
- **Wi-Fi/Bluetooth no se activa solo** → Android 10+ lo bloquea por seguridad;
  JARVIS abre el ajuste para que lo toques una vez.
- **Brillo no cambia** → concede «Modificar ajustes del sistema» una vez.

---

## 📋 Lista de descarga (resumen)

- **APK release (recomendado):** https://github.com/alexsndersoto04-source/moon/releases/latest → `JARVIS-2.0.0.apk`
- **APK debug:** mismo Release → `JARVIS-2.0.0-debug.apk`
- **Compilación automática:** https://github.com/alexsndersoto04-source/moon/actions → artifact `JARVIS-apk`
- **Claves gratis:** OpenRouter https://openrouter.ai/keys · Groq https://console.groq.com/keys

*JARVIS 2.0.0 · Android 10+ · Hecho para ayudarte cada día con tus proyectos.*
