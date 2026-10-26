# MoonShare 🌙

Pasar archivos entre dos Android **acercando los teléfonos**. Fotos, videos, música,
PDF, APK, juegos: tocás qué querés, juntás los dos aparatos, y se van.

Wi-Fi Direct lleva los datos. NFC hace el pareo. No hay servidor en el medio, no hay
cable, no hay nube, no hay que crear un hotspot ni compartir una clave.

```
  ┌─────────────┐   tocarse   ┌─────────────┐
  │  teléfono A │ ─────────── │  teléfono B │   1 · NFC intercambia el token de pareo
  │  (envía)    │             │  (recibe)   │   2 · B levanta el grupo Wi-Fi Direct
  └─────┬───────┘             └──────┬──────┘   3 · A entra por WPS-PBC, sin diálogo
        │                            │           4 · TCP 192.168.49.1:41795
        └────────── datos ───────────┘           5 · gota de agua por archivo + esfera
```

---

## Cómo conseguir el APK

El proyecto **no incluye el APK compilado**: compilar necesita el Android SDK, y ese
binario no vive en un repo. Tres caminos, del más cómodo al más manual.

### 1 · Dejá que GitHub Actions te lo compile (recomendado)

Hay un workflow en [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml).
Al pushear, corre solo; o se dispara a mano desde **Actions ▸ Build APK ▸ Run workflow**.

Termina con un artefacto **`moonshare-apk`** en la página de la run: lo descargás, lo
pasás al teléfono y lo instalás. `debug` se instala directo; `release` pide haber
configurado la firma (ver abajo).

### 2 · Android Studio

`File ▸ Open…` y elegís esta carpeta. Esperás el sync inicial (baja Gradle + las
dependencias, 2-5 min) y después:

```
Build ▸ Build Bundle(s)/APK(s) ▸ Build APK(s)
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

> Nota: no se versiona `gradle/wrapper/gradle-wrapper.jar`. A Android Studio eso no lo
> frena —lee `gradle-wrapper.properties` y provisioning de Gradle por su cuenta—, pero
> `./gradlew` desde la terminal sí lo necesita. Para eso: `gradle wrapper --gradle-version 8.9`,
> o usá el script del punto 3, que lo regenera solo.

### 3 · Terminal, sin Studio

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
export ANDROID_HOME="$HOME/Android/Sdk"
./tools/setup-and-build.sh          # -> app/build/outputs/apk/debug/…
```

### Instalar en el Redmi (y cualquiera)

1. Pasás el `.apk` al teléfono.
2. Al abrirlo, Android pide permitir **"instalar apps desconocidas"** para el gestor de
   archivos o el navegador. Es un solo toggle, y es obligatorio por el sistema, no por la app.
3. Instalar. Los permisos de la app se piden al primer arranque, con explicación previa.

### Si se cierra sola (autodiagnóstico)

La app trae un reportero de crashes propio, porque un `adb logcat` desde la PC es la
peor forma de debuggear un teléfono que ni siquiera llega a mostrar una pantalla:

- Cualquier excepción que mate el proceso se escribe antes de morir en el almacenamiento
  privado de la app (`filesDir/crash/latest.log`, más hasta 4 anteriores con marca de tiempo).
- Al volver a abrirla aparece un panel oscuro con el resumen: dispositivo, API, versión,
  en qué fase del arranque murió (`APP_BEGIN → NOTIF → SFX → ENGINE → ACTIVITY → FIRST_FRAME → READY`)
  y la cadena de causas.
- Ahí están **Copiar y salir** (deja el reporte en el portapapeles para pegarlo en un issue)
  y **ver todo** para desplegar la traza completa.

Nada de esto requiere root ni permisos raros, y el reporte no sale del teléfono salvo que
vos lo copies. El archivo en sí no es legible desde el explorador de archivos (Android lo
impide para datos privados de otra app); para eso está el botón de copiar.

Además el arranque está escrito a prueba de subsistemas rotos: notificaciones, sonido,
motor de transferencia y cada radio van en `runCatching` separados, así que un fallo en
`SoundPool` o en `WifiP2pManager` degrada esa función en vez de tumbar la app. Las
corrutinas del motor y del `ViewModel` usan `CoroutineExceptionHandler`: una excepción
dentro de un `collect` sin handler es exactamente el mecanismo que produce
*"se queda negra y se sale"*.

---

## Qué te va a pedir el sistema (y por qué)

"Sin configuración" significa sin routers, sin IPs, sin FTP y sin compartir un link. No
significa sin permisos: Android impone tres cosas que ninguna app puede saltarse.

1. **Wi‑Fi encendido y ubicación activada.** Wi‑Fi Direct es una radio del Wi‑Fi. En
   Android 10 a 12, además, `discoverPeers()` devuelve la lista **vacía en silencio** si
   los servicios de ubicación están apagados, así que la app te lo dice explícito en vez
   de hacerte creer que no hay nadie cerca.
2. **Un diálogo de confirmación del grupo la primera vez** (por teléfono). Al crear o
   unirse a un grupo, el sistema muestra su propia confirmación. Es el reemplazo de
   Android del "permite al otro dispositivo" de iOS y no se puede omitir desde una app.
3. **En MIUI, exención de optimización de batería** (Ajustes → Apps → MoonShare → Inicio
   automático: permitir). Sin eso el sistema corta el servicio y la transferencia se
   interrumpe al minimizar la app.

La app reporta cada una de estas condiciones con un texto y una acción (abrir el panel de
Wi‑Fi, reintentar), en vez de mostrar un spinner eterno.

## Qué hace y qué no va a hacer

Conviene leer esto antes de emocionarse, porque la promesa "*igual que AirDrop pero en
Android*" choca con tres cosas que Android impone y ninguna app puede saltarse:

**Lo que sí logra MoonShare**

- Transferencias a **30-80 MB/s** reales entre dos teléfonos modernos por Wi-Fi Direct
  (5 GHz), contra ~1-2 MB/s de Bluetooth o los 25 MB/s del Wi-Fi hotspot clásico.
- **Cero configuración de red**: no se crea un hotspot visible, no se comparte clave,
  no se elige una red.
- **Cero fricción de pareo** si los dos tienen NFC: un toque y se conecta (el diálogo
  "¿aceitás conectar con X?" desaparece porque la app ya tiene la dirección del par y usa
  WPS-PBC, que no pide confirmación).
- Cualquier tipo de archivo, sin límite de tamaño (un `.apk` de 2 GB entra igual).
- Funciona **sin internet** y sin datos móviles. En un avión, en un campo, donde sea.
- Suena una **gota de agua** por archivo y sube el nivel del agua dentro de la **esfera
  de cristal**; en Ajustes se regula el volumen, se apaga o se limita solo a lo entrante.

**Lo que no puede hacer, y por qué**

| Límite | Motivo real |
|---|---|
| **No transfiere con iPhone** | AirDrop usa AWDL + un protocolo privativo de Apple. No es que Android no pueda: es que del otro lado nadie escucha. MoonShare habla con Android, y con Samsung/Xiaomi/etc. sí —no solo entre dos MoonShare. |
| **El primer pareo necesita que el otro teléfono tenga la app en "Recibiendo"** | Wi-Fi Direct exige que un extremo levante el grupo. Android no permite crearlo desde el fondo. Es la única pantallita que queda. |
| **Nada de "segundo plano"** | Android 12+ bloquea `discoverPeers()`/`connect()` si la app está en segundo plano. Por eso existe el servicio en primer plano con notificación: la app se minimiza, pero nunca se duerme del todo. |
| **Sin NFC no hay "choque"** | El **Redmi 9C** que no es la variante *NFC* no tiene antena. MoonShare lo detecta y cae al pareo por lista: un tap en vez de un choque. Igual sin configuración de red. |
| **El usuario siempre aprueba la entrada** (salvo que lo desactives) | No es flojera: aceptar archivos de un desconocido en un colectivo sin preguntar es una puerta abierta. En Ajustes ▸ Transferencia está *Aceptar sin preguntar* para el que lo quiera. |

**Sobre MIUI (tu caso, el Redmi):** MIUI mata procesos agresivamente. La app pide
"Sin restricciones" de batería en el onboarding y en Ajustes ▸ Permisos, pero si el
usuario lo niega, las transferencias largas se van a cortar. Es un ajuste del
sistema, no un bug. Está documentado en la propia app.

---

## Arquitectura

```
com.moonshare
├── MoonApplication          · composition root, sin DI framework
├── MainActivity             · intents, tema, keep-screen-on, NFC lifecycle
├── transfer/
│   ├── TransferEngine       · máquina de estados, cola, consentimiento, feedback
│   └── TransferService      · foreground service, notificación de progreso
├── net/
│   ├── MoonProtocol         · framing TCP: [len][tipo][payload JSON|binario]
│   ├── ReceiveEndpoint      · ServerSocket, escribe lo que entra en MediaStore
│   ├── SendEndpoint         · cliente, stream + pacing + límite de velocidad
│   └── wifi/WifiDirectManager · WifiP2pManager: grupo, discovery, join, enlaces
├── nfc/
│   ├── MoonCardService      · HCE: este teléfono se hace pasar por tag NDEF Tipo-4
│   ├── NfcHandoff           · reader mode + parser ISO-DEP de respaldo
│   ├── PairingToken         · la carga útil: {addr, nombre, puerto}
│   └── NfcAvailability      · ¿hay antena? ¿está encendida? ¿hay HCE?
├── data/
│   ├── media/MediaRepository · MediaStore (fotos/video/audio/docs/apk) + SAF
│   ├── settings/SettingsStore · DataStore Preferences, 24 ajustes
│   └── model/                · FileItem, TransferTask, HistoryEntry
├── ui/
│   ├── glass/Glass.kt        · Modifier.moonGlass: vidrio sin un solo borde
│   ├── glass/CrystalOrb.kt   · la esfera: agua adentro, onda por archivo
│   ├── home | pick | transfer | settings | history | pairing
│   └── theme/                · paleta, tipografía, formas
├── core/sound/SfxPlayer      · SoundPool de las gotas (3 streams, mezcla)
└── util/                     · SaveSink (MediaStore), Filesx, Notifx, permisos
```

Tres decisiones de diseño que valen la pena explicar:

**El receptor es siempre el Group Owner.** En Wi-Fi Direct el *GO* es el que manda el
grupo y su IP es siempre `192.168.49.1`. Fijar ese rol (en vez de dejar que la
negociación WPS decida, que es lo que hacen otras apps) elimina la fase de "descubrir
quién manda" y hace que el conector sepa de antemano a qué IP ir. Determinismo >
flexibilidad acá.

**El protocolo es un socket crudo, no HTTP.** Se podría haber montado NanoHTTPD, pero
son 200 líneas de framing y cero dependencias, cero cabeceras, cero riesgo de que el
otro lado tenga un server incompatible. Encima permite full-duplex: un mismo socket
lleva el stream de datos y los ACK de confirmación en paralelo.

**NFC solo negocia, nunca transporta.** Un tag NDEF admite ~1 KB a 424 kbit/s: un
`image/jpeg` de 4 MB tardaría 80 segundos por NFC, y Wi-Fi Direct a ese tiempo ya
iba por la mitad del archivo. Entonces NFC mueve 60 bytes —la dirección del par— y
esos 60 bytes compran seis toques menos de UI.

## Ajustes (24, agrupados)

| Sección | Cosas que podés controlar |
|---|---|
| **Apariencia** | negro puro AMOLED, reducir movimiento |
| **La esfera** | mostrar/ocultar, intensidad del vidrio (0.2 – 1.0) |
| **Sonido** | gota on/off, volumen, sonar solo al recibir, vibración, botón *Probar* |
| **Transferencia** | aceptar sin preguntar, guardar en galería, mantener nombre original, streams en paralelo (1-4), mantener pantalla encendida, timeout de consentimiento (15-600 s) |
| **Radio y red** | preferir 5 GHz, dejar el grupo armado después de enviar, nombre del dispositivo, puerto de escucha |
| **NFC** | parear al tocar, estado del hardware, atajo a los ajustes de NFC |
| **Permisos** | estado en vivo de cercanos/medios/notificaciones, acceso a todos los archivos, optimización de batería |
| **Avanzado** | historial on/off + verlo, límite de velocidad en KB/s, logs detallados, borrar historial, reset total |

## Compilación y release firmada

```bash
./gradlew assembleDebug          # APK de prueba, firma debug, instalable
./gradlew assembleRelease        # minificado + shrinkResources
./gradlew lint testDebugUnitTest connectedDebugAndroidTest
```

Para que la app sea tuya también en el sentido legal/técnico (nadie más puede firmar
una actualización tuya), seguí [`OWNERSHIP.md`](OWNERSHIP.md) y
`keystore.properties.example`. Si `keystore.properties` existe, `app/build.gradle.kts`
lo levanta solo; si no, `release` se firma con la clave de debug para que no te trabe.

## Límite conocido y roadmap honesto

- **6 GHz**: Android lo expone de forma distinta según OEM; hoy la banda se pide por
  reflexión con *fallback* a "que elija la radio". Funciona, pero no es garantía.
- **Descubrimiento en Xiaomi**: algunos ROMs tardan 8-15 s en listar pares. El toque
  NFC lo saltea; sin NFC hay que esperar el listado.
- **No hay cifrado en el enlace**: Wi-Fi Direct usa WPA3-SAE por defecto en Android 10+
  (el grupo está cifrado en el aire), pero el TCP no agrega su propio TLS. Para un
  pareo a 20 cm de distancia es razonable; si lo querés duro, el próximo paso es un
  pre-shared key derivado del token NFC y un HKDF sobre la conexión.
- **Faltan tests de instrumento**: hay lugar para `WifiDirectManager` con un fake del
  `WifiP2pManager` y para el codec de `MoonProtocol` (ida y vuelta de frames). Están
  en el TODO porque el objetivo de esta primera pasada fue tener el camino completo
  funcionando, no el 100% de cobertura.

## Sonidos

`tools/make_sfx.py` sintetiza las tres gotas (44.1 kHz, 16 bits, mono, ~40 KB c/u) con
una envoltura exponencial, glissando descendente y reverberación de peines de
Schroeder. Quería un sonido de agua puntual y caro, no un paquete de assets con
licencia dudosa. Se regenera con:

```bash
python3 tools/make_sfx.py
```

Cualquier parámetro (registro, cola, mezcla de reverberación) es un argumento de la
función `drop()`. Cambiar el sonido no requiere saber de edición de audio, y el repo no
crece con binarios inauditoriables.
