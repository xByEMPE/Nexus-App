// Modelo geométrico de la inspección One Stop (misma lógica que MissionPlanner/PathPlanner de Nexus App, simplificada).
// Unidades del modelo: 1 m = K unidades. y: hacia el frente de la turbina; z: arriba. El visor del kit es un espejo (x a la derecha
// mirando desde +y), así que x positivo = derecha del rotor visto de frente y los giros de cara van con el signo cambiado.
export const K = 2.5;
export const L = 100; // pala de 40 m
export const ZH = 130; // altura del buje en el modelo (la torre se dibuja recortada)
export const HUB = [0, 0, ZH];
export const SPIN_TIP_Y = 19.5;
export const D_TIP = 11 * K; // sin RTK: 5,5 + 5,5 m
export const D_ROOT = 13 * K; // sin RTK: 10 + 3 m
export const S_LE0 = 10;
export const S_SS_END = 30;
export const S_TE0 = 55;
export const TIP_END = L + 4 * K; // punta + margen de 4 m
export const HOME = [74, 128, 0];
export const CLOCK = { "4h": 120, "12h": 0, "8h": 240 };

const rad = (d) => (d * Math.PI) / 180;
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const mul = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const len = (a) => Math.hypot(a[0], a[1], a[2]);
export const lerp = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
export { add, sub, mul, len };

export function rotate(v, axis, deg) {
  const c = Math.cos(rad(deg)), s = Math.sin(rad(deg));
  const kv = cross(axis, v), kd = dot(axis, v) * (1 - c);
  return [v[0] * c + kv[0] * s + axis[0] * kd, v[1] * c + kv[1] * s + axis[1] * kd, v[2] * c + kv[2] * s + axis[2] * kd];
}
export const dirOf = (clockDeg) => [Math.sin(rad(clockDeg)), 0, Math.cos(rad(clockDeg))];
const FRONT = [0, 1, 0];
const ROT = { LE: 0, SS: 90, TE: 180, PS: 270 };
const dAt = (s) => D_ROOT + (D_TIP - D_ROOT) * Math.min(1, Math.max(0, (s - S_LE0) / (TIP_END - S_LE0)));
const onBlade = (a, s, rotDeg) => add(add(HUB, mul(a, s)), mul(rotate(FRONT, a, -rotDeg), dAt(s)));

// Una pala completa: LE (raíz→punta), SS (punta→raíz), TE (raíz→punta), PS (punta→raíz) con giros de 15° en los extremos.
export function bladePath(clockDeg) {
  const a = dirOf(clockDeg);
  const out = [];
  const lane = (face, from, to) => {
    const n = Math.max(2, Math.round(Math.abs(to - from) / 14));
    for (let i = 0; i <= n; i++) {
      const s = from + ((to - from) * i) / n;
      out.push({ p: onBlade(a, s, ROT[face]), face, photo: true, s, axis: add(HUB, mul(a, s)) });
    }
  };
  const turn = (s, from, to) => {
    const n = Math.round(Math.abs(to - from) / 15);
    for (let i = 1; i < n; i++) out.push({ p: onBlade(a, s, from + ((to - from) * i) / n), face: "T", photo: false, s, axis: add(HUB, mul(a, s)) });
  };
  lane("LE", S_LE0, TIP_END); turn(TIP_END, 0, 90);
  lane("SS", TIP_END, S_SS_END); turn(S_SS_END, 90, 180);
  lane("TE", S_TE0, TIP_END); turn(TIP_END, 180, 270);
  lane("PS", TIP_END, S_SS_END);
  return out;
}

// Salto entre palas: arco esférico sobre el eje del rotor, siempre al frente del plano del rotor.
export function bladeToBlade(from, to) {
  const p0 = add(from, mul(FRONT, 4 * K));
  const r2 = (p) => (p[0] - HUB[0]) ** 2 + (p[2] - HUB[2]) ** 2;
  const t = (r2(to) + to[1] ** 2 - r2(p0) - p0[1] ** 2) / (2 * (to[1] - p0[1]));
  const c = [0, t, ZH];
  const v1 = sub(p0, c), v2 = sub(to, c);
  const ang = (Math.acos(Math.max(-1, Math.min(1, dot(v1, v2) / (len(v1) * len(v2))))) * 180) / Math.PI;
  const ax = cross(v1, v2); const axn = mul(ax, 1 / len(ax));
  const n = Math.max(2, Math.round(ang / 15));
  const out = [{ p: from, face: "T", photo: false }, { p: p0, face: "T", photo: false }];
  for (let i = 1; i < n; i++) out.push({ p: add(c, rotate(v1, axn, (ang * i) / n)), face: "T", photo: false });
  out.push({ p: to, face: "T", photo: false });
  return out;
}

// ───────── Three Stop (FlightActivity): una pala vertical por parada; 4 tramos TE, PS, LE, SS con subida o bajada automática ─────────
export const TS = 5; // la animación va 5 veces más rápida que el tiempo real en Three Stop
export const D3 = 3.8 * K; // distancia objetivo (válida entre 3,3 y 4,8 m)
export const S3_0 = 26; // el piloto arranca sobre la góndola
export const S3_1 = L + 2;
export const HOME_GROUND = [HOME[0], HOME[1], 2];
const V3 = 0.94; // m/s: 1,0 × √(40/45) a 3,8 m
const BRAKE_M = 7, V_TIP = 0.2, PHOTO_S = 2, V_MAN = 0.6;
const Z_UP = [0, 0, 1];
const onBlade3 = (rot, s) => add(add(HUB, [0, 0, s]), mul(rotate([0, 1, 0], Z_UP, -rot), D3));
const row3 = (rot, s, face, extra) => ({ p: onBlade3(rot, s), face, photo: false, s, axis: add(HUB, [0, 0, s]), ...extra });

function manualArc(from, to, s) {
  const n = Math.max(1, Math.round(Math.abs(to - from) / 15));
  const rows = [];
  let t = 0, prev = null;
  for (let i = 0; i <= n; i++) {
    const r = row3(from + ((to - from) * i) / n, s, "M");
    if (prev) t += len(sub(r.p, prev)) / K / V_MAN;
    rows.push({ ...r, t });
    prev = r.p;
  }
  return rows;
}

function autoLane(rot, up, face) {
  const from = up ? S3_0 : S3_1, to = up ? S3_1 : S3_0;
  const total = Math.abs(to - from) / K;
  const sAt = (x) => from + (to - from) * Math.min(1, x / total);
  const rows = [{ ...row3(rot, from, face, { photo: true, auto: true }), t: 0 }];
  let x = 0, t = 0, next = PHOTO_S;
  while (x < total - 1e-9) {
    const rem = total - x;
    const v = rem <= BRAKE_M ? V_TIP + ((V3 - V_TIP) * rem) / BRAKE_M : V3;
    x = Math.min(total, x + v * 0.05);
    t += 0.05;
    if (t + 1e-9 >= next) { rows.push({ ...row3(rot, sAt(x), face, { photo: true, auto: true }), t: next }); next += PHOTO_S; }
  }
  rows.push({ ...row3(rot, to, face, { auto: true }), t });
  return rows;
}

const chain = (...parts) => {
  const out = [];
  let t0 = 0;
  for (const part of parts) {
    const base = t0;
    part.forEach((r, i) => { if (!(out.length && i === 0)) out.push({ ...r, t: r.t + base }); });
    t0 = out[out.length - 1].t;
  }
  return out;
};

export function buildThreeSteps() {
  const idPos = onBlade3(0, S3_0);
  const brake = (up) => ({ kind: "bracket", tone: "red", a: add(HUB, [0, 3.5, up ? S3_1 - BRAKE_M * K : S3_0]), b: add(HUB, [0, 3.5, up ? S3_1 : S3_0 + BRAKE_M * K]) });
  const section = (face, rot, up, from, title) => {
    const route = chain(manualArc(from.rot, rot, from.s), autoLane(rot, up, face));
    const mid = route.filter((r) => r.auto)[Math.floor(route.filter((r) => r.auto).length / 2)].p;
    return {
      id: "sec-" + face, kind: "section", phase: "Parada 1", title, route, blade: "A", labels: ["A", "B", "C"],
      marks: [brake(up), { kind: "tag", at: add(mid, [0, 0, 0]), text: face + (up ? " sube" : " baja"), dx: -48 }, { kind: "tag", at: add(HUB, [0, 3.5, (up ? S3_1 : S3_0) + (up ? 14 : -14)]), text: "freno: 7 m → 0,2 m/s", dx: 40 }],
    };
  };
  const te = section("TE", 180, true, { rot: 0, s: S3_0 }, "TE");
  const ps = section("PS", 270, false, { rot: 180, s: S3_1 }, "PS");
  const le = section("LE", 0, true, { rot: 270, s: S3_0 }, "LE");
  const ss = section("SS", 90, false, { rot: 0, s: S3_1 }, "SS");
  const lastOf = (st) => st.route[st.route.length - 1];
  const land = [{ p: lastOf(ss).p, face: "T" }, { p: add(HOME_GROUND, [0, 0, 60]), face: "T" }, { p: HOME_GROUND, face: "T" }];
  const lap = (letter, labels) => ({
    kind: "replay", phase: "Parada", title: "Pala " + letter, blade: letter, labels,
    route: [{ p: HOME_GROUND, face: "T" }, { p: add(HOME_GROUND, [0, 0, 60]), face: "T" }, { p: idPos, face: "T" },
      ...[te, ps, le, ss].flatMap((st) => st.route.map((r) => ({ ...r, t: undefined }))), ],
    speed: 95,
    marks: [{ kind: "tag", at: add(HUB, [0, 3.5, S3_1 + 14]), text: "TE · PS · LE · SS" }],
  });
  return [
    { id: "id", kind: "hold", phase: "Parada 1", title: "ID", pos: idPos, labels: ["A", "B", "C"], blade: "A",
      marks: [{ kind: "dim", tone: "mid", a: idPos, b: add(HUB, [0, 3.5, 14]), text: "foto de ID", dx: 26, dy: -6 }, { kind: "tag", at: add(HUB, [0, 3.5, 30]), text: "etiqueta de la pala" }] },
    te, ps, le, ss,
    { id: "land", kind: "exit", phase: "Fin de la pala", title: "Aterrizaje", route: land, speed: 40, labels: ["A", "B", "C"], blade: "A", marks: [] },
    lap("B", ["B", "C", "A"]),
    lap("C", ["C", "A", "B"]),
  ];
}

const aim = (clockDeg, s) =>add(add(HUB, mul(dirOf(clockDeg), s)), [0, 3.5, 0]);
const inFront = (p, m = 5) => add(p, [0, m * K, 0]); // el dron queda al frente del punto apuntado

export function buildSteps() {
  const p4 = bladePath(CLOCK["4h"]), p12 = bladePath(CLOCK["12h"]), p8 = bladePath(CLOCK["8h"]);
  const wedge = (clockA, clockB) => [clockA, clockB].map((c) => ({ kind: "ray", a: add(HUB, [0, 3.5, 0]), b: add(add(HUB, mul(dirOf(c), 112)), [0, 3.5, 0]) }));
  const bracket = (clock, s0, s1) => ({ kind: "bracket", a: aim(clock, s0), b: aim(clock, s1) });
  const last = (arr) => arr[arr.length - 1].p;
  const mid = (list, face) => { const f = list.filter((q) => q.face === face); return f[Math.floor(f.length / 2)].p; };

  const SP = [0, SPIN_TIP_Y + 5 * K, ZH + 6];
  const T12 = inFront(aim(0, 90)), R12 = inFront(aim(0, 16));
  const T4 = inFront(aim(CLOCK["4h"], 90)), T8 = inFront(aim(CLOCK["8h"], 90));

  const blade = (name, path, clock, order) => ({
    id: "blade-" + name, kind: "blade", phase: "Inspección", title: `Pala ${name} · ${order}ª`, route: path, speed: 62,
    marks: [
      { kind: "dim", a: add(HUB, mul(dirOf(clock), 100)), b: path.find((q) => q.face === "LE" && q.s >= 99).p, text: "11 m · RTK 5,5 m", dx: 0, dy: -22 },
      { kind: "dim", a: add(HUB, mul(dirOf(clock), 12)), b: path[0].p, text: "13 m · RTK 10 m", dx: 8, dy: 24 },
      { kind: "tag", at: mid(path, "LE"), text: "LE" }, { kind: "tag", at: mid(path, "SS"), text: "SS" },
      { kind: "tag", at: mid(path, "TE"), text: "TE" }, { kind: "tag", at: mid(path, "PS"), text: "PS" },
    ],
  });

  const exitPts = (() => { const l = last(p8); const p0 = add(l, mul(FRONT, 15 * K)); return [{ p: l }, { p: p0 }, { p: add(p0, mul(FRONT, 1 * K)) }]; })();
  const homeUp = [HOME[0], HOME[1], 40];

  return [
    { id: "hub", kind: "measure", phase: "Calibración", title: "Buje", target: [0, SPIN_TIP_Y, ZH + 6], pos: SP,
      marks: [{ kind: "dim", a: SP, b: [0, SPIN_TIP_Y, ZH + 6], text: "4–6 m", dx: 20, dy: -22 }, { kind: "band", z0: ZH - 20 * K, z1: ZH + 20 * K, y: SP[1], text: "buje ±20 m" }] },
    { id: "tip12", kind: "measure", phase: "Calibración", title: "Punta 12h", target: aim(0, 90), pos: T12,
      marks: [{ kind: "dim", a: T12, b: aim(0, 90), text: "4–6 m", dx: 22, dy: -20 }, bracket(0, 85, 95), ...wedge(0, 15), { kind: "tag", at: add(aim(0, 90), [-40, 0, -8]), text: "85–95 % del radio" }] },
    { id: "root12", kind: "measure", phase: "Calibración", title: "Raíz 12h", target: aim(0, 16), pos: R12,
      marks: [{ kind: "dim", a: R12, b: aim(0, 16), text: "4–6 m", dx: 22, dy: -20 }, bracket(0, 7.5, 25), { kind: "tag", at: add(aim(0, 25), [-38, 0, 6]), text: "3–10 m del buje" }] },
    { id: "tip4", kind: "measure", phase: "Calibración", title: "Punta 4h", target: aim(CLOCK["4h"], 90), pos: T4,
      marks: [{ kind: "dim", a: T4, b: aim(CLOCK["4h"], 90), text: "4–6 m", dx: 22, dy: -20 }, bracket(CLOCK["4h"], 85, 95), ...wedge(105, 135), { kind: "tag", at: add(aim(CLOCK["4h"], 90), [0, 0, -16]), text: "85–95 %" }] },
    { id: "tip8", kind: "measure", phase: "Calibración", title: "Punta 8h", target: aim(CLOCK["8h"], 90), pos: T8,
      marks: [{ kind: "dim", a: T8, b: aim(CLOCK["8h"], 90), text: "4–6 m", dx: 22, dy: -20 }, bracket(CLOCK["8h"], 85, 95), ...wedge(225, 255), { kind: "tag", at: add(aim(CLOCK["8h"], 90), [0, 0, -16]), text: "85–95 %" }] },
    { id: "plan", kind: "plan", phase: "Plan", title: "Verificación y plan", route: [{ p: T8 }, { p: p4[0].p }], dur: 4.2,
      marks: [{ kind: "tag", at: p4[0].p, text: "1ª · 4h" }, { kind: "tag", at: add(p12[0].p, [0, 0, 8]), text: "2ª · 12h" }, { kind: "tag", at: p8[0].p, text: "3ª · 8h" }] },
    blade("4h", p4, CLOCK["4h"], 1),
    { id: "to12", kind: "transfer", phase: "Inspección", title: "Salto 4h → 12h", route: bladeToBlade(last(p4), p12[0].p), speed: 46,
      marks: [{ kind: "tag", at: add(p12[0].p, [0, 8, -14]), text: "arco sobre el eje del rotor" }] },
    blade("12h", p12, CLOCK["12h"], 2),
    { id: "to8", kind: "transfer", phase: "Inspección", title: "Salto 12h → 8h", route: bladeToBlade(last(p12), p8[0].p), speed: 46,
      marks: [{ kind: "tag", at: add(p8[0].p, [0, 8, -14]), text: "al frente del rotor" }] },
    blade("8h", p8, CLOCK["8h"], 3),
    { id: "exit", kind: "exit", phase: "Salida", title: "Salida", route: [...exitPts, { p: homeUp }], speed: 40,
      marks: [{ kind: "dim", a: exitPts[0].p, b: exitPts[1].p, text: "15 m del rotor" }] },
  ];
}
