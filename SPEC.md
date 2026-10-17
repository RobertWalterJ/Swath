# Swath: Spec for Claude Code

October 16, 2026 · Robert Walter-Joseph

## Purpose and scope

Swath is an Android app that turns a sideways walk past a whiteboard or sticky-note wall into one straight-on, zoomable, searchable image, with each sticky note extracted as its own record.

**Name.** Swath is the working name for the app; Pinup is the proposed name for its collaborative notes mode. Neither is final, so the code treats both as swappable placeholders (see Working conventions under Build milestones).

**Goals**

- Capture walls up to 12 m wide and 3 m tall in one session, readable at handwriting level.
- Tolerate real handheld behaviour: shake, twist, uneven pace, drifting closer or farther.
- Show live coverage so gaps are fixed on the spot, before the room is cleared.
- Run fully on-device: no account, no upload, works offline in a basement meeting room.
- Produce exports a planner can use the same day: PDF, PNG, CSV of notes.

**Non-goals for v1**

- Full 3D models of rooms or curved surfaces.
- iOS (keep the processing core free of Android APIs so a later port stays possible).
- Cloud sync, collaboration or user accounts.
- Live translation or AI summarization of notes (a later add-on).

**How Claude Code should use this spec**

Treat this as the source of truth and keep it in the repo as `SPEC.md`. Build in the milestone order near the end; each milestone has testable acceptance criteria. Where a decision is not covered, choose the simplest option that meets the acceptance criteria and log it in `DECISIONS.md`.

## Users and use cases

The primary user is a facilitator who needs a faithful record of a wall at the end of a session, often with little time and people still in the room.

| Use case | Typical surface | What matters most |
| --- | --- | --- |
| Community engagement session | 4 to 10 m of sticky notes on kraft paper or a wall | Every note captured, colour preserved, notes exported as rows |
| Design charrette or workshop | Whiteboards plus pinned sketches and maps | Fine line detail, glare removal |
| Internal team meeting | One or two whiteboards | Speed: under 60 seconds start to export |
| Long timeline or process wall | Long wall in a narrow corridor | Straight, undistorted result over long runs |

**Core user story:** I stand about an arm and a half from the wall, tap Start, walk the length of it, glance at the coverage map, sweep over any gaps, tap Done, and within a minute I can zoom into any note and search its text.

## Platform and stack

Native Android is required for v1, because it gives direct access to ARCore motion tracking and high-resolution camera frames while tracking runs.

| Layer | Choice | Why |
| --- | --- | --- |
| Language and UI | Kotlin, Jetpack Compose | Current Android standard, well supported by Claude Code |
| Minimum OS | Android 10 (API 29); target the latest stable SDK | Covers most phones in use |
| Devices | ARCore-supported phones on Google's certified list; depth sensors used when present, never required | ARCore support is the hard requirement |
| Tracking and plane | ARCore with vertical plane finding; camera pose and image intrinsics per frame | 6DoF pose per frame, wall plane estimate |
| Still capture | ARCore Shared Camera with a Camera2 still stream at the highest resolution the device allows alongside tracking; fallback to the highest ARCore CPU image config | High-res stills with pose attached |
| Image processing | OpenCV 4.x (Android SDK plus JVM bindings) for warp, blend and registration; optional GPU path later | Same code runs on phone and desktop |
| Feature matching | OpenCV ORB features with RANSAC; ECC alignment for small corrections | Pose refinement between overlapping stills |
| OCR | Google ML Kit Text Recognition v2, bundled on-device model | Works offline; strong on print and neat handwriting, weaker on messy handwriting |
| Note detection | OpenCV colour segmentation and contour rectangle fitting | No model training needed for v1 |
| Storage | Room for metadata, files in app storage for images and tiles | Simple, local |
| Viewer | Tiled zoom view over the tile pyramid (custom Compose canvas, or Subsampling Scale Image View) | Smooth pan and zoom of very large images |
| PDF | Android `PdfDocument` | Built in, no extra dependency |

No analytics or network calls; ML Kit uses its bundled model rather than a Play Services download. The app must work in airplane mode.

## Capture flow and pipeline

A session runs in three phases: lock onto the wall, sweep while capturing, then process into a final mosaic. Live work stays light; heavy work happens after Done.

**Phase A: Setup (target under 5 seconds)**

1. User points the phone at the wall. ARCore detects a vertical plane; the app shows a translucent overlay on it.
2. User taps the wall to confirm. The app fixes a **wall frame**: origin at the tap point, X axis horizontal along the wall, Y axis up (from gravity), Z out of the wall.
3. The app locks focus at the measured distance, sets white balance from the wall, and chooses an exposure with shutter of 1/250 s or faster, raising ISO as needed.
4. If the user is closer than 0.6 m or farther than 2.0 m, a hint asks them to adjust. Recommended distance is 1.0 to 1.5 m.

**Phase B: Sweep (live)**

1. Each AR frame (30 fps, low-res) updates the pose and the live coverage map.
2. A capture is triggered when the camera footprint has moved enough that overlap with the last kept still would fall below 60%, or when it covers an area not yet captured.
3. On trigger, request a burst of 3 high-res frames; score sharpness (variance of Laplacian on a downsampled copy) and keep the best.
4. Store the still as JPEG (quality 95) with its pose, intrinsics, timestamp, exposure, and motion readings.
5. Run a quick low-res projection of the still onto the wall plane to update the coverage map and quality heatmap.

**Phase C: Process (after Done, target under 30 seconds for a 6 m wall)**

1. Refine poses: match features between overlapping stills and run a small global adjustment so neighbours align.
2. Project every still onto the wall plane at a common resolution.
3. Blend with glare rejection and seam smoothing.
4. Write the mosaic as a tile pyramid plus a full PNG.
5. Run OCR and sticky-note detection on the mosaic.
6. Open the viewer. Processing can continue in the background with a progress bar.

## Robustness to handheld motion

The app never assumes smooth movement. Every still carries its measured pose, so motion problems become correction or rejection rules rather than failures.

| Problem | Detection | Handling | User feedback |
| --- | --- | --- | --- |
| Uneven walking speed | Pose delta between frames | Capture is triggered by footprint overlap, never by time | None needed |
| Moving too fast | Angular velocity over 1.0 rad/s or linear speed over 0.6 m/s at capture | Skip the trigger and retry on the next calm frame | Haptic tap, "Slow down" label |
| Hand shake and blur | Gyro reading during exposure; Laplacian sharpness score | Best of 3-frame burst; reject frames under sharpness threshold | Blurry areas shown hatched on coverage map |
| Twist (roll) | Pose roll angle | Fully corrected by projection | None under 25 degrees; level indicator above |
| Tilt (pitch or yaw) | Angle between camera axis and wall normal | Corrected by projection; frames weighted by cos of angle in blend | "Face the wall" hint over 30 degrees; frames over 50 degrees rejected |
| Closer or farther | Distance from pose to plane | Rescaled by projection; resolution recorded per pixel | Hint when outside 0.6 to 2.0 m; low-detail areas flagged |
| Tracking drift on long walls | Feature misalignment between overlapping stills | Global pose refinement in Phase C | None unless refinement fails |
| Tracking lost | ARCore tracking state paused or stopped | Pause capture; relocalize against existing stills | "Point back at a captured area" |
| Wall plane slightly wrong | Residuals from feature matches | Re-fit plane from matched 3D points during refinement | None |

**Thresholds** live in one `CaptureConfig` object so they can be tuned without code changes. Starting values above are estimates and must be calibrated with field tests.

## Coverage map and capture UI

The coverage map is the centrepiece of the capture screen: a live, flattened picture of the wall that fills in as the user sweeps, so gaps are fixed before leaving.

**Screen layout (portrait and landscape)**

- Full-screen camera view with the wall plane outlined.
- Coverage map strip pinned to the top: the wall as a horizontal band, current camera footprint shown as a moving rectangle.
- Bottom bar: Start or Pause, Done, distance readout in metres, and a small level indicator.
- One hint line above the bottom bar, showing only the most important current hint.

**Coverage map states**

| State | Rule | Display |
| --- | --- | --- |
| Not captured | No kept still covers the cell | Empty, dark |
| Captured, good | Sharp, angle under 30 degrees, at least 2.5 px per mm | Filled with low-res image |
| Captured, weak | Blurry, steep angle, or under 2.5 px per mm | Image with amber hatching |
| Glare | Saturated pixels in every still covering the cell | Image with a white glare marker |

The map is a grid of 2 cm cells in wall coordinates. The wall's extent grows automatically as the user moves. The user can tap Done at any time; if weak or empty cells sit inside the captured bounds, a one-line prompt offers "Fill gaps" or "Finish anyway".

**Feedback rules**

- Haptics: a light tick for each kept still, a double tap for "slow down".
- Never show more than one text hint at once; hints fade after 2 seconds of correct behaviour.
- Optional audio cue for capture, off by default.
- High-contrast mode and TalkBack labels on all controls.

## Projection, stitching and blending

Because the wall is planar, each still maps to the wall by a single homography computed from its pose and intrinsics. No depth estimation or 3D mesh is needed.

**1. Pose refinement**

- For each pair of stills with over 30% footprint overlap, warp both to the wall plane at low resolution and estimate a small residual correction (translation plus rotation in the plane) with OpenCV ECC alignment.
- Solve a least-squares adjustment over all stills so corrections are consistent across the wall, anchoring the first still.
- If a pair's correction exceeds 5 cm or 3 degrees, drop that pair as unreliable and log it.
- Re-fit the wall plane from matched points if the residual pattern shows a consistent tilt.

**2. Projection**

    H = K ( R - (t n^T) / d ) K^-1

Where K is the camera intrinsic matrix, R and t the camera pose relative to the wall frame, n the wall normal and d the plane distance. In practice, compute the mapping from wall coordinates to image pixels and sample with bilinear filtering with OpenCV remap, tile by tile.

- Output resolution: 4 px per mm by default (user setting: 3, 4 or 6). A 6 m by 2 m wall at 4 px per mm is 24,000 by 8,000 pixels.
- Process in 2,048 px tiles so memory stays bounded.

**3. Blending with glare rejection**

- For each output pixel, gather samples from all stills that cover it.
- Weight each sample by sharpness, viewing angle (cos of angle to normal), distance to the image edge, and resolution.
- Reject samples that are near saturation or much brighter than the median of the other samples. This removes whiteboard glare, which moves between views.
- Combine with weighted average, then apply two-band blending (low frequencies averaged, high frequencies from the single best sample) to keep ink edges crisp and avoid ghosting.
- Normalize exposure between stills before blending using overlap statistics, as a fallback if locked exposure drifts.

**4. Output**

- Tile pyramid (256 px tiles, levels down to a single tile) for the viewer.
- Full-resolution PNG and a downsampled JPEG preview.
- A per-pixel quality layer (resolution and confidence) at low resolution, used by the viewer to flag weak areas.

## OCR and sticky-note extraction

OCR runs on the finished mosaic, not on individual stills, so every word appears once, in wall coordinates, at its best resolution.

**Text recognition**

- Run ML Kit Text Recognition v2 (bundled Latin-script model, covering English and French) over overlapping 4,000 px windows with 10% overlap.
- Merge duplicate detections from window overlaps by bounding-box intersection.
- Store each text line with its wall-coordinate box, confidence, and source note (if inside one).
- Lines under 0.3 confidence are kept but flagged for review rather than dropped.

**Sticky-note detection**

1. Convert the mosaic (at 1 px per mm) to HSV and segment saturated regions that differ from the background colour.
2. Keep regions 40 to 200 mm across that are roughly square or rectangular (OpenCV minAreaRect on candidates, aspect ratio 0.5 to 2.0).
3. Assign each note a colour name from a fixed palette (yellow, pink, orange, green, blue, purple, white), editable by the user.
4. Crop each note from the full-resolution mosaic, deskew it, and run OCR on the crop.
5. Group notes into clusters by spatial proximity (DBSCAN, eps about 1.5 note widths), giving a rough reading of how the wall was organized.

**Whiteboard regions**

Areas outside notes are treated as freeform. Text lines are grouped into blocks by proximity. Hand-drawn diagrams are kept as image only in v1.

**Corrections**

Users can tap any note or text block to edit its text, colour, or cluster. Edits are stored separately from machine output so re-running OCR never overwrites them.

## Viewer, search and export

The viewer opens on the whole wall and zooms smoothly to ink level, with text and notes layered on top.

**Viewer**

- Tiled deep-zoom view, smooth pan and pinch on a 24,000 px wide mosaic.
- Toggle layers: note outlines, recognized text boxes, weak-quality overlay.
- Tap a note to open a card: crop, text, colour, cluster, edit.
- Notes list view beside or below the image, sortable by position, colour or cluster.

**Search**

- Full-text search across all sessions; results jump to the location on the wall and highlight the box.
- Filter by note colour and by session date.

**Session library**

- Each session has a title, date, optional location and tags, and a thumbnail.
- Rename, duplicate, delete (moves to a Recently Deleted area for 30 days before removal).

**Exports (via the Android share sheet)**

| Format | Contents |
| --- | --- |
| PDF | Overview page plus zoomed pages tiled across the wall at readable scale, with a searchable text layer |
| PNG | Full-resolution mosaic |
| JPEG | Downsampled preview for email or messaging |
| CSV | One row per note: id, text, colour, cluster, x and y in mm, confidence, edited flag |
| JSON | Full session: notes, text blocks, wall dimensions, capture metadata |
| Note images | Zip of individual note crops named by id |

The CSV is the key handoff for engagement synthesis: it should open cleanly in Excel with UTF-8 and proper quoting.

## Data model and storage

Metadata lives in Room; images live as files in a per-session folder. All coordinates are in millimetres in the wall frame, origin at bottom-left of the captured extent.

**Entities**

| Entity | Key fields |
| --- | --- |
| Session | id, title, createdAt, location?, tags, wallWidthMm, wallHeightMm, pxPerMm, status (capturing, processing, ready, failed), folderPath |
| Still | id, sessionId, fileName, timestamp, intrinsics (3x3), poseRaw (4x4), poseRefined (4x4), sharpness, angleDeg, distanceM, kept |
| Note | id, sessionId, boxMm (x, y, w, h, rotation), colour, clusterId, cropFileName, ocrText, ocrConfidence, userText?, userColour? |
| TextBlock | id, sessionId, boxMm, ocrText, confidence, noteId?, userText? |
| Cluster | id, sessionId, label?, colour |

**Folder layout per session**

    Sessions/<session-id>/
      stills/        JPEG stills plus pose JSON sidecars
      mosaic/        full.png, preview.jpg, quality.png
      tiles/         <level>/<x>_<y>.jpg
      notes/         <note-id>.jpg
      session.json   portable export of all metadata

**Storage rules**

- Raw stills are kept until the user taps "Free up space" so a session can be reprocessed with better algorithms later.
- Expected size for a 6 m wall: about 150 to 300 MB with stills, about 60 MB without.
- Show per-session size in the library.

## Architecture and module layout

All image and geometry code lives in a pure Kotlin/JVM module, so it runs identically in the app, the replay CLI and the test suite.

    Swath Android app (Jetpack Compose: Capture, Viewer, Library, Export)
        |
    capture module (app only, uses ARCore)
      Wall lock, camera locks, high-res stills
      Trigger, quality gates, live coverage map
        |  stills and poses                 Replay CLI (desktop JVM)
        v                                     |  recorded sessions
    core module (pure Kotlin/JVM + OpenCV, no Android APIs)  <-+
      Geometry:    wall frame math, pose to plane
      Refinement:  pairwise match, global adjust
      Mosaic:      OpenCV warp, deglare blend, tile pyramid
      Extraction:  OCR interface, note detection, clusters
      Export:      PDF, PNG, CSV, JSON, note zip
        |  read and write
        v
    Session storage (Room metadata plus per-session folders:
                     stills, mosaic, tiles, notes, session.json)

Only the capture module touches ARCore and the camera; everything below it works from recorded stills and poses.

| Module | Responsibility | Key types |
| --- | --- | --- |
| app (Compose) | Screens, navigation, settings, PDF export, share sheet | `CaptureScreen`, `ViewerScreen`, `LibraryScreen` |
| capture | ARCore session, wall lock, capture trigger, quality gates, coverage map | `WallLock`, `CaptureController`, `CoverageGrid`, `CaptureConfig` |
| core / geometry | Wall frame, pose to homography, footprint maths | `WallFrame`, `PlaneHomography` |
| core / refinement | Pairwise registration, global pose adjustment, plane re-fit | `PairMatcher`, `PoseGraph` |
| core / mosaic | OpenCV warp, glare-rejecting blend, tile pyramid | `Warper`, `Blender`, `TileWriter`, `ProcessingConfig` |
| core / extraction | Note detection, colour, clustering; OCR behind an interface | `NoteDetector`, `Clusterer`, `TextReader` interface |
| ocr-mlkit | Android implementation of `TextReader` using ML Kit | `MlKitTextReader` |
| core / export | CSV, JSON, note zip | `Exporter` |
| storage | Room entities and session folders | `SessionDao`, `SessionStore` |
| replay-cli | Desktop JVM app that runs processing on a recorded folder and prints metrics; OCR is skipped or stubbed, since ML Kit runs only on Android | `<appSlug>-replay` |

## Performance targets

Targets assume a Pixel 7 and a 6 m by 2 m wall captured at 1.2 m; newer phones should beat them.

| Measure | Target |
| --- | --- |
| Setup, launch to first capture | Under 5 s |
| Live preview frame rate during sweep | 30 fps (ARCore camera rate) |
| Coverage map update latency | Under 200 ms after a still is kept |
| Stills per 6 m wall, single pass | About 25 to 40 |
| Processing time after Done | Under 30 s to viewable mosaic; OCR may finish up to 30 s later |
| Peak memory | Under 1.2 GB |
| Alignment error between neighbouring stills | Under 1 mm on the wall |
| Straightness over 6 m | Under 5 mm deviation of a straight edge |
| Readability | 10 mm handwriting legible at 100% zoom |
| OCR accuracy on clear printed-style handwriting | Over 90% words correct |
| Battery use | Under 5% per session |

Thermal limits: if the device reports serious thermal state, pause blending and resume when it cools, keeping the user informed.

## Testing and acceptance

The pipeline must be testable without a phone, using recorded sessions, so Claude Code can iterate on algorithms from the command line.

**Test infrastructure**

- A **session recorder** debug mode saves stills, poses and AR frames in the session folder format above.
- A **replay harness** (desktop JVM command-line app sharing the core module) runs Phase C on a recorded session and writes the mosaic plus a metrics JSON.
- A **synthetic generator** renders a known wall image from simulated poses, with added shake, tilt, speed changes, blur, glare and pose drift, so output can be compared to ground truth.

**Unit tests**

- Homography from pose: projected corners match ground truth within 0.5 px.
- Capture trigger: correct number of stills for scripted fast, slow and stop-start walks.
- Sharpness scorer ranks blurred frames below sharp ones.
- Glare rejection removes a moving bright spot without darkening white areas.
- Note detection finds 95% of notes on synthetic walls with 7 colours.
- CSV export round-trips through a parser with commas, quotes, accents and line breaks.

**Field test set (recorded by hand, at least 10 sessions)**

- [ ] Single whiteboard, office lighting, steady walk
- [ ] 6 m sticky-note wall, uneven walking pace
- [ ] Same wall, deliberate twisting and tilting
- [ ] Glossy whiteboard under overhead lights (glare)
- [ ] Narrow corridor, 0.7 m from wall
- [ ] Tall wall, two passes (high and low)
- [ ] Tracking interruption (person walks in front)
- [ ] Dim room
- [ ] Notes on patterned or coloured background
- [ ] 10 m wall, single continuous pass

**Release acceptance**

All performance targets met on the field set; no crashes across 50 replayed sessions; every field session produces a mosaic with no visible seams at 100% zoom on notes.

## Build milestones for Claude Code

Build in this order. Each milestone ends with a working build, passing tests, and a short note in `DECISIONS.md`; do not start the next until its acceptance check passes.

1. **M0 Scaffold.** Gradle multi-module project (`app`, `capture`, `core`, `ocr-mlkit`, `storage`, `replay-cli`), OpenCV wired into Android and JVM, CI script running tests. *Done when:* app launches on an ARCore device and `./gradlew test` passes.
2. **M1 Wall lock and recorder.** ARCore session, vertical plane finding, tap to set wall frame, camera locks via Shared Camera, high-res capture with pose sidecars. *Done when:* a recorded session folder replays in the CLI.
3. **M2 Capture triggering and quality gates.** Overlap-based trigger, burst-and-pick sharpness, motion and angle gating, haptics and hints. *Done when:* scripted walks produce expected still counts and no blurred keeps.
4. **M3 Projection and simple mosaic.** Pose to homography, OpenCV warp, weighted average blend, tiled output. *Done when:* synthetic walls reproduce within 1 mm with ground-truth poses.
5. **M4 Live coverage map.** Grid, cell states, footprint rectangle, Fill gaps prompt. *Done when:* holes and weak areas show correctly in field tests.
6. **M5 Pose refinement.** Pairwise registration, global adjustment, plane re-fit. *Done when:* synthetic drift of 5 cm over 10 m is corrected to under 5 mm.
7. **M6 Glare rejection and two-band blending.** *Done when:* glare field sessions show no white blowouts where any view saw ink.
8. **M7 Viewer and library.** Deep-zoom viewer, layers, session list, rename and delete. *Done when:* smooth pan on a 24,000 px mosaic on the reference phone.
9. **M8 OCR and notes.** Windowed ML Kit OCR, note detection, colour, crops, clusters, editing. *Done when:* note detection and OCR targets met on the field set.
10. **M9 Search and export.** Full-text search, PDF, PNG, CSV, JSON, zip. *Done when:* CSV opens cleanly in Excel and PDF text is selectable.
11. **M10 Polish and field validation.** Accessibility, thermal handling, onboarding card, testing on at least three phone models from different makers. *Done when:* release acceptance criteria pass.

**Working conventions for Claude Code**

- Keep all algorithms in `core` as pure Kotlin/JVM with no Android dependency, so they run in the CLI and unit tests.
- Put every tunable threshold in `CaptureConfig` or `ProcessingConfig`.
- Use OpenCV for image work and ML Kit for OCR; record any other added library in `DECISIONS.md`.
- Camera behaviour varies by maker: log the device model and camera config with every recorded session.

**Name placeholders.** Never hard-code the product or mode names. Define them once in `gradle.properties` as `appName` (default `Swath`), `appSlug` (default `swath`) and `notesModeName` (default `Pinup`), and feed them into `@string/app_name`, the application ID suffix, the CLI binary name and all UI strings. Use neutral package names such as `app.wallcapture.core`, so a rename touches one file and no imports.

## Open questions

- [ ] Name: confirm Swath for the app and Pinup for the notes mode after Play Store and trademark checks.
- [ ] Distribution: personal sideload, GPA staff via Play internal testing, or public Play Store?
- [ ] Handwriting OCR: is on-device ML Kit accurate enough, or should there be an opt-in cloud OCR (such as Google Cloud Vision) for messy handwriting?
- [ ] Reference phone for performance targets: is a Pixel 7 representative of the phones that will be used?
- [ ] Languages for OCR beyond English and French?
- [ ] Should clusters export as Miro or Mural boards, or is CSV enough for v1?
- [ ] Is a tablet layout wanted for review in workshops?
- [ ] Privacy: do engagement sessions need a consent note or auto-blur of faces caught at the edges of stills?
- [ ] iOS: a later port, or out of scope entirely?
