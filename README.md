# Lite Launcher

Launcher mínimo para Android 13+ (minSdk/targetSdk 33). Sin dependencias.

Compilar: abre la carpeta en Android Studio (Hedgehog o superior, JDK 17) y sincroniza Gradle.
- Debug:   Build > Build APK(s)  ->  app/build/outputs/apk/debug/
- Release: Build > Generate Signed App Bundle / APK, o `./gradlew assembleRelease`
  (queda firmado con la clave debug, instalable con `adb install`).

Instalar y activar:
    adb install -r app-release.apk
Luego: Ajustes > Apps > Apps predeterminadas > App de inicio > Lite Launcher.

Volver al launcher anterior: misma pantalla, elige el de Xiaomi (com.miui.home).
