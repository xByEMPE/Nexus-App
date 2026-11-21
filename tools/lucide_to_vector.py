"""Convierte iconos SVG de Lucide (ISC) a VectorDrawable de Android.

Uso: python tools/lucide_to_vector.py <dir_svg_lucide> <app/src/main/res/drawable>

Cada icono queda como ic_nx_<nombre>.xml: 24dp, trazo blanco de 1.6, sin relleno,
extremos y uniones redondeados. El color final se da con android:tint / app:tint.
"""
import os
import sys
import xml.etree.ElementTree as ET

# nombre Lucide -> nombre del recurso (sin prefijo ic_nx_)
ICONS = {
    "arrow-left": "back", "user": "user", "mail": "mail", "lock": "lock", "eye": "eye",
    "eye-off": "eye_off", "settings": "settings", "refresh-cw": "refresh", "rotate-ccw": "reset",
    "chevron-down": "chevron_down", "check": "check", "triangle-alert": "alert",
    "log-out": "logout", "plus": "plus", "shield": "shield", "fan": "fan", "cpu": "cpu",
    "satellite": "satellite", "battery": "battery", "gamepad-2": "gamepad", "wifi": "signal",
    "camera": "camera", "map-pin": "pin", "download": "download", "history": "history",
    "file-up": "file_up", "database": "database", "x": "close",
}

STROKE_WIDTH = "1.6"


def f(v):
    return float(v or 0)


def num(v):
    s = ("%.3f" % v).rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def ellipse_path(cx, cy, rx, ry):
    return (f"M{num(cx - rx)},{num(cy)}"
            f"a{num(rx)},{num(ry)} 0 1,0 {num(2 * rx)},0"
            f"a{num(rx)},{num(ry)} 0 1,0 {num(-2 * rx)},0Z")


def rect_path(x, y, w, h, rx, ry):
    if rx == 0 and ry == 0:
        return f"M{num(x)},{num(y)}h{num(w)}v{num(h)}h{num(-w)}Z"
    rx = min(rx or ry, w / 2)
    ry = min(ry or rx, h / 2)
    return (f"M{num(x + rx)},{num(y)}h{num(w - 2 * rx)}"
            f"a{num(rx)},{num(ry)} 0 0 1 {num(rx)},{num(ry)}v{num(h - 2 * ry)}"
            f"a{num(rx)},{num(ry)} 0 0 1 {num(-rx)},{num(ry)}h{num(-(w - 2 * rx))}"
            f"a{num(rx)},{num(ry)} 0 0 1 {num(-rx)},{num(-ry)}v{num(-(h - 2 * ry))}"
            f"a{num(rx)},{num(ry)} 0 0 1 {num(rx)},{num(-ry)}Z")


def poly_path(points, close):
    pts = points.replace(",", " ").split()
    pairs = [(pts[i], pts[i + 1]) for i in range(0, len(pts) - 1, 2)]
    d = "M" + " L".join(f"{x},{y}" for x, y in pairs)
    return d + ("Z" if close else "")


def element_to_path(el):
    tag = el.tag.split("}")[-1]
    a = el.attrib
    if tag == "path":
        return a["d"]
    if tag == "circle":
        r = f(a.get("r"))
        return ellipse_path(f(a.get("cx")), f(a.get("cy")), r, r)
    if tag == "ellipse":
        return ellipse_path(f(a.get("cx")), f(a.get("cy")), f(a.get("rx")), f(a.get("ry")))
    if tag == "rect":
        return rect_path(f(a.get("x")), f(a.get("y")), f(a.get("width")), f(a.get("height")),
                         f(a.get("rx")), f(a.get("ry")))
    if tag == "line":
        return f"M{a.get('x1', '0')},{a.get('y1', '0')} L{a.get('x2', '0')},{a.get('y2', '0')}"
    if tag == "polyline":
        return poly_path(a["points"], False)
    if tag == "polygon":
        return poly_path(a["points"], True)
    return None


def convert(svg_file):
    root = ET.parse(svg_file).getroot()
    paths = [p for p in (element_to_path(el) for el in root.iter()) if p]
    body = "\n".join(
        f'    <path\n        android:pathData="{d}"\n'
        f'        android:fillColor="@android:color/transparent"\n'
        f'        android:strokeColor="#FFFFFFFF"\n'
        f'        android:strokeWidth="{STROKE_WIDTH}"\n'
        f'        android:strokeLineCap="round"\n'
        f'        android:strokeLineJoin="round" />'
        for d in paths)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Icono Lucide (licencia ISC, ver docs/licencias/lucide.txt) -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp"\n    android:height="24dp"\n'
            '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
            f"{body}\n</vector>\n")


def main(src, dst):
    for lucide, res in ICONS.items():
        out = os.path.join(dst, f"ic_nx_{res}.xml")
        with open(out, "w", encoding="utf-8") as fh:
            fh.write(convert(os.path.join(src, f"{lucide}.svg")))
        print(out)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
