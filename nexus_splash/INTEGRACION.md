# Integración del splash de Nexus (horizontal)

Paquete de ejemplo: `com.nexus.app`. Si el tuyo es otro, cambia la línea `package` de los dos `.kt`
y el import en `MainActivity.kt`.

## 1. Dependencias (`app/build.gradle.kts`)

```kotlin
dependencies {
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")   // enableEdgeToEdge necesita >= 1.8
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
}
```

## 2. Manifest (`AndroidManifest.xml`)

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:screenOrientation="sensorLandscape"
    android:theme="@style/Theme.Nexus.Starting">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

## 3. Archivos

| Archivo | Destino |
|---|---|
| `NexusSplashScreen.kt` | `app/src/main/java/com/nexus/app/ui/splash/` |
| `MainActivity.kt` | `app/src/main/java/com/nexus/app/` (fusiona con la tuya si ya existe) |
| `themes.xml` | `app/src/main/res/values/` (fusiona si ya tienes uno) |
| `splash_blank.xml` | `app/src/main/res/drawable/` |

En `MainActivity.kt`, `AppRoot()` es un marcador: pon ahí la raíz de tu app.

## 4. Ajustes rápidos

- **Duración**: `TOTAL_S` en `NexusSplashScreen.kt` (5.8 s). Hay un `require` que impide pasar de 8 s.
- **Textos**: parámetros `title`, `subtitle` y `footer` de `NexusSplashScreen(...)`.
- **Color azul**: `NexusBlue` (`#2979FF`).
- **Orientación**: el splash está compuesto para horizontal (16:9 a 20:9 y tabletas 4:3). `sensorLandscape` permite ambos sentidos.
- **Escena**: aerogeneradores, campo solar y torres están en las secciones 4–7 de `draw()`; cambia posiciones (fracciones de ancho `w`) o cantidad de paneles (`nPan`) ahí.
- **Omitir**: tocar la pantalla llama a `onFinished` de inmediato.
- Si necesitas cargar datos al arrancar, hazlo en paralelo y pasa a la siguiente pantalla cuando
  termine **la animación y** la carga, no solo una de las dos.
