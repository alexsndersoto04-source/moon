# MoonShare

Compartir archivos entre dos Android **con solo acercar los teléfonos**.

Elegís fotos, videos, música, documentos o cualquier archivo, juntás los dos equipos y la transferencia empieza. Sin buscar dispositivos, sin aceptar cada envío y sin crear una red manualmente.

MoonShare usa:

- **NFC** para identificar al otro teléfono durante el acercamiento.
- **Nearby Connections** para negociar y transportar los archivos sin configurar una red a mano.
- **SHA-256** para comprobar que cada archivo llegó completo y sin daños.

No hay cuentas, servidores, nube ni conexión a Internet.

---

## La experiencia buscada

### Para enviar

1. Abrí MoonShare.
2. Tocá **Elegir fotos, videos o archivos**.
3. Elegí lo que querés pasar.
4. Acercá el otro teléfono.
5. Al juntarse, aparece el efecto transparente de proximidad y el envío empieza solo.

No hay un botón posterior de “Enviar” después de elegir: la selección ya deja activo el lector NFC. La hoja de dispositivos solo aparece como respaldo cuando un teléfono no tiene NFC o esa función está desactivada; en ese caso, la persona elige explícitamente el equipo correcto. MoonShare nunca envía por su cuenta al primer dispositivo que aparece.

### Para recibir

1. Activá **Disponibilidad** una sola vez en la pantalla principal.
2. Podés minimizar MoonShare: un servicio en primer plano mantiene listo el receptor.
3. Cuando alguien acerca su teléfono y comienza un envío dirigido a vos, el toque físico funciona como consentimiento.
4. Los archivos se guardan y el teléfono muestra la recepción sin pedirte que aceptes cada envío.

El consentimiento por toque tiene una ventana breve y usa un código efímero entregado por NFC. Una conexión que no viene de ese acercamiento sí puede mostrar la confirmación de recepción.

---

## Qué puede garantizar Android y qué no

MoonShare está diseñado para acercarse lo máximo posible a la experiencia de acercar y compartir, pero Android impone límites reales:

| Situación | Comportamiento |
|---|---|
| Dos Android con MoonShare, desbloqueados y Disponibilidad activa | Elegir, acercar y transferir sin aceptar el envío |
| MoonShare minimizado en el receptor | La Disponibilidad permanece activa mediante el servicio en primer plano |
| Primera instalación | Android exige otorgar permisos de dispositivos cercanos, archivos y notificaciones |
| Primera conexión en algunos fabricantes | Android puede pedir permisos o mostrar avisos del sistema; ninguna app puede omitirlos siempre |
| Receptor bloqueado, apagado, sin NFC/Wi-Fi o con la Disponibilidad desactivada | Android no permite garantizar el inicio automático |
| Sin MoonShare en el otro teléfono | No se puede completar la transferencia |
| iPhone / AirDrop | No es interoperable: AirDrop es un protocolo cerrado de Apple |

La app no simula estos casos ni declara que una función existe cuando solo está dibujada en pantalla.

---

## Diseño

Interfaz profesional y minimalista:

- Blanco y azul, con grises neutros.
- Sin tema futurista, neón, esfera de cristal ni adornos innecesarios.
- Sin bordes decorativos: las zonas se distinguen por fondo y espacio.
- Una hoja translúcida de proximidad cuando los teléfonos se acercan.
- Una tarjeta translúcida de progreso mientras pasa un archivo.

Los Ajustes contienen únicamente opciones conectadas al comportamiento real: nombre del teléfono, NFC, dónde guardar lo recibido, pantalla encendida durante la transferencia, preferencia de 5 GHz, sonido, vibración, permisos, batería y actividad local.

---

## Integridad y seguridad del toque

- El receptor anuncia por NFC un código efímero asociado a esa Disponibilidad.
- El código se renueva al ser leído; activar Disponibilidad y esperar más de cinco minutos no deja vencido el emparejamiento.
- El emisor presenta ese código al saludar al receptor.
- Solo un código correcto, leído recientemente durante el acercamiento, autoriza automáticamente esa recepción.
- Cada archivo viaja con su tamaño y hash SHA-256.
- El receptor recalcula el hash: si el tamaño o el hash no coincide, descarta el archivo y no lo marca como recibido.

Nearby Connections elige el medio disponible entre los teléfonos y cifra el enlace según sus capacidades. MoonShare no envía archivos por NFC: NFC solo transporta la identificación del acercamiento porque es demasiado lento para archivos.

---

## Cómo conseguir el APK

### GitHub Actions

El workflow `.github/workflows/build-apk.yml` compila el proyecto y publica el APK como artefacto **`moonshare-apk`**.

Al terminar una ejecución exitosa:

1. Entrá a la ejecución en **Actions**.
2. Descargá **Artifacts → moonshare-apk**.
3. Instalá el APK en el teléfono.

La variante `debug` se puede instalar directamente para pruebas.

### Android Studio

Abrí esta carpeta con Android Studio, esperá la sincronización de Gradle y ejecutá:

```text
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

El resultado queda en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Terminal

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
export ANDROID_HOME="$HOME/Android/Sdk"
./tools/setup-and-build.sh
```

---

## Pruebas

### Automáticas

La CI ejecuta:

- Compilación del APK.
- Lint de compatibilidad.
- Pruebas de arranque con Robolectric en API 29.
- Pruebas del código efímero del toque NFC.
- Pruebas de renovación del token.
- Pruebas de ida y vuelta del protocolo, incluyendo hash en el cierre de cada archivo.

### En teléfonos reales

NFC, Nearby Connections, los diálogos del sistema y las restricciones de cada fabricante no se pueden probar honestamente en un emulador. Antes de considerar una versión terminada, debe verificarse con dos Android:

1. Instalación limpia y primera concesión de permisos.
2. Disponibilidad activada y app minimizada.
3. Esperar más de cinco minutos y luego acercar los teléfonos.
4. Enviar una foto, un video grande, un documento y un archivo de 0 bytes.
5. Cancelar a mitad de un archivo grande.
6. Alejar los teléfonos durante la conexión.
7. Repetir con la pantalla encendida y con MoonShare minimizado en el emisor.
8. Comprobar que el archivo recibido abre y coincide con el original.
9. Probar en al menos un Xiaomi/MIUI y, si es posible, un Samsung o Pixel.

Una compilación exitosa no sustituye esta prueba física.

---

## Arquitectura

```text
com.moonshare
├── MainActivity             Intents, NFC y ciclo de vida de la ventana
├── MoonApplication          Arranque tolerante y composición de dependencias
├── transfer/
│   ├── TransferEngine       Coordinación, cola, toque NFC y estados
│   └── TransferService      Disponibilidad y transferencias minimizadas
├── net/
│   ├── MoonProtocol         Protocolo binario y mensajes de control
│   └── nearby/              Descubrimiento, conexión y Payload.File
├── nfc/
│   ├── MoonCardService      El receptor se anuncia como tag NDEF
│   ├── NfcHandoff           El emisor lee el acercamiento
│   └── PairingToken         Identidad efímera del acercamiento
├── data/                    Archivos, ajustes e historial local
└── ui/                      Pantallas blancas y azules, proximidad y progreso
```

### Decisiones principales

**Nearby negocia el enlace.** Cuando la Disponibilidad está activa, el receptor se anuncia con una identidad efímera. Después del toque, el emisor reconoce esa identidad entre los teléfonos cercanos y Nearby Connections negocia el medio de transporte. MoonShare no supone una IP ni crea un grupo Wi-Fi Direct a mano.

**El toque es el gesto de consentimiento.** No se reemplaza por un temporizador ni por aceptar automáticamente cualquier conexión: el receptor exige que el código efímero haya sido leído recientemente por NFC.

**Un servicio mantiene la Disponibilidad.** Android y especialmente algunos fabricantes detienen procesos minimizados. El servicio en primer plano mantiene la recepción preparada y muestra el progreso; por eso existe la notificación del sistema.

---

## Compilar y verificar

```bash
gradle :app:assembleDebug
gradle :app:lintDebug
gradle :app:testDebugUnitTest
```

La firma de una versión personal se explica en [`OWNERSHIP.md`](OWNERSHIP.md).
