import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SKILL = "C:/Users/airsc/AppData/Local/Claude-Data/claude_pzs8sxrjxfjjc/local-agent-mode-sessions/skills-plugin/cd412a49-d771-4224-a6df-00bd3dbcc4f0/c30ec358-5c89-4920-8ec6-6466c98409f8/skills/anatomy";
const load = (file) => import(pathToFileURL(join(SKILL, file)).href);
const k = await load("kit/iso-kit.mjs");
const G = await load("kit/lathe.mjs");
const { kitScript } = await load("scripts/inline-kit.mjs");
const M = await import(pathToFileURL(join(HERE, "model.mjs")).href);

const theme = process.argv.includes("--light") ? "light" : "dark";
const WIDTH = 640;
const HEIGHT = 600;
const { K, L, ZH, HUB, HOME, CLOCK } = M;

const GROUND = { x: -135, y: -70, w: 270, d: 240, r: 10 };
const GROUND_H = 10;
const NACELLE = { x: -10, y: -62, w: 20, d: 64, r: 5 };
const TOWER_BREAK = [46, 74];

const P = k.fitProjection(
  [...k.boxCorners(GROUND, -GROUND_H - 4, 0), [0, 0, ZH + L + 24], [-120, 0, ZH - 90], [120, 0, ZH - 90], [0, 130, ZH + L], [0, 90, ZH - 90]],
  WIDTH,
  HEIGHT,
  { pad: 12, azimuth: 58, elevation: 21 },
);
const at = (p) => k.iso(p, P);
const S = (paths, style) => k.solidSvg(paths, style);
const Ln = (d, style) => k.lineSvg(d, style);
const D = (pts, style) => k.dotsSvg(pts, style);

// ───────────── Escena fija ─────────────
function ground() {
  const out = [`<path class="iso-halo" d="${k.haloOf(GROUND, -GROUND_H - 4, GROUND_H, P)}"/>`];
  const feet = k.corners(GROUND, 18).sort((a, b) => k.depthOf([a[0], a[1], 0], P) - k.depthOf([b[0], b[1], 0], P));
  for (const [x, y] of feet) {
    out.push(S(k.cylinder(x, y, 7, -GROUND_H - 4, 1.8, P, 24), { tone: "lo" }));
    out.push(S(k.cylinder(x, y, 5.4, -GROUND_H - 4 + 1.8, 2.2, P, 24), { tone: "mid" }));
  }
  out.push(S(k.slabOf(GROUND, -GROUND_H, GROUND_H, P, 8, 1.2), { tone: "lo" }));
  out.push(Ln(k.planOutline(k.insetPlan(GROUND, 5), 0, P), { tone: "faint" }));
  const screws = k.corners(GROUND, 11);
  out.push(Ln(screws.map(([x, y]) => k.ring(x, y, 2.4, 0, P, 16)).join(""), { tone: "faint" }));
  out.push(D(screws.map(([x, y]) => at([x, y, 0])), { size: 0.5 }));
  // regla de 10 m (25 u) a lo largo del borde frontal-derecho
  const strip = { x: -128, y: GROUND.y + GROUND.d - 16, w: 256, d: 7, r: 1 };
  out.push(S(k.slabOf(strip, 0, 0.6, P, 3), { tone: "lo", crease: "none" }));
  const ticks = k.topTicks(strip.x + 4, strip.x + strip.w - 4, K, 10, strip.y + 0.8, 0.6, [2, 3.6], P);
  out.push(Ln(ticks.minor, { tone: "lo" }), Ln(ticks.major, { tone: "mid" }));
  out.push(D([at([strip.x + 2, strip.y + 3.5, 0.6]), at([strip.x + strip.w - 2, strip.y + 3.5, 0.6])], { size: 0.45 }));
  // placa de despegue
  const [hx, hy] = HOME;
  out.push(S(k.cylinder(hx, hy, 15, 0, 1.4, P, 40, 0.4), { tone: "mid" }));
  out.push(Ln(k.ring(hx, hy, 11, 1.4, P, 40), { tone: "lo" }));
  out.push(Ln(k.lineOnTop([hx - 7, hy], [hx + 7, hy], 1.4, P) + k.lineOnTop([hx, hy - 7], [hx, hy + 7], 1.4, P), { tone: "hi" }));
  out.push(D([at([hx, hy, 1.4])], { size: 0.5, tone: "hi" }));
  // flecha de frente de la turbina
  out.push(Ln(k.lineOnTop([0, 14], [0, 120], 0.05, P) + k.lineOnTop([0, 120], [-6, 108], 0.05, P) + k.lineOnTop([0, 120], [6, 108], 0.05, P), { tone: "lo", dotted: true }));
  out.push(k.faceTextSvg(k.topMatrix([10, 118], 0.1, P, "x"), "FRENTE", { size: 6, tone: "lo" }));
  return out.join("");
}

function tower() {
  const out = [];
  // Torre continua y cónica (de 9 u en la base a 7,4 u arriba) con bridas de unión de tramos, puerta y cimentación.
  const F = G.frameOf([0, 0, 0], [0, 0, 1], [1, 0, 0], [0, 1, 0]);
  const R0 = 9, R1 = 7.4, S0 = 5, S1 = ZH - 12;
  const rAt = (s) => R0 + ((R1 - R0) * (s - S0)) / (S1 - S0);
  const slope = (R1 - R0) / (S1 - S0);
  out.push(S(G.disc(0, S0, 12.5, F, P), { tone: "lo" }));
  out.push(S(G.solidOf([[S0, R0], [S1, R1]], F, P, { smooth: { slope: () => slope, radius: rAt } }), { tone: "mid" }));
  const joints = [34, 64, 94];
  const bands = [];
  for (const s of joints) {
    out.push(S(G.disc(s - 1.1, s + 1.1, rAt(s) + 0.9, F, P), { tone: "mid" }));
    bands.push(Ln(G.arcOf(F, s - 1.1, rAt(s) + 0.9, P), { tone: "faint" }));
  }
  out.push(bands.join(""));
  out.push(S(G.disc(S1, S1 + 2.4, 8.4, F, P), { tone: "mid" }));
  // puerta en el frente de la torre (hacia +y, lado del espectador)
  const door = { x: -2.6, y: rAt(14) - 0.5, w: 5.2, d: 1.4, r: 0.6 };
  out.push(S(k.slabOf({ x: door.x, y: door.y, w: door.w, d: door.d, r: 0.6 }, 6, 13, P, 2), { tone: "lo", crease: "none" }));
  out.push(D([at([2, door.y + door.d, 15])], { size: 0.45 }));
  return out.join("");
}

function nacelle() {
  const out = [];
  out.push(S(k.slabOf(NACELLE, ZH - 11, 22, P, 5, 0.9), { tone: "hi" }));
  out.push(Ln(k.planOutline(k.insetPlan(NACELLE, 2.6), ZH + 11, P, 4), { tone: "faint" }));
  const vents = [];
  for (let y = -54; y <= -22; y += 6) vents.push(k.lineOnTop([-5, y], [5, y], ZH + 11, P));
  out.push(Ln(vents.join(""), { tone: "lo" }));
  out.push(D([at([-6.5, -58, ZH + 11]), at([6.5, -58, ZH + 11]), at([-6.5, -4, ZH + 11]), at([6.5, -4, ZH + 11])], { size: 0.45 }));
  return out.join("");
}

const HUB_F = G.frameOf([0, 2, ZH], [0, 1, 0], [1, 0, 0], [0, 0, 1]);
function spinner() {
  const out = [];
  const profile = [[0, 9], [4, 9], [10, 7.6, 1], [15, 4.6, 1], [17.5, 0]];
  out.push(S(G.solidOf(profile, HUB_F, P, { smooth: true }), { tone: "hi" }));
  out.push(Ln(G.arcOf(HUB_F, 4, 9, P), { tone: "faint" }));
  const bolts = G.dotsOf(HUB_F, 4, 8.3, 14, P);
  out.push(D(bolts, { size: 0.42, tone: "mid" }));
  return out.join("");
}

function bladeSolid(name) {
  const a = M.dirOf(CLOCK[name]);
  // polígono convexo de la pala en el plano (x, z) del rotor; u = a, v = perpendicular en el plano
  const perp = [a[2], 0, -a[0]];
  const F = G.frameOf([HUB[0], -3, HUB[2]], [0, 1, 0], a, perp);
  const poly = [[6, -3.4], [22, -6.2], [L, -0.9], [L, 0.9], [22, 4.4], [6, 3.4]];
  const out = [];
  out.push(S(G.prismOf(F, poly, 0, 6, P), { tone: "hi", crease: "faint" }));
  // costilla central sobre la cara frontal, termina en puntos
  const seg = (s) => at(M.add(M.add(HUB, M.mul(a, s)), [0, 3.05, 0]));
  out.push(Ln(k.segment(M.add(M.add(HUB, M.mul(a, 16)), [0, 3.05, 0]), M.add(M.add(HUB, M.mul(a, L - 8)), [0, 3.05, 0]), P), { tone: "faint" }));
  out.push(D([seg(16), seg(L - 8)], { size: 0.42, tone: "mid" }));
  // brida de raíz (cojinete de paso)
  const FR = G.frameAlong(M.add(HUB, M.mul(a, 7)), a);
  out.push(S(G.disc(0, 3.4, 5.6, FR, P), { tone: "mid" }));
  return out.join("");
}

// ───────────── Página ─────────────
const modelSource = readFileSync(join(HERE, "model.mjs"), "utf8").replace(/^export /gm, "").replace(/^export \{[^}]*\};?$/gm, "");

const staticBack = [ground(), tower(), nacelle()].join("");
const staticMid = [spinner(), bladeSolid("4h"), bladeSolid("8h"), bladeSolid("12h")].join("");
const labels = ["12h", "4h", "8h"]
  .map((n) => {
    const a = M.dirOf(CLOCK[n]);
    const [x, y] = at(M.add(M.add(HUB, M.mul(a, L + (n === "12h" ? 34 : 30))), [0, 3.5, 0]));
    return `<text class="op-clock" id="lbl-${n}" x="${x.toFixed(1)}" y="${y.toFixed(1)}" text-anchor="middle">${n}</text>`;
  })
  .join("");

const svg = k.figureSvg({
  width: WIDTH,
  height: HEIGHT,
  label:
    "Maqueta de una turbina eólica con tres palas a 12, 4 y 8 horas sobre una base con regla y placa de despegue. Un dron modelo recorre la inspección One Stop (cinco mediciones de calibración y el vuelo de cada pala) o la inspección Three Stop (una pala por parada, cuatro tramos de subida o bajada a 3,8 metros de la pala).",
  body: [staticBack, `<g id="gBack"></g>`, staticMid, labels, `<g id="gFront"></g>`],
});

const ONE_TEXT = [
  ["Calibración · 1 de 5", "Buje (spinner)", "Despega y colócate al frente de la turbina, a la altura del buje (la app acepta ±20 m) y a 4–6 m del spinner. Apunta el láser (o la visión) al centro del spinner y mide. Esta posición fija el rumbo del rotor y la altura de referencia.", "Dron a 4–6 m del spinner · altura = buje ±20 m"],
  ["Calibración · 2 de 5", "Punta de la pala 12h", "Con el rotor detenido en forma de «Y» (una pala arriba, a las 12h), sube hasta la punta de esa pala y quédate a 4–6 m. El punto apuntado debe caer entre el 85 % y el 95 % del radio y a 75–90° de elevación respecto al buje; fuera de ahí la app lo rechaza (demasiado cerca de la raíz o de la punta).", "4–6 m · punto apuntado a 85–95 % del radio · elevación 75–90°"],
  ["Calibración · 3 de 5", "Raíz de la pala 12h", "Baja por el frente de la misma pala hasta la raíz y apunta a su eje: entre 3 y 10 m del buje y a no más de 4 m del eje de la pala. Con la punta y la raíz de la pala 12h la app calcula la longitud real y el eje de las palas.", "4–6 m · punto a 3–10 m del buje y ≤4 m del eje"],
  ["Calibración · 4 de 5", "Punta de la pala 4h", "Pala de abajo a la derecha (visto desde el frente). Vuela hasta su punta, a 4–6 m, y apunta al 85–95 % del radio. La elevación del punto debe quedar entre −45° y −15° respecto al buje, hacia el lado derecho (±45° del rumbo lateral).", "4–6 m · 85–95 % del radio · elevación −45° a −15°"],
  ["Calibración · 5 de 5", "Punta de la pala 8h", "Igual que la anterior, pero en la pala de abajo a la izquierda. Con las tres puntas y la raíz la app obtiene el plano del rotor y verifica que las medidas sean coherentes entre sí.", "4–6 m · 85–95 % del radio · elevación −45° a −15°"],
  ["Plan", "Verificación y plan de vuelo", "Con las cinco medidas la app deduce el eje del rotor, la raíz y las tres palas, y genera los waypoints: primero la pala de las 4h, luego la de las 12h y al final la de las 8h. Antes de despegar la misión se ajusta el brillo con fotos de prueba, se comprueba que la vuelta a casa no cruce el rotor y se sube el plan al dron.", "Orden: 4h → 12h → 8h"],
  ["Inspección · pala 4h", "Pala 4h: LE → SS → TE → PS", "El dron recorre cuatro carriles alrededor de la pala: borde de ataque (LE) de la raíz a la punta, gira 90° en la punta, cara de succión (SS) de la punta a la raíz, gira en la raíz, borde de salida (TE) hacia la punta y cara de presión (PS) de vuelta. Se mantiene a 13 m de la pala en la raíz y 11 m en la punta (5,5 m con RTK en la punta), a 1 m/s y con una foto en cada parada.", "Raíz 13 m · punta 11 m · 1 m/s · RTK: 10 m / 5,5 m"],
  ["Cambio de pala", "Salto 4h → 12h", "Entre palas el dron no cruza el rotor: sube en un arco sobre el eje del rotor, siempre por delante del plano de las palas y a la distancia del buje que ya traía.", "Siempre al frente del plano del rotor"],
  ["Inspección · pala 12h", "Pala 12h: LE → SS → TE → PS", "Mismos cuatro carriles. Por detrás, el borde de salida (TE) empieza pasado el nacelle: el dron nunca pasa por detrás de la góndola ni de la raíz de la pala. En los cruces la distancia mínima a la pala es 10,5 m (5 m con RTK).", "Cruces: mínimo 10,5 m · RTK: 5 m"],
  ["Cambio de pala", "Salto 12h → 8h", "Segundo arco por delante del rotor hasta el inicio de la pala de las 8h.", "Siempre al frente del plano del rotor"],
  ["Inspección · pala 8h", "Pala 8h: LE → SS → TE → PS", "Última pala. En las dos caras de abajo (la PS en la pala de las 8h) el dron pasa por debajo de la pala y se echa algo más atrás para que el sensor de visión siga viendo la superficie.", "Raíz 13 m · punta 11 m · fotos en cada parada"],
  ["Salida", "Salida del rotor y vuelta a casa", "Al terminar se aleja 15 m del plano del rotor, hace una pausa y vuelve a casa. La pantalla de vídeo se mantiene hasta que aterrices; si pierdes el enlace o fuerzas la vuelta, la altura de RTH se ajusta para no cruzar el rotor.", "15 m fuera del rotor · luego RTH"],
];
const ONE_CHIPS = [["Buje", "cal"], ["Punta 12h", "cal"], ["Raíz 12h", "cal"], ["Punta 4h", "cal"], ["Punta 8h", "cal"], ["Plan", "plan"], ["Pala 4h", "ins"], ["4h→12h", "ins"], ["Pala 12h", "ins"], ["12h→8h", "ins"], ["Pala 8h", "ins"], ["Salida", "out"]];

const THREE_TEXT = [
  ["Parada 1 · pala A", "Despegue y foto de ID", "Con el rotor detenido y la pala A hacia arriba (12h), despega y colócate frente a la raíz de la pala. Pulsa el botón principal para tomar la foto de ID, que asocia las fotos de la carpeta con esa pala. En Three Stop no hay mediciones de calibración: el piloto coloca el dron a ojo y la app lo comprueba con el láser.", "Foto de ID · sin calibración"],
  ["Parada 1 · tramo 1 de 4", "TE: borde de salida (sube)", "Rodea la pala a mano hasta el borde de salida (TE) y acércate hasta que el medidor se ponga en verde: entre 3,3 y 4,8 m, con 3,8 m como objetivo. Con los motores encendidos y el interruptor en N, ajusta el brillo y pulsa para iniciar. El dron sube solo y mantiene 3,8 m con el láser (o la visión); tú corriges a los lados con el stick. Toma una foto cada 2 s y, en los últimos 7 m, frena hasta 0,2 m/s.", "3,8 m (3,3–4,8 m) · foto cada 2 s · freno de 7 m a 0,2 m/s"],
  ["Parada 1 · tramo 2 de 4", "PS: cara de presión (baja)", "Al terminar el tramo, la app pregunta si es correcto. Con «Sí, siguiente» toma la foto END. Pasas a mano a la cara de presión (PS), en la punta, ajustas el brillo y el dron baja solo hasta la raíz con la misma distancia de 3,8 m.", "Baja · 3,8 m · foto cada 2 s"],
  ["Parada 1 · tramo 3 de 4", "LE: borde de ataque (sube)", "Tercer tramo: desde la raíz, por el borde de ataque (LE), el dron vuelve a subir. Si el tramo no te convence, «Repetir» te devuelve a la fase de colocación.", "Sube · 3,8 m · foto cada 2 s"],
  ["Parada 1 · tramo 4 de 4", "SS: cara de succión (baja)", "Último tramo, la cara de succión (SS), de la punta a la raíz. Al confirmarlo, la app pide aterrizar.", "Baja · 3,8 m · foto cada 2 s"],
  ["Fin de la pala", "Aterrizaje y cambio de pala", "Aterriza la aeronave. Cuando los motores se apagan, la pala A queda guardada como completada (en verde en la lista) y vuelves a la pantalla principal. Pide que giren el rotor hasta que la siguiente pala quede arriba y elige esa pala.", "Una pala por parada · 3 paradas"],
  ["Parada 2 · pala B", "Misma secuencia en la pala B", "Con la pala B arriba se repite todo: foto de ID y los cuatro tramos TE → PS → LE → SS, con las mismas distancias y velocidades. La animación lo muestra acelerado.", "TE · PS · LE · SS"],
  ["Parada 3 · pala C", "Misma secuencia en la pala C", "Última parada, con la pala C arriba. Al terminar, las tres palas quedan en verde y la inspección está completa: 3 palas, 12 tramos.", "3 palas · 12 tramos"],
];
const THREE_CHIPS = [["ID", "cal"], ["TE sube", "ins"], ["PS baja", "ins"], ["LE sube", "ins"], ["SS baja", "ins"], ["Aterrizaje", "out"], ["Parada 2", "plan"], ["Parada 3", "plan"]];

const MODES = {
  one: {
    steps: M.buildSteps(), text: ONE_TEXT, chips: ONE_CHIPS, title: "One Stop", hint: "Flechas ← → para cambiar de paso",
    caption: "Maqueta de la lógica del planificador de One Stop: una pala de 40 m y el dron mucho más grande que el real. Las distancias van a escala del modelo (sin RTK: raíz 13 m, punta 11 m, cruces 10,5 m). El recorrido aproxima los waypoints reales, que dependen de la turbina y de la cámara.",
  },
  three: {
    steps: M.buildThreeSteps(), text: THREE_TEXT, chips: THREE_CHIPS, title: "Three Stop", hint: "Flechas ← → para cambiar de paso",
    caption: "Maqueta de la lógica de Three Stop de la app: una pala de 40 m, dron fuera de escala y animación 5 veces más rápida que el tiempo real. La subida y la bajada, los 3,8 m, la foto cada 2 s y el freno de 7 m salen de los valores de la app; la posición exacta del piloto en cada tramo es orientativa.",
  },
};

const live = `
${modelSource}
const P = ${JSON.stringify(P)};
const MODES = ${JSON.stringify(MODES)};
const stage = document.querySelector(".iso-stage");
const svg = stage.querySelector("svg");
const gBack = svg.querySelector("#gBack"), gFront = svg.querySelector("#gFront");
const readout = document.querySelector("[data-readout]");
const panel = document.querySelector(".op-panel");
const chipsEl = document.querySelector(".op-chips");
const titleEl = document.querySelector('[data-corner="title"]');
const captionEl = document.querySelector(".iso-caption");
const tabs = [...document.querySelectorAll(".op-tab")];
const still = matchMedia("(prefers-reduced-motion: reduce)");
const pt = (p) => iso(p, P);
const d2 = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
const ease = (t) => t * t * (3 - 2 * t);
const PATH_SPEED = 62;
const S = solidSvg, Ln = lineSvg, D = dotsSvg;
const CLOCKS = { "12h": svg.querySelector("#lbl-12h"), "4h": svg.querySelector("#lbl-4h"), "8h": svg.querySelector("#lbl-8h") };

function prepare(steps) {
  for (const s of steps) {
    if (s.route) {
      let acc = 0; s.cum = [0];
      for (let i = 1; i < s.route.length; i++) { acc += d2(s.route[i].p, s.route[i - 1].p); s.cum.push(acc); }
      s.len = acc;
      const lastT = s.route[s.route.length - 1].t;
      if (lastT !== undefined) { s.timed = true; s.dur = lastT / TS; }
      else s.dur = s.dur || Math.max(3.2, acc / (s.speed || PATH_SPEED));
      s.fly = s.dur; s.hold = 0;
    } else { s.fly = 3.4; s.hold = 2.6; s.dur = s.fly + s.hold; }
  }
  let prev = [HOME[0], HOME[1], 6];
  for (const s of steps) { s.from = prev; s.end = s.route ? s.route[s.route.length - 1].p : s.pos; prev = s.end; }
}
Object.values(MODES).forEach((m) => prepare(m.steps));

const yawFacing = (a, b, keep) => { const dx = b[0] - a[0], dy = b[1] - a[1]; return Math.hypot(dx, dy) < 0.5 ? keep : Math.atan2(dy, dx); };

function routeAt(s, u) {
  const dist = s.len * u;
  let i = 1; while (i < s.cum.length - 1 && s.cum[i] < dist) i++;
  const span = s.cum[i] - s.cum[i - 1] || 1, t = Math.min(1, Math.max(0, (dist - s.cum[i - 1]) / span));
  return { p: lerp(s.route[i - 1].p, s.route[i].p, t), idx: i - 1, row: s.route[i] };
}

let modeKey = "one", MODE = MODES.one, STEPS = MODE.steps, chips = [];
let cur = 0, clock = 0, playing = !still.matches, visible = false, frame = 0, last = 0;
let yaw = -Math.PI / 2, shownYaw = yaw, marksAlpha = 0;
const done = [];

function setLabels(s) {
  const L3 = s.labels, names = ["12h", "4h", "8h"];
  names.forEach((n, i) => { CLOCKS[n].textContent = L3 ? "Pala " + L3[i] + " · " + n : n; });
}

function enter(i, instant) {
  cur = i; clock = 0; marksAlpha = 0;
  chips.forEach((c, j) => { c.dataset.on = j === i ? "1" : "0"; c.dataset.done = j < i ? "1" : "0"; });
  const [phase, title, body, nums] = MODE.text[i];
  panel.querySelector(".op-phase").textContent = phase;
  panel.querySelector(".op-title").textContent = title;
  panel.querySelector(".op-body").textContent = body;
  panel.querySelector(".op-nums").textContent = nums;
  readout.textContent = (i + 1) + "/" + STEPS.length + " · " + title;
  setLabels(STEPS[i]);
  if (instant) clock = STEPS[i].dur;
}

function position(s) {
  if (s.route) {
    if (s.timed) {
      const t = Math.min(clock, s.fly) * TS;
      let i = 1; while (i < s.route.length - 1 && s.route[i].t < t) i++;
      const a = s.route[i - 1], b = s.route[i], f = Math.min(1, Math.max(0, (t - a.t) / ((b.t - a.t) || 1)));
      return { p: lerp(a.p, b.p, f), idx: i - 1, row: b, face: b.face };
    }
    const u = Math.min(1, clock / s.fly);
    const r = routeAt(s, s.kind === "blade" || s.kind === "transfer" || s.kind === "replay" ? u : ease(u));
    return { p: r.p, idx: r.idx, row: r.row, face: r.row.face };
  }
  const u = ease(Math.min(1, clock / s.fly));
  const p = lerp(s.from, s.pos, u); p[2] += Math.sin(u * Math.PI) * 10 * (1 - u);
  return { p, idx: -1, row: null, face: null };
}

function droneSvg(p, yawRad, time) {
  const c = Math.cos(yawRad), s = Math.sin(yawRad);
  const Z = 1.45;
  const rot = (x, y) => [p[0] + Z * (x * c - y * s), p[1] + Z * (x * s + y * c)];
  const out = [];
  const tips = [[6.4, 5.2], [6.4, -5.2], [-6.4, 5.2], [-6.4, -5.2]].map(([x, y]) => rot(x, y));
  out.push(Ln(tips.map((t) => segment([p[0], p[1], p[2] + 1.6], [t[0], t[1], p[2] + 1.6], P)).join(""), { tone: "mid" }));
  const body = [[-4.2, -2.4], [3.6, -2.4], [4.6, 0], [3.6, 2.4], [-4.2, 2.4]].map(([x, y]) => rot(x, y));
  out.push(S(extrude(body, p[2], 3.4, P, { bevel: 0.4 }), { tone: "hi", lit: true }));
  const nose = rot(4.6, 0);
  out.push(S(cylinder(nose[0], nose[1], 1.6, p[2] - 1, 2.4, P, 10), { tone: "mid" }));
  tips.forEach((t, i) => {
    out.push(S(cylinder(t[0], t[1], 1.5, p[2] + 1, 2.2, P, 10), { tone: "mid" }));
    out.push(Ln(ring(t[0], t[1], 5, p[2] + 3.4, P, 16), { tone: "hi" }));
    const a = time * 38 + i * 1.7;
    out.push(Ln(lineOnTop([t[0] - 5 * Math.cos(a), t[1] - 5 * Math.sin(a)], [t[0] + 5 * Math.cos(a), t[1] + 5 * Math.sin(a)], p[2] + 3.4, P), { tone: "lit" }));
  });
  out.push(D([pt([p[0], p[1], p[2] + 3.4])], { size: 0.5, tone: "hi" }));
  return out.join("");
}

const chip = (x, y, text, tone) => {
  const w = text.length * 3.9 + 10;
  return '<g class="op-chip-g" data-tone="' + tone + '"><rect x="' + (x - w / 2).toFixed(1) + '" y="' + (y - 7.5).toFixed(1) + '" width="' + w.toFixed(1) + '" height="15" rx="2"/><text x="' + x.toFixed(1) + '" y="' + (y + 3.6).toFixed(1) + '" text-anchor="middle">' + text + '</text></g>';
};

function marksSvg(s, alpha) {
  const out = [];
  for (const m of s.marks || []) {
    if (m.kind === "dim") {
      const a = pt(m.a), b = pt(m.b), tone = m.tone || "red";
      out.push(Ln(segment(m.a, m.b, P), { tone, dotted: true }));
      out.push(D([a, b], { size: 0.8, tone: "hi" }));
      out.push(chip((a[0] + b[0]) / 2 + (m.dx === undefined ? 26 : m.dx), (a[1] + b[1]) / 2 + (m.dy === undefined ? -8 : m.dy), m.text, tone));
    } else if (m.kind === "bracket") {
      out.push('<path class="op-zone" data-tone="' + (m.tone || "green") + '" d="' + segment(m.a, m.b, P) + '"/>');
    } else if (m.kind === "ray") {
      out.push(Ln(segment(m.a, m.b, P), { tone: "green", dotted: true }));
    } else if (m.kind === "tag") {
      const a = pt(m.at); out.push(chip(a[0] + (m.dx || 0), a[1], m.text, "mid"));
    } else if (m.kind === "band") {
      const x0 = -46, x1 = 46;
      for (const z of [m.z0, m.z1]) out.push(Ln(segment([x0, m.y, z], [x1, m.y, z], P), { tone: "green", dotted: true }));
      out.push(Ln(segment([x1, m.y, m.z0], [x1, m.y, m.z1], P), { tone: "green" }));
      const q = pt([x1, m.y, m.z1]); out.push(chip(q[0] + 4, q[1] - 12, m.text, "green"));
    }
  }
  return '<g opacity="' + alpha.toFixed(2) + '">' + out.join("") + '</g>';
}

function pathSvgs(routes, upto) {
  const back = [], front = [];
  routes.forEach((r) => {
    for (let i = 1; i < r.length; i++) {
      if (upto !== undefined && i > upto) break;
      const a = r[i - 1], b = r[i];
      const seg = "M" + pt(a).map((v) => v.toFixed(1)).join(" ") + "L" + pt(b).map((v) => v.toFixed(1)).join(" ");
      ((a[1] + b[1]) / 2 < 0 ? back : front).push(seg);
    }
  });
  return [back.join(""), front.join("")];
}

const TRACKED = { blade: 1, section: 1, replay: 1 };
const FACES = { LE: "borde de ataque", SS: "cara de succión", TE: "borde de salida", PS: "cara de presión" };

function render(time) {
  const s = STEPS[cur];
  const where = position(s);
  const p = where.p;
  let want = yaw;
  if (s.kind === "measure" || s.kind === "hold") want = -Math.PI / 2;
  else if (s.route) {
    const nxt = s.route[Math.min(where.idx + 1, s.route.length - 1)];
    want = yawFacing(p, nxt.axis || HUB, yaw);
  }
  let dy = want - shownYaw; while (dy > Math.PI) dy -= 2 * Math.PI; while (dy < -Math.PI) dy += 2 * Math.PI;
  shownYaw += dy * 0.12; yaw = want;
  marksAlpha = Math.min(1, marksAlpha + 0.04);

  const behind = p[1] < -1;
  const doneBack = done.map((d) => d[0]).join(""), doneFront = done.map((d) => d[1]).join("");
  let curBack = "", curFront = "";
  if (s.route) {
    const rr = pathSvgs([s.route.map((q) => q.p)], where.idx + 1);
    curBack = rr[0]; curFront = rr[1];
    const tail = "M" + pt(s.route[where.idx].p).map((v) => v.toFixed(1)).join(" ") + "L" + pt(p).map((v) => v.toFixed(1)).join(" ");
    if ((s.route[where.idx].p[1] + p[1]) / 2 < 0) curBack += tail; else curFront += tail;
  }
  const ghost = pathSvgs(s.kind === "plan" ? MODE.steps.filter((x) => x.kind === "blade").map((x) => x.route.map((q) => q.p)) : (s.route ? [s.route.map((q) => q.p)] : []));
  const gBackSvg = '<path class="op-ghost" d="' + ghost[0] + '"/><path class="op-trail op-done" d="' + doneBack + '"/><path class="op-trail" d="' + curBack + '"/>';
  const gFrontGhost = '<path class="op-ghost" d="' + ghost[1] + '"/><path class="op-trail op-done" d="' + doneFront + '"/><path class="op-trail" d="' + curFront + '"/>';

  let photos = "", extra = "";
  if (TRACKED[s.kind]) {
    s.route.forEach((q, i) => {
      if (!q.photo) return;
      const q2 = pt(q.p), got = i <= where.idx;
      photos += '<circle class="op-photo" data-got="' + (got ? 1 : 0) + '" cx="' + q2[0].toFixed(1) + '" cy="' + q2[1].toFixed(1) + '" r="' + (got ? 1.5 : 1) + '"/>';
    });
    const q = s.route[Math.max(0, where.idx)];
    if (q && q.axis) {
      photos += Ln(segment(p, q.axis, P), { tone: "mid", dotted: true });
      if (s.kind === "section") {
        const a = pt(p), b = pt(q.axis), auto = !!(where.row && where.row.auto);
        extra = chip((a[0] + b[0]) / 2 + 70, (a[1] + b[1]) / 2 - 4, auto ? "3,8 m · automático" : "manual · 3,3–4,8 m", auto ? "green" : "red");
      }
    }
  }
  const drone = droneSvg(p, shownYaw, time);
  const shadow = '<path class="iso-halo" d="' + haloOf({ x: p[0] - 6, y: p[1] - 4.5, w: 12, d: 9, r: 4 }, 0, 0.1, P) + '"/>' + Ln(segment([p[0], p[1], 0], [p[0], p[1], Math.max(0, p[2] - 1)], P), { tone: "faint", dotted: true });
  const sight = (s.kind === "measure" && s.target && clock > s.fly - 0.2) ? '<g class="op-laser">' + Ln(segment(p, s.target, P), { tone: "red" }) + '<circle cx="' + pt(s.target)[0].toFixed(1) + '" cy="' + pt(s.target)[1].toFixed(1) + '" r="' + (3 + 1.6 * Math.sin(time * 9)).toFixed(1) + '" class="op-hit"/></g>' : "";

  gBack.innerHTML = gBackSvg + shadow + (behind ? drone : "");
  gFront.innerHTML = gFrontGhost + photos + marksSvg(s, marksAlpha) + extra + sight + (behind ? "" : drone);
  if (TRACKED[s.kind] && where.face) {
    const name = FACES[where.face];
    const head = s.blade ? "Pala " + s.blade : s.title.split(" · ")[0];
    if (s.kind === "blade") readout.textContent = head + " · " + (name ? name : "giro alrededor de la pala");
    else if (s.kind === "section") readout.textContent = head + " · " + (name || "") + (where.row && where.row.auto ? " · automático" : " · manual");
    else readout.textContent = head + " · " + (name || "en camino");
  }
}

function finish() {
  const s = STEPS[cur];
  if (s.route) done.push(pathSvgs([s.route.map((q) => q.p)]));
}

function tick(now) {
  const dt = last ? Math.min((now - last) / 1000, 1 / 20) : 1 / 60;
  last = now;
  if (playing) {
    clock += dt;
    if (clock >= STEPS[cur].dur) {
      finish();
      if (cur < STEPS.length - 1) enter(cur + 1);
      else { done.length = 0; enter(0); }
    }
  }
  render(now / 1000);
  frame = visible ? requestAnimationFrame(tick) : 0;
}
const run = () => { if (!frame && visible) { last = 0; frame = requestAnimationFrame(tick); } };
function jump(i) {
  done.length = 0;
  STEPS.slice(0, i).forEach((s) => { if (s.route) done.push(pathSvgs([s.route.map((q) => q.p)])); });
  enter(i);
  render(performance.now() / 1000);
  run();
}
function buildChips() {
  chipsEl.innerHTML = MODE.chips.map((c, i) => '<button type="button" class="op-chip" data-on="0" data-done="0" data-g="' + c[1] + '"><b>' + (i + 1) + '</b>' + c[0] + '</button>').join("");
  chips = [...chipsEl.children];
  chips.forEach((c, i) => c.addEventListener("click", () => { playing = false; setPlay(); jump(i); }));
}
function setMode(key) {
  modeKey = key; MODE = MODES[key]; STEPS = MODE.steps;
  tabs.forEach((t) => { const on = t.dataset.mode === key; t.setAttribute("aria-selected", String(on)); t.dataset.on = on ? "1" : "0"; });
  titleEl.textContent = "Inspección " + MODE.title;
  captionEl.textContent = MODE.caption;
  document.querySelector(".iso-plate-corner[data-corner=hint]").textContent = MODE.hint;
  done.length = 0; yaw = -Math.PI / 2; shownYaw = yaw;
  buildChips(); enter(0); render(performance.now() / 1000); run();
}
const playBtn = document.querySelector(".op-play");
function setPlay() { playBtn.textContent = playing ? "Pausa" : "Reproducir"; playBtn.setAttribute("aria-pressed", String(playing)); }
playBtn.addEventListener("click", () => { playing = !playing; setPlay(); run(); });
document.querySelector(".op-prev").addEventListener("click", () => { playing = false; setPlay(); jump(Math.max(0, cur - 1)); });
document.querySelector(".op-next").addEventListener("click", () => { playing = false; setPlay(); jump(Math.min(STEPS.length - 1, cur + 1)); });
tabs.forEach((t) => t.addEventListener("click", () => setMode(t.dataset.mode)));
stage.addEventListener("keydown", (e) => {
  if (e.key === "ArrowRight") { playing = false; setPlay(); jump(Math.min(STEPS.length - 1, cur + 1)); }
  else if (e.key === "ArrowLeft") { playing = false; setPlay(); jump(Math.max(0, cur - 1)); }
  else if (e.key === " ") { e.preventDefault(); playing = !playing; setPlay(); run(); }
  else return;
  e.preventDefault();
});
new IntersectionObserver((entries) => { visible = entries[entries.length - 1].isIntersecting; if (visible) run(); }, { rootMargin: "120px 0px" }).observe(stage);
setMode("one");
setPlay();
window.__op = { jump, mode: setMode, get step() { return cur; }, set clock(v) { clock = v; }, render, pause() { playing = false; setPlay(); } };
`;

const compare = `
<section class="op-compare" aria-label="Diferencias entre One Stop y Three Stop">
  <h2>One Stop frente a Three Stop</h2>
  <div class="op-table-wrap"><table>
    <thead><tr><th></th><th>One Stop</th><th>Three Stop</th></tr></thead>
    <tbody>
      <tr><th>Paradas del rotor</th><td>1, con las tres palas en «Y» (12h, 4h y 8h)</td><td>3: una pala arriba en cada parada, con el rotor girado 120° entre paradas</td></tr>
      <tr><th>Calibración</th><td>5 mediciones con láser o visión: buje, punta 12h, raíz 12h, punta 4h y punta 8h</td><td>Sin mediciones: foto de ID y colocación manual a 3,8 m</td></tr>
      <tr><th>Quién vuela</th><td>Misión automática con waypoints; el piloto puede frenarla con el botón o tirando el stick</td><td>Subida y bajada automáticas; el piloto coloca el dron, corrige a los lados y confirma cada tramo</td></tr>
      <tr><th>Distancia a la pala</th><td>13 m en la raíz y 11 m en la punta (con RTK, 10 m y 5,5 m); cruces a 10,5 m (5 m con RTK)</td><td>3,8 m, válida entre 3,3 y 4,8 m</td></tr>
      <tr><th>Velocidad</th><td>1 m/s, con parada y foto en cada waypoint</td><td>Entre 0,3 y 1,5 m/s según la pala y la distancia; en los últimos 7 m frena hasta 0,2 m/s</td></tr>
      <tr><th>Fotos</th><td>Una por waypoint</td><td>Una cada 2 s (hasta 255 por tramo), más una foto START y otra END</td></tr>
      <tr><th>Orden</th><td>Palas 4h → 12h → 8h; en cada una LE, SS, TE y PS</td><td>Pala a pala; en cada una TE, PS, LE y SS (sube, baja, sube, baja)</td></tr>
      <tr><th>Seguridad</th><td>Freno por proximidad, altura de RTH que no cruza el rotor, paro de emergencia; si se pierde el enlace, vuelve a casa</td><td>Interruptor en N, tirón atrás del stick que aborta, filtro de saltos del medidor; si se pierde el enlace, se queda en el sitio (HOVER)</td></tr>
      <tr><th>Al terminar</th><td>La pantalla de vídeo sigue hasta que aterrices</td><td>Aterrizas, la pala se guarda como completada y eliges la siguiente</td></tr>
    </tbody>
  </table></div>
</section>`;

const panelHtml = `
<section class="op" aria-live="polite">
  <div class="op-tabs" role="tablist" aria-label="Tipo de inspección">
    <button type="button" role="tab" class="op-tab" data-mode="one" data-on="1" aria-selected="true">One Stop</button>
    <button type="button" role="tab" class="op-tab" data-mode="three" data-on="0" aria-selected="false">Three Stop</button>
  </div>
  <div class="op-controls">
    <button type="button" class="op-btn op-prev" aria-label="Paso anterior">‹</button>
    <button type="button" class="op-btn op-play" aria-pressed="true">Pausa</button>
    <button type="button" class="op-btn op-next" aria-label="Paso siguiente">›</button>
    <span class="op-legend"><i data-k="cal"></i>Calibración / colocación<i data-k="plan"></i>Plan / repetir<i data-k="ins"></i>Inspección</span>
  </div>
  <div class="op-chips"></div>
  <div class="op-panel">
    <div class="op-phase"></div>
    <h2 class="op-title"></h2>
    <p class="op-body"></p>
    <p class="op-nums"></p>
  </div>
</section>`;

const css = `
.op{margin:20px 0 0;display:grid;gap:14px}
.op-tabs{display:flex;gap:0;border:1px solid var(--anatomy-mid);width:max-content;max-width:100%}
.op-tab{font:600 13px var(--anatomy-mono);letter-spacing:.06em;text-transform:uppercase;color:var(--anatomy-text);background:transparent;border:0;padding:10px 20px;cursor:pointer}
.op-tab[data-on="1"]{background:var(--anatomy-strong);color:var(--anatomy-page)}
.op-controls{display:flex;flex-wrap:wrap;align-items:center;gap:8px}
.op-btn{font:600 12px var(--anatomy-mono);letter-spacing:.06em;text-transform:uppercase;color:var(--anatomy-strong);background:var(--anatomy-card);border:1px solid var(--anatomy-mid);padding:8px 14px;cursor:pointer}
.op-btn:hover,.op-chip:hover,.op-tab:hover{border-color:var(--anatomy-hi)}
.op-btn:focus-visible,.op-chip:focus-visible,.op-tab:focus-visible,.iso-stage:focus-visible{outline:2px solid var(--anatomy-blue);outline-offset:2px}
.op-legend{margin-left:auto;display:flex;align-items:center;gap:6px;color:var(--anatomy-muted);font-size:12px;flex-wrap:wrap}
.op-legend i{width:9px;height:9px;display:inline-block;margin-left:8px;background:var(--c,var(--anatomy-mid))}
.op-legend i[data-k="cal"],.op-chip[data-g="cal"] b{--c:var(--anatomy-red)}
.op-legend i[data-k="plan"],.op-chip[data-g="plan"] b{--c:var(--anatomy-green)}
.op-legend i[data-k="ins"],.op-chip[data-g="ins"] b,.op-chip[data-g="out"] b{--c:var(--anatomy-blue)}
.op-chips{display:grid;grid-template-columns:repeat(6,1fr);gap:6px}
.op-chip{display:flex;align-items:center;gap:7px;text-align:left;font:12px/1.2 ui-sans-serif,system-ui,sans-serif;color:var(--anatomy-text);background:transparent;border:1px solid var(--anatomy-lo);padding:8px 8px;cursor:pointer;min-height:38px}
.op-chip b{font:700 10px var(--anatomy-mono);color:var(--anatomy-page);background:var(--c,var(--anatomy-mid));min-width:18px;height:18px;display:grid;place-items:center}
.op-chip[data-on="1"]{border-color:var(--anatomy-strong);color:var(--anatomy-strong);background:var(--anatomy-card)}
.op-chip[data-done="1"]{color:var(--anatomy-muted)}
.op-panel{border:1px solid var(--anatomy-lo);background:var(--anatomy-card);padding:18px 20px;display:grid;gap:8px}
.op-phase{font:11px var(--anatomy-mono);letter-spacing:.1em;text-transform:uppercase;color:var(--anatomy-muted)}
.op-title{margin:0;font-size:19px;line-height:1.25;color:var(--anatomy-strong);font-weight:650;text-wrap:balance}
.op-body{margin:0;color:var(--anatomy-text);font-size:14.5px;line-height:1.6;max-width:62ch}
.op-nums{margin:4px 0 0;font:12.5px var(--anatomy-mono);color:var(--anatomy-strong);border-top:1px solid var(--anatomy-lo);padding-top:10px}
.op-clock{font:600 11px var(--anatomy-mono);fill:var(--anatomy-text);letter-spacing:.06em}
.op-chip-g rect{fill:var(--anatomy-card);stroke:var(--anatomy-mid);stroke-width:.6;vector-effect:non-scaling-stroke}
.op-chip-g text{font:600 8.4px var(--anatomy-mono);fill:var(--anatomy-strong);letter-spacing:.02em}
.op-chip-g[data-tone="red"] rect{stroke:var(--anatomy-red)}
.op-chip-g[data-tone="green"] rect{stroke:var(--anatomy-green)}
.op-ghost,.op-trail,.op-zone{fill:none;stroke-linecap:round;stroke-linejoin:round;vector-effect:non-scaling-stroke}
.op-ghost{stroke:var(--anatomy-mid);stroke-width:1;stroke-dasharray:.01 4}
.op-trail{stroke:var(--anatomy-blue);stroke-width:1.5}
.op-trail.op-done{stroke:var(--anatomy-mid);stroke-width:.8;opacity:.38}
.op-zone{stroke:var(--anatomy-green);stroke-width:4.5;opacity:.95}
.op-zone[data-tone="red"]{stroke:var(--anatomy-red);opacity:.8}
.op-photo{fill:var(--anatomy-mid);stroke:none}
.op-photo[data-got="1"]{fill:var(--anatomy-lit)}
.op-hit{fill:none;stroke:var(--anatomy-red);stroke-width:1.2;vector-effect:non-scaling-stroke}
.iso-stage{outline:none}
.op-compare{margin-top:32px;display:grid;gap:12px}
.op-compare h2{margin:0;font-size:19px;color:var(--anatomy-strong);font-weight:650}
.op-table-wrap{overflow-x:auto;border:1px solid var(--anatomy-lo)}
.op-compare table{border-collapse:collapse;width:100%;min-width:640px;font-size:13.5px;line-height:1.5}
.op-compare th,.op-compare td{text-align:left;vertical-align:top;padding:10px 14px;border-bottom:1px solid var(--anatomy-lo)}
.op-compare thead th{font:600 11px var(--anatomy-mono);letter-spacing:.1em;text-transform:uppercase;color:var(--anatomy-muted);background:var(--anatomy-card)}
.op-compare tbody th{width:150px;color:var(--anatomy-strong);font-weight:600}
.op-compare td{color:var(--anatomy-text)}
.op-compare tr:last-child th,.op-compare tr:last-child td{border-bottom:0}
@media (max-width:600px){.op-chips{grid-template-columns:repeat(3,1fr)}.op-legend{margin-left:0;width:100%}}
@media (prefers-reduced-motion:reduce){.op-laser{animation:none}}
`;

const body =
  k.plateHtml({
    fig: "Fig 1",
    title: "Inspección One Stop",
    hint: "Flechas ← → para cambiar de paso",
    readout: "1/12 · Buje",
    keys: [
      { mark: "lit", label: "Dron (fuera de escala)" },
      { mark: "red", label: "Láser, distancia de medición y zona de frenado" },
      { mark: "green", label: "Zona donde debe caer el punto apuntado" },
      { mark: "dotted", label: "Ruta planificada · fotos como puntos" },
    ],
    caption: MODES.one.caption,
    body: `<div class="iso-stage" tabindex="0" role="group" aria-label="Animación de la inspección. Flechas izquierda y derecha cambian de paso, espacio pausa.">${svg}</div>`,
  }) + panelHtml + compare;

const TITLE = "Inspección One Stop y Three Stop";
if (process.argv.includes("--artifact")) {
  const themeSync = `
const root = document.documentElement;
const dark = matchMedia("(prefers-color-scheme: dark)");
const apply = () => { const t = root.dataset.theme || (dark.matches ? "dark" : "light"); document.body.classList.add("iso"); document.body.dataset.theme = t; };
apply(); dark.addEventListener("change", apply); new MutationObserver(apply).observe(root, { attributes: true, attributeFilter: ["data-theme"] });
`;
  const page = `<title>${TITLE}</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>${k.ISO_CSS}
:root{--bg:#f2f2ef;color-scheme:light}
@media (prefers-color-scheme: dark){:root:not([data-theme="light"]){--bg:#0b0b0b;color-scheme:dark}}
:root[data-theme="dark"]{--bg:#0b0b0b;color-scheme:dark}
*{box-sizing:border-box}
body{background:var(--bg);color:var(--anatomy-text,#57575a);font:14px/1.6 ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif;padding-block:24px 56px;padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px))}
.iso-page{width:min(760px,100%);margin:0 auto}
${css}</style>
<main class="iso-page">${body}</main>
<script type="module">${(themeSync + kitScript() + live).replace(/<\/(script)/gi, "<\\/$1")}</script>`;
  writeFileSync(join(HERE, "inspeccion-universal-artifact.html"), page);
  console.log("wrote inspeccion-universal-artifact.html");
}
writeFileSync(
  join(HERE, theme === "light" ? "inspeccion-universal-light.html" : "inspeccion-universal.html"),
  k.pageHtml({ title: TITLE, theme, body: `<style>${css}</style>` + body, script: kitScript() + live, width: 760 }),
);
console.log("wrote", theme === "light" ? "inspeccion-universal-light.html" : "inspeccion-universal.html");
