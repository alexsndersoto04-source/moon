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

## 2. Los gestos

| Gesto | Estado | Acción |
|---|---|---|
| Mano abierta | `STATE_IDLE` | Explorar la estantería de archivos |
| Deslizar la mano en horizontal | `STATE_IDLE` | Pasar al archivo siguiente / anterior |
| Cierre firme del puño (3 frames) | `STATE_GRAB_FIST` | Selecciona el archivo activo (`onFistClosed()`) |
| Empujar el puño hacia la cámara | `STATE_THROW_VECTOR` | Envía el archivo al dispositivo cercano |
| Abrir la mano | `STATE_IDLE` | Suelta la selección |

El gesto de lanzamiento combina **tres señales independientes** y exige que al menos dos
coincidan, por eso no se dispara al inclinar o agitar el teléfono:

1. Crecimiento aparente de la mano (acercarse a la lente = escala creciente).
2. Profundidad `z` de los *landmarks* de MediaPipe.
3. Aceleración lineal del dispositivo en el eje de avance (`TYPE_LINEAR_ACCELERATION`, filtrada).

El puño se detecta comparando la punta de cada dedo con su articulación PIP respecto al
centro de la palma: es invariante a la escala y no necesita calibración. Hay histéresis
por frames (nunca decide con un frame ruidoso) y *cooldown* por evento.

Un toque en la tira de archivos o en las pestañas sigue funcionando como alternativa.

### Ajustar la sensibilidad

`app/src/main/java/com/airgesture/transfer/core/Prefs.kt` → `DEFAULT_SENSITIVITY`
(`0f` = estricto, `1f` = muy fácil). Los umbrales base viven en
`vision/GestureInterpreter.kt` (constantes al final del archivo).

---

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

**Transferencia:** `P2P_CLUSTER`, auto-aceptar sin diálogo. Se envían dos payloads: metadatos
(JSON en BYTES) y luego el archivo (FILE desde un `ParcelFileDescriptor`, sin copiar el
archivo a memoria). La recepción se guarda en `Descargas/AirGesture` vía `MediaStore`, con
progreso en una barra fina.

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
  necesitar retoque según la iluminación y la mano. Están todos en un solo archivo.
- La variante *release* no está minificada (`minifyEnabled false`) para no arriesgar
  reflexión en MediaPipe / Nearby. La firma es la keystore de depuración del CI: sirve
  para instalar y probar, no para Play Store.

## 7. Licencias

Código propio bajo MIT. MediaPipe y sus modelos son Apache 2.0 de Google; Nearby
Connections es parte de Google Play Services.
