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

## 3. Estado de la conexión y del envío (visible en pantalla)

Debajo del medidor de empuje hay una **línea de registro** con lo que está pasando de
verdad, actualizada en cada paso:

```
Buscando dispositivos · soy Redmi9C-A3F
Dispositivo cerca: Redmi9C-91B
Conectado a Redmi9C-91B
Preparando Screenshot_2026-10-27.jpg
Enviando Screenshot_2026-10-27.jpg…
El otro teléfono confirmó la recepción
```

Si algo falla, el texto dice exactamente dónde. Además hay un botón **Enviar** que manda el
archivo seleccionado sin usar ningún gesto: sirve para separar «problema de gesto» de
«problema de red» en un solo toque.

### Cómo se siente el gesto (1.5.0)

**La señal ya no es un umbral: es una medida.** El motor compara el tamaño
aparente de la palma con una línea base que sigue la mano muy despacio (~1,6 s).
Acercar la mano crece esa relación entre un 20 % y un 60 %; tenerla quieta la deja
en 1,00. `approach` es esa relación normalizada 0..1 y **no depende de ningún
valor calibrado**, así que el primer empuje ya se registra.

Encima de eso:

- **One Euro por canal** (posición, tamaño, profundidad): suaviza quieto, no
  suaviza rápido.
- **Fusión**: `approach` y la aceleración del teléfono son la evidencia primaria;
  velocidad y profundidad la confirman. La confianza final es un número 0..1 y el
  disparo ocurre a `0.62 − 0.24·sensibilidad` (0,38 agresivo … 0,62 estricto).
  Un único número gobierna el gesto, por eso el deslizador ahora se siente lineal.
- **Arbitraje**: un deslizamiento lateral es *swipe* y amortigua el empuje al 45 %.
- **Histéresis**: hay que mantenerse 70 ms por encima y volver a bajar para
  rearmar, con 700 ms de enfriamiento entre envíos.
- **Háptico progresivo** en tres tramos antes del doble pulso de confirmación.

### Cómo se siente el gesto (1.4.1)

El gesto ya no depende de una postura: se lee el **movimiento** de la mano, no su forma.
Cada cuadro se filtra con un **filtro One Euro** por canal (posición, tamaño y profundidad)
que suaviza cuando la mano está quieta y deja de suavizar cuando se mueve rápido. Es la
diferencia entre «tiembla» y «pegado a la mano».

Encima de eso hay tres cosas que antes no existían:

1. **Calibración guiada (20 s).** La app te pide empujar la mano hacia el teléfono tres
   veces y mide *tus* picos reales de crecimiento, profundidad, velocidad y aceleración.
   Después el disparo ocurre al 55 % de tu propia medida: el mismo gesto que hiciste al
   calibrar es el que envía. Ruta: Ajustes → **Calibrar gesto**, o el aviso del primer
   arranque.
2. **Arbitraje.** Un movimiento lateral es un *swipe* (cambiar de archivo) y **amortigua**
   el empuje, así que hojear nunca envía nada por accidente. Solo el avance real —la mano
   crece en la imagen, la profundidad baja o el teléfono acusa aceleración— cuenta como
   empuje.
3. **Háptico progresivo.** El medidor vibra en tres tramos antes de confirmar: se siente
   cómo se «carga» el gesto en vez de disparar de golpe. La confirmación sigue siendo un
   doble pulso.

**El gesto no depende de la conexión.** La lectura de la mano es local y sigue funcionando
aunque el otro teléfono todavía no aparezca: si empujas sin enlace, el archivo queda en cola
y sale al conectar. Desde la 1.4.2 la pista de la pantalla prioriza lo que está viendo la
cámara («acerca la mano», «empuja», «seleccionado») y la conexión pasa a segundo plano; si la
pestaña está vacía el gesto lo dice explícitamente en vez de quedarse mudo.

La calibración se apoya en un **ticker propio**, no en el hilo de la cámara: el panel sigue
respirando aunque la mano salga del cuadro, la barra muestra el **empuje en vivo** (así se ve
que sí lo está registrando) y cada intento capturado da un pulso. Los umbrales de captura son
relativos y neutros, de modo que un empuje normal cuenta en el primer intento.

Los valores sin calibrar son conservadores y funcionan igual; la calibración solo los ajusta
a tu mano. Si algo va demasiado sensible, Ajustes → **Sensibilidad** escala los umbrales
(0 = estricto, 1 = ansioso).

### Cómo encuentra al otro teléfono (1.5.0)

Los dos teléfonos **anuncian y buscan a la vez, siempre**, sin turnos y sin
reiniciar el escaneo: el primer resultado llega en uno o dos segundos. El
handshake lo desempata el nombre, no la suerte: el equipo cuyo nombre ordena
primero llama, el otro acepta. Si el que debía llamar no lo hace en 1,5 s, el otro
toma la iniciativa. Un solo diálogo por pareja, siempre.

### Cómo encuentra al otro teléfono (1.4.0)

Nearby necesita **uno anunciando y otro buscando** a la vez, pero ninguna app puede saber de
antemano qué papel le toca. La versión anterior hacía las dos cosas simultáneamente y además
reiniciaba ambas cada 6 s: reiniciar el escaneo constantemente hace que el stack BLE nunca
entregue resultados, y por eso se quedaba en «Buscando… / Reintentando descubrimiento…» **para
siempre**, sin error.

Ahora cada teléfono **alterna turnos**:

```
Visible como Redmi9C-054   (6 s anunciándose)
Buscando el otro teléfono…  (7 s buscando)
Encontrado: Redmi9C-91B · conectando
Conectado a Redmi9C-91B
```

El desfase inicial es distinto en cada equipo, así que uno siempre está escuchando mientras el
otro busca. Estrategia `P2P_POINT_TO_POINT` (la documentada para un enlace 1:1). Si lanzas un
envío sin conexión, el archivo queda **en cola y sale solo en cuanto conecta**.

### Si aun así no conecta

Ajustes → **Ver diagnóstico** → **Copiar informe**. El texto incluye:

- modelo, versión de Android, versión de la app y de Play Services;
- el estado de **cada permiso** concedido/denegado;
- Bluetooth, ubicación del sistema, Wi-Fi Direct y BLE;
- los últimos 40 eventos con hora (`Visible como…`, `Buscando…`, `Conexión rechazada: …`).

Con ese informe se identifica en un minuto si el bloqueo es un permiso, un radio apagado, Play
Services ausente o el propio emparejamiento.

### Qué se envía y cómo se confirma

Metadatos en un payload `BYTES`, y **solo cuando ese payload confirma SUCCESS** se envía el
archivo como `FILE` desde un `ParcelFileDescriptor` (cero copias en memoria). El receptor
copia con `asUri()` a `Descargas/AirGesture` y responde un **ACK**: eso es lo que hace
aparecer «El otro teléfono confirmó la recepción». Verificación real, no una suposición.

## El diagnóstico de un Redmi 9C (1.5.3)

El informe copiable devolvió esto:

```
cuadros=89 manos=57 fps=0 modoColor=RGBA (BGR) sonda=auto
fallosConversion=0 modelo=ok inferencia=461ms
```

Tres conclusiones, en orden de importancia:

1. **La mano sí se detectaba** (57 de 89 cuadros). El problema no era la
   detección sino la **velocidad**: 461 ms por inferencia en un Helio G35 a
   640x480 son 2 cuadros por segundo, y la sonda de color, que nunca llegaba a
   fijarse, **duplicaba cada inferencia**: un cuadro por segundo en total. A esa
   velocidad ninguna lógica de gesto puede funcionar, por bien escrita que esté.
2. **La sonda se quedaba en `auto`** porque solo se fijaba al detectar en el
   reintento, no en el primer pase. Ahora se fija en cuanto ve una mano, y si no
   ve ninguna sigue reintentando cada 12 cuadros en vez de rendirse.
3. **`fps=0` era un contador roto**, no una medida. Ahora mide el intervalo real
   entre cuadros.

### Lo que se cambió por rendimiento

- **Inferencia a 320 px de lado**. El detector de MediaPipe trabaja internamente a
  ~224 px: alimentarlo con 640x480 era tirar el 80 % del trabajo. Es el cambio
  que más pesa en este teléfono.
- **Análisis a 480x360**, previsualización a 640x480. Son dos presupuestos
  distintos: la previsualización sigue fluida y solo el esqueleto va a la
  frecuencia del detector.
- **Una sola inferencia por cuadro** una vez fijado el color.
- **Suavizado en función del tiempo, no de los cuadros**. El filtro anterior
  avanzaba un tanto fijo *por cuadro*; a 2 fps eso hacía que cada gesto tardara
  cuatro veces más, y la línea base tardaba 30 s en adaptarse. Ahora usa
  constantes de tiempo en segundos (`exp(-dt/tau)`), así que el tacto es el mismo
  a 8 fps que a 30.
- **La ventana de movimiento acepta dos muestras** en vez de exigir tres: a 5 fps
  la ventana casi nunca tenía tres y todas las señales leían cero.

## Por qué no se detectaba la mano (1.5.2)

La previsualización y la inferencia son **dos tuberías distintas**. La que ves en
pantalla la pinta CameraX decodificando YUV; la que recibe MediaPipe la construye
`FrameConverter`. Ese conversor tenía dos rutas que escribían **órdenes de bytes
incompatibles** en el mismo `Bitmap`:

- la ruta YUV escribía enteros de color `A,R,G,B` documentados;
- la ruta RGBA volcaba los bytes crudos de CameraX —`R,G,B,A`— con
  `copyPixelsFromBuffer`, que los reinterpreta según la memoria nativa del bitmap
  (`B,G,R,A` en cualquier ARM).

Intercambiaba rojo y azul: la cámara se veía perfecta y el modelo recibía una mano
azul. Con eso la detección puede caer a cero y **nada** de lo que hay encima
(gesto, calibración, envío) llega a ejecutarse.

Ahora ambas rutas construyen los enteros de color a mano y escriben con
`setPixels`, que está documentado y no admite interpretación. Además:

- **Sonda automática de orden de color**: si en los primeros 90 cuadros no aparece
  ninguna mano, el analizador prueba el orden opuesto en el mismo fotograma y se
  queda con el que sí detecta. No depende de que yo acierte con tu teléfono.
- **El modelo se verifica en el APK**: el CI falla si `hand_landmarker.task` no
  está *Stored* **y** pesa más de 5 MB, así que un modelo truncado no puede
  entregarse.
- **La app ya no se queda callada**: si el modelo no cargó o no se puede leer ni un
  fotograma, lo dice en la línea de abajo. Settings → Ver diagnóstico incluye ahora
  `cuadros / manos / fps / modo de color / sonda / fallos / modelo`.

## Gesto con confirmación: cargar y soltar (1.6.0)

Antes bastaba con que la mano se acercara: el acercamiento **por sí solo** podía
cruzar el umbral, el motor arrancaba armado y el puño no participaba en la
condición de envío. Resultado: el archivo salía al levantar la mano.

Ahora son tres pasos separados, y el tercero es el que envía:

```
acercar   el medidor se llena y vibra por tramos      → no envía
cerrar    el puño carga el archivo                    → un pulso grave
abrir     la mano SE ENVIÓ                            → aquí arranca el efecto
```

Detalles que importan en la mano:

- La ventana para soltar es de **4 s**; si pasa, se cancela en silencio en vez de
  sorprender a nadie.
- Hay **350 ms** de enfriamiento tras cancelar, para que un puño tembloroso no
  vuelva a cargar solo.
- Mientras está cargado, el medidor se queda **lleno**: la barra deja de ser un
  adorno y pasa a ser «listo, suelta cuando quieras».
- En Ajustes → **Enviar cerrando el puño** se puede desactivar y volver al envío
  por empuje, para quien no pueda cerrar la mano.

## El efecto: agua de verdad, no círculos (1.7.0)

El intento anterior eran círculos con trazo: geométricamente correctos y visualmente
planos. Un círculo dibujado no parece agua porque le falta lo único que hace que el
agua parezca agua: **volumen y luz**.

Ahora cada onda se pinta en cuatro capas superpuestas:

```
bloom   ancha y tenue   → la bruma que levanta la cresta
surco   estrecha, oscura → la depresión detrás, la que da profundidad
cresta  fina y blanca    → el filo, con la dirección de la luz
glint   hilo de luz      → el especular sobre la cresta
```

Y tres decisiones que cambian por completo la lectura:

- **La luz viene de un sitio.** Cada cresta lleva un `SweepGradient` anclado al punto
  de impacto: brillante arriba a la izquierda, casi nada abajo a la derecha. Una
  onda iluminada por igual es un dibujo; iluminada desde un lado es agua.
- **Nada es un círculo perfecto.** Cada onda lleva una ondulación radial lenta: la
  cresta respira en vez de ser un anillo geométrico.
- **El archivo es una gota de vidrio**, no una tarjeta. Tiene sombra de contacto,
  está aplastada por la superficie, lleva el reflejo del lado iluminado, un punto
  especular y se disuelve cuando la onda lo alcanza.

Seis ondas separadas 85 ms, un manto de luz (cáustica) bajo ellas, doce gotas
despedidas con halo y un velo que barre la superficie entera. Duración: 1,25 s.

## El efecto: una piedra en el agua (1.6.1)

El intento anterior tenía una tarjeta quieta en el centro durante casi todo el
recorrido: se percibía como una captura que aparece y desaparece. Este no tiene
ni un fotograma estático.

```
0,00  el contacto: un destello donde el archivo sale
0,00  onda 1 · después 2, 3, 4 y 5 — 110 ms entre cada una
0,05  el archivo se hunde: crece, gira 5° y se disuelve
0,10  gotas despedidas por el frente de la primera onda
0,60  la última onda llega al borde y se apaga
```

Cada onda es su propia animación, con su propia vida: nace en el centro, se abre
con una curva *ease-out* (rápida en el impacto, lenta en el borde — así se comporta
el agua de verdad), adelgaza al crecer y se disuelve antes de tocar el marco.
Lleva un filo oscuro y una luz interior, que es como una onda real refleja la luz
por sus dos caras.

Además:

- **La piedra cae donde está el archivo**: el origen de las ondas se ancla a la
  tarjeta seleccionada en la estantería, no al centro de la pantalla.
- **Llegada**: la misma gramática al revés — las ondas se cierran y la tarjeta
  sube hasta su sitio.

## El efecto de entrega (1.6.0)

Las dos razones por las que no se veía, ya corregidas:

1. Solo se disparaba cuando el archivo **empezaba a salir de verdad**; si quedaba
   en cola, la pantalla no decía nada. Ahora el efecto responde **al gesto**, en el
   instante en que se abre la mano, aunque el envío tenga que esperar conexión.
2. Si el teléfono tenía las animaciones desactivadas —MIUI lo permite y a veces
   viene así— el código hacía `return` y **borraba el efecto sin avisar**. Ahora se
   acorta a 320 ms: el vuelo sigue existiendo.

Además hay efecto de **llegada** en el receptor (la tarjeta desciende y se posa,
los anillos se cierran en vez de abrirse) y un botón en Ajustes → **Probar efecto
de entrega** que reproduce la animación en un segundo, sin segundo teléfono y sin
enviar nada. Es la prueba que faltaba para saber si falla el efecto o el envío.

## Pantalla ordenada y calibración que se aparta sola (1.5.4)

Dos defectos de composición, visibles en un solo pantallazo:

- **Las pestañas flotaban dentro de la tarjeta de cámara.** Por orden de dibujo
  quedaban **encima** del panel de calibración: el botón «Listo» aparecía detrás
  de «Videos/PDFs/Música» y los toques caían en la pestaña en vez del botón. Ahora
  las pestañas son una fila propia debajo de la cámara: no pueden solaparse con
  nada.
- **La calibración se quedaba abierta** pidiendo un toque más. Ahora, al llegar al
  intento 3, guarda el perfil **sola**, da un pulso, muestra «Listo» 1,5 s y se
  retira. La cámara y la tira de archivos quedan libres y la app sigue normal. Para
  recalibrar: Ajustes → Calibrar gesto.

## Efecto de entrega

Cuando un archivo sale por gesto —o por el botón— se reproduce una coreografía de
900 ms sobre un único `Canvas`:

```
0,00  el halo se enciende y estalla el primer anillo
0,05  la tarjeta con su miniatura real despega, gira 3° y se eleva
0,10  una estela de luz la sigue mientras sube
0,18  segundo anillo · 0,34 tercero
0,20  diez partículas ascienden a lo largo de su trayectoria
0,70  la tarjeta se disuelve en la luz
0,86  un destello suave cierra el movimiento
```

Las curvas son lo importante: `PathInterpolator(0.16, 1, 0.3, 1)` es la
desaceleración que usa iOS en sus transiciones — salida rápida y posada larga.
Nada es lineal, nada rebota. Se dibuja solo mientras dura y se desactiva si el
teléfono tiene las animaciones desactivadas.

## Cola, cancelación y vigilante

- Los archivos se apilan en una **cola real**: si no hay enlace esperan, y salen
  en orden en cuanto conecta. Nada se pierde.
- **Cancelar** en la tarjeta de progreso cancela el payload en curso (`cancelPayload`),
  el otro teléfono descarta el archivo a medio escribir y la cola se vacía.
- Si un payload pasa **25 s sin ningún avance**, se cancela y se avisa; la UI no
  se queda clavada en un porcentaje.

## 3. Arquitectura

```
app/src/main/java/com/airgesture/transfer/
├── core/        AirGestureApp · Prefs · GestureState
├── media/       MediaKind · MediaEntry · MediaRepository      (MediaStore vía ContentResolver)
├── camera/      FrameConverter · CameraAnalyzer               (480p, RGBA, cero allocs)
├── vision/      HandTracker · GestureInterpreter · OneEuroFilter · GestureProfile
│                GestureCalibration · ThrustSensor
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
