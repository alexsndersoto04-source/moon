# Mariliana

Inventario compartido de bebidas para dos dispositivos Android. Es un proyecto independiente de los demás proyectos del repositorio. La interfaz es instalable como aplicación web y el directorio `android/` contiene el proyecto nativo de Capacitor para generar un APK.

## Qué hace

- Productos con nombre, código de barras, cantidad, mínimo, presentación, material, categoría, fecha de vencimiento y notas. El nombre y la cantidad son suficientes para comenzar.
- La cámara lee códigos de barras registrados; cada lectura descuenta una unidad o una caja de seis, según la selección. Un código desconocido abre el formulario de registro. También se puede introducir el código manualmente.
- Entradas, salidas, correcciones por movimientos, avisos de existencias bajas, agotadas y fechas próximas de vencimiento. La fecha de vencimiento es por producto, no por lote.
- Historial por día. Borrar **salidas** de una fecha no modifica existencias ni borra entradas, y se aplica al inventario compartido.
- Guarda operaciones en el dispositivo mientras no hay conexión. Se envían al servidor al recuperarla. Cada operación tiene un identificador único para que un reintento no descuente dos veces.
- El servidor impide que el stock compartido sea negativo. Si los dos teléfonos venden la última unidad sin conexión, no es posible garantizar ambas salidas: la operación que no pueda aplicarse se rechaza y se muestra en Ajustes para revisión. Los datos mostrados sin conexión son provisionales. El historial usa la hora del teléfono: mantengan los relojes configurados correctamente. Una salida hecha sin conexión antes de borrar un día podría llegar después y reaparecer en ese historial.

## Probar en este entorno

Necesitas Node.js 22.13 o superior y npm:

```sh
cd mariliana
npm ci
npm start
```

Abre `http://localhost:3000`. La cámara del navegador requiere permiso y un contexto seguro: HTTPS o localhost. La dirección de vista previa de Arena es temporal; no es un servidor de producción ni debe usarse para guardar el inventario real. Por defecto el servidor **no tiene clave**, para facilitar la prueba.

Ejecutar pruebas: `npm test`.

## Configurar Neon y Render para los dos teléfonos

La aplicación **no viene conectada a ninguna cuenta**. El servidor de Render y la base de datos de Neon deben crearse en tus propias cuentas. Comprueba los precios, límites y condiciones de los planes antes de contratarlos. El plan gratuito de un servidor puede pausarse por inactividad; al despertar puede tardar en responder. La app consulta periódicamente el servidor mientras está abierta, lo que también puede influir en el consumo.

### 1. Crear la base en Neon

1. Entra a [Neon](https://neon.tech/) y crea tu cuenta y un proyecto para Mariliana. Elige una región cercana a la de Render, si está disponible.
2. En la pantalla **Connect**, copia la cadena de conexión PostgreSQL con SSL (preferiblemente la conexión *pooled*). Contiene usuario y contraseña: **no la compartas por chat, ni la subas al repositorio**.
3. No necesitas crear las tablas manualmente. El servidor las crea al arrancar. Esta base debe ser exclusiva para Mariliana.

### 2. Crear el servidor en Render

1. Entra a [Render](https://render.com/) y crea un **Web Service** conectado a este repositorio de GitHub. Si Render solicita permiso para el repositorio, concédelo en GitHub.
2. Selecciona la rama que contiene Mariliana: `arena/690a9141-moon`. Mientras se use esa rama, mantenerla disponible; no seleccionar `main` a menos que el proyecto se haya incorporado allí.
3. Selecciona **Node** y configura:
   - **Root Directory:** `mariliana`
   - **Build Command:** `npm ci --omit=dev`
   - **Start Command:** `npm start`
   - **Health Check Path**, si se solicita: `/health` (comprueba que el proceso responde, no que la base esté disponible).
4. En **Environment**, agrega estas variables de forma privada:
   - `NODE_VERSION`: `22.16.0` (o una versión compatible de Node 22).
   - `NODE_ENV`: `production`.
   - `DATABASE_URL`: pega la cadena secreta que copiaste de Neon.
   - `MARILIANA_KEY`: inventa una clave larga y única de al menos 16 caracteres; la usarán ambos teléfonos. No la incluyas en GitHub ni en el APK.
5. Despliega el servicio. Render proporcionará una dirección HTTPS. El servidor se negará a iniciar en producción si no tiene base de datos o una clave suficientemente larga. No pongas la URL de Neon en los teléfonos: allí solo va la dirección de Render.

### 3. Configurar los dos teléfonos

En **Ajustes → Sincronización compartida** de cada teléfono escribe la misma dirección HTTPS de Render y la misma `MARILIANA_KEY`, y pon un nombre diferente en cada uno. Guarda los ajustes y verifica que diga «Sincronizado». Prueba con un producto ficticio: agrega unidades en un teléfono, registra una salida en el otro y verifica el resultado. Prueba la cámara, el historial, el borrado de salidas y la recuperación de conexión antes de usar datos reales.

Los cambios que hagas sin conexión quedan pendientes en el teléfono; al volver la conexión se envían. Si la otra persona vendió las últimas unidades, puede aparecer un conflicto en Ajustes que debe revisarse. La clave compartida se guarda localmente en el teléfono; protege ambos dispositivos. Esta versión no tiene cuentas individuales ni permisos separados.

Si la primera instalación nueva tiene operaciones pendientes y todavía no se ha conectado a ningún servidor, la versión actual permite configurar el servidor y enviarlas. **Si ya instalaste el APK anterior y guardaste datos en él, no lo desinstales ni borres sus datos para actualizar**: las versiones de prueba generadas por distintos ejecutores pueden tener firmas distintas y Android podría exigir desinstalar, lo que borraría la copia local. El JSON que se descarga es solo de consulta, no se puede restaurar automáticamente. Pide ayuda para revisar cómo conservar o volver a cargar esos datos antes de proceder.

El SQLite local de prueba no se migra automáticamente a Neon. Si ya existen datos en el servidor de prueba, no desaparecen ni se copian a Neon: es necesaria una migración explícita y verificada. Los datos creados en teléfonos pendientes de sincronizar siguen sus propias reglas indicadas arriba.

La interfaz web se puede instalar desde un navegador compatible. La dirección de vista previa de Arena es temporal y no debe usarse para guardar inventario real.

## Compilar el APK en una computadora con Android Studio

Se necesita Java 21, Android SDK 35 y acceso a los repositorios de Gradle/Google. Desde este directorio:

```sh
npm ci
npx cap sync android
cd android
./gradlew assembleDebug
```

El archivo de prueba quedará en `android/app/build/outputs/apk/debug/app-debug.apk`. Es un APK de depuración, no una versión firmada para distribuir públicamente. Una versión de producción necesita una clave de firma propia y pruebas en los modelos de teléfono reales. Tras instalarlo, configura en Ajustes la URL HTTPS y la clave del servidor compartido. No hay un servidor precargado en el APK.

## Copias y restauración

Ajustes permite descargar un JSON de consulta con el estado visible; **no es una copia completa restaurable** y puede incluir cambios locales pendientes. Neon es el almacenamiento principal, no una copia externa por sí solo. Configura y verifica las opciones de respaldo y restauración disponibles en tu plan de Neon, y conserva copias externas cifradas con herramientas PostgreSQL como `pg_dump`. No compartas la cadena de conexión ni subas copias con datos privados a un repositorio público.

Antes de restaurar, coordina con ambas personas, resuelve las operaciones pendientes y detén temporalmente el acceso al servidor. Restaurar una copia devuelve el inventario a un estado anterior y debe comprobarse primero en una base separada. Borrar el historial de salidas no modifica el inventario, pero se conservan identificadores de operaciones para evitar duplicados; esos identificadores crecerán con el uso. PostgreSQL puede reutilizar espacio después de borrar, pero el tamaño facturado no necesariamente disminuye de inmediato. Revisa el consumo y las políticas de retención del proveedor.
