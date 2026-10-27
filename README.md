# AirGesture Transfer

Transferencia de archivos entre teléfonos **sin cables, sin routers y sin configuración**:
miras tu mano a la cámara, cierras el puño para elegir un archivo y empujas la mano
hacia delante para lanzarlo al dispositivo más cercano.

Compilado automáticamente por GitHub Actions → **el APK se descarga desde Releases o
desde la pestaña Actions**, sin necesidad de Android Studio.

---

## 1. Descargar e instalar el APK (2 minutos)

1. Entra a **[Releases](https://github.com/alexsndersoto04-source/moon/releases/latest)**
   y abre el último `AirGesture Transfer 1.0.0 (build N)`.
2. Descarga `AirGestureTransfer-1.0.0.apk` **directamente en el teléfono**
   (o envíatelo; también está `…-debug.apk` y un `SHA256SUMS.txt`).
   - Alternativa: **Actions → Build APK → último run → Artifacts → `AirGestureTransfer-apk`**
     (requiere sesión de GitHub, el artifact es un `.zip`).
3. Al abrir el APK, Android preguntará si permites instalar apps de esta fuente:
   *Ajustes → Instalar apps desconocidas → tu navegador/gestor de archivos → Permitir*.
4. Instala y abre. Concede los permisos (cámara, archivos y dispositivos cercanos).
5. Repite en el segundo teléfono. **No hay que emparejar nada**: cada app anuncia y
   busca al mismo tiempo y se conecta sola con la primera que responde.

> Requisitos: Android 10+ (API 29), cámara, Bluetooth LE y Wi-Fi Direct.
> Probado como objetivo principal en Xiaomi Redmi 9C (Helio G35, 2/3 GB RAM).

### Cómo se genera el APK

`.github/workflows/build.yml` se dispara en cada push a `main` o `arena/**`, en Pull
Requests y manualmente (`workflow_dispatch`). El job:

1. Instala JDK 17 + Android SDK (runner `ubuntu-latest`).
2. Genera el Gradle wrapper 8.7 y crea una keystore de depuración para firmar la variante *release*.
3. Descarga el modelo `hand_landmarker.task` (tarea `downloadHandModel`, ~7 MB, con reintentos).
4. Ejecuta `./gradlew :app:assembleRelease :app:assembleDebug` y **verifica el APK**
   (modelo MediaPipe incluido y sin comprimir, ABIs presentes, firma válida con
   `apksigner`): si esa comprobación falla, el workflow falla.
5. Sube los APK + `SHA256SUMS.txt` como artifact y publica un **GitHub Release** descargable.

---

## 2. Los gestos (naturales, sin poses)

**No hay que hacer ninguna pose concreta.** La app no pide puño cerrado, ni dedos
estirados, ni mantener nada quieto. Basta con mover la mano.

| Lo que haces | Lo que pasa |
|---|---|
| Mueves la mano a la cámara | La app la detecta (chip «Mano detectada», esqueleto sutil) |
| Deslizas la mano a izquierda/derecha | Cambia el archivo activo (umbral bajo, cualquier pose) |
| **Empujas la mano hacia el teléfono** | Envía el archivo activo al dispositivo cercano |
| Cierras la mano un momento | Solo feedback: háptico + «Listo» (no es obligatorio) |
| Mantienes pulsada una tarjeta | Envío inmediato (respaldo por si el gesto no sale) |

El empuje se reconoce fusionando **cuatro señales independientes** y usando el *pico*
de cada una dentro de una ventana corta, así un gesto rápido y natural cuenta igual que
uno lento:

1. Crecimiento aparente de la mano (te acercas a la lente).
2. Profundidad `z` de los landmarks de MediaPipe.
3. Velocidad de la palma en cualquier dirección (detecta el *flick*).
4. Acelerómetro lineal del teléfono en el eje de avance.

Se necesitan al menos **dos señales de acuerdo**: mover la mano de lado o agitar el
teléfono por sí solo no dispara un envío. Después de cada lanzamiento el detector se
desarma y solo se rearma cuando el empuje baja, de modo que un gesto nunca envía dos veces
(y no hay que «resetear» nada).

La barra fina bajo la cámara es el **medidor de empuje**: se llena mientras el gesto se
reconoce. Si llega al final y no envía, es que no hay dispositivo conectado todavía.

### Sensibilidad

Por defecto está en `0.62` (eager), pensada para que funcione a la primera.
Ajusta `DEFAULT_SENSITIVITY` en `vision/GestureInterpreter.kt` (o `Prefs.kt`): `0` = muy
estricto, `1` = muy fácil. Todos los umbrales están juntos al final de
`GestureInterpreter.kt`.

### Diagnóstico en pantalla (importante si algo no funciona)

Mantén pulsado el título **AirGesture** y aparece un HUD con números reales:

```
fps 12 · 78 ms          ← frames de inferencia por segundo y coste de cada una
mano 340 · RGBA         ← frames con mano detectada y formato de origen del frame
cierre 0.42 · empuje 0.31
cuadros 1204 · fallos 0 ← frames analizados y fallos de conversión
modelo ok · dedos 21    ← modelo MediaPipe cargado y landmarks recibidos
```

Cómo leerlo:

- `fps 0` y `cuadros` congelado → la cámara no está entregando frames.
- `fallos` subiendo → la conversión de imagen falla en tu dispositivo (mándame ese número).
- `mano 0` con `fps > 0` → los frames llegan pero MediaPipe no ve la mano: acerca la
  mano, mejora la luz, usa un fondo liso y que la mano entre completa en el encuadre.
- `dedos 21` y `mano > 0` → el tracking funciona; si no envía, mira el medidor de empuje.

## 3. Arquitectura

```
app/src/main/java/com/airgesture/transfer/
├── core/        AirGestureApp · Prefs · GestureState
├── media/       MediaKind · MediaEntry · MediaRepository      (MediaStore vía ContentResolver)
├── camera/      FrameConverter · CameraAnalyzer               (480p, RGBA, cero allocs)
├── vision/      HandTracker · GestureInterpreter · ThrustSensor
├── transfer/    NearbyTransferManager · NearbyState           (Nearby Connections P2P_CLUSTER)
├── ui/          MainActivity · MainViewModel · FileAdapter · HandOverlayView · ThumbnailLoader
└── util/        Permissions · Formatters · Haptics
```

**Decisiones de rendimiento (clave para el Redmi 9C):**

- **480p y hilo propio.** `ImageAnalysis` se configura a 640×480 con
  `STRATEGY_KEEP_ONLY_LATEST` sobre un único executor. Si el dispositivo se atrasa se
  descartan frames; la UI nunca se bloquea.
- **Conversión sin allocations.** `FrameConverter` pide `OUTPUT_IMAGE_FORMAT_RGBA_8888`
  (la conversión YUV→RGBA la hace CameraX en nativo) y copia a un `Bitmap` reutilizado
  con `copyPixelsFromBuffer`. Cero basura por frame → cero GC visible.
- **MediaPipe en modo VIDEO.** `detectForVideo()` es síncrono: no hay cola de resultados
  asíncronos acumulándose, y el bitmap se puede reutilizar sin condiciones de carrera.
  Delegado **CPU** a propósito (el delegado GPU es inestable en Mali-G35).
- **Doble hilo de estado.** El overlay recibe frames por un `SharedFlow` con
  `DROP_OLDEST`; los eventos de gesto viajan a `viewModelScope`. Un frame descartado no
  rompe la máquina de estados.
- **Miniaturas.** `ContentResolver.loadThumbnail()` en un pool de 2 hilos con `LruCache`
  de 6 MB; nunca se decodifica un archivo de 12 MP para la tira.
- **UI mínima.** Sin animaciones costosas: un `RecyclerView` horizontal con DiffUtil y una
  vista custom que dibuja el esqueleto con `Paint` preasignados.

**Transferencia (protocolo propio sobre STREAM):** cada archivo viaja en **un solo payload
`STREAM` autodescriptivo**:

```
int32 'AGFT' · uint8 versión · int32 largo del nombre + nombre
int32 largo del MIME + MIME · int64 tamaño · bytes del archivo
```

Los metadatos van *dentro* del flujo, así que no hay carrera entre payloads. El receptor
lee la cabecera y escribe directo en `Descargas/AirGesture` vía MediaStore, con copia por
bloques de 64 KB (un video de 200 MB nunca pasa por el heap). El cierre del flujo es lo
que marca la transferencia como completa, y si el tamaño declarado no coincide con el
recibido se cancela y se borra el archivo parcial en vez de dejar basura. También se
aceptan payloads `FILE` de versiones anteriores.

**Cámara frontal por defecto** (icono de ajustes para cambiarla): es la lente correcta para
gesticular, porque te ves la mano y la pantalla a la vez; el overlay se espeja para coincidir
con la previsualización.

**Ajustes reales** (icono de la esquina): cámara frontal/trasera, sensibilidad del gesto
(aplicada al instante), esqueleto, vibración, mantener pantalla encendida y HUD de
diagnóstico. Todo se guarda en `SharedPreferences` y se re-aplica al volver a la cámara.

**Privacidad:** todo es local. No hay red, ni cuentas, ni telemetría. El único tráfico del
proyecto es la descarga del modelo durante la compilación.

---

## 4. Permisos y por qué

| Permiso | Uso |
|---|---|
| `CAMERA` | Tracking de manos |
| `BLUETOOTH_SCAN` / `ADVERTISE` / `CONNECT` (12+) | BLE para descubrir y enlazar (Nearby) |
| `NEARBY_WIFI_DEVICES` (13+) | Wi-Fi Direct para el transporte |
| `ACCESS_FINE_LOCATION` (≤11) | Requisito del sistema para escanear BLE en Android 10/11 |
| `READ_MEDIA_*` / `READ_EXTERNAL_STORAGE` | Listar imágenes, videos, PDF y audio |

Se conceden desde una sola pantalla inicial; si algo se deniega de forma permanente
aparece el acceso directo a los ajustes de la app.

---

## 5. Compilar localmente (opcional)

```bash
# Requiere JDK 17 y Android SDK 34
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease   # requiere una keystore en ~/.android/debug.keystore
```

Sin wrapper en el repo, genéralo una vez con `gradle wrapper --gradle-version 8.7`
(o usa el que produce el workflow).

Versiones fijadas: AGP 8.5.2 · Gradle 8.7 · Kotlin 1.9.24 · `compileSdk 34` · `minSdk 29` ·
MediaPipe Tasks Vision 0.10.14 · Nearby Connections 19.3.0 · CameraX 1.3.4.
ABIs incluidas: `arm64-v8a`, `armeabi-v7a`.

---

## 6. Límites conocidos (honestidad sobre el alcance)

- Una conexión a la vez: el modelo mental es «el dispositivo más cercano». Con
  `P2P_CLUSTER` la API admite hasta 4; el envío en paralelo no está implementado.
- La transferencia vive mientras la app está en primer plano: no hay `ForegroundService`,
  así que si el sistema mata el proceso a mitad de envío, se cancela.
- Los umbrales de gesto están afinados por razones físicas, no con un dataset: pueden
  necesitar retoque según la iluminación y la mano. Están todos en un solo archivo y se
  pueden ver en vivo con el HUD de diagnóstico.
- La variante *release* no está minificada (`minifyEnabled false`) para no arriesgar
  reflexión en MediaPipe / Nearby. La firma es la keystore de depuración del CI: sirve
  para instalar y probar, no para Play Store.

## 7. Licencias

Código propio bajo MIT. MediaPipe y sus modelos son Apache 2.0 de Google; Nearby
Connections es parte de Google Play Services.
