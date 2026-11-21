# Nexus App

App Android (Kotlin) para el control remoto DJI que asiste la **inspección de aerogeneradores con dron**. Propiedad de Nexus · [nexusio.com.mx](https://nexusio.com.mx).

- Paquete: `mx.com.nexusio.fly` · versión 3.0
- Key de DJI Developer: `DJI_API_KEY=...` en `local.properties` (registrada para `mx.com.nexusio.fly`)
- Backend Supabase: `ui/BackendConfig.kt`
- `minSdk 24` / `targetSdk 30` · solo arm64
- **DJI Mobile SDK V5 5.18.0** (aircraft, networkImp, uxsdk local) · MapLibre

## Módulos

| Módulo | Ruta | Qué hace |
|---|---|---|
| **Drone** | `drone/` | Registro del SDK DJI, configuración de piloto y aeronave, vuelo de inspección (Three Stop) |
| **One Stop** | `drone/onestop/` | Inspección automática de las tres palas con el rotor en una sola posición |
| **UI** | `ui/` | Splash, home, sesión del piloto y actualización de la app |

## Modos de inspección

La app ofrece dos métodos. La diferencia de fondo es **cuántas veces se detiene el rotor** y **quién vuela**.

```
        THREE STOP                              ONE STOP
  (se gira el rotor 3 veces; la pala     (el rotor queda quieto en «Y»;
   a inspeccionar queda a las 12h)        la app vuela las 3 palas)

  Parada 1     Parada 2     Parada 3            pala 12h
   A (12h)      B (12h)      C (12h)               |
     |            |            |         pala 8h \  |  / pala 4h
     |            |            |                  \ | /
   (HUB)        (HUB)        (HUB)               (HUB)
                                                   |
  Subida y bajada automáticas;           Misión automática de waypoints
  el piloto coloca el dron y             4h → 12h → 8h, con 5 mediciones
  corrige a los lados                    de calibración previas
```

| | One Stop | Three Stop |
|---|---|---|
| Paradas del rotor | 1, con las tres palas en «Y» (12h, 4h y 8h) | 3: una pala arriba en cada parada, rotor girado 120° entre paradas |
| Calibración | 5 mediciones con láser o visión: buje, punta 12h, raíz 12h, punta 4h y punta 8h | Sin mediciones: foto de ID y colocación manual a 3,8 m |
| Quién vuela | Misión automática de waypoints; el piloto puede frenarla con el botón o tirando el stick | Subida/bajada automáticas; el piloto coloca el dron, corrige a los lados y confirma cada tramo |
| Distancia a la pala | 13 m en la raíz y 11 m en la punta (RTK: 10 m y 5,5 m) | 3,8 m, válida entre 3,3 y 4,8 m |
| Velocidad | 1 m/s, con parada y foto en cada waypoint | 0,3–1,5 m/s; en los últimos 7 m frena hasta 0,2 m/s |
| Fotos | Una por waypoint | Una cada 2 s (hasta 255 por tramo) más START y END |
| Orden | Palas 4h → 12h → 8h; en cada una LE, SS, TE y PS | Pala a pala; en cada una TE, PS, LE y SS (sube, baja, sube, baja) |
| Si se pierde el enlace | Vuelve a casa (la altura de RTH no cruza el rotor) | Se queda en el sitio (HOVER) |

**Superficies de la pala:** LE = borde de ataque · TE = borde de salida · SS = cara de succión · PS = cara de presión.

### Flujo de One Stop

```
 Calibración (5 mediciones)               Plan             Inspección                 Salida
 ┌────────┬───────────┬──────────┐     ┌─────────┐   ┌─────────┬──────────┬─────────┐   ┌────────────┐
 │ Buje   │ Punta 12h │ Raíz 12h │ ──► │ Eje del │──►│ Pala 4h │ Pala 12h │ Pala 8h │──►│ 15 m fuera │
 │        │ Punta 4h  │ Punta 8h │     │ rotor + │   │ LE SS   │ LE SS    │ LE SS   │   │ del rotor  │
 └────────┴───────────┴──────────┘     │ ruta    │   │ TE PS   │ TE PS    │ TE PS   │   │ y RTH      │
        a 4–6 m de cada punto          └─────────┘   └─────────┴──────────┴─────────┘   └────────────┘
                                                      arcos de cambio entre palas,
                                                      siempre delante del rotor
```

### Flujo de Three Stop (por cada pala)

```
 Foto de ID ─► TE (sube) ─► PS (baja) ─► LE (sube) ─► SS (baja) ─► Aterrizaje ─► girar rotor ─► siguiente pala
      cada tramo: 3,8 m con láser/visión · foto cada 2 s · el piloto confirma «Sí, siguiente» o «Repetir»
```

> Hay una animación interactiva paso a paso de ambos métodos en [`docs/animacion-onestop/inspeccion-universal.html`](docs/animacion-onestop/inspeccion-universal.html).

## One Stop: lo más llamativo

**1. Plan de vuelo calculado en el campo.** Con las cinco mediciones la app deduce el eje del rotor, la longitud real de pala y la geometría 3D (palas, torre, góndola), y genera la ruta (`flightcore/`: `MissionPlanner`, `PathPlanner`, `GeoMission`). Cada medición se valida (p. ej. la punta debe caer entre el 85 % y el 95 % del radio); si no es coherente, la app la rechaza. La ruta se exporta como **KMZ/waylines** de DJI (`KmzWaylineWriter`) y se vuela sin pausas salvo en los waypoints que lo requieren.

**2. Medición de distancia con láser o visión.** Matrice 4E/4T, M30 y Zenmuse H30 usan láser; el resto (p. ej. Mavic 3E) mide con visión. El láser se contrasta con la visión para detectar mediciones erróneas (objeto más cercano delante, fuera de rango). Muestreo estadístico: ≥ 20 muestras con desviación ≤ 0.3 m a 4–6 m.

**3. Cambio de pala sin cruzar el rotor.** Entre palas el dron sube en un arco por delante del plano del rotor y nunca pasa por detrás de la góndola ni de la raíz. La altura de RTH se ajusta para no cruzar el rotor.

**4. Seguridad por capas.**
- `ProximityGuard`: frena si algún sensor lee < 4 m de forma sostenida.
- `ObstacleAvoidanceSetup`: configura la evasión DJI en BRAKE y restaura los valores previos al salir.
- `EmergencyStop`, `ReturnHomeSetup` y validaciones del plan (`SafetyCheck`, `AppSafety`) que bloquean la medición si hay violaciones.
- Los waypoints del plan van firmados con HMAC y se validan antes de enviarse.

**5. Visor 3D del plan** (`planview/`). Turbina en alambre, ruta pendiente/hecha, dron coloreado por holgura (rojo → ámbar → verde) y cono de la cámara. Se abre como miniatura sobre el vídeo FPV y al tocarla intercambia lugar con el vídeo, estilo DJI.

**6. Perfiles de captura por dron** (`CaptureProfile`). Zoom 3× y obturador electrónico en Matrice 4E; gran angular con obturador mecánico y dewarping en Mavic 3E. Escalera de exposición ajustable por el piloto (+/−) que nunca baja de una velocidad mínima. Al cerrar, la cámara y la evasión de obstáculos se restauran para que Three Stop quede como estaba.

**7. Archivo de turbina `.job`.** Define el modelo de turbina y se carga una vez por proyecto. Formato en [`docs/onestop_job_format.md`](docs/onestop_job_format.md) y ejemplo en [`docs/ejemplo_turbina.job`](docs/ejemplo_turbina.job). El dron y la cámara se detectan automáticamente (catálogo en `values.json`).

## Compilar y probar

Requiere el JDK de Android Studio (JBR).

```bash
export JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --offline
```

Los tests unitarios incluyen un **simulador de vuelo con viento** y una turbina virtual con láser por raycast (`app/src/test/`) para validar el plan sin dron.

## Estado

- Probado en el simulador de DJI (DJI Assistant 2 + RC Pro Enterprise + Mavic 3E) y con tests unitarios.
- **Aún no validado en vuelo real.**
- `assembleRelease` genera un APK sin firmar (falta configurar `signingConfig`).

Más detalle de diseño y decisiones en [`context.md`](context.md).
