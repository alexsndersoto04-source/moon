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

## Configuración real para dos teléfonos

La sincronización requiere instalar y mantener **un servidor compartido** accesible por ambos teléfonos a través de HTTPS. El APK por sí solo no proporciona un servidor ni una cuenta en la nube. Puede ser un servidor privado o un servicio de alojamiento con Node 22, almacenamiento persistente y un proxy HTTPS; el proveedor puede tener costo. Mantén una copia de seguridad externa. No uses la vista previa temporal como servidor real.

1. Instala el proyecto y las dependencias en el servidor (`npm ci`).
2. Define `MARILIANA_KEY` como una clave larga y privada, `MARILIANA_DATA` como un directorio persistente y `PORT` como el puerto interno. Por ejemplo:
   ```sh
   MARILIANA_KEY='una-clave-larga-y-unica' MARILIANA_DATA=/ruta/persistente PORT=3000 npm start
   ```
3. Publica el servicio **solo mediante HTTPS** detrás de un proxy. Sin `MARILIANA_KEY` cualquiera que alcance el servidor puede leer o modificar los datos. Comparte la clave únicamente con las dos personas autorizadas. La clave queda almacenada localmente en cada dispositivo; protege también los teléfonos. Esta versión usa una clave compartida, no cuentas individuales ni permisos separados.
4. En ambos teléfonos, abre Ajustes e introduce la misma dirección HTTPS y clave. En cada uno escribe un nombre diferente para distinguir los movimientos. No cambies de servidor mientras haya operaciones pendientes.
5. Comprueba que ambos indican «Sincronizado». Agrega una bebida de prueba, registra una salida desde cada teléfono y verifica los resultados. Prueba también la cámara y la recuperación de conexión antes de usar datos reales.

La interfaz web se puede instalar desde el navegador compatible usando «Instalar aplicación» o «Agregar a pantalla de inicio». Para abrirla sin internet por primera vez, antes hay que cargarla con conexión y permitir que se guarden sus archivos. En el APK los archivos de la interfaz ya están incluidos.

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

Ajustes permite descargar un JSON de consulta con el estado visible; **no es una copia completa restaurable** y puede incluir cambios locales pendientes. Para una recuperación fiable, respalda la base SQLite del servidor con una copia coherente, por ejemplo usando la API de respaldo de Node (el archivo de destino no debe existir):

```sh
node -e "const {DatabaseSync,backup}=require('node:sqlite'); const db=new DatabaseSync('/ruta/persistente/inventario.sqlite'); backup(db,'/ruta/segura/mariliana-copia.sqlite').then(()=>db.close())"
```

Guarda esa copia fuera del servidor y comprueba periódicamente que se puede restaurar. Para restaurar, detén el servidor, guarda aparte la base actual y sus archivos `-wal` y `-shm`, reemplaza la base por la copia y arranca el servidor de nuevo. Coordina la restauración con ambos usuarios y resuelve primero cualquier cambio pendiente en los teléfonos: una restauración vuelve el servidor a un estado anterior. El JSON descargado desde la interfaz no restaura el sistema.
