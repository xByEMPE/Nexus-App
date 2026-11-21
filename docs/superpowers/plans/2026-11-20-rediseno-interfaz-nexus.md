# Rediseño de interfaz Nexus App — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reestilizar todas las pantallas que no son de vuelo con el sistema de diseño Nexus (oscuro, iconos Lucide, sin emojis) y arrancar directo en la pantalla principal del dron.

**Architecture:** XML clásico + un tema `Theme.Nexus.App` aplicado por activity, estilos `Nx.*`, fondos `bg_nx_*` e iconos `ic_nx_*` (VectorDrawable). La lógica Kotlin no cambia salvo donde pinta colores a mano. Dos piezas de lógica pura nuevas y testeadas: `RotorTipLayout` (geometría de las puntas sobre la foto) y `HealthState` (estado de las tarjetas de salud).

**Tech Stack:** Android (AGP 8.7, Kotlin 2.1, minSdk 24), MaterialComponents 1.8, JUnit 4, DJI MSDK 5.18.

**Spec:** `docs/superpowers/specs/2026-11-20-rediseno-interfaz-nexus-design.md`. Bocetos: `.superpowers/brainstorm/674-1795218614/content/layout-principal-v2.html` y `pantallas.html` (ábrelos en un navegador: son la referencia visual de medidas y jerarquía).

## Global Constraints

- Raíz del proyecto: `C:\Users\airsc\Documents\Nexus App`. Paquete `mx.com.nexusio.fly`. Kotlin en `app/src/main/java/mx/com/nexusio/fly/`, recursos en `app/src/main/res/`.
- Comando de verificación: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug :app:testDebugUnitTest --offline`. Debe terminar en BUILD SUCCESSFUL.
- **No tocar** `FlightActivity.kt`, `OneStopFlightActivity.kt`, `activity_flight.xml`, `activity_one_stop_flight.xml` ni nada bajo `drone/onestop/planview/`. La única excepción es el atributo `android:theme` de `FlightActivity` en el manifest (Task 1).
- **No hacer commits**: el repo tiene mucho trabajo sin commitear del usuario. Cada task termina en verificación, no en commit.
- Colores exactos: `nx_background #000000`, `nx_surface #090A0C`, `nx_surface_raised #101114`, `nx_border_subtle #262626`, `nx_border_strong #3B3B3B`, `nx_accent #2F7BFE`, `nx_accent_deep #001233`, `nx_accent_muted #5F72A0`, `nx_telemetry #70A5FF`, `nx_critical #FF5A4E`, `nx_caution #E8B04B`, `nx_fg #FFFFFF`, `nx_fg_muted #9AA3B2`.
- Sin emojis en layouts, strings, toasts ni títulos de diálogo de las pantallas rediseñadas (MainActivity, PilotConfig, AircraftSettings, OneStopJobDialog, UpdateManager, login).
- Conservar todos los `android:id` que referencia el Kotlin y la clase de cada vista con ID.
- Textos de UI en español. Etiquetas de sección en MAYÚSCULAS con `letterSpacing 0.18`.
- Iconos: Lucide (ISC). Trazo 1.6 sobre viewport 24, `strokeLineCap/Join round`, color por `android:tint`/`app:tint`.

## Review Focus

1. **Pantallas de vuelo intactas:** al cambiar el tema global, `FlightActivity` debe seguir con el tema claro, y sus `AlertDialog` también. Lo cubre el paso de verificación de Task 1 (inspeccionar el manifest fusionado).
2. **Toque en las puntas con vistas de cualquier proporción** (columna ancha en tablet, alta en celular vertical): la detección debe seguir la foto escalada, no el centro de la vista. Lo cubren los tests de proporciones de Task 3.
3. **Tarjeta de salud antes de recibir datos** del dron: debe decir "Buscando…" en gris, no "Normal". Lo cubre un test de Task 6.
4. **Texto claro sobre fondo claro:** listas con `simple_list_item_1` y spinners con adaptadores del sistema heredan colores del tema; con tema oscuro deben verse en texto claro. Lo cubren los layouts de fila `item_nx_list` / `item_nx_spinner` de Tasks 4–5 y la revisión visual.
5. **Diálogo "Confirmar vuelo"** construido en código con `Color.BLACK` / `DKGRAY`: sobre el diálogo oscuro sería ilegible. Se cambia en Task 4.

---

### Task 1: Sistema de diseño y protección de las pantallas de vuelo

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (agregar `android:theme="@style/Theme.MaterialComponents.Light.NoActionBar"` a `.drone.FlightActivity`; `android:theme="@style/Theme.Nexus.App"` a `.drone.MainActivity`, `.drone.AircraftSettingsActivity` y `.drone.PilotConfigActivity`, que reemplaza el `Theme.AppCompat.Light.NoActionBar` actual. El tema de `<application>` no cambia).
- Modify: `app/src/main/res/values/colors.xml` (agregar los 13 `nx_*`; dejar los existentes).
- Modify: `app/src/main/res/values/themes.xml` (agregar `Theme.Nexus.App`, `Theme.Nexus.Dialog`, estilos `Nx.*`).
- Create: `app/src/main/res/values/dimens_nx.xml`.
- Create: `app/src/main/res/drawable/bg_nx_card.xml`, `bg_nx_field.xml` (selector: enfocado = borde `nx_accent`), `bg_nx_btn_primary.xml`, `bg_nx_btn_secondary.xml`, `bg_nx_btn_danger.xml` (selectores pressed/disabled), `bg_nx_row_selected.xml` (layer-list con borde izquierdo 2dp `nx_accent` sobre `#1F2F7BFE`), `bg_nx_pill.xml`, `bg_nx_segment_left.xml`/`bg_nx_segment_right.xml` (selector checked = `nx_accent`), `bg_nx_health_normal.xml`, `bg_nx_health_warning.xml`, `bg_nx_health_failure.xml`, `bg_nx_health_nodata.xml` (fondo `nx_surface`, borde 1dp `nx_border_subtle`, franja superior 2dp del color de estado: telemetry/caution/critical/border_strong).
- Create: `tools/lucide_to_vector.py` y `app/src/main/res/drawable/ic_nx_*.xml`.
- Create: `docs/licencias/lucide.txt` (texto de la licencia ISC de Lucide).

**Interfaces:**
- Produces: tema `@style/Theme.Nexus.App` (padre `Theme.MaterialComponents.NoActionBar`; `colorPrimary=nx_accent`, `colorSurface=nx_surface`, `android:windowBackground=nx_background`, `android:textColorPrimary=nx_fg`, `android:textColorSecondary=nx_fg_muted`, `colorControlNormal=nx_fg_muted`, `alertDialogTheme` y `materialAlertDialogTheme` = `@style/Theme.Nexus.Dialog`, status y navigation bar `nx_background`).
- Produces: estilos `Nx.TopBar`, `Nx.TopBar.Title`, `Nx.Wordmark` (11sp, `letterSpacing 0.42`, bold), `Nx.Card`, `Nx.SectionLabel`, `Nx.Body`, `Nx.Body.Muted`, `Nx.Field` (EditText 44dp, `bg_nx_field`, hint `nx_fg_muted`), `Nx.Spinner`, `Nx.Button.Primary`, `Nx.Button.Secondary`, `Nx.Button.Danger` (todos MaterialButton, radio 6dp, alto 44dp, `letterSpacing 0.12`), `Nx.Segment` (RadioButton sin botón, centrado), `Nx.Value.Mono` (`fontFamily monospace`, `nx_telemetry`), `Nx.IconButton` (28dp, `bg_nx_card`).
- Produces: dimens `nx_space_xs 4dp`, `nx_space_s 8dp`, `nx_space_m 10dp`, `nx_space_l 14dp`, `nx_radius 8dp`, `nx_radius_s 6dp`, `nx_topbar_h 48dp`.
- Produces: iconos `ic_nx_back` (arrow-left), `ic_nx_user`, `ic_nx_mail`, `ic_nx_lock`, `ic_nx_eye`, `ic_nx_eye_off`, `ic_nx_settings`, `ic_nx_refresh` (refresh-cw), `ic_nx_reset` (rotate-ccw), `ic_nx_chevron_down`, `ic_nx_check`, `ic_nx_alert` (triangle-alert), `ic_nx_logout` (log-out), `ic_nx_plus`, `ic_nx_shield`, `ic_nx_fan`, `ic_nx_cpu`, `ic_nx_satellite`, `ic_nx_battery`, `ic_nx_gamepad` (gamepad-2), `ic_nx_signal` (wifi), `ic_nx_camera`, `ic_nx_pin` (map-pin), `ic_nx_download`, `ic_nx_history`, `ic_nx_file_up`, `ic_nx_database`, `ic_nx_close` (x), `ic_nx_turbine` (propio: torre vertical, tres palas a 12h/4h/8h y buje circular, mismo trazo).

- [ ] **Step 0: Guardar la línea base de vuelo**: `git diff -- app/src/main/java/mx/com/nexusio/fly/drone/FlightActivity.kt app/src/main/java/mx/com/nexusio/fly/drone/onestop/OneStopFlightActivity.kt app/src/main/res/layout/activity_flight.xml app/src/main/res/layout/activity_one_stop_flight.xml > <scratchpad>/vuelo_base.diff`. Si `git diff` no los muestra porque fueron renombrados, usar `sha256sum` de los cuatro archivos.
- [ ] **Step 1: Fijar el tema de FlightActivity** en el manifest (antes de cualquier otro cambio).
- [ ] **Step 2: Escribir `tools/lucide_to_vector.py`**: `python tools/lucide_to_vector.py <dir_svg> <res/drawable>`. Lee SVG de Lucide (viewBox 24, `stroke-width 2`) y escribe `<vector>` de 24dp con `strokeWidth 1.6`, `strokeColor #FFFFFFFF`, `fillColor @android:color/transparent`, caps/joins round. Convierte `path`, `circle`, `ellipse`, `rect` (con `rx`), `line`, `polyline` y `polygon` a pathData. Los SVG se obtienen con `npm pack lucide-static@latest` en el scratchpad (carpeta propia; ver nota de archivos descargados) y se leen de `package/icons/`.
- [ ] **Step 3: Generar los iconos** de la lista Produces y dibujar `ic_nx_turbine.xml` a mano.
- [ ] **Step 4: Crear colores, dimens, estilos, tema y fondos** según Interfaces.
- [ ] **Step 5: Verificar**: ejecutar el comando global; luego `grep -n "FlightActivity" -A3 app/build/intermediates/merged_manifests/debug/*/AndroidManifest.xml` (o `processDebugMainManifest`) debe mostrar `Theme.MaterialComponents.Light.NoActionBar`. Esperado: BUILD SUCCESSFUL y los 124+ tests existentes en verde.

---

### Task 2: Arranque directo y eliminación de la selección de servicio

**Files:**
- Modify: `app/src/main/java/mx/com/nexusio/fly/ui/SplashActivity.kt` (`goNext()`: siempre `MainActivity::class.java`; quitar la rama de `Build.MANUFACTURER` y el import de `HomeActivity` si queda sin uso).
- Delete: `app/src/main/java/mx/com/nexusio/fly/ui/HomeActivity.kt`, `app/src/main/res/layout/activity_home.xml`.
- Modify: `app/src/main/AndroidManifest.xml` (quitar `<activity android:name=".ui.HomeActivity">`).

- [ ] **Step 1: Comprobar referencias**: `grep -rn "HomeActivity\|activity_home\|card_drone_service\|card_lps_service\|btn_global_login" app/src` debe listar solo los archivos de esta task.
- [ ] **Step 2: Hacer los cambios.** Si `MainActivity` necesitaba algo que solo hacía Home (no debería: ya llama a `SessionManager.freshToken` y `UpdateManager.checkForUpdates`), detenerse y reportarlo.
- [ ] **Step 3: Verificar**: comando global en verde, y el grep del Step 1 sin resultados.

---

### Task 3: Foto de la turbina en la orientación de palas

**Files:**
- Create: `app/src/main/res/drawable-nodpi/turbine_rotor.png` (generado).
- Create: `tools/make_turbine_rotor.py`.
- Create: `app/src/main/java/mx/com/nexusio/fly/drone/onestop/RotorTipLayout.kt`.
- Modify: `app/src/main/java/mx/com/nexusio/fly/drone/onestop/RotorOrientationView.kt` (`onDraw`, `onTouchEvent`, colores).
- Test: `app/src/test/java/mx/com/nexusio/fly/drone/onestop/RotorTipLayoutTest.kt`.

**Interfaces:**
- Produces (Kotlin puro, sin `android.*`):
  ```kotlin
  data class TipPoint(val x: Float, val y: Float)
  data class ImageBox(val left: Float, val top: Float, val width: Float, val height: Float)
  object RotorTipLayout {
      const val IMAGE_W = 1792f
      const val IMAGE_H = 1539f
      /** Puntas normalizadas en orden de assignedLabels: [12h, 4h, 8h]. */
      val TIPS_NORM: List<TipPoint> = listOf(TipPoint(0.505f, 0.10f), TipPoint(0.895f, 0.88f), TipPoint(0.12f, 0.88f))
      fun imageBox(viewW: Float, viewH: Float, padding: Float = 0f): ImageBox   // fit-center
      fun tipCenters(viewW: Float, viewH: Float, padding: Float = 0f): List<TipPoint>
      fun hitTip(x: Float, y: Float, viewW: Float, viewH: Float, radius: Float, padding: Float = 0f): Int? // índice más cercano dentro del radio
  }
  ```
- `RotorOrientationView` conserva su API pública (`onOrientationChangedListener`, `isMappingValid()`, `getBladeMap()`) y el ciclo ?→A→B→C.

- [ ] **Step 1: Escribir `tools/make_turbine_rotor.py`** (Pillow): abre `nexus_splash/turbine.jpg` (1792×2332), recorta `(0, 0, 1792, int(2332*0.66))` = 1792×1539, y pone transparentes los píxeles con `min(r,g,b) > 235` y `max-min < 14`; con `215 < min ≤ 235` aplica alfa lineal `(235-min)/20*255`. Guarda en `app/src/main/res/drawable-nodpi/turbine_rotor.png`. Ejecutarlo y comprobar que el PNG mide 1792×1539.
- [ ] **Step 2: Escribir los tests que fallan**

```kotlin
class RotorTipLayoutTest {
    private val eps = 0.5f
    @Test fun `vista con la misma proporción coloca la punta de 12h en la foto`() {
        val t = RotorTipLayout.tipCenters(1792f, 1539f)
        assertEquals(905.0f, t[0].x, eps); assertEquals(153.9f, t[0].y, eps)
    }
    @Test fun `vista más ancha centra la foto en horizontal`() {
        val b = RotorTipLayout.imageBox(2000f, 1539f)
        assertEquals(104f, b.left, eps); assertEquals(0f, b.top, eps); assertEquals(1792f, b.width, eps)
        assertEquals(1009.0f, RotorTipLayout.tipCenters(2000f, 1539f)[0].x, eps)
    }
    @Test fun `vista más alta centra la foto en vertical`() {
        val b = RotorTipLayout.imageBox(1792f, 2000f)
        assertEquals(230.5f, b.top, eps); assertEquals(1539f, b.height, eps)
    }
    @Test fun `padding reduce la caja`() {
        val b = RotorTipLayout.imageBox(1812f, 1559f, padding = 10f)
        assertEquals(10f, b.left, eps); assertEquals(10f, b.top, eps); assertEquals(1792f, b.width, eps)
    }
    @Test fun `toque sobre cada punta devuelve su índice`() {
        val t = RotorTipLayout.tipCenters(600f, 400f)
        t.forEachIndexed { i, p -> assertEquals(i, RotorTipLayout.hitTip(p.x, p.y, 600f, 400f, 40f)) }
    }
    @Test fun `toque lejos de las puntas devuelve null`() {
        assertNull(RotorTipLayout.hitTip(300f, 300f, 600f, 400f, 40f))
    }
}
```

- [ ] **Step 3: Ejecutar** `./gradlew :app:testDebugUnitTest --offline --tests "*RotorTipLayoutTest"`. Esperado: FAIL (no compila, `RotorTipLayout` no existe).
- [ ] **Step 4: Implementar `RotorTipLayout`** según Interfaces.
- [ ] **Step 5: Ejecutar de nuevo.** Esperado: PASS.
- [ ] **Step 6: Reescribir el dibujo de `RotorOrientationView`**:
  - Fondo: degradado radial `#1A2F7BFE` → transparente, borde 1dp `nx_border_subtle`, esquinas 6dp.
  - Foto `turbine_rotor` dibujada en `imageBox(width, height, padding = 8dp)` con alfa 235.
  - Etiqueta "ORIENTACIÓN DE PALAS" arriba a la izquierda (9.5sp, `nx_accent_muted`) y "Toca cada punta para asignar A · B · C" abajo a la derecha (10sp, `nx_fg_muted`).
  - En cada punta, un círculo de 12dp de radio: asignada = relleno `nx_accent` + halo `#402F7BFE` de 3dp + letra blanca bold 12sp; "?" = relleno `nx_surface_raised`, borde `nx_border_strong`, texto `nx_fg_muted`.
  - `onTouchEvent`: `hitTip(event.x, event.y, width, height, radius = 24dp, padding = 8dp)`.
  - Sin `Paint` creado dentro de `onDraw`.
- [ ] **Step 7: Verificar**: comando global en verde.

---

### Task 4: Pantalla principal del dron (opción A)

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml` (reescritura completa).
- Create: `app/src/main/res/layout/item_nx_list.xml` (TextView 40dp, padding 8dp, `nx_fg`, 13sp), `item_nx_spinner.xml` y `item_nx_spinner_dropdown.xml` (fondo `nx_surface_raised`).
- Modify: `app/src/main/java/mx/com/nexusio/fly/drone/MainActivity.kt` (solo colores, adaptadores y emojis; ver pasos).

**Interfaces:**
- Consumes: estilos, fondos e iconos de Task 1; `RotorOrientationView` de Task 3.
- Se conservan todos los IDs actuales: `rg_inspection_type`, `rb_single_stop`, `rb_three_stop`, `layout_three_stop_params`, `spinner_blade`, `layout_blade_length`, `et_blade_length`, `layout_one_stop_job`, `tv_job_summary`, `btn_job_configure`, `btn_reset_inspection`, `spinner_project`, `tv_project_client`, `tv_project_farm`, `btn_sync_turbines`, `lv_pending_turbines`, `layout_one_stop_rotor_selector`, `rotor_orientation_view`, `tv_drone_status`, `iv_drone`, `spinner_rc_mode`, `btn_aircraft_settings`, `btn_flight`, `tv_app_version`, `tv_config_title`. Las guidelines `guideline_left`/`guideline_right` pueden desaparecer si nadie las referencia en Kotlin (comprobar con grep).
- `btn_sync_turbines` y `btn_reset_inspection` pasan a `MaterialButton` con icono (`ic_nx_refresh` / `ic_nx_reset`); si el Kotlin los declara como `Button`, sigue compilando porque `MaterialButton` hereda de `Button`.
- Nuevo ID `btn_pilot_profile` (píldora del piloto en la barra superior) y `tv_pilot_name` dentro de ella.

- [ ] **Step 1: Reescribir `activity_main.xml`** siguiendo `layout-principal-v2.html`:
  - Raíz `LinearLayout` vertical con fondo `nx_background`.
  - Barra superior `Nx.TopBar` de 48dp: `NEXUS` (`Nx.Wordmark`), separador, "Inspección de aerogeneradores" (`nx_fg_muted`), espacio, `tv_drone_status` como píldora (`bg_nx_pill` + punto de 7dp a la izquierda con `drawableStart`), `btn_pilot_profile` (píldora con `ic_nx_user` + `tv_pilot_name`), `btn_aircraft_settings` como `Nx.IconButton` con `ic_nx_settings`.
  - Debajo, `LinearLayout` horizontal con padding 10dp y tres `Nx.Card` con pesos 1 / 1.25 / 0.85:
    - **Proyecto:** etiqueta, `spinner_project`, cliente · parque en una línea muted, etiqueta "TURBINAS PENDIENTES" con `btn_sync_turbines` (icono, sin texto) a la derecha, `lv_pending_turbines` con borde.
    - **Inspección:** `tv_config_title` = "INSPECCIÓN" (etiqueta), `rg_inspection_type` horizontal con los dos `RadioButton` en `Nx.Segment` + `bg_nx_segment_left/right` ("One Stop" / "Three Stop"), `layout_three_stop_params` (spinner + largo), `layout_one_stop_job` (fila campo: `tv_job_summary` + `btn_job_configure` Secundario), `layout_one_stop_rotor_selector` con `rotor_orientation_view` en `0dp` + `layout_weight=1` para ocupar el resto, `btn_reset_inspection` Secundario.
    - **Aeronave:** etiqueta, `iv_drone` (alto 72dp, `fitCenter`), fila "Modo de palancas" + `spinner_rc_mode`, espacio, `btn_flight` = `Nx.Button.Primary` "CONTINUAR", `tv_app_version` (10sp, muted, centrado).
  - En vertical (celular) el bloque de tres columnas va dentro de `HorizontalScrollView` con ancho mínimo 960dp por columna total. Es suficiente: el spec acepta desplazamiento.
  - Quitar el texto heredado "Toca cada punta…" del XML (ahora lo dibuja la vista).
- [ ] **Step 2: Adaptar `MainActivity.kt`**:
  - `actualizarInterfazDron`: texto "Dron conectado" / "Dron no conectado"; ya no cambia el fondo; el punto de la píldora se tiñe `nx_telemetry` o `nx_caution` (`compoundDrawableTintList`).
  - Filas de turbinas (≈ líneas 555–567 y 795–802): seleccionada → fondo `bg_nx_row_selected` + `nx_fg`; completada → transparente + `nx_telemetry`; normal → transparente + `nx_fg`. Colores por `ContextCompat.getColor(this, R.color.nx_*)`.
  - Diálogo "Confirmar vuelo" (≈ líneas 690–725): `Color.BLACK` → `nx_fg`; `Color.DKGRAY` → `nx_fg_muted`; los spinners del diálogo usan `item_nx_spinner` / `item_nx_spinner_dropdown`.
  - Todos los `ArrayAdapter` de spinners y listas de esta activity usan `item_nx_spinner`, `item_nx_spinner_dropdown` e `item_nx_list` en lugar de `android.R.layout.*`.
  - `btn_pilot_profile` abre `PilotConfigActivity` (el mismo `Intent` que hoy se usa en la línea ≈267); `tv_pilot_name` muestra el nombre guardado en preferencias si hay sesión, o "Iniciar sesión".
  - Quitar emojis de toasts (p. ej. línea 329 "⚠️ Inicia sesión en CONFIGURACIÓN…" → "Inicia sesión en tu perfil de piloto para ver tus proyectos.").
- [ ] **Step 3: Verificar**: comando global en verde. `grep -nP "[\x{2600}-\x{27BF}\x{1F300}-\x{1FAFF}]" app/src/main/res/layout/activity_main.xml app/src/main/java/mx/com/nexusio/fly/drone/MainActivity.kt` (con `LC_ALL=C.UTF-8`) sin resultados. Instalar en el mando o emulador y comparar con el boceto.

---

### Task 5: Inicio de sesión y Perfil de piloto

**Files:**
- Modify: `app/src/main/res/layout/dialog_login.xml`, `app/src/main/res/layout/activity_pilot_config.xml`.
- Modify: `app/src/main/java/mx/com/nexusio/fly/drone/PilotConfigActivity.kt` (colores, adaptador, contador, ojo de contraseña) y el código que abre `dialog_login` (`grep -rn "dialog_login" app/src/main/java`).

**Interfaces:**
- Consumes: Task 1 (estilos, iconos), `item_nx_list` de Task 4.
- Se conservan los IDs `et_login_email`, `et_login_password`, `btn_login_submit`, `btn_back_to_main`, `layout_login_form`, `et_pilot_email`, `et_pilot_password`, `btn_pilot_login`, `layout_pilot_profile`, `tv_logged_pilot_name`, `tv_logged_pilot_license`, `tv_current_flight_hours`, `et_add_flight_hours`, `btn_add_hours`, `btn_pilot_logout`, `btn_sync_history`, `lv_inspection_history`.
- Nuevo ID `tv_inspected_count`.

- [ ] **Step 1: `dialog_login.xml`** según `pantallas.html` §1: tarjeta 330dp `bg_nx_card` con radio 10dp; fila `NEXUS` + cerrar (`ic_nx_close`, nuevo ID `btn_login_close`, que cierra el diálogo); título "Iniciar sesión" 16sp; subtítulo muted "Accede con tu cuenta de piloto para sincronizar proyectos."; etiquetas; campos `Nx.Field` con `drawableStart` `ic_nx_mail` / `ic_nx_lock`; contraseña dentro de un `TextInputLayout` con `endIconMode="password_toggle"` (o un `ImageButton` `ic_nx_eye` que alterne `inputType`); botón primario "ACCEDER Y SINCRONIZAR".
- [ ] **Step 2: `activity_pilot_config.xml`** según §2: barra superior (`btn_back_to_main` como `Nx.IconButton` `ic_nx_back` sin texto, título "Perfil de piloto", `NEXUS` a la derecha) y dos columnas (0.9 / 1.4):
  - Izquierda: `layout_login_form` (mismos campos que el diálogo + `btn_pilot_login` Primario) o `layout_pilot_profile` (avatar 44dp con `ic_nx_user` sobre `nx_accent_deep` y borde `nx_accent`, nombre 14sp bold, licencia muted, dos mini-tarjetas: "HORAS TOTALES" → `tv_current_flight_hours` y "TURBINAS" → `tv_inspected_count`, fila `et_add_flight_hours` + `btn_add_hours` Secundario con `ic_nx_plus`, espacio, `btn_pilot_logout` = `Nx.Button.Danger` con `ic_nx_logout`).
  - Derecha: etiqueta "HISTORIAL DE TURBINAS INSPECCIONADAS" + `btn_sync_history` Secundario con `ic_nx_refresh`, y `lv_inspection_history`.
  - Quitar los tres `CardView` vacíos o decorativos actuales si no tienen IDs usados por Kotlin.
- [ ] **Step 3: `PilotConfigActivity.kt`**:
  - `tv_current_flight_hours` muestra solo el número (`"%.1f".format(h)`) con estilo `Nx.Value.Mono` (20sp). Alerta de horas (línea ≈278): `nx_caution` en lugar de `#E65100`; normal `nx_telemetry` en lugar de negro.
  - El adaptador del historial usa `item_nx_list`.
  - `tv_inspected_count` = tamaño de `historyList` sin el texto de "vacío" (0 si está vacía).
- [ ] **Step 4: Verificar**: comando global en verde y revisión visual de los estados con y sin sesión.

---

### Task 6: Ajustes de aeronave y sistema de gestión sanitaria VANT

**Files:**
- Create: `app/src/main/java/mx/com/nexusio/fly/drone/HealthState.kt`.
- Test: `app/src/test/java/mx/com/nexusio/fly/drone/HealthStateTest.kt`.
- Modify: `app/src/main/res/layout/activity_aircraft_settings.xml`, `app/src/main/java/mx/com/nexusio/fly/drone/AircraftSettingsActivity.kt`.

**Interfaces:**
- Produces (Kotlin puro):
  ```kotlin
  enum class Severity { NOTICE, CAUTION, WARNING, SERIOUS }
  enum class HealthState { NO_DATA, NORMAL, WARNING, FAILURE }
  object HealthStatus {
      fun of(received: Boolean, severities: List<Severity>): HealthState
      fun label(state: HealthState, count: Int): String
  }
  ```
  Reglas: `!received` → `NO_DATA`. Lista vacía → `NORMAL`. Algún `WARNING` o `SERIOUS` → `FAILURE`. Si no, `WARNING`.
  Textos: `NO_DATA` "Buscando…", `NORMAL` "Normal", `WARNING` "1 aviso" / "N avisos", `FAILURE` "1 falla" / "N fallas".
- Mapeo en la activity desde `dji.v5.manager.diagnostic.WarningLevel`: `NOTICE`/`UNKNOWN` → `NOTICE`, `CAUTION` → `CAUTION`, `WARNING` → `WARNING`, `SERIOUS_WARNING` → `SERIOUS`. `NORMAL` se descarta (no cuenta como incidencia).
- Se conservan `card_propulsion … card_camara`, `tv_status_*` y los IDs de límites, failsafe, home point y actualizaciones que usa el Kotlin (comprobar con `grep -o "R.id.[a-z_]*" AircraftSettingsActivity.kt | sort -u` antes de reescribir).

- [ ] **Step 1: Escribir los tests que fallan**

```kotlin
class HealthStateTest {
    @Test fun `sin datos recibidos es NO_DATA aunque la lista esté vacía`() =
        assertEquals(HealthState.NO_DATA, HealthStatus.of(false, emptyList()))
    @Test fun `recibido y vacío es NORMAL`() =
        assertEquals(HealthState.NORMAL, HealthStatus.of(true, emptyList()))
    @Test fun `solo avisos leves es WARNING`() =
        assertEquals(HealthState.WARNING, HealthStatus.of(true, listOf(Severity.NOTICE, Severity.CAUTION)))
    @Test fun `un WARNING o SERIOUS es FAILURE`() {
        assertEquals(HealthState.FAILURE, HealthStatus.of(true, listOf(Severity.NOTICE, Severity.WARNING)))
        assertEquals(HealthState.FAILURE, HealthStatus.of(true, listOf(Severity.SERIOUS)))
    }
    @Test fun `etiquetas en singular y plural`() {
        assertEquals("Buscando…", HealthStatus.label(HealthState.NO_DATA, 0))
        assertEquals("Normal", HealthStatus.label(HealthState.NORMAL, 0))
        assertEquals("1 aviso", HealthStatus.label(HealthState.WARNING, 1))
        assertEquals("3 avisos", HealthStatus.label(HealthState.WARNING, 3))
        assertEquals("1 falla", HealthStatus.label(HealthState.FAILURE, 1))
        assertEquals("2 fallas", HealthStatus.label(HealthState.FAILURE, 2))
    }
}
```

- [ ] **Step 2: Ejecutar** `--tests "*HealthStateTest"`. Esperado: FAIL (no compila).
- [ ] **Step 3: Implementar `HealthState.kt`.**
- [ ] **Step 4: Ejecutar.** Esperado: PASS.
- [ ] **Step 5: Reescribir `activity_aircraft_settings.xml`** según `pantallas.html` §3: barra superior (volver con `ic_nx_back`, cuyo comportamiento es `finish()`; título "Ajustes de aeronave"; modelo del dron muted si ya hay un TextView para ello). Etiqueta con `ic_nx_shield` "SISTEMA DE GESTIÓN SANITARIA VANT". Fila de 7 tarjetas de igual peso (`LinearLayout` vertical cada una, ID `card_*`): icono 30dp en caja `nx_surface_raised` (`ic_nx_fan`, `ic_nx_cpu`, `ic_nx_satellite`, `ic_nx_battery`, `ic_nx_gamepad`, `ic_nx_signal`, `ic_nx_camera`), nombre sin emoji, `tv_status_*`. Debajo, tres columnas: "LÍMITES DE VUELO" (valor `Nx.Field` angosto + "m" + botón SET con contorno `nx_accent`), "ANTICOLISIÓN Y FAILSAFE" (spinners + valores de freno/alerta en mono) y una columna con "PUNTO DE ORIGEN" (dos botones Secundarios "En dron" `ic_nx_pin` / "En control" `ic_nx_gamepad`) y "APLICACIÓN · v{versión}" (Buscar `ic_nx_download` / Versiones `ic_nx_history`). La pantalla va dentro de un `ScrollView`.
- [ ] **Step 6: Adaptar `AircraftSettingsActivity.kt`**:
  - Llevar un `Set<Int>` de categorías con datos recibidos: se marca la primera vez que llega el listener de salud. Antes de eso, todas las tarjetas en `NO_DATA`.
  - `actualizarColor(cardId, tvId, errors)` → calcula `HealthStatus.of(received, errors.map { it.warningLevel().toSeverity() }.filterNotNull())`, fondo de la tarjeta `bg_nx_health_*`, texto `HealthStatus.label(...)` con color telemetry/caution/critical/fg_muted y `drawableStart` `ic_nx_check` o `ic_nx_alert` (ninguno en `NO_DATA`).
  - Botones del historial de versiones (líneas ≈105–112): estilo secundario Nexus (`nx_surface_raised`, texto `nx_fg`, borde `nx_border_strong`) en lugar de `#E0E0E0` / negro.
  - Quitar emojis de textos ("📍 Fijar en Dron" → "En dron", etc.).
- [ ] **Step 7: Verificar**: comando global en verde y revisión visual con el dron apagado (todas "Buscando…") y encendido.

---

### Task 7: Diálogo de datos de turbina One Stop

**Files:**
- Modify: `app/src/main/res/layout/layout_onestop_job.xml`, los estilos `OneStopJob*` de `app/src/main/res/values/themes.xml` y `app/src/main/java/mx/com/nexusio/fly/drone/onestop/job/OneStopJobDialog.kt` (solo colores, adaptadores y la ventana del diálogo).

**Interfaces:**
- Consumes: Task 1, `item_nx_spinner*` de Task 4.
- Se conservan todos los IDs del layout actual (`tv_job_title`, `col_job_left`, `btn_job_remote`, `btn_job_load`, `tv_job_file_name`, `tv_job_status`, `tv_job_turbine_name`, `tv_job_turbine_model`, `tv_job_blade_length`, `et_hub_height`, `tv_job_hub_height_error`, `tv_job_drone`, `spinner_job_drone`, `tv_job_payload_label`, `spinner_job_payload`, `tv_job_lens_label`, `spinner_job_lens`, `tv_job_fov`, `col_job_right`, `table_job_dimensions`, los 12 `tv_job_*` de dimensiones, `barrier_job_columns`, `btn_job_cancel`, `btn_job_confirm`).

- [ ] **Step 1: Reescribir `layout_onestop_job.xml`** según `pantallas.html` §4: encabezado (`ic_nx_turbine` en `nx_telemetry`, `tv_job_title` "Datos de la turbina", "· One Stop" muted). Columna izquierda con tres `Nx.Card`: ARCHIVO (dos botones Secundarios `ic_nx_database` / `ic_nx_file_up`, `tv_job_file_name` en mono + `tv_job_status`); TURBINA + ALTURA DE BUJE lado a lado (`et_hub_height` en `Nx.Field` con borde `nx_accent` y sufijo "m"); DRON ENLAZADO (`tv_job_drone`, los dos spinners lado a lado, lente, `tv_job_fov` muted 10sp). Columna derecha: tarjeta DIMENSIONES con leyenda (punto `nx_telemetry` "job" / punto `nx_fg_muted` "genérico") y la `TableLayout` con divisores `nx_border_subtle`. Pie fijo fuera del scroll: `btn_job_cancel` Secundario y `btn_job_confirm` Primario "CALCULAR PLAN".
- [ ] **Step 2: Actualizar estilos `OneStopJob*`**: Card → `bg_nx_card`; Section → `nx_accent_muted` 9.5sp, `letterSpacing 0.18`; Label → `nx_fg_muted`; Value → `nx_fg`; Cell → `monospace` `nx_fg_muted`.
- [ ] **Step 3: `OneStopJobDialog.kt`**: línea ≈291 `#4CAF50` / `#9E9E9E` → `nx_telemetry` / `nx_fg_muted`; línea ≈428 error `#FFEB3B` → `nx_caution`, ok `#4CAF50` → `nx_telemetry`. Los spinners usan `item_nx_spinner*`. La ventana del diálogo usa fondo `nx_background`. La lista de jobs remotos (`AlertDialog` línea ≈220) toma el tema oscuro solo; si construye su `AlertDialog` con `android.app.AlertDialog` sin tema, pasarle `R.style.Theme_Nexus_Dialog`.
- [ ] **Step 4: Verificar**: comando global en verde, con los tests de job/wtgm existentes en verde, y revisión visual con un `.wtgm` cargado (valores azul claro) y sin archivo (gris).

---

### Task 8: Diálogos del sistema, limpieza de emojis y verificación final

**Files:**
- Modify: `app/src/main/java/mx/com/nexusio/fly/ui/UpdateManager.kt` (títulos, textos, barra de progreso, tema).
- Modify: `app/src/main/res/values/strings.xml` (solo strings usadas por pantallas rediseñadas que contengan emojis).

- [ ] **Step 1: Barrido de emojis**: `LC_ALL=C.UTF-8 grep -rnP "[\x{2600}-\x{27BF}\x{1F300}-\x{1FAFF}\x{2B50}\x{2705}\x{274C}]" app/src/main --include=*.kt --include=*.xml`. Quitar los que estén en archivos rediseñados (MainActivity, PilotConfigActivity, AircraftSettingsActivity, OneStopJobDialog, UpdateManager, `ui/`, los layouts de Tasks 4–7 y sus strings). Los de `FlightActivity`, `OneStopFlightActivity`, sus layouts y strings `onestop_flight_*` se dejan.
- [ ] **Step 2: `UpdateManager`**: los `AlertDialog` usan `Theme.Nexus.Dialog` (pasarlo al `Builder` cuando el contexto no sea una activity con tema Nexus). La barra de progreso de descarga va con `progressTint nx_accent` y fondo `nx_border_subtle`. Botón negativo "Después" y positivo "ACTUALIZAR" (si hoy dicen otra cosa equivalente, conservar el sentido).
- [ ] **Step 3: Verificación final**:
  - Comando global en verde.
  - `git diff --stat -- app/src/main/java/mx/com/nexusio/fly/drone/FlightActivity.kt app/src/main/java/mx/com/nexusio/fly/drone/onestop/OneStopFlightActivity.kt app/src/main/res/layout/activity_flight.xml app/src/main/res/layout/activity_one_stop_flight.xml` sin cambios respecto a antes de empezar (comparar con un `git diff` guardado al inicio de Task 1, porque esos archivos ya tienen cambios sin commitear del usuario).
  - En el mando o emulador: splash → principal directo; cada pantalla contra su boceto; abrir Three Stop y One Stop y confirmar que se ven como antes.
