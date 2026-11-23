# KODE KSST

Editor de código avanzado multi-lenguaje con **Agente Autónomo** integrado y conector universal para cualquier API de IA ("cerebro").

## Requisitos para ejecutar localmente
- **Node.js** 20+ y **npm**
- **Python 3** (para ejecución de Python, SQLite SQL y Terminal PTY)
- Opcional según lenguajes que ejecutes: `gcc` (C17), `g++` (C++20), `make`, `git`

## Instalación y uso local
```bash
git clone -b arena/6325a5b4-moon https://github.com/alexsndersoto04-source/moon.git
cd moon/kode-ksst
npm install
npm run build
npm start
```
Luego abre en tu navegador: `http://localhost:3000`

Para desarrollo con recarga rápida:
```bash
npm run dev
```

## Conectar el Cerebro del Agente
1. Haz clic en **Conectar Cerebro API** en la barra superior o en el panel derecho **KODE KSST AGENT**.
2. Selecciona proveedor (OpenAI, Anthropic Claude, Google Gemini, DeepSeek, Groq, OpenRouter, Ollama local o Endpoint compatible).
3. Pega tu API Key y pulsa **Probar Conexión** -> **Guardar y Activar Cerebro**.
4. Escribe instrucciones en el chat del Agente para que lea, programe, depure, compile, ejecute pruebas y controle todo el IDE por ti.
