"""Duna's heap (24 Nov 2026), Scene.dunaStep ported to numpy, to judge the pile before the phone does.

Runs a cover-screen sized glass through a pour into the bottom-right corner, a swing to the
bottom-left, straight down at a gentler slope, then level, and writes a strip of the four rests
to /tmp/duna_strip.png with how long each took to come to rest. python3 tools/duna_heap.py
"""
import numpy as np
from PIL import Image, ImageDraw

W, H, DP = 1080, 2520, 2.625
rng = np.random.default_rng(3)
cap = int((W / DP) * (H / DP) / 4.2)
hash_ = rng.random(cap)
hx = rng.random(cap) * W; hy = rng.random(cap) * H
L = hash_ > .8
lidx = np.nonzero(L)[0]
m = (hash_[lidx] - .8) / .2
lift = (hash_[lidx] * 7919.13) % 1
jx = (((hash_[lidx] * 104729.7) % 1) - .5) * 3 * DP; jy = (((hash_[lidx] * 15485.863) % 1) - .5) * 3 * DP
x0 = hx[lidx]; y0 = hy[lidx]
ox = np.zeros(len(lidx)); oy = np.zeros(len(lidx)); vx = np.zeros_like(ox); vy = np.zeros_like(ox)
gone = np.zeros(len(lidx), bool)
asleep = np.zeros(len(lidx), bool); calm = np.zeros(len(lidx), int)
anx = np.zeros(len(lidx)); any_ = np.zeros(len(lidx))
cell = 3 * DP; cw = int(W / cell) + 2; ch = int(H / cell) + 2
packed = cell * cell / (2.2 * DP * DP)
repose = .3 * 9.81
STICK_LOAD = .15; SLEEP = True; PK = .45; PCAP = .5; VISC = .8; ITER = 3; VFILL = 1.15
state = dict(gx=0., gy=0., sx=0., sy=0., since=0)

def count(sel):
    c = np.zeros(cw * ch)
    fx = np.clip((x0 + ox)[sel] / cell, 0, cw - 1.001); fy = np.clip((y0 + oy)[sel] / cell, 0, ch - 1.001)
    ix = fx.astype(int); iy = fy.astype(int); ax = fx - ix; ay = fy - iy; q = iy * cw + ix
    np.add.at(c, q, (1 - ax) * (1 - ay)); np.add.at(c, q + 1, ax * (1 - ay))
    np.add.at(c, q + cw, (1 - ax) * ay); np.add.at(c, q + cw + 1, ax * ay)
    return c / packed

def fill(c, x, y):
    fx = np.clip(x / cell, 0, cw - 1.001); fy = np.clip(y / cell, 0, ch - 1.001)
    ix = fx.astype(int); iy = fy.astype(int); ax = fx - ix; ay = fy - iy; q = iy * cw + ix
    return (c[q] * (1 - ax) + c[q + 1] * ax) * (1 - ay) + (c[q + cw] * (1 - ax) + c[q + cw + 1] * ax) * ay
def over(c, x, y): return np.maximum(fill(c, x, y) - 1, 0)

def held(c, x, y, ux, uy):
    bx = x + ux * cell * 1.2; by = y + uy * cell * 1.2
    edge = (bx < 0) | (by < 0) | (bx > W) | (by > H)
    return (fill(c, x, y) > .6) & (edge | (fill(c, bx, by) > .6))

def rivulet(x, y):
    return .55 * np.sin(x / (41 * DP) + 1.6 * np.sin(y / (67 * DP))) + .45 * np.sin(y / (29 * DP) - 1.3 * np.sin(x / (53 * DP)))

def step(tgx, tgy, dt=1.0):
    global ox, oy, vx, vy
    e = 1 - np.exp(-dt * 16.67 / 110)
    state['gx'] += (tgx - state['gx']) * e; state['gy'] += (tgy - state['gy']) * e
    gx, gy = state['gx'], state['gy']
    gm = max(np.hypot(gx, gy), 1e-3); slide = gm > repose; ux, uy = gx / gm, gy / gm
    if slide: gone[:] |= gm > repose * (1 + .4 * (1 - m))
    # The slope swung more than 4 degrees since the heap last rested, or levelled: everything wakes
    if SLEEP is False or not slide or (ux * state['sx'] + uy * state['sy']) < np.cos(np.radians(4)):
        asleep[:] = False; calm[:] = 0; state['sx'], state['sy'] = ux, uy; state['since'] = 0
    g = gone & ~asleep
    p0x = ox.copy(); p0y = oy.copy()
    x = x0 + ox; y = y0 + oy
    c = count(np.ones_like(g))
    fl = fill(c, x, y)
    # Support taken away: a sleeping grain whose heap has gone wakes
    woke = asleep & (fill(c, x, y) < .45); asleep[woke] = False; calm[woke] = 0
    g = gone & ~asleep
    if slide:
        pull = (gm - repose) / 9.81 * .9 * DP * dt * (.6 + .4 * m)
        bend = rivulet(x, y) * .55
        vx = np.where(g, (vx + (ux - uy * bend) * pull) * .94 ** dt, vx)
        vy = np.where(g, (vy + (uy + ux * bend) * pull) * .94 ** dt, vy)
        # In the heap, sand on sand: heavy friction
        fr = np.where(fl > VFILL, VISC ** dt, 1.0)
        vx = np.where(g, vx * fr, vx); vy = np.where(g, vy * fr, vy)
    else:
        k = .0016 * dt * (.55 + .9 * lift) / (1 + 5 * over(c, x, y))
        vx = np.where(g, (vx - ox * k) * .9 ** dt, vx); vy = np.where(g, (vy - oy * k) * .9 ** dt, vy)
        # The last stretch home at a walking pace, never a crawl, unless still buried
        d = np.hypot(ox, oy); sp = np.hypot(vx, vy); floor = np.minimum(d, .08 * DP)
        lift_ok = g & (sp < floor) & (d > 0) & (over(c, x, y) < .3)
        vx = np.where(lift_ok, -ox / np.maximum(d, 1e-9) * floor, vx); vy = np.where(lift_ok, -oy / np.maximum(d, 1e-9) * floor, vy)
    v = np.hypot(vx, vy); s = np.where(v > 4.5 * DP, 4.5 * DP / np.maximum(v, 1e-9), 1)
    vx *= s; vy *= s
    ox = np.where(g, ox + vx * dt, ox); oy = np.where(g, oy + vy * dt, oy)
    for _ in range(ITER):
        c = count(np.ones_like(g))
        x = x0 + ox + jx; y = y0 + oy + jy
        ee = over(c, x, y)
        gxE = over(c, x + cell * .5, y) - over(c, x - cell * .5, y)
        gyE = over(c, x, y + cell * .5) - over(c, x, y - cell * .5)
        gl = np.hypot(gxE, gyE)
        act = g & (ee > 0) & (gl > 1e-4)
        push = np.minimum(PCAP * cell, PK * cell * gl)
        ox = np.where(act, ox - gxE / np.maximum(gl, 1e-9) * push, ox)
        oy = np.where(act, oy - gyE / np.maximum(gl, 1e-9) * push, oy)
    x = np.clip(x0 + ox, .6 * DP, W - .6 * DP); y = np.clip(y0 + oy, .6 * DP, H - .6 * DP)
    ox = np.where(g, x - x0, ox); oy = np.where(g, y - y0, oy)
    mx = ox - p0x; my = oy - p0y; moved = np.abs(mx) + np.abs(my)
    # Static friction: a little for running grain, much more for grain under the heap's load
    load = fill(count(np.ones_like(g)), x0 + ox, y0 + oy) > 1.0
    stuck = g & bool(slide) & (moved < np.where(load, STICK_LOAD, .045) * DP * dt)
    ox = np.where(stuck, p0x, ox); oy = np.where(stuck, p0y, oy)
    vx = np.where(stuck, 0, np.where(g, mx / dt, vx)); vy = np.where(stuck, 0, np.where(g, my / dt, vy))
    home = g & (not slide) & (np.abs(ox) + np.abs(oy) <= .5 * DP)
    gone[home] = False
    if slide and SLEEP:
        x = x0 + ox; y = y0 + oy
        cc = count(np.ones_like(g)); fl = fill(cc, x, y)
        # Packed, or on the heap's skin and barely moving: either way, at rest once it has stayed so
        state['since'] += dt
        # Slow, and on the heap or, once the pour is a few seconds old, anywhere: stragglers rest too
        # Calm: still within 1.5 dp of where it was when it last stopped going anywhere. A grain
        # jostling on the heap's skin is calm; one still running is not
        near = (np.abs(x - anx) + np.abs(y - any_)) < 2.5 * DP
        still = near & held(cc, x, y, ux, uy)
        calm[:] = np.where(g & still, calm + 1, 0 * calm)
        anx[:] = np.where(g & ~near, x, anx); any_[:] = np.where(g & ~near, y, any_)
        nap = g & (calm >= 20); asleep[nap] = True; vx[nap] = 0; vy[nap] = 0
        g = g & ~nap
    return float(moved[g & ~stuck].max()) if (g & ~stuck).any() else 0.0

def frame(name, scale=.33):
    img = Image.new('RGB', (W, H), (12, 10, 9)); d = img.load()
    for xs, ys, col in ((hx[~L], hy[~L], (120, 110, 100)), (x0 + ox, y0 + oy, (235, 225, 205))):
        for a, b in zip(xs.astype(int), ys.astype(int)):
            if 0 <= a < W and 0 <= b < H: d[a, b] = col
    img.resize((int(W * scale), int(H * scale))).save(name)

def g_at(deg_dir, deg_tilt):
    t = np.radians(deg_tilt); a = np.radians(deg_dir)
    return 9.81 * np.sin(t) * np.cos(a), 9.81 * np.sin(t) * np.sin(a)

if __name__ == '__main__':
    import sys
    shots = []
    seq = [("down-right corner, 35°", g_at(55, 35), 700), ("swung to down-left, 35°", g_at(125, 35), 700),
           ("straight down, 25°", g_at(90, 25), 600), ("level: wind home", (0., 0.), 900)]
    for label, (gx, gy), frames in seq:
        es = []
        for f in range(frames):
            es.append(step(gx, gy))
        moving = sum(1 for e in es[-30:] if e > .01 * DP)
        print(f"{label}: last-frame motion {es[-1]/DP:.3f} dp, frames still moving in last 30: {moving}, awake {(gone & ~asleep).sum()}, gone {gone.sum()}")
        name = f"/tmp/duna_{len(shots)}.png"; frame(name, scale=.4); shots.append(name)
    ims = [Image.open(s) for s in shots]
    out = Image.new('RGB', (sum(i.width for i in ims) + 20 * (len(ims) - 1), ims[0].height), (40, 40, 40))
    xo = 0
    for i in ims: out.paste(i, (xo, 0)); xo += i.width + 20
    out.save('/tmp/duna_strip.png')
    # Heap volume check: area of packed pile vs grain that came
    print("loose grains", len(lidx), "expected heap area dp²", round(len(lidx) * 2.2), "≈", round(100 * len(lidx) * 2.2 / (W / DP * H / DP), 1), "% of screen")
