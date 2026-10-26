#!/usr/bin/env bash
# Compilador local de MoonShare.
#
#   ./tools/setup-and-build.sh            -> APK de debug (instalable directo)
#   ./tools/setup-and-build.sh release    -> APK release (necesita keystore.properties)
#
# Prefiere el wrapper de Gradle si está; si falta el jar (no se versiona acá),
# cae a un `gradle` del PATH y, en el peor caso, te dice exactamente qué instalar.
set -euo pipefail

VARIANT="${1:-debug}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- prerequisitos
command -v java >/dev/null 2>&1 || die "Falta un JDK 17+.
  Debian/Ubuntu : sudo apt install openjdk-17-jdk
  macOS         : brew install --cask temurin@17"

if [[ ! -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  die "Falta el Android SDK (ANDROID_HOME no está definido).
  La vía corta: instalá Android Studio, que lo deja listo y exporta la variable.
  Sin Studio: descargás 'commandlinetools-linux' y ejecutás
     sdkmanager \"platforms;android-35\" \"build-tools;35.0.0\" \"platform-tools\"
  y exportás ANDROID_HOME al directorio que crees."
fi

# ------------------------------------------------------------------ gradle
TASK="assemble${VARIANT^}"

if [[ -f gradle/wrapper/gradle-wrapper.jar ]]; then
  say "Compilando $TASK con el wrapper"
  ./gradlew "$TASK" --stacktrace
elif command -v gradle >/dev/null 2>&1; then
  say "No está gradle-wrapper.jar; regenerando el wrapper con el gradle del PATH"
  gradle wrapper --gradle-version 8.9 >/dev/null
  ./gradlew "$TASK" --stacktrace
else
  die "Ni gradle-wrapper.jar ni un gradle en el PATH.
  Opción A (recomendada): abrí la carpeta en Android Studio → Build ▸ Build APK(s).
  Opción B: instalá Gradle 8.9 (https://gradle.org/install/) y volvé a correr este script.
  Opción C: dejá que te lo compile GitHub Actions — mirá la pestaña Actions de
            la repo y descargás el artefacto 'moonshare-apk'."
fi

APK="$(find app/build/outputs/apk -name '*.apk' 2>/dev/null | head -1 || true)"
[[ -n "$APK" ]] || die "Gradle terminó pero no encuentro el APK (revisá el log de arriba)."

say "Listo"
printf '  APK : %s (%s)\n' "$APK" "$(du -h "$APK" | cut -f1)"
printf '  Instalar por USB con adb install -r "%s"\n' "$APK"
printf '  O pasás el archivo al teléfono y lo abrís desde el gestor de archivos.\n'
