# Solco

A live wallpaper of grain and light for the Galaxy Z Fold 8 Ultra. Nothing on it moves unless something moves it: the fold, your finger, the sun.

Built to Bellezza Necessaria, the Ferguson Consultancy Designs mandate: every element answers the question of which force put it there.

## Styles

**Solco.** Bands of grain on rings centred on the hinge: the left edge of the cover screen, the crease of the main screen. Opening the phone stirs the grain one way along its rings, closing it the other, with the outer grain carried first and furthest. Fine grooves fill the quiet middle.

**Tronco.** Tree rings. One ring a day from the day Solco is first set, as wide as that day's unfolded use: pale early grain, then a dense line where the day closed. Today's ring is still growing. On the first day there is only a core.

**Marea.** The grain fills to the battery's level, foam at the surface and sediment at the floor. On charge the surface breathes; otherwise it is still.

**Duna.** Wind ripples blowing out from the hinge. Tilt past the angle of repose and the loosest grain runs downhill and heaps in the corner, as much as came; turn the phone and the heap pours round; level it and the wind blows it home.

**Polvere.** Dust hangs in a shaft of light falling from the sun or moon. The longer the phone lies untouched, the more has settled to the floor; picking it up lifts it back into the light. With the moon set there is no shaft. The shaft is laid again as the sun moves on.

**Delta.** Each charge of the past week carves a channel out from the hinge, as deep as the charge gave. The oldest silt up; the newest channel's banks wear the finish's accent. The plug is heard for as long as Solco is set, two broadcasts a charge. With no charges yet the sand is flat.

**Pioggia.** The weather outside now, as a long exposure: rain in still streaks slanted by the real wind and as long as the rain is hard, snow in soft clumps that drift on the floor, a dry sky a haze as heavy as its cloud, one fork of lightning in a thunderstorm. Read from Open-Meteo (no key; only the place's latitude and longitude, to two places, are sent) every 20 minutes at most, and only while Pioggia is on screen.

**Stagione.** One ray for each day of the year, fanned from the hinge, as long as that day's daylight at your place: the fan swells to midsummer and pinches at the winter solstice. Days gone are dense, days to come faint, today's ray wears the accent; a hair of bare sand marks each month.

**Sentiero.** Raked gravel worn by your hand. Each touch on the home screen today presses a hollow with the gravel banked round it, and each swipe drags a path; the last hour's banks wear the accent. At midnight the garden is raked again. Kept on the phone, today only, and only while Sentiero is chosen.

**Deserto.** One great dune across the crease, a true heightfield seen from a low eye and lit from the side the sun or moon is on, its backlit crest catching a rim in the accent. While the phone is locked the time stands in the sky behind the crest, in Italiana, as a depth effect does. A live wallpaper is drawn beneath the lock screen, so One UI's own clock cannot go behind the dune: Solco draws its own and shows it only while locked. Hide One UI's clock (Lock screen settings) to see it alone.

## In every style

- **The disc** is the sun by day and the moon by night, riding a ring round the hinge: low at dawn and dusk, highest at noon. Cooler and brighter by day, warmer and dimmer at night.
- **True moon phase** (on by default): at night the disc shows tonight's moon.
- **Concentrato** (on by default): a fast fold leaves the bands emptier for a moment, read from the hinge sensor.
- **Touch:** grain yields under a finger and springs back.
- **Copertina** (Half by default): while music plays, the grain and the disc take the colours of the sleeve, each grain keeping its finish's lightness, and return to the finish when it stops. The two colours come from Fold Widgets, which reads the artwork; only the colours cross. Settings has A touch, Half, Full or Off, and Show me plays five made-up sleeves on the preview.

## Finishes

Visconti (green-black, champagne and ivory grain), Carbonio (black, ivory and rosso grain), Notte (midnight navy, ivory, champagne and slate grain), Tabacco (tobacco brown, cream, ochre and umber grain), Grafite (graphite, silver and smoke grain), Bronzo (warm black, bronze, copper and pale-gold grain) and Verderame (deep petrol, verdigris grain with copper: the patina to Bronzo's metal).

Seven styles and seven finishes, so a different pairing can be worn each day.

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
