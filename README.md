# Solco

A live wallpaper of grain and light for the Galaxy Z Fold 8 Ultra. Nothing on it moves unless something moves it: the fold, your finger, the sun.

Built to Bellezza Necessaria, the Ferguson Consultancy Designs mandate: every element answers the question of which force put it there.

## Styles

**Solco.** Bands of grain on rings centred on the hinge: the left edge of the cover screen, the crease of the main screen. Opening the phone stirs the grain one way along its rings, closing it the other, with the outer grain carried first and furthest. Fine grooves fill the quiet middle.

**Tronco.** Tree rings. One ring a day from the day Solco is first set, as wide as that day's unfolded use: pale early grain, then a dense line where the day closed. Today's ring is still growing. On the first day there is only a core.

## In every style

- **The disc** is the sun by day and the moon by night, riding a ring round the hinge: low at dawn and dusk, highest at noon. Cooler and brighter by day, warmer and dimmer at night.
- **True moon phase** (on by default): at night the disc shows tonight's moon.
- **Concentrato** (on by default): a fast fold leaves the bands emptier for a moment, read from the hinge sensor.
- **Touch:** grain yields under a finger and springs back.

## Finishes

Visconti (green-black, champagne and ivory grain), Avorio (bone, ink and oxide grain, a rosso sun), Carbonio (black, ivory and rosso grain), Rosso (oxblood, champagne grain).

## Battery

At rest nothing is drawn. Frames run only while grain is moving, after a fold or a touch, and once a minute for the sun. Hidden, the wallpaper listens to nothing. Tronco samples once a minute whether the screen is on and unfolded; asleep, nothing runs.

## Sipario

A second wallpaper in the same app: five scenes, each one world drawn at the Fold's own scale. The cover (1,080 × 2,520) and the main screen (2,256 × 2,504) share 422 ppi, and the cover is almost exactly the main screen's right half. Folded, you see the half you hold; unfold, and the door swings away from what was standing beside it. Nothing zooms.

- **Catenaria.** Weighted chains hang from a rod. Unfolded, the door is Gaudí's mirror: the same curves stand up as arches, built from their feet, and the tallest keystone lights.
- **Stagioni.** The tree as it stands today. Unfolded, the canopy runs back through the year: summer by the crease, then blossom, then last winter's frost. Folding shakes a few loose autumn leaves down.
- **Ponte.** The road stops at a raised bascule bridge, its red lamp lit. Unfolded, the span has come down on the hinge and seats, and a Bauhaus town lights up across the river.
- **Fanale.** A red ring over a wet road, like a moon. Unfolded, its twin lights bead by bead: tail lamps. Tap to lock.
- **Goccia.** A drop forms on a leaf tip. Unfolded, the hinge's settle shakes it loose into the pond.

Each scene draws only on a fold, a touch, or the minute. Concentrato is shared with Solco. Open Sipario from the foot of Solco's Settings, or choose it in the wallpaper picker.

## Install

1. Open **Releases** on this repo and download `Solco.apk` on the phone.
2. Open it and allow the install.
3. Open **Solco**, choose a style and finish, then **Set as wallpaper**.

Every push to `main` builds a new APK, publishes it as a release, runs it on an emulator and attaches the screenshots to the same release.

## Diagnostics

At the foot of Settings: whether the wallpaper has drawn on the cover screen and the main screen (size and display), whether the phone has a hinge sensor, and the last fold's measured speed. The fast-fold threshold (320 °/s) is an estimate to be tuned on the Fold.

## Where things live

| File | What it does |
|---|---|
| `Scene.kt` | The grain: where each grain lives, Solco's bands, Tronco's rings, the fold's stir, touch, the spring home |
| `SolcoRenderer.kt` | Draws with the GPU: ground and grooves, grain as points, the disc |
| `SolcoWallpaperService.java` | The wallpaper host. Fold detection, hinge sensor, touch, the minute |
| `Watch.kt` | The fold as the wallpaper sees it, Tronco's daily record, diagnostics |
| `Painters.kt` | Ground and grooves, the sun and moon, and the Canvas painter for Settings |
| `Look.kt` | Finishes, styles, saved choices |
| `SettingsActivity.kt`, `Views.kt`, `Kit.kt` | Settings in the family's kit |
| `tools/preview.html` | The browser preview Solco was designed in |
| `Sipario.kt` | Sipario's five scenes, drawn with a Canvas in one world at the Fold's scale |
| `SiparioWallpaperService.kt` | Sipario's wallpaper host: a hardware canvas, the fold, touch, the minute |
| `SiparioActivity.kt` | Sipario's settings: the scene live, play the unfold, five tiles |
| `tools/sipario-preview.html` | The browser preview Sipario was designed in |
| `tools/emulator_test.sh` | The emulator run behind each release's screenshots |

Fonts: Italiana and Jost, both under the SIL Open Font Licence (in `app/src/main/assets/fonts`).
