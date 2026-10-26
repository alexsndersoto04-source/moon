# MoonShare — aviso de propiedad y uso

**Este código es del dueño de este repositorio. Todo, sin reserva alguna.**

No hay licencia de terceros que lo limite, no hay CLA, no hay "versión pro" que
restrinja funciones, no se phonea a ningún servidor y no se recolecta ningún dato.

| | |
|---|---|
| Código fuente | 100% del dueño de la repo |
| Binarios de terceros | Ninguno embebido |
| Analítica / trackers / publicidad | Ninguno |
| Servidor intermedio | No existe: los archivos van de teléfono a teléfono |
| Telemetría de errores | Ninguna |

## Por qué se puede afirmar esto

- **Dependencias**: solo AndroidX, Kotlin y kotlinx. Nada de SDKs de terceros con
  permisos ocultos. Están listadas una por una en `gradle/libs.versions.toml`, así
  que auditar el árbol de dependencias es leer un archivo.
- **Red**: el único socket que abre la app es `ServerSocket`/`Socket` sobre la
  interfaz Wi-Fi Direct (subred point-to-point 192.168.49.0/24). No hay ningún
  hostname hardcodeado en el fuente; se puede verificar con
  `grep -rn "http" app/src/main/java` y ver que no aparece ninguno.
- **Permisos**: los que están en `AndroidManifest.xml` son los mínimos que exige
  Wi-Fi Direct en cada nivel de API (10, 11, 12, 13, 14 y 15 piden cosas distintas).
  Están comentados uno a uno ahí mismo.
- **Sonidos**: los `.wav` en `app/src/main/res/raw` son generado por
  `tools/make_sfx.py`, un script de 200 líneas de Python que está en el repo. No es
  un asset descargado de un sitio de terceros: si querés cambiar el sonido, cambiás
  el script y regenerás.

## Al firmar la release

Mientras no exista `keystore.properties`, la variante `release` se firma con la
clave de debug de Android Studio (sirve para probar, no para publicar). Para que la
app sea *tuya* también en el sentido de Android —es decir, que solo vos puedas
firmar actualizaciones— generá tu keystore siguiendo `keystore.properties.example`.
**Respaldá ese archivo**: perder la clave significa no poder actualizar nunca más la
misma instalación.
