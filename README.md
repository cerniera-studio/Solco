# Solco

A live wallpaper of grain and light for the Galaxy Z Fold 8 Ultra. Nothing on it moves unless something moves it: the fold, your finger, the sun.

Built to Bellezza Necessaria, the Ferguson Consultancy Designs mandate: every element answers the question of which force put it there.

## Styles

In alphabetical order, as Settings shows them.

**Delta.** Each charge carves a channel out from the hinge, as deep as the charge was long. Over a week they braid a delta, the oldest silting up; the newest channel's banks wear the accent. While the phone charges, grain runs down the newest channel from the hinge.

**Deserto.** One great dune across the crease, lit from the side the sun or moon is on and at its real height: a low light throws the shade long, a high one draws it in under the crest. When the real wind outside blows hard enough to move sand, a plume streams off the crest the way it blows. At night the sky holds a few stars.

**Marea.** The grain fills to the battery's level, foam at the surface and sediment at the floor. On charge the surface breathes; otherwise it is still.

**Orbita** (the Solco style until 28 Nov 2026). Bands of grain on rings centred on the hinge: the left edge of the cover screen, the crease of the main screen. Opening the phone stirs the grain one way along its rings, closing it the other, with the outer grain carried first and furthest. Fine grooves fill the quiet middle. Hold a finger on the glass and the nearby rings bow towards it, then flow back when you let go.

**Orma.** Today's steps, pressed into the sand as a trail of footprints from the bottom of the glass. Brisk walking lengthens the stride, a run digs in the toes, and where you stood still the feet rest side by side. The prints soften as much as today's real wind has blown over them; the last hour's wear the accent. At midnight the sand is smooth again.

**Pioggia.** The weather outside now, as a long exposure: rain in streaks slanted by the real wind, snow in soft clumps, a dry sky a haze as heavy as its cloud. Puddles on the floor hold as much rain as has fallen here today, and stay after it stops. Read from Open-Meteo (no key; only the place's latitude and longitude, to two places, are sent) every 20 minutes at most, and only while Pioggia, Deserto or Orma is on screen.

**Sentiero.** Raked gravel worn by your hand. Each touch on the home screen today presses a hollow with the gravel banked round it, and each swipe drags a path; the last hour's banks wear the accent. At midnight the garden is raked again. Kept on the phone, today only, and only while Sentiero is chosen.

**Stagione.** One ray for each day of the year, fanned from the hinge, as long as that day's daylight here. Days gone are dense, days to come faint; today's ray fills in the accent as the daylight passes, from sunrise to sunset.

**Tronco.** One ring a day, as wide as that day’s unfolded use. Across each ring runs its day from midnight to midnight: the grain is dense in the hours the phone was open and pale in the quiet ones, and a fine line closes the ring. The trunk begins as a core on the day you set it.

## In every style

- **The disc** is the sun by day and the moon by night, riding a ring round the hinge: low at dawn and dusk, highest at noon. Cooler and brighter by day, warmer and dimmer at night.
- **True moon phase** (on by default): at night the disc shows tonight's moon.
- **Concentrato** (on by default): a fast fold leaves the bands emptier for a moment, read from the hinge sensor.
- **Touch:** grain yields under a finger and springs back.
- **Copertina** (Half by default): while music plays, the grain and the disc take the colours of the sleeve, each grain keeping its finish's lightness, and return to the finish when it stops. The two colours come from Fold Widgets, which reads the artwork; only the colours cross. Settings has A touch, Half, Full or Off, and Show me plays five made-up sleeves on the preview.

## Finishes

In alphabetical order, as Settings shows them: Bronzo (warm black, bronze, copper and pale-gold grain), Carbonio (black, ivory and rosso grain), Grafite (graphite, silver and smoke grain), Notte (midnight navy, ivory, champagne and slate grain), Tabacco (tobacco brown, cream, ochre and umber grain), Verderame (deep petrol, verdigris grain with copper: the patina to Bronzo's metal) and Visconti (green-black, champagne and ivory grain).

Nine styles and seven finishes, so a different pairing can be worn each day.

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
| `Charges.kt` | Delta's record: each charge of the week, when it began and what it gave |
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
