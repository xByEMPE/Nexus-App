# Incidencia 2026-11-06 — "No se puede iniciar: … waypoint 179 … 0,0 m de la turbina" con medidas correctas

**Estado:** CAUSA CONFIRMADA (2026-11-06) — diagnóstico y aviso implementados; falta prueba en campo. Ver "Resolución" al final.

## Síntoma
En One Stop, tras medir la turbina real (medidas verificadas como correctas), el plan se genera
("Plan listo. Ajuste el brillo (+/-) y presione para iniciar") pero la app no deja iniciar el vuelo:

> No se puede iniciar: si se pierde el enlace cerca del waypoint 179, la vuelta a casa pasaría a 0,0 m de la turbina

Es `R.string.onestop_rth_unsafe` (`strings.xml:64`). El piloto lo describe como "el mensaje de pérdida de enlace" y
"que cruza (la turbina) al momento del regreso a casa": es el mismo aviso.

Captura de pantalla: [`2026-11-06_pantalla_rth.png`](2026-11-06_pantalla_rth.png).
Estado visible en la captura: dron en tierra (ALT 0,0 m, 36,3 m ASL), a 4 m del home, P-GPS sin RTK,
secuencia HUB → 12h → 12R → 4h → 8h, plan 3D mostrado.

## Log
- Dispositivo: DJI RC Plus 2 (`9N9CMCU0011HUX`), Android 11, proceso `mx.com.nexusio.fly` PID 20346, desde 07:49.
- Log único de la app (se sigue añadiendo): [`2026-11-06_logcat.txt`](2026-11-06_logcat.txt).
  Las líneas `===== captura PID … =====` marcan dónde empieza cada tramo de captura.
- **El logcat no contiene el error:** la validación de RTH no escribe nada en `Log`. Solo hay ruido del SDK
  (KeyStore code 7, `NETWORK_ACCESS_FAIL` sin red, `HoveringPositionMode REQUEST_HANDLER_NOT_FOUND`,
  `CANNOT_UPGRADE_IN_CUR_STATE`, `FLY_SAFE DB_NOT_COVER`, `AttitudeDashBoard`), nada relacionado con el plan.

## De dónde sale
`OneStopFlightActivity.programarVueltaACasa()` (`OneStopFlightActivity.kt:325-339`) →
`ReturnHomeSafety.schedule()` (`flightcore/ReturnHomeSafety.kt:156`).

No valida las medidas, sino la trayectoria de vuelta a casa (RTH) que haría el dron si perdiera el enlace en
cualquier punto de la misión (muestreada cada 2 m). Falla si en el peor caso la RTH pasa a menos de
`MIN_CLEARANCE_M = 5.0` m de torre, nacelle, spinner, brida o cualquier pala (`WindTurbine.distance`, con las palas
en cualquier posición). Hay dos perfiles: LEVEL (RTH 20 m: vuelve a la altura actual) y CLIMB (sube por encima del
rotor + 20 m). Si ninguno es seguro en el tramo de un waypoint, se bloquea.

## Lo que dicen los datos (waypoint 179, 0,0 m)
- **0,0 m no es "justo por debajo de 5 m"**: la vuelta a casa atraviesa la turbina. Se descarta la hipótesis del
  margen de 5 m de la ruta (habría salido 4,x m).
- **No es el home dentro de la torre**: en ese caso todos los waypoints darían 0,0 y `schedule` reportaría el
  waypoint 1 (el peor solo se actualiza con `<` estricto). Es un tramo concreto, ya avanzada la misión.
- Por lo tanto, en el tramo del waypoint 179 **los dos perfiles chocan** con la turbina:
  - CLIMB: la subida vertical desde debajo de una pala (4h/8h, última parte de la secuencia) atraviesa la pala; y/o
  - LEVEL: la recta a casa a la altura actual cruza el plano del rotor/la torre (waypoint detrás del rotor), y
    si el home está a <50 m en horizontal (`NEAR_HOME_M`) esta recta se evalúa siempre, incluso con CLIMB.
  Es un hueco del modelo de dos perfiles: hay tramos donde no existe una altura de RTH fija segura.

## Para analizar
- Identificar qué es el waypoint 179: pala, cara (LE/PS/SS/TE), lado del rotor, altura y distancia horizontal al home.
- Añadir log (`Log.w("Nexus_RTH", …)`) en `programarVueltaACasa` con: `worst.waypointIndex`, `minClearanceM`,
  `component` (Tower/Blade-…), `leg` (CLIMB/CRUISE/DESCENT), perfil elegido, holgura de cada perfil en ese tramo,
  `heights`, `homeLocal` y distancia horizontal casa-torre. Con `component` + `leg` se ve directamente cuál choca.
- Mostrar también `component` y `leg` en el mensaje al piloto.
- Reproducir en test unitario (`app/src/test/.../drone/`) con las dimensiones reales y la posición del despegue.
- Comprobar la altitud de casa: `homeLocal` usa altitud 0 frente a `worldOrigin.alt` (altitud del dron en la primera
  medición, `GeoMission.kt:85`, `OneStopFlightActivity.kt:329`); solo es correcto si es relativa al despegue.

## Posibles correcciones (a decidir tras el análisis)
- Tercer comportamiento para los tramos sin RTH fija segura: en vez de RTH, que la acción ante pérdida de enlace sea
  terminar/pausar la misión (hover) o salir primero por la ruta de la misión hasta un punto libre y luego RTH.
- Reordenar o ajustar la ruta en esos tramos (salida hacia delante del rotor antes de cambiar de pala).
- Si el home está a <50 m de la torre, avisar antes de despegar de que hay que despegar más lejos o
  fuera del sector trasero.

## Resolución (2026-11-06)
- El piloto despegó a ≤ 20 m de la torre, frente al cono. Reproducido en `ReturnHomeSafetyTest.homeCloseToTheTowerIsDiagnosedAndAFartherTakeoffIsSuggested`:
  con casa a 20 m fallan las 6 combinaciones (0,0 m, tramo CRUISE, waypoint detrás del rotor a ~33 m de casa); con casa a ≥ 50 m frente al rotor todas pasan.
- Causa: a menos de 50 m de casa el dron vuelve sin subir; desde detrás del rotor esa recta lo cruza con cualquier altura de RTH. El bloqueo es correcto.
- Cambios: log `Nexus_RTH`, mensaje que dice la distancia casa–torre y desde dónde despegar (`ReturnHomeSafety.diagnose`), y aviso temprano tras medir el buje.
- Procedimiento en campo: despegar a ≥ 50 m (lo que diga la app) frente al rotor, del lado del cono, y medir desde ahí.
