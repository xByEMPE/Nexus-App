const root = document.documentElement;
const dark = matchMedia("(prefers-color-scheme: dark)");
const apply = () => { const t = root.dataset.theme || (dark.matches ? "dark" : "light"); document.body.classList.add("iso"); document.body.dataset.theme = t; };
apply(); dark.addEventListener("change", apply); new MutationObserver(apply).observe(root, { attributes: true, attributeFilter: ["data-theme"] });
const AZIMUTH = 45;
const ELEVATION = 30;
const SHADES = 4;
const RADIANS = Math.PI / 180;
const UNIT = Math.sqrt(1.6);
function cameraOf({ scale, azimuth = AZIMUTH, elevation = ELEVATION }) {
    const a = azimuth * RADIANS;
    const e = elevation * RADIANS;
    return { sinA: Math.sin(a), cosA: Math.cos(a), sinE: Math.sin(e), cosE: Math.cos(e), k: scale * UNIT };
}
function iso([x, y, z], projection) {
    const { origin } = projection;
    const { sinA, cosA, sinE, cosE, k } = cameraOf(projection);
    return [origin[0] + (x * sinA - y * cosA) * k, origin[1] + ((x * cosA + y * sinA) * sinE - z * cosE) * k];
}
function towardViewer(projection) {
    const { sinA, cosA } = cameraOf(projection);
    return [cosA, sinA];
}
function depthOf([x, y, z], projection) {
    const { sinA, cosA, sinE, cosE } = cameraOf(projection);
    return (x * cosA + y * sinA) * cosE + z * sinE;
}
const tenth = (value) => {
    const text = (Math.round(value * 10) / 10).toFixed(1).replace(/\.0$/, "");
    return text === "-0" ? "0" : text;
};
function pathOf(points, closed = false) {
    if (!points.length)
        return "";
    return points.map(([x, y], at) => `${at ? "L" : "M"}${tenth(x)} ${tenth(y)}`).join("") + (closed ? "Z" : "");
}
const joinPaths = (paths) => paths.filter(Boolean).join("");
function roundedPlan({ x, y, w, d, r }, steps = 8) {
    const radius = Math.max(0, Math.min(r, w / 2, d / 2));
    const corners = [
        [x + w - radius, y + radius, -90],
        [x + w - radius, y + d - radius, 0],
        [x + radius, y + d - radius, 90],
        [x + radius, y + radius, 180],
    ];
    const out = [];
    for (const [cx, cy, start] of corners) {
        const count = radius > 0 ? steps : 0;
        for (let step = 0; step <= count; step++) {
            const angle = (start + (count ? (90 * step) / count : 45)) * RADIANS;
            out.push([cx + radius * Math.cos(angle), cy + radius * Math.sin(angle)]);
        }
    }
    return out;
}
function signedArea(points) {
    let area = 0;
    for (let index = 0; index < points.length; index++) {
        const [ax, ay] = points[index];
        const [bx, by] = points[(index + 1) % points.length];
        area += ax * by - bx * ay;
    }
    return area / 2;
}
const oriented = (points) => (signedArea(points) < 0 ? [...points].reverse() : points);
function convexHull(points) {
    const sorted = [...points].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    if (sorted.length < 3)
        return sorted;
    const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    const lower = [];
    for (const point of sorted) {
        while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], point) <= 0)
            lower.pop();
        lower.push(point);
    }
    const upper = [];
    for (let index = sorted.length - 1; index >= 0; index--) {
        const point = sorted[index];
        while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], point) <= 0)
            upper.pop();
        upper.push(point);
    }
    return [...lower.slice(0, -1), ...upper.slice(0, -1)];
}
function runsOf(flags, want) {
    const count = flags.length;
    const runs = [];
    const start = flags.findIndex((flag, index) => flag !== flags[(index + count - 1) % count]);
    if (start < 0)
        return flags[0] === want ? [Array.from({ length: count + 1 }, (_, index) => index % count)] : [];
    let run = null;
    for (let step = 0; step < count; step++) {
        const index = (start + step) % count;
        if (flags[index] === want) {
            if (!run)
                run = [index];
            run.push((index + 1) % count);
        }
        else if (run) {
            runs.push(run);
            run = null;
        }
    }
    if (run)
        runs.push(run);
    return runs;
}
function extrude(ring, z, height, projection, options = {}) {
    const convex = options.convex ?? true;
    const count = ring.length;
    const counter = signedArea(ring) > 0;
    const [vx, vy] = towardViewer(projection);
    const { sinA, cosA } = cameraOf(projection);
    const normals = ring.map(([ax, ay], index) => {
        const [bx, by] = ring[(index + 1) % count];
        const dx = bx - ax;
        const dy = by - ay;
        const length = Math.hypot(dx, dy) || 1;
        return (counter ? [dy / length, -dx / length] : [-dy / length, dx / length]);
    });
    const facing = normals.map(([nx, ny]) => nx * vx + ny * vy > 1e-9);
    const top = ring.map(([x, y]) => iso([x, y, z + height], projection));
    const bottom = ring.map(([x, y]) => iso([x, y, z], projection));
    const outline = [];
    const crease = [];
    for (const run of runsOf(facing, false))
        outline.push(pathOf(run.map((index) => top[index])));
    for (const run of runsOf(facing, true)) {
        crease.push(pathOf(run.map((index) => top[index])));
        if (height > 0)
            outline.push(pathOf(run.map((index) => bottom[index])));
    }
    if (height > 0) {
        for (let index = 0; index < count; index++) {
            if (facing[(index + count - 1) % count] !== facing[index])
                outline.push(pathOf([top[index], bottom[index]]));
        }
    }
    let fill;
    if (convex) {
        fill = pathOf(convexHull([...top, ...bottom]), true);
    }
    else {
        const parts = [pathOf(oriented(top), true), pathOf(oriented(bottom), true)];
        for (let index = 0; index < count; index++) {
            if (!facing[index])
                continue;
            const next = (index + 1) % count;
            parts.push(pathOf(oriented([top[index], top[next], bottom[next], bottom[index]]), true));
        }
        fill = parts.join("");
    }
    const buckets = Array.from({ length: SHADES }, () => []);
    if (height > 0) {
        const [lx, ly] = [-sinA, cosA];
        for (let index = 0; index < count; index++) {
            if (!facing[index])
                continue;
            const [nx, ny] = normals[index];
            const lit = (nx * lx + ny * ly + 1) / 2;
            const shade = Math.min(SHADES - 1, Math.max(0, Math.floor(lit * SHADES)));
            const next = (index + 1) % count;
            buckets[shade].push(pathOf(oriented([top[index], top[next], bottom[next], bottom[index]]), true));
        }
    }
    let bevel;
    if (options.bevel && options.bevel > 0) {
        const by = options.bevel;
        const inset = ring.map(([x, y], index) => {
            const [ax, ay] = normals[(index + count - 1) % count];
            const [bx, by2] = normals[index];
            const mx = ax + bx;
            const my = ay + by2;
            const length = Math.hypot(mx, my) || 1;
            return [x - (mx / length) * by, y - (my / length) * by];
        });
        const insetTop = inset.map(([x, y]) => iso([x, y, z + height], projection));
        bevel = runsOf(facing, true)
            .map((run) => pathOf([top[run[0]], ...run.slice(1, -1).map((index) => insetTop[index]), top[run[run.length - 1]]]))
            .join("");
    }
    return {
        fill,
        outline: outline.join(""),
        crease: crease.join(""),
        top: pathOf(oriented(top), true),
        shades: buckets.map((paths) => paths.join("")),
        bevel,
    };
}
const ringOf = (plan, steps = 8) => roundedPlan(plan, steps);
function slabOf(plan, z, height, projection, steps = 8, bevel = 0) {
    return extrude(ringOf(plan, steps), z, height, projection, { convex: true, bevel });
}
function circleRing(cx, cy, radius, steps = 40) {
    return Array.from({ length: steps }, (_, step) => {
        const angle = (step / steps) * Math.PI * 2;
        return [cx + radius * Math.cos(angle), cy + radius * Math.sin(angle)];
    });
}
function cylinder(cx, cy, radius, z, height, projection, steps = 32, bevel = 0) {
    return extrude(circleRing(cx, cy, radius, steps), z, height, projection, { convex: true, bevel });
}
const insetPlan = ({ x, y, w, d, r }, by) => ({ x: x + by, y: y + by, w: w - by * 2, d: d - by * 2, r: Math.max(0, r - by) });
function onTop(points, z, projection, closed = false) {
    return pathOf(points.map(([x, y]) => iso([x, y, z], projection)), closed);
}
const planOutline = (plan, z, projection, steps = 8) => onTop(ringOf(plan, steps), z, projection, true);
const lineOnTop = (from, to, z, projection) => onTop([from, to], z, projection);
const segment = (from, to, projection) => pathOf([iso(from, projection), iso(to, projection)]);
const rise = (at, from, to, projection) => segment([at[0], at[1], from], [at[0], at[1], to], projection);
const ring = (cx, cy, radius, z, projection, steps = 40) => onTop(circleRing(cx, cy, radius, steps), z, projection, true);
const pointsAt = (points, projection) => points.map((point) => iso(point, projection));
function dotGrid(x, y, columns, rows, gap, z, projection) {
    const dots = [];
    for (let row = 0; row < rows; row++) {
        for (let column = 0; column < columns; column++)
            dots.push(iso([x + column * gap, y + row * gap, z], projection));
    }
    return dots;
}
function corners({ x, y, w, d }, inset = 0) {
    return [
        [x + inset, y + inset],
        [x + w - inset, y + inset],
        [x + inset, y + d - inset],
        [x + w - inset, y + d - inset],
    ];
}
function boxCorners({ x, y, w, d }, z0, z1) {
    const out = [];
    for (const px of [x, x + w])
        for (const py of [y, y + d])
            for (const z of [z0, z1])
                out.push([px, py, z]);
    return out;
}
function knurl(cx, cy, radius, z0, z1, count, projection, inset = 0.4) {
    const [vx, vy] = towardViewer(projection);
    const lines = [];
    for (let index = 0; index < count; index++) {
        const angle = (index / count) * Math.PI * 2;
        const nx = Math.cos(angle);
        const ny = Math.sin(angle);
        if (nx * vx + ny * vy < 0.15)
            continue;
        lines.push(segment([cx + radius * nx, cy + radius * ny, z0 + inset], [cx + radius * nx, cy + radius * ny, z1 - inset], projection));
    }
    return lines.join("");
}
function sideArc(cx, cy, radius, z, projection, steps = 24) {
    const [vx, vy] = towardViewer(projection);
    const facing = Math.atan2(vy, vx);
    const points = [];
    for (let step = 0; step <= steps; step++) {
        const angle = facing - Math.PI / 2 + (step / steps) * Math.PI;
        points.push(iso([cx + radius * Math.cos(angle), cy + radius * Math.sin(angle), z], projection));
    }
    return pathOf(points);
}
function ringSeam(ring, z, projection) {
    const count = ring.length;
    const counter = signedArea(ring) > 0;
    const [vx, vy] = towardViewer(projection);
    const facing = ring.map(([ax, ay], index) => {
        const [bx, by] = ring[(index + 1) % count];
        const dx = bx - ax;
        const dy = by - ay;
        const [nx, ny] = counter ? [dy, -dx] : [-dy, dx];
        return nx * vx + ny * vy > 1e-9;
    });
    return runsOf(facing, true).map((run) => pathOf(run.map((index) => iso([ring[index][0], ring[index][1], z], projection)))).join("");
}
const sideSeam = (plan, z, projection, steps = 8) => ringSeam(ringOf(plan, steps), z, projection);
function sideRing(center, radius, axis, projection, steps = 20) {
    const [px, py, pz] = center;
    const points = [];
    for (let step = 0; step < steps; step++) {
        const angle = (step / steps) * Math.PI * 2;
        const u = radius * Math.cos(angle);
        const v = radius * Math.sin(angle);
        points.push(iso(axis === "x" ? [px, py + u, pz + v] : [px + u, py, pz + v], projection));
    }
    return pathOf(points, true);
}
function coil(from, to, center, radius, turns, axis, projection, perTurn = 16) {
    const count = Math.max(1, Math.round(turns * perTurn));
    const back = [];
    const front = [];
    let run = [];
    let near = null;
    for (let index = 0; index <= count; index++) {
        const share = index / count;
        const angle = share * turns * Math.PI * 2;
        const u = radius * Math.cos(angle);
        const v = radius * Math.sin(angle);
        const along = from + (to - from) * share;
        const point = axis === "x" ? [along, center[0] + u, center[1] + v] : [center[0] + u, along, center[1] + v];
        const normal = axis === "x" ? [0, Math.cos(angle), Math.sin(angle)] : [Math.cos(angle), 0, Math.sin(angle)];
        const facing = depthOf(normal, projection) > 0;
        const flat = iso(point, projection);
        if (near === null)
            near = facing;
        if (facing !== near) {
            run.push(flat);
            (near ? front : back).push(pathOf(run));
            run = [flat];
            near = facing;
            continue;
        }
        run.push(flat);
    }
    if (run.length > 1)
        (near ? front : back).push(pathOf(run));
    return { back: back.join(""), front: front.join("") };
}
function topTicks(from, to, step, every, edge, z, lengths, projection, axis = "x") {
    const minor = [];
    const major = [];
    const first = Math.ceil(from / step) * step;
    for (let at = first, index = Math.round(first / step); at <= to + 1e-9; at += step, index++) {
        const big = index % every === 0;
        const length = big ? lengths[1] : lengths[0];
        const a = axis === "x" ? [at, edge] : [edge, at];
        const b = axis === "x" ? [at, edge + length] : [edge + length, at];
        (big ? major : minor).push(lineOnTop(a, b, z, projection));
    }
    return { minor: minor.join(""), major: major.join("") };
}
function sideTicks(from, to, step, every, face, top, lengths, projection, axis = "x") {
    const minor = [];
    const major = [];
    const first = Math.ceil(from / step) * step;
    for (let at = first, index = Math.round(first / step); at <= to + 1e-9; at += step, index++) {
        const big = index % every === 0;
        const length = big ? lengths[1] : lengths[0];
        const a = axis === "x" ? [at, face, top] : [face, at, top];
        const b = axis === "x" ? [at, face, top - length] : [face, at, top - length];
        (big ? major : minor).push(segment(a, b, projection));
    }
    return { minor: minor.join(""), major: major.join("") };
}
function radialTicks(cx, cy, radius, count, every, z, lengths, projection, from = 0, span = Math.PI * 2) {
    const minor = [];
    const major = [];
    const closed = Math.abs(span - Math.PI * 2) < 1e-9;
    const steps = closed ? count : count + 1;
    for (let index = 0; index < steps; index++) {
        const angle = from + (index / count) * span;
        const big = index % every === 0;
        const inner = radius - (big ? lengths[1] : lengths[0]);
        const sx = Math.sin(angle);
        const sy = -Math.cos(angle);
        (big ? major : minor).push(lineOnTop([cx + sx * inner, cy + sy * inner], [cx + sx * radius, cy + sy * radius], z, projection));
    }
    return { minor: minor.join(""), major: major.join("") };
}
const haloOf = (plan, z, height, projection) => slabOf(plan, z, height, projection).fill;
function axisVector(projection, [dx, dy, dz]) {
    const origin = iso([0, 0, 0], projection);
    const moved = iso([dx, dy, dz], projection);
    return [moved[0] - origin[0], moved[1] - origin[1]];
}
const fine = (value) => Math.round(value * 100) / 100;
function translateAlong(projection, by) {
    const [x, y] = axisVector(projection, by);
    return `translate(${fine(x)} ${fine(y)})`;
}
const thousandth = (value) => Math.round(value * 1000) / 1000;
function axesAt(at, z, projection) {
    const origin = iso([at[0], at[1], z], projection);
    const along = (point) => {
        const [px, py] = iso(point, projection);
        return [px - origin[0], py - origin[1]];
    };
    return { origin, x: along([at[0] + 1, at[1], z]), y: along([at[0], at[1] + 1, z]), z: along([at[0], at[1], z + 1]) };
}
const matrixOf = (u, v, origin) => `matrix(${thousandth(u[0])} ${thousandth(u[1])} ${thousandth(v[0])} ${thousandth(v[1])} ${thousandth(origin[0])} ${thousandth(origin[1])})`;
function holdStill(element, [dx, dy]) {
    const text = dx || dy ? `translate(${-dx} ${-dy})` : "";
    element.querySelectorAll("[data-hold]").forEach((node) => (text ? node.setAttribute("transform", text) : node.removeAttribute("transform")));
}
function topMatrix(at, z, projection, along = "x") {
    const { origin, x, y } = axesAt(at, z, projection);
    return along === "x" ? matrixOf(x, y, origin) : matrixOf([-y[0], -y[1]], x, origin);
}
function sideMatrix(at, z, projection, face) {
    const { origin, x, y, z: up } = axesAt(at, z, projection);
    const down = [-up[0], -up[1]];
    return face === "left" ? matrixOf(x, down, origin) : matrixOf([-y[0], -y[1]], down, origin);
}
function screenRise(height, projection) {
    const { cosE, k } = cameraOf(projection);
    return height * cosE * k;
}
function fitProjection(points, width, height, { pad = 24, headroom = 0, azimuth = AZIMUTH, elevation = ELEVATION } = {}) {
    const unit = { origin: [0, 0], scale: 1, azimuth, elevation };
    const flat = points.map((point) => iso(point, unit));
    const xs = flat.map(([x]) => x);
    const ys = flat.map(([, y]) => y);
    const left = Math.min(...xs);
    const right = Math.max(...xs);
    const up = Math.min(...ys);
    const down = Math.max(...ys);
    const scale = Math.min((width - pad * 2) / (right - left), (height - pad * 2 - headroom) / (down - up));
    return {
        origin: [width / 2 - ((left + right) / 2) * scale, headroom + (height - headroom) / 2 - ((up + down) / 2) * scale],
        scale,
        azimuth,
        elevation,
    };
}
const escape = (text) => text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
const attrs = (pairs) => Object.entries(pairs)
    .filter(([, value]) => value !== undefined && value !== false)
    .map(([key, value]) => ` ${key}="${escape(String(value))}"`)
    .join("");
function solidSvg(paths, { tone = "hi", crease = "faint", halo = false, lit = false, flat = false, className, inner = "" } = {}) {
    const parts = [];
    if (halo)
        parts.push(`<path class="iso-halo" d="${paths.fill}"/>`);
    parts.push(`<path class="iso-fill" d="${paths.fill}"/>`);
    if (!flat) {
        paths.shades.forEach((d, shade) => {
            if (d)
                parts.push(`<path class="iso-shade" data-shade="${shade}" d="${d}"/>`);
        });
        parts.push(`<path class="iso-top" d="${paths.top}"/>`);
    }
    if (crease !== "none" && paths.crease)
        parts.push(`<path class="iso-line iso-crease" data-tone="${crease}" d="${paths.crease}"/>`);
    if (crease !== "none" && paths.bevel)
        parts.push(`<path class="iso-line iso-bevel" data-tone="${crease}" d="${paths.bevel}"/>`);
    if (inner)
        parts.push(inner);
    parts.push(`<path class="iso-line iso-edge" data-tone="${tone}" d="${paths.outline}"/>`);
    return `<g${attrs({ class: className ? `iso-solid ${className}` : "iso-solid", "data-lit": lit ? "" : undefined, "data-seamless": paths.seamless ? "" : undefined })}>${parts.join("")}</g>`;
}
function lineSvg(d, { tone = "lo", dotted = false, dashed = false, flow = false, className, free } = {}) {
    if (!d)
        return "";
    return `<path${attrs({
        class: className ? `iso-line ${className}` : "iso-line",
        "data-tone": tone,
        "data-free": free === true ? "" : free || undefined,
        "data-dotted": dotted ? "" : undefined,
        "data-dashed": dashed ? "" : undefined,
        "data-flow": flow ? "" : undefined,
        d,
    })}/>`;
}
function dotsSvg(points, { size = 0.5, tone = "mid", pulse = false } = {}) {
    const dots = points
        .map(([x, y, alpha], index) => `<circle cx="${tenth(x)}" cy="${tenth(y)}" r="${size}"${alpha !== undefined && alpha < 1 ? ` fill-opacity="${Math.max(0, alpha).toFixed(2)}"` : ""}${pulse ? ` data-pulse="" style="--pulse-at:${(index * 137) % 1600}ms"` : ""}/>`)
        .join("");
    return `<g class="iso-dots" data-tone="${tone}">${dots}</g>`;
}
function fadedSvg(list, style = {}) {
    const groups = new Map();
    for (const entry of list) {
        const [d, alpha] = Array.isArray(entry) ? entry : [entry.d, entry.alpha];
        if (!d || !(alpha > 0.02))
            continue;
        const level = Math.min(1, Math.round(alpha * 20) / 20);
        groups.set(level, (groups.get(level) ?? "") + d);
    }
    return [...groups].map(([alpha, d]) => (alpha >= 1 ? lineSvg(d, style) : `<g opacity="${alpha.toFixed(2)}">${lineSvg(d, style)}</g>`)).join("");
}
function fadeLineSvg(points, { fade = [0, 0], tone = "lo", className, stops = 6 } = {}) {
    if (points.length < 2)
        return "";
    const ease = (t) => t * t * (3 - 2 * t);
    const hashOf = (text) => {
        let hash = 2166136261;
        for (let index = 0; index < text.length; index++)
            hash = Math.imul(hash ^ text.charCodeAt(index), 16777619);
        return (hash >>> 0).toString(36);
    };
    const lengths = [0];
    for (let index = 1; index < points.length; index++)
        lengths.push(lengths[index - 1] + Math.hypot(points[index][0] - points[index - 1][0], points[index][1] - points[index - 1][1]));
    const at = (length) => {
        let index = 1;
        while (index < points.length - 1 && lengths[index] < length)
            index++;
        const span = lengths[index] - lengths[index - 1] || 1;
        const t = Math.max(0, Math.min(1, (length - lengths[index - 1]) / span));
        return [points[index - 1][0] + (points[index][0] - points[index - 1][0]) * t, points[index - 1][1] + (points[index][1] - points[index - 1][1]) * t];
    };
    const pieceOf = (from, to) => [at(from), ...points.filter((_, index) => lengths[index] > from + 1e-6 && lengths[index] < to - 1e-6), at(to)];
    const total = lengths[lengths.length - 1];
    if (total < 1e-6)
        return "";
    let [start, end] = Array.isArray(fade) ? fade : [fade, fade];
    if (start + end > total) {
        const share = total / (start + end);
        start *= share;
        end *= share;
    }
    const paint = `var(--anatomy-${tone})`;
    const ramp = (piece, rising) => {
        const d = pathOf(piece);
        const [x1, y1] = piece[0];
        const [x2, y2] = piece[piece.length - 1];
        const id = `iso-fade-${hashOf(`${d}|${tone}|${rising}`)}`;
        const marks = Array.from({ length: stops + 1 }, (_, index) => {
            const t = index / stops;
            const alpha = ease(rising ? t : 1 - t);
            return `<stop offset="${t.toFixed(3)}" stop-opacity="${alpha.toFixed(3)}" style="stop-color:${paint}"/>`;
        }).join("");
        const gradient = `<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${tenth(x1)}" y1="${tenth(y1)}" x2="${tenth(x2)}" y2="${tenth(y2)}">${marks}</linearGradient>`;
        return `<defs>${gradient}</defs><path${attrs({ class: className ? `iso-line ${className}` : "iso-line", "data-tone": tone, "data-free": rising ? "start" : "end", d, style: `stroke:url(#${id})` })}/>`;
    };
    const parts = [];
    if (start > 0.05)
        parts.push(ramp(pieceOf(0, start), true));
    if (total - end - start > 0.05)
        parts.push(lineSvg(pathOf(pieceOf(start, total - end)), { tone, className }));
    if (end > 0.05)
        parts.push(ramp(pieceOf(total - end, total), false));
    return `<g class="iso-fade">${parts.join("")}</g>`;
}
function wireSvg(from, to, { via = [], tone = "lo", dotted = false, dashed = false, flow = false, ends = "both", size = 1.2 } = {}) {
    const d = pathOf([from, ...via, to]);
    const end = ([x, y]) => `<circle class="iso-wire-end" cx="${tenth(x)}" cy="${tenth(y)}" r="${size}"/>`;
    return `<g class="iso-wire" data-tone="${tone}">${lineSvg(d, { tone, dotted, dashed, flow })}${ends === "both" || ends === "from" ? end(from) : ""}${ends === "both" || ends === "to" ? end(to) : ""}</g>`;
}
function faceTextSvg(transform, text, { size = 3, tone = "mid", anchor = "start" } = {}) {
    return `<text class="iso-face-text" data-tone="${tone}" transform="${transform}" font-size="${size}" text-anchor="${anchor}">${escape(text)}</text>`;
}
function groupSvg(children, extra = {}) {
    return `<g${attrs(extra)}>${Array.isArray(children) ? children.join("") : children}</g>`;
}
function figureSvg({ width, height, label, body, standalone = false, className }) {
    const style = standalone ? `<style>${ISO_CSS}</style>` : "";
    return `<svg${attrs({
        xmlns: "http://www.w3.org/2000/svg",
        class: `iso-svg${standalone ? " iso" : ""}${className ? ` ${className}` : ""}`,
        "data-theme": standalone || undefined,
        viewBox: `0 0 ${width} ${height}`,
        role: "img",
        "aria-label": label,
    })}>${style}${Array.isArray(body) ? body.join("") : body}</svg>`;
}
function swatchSvg(mark) {
    if (mark === "raised" || mark === "flat" || mark === "lit") {
        const body = mark === "flat" ? "M2 6.5 8 3.5 14 6.5 8 9.5Z" : "M2 4.5 8 1.5 14 4.5 14 6 8 9 2 6Z";
        const crease = mark === "flat" ? "" : `<path class="iso-swatch-crease" d="M2 4.5 8 7.5 14 4.5"/>`;
        return `<svg class="iso-swatch" data-mark="${mark}" viewBox="0 0 16 10" aria-hidden="true"><path d="${body}"/>${crease}</svg>`;
    }
    return `<svg class="iso-swatch" data-mark="${mark}" viewBox="0 0 16 10" aria-hidden="true"><path d="M1.5 5h13"/></svg>`;
}
function plateHtml({ fig, title, hint, readout = "", keys = [], caption, body }) {
    const corner = (name, text, extra = "") => `<span class="iso-plate-corner" data-corner="${name}"${extra}>${escape(text)}</span>`;
    const legend = keys.length || caption
        ? `<figcaption class="iso-legend">${keys.length ? `<span class="iso-keys">${keys.map((key) => `<span class="iso-key">${swatchSvg(key.mark)}${escape(key.label)}</span>`).join("")}</span>` : ""}${caption ? `<span class="iso-caption">${escape(caption)}</span>` : ""}</figcaption>`
        : "";
    return `<figure class="iso-figure"><div class="iso-plate"><div class="iso-plate-corners" aria-hidden="true">${corner("fig", fig)}${title ? corner("title", title) : ""}${hint ? corner("hint", hint) : ""}${corner("readout", readout, " data-readout")}</div>${body}</div>${legend}</figure>`;
}
function pageHtml({ title, theme = "dark", body, script = "", width = 650 }) {
    return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${escape(title)}</title><style>${ISO_CSS}${PAGE_CSS.replace("__WIDTH__", String(width))}</style></head><body class="iso" data-theme="${theme}"><main class="iso-page">${body}</main>${script ? `<script type="module">${script.replace(/<\/(script)/gi, "<\\/$1")}</script>` : ""}</body></html>`;
}
const PAGE_CSS = `
*{box-sizing:border-box}
html{color-scheme:dark}
body[data-theme="light"]{color-scheme:light}
body{margin:0;background:var(--anatomy-page);color:var(--anatomy-text);font:14px/1.6 ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif;-webkit-font-smoothing:antialiased}
.iso-page{width:min(__WIDTH__px,100% - 32px);margin:64px auto 96px}
`;
const ISO_CSS = `
.iso{
  --anatomy-page:#0b0b0b;--anatomy-card:#131313;--anatomy-text:#a3a3a3;--anatomy-strong:#e6e6e6;--anatomy-muted:#6e6e6e;
  --anatomy-paper:#131313;--anatomy-face:#1b1b1b;--anatomy-top:#1c1c1c;
  --anatomy-shade-0:#0f0f0f;--anatomy-shade-1:#121212;--anatomy-shade-2:#151515;--anatomy-shade-3:#181818;
  --anatomy-lit-top:#262626;--anatomy-lit-shade:#1d1d1d;
  --anatomy-hi:#d4d4d4;--anatomy-mid:#6a6a6a;--anatomy-lo:#3e3e3e;--anatomy-faint:#2a2a2a;--anatomy-lit:#ffffff;
  --anatomy-halo:#050505;--anatomy-halo-opacity:.9;--anatomy-dot:#e6e6e6;
  --anatomy-red:#ff6b6b;--anatomy-green:#6be0a0;--anatomy-blue:#6b9bff;
  --anatomy-weight:.6px;--anatomy-ease:cubic-bezier(.32,.72,0,1);
  --anatomy-mono:ui-monospace,"SF Mono",Menlo,Consolas,monospace;
  --anatomy-lift:inset 0 1px 0 0 #ffffff0a,inset 0 0 0 1px #ffffff06,0 0 0 1px #00000026,0 1px 1px -.5px #00000014,0 3px 3px -1.5px #00000014,0 6px 6px -3px #00000014,0 12px 12px -6px #00000014;
}
.iso[data-theme="light"]{
  --anatomy-page:#f2f2ef;--anatomy-card:#fbfbfa;--anatomy-text:#57575a;--anatomy-strong:#18181a;--anatomy-muted:#8c8c90;
  --anatomy-paper:#fbfbfa;--anatomy-face:#ffffff;--anatomy-top:#ffffff;
  --anatomy-shade-0:#dcdcd9;--anatomy-shade-1:#e5e5e2;--anatomy-shade-2:#ededea;--anatomy-shade-3:#f4f4f1;
  --anatomy-lit-top:#ffffff;--anatomy-lit-shade:#eeeeeb;
  --anatomy-hi:#26262a;--anatomy-mid:#8a8a8f;--anatomy-lo:#b4b4b8;--anatomy-faint:#d4d4d6;--anatomy-lit:#000000;
  --anatomy-halo:#8a8a80;--anatomy-halo-opacity:.32;--anatomy-dot:#3a3a3e;
  --anatomy-red:#e5484d;--anatomy-green:#30a46c;--anatomy-blue:#3e63dd;
  --anatomy-lift:inset 0 1px 0 0 #ffffff,0 0 0 1px #0000000f,0 1px 1px -.5px #0000000a,0 3px 3px -1.5px #0000000a,0 6px 6px -3px #0000000a,0 12px 12px -6px #0000000a;
}
.iso-svg{display:block;width:100%;height:auto;overflow:visible}
.iso-svg path,.iso-svg circle{vector-effect:non-scaling-stroke}
.iso-fill{fill:var(--anatomy-paper);stroke:none}
.iso-solid[data-lit]>.iso-fill{fill:var(--anatomy-face)}
.iso-shade,.iso-top{stroke:none}
.iso-shade[data-shade="0"]{fill:var(--anatomy-shade-0)}
.iso-shade[data-shade="1"]{fill:var(--anatomy-shade-1)}
.iso-shade[data-shade="2"]{fill:var(--anatomy-shade-2)}
.iso-shade[data-shade="3"]{fill:var(--anatomy-shade-3)}
.iso-top{fill:var(--anatomy-top)}
.iso-solid[data-lit]>.iso-top{fill:var(--anatomy-lit-top)}
.iso-solid[data-lit]>.iso-shade{fill:var(--anatomy-lit-shade)}
.iso-solid[data-seamless]>.iso-shade,.iso-solid[data-seamless]>.iso-top{stroke-width:.4;stroke-linejoin:round}
.iso-solid[data-seamless]>.iso-shade[data-shade="0"]{stroke:var(--anatomy-shade-0)}
.iso-solid[data-seamless]>.iso-shade[data-shade="1"]{stroke:var(--anatomy-shade-1)}
.iso-solid[data-seamless]>.iso-shade[data-shade="2"]{stroke:var(--anatomy-shade-2)}
.iso-solid[data-seamless]>.iso-shade[data-shade="3"]{stroke:var(--anatomy-shade-3)}
.iso-solid[data-seamless]>.iso-top{stroke:var(--anatomy-top)}
.iso-solid[data-seamless][data-lit]>.iso-top{stroke:var(--anatomy-lit-top)}
.iso-solid[data-seamless][data-lit]>.iso-shade{stroke:var(--anatomy-lit-shade)}
.iso-solid[data-lit]>.iso-edge{stroke:var(--anatomy-lit)}
.iso-halo{fill:var(--anatomy-halo);stroke:none;filter:blur(9px);opacity:var(--anatomy-halo-opacity);transform:translateY(3px)}
.iso-line{fill:none;stroke:var(--anatomy-lo);stroke-width:var(--anatomy-weight);stroke-linecap:round;stroke-linejoin:round}
.iso-line[data-tone="hi"]{stroke:var(--anatomy-hi)}
.iso-line[data-tone="mid"]{stroke:var(--anatomy-mid)}
.iso-line[data-tone="lo"]{stroke:var(--anatomy-lo)}
.iso-line[data-tone="faint"]{stroke:var(--anatomy-faint)}
.iso-line[data-tone="lit"]{stroke:var(--anatomy-lit)}
.iso-line[data-tone="red"]{stroke:var(--anatomy-red)}
.iso-line[data-tone="green"]{stroke:var(--anatomy-green)}
.iso-line[data-tone="blue"]{stroke:var(--anatomy-blue)}
.iso-line[data-dotted]{stroke-dasharray:.01 3.5;stroke-width:1}
.iso-line[data-dashed]{stroke-dasharray:3 3}
.iso-line[data-flow]{stroke-dasharray:2 6}
.iso-wire-end{fill:var(--anatomy-paper);stroke:var(--anatomy-lo);stroke-width:var(--anatomy-weight)}
.iso-wire[data-tone="hi"] .iso-wire-end{stroke:var(--anatomy-hi)}
.iso-wire[data-tone="mid"] .iso-wire-end{stroke:var(--anatomy-mid)}
.iso-wire[data-tone="faint"] .iso-wire-end{stroke:var(--anatomy-faint)}
.iso-wire[data-tone="lit"] .iso-wire-end{stroke:var(--anatomy-lit)}
.iso-dots circle{fill:var(--anatomy-dot);stroke:none;opacity:.32}
.iso-dots[data-tone="hi"] circle{opacity:.85}
.iso-dots[data-tone="mid"] circle{opacity:.45}
.iso-dots[data-tone="lo"] circle{opacity:.22}
.iso-dots[data-tone="faint"] circle{opacity:.12}
.iso-face-text{fill:var(--anatomy-mid);font-family:var(--anatomy-mono);letter-spacing:.04em}
.iso-face-text[data-tone="hi"]{fill:var(--anatomy-hi)}
.iso-face-text[data-tone="lo"]{fill:var(--anatomy-lo)}
.iso-face-text[data-tone="faint"]{fill:var(--anatomy-faint)}
.tb-body{fill:var(--anatomy-shade-2);stroke:none}
.tb-shine{fill:var(--anatomy-top);stroke:none}
.tb-shade{fill:var(--anatomy-shade-0);stroke:none}
.tb-ring{fill:none;stroke:var(--anatomy-lo);stroke-width:.45;stroke-linecap:butt;stroke-linejoin:round}
.tb-ring[data-tone="mid"]{stroke:var(--anatomy-mid)}
.tb-ring[data-tone="faint"]{stroke:var(--anatomy-faint)}
.tb-ring[data-tone="hi"]{stroke:var(--anatomy-hi)}
@keyframes iso-flow{to{stroke-dashoffset:-32}}
@keyframes iso-pulse{0%,100%{opacity:.18}12%{opacity:1}40%{opacity:.18}}
.iso-line[data-flow]{animation:iso-flow 1600ms linear infinite}
.iso-dots circle[data-pulse]{animation:iso-pulse 1600ms steps(1,end) infinite;animation-delay:var(--pulse-at,0ms)}
.iso-figure{margin:32px 0 36px}
.iso-plate{position:relative;padding:52px 24px;border-radius:0;background:var(--anatomy-card);box-shadow:0 0 0 1px var(--anatomy-mid);--anatomy-paper:var(--anatomy-card)}
.iso-plate-corners{position:absolute;inset:18px 20px;pointer-events:none}
.iso-plate-corner{position:absolute;color:var(--anatomy-muted);font-family:var(--anatomy-mono);font-size:10.5px;line-height:1;letter-spacing:.04em;white-space:nowrap}
.iso-plate-corner[data-corner="fig"]{top:0;left:0;color:var(--anatomy-text)}
.iso-plate-corner[data-corner="title"]{top:0;right:0;text-transform:uppercase;letter-spacing:.1em}
.iso-plate-corner[data-corner="hint"]{bottom:0;left:0;text-transform:uppercase;letter-spacing:.1em}
.iso-plate-corner[data-corner="readout"]{right:0;bottom:0;color:var(--anatomy-text);font-variant-numeric:tabular-nums}
.iso-plate .iso-svg{margin:0 auto}
.iso-legend{display:grid;gap:8px;margin-top:12px;color:var(--anatomy-muted);font-size:13px;line-height:1.55}
.iso-keys{display:flex;flex-wrap:wrap;gap:6px 18px}
.iso-key{display:inline-flex;align-items:center;gap:7px}
.iso-swatch{width:16px;height:10px;flex:none;overflow:visible}
.iso-swatch path{fill:none;stroke:var(--anatomy-hi);stroke-width:1;vector-effect:non-scaling-stroke;stroke-linejoin:round;stroke-linecap:round}
.iso-swatch[data-mark="raised"] path,.iso-swatch[data-mark="flat"] path{stroke:var(--anatomy-mid)}
.iso-swatch[data-mark="lit"] path{stroke:var(--anatomy-lit)}
.iso-swatch[data-mark="dim"] path{stroke:var(--anatomy-lo)}
.iso-swatch[data-mark="dotted"] path{stroke-dasharray:.01 3;stroke-width:1.4}
.iso-swatch[data-mark="dashed"] path{stroke-dasharray:3 2.5}
.iso-swatch[data-mark="red"] path{stroke:var(--anatomy-red)}
.iso-swatch[data-mark="green"] path{stroke:var(--anatomy-green)}
.iso-swatch[data-mark="blue"] path{stroke:var(--anatomy-blue)}
.iso-swatch-crease{stroke:var(--anatomy-lo)!important}
@media (max-width:560px){.iso-plate{padding:48px 12px 70px}.iso-plate-corner[data-corner="hint"]{bottom:18px}}
@media (prefers-reduced-motion:reduce){.iso-line[data-flow],.iso-dots circle[data-pulse]{animation:none}}
`;

// Modelo geométrico de la inspección One Stop (misma lógica que MissionPlanner/PathPlanner de Nexus App, simplificada).
// Unidades del modelo: 1 m = K unidades. y: hacia el frente de la turbina; z: arriba. El visor del kit es un espejo (x a la derecha
// mirando desde +y), así que x positivo = derecha del rotor visto de frente y los giros de cara van con el signo cambiado.
const K = 2.5;
const L = 100; // pala de 40 m
const ZH = 130; // altura del buje en el modelo (la torre se dibuja recortada)
const HUB = [0, 0, ZH];
const SPIN_TIP_Y = 19.5;
const D_TIP = 11 * K; // sin RTK: 5,5 + 5,5 m
const D_ROOT = 13 * K; // sin RTK: 10 + 3 m
const S_LE0 = 10;
const S_SS_END = 30;
const S_TE0 = 55;
const TIP_END = L + 4 * K; // punta + margen de 4 m
const HOME = [74, 128, 0];
const CLOCK = { "4h": 120, "12h": 0, "8h": 240 };

const rad = (d) => (d * Math.PI) / 180;
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const mul = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const len = (a) => Math.hypot(a[0], a[1], a[2]);
const lerp = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
{ add, sub, mul, len };

function rotate(v, axis, deg) {
  const c = Math.cos(rad(deg)), s = Math.sin(rad(deg));
  const kv = cross(axis, v), kd = dot(axis, v) * (1 - c);
  return [v[0] * c + kv[0] * s + axis[0] * kd, v[1] * c + kv[1] * s + axis[1] * kd, v[2] * c + kv[2] * s + axis[2] * kd];
}
const dirOf = (clockDeg) => [Math.sin(rad(clockDeg)), 0, Math.cos(rad(clockDeg))];
const FRONT = [0, 1, 0];
const ROT = { LE: 0, SS: 90, TE: 180, PS: 270 };
const dAt = (s) => D_ROOT + (D_TIP - D_ROOT) * Math.min(1, Math.max(0, (s - S_LE0) / (TIP_END - S_LE0)));
const onBlade = (a, s, rotDeg) => add(add(HUB, mul(a, s)), mul(rotate(FRONT, a, -rotDeg), dAt(s)));

// Una pala completa: LE (raíz→punta), SS (punta→raíz), TE (raíz→punta), PS (punta→raíz) con giros de 15° en los extremos.
function bladePath(clockDeg) {
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
function bladeToBlade(from, to) {
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

const aim = (clockDeg, s) => add(add(HUB, mul(dirOf(clockDeg), s)), [0, 3.5, 0]);
const inFront = (p, m = 5) => add(p, [0, m * K, 0]); // el dron queda al frente del punto apuntado

function buildSteps() {
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

const P = {"origin":[365.82717374920855,426.9145418432289],"scale":1.3673615733282893,"azimuth":58,"elevation":21};
const STEPS = [{"id":"hub","kind":"measure","phase":"Calibración","title":"Buje","target":[0,19.5,136],"pos":[0,32,136],"marks":[{"kind":"dim","a":[0,32,136],"b":[0,19.5,136],"text":"4–6 m","dx":20,"dy":-22},{"kind":"band","z0":80,"z1":180,"y":32,"text":"buje ±20 m"}]},{"id":"tip12","kind":"measure","phase":"Calibración","title":"Punta 12h","target":[0,3.5,220],"pos":[0,16,220],"marks":[{"kind":"dim","a":[0,16,220],"b":[0,3.5,220],"text":"4–6 m","dx":22,"dy":-20},{"kind":"bracket","a":[0,3.5,215],"b":[0,3.5,225]},{"kind":"ray","a":[0,3.5,130],"b":[0,3.5,242]},{"kind":"ray","a":[0,3.5,130],"b":[28.987733051482323,3.5,238.18369254437565]},{"kind":"tag","at":[-40,3.5,212],"text":"85–95 % del radio"}]},{"id":"root12","kind":"measure","phase":"Calibración","title":"Raíz 12h","target":[0,3.5,146],"pos":[0,16,146],"marks":[{"kind":"dim","a":[0,16,146],"b":[0,3.5,146],"text":"4–6 m","dx":22,"dy":-20},{"kind":"bracket","a":[0,3.5,137.5],"b":[0,3.5,155]},{"kind":"tag","at":[-38,3.5,161],"text":"3–10 m del buje"}]},{"id":"tip4","kind":"measure","phase":"Calibración","title":"Punta 4h","target":[77.94228634059948,3.5,85.00000000000003],"pos":[77.94228634059948,16,85.00000000000003],"marks":[{"kind":"dim","a":[77.94228634059948,16,85.00000000000003],"b":[77.94228634059948,3.5,85.00000000000003],"text":"4–6 m","dx":22,"dy":-20},{"kind":"bracket","a":[73.6121593216773,3.5,87.50000000000003],"b":[82.27241335952168,3.5,82.50000000000003]},{"kind":"ray","a":[0,3.5,130],"b":[108.18369254437565,3.5,101.01226694851766]},{"kind":"ray","a":[0,3.5,130],"b":[79.19595949289332,3.5,50.80404050710669]},{"kind":"tag","at":[77.94228634059948,3.5,69.00000000000003],"text":"85–95 %"}]},{"id":"tip8","kind":"measure","phase":"Calibración","title":"Punta 8h","target":[-77.94228634059947,3.5,84.99999999999996],"pos":[-77.94228634059947,16,84.99999999999996],"marks":[{"kind":"dim","a":[-77.94228634059947,16,84.99999999999996],"b":[-77.94228634059947,3.5,84.99999999999996],"text":"4–6 m","dx":22,"dy":-20},{"kind":"bracket","a":[-73.61215932167727,3.5,87.49999999999997],"b":[-82.27241335952165,3.5,82.49999999999996]},{"kind":"ray","a":[0,3.5,130],"b":[-79.19595949289331,3.5,50.80404050710666]},{"kind":"ray","a":[0,3.5,130],"b":[-108.18369254437565,3.5,101.01226694851769]},{"kind":"tag","at":[-77.94228634059947,3.5,68.99999999999996],"text":"85–95 %"}]},{"id":"plan","kind":"plan","phase":"Plan","title":"Verificación y plan","route":[{"p":[-77.94228634059947,16,84.99999999999996]},{"p":[8.660254037844387,32.5,125]}],"dur":4.2,"marks":[{"kind":"tag","at":[8.660254037844387,32.5,125],"text":"1ª · 4h"},{"kind":"tag","at":[0,32.5,148],"text":"2ª · 12h"},{"kind":"tag","at":[-8.660254037844386,32.5,125],"text":"3ª · 8h"}]},{"id":"blade-4h","kind":"blade","phase":"Inspección","title":"Pala 4h · 1ª","route":[{"p":[8.660254037844387,32.5,125],"face":"LE","photo":true,"s":10,"axis":[8.660254037844387,0,125]},{"p":[21.032045520479226,31.785714285714285,117.85714285714286],"face":"LE","photo":true,"s":24.285714285714285,"axis":[21.032045520479226,0,117.85714285714286]},{"p":[33.40383700311406,31.071428571428573,110.71428571428572],"face":"LE","photo":true,"s":38.57142857142857,"axis":[33.40383700311406,0,110.71428571428572]},{"p":[45.7756284857489,30.357142857142858,103.57142857142858],"face":"LE","photo":true,"s":52.857142857142854,"axis":[45.7756284857489,0,103.57142857142858]},{"p":[58.14741996838374,29.642857142857142,96.42857142857144],"face":"LE","photo":true,"s":67.14285714285714,"axis":[58.14741996838374,0,96.42857142857144]},{"p":[70.51921145101858,28.928571428571427,89.2857142857143],"face":"LE","photo":true,"s":81.42857142857143,"axis":[70.51921145101858,0,89.2857142857143]},{"p":[82.89100293365341,28.214285714285715,82.14285714285717],"face":"LE","photo":true,"s":95.71428571428571,"axis":[82.89100293365341,0,82.14285714285717]},{"p":[95.26279441628826,27.5,75.00000000000003],"face":"LE","photo":true,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[91.7040325461286,26.56296022294938,68.83604362884466],"face":"T","photo":false,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[88.38779441628826,23.815698604072065,63.092150697964],"face":"T","photo":false,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[85.54007617497324,19.445436482630058,58.15975801836568],"face":"T","photo":false,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[83.35494511425223,13.750000000000004,54.37500000000003],"face":"T","photo":false,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[81.98131430481358,7.117523740319321,51.995801647210314],"face":"T","photo":false,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[81.51279441628826,1.6838893488276107e-15,51.18430139592796],"face":"SS","photo":true,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[69.63245569916242,1.724710908799189e-15,57.273617793404995],"face":"SS","photo":true,"s":96.66666666666667,"axis":[83.71578903249575,0,81.66666666666669]},{"p":[57.75211698203657,1.7655324687707674e-15,63.362934190882044],"face":"SS","photo":true,"s":83.33333333333333,"axis":[72.16878364870323,0,88.33333333333336]},{"p":[45.871778264910716,1.8063540287423458e-15,69.45225058835908],"face":"SS","photo":true,"s":70,"axis":[60.62177826491071,0,95.00000000000001]},{"p":[33.991439547784864,1.8471755887139246e-15,75.54156698583611],"face":"SS","photo":true,"s":56.666666666666664,"axis":[49.07477288111819,0,101.66666666666669]},{"p":[22.11110083065902,1.887997148685503e-15,81.63088338331315],"face":"SS","photo":true,"s":43.33333333333333,"axis":[37.52776749732568,0,108.33333333333334]},{"p":[10.230762113533167,1.9288187086570813e-15,87.72019978079018],"face":"SS","photo":true,"s":30,"axis":[25.98076211353316,0,115]},{"p":[10.76743034948034,-8.152799920729407,88.64973643225905],"face":"T","photo":false,"s":30,"axis":[25.98076211353316,0,115]},{"p":[12.340862003928256,-15.749999999999993,91.375],"face":"T","photo":false,"s":30,"axis":[25.98076211353316,0,115]},{"p":[14.84383030984504,-22.273863607376246,95.71026827558248],"face":"T","photo":false,"s":30,"axis":[25.98076211353316,0,115]},{"p":[18.105762113533164,-27.279800219209818,101.36009989039509],"face":"T","photo":false,"s":30,"axis":[25.98076211353316,0,115]},{"p":[21.904362153168456,-30.42666352810565,107.93946815667657],"face":"T","photo":false,"s":30,"axis":[25.98076211353316,0,115]},{"p":[47.63139720814413,-30.25,102.50000000000001],"face":"TE","photo":true,"s":55,"axis":[47.63139720814413,0,102.50000000000001]},{"p":[59.53924651018016,-29.5625,95.62500000000001],"face":"TE","photo":true,"s":68.75,"axis":[59.53924651018016,0,95.62500000000001]},{"p":[71.44709581221619,-28.875,88.75000000000003],"face":"TE","photo":true,"s":82.5,"axis":[71.44709581221619,0,88.75000000000003]},{"p":[83.35494511425223,-28.1875,81.87500000000003],"face":"TE","photo":true,"s":96.25,"axis":[83.35494511425223,0,81.87500000000003]},{"p":[95.26279441628826,-27.5,75.00000000000003],"face":"TE","photo":true,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[98.82155628644792,-26.562960222949382,81.1639563711554],"face":"T","photo":false,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[102.13779441628826,-23.81569860407206,86.90784930203607],"face":"T","photo":false,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[104.98551265760328,-19.44543648263006,91.84024198163438],"face":"T","photo":false,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[107.17064371832429,-13.750000000000012,95.62500000000003],"face":"T","photo":false,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[108.54427452776294,-7.117523740319317,98.00419835278974],"face":"T","photo":false,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[109.01279441628826,-5.051668046482832e-15,98.8156986040721],"face":"PS","photo":true,"s":110,"axis":[95.26279441628826,0,75.00000000000003]},{"p":[97.79912236582908,-5.174132726397566e-15,106.05971553992838],"face":"PS","photo":true,"s":96.66666666666667,"axis":[83.71578903249575,0,81.66666666666669]},{"p":[86.58545031536988,-5.296597406312302e-15,113.30373247578467],"face":"PS","photo":true,"s":83.33333333333333,"axis":[72.16878364870323,0,88.33333333333336]},{"p":[75.3717782649107,-5.4190620862270374e-15,120.54774941164095],"face":"PS","photo":true,"s":70,"axis":[60.62177826491071,0,95.00000000000001]},{"p":[64.15810621445152,-5.541526766141773e-15,127.79176634749726],"face":"PS","photo":true,"s":56.666666666666664,"axis":[49.07477288111819,0,101.66666666666669]},{"p":[52.944434163992334,-5.663991446056508e-15,135.03578328335354],"face":"PS","photo":true,"s":43.33333333333333,"axis":[37.52776749732568,0,108.33333333333334]},{"p":[41.73076211353315,-5.786456125971244e-15,142.27980021920982],"face":"PS","photo":true,"s":30,"axis":[25.98076211353316,0,115]}],"speed":62,"marks":[{"kind":"dim","a":[86.60254037844388,0,80.00000000000003],"b":[95.26279441628826,27.5,75.00000000000003],"text":"11 m · RTK 5,5 m","dx":0,"dy":-22},{"kind":"dim","a":[10.392304845413264,0,124],"b":[8.660254037844387,32.5,125],"text":"13 m · RTK 10 m","dx":8,"dy":24},{"kind":"tag","at":[58.14741996838374,29.642857142857142,96.42857142857144],"text":"LE"},{"kind":"tag","at":[45.871778264910716,1.8063540287423458e-15,69.45225058835908],"text":"SS"},{"kind":"tag","at":[71.44709581221619,-28.875,88.75000000000003],"text":"TE"},{"kind":"tag","at":[75.3717782649107,-5.4190620862270374e-15,120.54774941164095],"text":"PS"}]},{"id":"to12","kind":"transfer","phase":"Inspección","title":"Salto 4h → 12h","route":[{"p":[41.73076211353315,-5.786456125971244e-15,142.27980021920982],"face":"T","photo":false},{"p":[41.73076211353315,9.999999999999995,142.27980021920982],"face":"T","photo":false},{"p":[33.52258252170857,19.13840067976228,142.75404744974236],"face":"T","photo":false},{"p":[23.444453248361,26.172925387282003,142.51685107501777],"face":"T","photo":false},{"p":[12.058550391135661,30.71117573773982,141.58144233356268],"face":"T","photo":false},{"p":[0,32.5,140],"face":"T","photo":false}],"speed":46,"marks":[{"kind":"tag","at":[0,40.5,126],"text":"arco sobre el eje del rotor"}]},{"id":"blade-12h","kind":"blade","phase":"Inspección","title":"Pala 12h · 2ª","route":[{"p":[0,32.5,140],"face":"LE","photo":true,"s":10,"axis":[0,0,140]},{"p":[0,31.785714285714285,154.28571428571428],"face":"LE","photo":true,"s":24.285714285714285,"axis":[0,0,154.28571428571428]},{"p":[0,31.071428571428573,168.57142857142856],"face":"LE","photo":true,"s":38.57142857142857,"axis":[0,0,168.57142857142856]},{"p":[0,30.357142857142858,182.85714285714286],"face":"LE","photo":true,"s":52.857142857142854,"axis":[0,0,182.85714285714286]},{"p":[0,29.642857142857142,197.14285714285714],"face":"LE","photo":true,"s":67.14285714285714,"axis":[0,0,197.14285714285714]},{"p":[0,28.928571428571427,211.42857142857144],"face":"LE","photo":true,"s":81.42857142857143,"axis":[0,0,211.42857142857144]},{"p":[0,28.214285714285715,225.71428571428572],"face":"LE","photo":true,"s":95.71428571428571,"axis":[0,0,225.71428571428572]},{"p":[0,27.5,240],"face":"LE","photo":true,"s":110,"axis":[0,0,240]},{"p":[7.117523740319321,26.56296022294938,240],"face":"T","photo":false,"s":110,"axis":[0,0,240]},{"p":[13.749999999999998,23.815698604072065,240],"face":"T","photo":false,"s":110,"axis":[0,0,240]},{"p":[19.445436482630054,19.445436482630058,240],"face":"T","photo":false,"s":110,"axis":[0,0,240]},{"p":[23.81569860407206,13.750000000000004,240],"face":"T","photo":false,"s":110,"axis":[0,0,240]},{"p":[26.56296022294938,7.117523740319321,240],"face":"T","photo":false,"s":110,"axis":[0,0,240]},{"p":[27.5,1.6838893488276107e-15,240],"face":"SS","photo":true,"s":110,"axis":[0,0,240]},{"p":[28.166666666666664,1.724710908799189e-15,226.66666666666669],"face":"SS","photo":true,"s":96.66666666666667,"axis":[0,0,226.66666666666669]},{"p":[28.833333333333332,1.7655324687707674e-15,213.33333333333331],"face":"SS","photo":true,"s":83.33333333333333,"axis":[0,0,213.33333333333331]},{"p":[29.5,1.8063540287423458e-15,200],"face":"SS","photo":true,"s":70,"axis":[0,0,200]},{"p":[30.166666666666668,1.8471755887139246e-15,186.66666666666666],"face":"SS","photo":true,"s":56.666666666666664,"axis":[0,0,186.66666666666666]},{"p":[30.833333333333332,1.887997148685503e-15,173.33333333333331],"face":"SS","photo":true,"s":43.33333333333333,"axis":[0,0,173.33333333333331]},{"p":[31.5,1.9288187086570813e-15,160],"face":"SS","photo":true,"s":30,"axis":[0,0,160]},{"p":[30.426663528105653,-8.152799920729407,160],"face":"T","photo":false,"s":30,"axis":[0,0,160]},{"p":[27.279800219209818,-15.749999999999993,160],"face":"T","photo":false,"s":30,"axis":[0,0,160]},{"p":[22.27386360737625,-22.273863607376246,160],"face":"T","photo":false,"s":30,"axis":[0,0,160]},{"p":[15.749999999999998,-27.279800219209818,160],"face":"T","photo":false,"s":30,"axis":[0,0,160]},{"p":[8.152799920729413,-30.42666352810565,160],"face":"T","photo":false,"s":30,"axis":[0,0,160]},{"p":[3.704556567420744e-15,-30.25,185],"face":"TE","photo":true,"s":55,"axis":[0,0,185]},{"p":[3.6203620999793626e-15,-29.5625,198.75],"face":"TE","photo":true,"s":68.75,"axis":[0,0,198.75]},{"p":[3.5361676325379825e-15,-28.875,212.5],"face":"TE","photo":true,"s":82.5,"axis":[0,0,212.5]},{"p":[3.4519731650966017e-15,-28.1875,226.25],"face":"TE","photo":true,"s":96.25,"axis":[0,0,226.25]},{"p":[3.3677786976552213e-15,-27.5,240],"face":"TE","photo":true,"s":110,"axis":[0,0,240]},{"p":[-7.117523740319309,-26.562960222949382,240],"face":"T","photo":false,"s":110,"axis":[0,0,240]},{"p":[-13.750000000000004,-23.81569860407206,240],"face":"T","photo":false,"s":110,"axis":[0,0,240]},{"p":[-19.445436482630054,-19.44543648263006,240],"face":"T","photo":false,"s":110,"axis":[0,0,240]},{"p":[-23.815698604072058,-13.750000000000012,240],"face":"T","photo":false,"s":110,"axis":[0,0,240]},{"p":[-26.56296022294938,-7.117523740319317,240],"face":"T","photo":false,"s":110,"axis":[0,0,240]},{"p":[-27.5,-5.051668046482832e-15,240],"face":"PS","photo":true,"s":110,"axis":[0,0,240]},{"p":[-28.166666666666664,-5.174132726397566e-15,226.66666666666669],"face":"PS","photo":true,"s":96.66666666666667,"axis":[0,0,226.66666666666669]},{"p":[-28.833333333333332,-5.296597406312302e-15,213.33333333333331],"face":"PS","photo":true,"s":83.33333333333333,"axis":[0,0,213.33333333333331]},{"p":[-29.5,-5.4190620862270374e-15,200],"face":"PS","photo":true,"s":70,"axis":[0,0,200]},{"p":[-30.166666666666668,-5.541526766141773e-15,186.66666666666666],"face":"PS","photo":true,"s":56.666666666666664,"axis":[0,0,186.66666666666666]},{"p":[-30.833333333333332,-5.663991446056508e-15,173.33333333333331],"face":"PS","photo":true,"s":43.33333333333333,"axis":[0,0,173.33333333333331]},{"p":[-31.5,-5.786456125971244e-15,160],"face":"PS","photo":true,"s":30,"axis":[0,0,160]}],"speed":62,"marks":[{"kind":"dim","a":[0,0,230],"b":[0,27.5,240],"text":"11 m · RTK 5,5 m","dx":0,"dy":-22},{"kind":"dim","a":[0,0,142],"b":[0,32.5,140],"text":"13 m · RTK 10 m","dx":8,"dy":24},{"kind":"tag","at":[0,29.642857142857142,197.14285714285714],"text":"LE"},{"kind":"tag","at":[29.5,1.8063540287423458e-15,200],"text":"SS"},{"kind":"tag","at":[3.5361676325379825e-15,-28.875,212.5],"text":"TE"},{"kind":"tag","at":[-29.5,-5.4190620862270374e-15,200],"text":"PS"}]},{"id":"to8","kind":"transfer","phase":"Inspección","title":"Salto 12h → 8h","route":[{"p":[-31.5,-5.786456125971244e-15,160],"face":"T","photo":false},{"p":[-31.5,9.999999999999995,160],"face":"T","photo":false},{"p":[-27.806620353403307,19.138400679762277,152.65438433938866],"face":"T","photo":false},{"p":[-22.56213763053244,26.172925387282,144.04506655340836],"face":"T","photo":false},{"p":[-16.059098468897634,30.711175737739815,134.65228980475692],"face":"T","photo":false},{"p":[-8.660254037844386,32.5,125],"face":"T","photo":false}],"speed":46,"marks":[{"kind":"tag","at":[-8.660254037844386,40.5,111],"text":"al frente del rotor"}]},{"id":"blade-8h","kind":"blade","phase":"Inspección","title":"Pala 8h · 3ª","route":[{"p":[-8.660254037844386,32.5,125],"face":"LE","photo":true,"s":10,"axis":[-8.660254037844386,0,125]},{"p":[-21.03204552047922,31.785714285714285,117.85714285714285],"face":"LE","photo":true,"s":24.285714285714285,"axis":[-21.03204552047922,0,117.85714285714285]},{"p":[-33.403837003114056,31.071428571428573,110.7142857142857],"face":"LE","photo":true,"s":38.57142857142857,"axis":[-33.403837003114056,0,110.7142857142857]},{"p":[-45.77562848574889,30.357142857142858,103.57142857142856],"face":"LE","photo":true,"s":52.857142857142854,"axis":[-45.77562848574889,0,103.57142857142856]},{"p":[-58.14741996838372,29.642857142857142,96.4285714285714],"face":"LE","photo":true,"s":67.14285714285714,"axis":[-58.14741996838372,0,96.4285714285714]},{"p":[-70.51921145101856,28.928571428571427,89.28571428571425],"face":"LE","photo":true,"s":81.42857142857143,"axis":[-70.51921145101856,0,89.28571428571425]},{"p":[-82.8910029336534,28.214285714285715,82.14285714285711],"face":"LE","photo":true,"s":95.71428571428571,"axis":[-82.8910029336534,0,82.14285714285711]},{"p":[-95.26279441628823,27.5,74.99999999999994],"face":"LE","photo":true,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-98.82155628644789,26.56296022294938,81.16395637115531],"face":"T","photo":false,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-102.13779441628823,23.815698604072065,86.90784930203597],"face":"T","photo":false,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-104.98551265760327,19.445436482630058,91.84024198163429],"face":"T","photo":false,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-107.17064371832427,13.750000000000004,95.62499999999994],"face":"T","photo":false,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-108.54427452776294,7.117523740319321,98.00419835278966],"face":"T","photo":false,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-109.01279441628824,1.6838893488276107e-15,98.815698604072],"face":"SS","photo":true,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-97.79912236582906,1.724710908799189e-15,106.0597155399283],"face":"SS","photo":true,"s":96.66666666666667,"axis":[-83.71578903249572,0,81.66666666666663]},{"p":[-86.58545031536988,1.7655324687707674e-15,113.30373247578461],"face":"SS","photo":true,"s":83.33333333333333,"axis":[-72.1687836487032,0,88.3333333333333]},{"p":[-75.3717782649107,1.8063540287423458e-15,120.54774941164091],"face":"SS","photo":true,"s":70,"axis":[-60.621778264910695,0,94.99999999999997]},{"p":[-64.15810621445152,1.8471755887139246e-15,127.7917663474972],"face":"SS","photo":true,"s":56.666666666666664,"axis":[-49.07477288111818,0,101.66666666666664]},{"p":[-52.94443416399234,1.887997148685503e-15,135.0357832833535],"face":"SS","photo":true,"s":43.33333333333333,"axis":[-37.52776749732566,0,108.33333333333331]},{"p":[-41.73076211353317,1.9288187086570813e-15,142.2798002192098],"face":"SS","photo":true,"s":30,"axis":[-25.980762113533153,0,114.99999999999999]},{"p":[-41.19409387758599,-8.152799920729407,141.35026356774094],"face":"T","photo":false,"s":30,"axis":[-25.980762113533153,0,114.99999999999999]},{"p":[-39.620662223138076,-15.749999999999993,138.62499999999997],"face":"T","photo":false,"s":30,"axis":[-25.980762113533153,0,114.99999999999999]},{"p":[-37.11769391722129,-22.273863607376246,134.28973172441752],"face":"T","photo":false,"s":30,"axis":[-25.980762113533153,0,114.99999999999999]},{"p":[-33.85576211353316,-27.279800219209818,128.6399001096049],"face":"T","photo":false,"s":30,"axis":[-25.980762113533153,0,114.99999999999999]},{"p":[-30.057162073897864,-30.42666352810565,122.06053184332342],"face":"T","photo":false,"s":30,"axis":[-25.980762113533153,0,114.99999999999999]},{"p":[-47.631397208144115,-30.25,102.49999999999997],"face":"TE","photo":true,"s":55,"axis":[-47.631397208144115,0,102.49999999999997]},{"p":[-59.53924651018015,-29.5625,95.62499999999997],"face":"TE","photo":true,"s":68.75,"axis":[-59.53924651018015,0,95.62499999999997]},{"p":[-71.44709581221618,-28.875,88.74999999999997],"face":"TE","photo":true,"s":82.5,"axis":[-71.44709581221618,0,88.74999999999997]},{"p":[-83.3549451142522,-28.1875,81.87499999999996],"face":"TE","photo":true,"s":96.25,"axis":[-83.3549451142522,0,81.87499999999996]},{"p":[-95.26279441628823,-27.5,74.99999999999994],"face":"TE","photo":true,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-91.70403254612857,-26.562960222949382,68.83604362884459],"face":"T","photo":false,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-88.38779441628822,-23.81569860407206,63.09215069796391],"face":"T","photo":false,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-85.5400761749732,-19.44543648263006,58.159758018365594],"face":"T","photo":false,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-83.35494511425219,-13.750000000000012,54.37499999999996],"face":"T","photo":false,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-81.98131430481352,-7.117523740319317,51.99580164721023],"face":"T","photo":false,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-81.51279441628822,-5.051668046482832e-15,51.18430139592789],"face":"PS","photo":true,"s":110,"axis":[-95.26279441628823,0,74.99999999999994]},{"p":[-69.63245569916238,-5.174132726397566e-15,57.273617793404945],"face":"PS","photo":true,"s":96.66666666666667,"axis":[-83.71578903249572,0,81.66666666666663]},{"p":[-57.75211698203652,-5.296597406312302e-15,63.36293419088199],"face":"PS","photo":true,"s":83.33333333333333,"axis":[-72.1687836487032,0,88.3333333333333]},{"p":[-45.87177826491068,-5.4190620862270374e-15,69.45225058835904],"face":"PS","photo":true,"s":70,"axis":[-60.621778264910695,0,94.99999999999997]},{"p":[-33.99143954778483,-5.541526766141773e-15,75.54156698583608],"face":"PS","photo":true,"s":56.666666666666664,"axis":[-49.07477288111818,0,101.66666666666664]},{"p":[-22.111100830658984,-5.663991446056508e-15,81.63088338331313],"face":"PS","photo":true,"s":43.33333333333333,"axis":[-37.52776749732566,0,108.33333333333331]},{"p":[-10.230762113533139,-5.786456125971244e-15,87.72019978079018],"face":"PS","photo":true,"s":30,"axis":[-25.980762113533153,0,114.99999999999999]}],"speed":62,"marks":[{"kind":"dim","a":[-86.60254037844385,0,79.99999999999996],"b":[-95.26279441628823,27.5,74.99999999999994],"text":"11 m · RTK 5,5 m","dx":0,"dy":-22},{"kind":"dim","a":[-10.392304845413262,0,124],"b":[-8.660254037844386,32.5,125],"text":"13 m · RTK 10 m","dx":8,"dy":24},{"kind":"tag","at":[-58.14741996838372,29.642857142857142,96.4285714285714],"text":"LE"},{"kind":"tag","at":[-75.3717782649107,1.8063540287423458e-15,120.54774941164091],"text":"SS"},{"kind":"tag","at":[-71.44709581221618,-28.875,88.74999999999997],"text":"TE"},{"kind":"tag","at":[-45.87177826491068,-5.4190620862270374e-15,69.45225058835904],"text":"PS"}]},{"id":"exit","kind":"exit","phase":"Salida","title":"Salida","route":[{"p":[-10.230762113533139,-5.786456125971244e-15,87.72019978079018]},{"p":[-10.230762113533139,37.49999999999999,87.72019978079018]},{"p":[-10.230762113533139,39.99999999999999,87.72019978079018]},{"p":[74,128,40]}],"speed":40,"marks":[{"kind":"dim","a":[-10.230762113533139,-5.786456125971244e-15,87.72019978079018],"b":[-10.230762113533139,37.49999999999999,87.72019978079018],"text":"15 m del rotor"}]}];
const TEXT = [
 ["Calibración · 1 de 5","Buje (spinner)","Despega y colócate al frente de la turbina, a la altura del buje (la app acepta ±20 m) y a 4–6 m del spinner. Apunta el láser (o la visión) al centro del spinner y mide. Esta posición fija el rumbo del rotor y la altura de referencia.","Dron a 4–6 m del spinner · altura = buje ±20 m"],
 ["Calibración · 2 de 5","Punta de la pala 12h","Con el rotor detenido en forma de «Y» (una pala arriba, a las 12h), sube hasta la punta de esa pala y quédate a 4–6 m. El punto apuntado debe caer entre el 85 % y el 95 % del radio y a 75–90° de elevación respecto al buje; fuera de ahí la app lo rechaza (demasiado cerca de la raíz o de la punta).","4–6 m · punto apuntado a 85–95 % del radio · elevación 75–90°"],
 ["Calibración · 3 de 5","Raíz de la pala 12h","Baja por el frente de la misma pala hasta la raíz y apunta a su eje: entre 3 y 10 m del buje y a no más de 4 m del eje de la pala. Con la punta y la raíz de la pala 12h la app calcula la longitud real y el eje de las palas.","4–6 m · punto a 3–10 m del buje y ≤4 m del eje"],
 ["Calibración · 4 de 5","Punta de la pala 4h","Pala de abajo a la derecha (visto desde el frente). Vuela hasta su punta, a 4–6 m, y apunta al 85–95 % del radio. La elevación del punto debe quedar entre −45° y −15° respecto al buje, hacia el lado derecho (±45° del rumbo lateral).","4–6 m · 85–95 % del radio · elevación −45° a −15°"],
 ["Calibración · 5 de 5","Punta de la pala 8h","Igual que la anterior, pero en la pala de abajo a la izquierda. Con las tres puntas y la raíz la app obtiene el plano del rotor y verifica que las medidas sean coherentes entre sí.","4–6 m · 85–95 % del radio · elevación −45° a −15°"],
 ["Plan","Verificación y plan de vuelo","Con las cinco medidas la app deduce el eje del rotor, la raíz y las tres palas, y genera los waypoints: primero la pala de las 4h, luego la de las 12h y al final la de las 8h. Antes de despegar la misión se ajusta el brillo con fotos de prueba, se comprueba que la vuelta a casa no cruce el rotor y se sube el plan al dron.","Orden: 4h → 12h → 8h"],
 ["Inspección · pala 4h","Pala 4h: LE → SS → TE → PS","El dron recorre cuatro carriles alrededor de la pala: borde de ataque (LE) de la raíz a la punta, gira 90° en la punta, cara de succión (SS) de la punta a la raíz, gira en la raíz, borde de salida (TE) hacia la punta y cara de presión (PS) de vuelta. Se mantiene a 13 m de la pala en la raíz y 11 m en la punta (5,5 m con RTK en la punta), a 1 m/s y con una foto en cada parada.","Raíz 13 m · punta 11 m · 1 m/s · RTK: 10 m / 5,5 m"],
 ["Cambio de pala","Salto 4h → 12h","Entre palas el dron no cruza el rotor: sube en un arco sobre el eje del rotor, siempre por delante del plano de las palas y a la distancia del buje que ya traía.","Siempre al frente del plano del rotor"],
 ["Inspección · pala 12h","Pala 12h: LE → SS → TE → PS","Mismos cuatro carriles. Por detrás, el borde de salida (TE) empieza pasado el nacelle: el dron nunca pasa por detrás de la góndola ni de la raíz de la pala. En los cruces la distancia mínima a la pala es 10,5 m (5 m con RTK).","Cruces: mínimo 10,5 m · RTK: 5 m"],
 ["Cambio de pala","Salto 12h → 8h","Segundo arco por delante del rotor hasta el inicio de la pala de las 8h.","Siempre al frente del plano del rotor"],
 ["Inspección · pala 8h","Pala 8h: LE → SS → TE → PS","Última pala. En las dos caras de abajo (la PS en la pala de las 8h) el dron pasa por debajo de la pala y se echa algo más atrás para que el sensor de visión siga viendo la superficie.","Raíz 13 m · punta 11 m · fotos en cada parada"],
 ["Salida","Salida del rotor y vuelta a casa","Al terminar se aleja 15 m del plano del rotor, hace una pausa y vuelve a casa. La pantalla de vídeo se mantiene hasta que aterrices; si pierdes el enlace o fuerzas la vuelta, la altura de RTH se ajusta para no cruzar el rotor.","15 m fuera del rotor · luego RTH"]
];
const stage = document.querySelector(".iso-stage");
const svg = stage.querySelector("svg");
const gBack = svg.querySelector("#gBack"), gFront = svg.querySelector("#gFront");
const readout = document.querySelector("[data-readout]");
const panel = document.querySelector(".op-panel");
const chips = [...document.querySelectorAll(".op-chip")];
const still = matchMedia("(prefers-reduced-motion: reduce)");
const pt = (p) => iso(p, P);
const d2 = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
const ease = (t) => t * t * (3 - 2 * t);
const PATH_SPEED = 62;
const S = solidSvg, Ln = lineSvg, D = dotsSvg;

// Longitudes y tiempos por paso
for (const s of STEPS) {
  if (s.route) {
    let acc = 0; s.cum = [0];
    for (let i = 1; i < s.route.length; i++) { acc += d2(s.route[i].p, s.route[i - 1].p); s.cum.push(acc); }
    s.len = acc;
    s.dur = s.dur || Math.max(3.2, acc / (s.speed || PATH_SPEED));
    s.fly = s.dur; s.hold = 0;
  } else { s.fly = 3.4; s.hold = 2.6; s.dur = s.fly + s.hold; }
}
// punto inicial de cada paso = fin del anterior
let prev = [HOME[0], HOME[1], 6];
for (const s of STEPS) {
  s.from = prev;
  s.end = s.route ? s.route[s.route.length - 1].p : s.pos;
  prev = s.end;
}
const yawFacing = (a, b, keep) => { const dx = b[0] - a[0], dy = b[1] - a[1]; return Math.hypot(dx, dy) < 0.5 ? keep : Math.atan2(dy, dx); };

function routeAt(s, u) {
  const dist = s.len * u;
  let i = 1; while (i < s.cum.length - 1 && s.cum[i] < dist) i++;
  const span = s.cum[i] - s.cum[i - 1] || 1, t = Math.min(1, Math.max(0, (dist - s.cum[i - 1]) / span));
  return { p: lerp(s.route[i - 1].p, s.route[i].p, t), idx: i - 1, t };
}

let cur = 0, clock = 0, playing = !still.matches, visible = false, frame = 0, last = 0, idle = 0;
let yaw = -Math.PI / 2, shownYaw = yaw;
let pos = prev.slice(), trail = [], marksAlpha = 0;
const done = [];

function enter(i, instant) {
  cur = i; clock = 0; marksAlpha = 0;
  chips.forEach((c, j) => { c.dataset.on = j === i ? "1" : "0"; c.dataset.done = j < i ? "1" : "0"; });
  const [phase, title, body, nums] = TEXT[i];
  panel.querySelector(".op-phase").textContent = phase;
  panel.querySelector(".op-title").textContent = title;
  panel.querySelector(".op-body").textContent = body;
  panel.querySelector(".op-nums").textContent = nums;
  readout.textContent = (i + 1) + "/" + STEPS.length + " · " + title;
  trail = [];
  if (instant) { clock = STEPS[i].dur; }
}

function position(s) {
  if (s.route) {
    const u = ease(Math.min(1, clock / s.fly));
    const r = routeAt(s, s.kind === "blade" || s.kind === "transfer" ? Math.min(1, clock / s.fly) : u);
    return { p: r.p, idx: r.idx, face: s.route[Math.min(r.idx + 1, s.route.length - 1)].face };
  }
  const u = ease(Math.min(1, clock / s.fly));
  const lift = Math.sin(u * Math.PI) * 10;
  const p = lerp(s.from, s.pos, u); p[2] += lift * (1 - u);
  return { p, idx: -1, face: null };
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

function marksSvg(s, alpha, drone) {
  const out = [];
  for (const m of s.marks || []) {
    if (m.kind === "dim") {
      const a = pt(m.a), b = pt(m.b);
      out.push(Ln(segment(m.a, m.b, P), { tone: "red", dotted: true }));
      out.push(D([a, b], { size: 0.8, tone: "hi" }));
      out.push(chip((a[0] + b[0]) / 2 + (m.dx === undefined ? 26 : m.dx), (a[1] + b[1]) / 2 + (m.dy === undefined ? -8 : m.dy), m.text, "red"));
    } else if (m.kind === "bracket") {
      out.push('<path class="op-zone" d="' + segment(m.a, m.b, P) + '"/>');
    } else if (m.kind === "ray") {
      out.push(Ln(segment(m.a, m.b, P), { tone: "green", dotted: true }));
    } else if (m.kind === "tag") {
      const a = pt(m.at); out.push(chip(a[0], a[1], m.text, "mid"));
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
  // devuelve [atrás, delante] como trazos; cada tramo va al plano de su punto medio
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

function render(time) {
  const s = STEPS[cur];
  const where = position(s);
  const p = where.p;
  // rumbo: hacia el siguiente punto o hacia el objetivo
  let want = yaw;
  if (s.kind === "measure") want = -Math.PI / 2;
  else if (s.route) {
    const nxt = s.route[Math.min(where.idx + 1, s.route.length - 1)];
    want = yawFacing(p, nxt.axis || HUB, yaw);
  }
  let dy = want - shownYaw; while (dy > Math.PI) dy -= 2 * Math.PI; while (dy < -Math.PI) dy += 2 * Math.PI;
  shownYaw += dy * 0.12; yaw = want;
  marksAlpha = Math.min(1, marksAlpha + 0.04);

  const behind = p[1] < -1;
  // rastro: lo recorrido de los pasos anteriores + el actual
  const parts = [], partsFront = [];
  const doneBack = done.map((d) => d[0]).join(""), doneFront = done.map((d) => d[1]).join("");
  let curBack = "", curFront = "";
  if (s.route) {
    const rr = pathSvgs([s.route.map((q) => q.p)], where.idx + 1);
    curBack = rr[0]; curFront = rr[1];
    const tail = "M" + pt(s.route[where.idx].p).map((v) => v.toFixed(1)).join(" ") + "L" + pt(p).map((v) => v.toFixed(1)).join(" ");
    if ((s.route[where.idx].p[1] + p[1]) / 2 < 0) curBack += tail; else curFront += tail;
  }
  const flat = s.route ? [s.route.map((q) => q.p)] : [];
  const ghost = pathSvgs(s.kind === "plan" ? STEPS.filter((x) => x.kind === "blade").map((x) => x.route.map((q) => q.p)) : flat);
  const gBackSvg = '<path class="op-ghost" d="' + ghost[0] + '"/><path class="op-trail op-done" d="' + doneBack + '"/><path class="op-trail" d="' + curBack + '"/>';
  const gFrontGhost = '<path class="op-ghost" d="' + ghost[1] + '"/><path class="op-trail op-done" d="' + doneFront + '"/><path class="op-trail" d="' + curFront + '"/>';

  // fotos
  let photos = "";
  if (s.kind === "blade") {
    const flash = [];
    s.route.forEach((q, i) => {
      if (!q.photo) return;
      const q2 = pt(q.p), got = i <= where.idx;
      photos += '<circle class="op-photo" data-got="' + (got ? 1 : 0) + '" cx="' + q2[0].toFixed(1) + '" cy="' + q2[1].toFixed(1) + '" r="' + (got ? 1.5 : 1) + '"/>';
    });
    const q = s.route[Math.max(0, where.idx)];
    if (q && q.axis) photos += Ln(segment(p, q.axis, P), { tone: "mid", dotted: true });
  }

  const drone = droneSvg(p, shownYaw, time);
  const shadow = '<path class="iso-halo" d="' + haloOf({ x: p[0] - 6, y: p[1] - 4.5, w: 12, d: 9, r: 4 }, 0, 0.1, P) + '"/>' + Ln(segment([p[0], p[1], 0], [p[0], p[1], Math.max(0, p[2] - 1)], P), { tone: "faint", dotted: true });
  const sight = (s.kind === "measure" && clock > s.fly - 0.2) ? '<g class="op-laser">' + Ln(segment(p, s.target, P), { tone: "red" }) + '<circle cx="' + pt(s.target)[0].toFixed(1) + '" cy="' + pt(s.target)[1].toFixed(1) + '" r="' + (3 + 1.6 * Math.sin(time * 9)).toFixed(1) + '" class="op-hit"/></g>' : "";

  gBack.innerHTML = gBackSvg + shadow + (behind ? drone : "");
  gFront.innerHTML = gFrontGhost + photos + marksSvg(s, marksAlpha) + sight + (behind ? "" : drone);
  if (s.kind === "blade" && where.face && readout) {
    const names = { LE: "borde de ataque · raíz→punta", SS: "cara de succión · punta→raíz", TE: "borde de salida · raíz→punta", PS: "cara de presión · punta→raíz", T: "giro alrededor de la pala" };
    readout.textContent = s.title.split(" · ")[0] + " · " + (names[where.face] || "");
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
chips.forEach((c, i) => c.addEventListener("click", () => { playing = false; setPlay(); jump(i); }));
const playBtn = document.querySelector(".op-play");
function setPlay() { playBtn.textContent = playing ? "Pausa" : "Reproducir"; playBtn.setAttribute("aria-pressed", String(playing)); }
playBtn.addEventListener("click", () => { playing = !playing; setPlay(); run(); });
document.querySelector(".op-prev").addEventListener("click", () => { playing = false; setPlay(); jump(Math.max(0, cur - 1)); });
document.querySelector(".op-next").addEventListener("click", () => { playing = false; setPlay(); jump(Math.min(STEPS.length - 1, cur + 1)); });
stage.addEventListener("keydown", (e) => {
  if (e.key === "ArrowRight") { playing = false; setPlay(); jump(Math.min(STEPS.length - 1, cur + 1)); }
  else if (e.key === "ArrowLeft") { playing = false; setPlay(); jump(Math.max(0, cur - 1)); }
  else if (e.key === " ") { e.preventDefault(); playing = !playing; setPlay(); run(); }
  else return;
  e.preventDefault();
});
new IntersectionObserver((entries) => { visible = entries[entries.length - 1].isIntersecting; if (visible) run(); }, { rootMargin: "120px 0px" }).observe(stage);
const startAt = Number(new URLSearchParams(location.search).get("step"));
if (Number.isFinite(startAt) && startAt > 0) { playing = false; jump(Math.min(STEPS.length - 1, startAt - 1)); clock = STEPS[cur].dur * (Number(new URLSearchParams(location.search).get("t")) || 1); }
else enter(0);
setPlay();
render(0);
window.__op = { jump, get step() { return cur; }, set clock(v) { clock = v; }, render };
