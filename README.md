# CodeForge

CodeForge es un editor web de código funcional para trabajar con archivos reales en un workspace del servidor. Incluye Monaco Editor, pestañas y guardado con control de concurrencia, explorador y búsqueda global, ejecución de programas, terminal por WebSocket, snapshots y un agente conectado a proveedores compatibles con la API de chat completions de OpenAI.

## Requisitos

- Node.js 18 o posterior (recomendado: Node.js 20+).
- `npm`.
- Bash en Linux/macOS para terminal y comandos de shell. Los runtimes adicionales dependen de lo que esté instalado en la máquina.

## Arranque

```bash
npm install
npm start
```

Abre `http://localhost:4321`. El servidor crea el directorio `workspace/` al iniciar; no agrega proyectos de ejemplo ni datos simulados. Todo archivo que crees desde CodeForge se lee y escribe ahí.

Para desarrollo con reinicio al cambiar archivos:

```bash
npm run dev
```

La aplicación también puede usar un workspace externo:

```bash
CF_WORKSPACE=/ruta/a/mi-proyecto npm start
```

Variables opcionales:

| Variable | Valor predeterminado | Uso |
| --- | --- | --- |
| `PORT` | `4321` | Puerto HTTP y WebSocket. |
| `HOST` | `0.0.0.0` | Interfaz de red. Usa `127.0.0.1` para acceso solo local. |
| `CF_WORKSPACE` | `./workspace` | Ruta absoluta del workspace real. |
| `CF_SHELL` | `$SHELL` o `/bin/bash` | Shell usada por la terminal. |
| `CF_DISABLE_TERMINAL` | `0` | Pon `1` para desactivar el acceso a terminal. |
| `CF_ALLOWED_ORIGINS` | mismo origen | Lista separada por comas de orígenes permitidos para WebSocket; `*` desactiva esa comprobación. |
| `CF_MAX_SNAPSHOT_MB` | `256` | Máximo de datos por snapshot. |

## Agente IA

Abre **Ajustes → Agente IA**, configura la URL base y pulsa **Buscar modelos**. CodeForge consulta el catálogo compatible `GET /models`, muestra los IDs encontrados y selecciona automáticamente primero uno que el proveedor marque como gratuito; si no hay uno gratuito, elige uno disponible y lo indica. Puedes escoger otra sugerencia o escribir el ID manualmente cuando el proveedor no publique catálogo. La prueba de conexión confirma si el modelo realmente acepta solicitudes de chat y herramientas.

El botón **OpenRouter: buscar modelos GRATIS** configura `https://openrouter.ai/api/v1` y consulta su catálogo. Para chatear necesitas una API key de OpenRouter; la disponibilidad, límites y precios de los modelos gratuitos pueden cambiar. La etiqueta GRATIS se basa en los metadatos publicados por el proveedor, no es una garantía independiente de disponibilidad o de límites.

Se admiten servicios que implementen `POST /chat/completions`, como proveedores tipo OpenAI o servidores locales como Ollama. La URL base puede incluir `/v1` o acabar directamente en `/chat/completions`. Si el proveedor no tiene `GET /models`, introduce el ID manualmente y usa **Probar conexión**.

Un error `insufficient_quota` o `credit_balance_exhausted` indica que esa API key no tiene saldo: cambiar el modelo dentro de la misma cuenta no lo soluciona. La suscripción de ChatGPT no añade créditos a la API; para cambiar de proveedor, usa su URL y su propia API key. Si cambias el host del proveedor en Ajustes, CodeForge borra la clave del formulario para evitar enviarla por accidente a otro servicio.

La configuración, incluida la clave, se conserva únicamente en el `localStorage` del navegador actual. La clave se envía al backend para atender las solicitudes y no se escribe en archivos del servidor. Las llamadas de herramientas trabajan sobre el workspace real: pueden crear/editar/borrar archivos, ejecutar código, buscar y restaurar snapshots. El agente bloquea comandos peligrosos en su herramienta de shell; revisa sus cambios y resultados.

## Funciones

- **Editor**: Monaco, resaltado de sintaxis, autocompletado, minimapa, plegado, varias pestañas, dirty state y guardado con `Ctrl/Cmd+S`.
- **Explorador**: crear archivos/carpetas, mover/renombrar, borrar y abrir rutas del workspace.
- **Búsqueda**: texto o regex en archivos, con filtro glob y salto directo a la línea.
- **Ejecución**: Node.js, Python, TypeScript vía `tsx`, Bash, Ruby, PHP, Lua, Perl, Go, Deno, C, C++, Rust, Java, Kotlin y Swift según las herramientas instaladas. Los programas tienen límites de tiempo y salida.
- **Terminal**: shell real interactiva; usa `node-pty` si está disponible y un modo compatible si no. Instalar el optional dependency `node-pty` habilita el comportamiento PTY cuando el sistema puede compilarlo.
- **Snapshots**: copias con retención de 10 puntos; se excluyen `.git`, `node_modules` y carpetas generadas. Restaurar crea antes un snapshot de seguridad.

## Seguridad y exposición de red

La terminal y la ejecución de programas son acceso real al sistema operativo del proceso de Node. La aplicación no tiene inicio de sesión incorporado: ejecútala en tu equipo o en una red de confianza, no la publiques directamente en Internet. Si necesitas exposición remota, añade autenticación/autorización en un proxy de confianza y limita el acceso de red. `CF_ALLOWED_ORIGINS` protege el upgrade WebSocket contra orígenes cruzados, pero **no es autenticación**.

Las rutas de API se confinan al workspace, se rechazan enlaces simbólicos, y la carpeta interna de snapshots no puede editarse a través de operaciones normales. Estas medidas no convierten en sandbox el código que se ejecuta: un programa lanzado por el usuario tiene los permisos del usuario del servidor.

## Verificación

```bash
npm test
```

La suite de integración inicia el servidor real sobre un workspace temporal y comprueba el editor y sus assets, API de archivos, versiones de guardado, búsqueda, ejecución Node y shell, protección de rutas, snapshots/restauración, terminal WebSocket y herramientas del agente.
