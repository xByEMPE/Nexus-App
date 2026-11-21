# Rediseño de interfaz Nexus App (excepto las pantallas de vuelo)

Fecha: 2026-11-20 · Proyecto: `C:\Users\airsc\Documents\Nexus App` (paquete `mx.com.nexusio.fly`).

## Intención

Que la app de inspección de aerogeneradores tenga un aspecto limpio y profesional de sector industrial, alineado con la marca Nexus, y que se entre directo a la inspección de aerogeneradores, el único servicio que existe hoy.

## Objetivos
- Rediseñar y redistribuir todas las pantallas que no son de vuelo con la paleta Nexus.
- Splash → pantalla principal del dron, siempre (mando DJI, celular y tablet).
- Sin emojis: iconos vectoriales profesionales.

## No objetivos / anti-objetivos
- **No tocar** `FlightActivity` (Three Stop) ni `OneStopFlightActivity` (cámara, visor 3D, HUD), ni sus diálogos. Deben verse exactamente igual que antes.
- No cambiar la lógica: DJI, Supabase, sesiones, planificación y actualizaciones quedan igual.
- No reescribir en Compose (solo el splash, que ya existe, sigue en Compose).

## Enfoque elegido: A (reestilizar XML con un sistema de diseño Nexus)

Opciones consideradas:
- **A. XML + tema, estilos y recursos Nexus** (elegido). Los IDs se conservan, así que el Kotlin solo se toca donde pinta colores a mano. Bajo riesgo.
- **B. Reescribir en Compose.** Más movimiento, pero reescribe la lógica de cada activity; varias veces el trabajo y el riesgo antes de las pruebas en campo.
- Mezclar las dos: descartado, dejaría dos estilos de código.

Bocetos aprobados: `.superpowers/brainstorm/674-1795218614/content/layout-principal-v2.html` y `pantallas.html`.

## Sistema de diseño

Tokens (de `NEXUS/src/lib/palette.ts`) en `res/values/colors.xml`:

| Token | Hex | Uso |
|---|---|---|
| `nx_background` | `#000000` | Fondo de pantalla |
| `nx_surface` | `#090A0C` | Tarjetas, barra superior |
| `nx_surface_raised` | `#101114` | Campos, filas destacadas |
| `nx_border_subtle` | `#262626` | Bordes y divisores |
| `nx_border_strong` | `#3B3B3B` | Botones secundarios, controles segmentados |
| `nx_accent` | `#2F7BFE` | Acción principal, selección |
| `nx_accent_deep` | `#001233` | Fondo del avatar, halos |
| `nx_accent_muted` | `#5F72A0` | Etiquetas de sección (MAYÚSCULAS, espaciado 0.18em) |
| `nx_telemetry` | `#70A5FF` | Datos en vivo, valores leídos del job, estado "normal" |
| `nx_critical` | `#FF5A4E` | Fallas, acciones destructivas |
| `nx_caution` | `#E8B04B` | Avisos |
| `nx_fg` / `nx_fg_muted` | `#FFFFFF` / `#9AA3B2` | Texto principal / secundario |

- **Tema** `Theme.Nexus.App` (MaterialComponents oscuro, sin barra de acción), con colores primario/superficie Nexus y `alertDialogTheme` / `materialAlertDialogTheme` oscuros. Así los `AlertDialog` de las pantallas rediseñadas toman el estilo sin cambiar código.
- **Estilos reutilizables:** `Nx.TopBar`, `Nx.Card`, `Nx.SectionLabel`, `Nx.Field` (EditText/Spinner), `Nx.Button.Primary`, `Nx.Button.Secondary`, `Nx.Button.Danger`, `Nx.Segmented`, `Nx.Value.Mono` (valores numéricos en monoespaciada).
- **Fondos** en `res/drawable`: `bg_nx_card`, `bg_nx_field`, `bg_nx_field_focused`, `bg_nx_btn_primary/secondary/danger` (con estados pressed/disabled), `bg_nx_row_selected` (borde izquierdo azul) y las tarjetas de salud por estado (borde superior azul claro/ámbar/rojo/gris).
- **Iconos:** Lucide (licencia ISC, libre), convertidos a VectorDrawable (`ic_nx_*.xml`) con trazo 1.6 y tinte por tema. Se agrega el aviso de licencia en `docs/licencias/lucide.txt`. Iconos: back, user, mail, lock, eye, settings, refresh, rotate-ccw, chevron-down, check, alert-triangle, log-out, plus, shield, fan, cpu, crosshair/satellite, battery, gamepad, wifi/signal, camera, map-pin, download, history, file-up, database, x, wind-turbine (propio, mismo estilo).
- **Tipografía:** la del sistema; los valores numéricos en `monospace`. No se agregan fuentes.

## Pantallas

1. **Arranque.** `SplashActivity.goNext()` va siempre a `MainActivity`. Se eliminan `HomeActivity` y `activity_home.xml` y se quitan del manifest. No se pierde nada: el login está en Perfil de piloto y en el diálogo, y `MainActivity` ya renueva el token (`SessionManager.freshToken`).
2. **Principal del dron (`activity_main.xml`)**, opción A:
   - Barra superior: `NEXUS` · "Inspección de aerogeneradores" · píldora de estado del dron (reutiliza `tv_drone_status`) · píldora del piloto (abre Perfil de piloto) · botón de engrane (abre Ajustes).
   - Tres columnas:
     - **Proyecto:** spinner, cliente/parque, turbinas pendientes con actualizar. La fila seleccionada lleva borde azul y las completas texto azul claro.
     - **Inspección:** control segmentado One Stop / Three Stop (sobre el `RadioGroup` actual). Con Three Stop: pala a inspeccionar y largo de pala. Con One Stop: la tarjeta del job (resumen + Configurar) y la **orientación de palas con la foto de la turbina** ocupando el resto de la tarjeta, más Reiniciar registro.
     - **Aeronave:** imagen o modelo del dron, estado, modo de palancas, CONTINUAR.
   - La versión de la app va pequeña al pie.
3. **Orientación de palas.** `RotorOrientationView` deja de dibujar la estrella y dibuja `turbine_rotor.png`: `nexus_splash/turbine.jpg` recortado al rotor (franja superior del 66 %) y con el fondo blanco quitado, guardado como PNG transparente en `drawable-nodpi`. La imagen se escala con *fit center* dentro de la vista. Los tres botones A/B/C van sobre las puntas, en coordenadas relativas a la imagen: 12h ≈ (50.5 %, 10 %), 8h ≈ (12 %, 88 %), 4h ≈ (89.5 %, 88 %). Azul relleno = asignada, gris = "?". El toque, el ciclo ?→A→B→C y `onOrientationChangedListener` no cambian.
4. **Login (`dialog_login.xml`).** Tarjeta oscura con wordmark, título, subtítulo, etiquetas, campos con icono (correo, contraseña con ojo para mostrar) y botón principal "ACCEDER Y SINCRONIZAR".
5. **Perfil de piloto (`activity_pilot_config.xml`).** Barra superior con volver. Izquierda: sin sesión, formulario; con sesión, avatar, nombre, licencia, horas totales y turbinas (contadas del historial ya cargado), registrar horas y CERRAR SESIÓN (estilo peligro). Derecha: historial en tabla (fecha, parque, turbina, tipo) con Actualizar.
6. **Ajustes de aeronave (`activity_aircraft_settings.xml`).** Barra superior con volver y modelo. Fila con las 7 tarjetas del **sistema de gestión sanitaria VANT** (icono, nombre, estado). `actualizarColor()` pasa de verde/rojo a los fondos por estado: normal azul claro, aviso ámbar, falla rojo, sin datos gris. Debajo, tres columnas: límites de vuelo (valor + SET), anticolisión y failsafe, punto de origen + actualizaciones (Buscar / Versiones).
7. **Datos de turbina One Stop (`layout_onestop_job.xml`).** Encabezado con icono y cerrar. Izquierda: archivo (base de datos / cargar), turbina, altura de buje (campo resaltado), dron/cámara/lente/FOV. Derecha: tabla de dimensiones (azul claro = del job, gris = genérico; se actualiza el color que pinta `OneStopJobDialog`). Pie: Cancelar / CALCULAR PLAN. Se actualizan los estilos `OneStopJob*` de `themes.xml`.
8. **Diálogos del sistema** (confirmar vuelo, reiniciar inspección, lista de jobs remotos, actualización y descarga en `UpdateManager`, detalle de salud). Toman el tema oscuro y conservan sus textos. Se quitan los emojis de sus títulos y toasts (p. ej. "⚠️").
9. **Colores pintados en Kotlin** en `MainActivity` (filas de turbinas y listas), `AircraftSettingsActivity`, `PilotConfigActivity` y `OneStopJobDialog`: se reemplazan por `ContextCompat.getColor(R.color.nx_*)`.

## Restricciones
- **Las pantallas de vuelo no deben cambiar.** El tema de la aplicación hoy es `Theme.MaterialComponents.Light.NoActionBar` y `FlightActivity` lo hereda. Se fija ese tema de forma explícita en `FlightActivity` (y `OneStopFlightActivity` conserva el suyo) **antes** de cambiar el tema global. El tema Nexus se aplica por activity a las pantallas rediseñadas.
- Pantalla principal en horizontal (RC Pro y tablet). En celular vertical, las columnas deben seguir siendo usables: se acepta desplazamiento.
- minSdk 24: VectorDrawable sin problema.
- Los IDs de vistas que usa el Kotlin se conservan. Si una vista cambia de tipo (p. ej. `RadioButton` con estilo de segmento), se mantiene la clase.
- Sin emojis en layouts, strings ni toasts de las pantallas rediseñadas.

## Verificación
- `./gradlew :app:assembleDebug :app:testDebugUnitTest --offline` en verde (con `JAVA_HOME` = JBR de Android Studio).
- Revisión visual en el mando o emulador de cada pantalla contra los bocetos, y comprobación de que Three Stop y One Stop en vuelo se ven igual que antes.

## Decisiones dejadas al implementador
- Medidas exactas (paddings, alturas) siguiendo los bocetos.
- Si el selector de pala/largo de Three Stop va en la misma columna Inspección o colapsado (la columna lo admite).
- El dibujo exacto del icono de turbina propio, mientras siga el trazo Lucide.
