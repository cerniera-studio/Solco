"""Deserto (25 Nov 2026): the numpy prototype Scene.genDeserto was tuned from. Needs tools/three.py and tools/deserto.py helpers from the session; kept for the record."""
Mirrors Scene.genDeserto (Kotlin) line for line where it matters."""
import math, sys
import numpy as np
from PIL import Image
sys.path.insert(0, '/home/claude/sim')
from three import Rng
from deserto import mask, render

def layout(w, h, dp, cover):
    if cover:
        dh = .30 * w; top = .12 * h; lines = 2; bh = dh * 2 * 1.02
        sy = top + bh - dh * .45
    else:
        dh = .19 * w; top = .13 * h; lines = 1; bh = dh * 1.02
        sy = top + bh - dh * .42
    return dict(dh=dh, top=top, lines=lines, cx=w * .5, bh=bh, sx=w * .5, sy=sy, hy=h * (.57 if cover else .60))

class Dune:
    """World: X across, Z away from the eye, height up. One barchan whose crest summit projects to (sx, sy)."""
    def __init__(s, w, h, dp, lay, morning, cover):
        s.w, s.h, s.dp = w, h, dp
        s.F = (h if cover else w) * .9           # focal length in px
        s.E = 1.6                                # eye height (m)
        s.Z0 = 60.0                              # the summit's distance (m)
        s.hy = lay['hy']; s.cx = lay['sx']
        s.Hd = s.E + (s.hy - lay['sy']) * s.Z0 / s.F   # tall enough to reach the clock
        s.Lx = s.Z0 * (w * (.55 if cover else .40)) / s.F   # half-width of the crest across the view
        s.wind = 1.0 if morning else -1.0        # which way the crest leans
        s.lx = -1.0 if morning else 1.0         # light from the left in the morning
    def height(s, X, Z):
        u = X / s.Lx
        hc = s.Hd * math.exp(-(u * u) * 1.15)                    # the crest falls away to the sides
        # The crest runs obliquely across the view, so one face takes the light and the other the shade
        zc = s.Z0 + .85 * X * s.wind + 6.0 * u * u + 1.5 * math.sin(u * 2.7)
        dz = Z - zc
        wlen = min(s.Hd * 2.0, s.Z0 * .7)
        if dz < 0:                                               # windward, towards us: long and gentle
            f = max(0.0, 1 + dz / wlen)
            f = f ** 1.35
            rip = .035 * math.sin((Z * 1.9 + X * .55)) * min(1.0, (1 - f) * 5) * f
        else:                                                    # the slip face, away: steep
            f = max(0.0, 1 - dz / (s.Hd * 1.48)); rip = 0   # the slip face at sand's angle of repose, about 34 degrees
        main = hc * f + rip * hc
        far = .9 * math.sin(X / 23 + Z / 41) + .6 * math.sin(X / 9.7 - Z / 17) + .4 * math.sin(Z / 7.3 + X / 31)
        # The floor: low swells far off and wind ripples close by, fading with distance
        floor = max(0.0, far) * min(1.0, Z / 120) + .05 * math.sin(Z * 2.3 + X * .45 + math.sin(X * .3)) * math.exp(-Z / 40)
        near = 1.1 * max(0.0, math.sin(X / 6.5 + 1.3) * math.exp(-((Z - 9) / 4.5) ** 2))
        return max(main, floor, near)
    def light(s, X, Z, e=.05):
        hx = (s.height(X + e, Z) - s.height(X - e, Z)) / (2 * e)
        hz = (s.height(X, Z + e) - s.height(X, Z - e)) / (2 * e)
        n = np.array([-hx, 1.0, -hz]); n /= np.linalg.norm(n)
        L = np.array([s.lx * .97, .12, -.22]); L /= np.linalg.norm(L)
        return max(0.0, float(n @ L))

def render_light(w, h, dp, lay, morning, cover):
    d = Dune(w, h, dp, lay, morning, cover)
    cell = 2 * dp
    cw, ch = int(w / cell) + 1, int(h / cell) + 1
    B = np.full((ch, cw), -1.0, np.float32)   # -1: sky
    D = np.zeros((ch, cw), np.float32)        # 1 where the cell is the great dune, 0 the floor
    sky = np.zeros(cw, np.float32)
    for i in range(cw):
        x = (i + .5) * cell
        ymax = h
        Z = 3.0
        while Z < 900 and ymax > 0:
            X = (x - d.cx) * Z / d.F
            H = d.height(X, Z)
            ys = d.hy + (d.E - H) * d.F / Z
            if ys < ymax:
                b = d.light(X, Z)
                j0 = max(0, int(ys / cell)); j1 = min(ch, int(ymax / cell) + 1)
                fresh = B[j0:j1, i] < 0
                B[j0:j1, i] = np.where(fresh, b, B[j0:j1, i])
                D[j0:j1, i] = np.where(fresh, 1.0 if H > 2.0 else 0.0, D[j0:j1, i])
                ymax = ys
            Z *= 1.012
        sky[i] = ymax
    # Exposed as a camera would be: for the brightest sand, so the lit face is bright and the shade dark
    lit = B[(B >= 0) & (D > 0)]
    top = float(np.percentile(lit, 98)) if lit.size else 1.0
    B = np.where(B >= 0, np.minimum(1.0, B / max(top, 1e-3)), B)
    return d, B, sky, cell

def deserto(w, h, dp, cover, text, morning=False, night=False):
    lay = layout(w, h, dp, cover)
    d, B, sky, cell = render_light(w, h, dp, lay, morning, cover)
    cap = int((w / dp) * (h / dp) / 4.2)
    rng = Rng(151); pts = []; guard = 0
    budget = int(cap * .88)
    ch, cw = B.shape
    # A fixed number of tries, so light sets how much grain there is: shade stays dark
    while len(pts) < budget and guard < cap * 1.7:
        guard += 1
        x = rng.next() * w; y = rng.next() * h
        i, j = min(int(x / cell), cw - 1), min(int(y / cell), ch - 1)
        b = B[j, i]
        top = sky[i]
        if b < 0:
            dens = (.006 if night else .004) + (.03 if night else .05) * math.exp(-(top - y) / (80 * dp))
            c = 0 if night and rng.next() < .35 else 2
        else:
            dens = .02 + .98 * b ** 1.1
            c = 0 if b > .3 else 2
            if b < .12 and y - top < 3.2 * dp: dens, c = .97, 1      # the backlit edge catches a rim of light
        if rng.next() < dens: pts.append((x, y, c))
    m = mask(w, h, dp, lay, text)
    clock = []; g = 0
    while len(clock) < cap - budget and g < cap * 12:
        g += 1
        x = rng.next() * w; y = lay['top'] + rng.next() * lay['bh'] * 1.25
        xi, yi = int(x), int(y)
        if yi >= h or m[yi, xi] < 128: continue
        if y > sky[min(int(x / cell), cw - 1)] - 1.5 * dp: continue
        if rng.next() < .9: clock.append((x, y, 0))
    return pts, clock

if __name__ == '__main__':
    ims = []
    for (w, h, cover, text, morning, night) in [(1080, 2520, True, '17\n33', False, False), (1080, 2520, True, '06\n48', True, True), (2256, 2504, False, '17:33', False, False)]:
        p, c = deserto(w, h, 2.625, cover, text, morning, night)
        im = render(p + c, w, h, '')
        ims.append(im.resize((int(w * 700 / h), 700), Image.LANCZOS))
    W = sum(i.width for i in ims) + 40
    out = Image.new('RGB', (W, 700), (40, 40, 40)); x = 0
    for i in ims: out.paste(i, (x, 0)); x += i.width + 20
    out.save('/tmp/deserto3d.png')
