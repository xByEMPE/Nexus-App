"""Genera drawable-nodpi/turbine_rotor.png a partir de nexus_splash/turbine.jpg.

Recorta el rotor (franja superior del 66 %) y vuelve transparente el fondo blanco.
Uso (desde la raíz del proyecto): python tools/make_turbine_rotor.py
"""
from PIL import Image

SRC = "nexus_splash/turbine.jpg"
DST = "app/src/main/res/drawable-nodpi/turbine_rotor.png"


def main():
    im = Image.open(SRC).convert("RGBA")
    w, h = im.size
    im = im.crop((0, 0, w, int(h * 0.66)))
    px = im.load()
    for y in range(im.size[1]):
        for x in range(w):
            r, g, b, _ = px[x, y]
            lo, hi = min(r, g, b), max(r, g, b)
            if hi - lo < 14:
                if lo > 235:
                    px[x, y] = (r, g, b, 0)
                elif lo > 215:
                    px[x, y] = (r, g, b, int((235 - lo) / 20 * 255))
    im.save(DST, optimize=True)
    print(DST, im.size)


if __name__ == "__main__":
    main()
