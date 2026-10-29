# Avatar Humano 3D - Fluido y con IA

> Proyecto independiente. No interfiere con AirGesture Transfer ni con ningún otro módulo del repo.

### Qué es
App web de un avatar masculino hiper-realista, fluido, con gestos humanos normales:
- Habla, escucha, piensa, parpadea, respira, gesticula
- Te ve por la cámara y te reconoce
- Conversación natural con IA (cerebro)

Funciona **sin PC, solo con el móvil**, abriendo un navegador.

### Estructura
```
avatar-humano/
├── index.html      # UI + canvas 3D
├── css/style.css
├── js/
│   ├── config.js   # Proveedores de cerebro
│   ├── avatar.js   # Three.js + animación humana
│   ├── brain.js    # Cerebro IA (mock / Gemini / Groq / OpenAI)
│   ├── voice.js    # Voz: escuchar y hablar (gratis con Web Speech API)
│   ├── vision.js   # Visión: cámara
│   └── app.js      # Lógica principal
└── assets/
```

### Cerebro GRATIS (sin límites de OpenAI)
No necesitas pagar OpenAI. Opciones gratuitas:

1. **MODO DEMO (sin clave, ya funciona):** El avatar ya habla y responde con personalidad propia. Ideal para probar gestos/movimientos.
2. **GEMINI GRATIS (recomendado):** Ve a `aistudio.google.com` -> "Get API Key" -> gratis, 60 peticiones/min, sin tarjeta. Copias la clave en Ajustes de la app.
3. **GROQ GRATIS:** Ve a `console.groq.com` -> crea key gratis (Llama 3.3 70B ultra rápido).
4. **OpenAI (opcional):** Si algún día tienes, también funciona.

> Voz y escucha son 100% gratis usando la voz de tu propio teléfono (Web Speech API), no gastas API.

### Cómo usar en el móvil
1. Abre `index.html` en tu navegador o usa el servidor de vista previa.
2. Dale permisos de micrófono y cámara cuando te los pida.
3. Elige tu cerebro en ⚙️ Ajustes.
4. Pulsa el micrófono para hablarle o escribe.

### Gestos humanos implementados
- Respiración sutil, sway corporal, micro-movimientos de cabeza
- Parpadeo aleatorio natural
- Lipsync: mandíbula se abre según el volumen de la voz
- Estados: Idle (respira), Escuchando (se inclina), Pensando (mira arriba), Hablando (gesticula)
- Visión: te sigue con la mirada cuando la cámara está activa

### Nota
Este proyecto es web puro (HTML/CSS/JS + Three.js CDN). No necesita instalar nada, no necesita Android Studio, no toca Gradle.
