# Formato del archivo `.job` (One-Stop)

El `.job` lleva los datos del **modelo de turbina** de un parque. Se carga **una sola vez por proyecto** desde el almacenamiento del control (sección *Cargar JOB* de One-Stop); la app lo guarda y lo reutiliza en todas las turbinas del proyecto, igual que el largo de pala.

- Extensión: `.job`
- Contenido: **JSON**, UTF-8 sin BOM
- Unidades: **metros** y **grados**. Número decimal con punto (`1.255`), nunca con coma ni con unidad (`"3 m"` es inválido).
- Ejemplo completo: [`ejemplo_turbina.job`](ejemplo_turbina.job)

## Estructura

```json
{
  "jobVersion": 1,
  "turbine": { "name": "...", "model": "...", "manufacturer": "..." },
  "dimensions": { "...": 0.0 }
}
```

### Raíz

| Campo | Tipo | Obligatorio | Descripción |
|---|---|---|---|
| `jobVersion` | entero | sí | Versión del formato. Hoy solo `1`. Otra versión se rechaza. |
| `turbine` | objeto | sí | Identificación de la turbina. |
| `dimensions` | objeto | sí | Dimensiones geométricas. Puede ir parcial. |

### `turbine`

| Campo | Tipo | Obligatorio | Descripción |
|---|---|---|---|
| `name` | texto | sí | Identificador de la turbina o del modelo (ej. `V112-3.0`). Se muestra en pantalla. |
| `model` | texto | no | Modelo comercial. Solo informativo. |
| `manufacturer` | texto | no | Fabricante. Solo informativo. |

### `dimensions`

Todos los campos son opcionales. Lo que falte (o venga inválido) se queda con el valor genérico y se marca en gris en pantalla.

| Campo | Unidad | Rango aceptado | Genérico | Descripción |
|---|---|---|---|---|
| `rotorDiameter` | m | 50 – 300 | 2 × longitud de pala | Solo se **contrasta** con la longitud de pala ingresada; si difiere más de 1 m se avisa. No la reemplaza. |
| `hubLength` | m | 0.1 – 15 | 2.5 | Largo del buje (spinner). |
| `bladeFlangeRadius` | m | 0.3 – 4 | 1.255 | Radio de la brida de pala. |
| `bladeMaxChordWidth` | m | 1 – 10 | 3.8 | Cuerda máxima de la pala. |
| `hubCenterToBladeFlange` | m | 0 – 10 | 2.0 | Centro del buje a la brida. |
| `hubCenterToMaxChordWidth` | m | 1 – 40 | 12.4 | Centro del buje a la cuerda máxima. |
| `bladeTiltAngle` | ° | 0 – 15 | 4.5 | Inclinación (cono) de la pala. |
| `rotorAxisAngle` | ° | 0 – 15 | 9.0 | Inclinación del eje del rotor. |
| `towerCenterToHubCenter` | m | 0 – 15 | 3.0 | Eje de la torre al centro del buje (voladizo). |
| `towerBottomRadius` | m | 0.5 – 10 | 3.0 | Radio de la torre en la base. |
| `towerTopRadius` | m | 0.5 – 10 | 2.0 | Radio de la torre en la cima. |
| `nacelleThickness` | m | 0.5 – 10 | 2.5 | Grosor de la góndola. |

## Lo que NO va en el `.job`

- **Altura de buje (`towerHeight`)**: la escribe el piloto en la sección *Cargar JOB*. Es obligatoria, no tiene valor por defecto (20 a 300 m y mayor que la longitud de pala) y queda guardada por proyecto. Si viene en el archivo se **ignora**.
- **Longitud de pala**: la da la app al elegir la turbina (`BLADE_LENGTH`).
- **Dron y cámara**: se detectan al enlazar el dron (catálogo `values.json`).

## Reglas de validación al cargar

1. Debe ser JSON válido y tener `jobVersion = 1`, `turbine.name` y `dimensions`; si no, se rechaza todo el archivo y se conservan los valores anteriores.
2. Un campo de `dimensions` con tipo incorrecto, no finito o fuera de rango **no** invalida el archivo: ese campo queda genérico y se lista en el aviso de estado.
3. Campos desconocidos se ignoran (permite ampliar el formato sin romper versiones anteriores).
4. `towerTopRadius` no debe superar `towerBottomRadius`; si ocurre, ambos quedan genéricos.
5. Al cargar bien, los valores reemplazan a los genéricos y se guardan **por proyecto**. Cargar otro `.job` en el mismo proyecto sobrescribe el anterior.

## Catálogo de drones

El dron y la cámara no van en el `.job`. La app los identifica al enlazar el dron (`ProductType` + `CameraType` del SDK) y toma los valores WPML de `values.json` (copia en `app/src/main/assets/drone_catalog.json`; deben mantenerse idénticos). Si el dron no se reconoce, o admite varias cámaras (M400, M350, M300), la sección *Cargar JOB* pide elegirlo.
