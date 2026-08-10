"""
BAS launcher / Play Store icon — ONE drawing, every output.

The home-screen icon and the Play listing icon drifted apart once (the
launcher still carried the old scoring artwork while the store showed the BAS
mark), which is exactly what this generator exists to prevent: the same
function draws the Play square, the adaptive-icon foreground at every density,
and the themed monochrome layer.

Composition — the two halves of the app in one mark: concentric scoring rings
with a gold bull (scoring), a gold reticle with mil ticks (ballistics), and a
tight red group just off centre (the shot). Flat shapes, no gradient, heavy
strokes, generous negative space, so it survives being 48 px on a home screen.

Everything is parameterised on R, the artwork radius, so the drawing is
identical at 512 px and at the ~66% safe zone an adaptive icon may occupy.

    python3 tools/generate_icons.py
"""
import cairo, math, os

GREEN = (0x2E/255, 0x40/255, 0x34/255)
GOLD  = (0xC9/255, 0xA2/255, 0x4B/255)
CREAM = (0xEA/255, 0xEF/255, 0xE4/255)
RED   = (0xD3/255, 0x2F/255, 0x2F/255)
REDDK = (0x8E/255, 0x1B/255, 0x1B/255)

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES  = os.path.join(HERE, "app/src/main/res")

# Adaptive icons are 108dp with only the central 72dp guaranteed visible, so
# the artwork is drawn at 66% of the canvas; the Play square is full bleed.
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def draw(ctx, cx, cy, R, mono=False):
    """The mark. mono=True flattens it to one opaque colour for themed icons."""
    def col(c):
        ctx.set_source_rgb(*( (1, 1, 1) if mono else c ))

    # scoring rings
    for k, f in enumerate((1.00, 0.78, 0.56)):
        col(CREAM)
        ctx.set_line_width(R * 0.075)
        ctx.arc(cx, cy, R * f, 0, 2 * math.pi)
        ctx.stroke()

    # gold accent ring + bull
    col(GOLD)
    ctx.set_line_width(R * 0.085)
    ctx.arc(cx, cy, R * 0.36, 0, 2 * math.pi)
    ctx.stroke()
    ctx.arc(cx, cy, R * 0.16, 0, 2 * math.pi)
    ctx.fill()

    # reticle: arms with a centre gap, plus mil ticks
    gap, reach = R * 0.26, R * 1.30
    col(GOLD)
    ctx.set_line_width(R * 0.055)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        ctx.move_to(cx + dx * gap, cy + dy * gap)
        ctx.line_to(cx + dx * reach, cy + dy * reach)
    ctx.stroke()
    ctx.set_line_width(R * 0.038)
    for i in (1, 2, 3):
        o = gap + (reach - gap) * i / 4
        t = R * 0.075
        ctx.move_to(cx - o, cy - t); ctx.line_to(cx - o, cy + t)
        ctx.move_to(cx + o, cy - t); ctx.line_to(cx + o, cy + t)
        ctx.move_to(cx - t, cy - o); ctx.line_to(cx + t, cy - o)
        ctx.move_to(cx - t, cy + o); ctx.line_to(cx + t, cy + o)
    ctx.stroke()

    # a tight group just off centre — asymmetric, so it reads as shots
    for gx, gy in ((0.20, -0.34), (0.36, -0.20), (0.27, -0.27)):
        ctx.set_source_rgb(*((1, 1, 1) if mono else RED))
        ctx.arc(cx + R * gx, cy + R * gy, R * 0.115, 0, 2 * math.pi)
        ctx.fill_preserve()
        ctx.set_source_rgb(*((1, 1, 1) if mono else REDDK))
        ctx.set_line_width(R * 0.022)
        ctx.stroke()


def play_icon(path, size=512):
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    ctx = cairo.Context(surf)
    ctx.set_source_rgb(*GREEN)
    ctx.paint()
    draw(ctx, size / 2, size / 2, size * 0.30)
    surf.write_to_png(path)


def foreground(path, px, mono=False):
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, px, px)
    ctx = cairo.Context(surf)                    # transparent background
    draw(ctx, px / 2, px / 2, px * 0.205, mono)  # inside the 72dp safe zone
    surf.write_to_png(path)


if __name__ == "__main__":
    out = os.path.join(HERE, "play")
    os.makedirs(out, exist_ok=True)
    play_icon(os.path.join(out, "bas_play_icon_512.png"))
    for d, px in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + d)
        os.makedirs(folder, exist_ok=True)
        foreground(os.path.join(folder, "ic_launcher_foreground.png"), px)
        foreground(os.path.join(folder, "ic_launcher_monochrome.png"), px, mono=True)
    print("icons written: play/bas_play_icon_512.png + mipmap-* foreground/monochrome")
