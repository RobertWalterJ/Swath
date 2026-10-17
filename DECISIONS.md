# Decisions

Where `SPEC.md` did not settle something, or where the simplest option that meets the acceptance criteria differed from the letter of the spec, it is logged here. Newest at the bottom of each section.

## Status against the milestones

| Milestone | State |
| --- | --- |
| M0 Scaffold | Done. Gradle multi-module project, CI workflow, replay CLI. OpenCV is not wired in (see below). |
| M1 Wall lock and recorder | Written, **not yet run on a phone**. ARCore session, vertical plane tap-to-lock, high-resolution CPU stills with pose sidecars. The recorded-session format round-trips through the replay CLI (tested). |
| M2 Triggering and quality gates | Done and tested in `core` (overlap trigger, speed/tilt/distance gates, burst-and-pick sharpness). Haptics and hints are wired in the app. Thresholds are estimates until field-tested. |
| M3 Projection and mosaic | Done and tested: homography matches an independent pinhole projection within 0.5 px, mosaic lines up with ground truth to the pixel. |
| M4 Live coverage map | Grid, cell states, footprint rectangle and the Fill gaps prompt are written and the grid is tested. The on-screen strip has not been seen on a device. |
| M5 Pose refinement | Done and tested: 50 mm of drift over 10 m is corrected to under 5 mm. Translation only (see below). |
| M6 Glare and two-band blending | Done and tested on synthetic glare. |
| M7 Viewer and library | Written, **not yet run on a phone**. Tiled deep-zoom viewer, layers, session list with rename, duplicate, delete, restore. |
| M8 OCR and notes | Note detection, colour naming and clustering are tested on synthetic walls. ML Kit OCR is written but untested. |
| M9 Search and export | CSV, JSON, zip are tested. PDF, PNG, JPEG, search are written, untested on a device. |
| M10 Polish and field validation | Not started. Needs real phones and walls. |

**What "written, not yet run" means:** the Android modules (`app`, `capture`, `storage`, `ocr-mlkit`) could not be compiled on the development PC because it has no Android SDK. They are built by the `android` job in `.github/workflows/ci.yml`. Treat the first CI run as the first compile.

## Architecture

- **Core has no OpenCV.** The spec calls for OpenCV for warp, blend and registration. Core instead uses a small pure-Kotlin `Raster` with bilinear sampling, box blur and normalised cross-correlation. Reasons: it runs identically on phone, desktop and in tests with no native library loading; it is fully verifiable without a device; and the operations needed (planar warp by a known homography, weighted blend, small-window registration) are simple. The cost is speed. If field testing shows the 30 second processing target is missed, the hot loops (`MosaicRenderer.render`, `PairMatcher`) are the places to swap in OpenCV behind the same interfaces.
- **Capture logic lives in `core`, not `capture`.** The trigger, quality gates, burst picker and coverage grid are pure maths, so they sit in `core` and are unit-tested and replayable. The `capture` module only holds ARCore, GL and the camera.
- **Image encoding behind `ImageCodec`.** `javax.imageio` does not exist on Android and `Bitmap` does not exist on the JVM, so core takes a codec. The replay CLI uses ImageIO; the app uses `Bitmap`.
- **No JSON or CSV libraries.** `Json` and the CSV writer/parser are small hand-written classes in core, to keep core dependency-free.
- **World units are metres, wall units are millimetres.** `WallFrame.wallToWorld` and `worldToWall` take and return metres; everything else (homography input, mosaic, coverage grid, notes) is millimetres.

## Capture

- **Stills come from ARCore's highest-resolution CPU image**, which is the fallback the spec names. The Shared Camera plus Camera2 still stream, and with it a true manual focus/exposure/white-balance lock, is deferred: it needs real hardware to get right. Focus stays on ARCore auto-focus, so the "lock focus at the measured distance" and "shutter 1/250 s or faster" items from Phase A are **not implemented yet**.
- **Burst of 3 uses three consecutive AR frames**, not a Camera2 burst, so the picked frame carries that frame's own pose.
- **Gyro angular velocity** comes from the device gyroscope, not from the ARCore pose delta.
- **The first frame of a sweep always triggers a capture** (no previous footprint). Speed is measured between consecutive frames, so the very first frame has no speed estimate.
- **Glare detection in the coverage map is not wired up.** `CoverageGrid.addStill` accepts a glare predicate but the engine does not supply one yet, so the Glare cell state never appears on device.

## Processing

- **Pose refinement is translation-only.** The spec says translation plus rotation, plane re-fit, and a 3 degree rejection. Translation in the wall plane is what the 5 cm / 10 m acceptance criterion needs and is solved by a weighted linear least squares; rotation and plane re-fit are not implemented. Pairs shifting more than 50 mm, or correlating below 0.5, are dropped.
- **Registration is brute-force normalised cross-correlation**, coarse (4x down) then fine with a parabola for sub-pixel peaks, not ORB + RANSAC. It works on the textured synthetic walls; featureless whiteboards will fail the score gate and be dropped, falling back to the raw poses. ORB + RANSAC is the likely upgrade.
- **Two-band blend** uses a box blur of the source still as the low band. The high band comes from the best-weighted sample.
- **The quality layer (per-pixel resolution and confidence) is not produced**, so the viewer has no "weak areas" overlay. The coverage map already flags weak cells during capture.
- **Note detection runs at up to 1 px/mm** and is capped at 24 million pixels so a 12 m wall stays inside the memory budget; crops are then cut from the full-resolution mosaic. Notes that touch each other merge into one blob and are rejected as too large. White notes are only detected when the wall background is not white.
- **Full-resolution PNG export** uses the sharpest pyramid level that fits in about 36 MP, because Android cannot hold a 190 MP bitmap. The pyramid's level 0 is the full-resolution data.
- **Stills are decoded at no more than 3000 px** on the long side in the app. The mosaic maths rescales intrinsics to match, so this is lossless for geometry.

## Export and data

- **CSV neutralises leading `=`, `+` and `@`** by prefixing an apostrophe, so a note reading `=SUM(...)` cannot execute in a spreadsheet. This means such text does not round-trip byte for byte.
- **PDF text layer** is real text drawn at 2/255 opacity over each note, so it is selectable and searchable but invisible. Text is only drawn for notes, not freeform whiteboard blocks.
- **Freeform text blocks are not extracted yet.** Only text inside detected notes is recognised. The `TextBlock` model and the windowed OCR are in place for it.
- **Search is a SQL `LIKE`**, not FTS. Fine for hundreds of sessions.
- **Room schema is version 1 with no migrations and `exportSchema = false`.** Add migrations before any release that people keep data on.

## Names

`appName`, `appSlug` and `notesModeName` are in `gradle.properties` only. They feed `@string/app_name`, `@string/notes_mode_name`, the application ID (`app.wallcapture.<appSlug>`) and the replay CLI binary name (`<appSlug>-replay`). Package names are the neutral `app.wallcapture.*`.

## Design pass (17 Oct 2026)

See `DESIGN.md` for the critique and the new flow. Decisions that went beyond it:

- **No auto-fading hints.** The spec says hints fade after 2 seconds. Robert is dyslexic and uses no timers by default, so a hint stays while its cause is true and disappears when the cause goes.
- **Read-aloud everywhere text is longer than a line.** One shared Android text-to-speech engine; a speaker icon sits beside the how-to cards, every capture instruction, the check result and the note editor.
- **Plain-writing check as a unit test.** `PlainWritingTest` fails the build when any line in `Copy.kt` runs over 20 words per sentence or uses a banned phrase.
- **Colour is never the only cue.** Weak map areas use amber with stripes. Notes use a swatch with the colour's letter plus the colour name.
- **Processing has its own screen** and never opens the finished wall by itself. A button does that.
- **The old modal onboarding became a three-page how-to** that can be reopened from Settings.
- **The share sheet replaced a six-item menu.** Report, Picture and Notes spreadsheet are the main choices. PNG, JSON and the note-photo zip sit under "More ways to share".
- **Report and Picture are generated with Android's own graphics and PDF classes**, with the same palette as the app, so no new libraries were added.
- **Not yet done:** none of this has been run on a phone. The mockups are renders of the design, not screenshots of the app.
