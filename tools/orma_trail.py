"""Orma prototype (27 Nov 2026): today's steps pressed into sand as a trail of footprints. A numpy port of Scene.genOrma, for tuning off the phone."""
import math, sys
import numpy as np
from PIL import Image

class Rng:
    def __init__(s, seed): s.s = seed
    def next(s):
        s.s = (s.s * 16807) % 2147483647
        return (s.s - 1) / 2147483646.0

def sample_day(now_min=17 * 60 + 40):
    """Per-minute steps: a walk to the station, a lunch walk, an evening walk, pottering between."""
    r = Rng(11); m = np.zeros(1440)
    def walk(a, b, spm):
        for t in range(a, b): m[t] += spm * (0.85 + 0.3 * r.next())
    walk(7 * 60 + 5, 7 * 60 + 12, 40)
    walk(8 * 60 + 10, 8 * 60 + 31, 112)
    for t in range(9 * 60, 12 * 60, 37): m[t] += 30 + 40 * r.next()
    walk(12 * 60 + 30, 12 * 60 + 52, 104)
    for t in range(13 * 60, 17 * 60, 29): m[t] += 25 + 30 * r.next()
    walk(17 * 60 + 5, 17 * 60 + 14, 150)   # a run for the train
    walk(17 * 60 + 14, 17 * 60 + 30, 108)
    m[now_min:] = 0
    return m, now_min

def catmull(pts, step):
    out = []
    P = [pts[0]] + pts + [pts[-1]]
    for i in range(1, len(P) - 2):
        p0, p1, p2, p3 = P[i - 1], P[i], P[i + 1], P[i + 2]
        seg = math.hypot(p2[0] - p1[0], p2[1] - p1[1])
        n = max(2, int(seg / step))
        for k in range(n):
            t = k / n; t2 = t * t; t3 = t2 * t
            x = .5 * (2 * p1[0] + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
            y = .5 * (2 * p1[1] + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
            out.append((x, y))
    out.append(pts[-1])
    return out

def path(gw, gh, dp, seed):
    """A wandering walk from the bottom edge up the glass, sweeping side to side like a walk along a beach."""
    r = Rng(seed % 2000000 + 17)
    top = gh * .07; bot = gh * .95
    rows = max(2, int((bot - top) / (118 * dp)))
    sp = (bot - top) / rows
    side = 0 if r.next() < .5 else 1
    pts = [(gw * (.34 + .32 * r.next()), gh + 30 * dp)]
    y = bot
    for i in range(rows + 1):
        reach = .06 + .22 * r.next()  # each sweep reaches its own distance across
        pts.append((gw * (reach if side == 0 else 1 - reach), y + (r.next() - .5) * sp * .4))
        if i < rows:
            pts.append((gw * (.36 + .28 * r.next()), y - sp * (.35 + .3 * r.next())))
        side ^= 1; y -= sp
    return catmull(pts, 1.0 * dp)

def prints(day, now_min, line, dp):
    """Footprints along the line: one per [per] steps, the stride from the pace, a pair side by side where you stood."""
    L = np.zeros(len(line))
    for i in range(1, len(line)): L[i] = L[i - 1] + math.hypot(line[i][0] - line[i - 1][0], line[i][1] - line[i - 1][1])
    total = day.sum()
    def lay(per):
        out = []; acc = 0.0; s = 20 * dp; last = -999; foot = 1
        for t in range(1440):
            if day[t] <= 0: continue
            acc += day[t]
            while acc >= per:
                acc -= per
                spm = day[t]
                stride = (26 if spm < 60 else 33 if spm < 100 else 37 if spm < 130 else 47) * dp
                run = spm >= 130
                if last >= 0 and t - last > 15 and out:
                    # Feet together where you stood: the other foot beside the last print
                    px = out[-1]
                    out.append((px[0], -px[1], px[2], px[3], True))
                    s += 22 * dp
                s += stride
                out.append((s, foot, (now_min - t) / 60.0, run, False))
                foot = -foot; last = t
        return out, s
    per = 100
    while True:
        out, s = lay(per)
        if s < L[-1] - 10 * dp or per > 5000: break
        per += 50
    res = []
    for (s, foot, age, run, stand) in out:
        i = min(len(L) - 2, int(np.searchsorted(L, s)))
        x, y = line[i]; x2, y2 = line[i + 1]
        a = math.atan2(y2 - y, x2 - x)
        res.append((x, y, a, foot, age, run, stand))
    return res, per, total

def foot_shape(u, v, side):
    """Hollow of one footprint in its own frame (dp units, u forward, v to the right), 0..1."""
    out = side  # +1: outward is to the right
    def ell(cu, cv, ru, rv):
        e = ((u - cu) / ru) ** 2 + ((v - cv) / rv) ** 2
        return np.clip((1.0 - e) * 2.2, 0, 1)
    h = np.maximum(ell(-5.0, .15 * out, 2.5, 2.2), ell(1.2, -.35 * out, 2.9, 3.0))
    h = np.maximum(h, ell(-1.9, 1.55 * out, 3.6, .95))  # the outer edge; the arch lifted on the inside
    toes = [(5.15, -1.75, 1.2), (5.55, -.25, .78), (5.3, .85, .7), (4.85, 1.8, .64), (4.2, 2.6, .58)]
    for (cu, cv, rr) in toes:
        h = np.maximum(h, ell(cu, cv * out, rr, rr) * .9)
    return h

def blur(a, r):
    from scipy.ndimage import uniform_filter
    for _ in range(3): a = uniform_filter(a, 2 * r + 1, mode='nearest')
    return a

def gen(w, h, dp, day, now_min, seed=20785):
    cell = .75 * dp
    cw = int(w / cell) + 2; ch = int(h / cell) + 2
    hol = np.zeros((ch, cw)); fresh = np.zeros((ch, cw)); push = np.zeros((ch, cw))
    line = path(w, h, dp, seed)
    pr, per, total = prints(day, now_min, line, dp)
    F = 2.0
    for (x, y, a, foot, age, run, stand) in pr:
        depth = .55 + .45 * math.exp(-age / 10.0)
        rec = age < 1.0
        # The foot sits to its side of the line
        nx, ny = -math.sin(a), math.cos(a)  # to the right of heading
        off = 6.5 * dp * foot
        fx, fy = x + nx * off, y + ny * off
        R = 9 * F * dp
        i0 = max(0, int((fx - R) / cell)); i1 = min(cw - 1, int((fx + R) / cell))
        j0 = max(0, int((fy - R) / cell)); j1 = min(ch - 1, int((fy + R) / cell))
        if i1 <= i0 or j1 <= j0: continue
        I, J = np.meshgrid(np.arange(i0, i1 + 1), np.arange(j0, j1 + 1))
        dx = (I + .5) * cell - fx; dy = (J + .5) * cell - fy
        u = (dx * math.cos(a) + dy * math.sin(a)) / (dp * F)
        v = (-dx * math.sin(a) + dy * math.cos(a)) / (dp * F)
        # Each foot turns out a little, as feet do
        tu = u * math.cos(.09 * foot) + v * math.sin(.09 * foot)
        tv = -u * math.sin(.09 * foot) + v * math.cos(.09 * foot)
        sh = foot_shape(tu, tv, foot) * depth
        if run:  # a running print is mostly forefoot, the heel barely down, sand thrown behind the toes
            sh *= np.clip((tu + 6) / 5, .25, 1)
        hol[j0:j1 + 1, i0:i1 + 1] = np.maximum(hol[j0:j1 + 1, i0:i1 + 1], sh)
        if rec: fresh[j0:j1 + 1, i0:i1 + 1] = np.maximum(fresh[j0:j1 + 1, i0:i1 + 1], sh)
        # Push-off: sand heaped just behind the ball, more when brisk
        pk = np.exp(-(((tu - (-.6)) / 1.6) ** 2 + (tv / 3.6) ** 2)) * (.55 if run else .25) * depth
        push[j0:j1 + 1, i0:i1 + 1] += pk
    broad = blur(hol.copy(), max(1, int(1.5 * dp / cell)))
    rim = np.clip(broad * 2.4 - hol * 1.5, 0, 1)
    rimF = np.clip(blur(fresh.copy(), max(1, int(1.5 * dp / cell))) * 2.4 - fresh * 1.5, 0, 1)
    # Low light from one side: the hollow's far wall catches it, the near wall falls into shadow
    gyh, gxh = np.gradient(blur(hol.copy(), 1), cell)
    lx, ly = .8, .6
    lit = np.clip((gxh * lx + gyh * ly) * dp * 4.0, -1, 1)
    cap = min(140000, int((w / dp) * (h / dp) / 4.2))
    rng = np.random.default_rng(5)
    N = cap * 12
    xs = rng.random(N) * w; ys = rng.random(N) * h
    q = (np.clip((ys / cell).astype(int), 0, ch - 1), np.clip((xs / cell).astype(int), 0, cw - 1))
    ho = hol[q]; ri = rim[q]; rf = rimF[q]; li = lit[q]
    ph = ((ys + 4 * dp * np.sin(xs / (70 * dp)) + 2 * dp * np.sin(xs / (23 * dp) + 1)) / (11 * dp)) % 1.0
    ripple = np.where(ph < .7, (ph / .7) ** 1.4, (1 - (ph - .7) / .3) ** 2.6)
    dens = (.15 + .11 * ripple) * (1 - ho) ** 3 + .015 * ho + 1.15 * ri * (1 - ho) * (1 - .6 * np.clip(-li, 0, 1)) + .75 * np.clip(li, 0, 1) * ho
    keep = rng.random(N) < dens
    idx = np.nonzero(keep)[0][:cap]
    col = np.where((rf[idx] > .2) & (li[idx] > -.2), 1, np.where((ri[idx] > .2) | (li[idx] > .3) | (ripple[idx] > .75), 0, 2))
    return xs[idx], ys[idx], col, per, total, len(pr)

def render(path_out, w, h, dp, fin, day, now_min):
    ground, grains, ga = fin
    xs, ys, col, per, total, n = gen(w, h, dp, day, now_min)
    img = np.zeros((h, w, 3)); img[:] = np.array(ground) / 255
    gc = np.array(grains) / 255
    xi = xs.astype(int); yi = ys.astype(int)
    for ox in range(2):
        for oy in range(2):
            X = np.clip(xi + ox, 0, w - 1); Y = np.clip(yi + oy, 0, h - 1)
            img[Y, X] = img[Y, X] * (1 - ga) + gc[col] * ga
    Image.fromarray((img * 255).astype(np.uint8)).save(path_out)
    print(path_out, 'grains', len(xs), 'prints', n, 'steps', int(total), 'per print', per)

CARBONIO = ((0x0B, 0x0B, 0x0C), [(0xE2, 0xDE, 0xD6), (0xB2, 0x38, 0x2A), (0x76, 0x76, 0x7A)], .78)
if __name__ == '__main__':
    day, now = sample_day()
    render('/tmp/orma-cover.png', 1080, 2520, 2.625, CARBONIO, day, now)
    render('/tmp/orma-main.png', 2256, 2504, 2.625, CARBONIO, day, now)
