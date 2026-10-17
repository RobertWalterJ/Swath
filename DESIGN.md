# Swath design brief

How Swath looks, reads and flows. Written as a critique of the first build, then the design that replaces it.

## The goal

A facilitator has about a minute and people waiting. The app must feel calm, quick and obvious. Contemporary and light: warm white, one accent colour, generous space, no decoration that does not carry meaning.

## Critique of the first build

| Area | What was wrong | Why it matters |
| --- | --- | --- |
| Home | A search icon, a settings icon, a floating button and a five-item menu on every card all competed. Status read like a developer log ("capturing"). Thumbnails were tiny. | The one thing to do, start a new wall, had no priority. |
| Capture | Seven things on screen at once: coverage map, hint, distance, still count, level dial, Start or Pause, Done. A modal dialog taught the method before the user could see the camera. | Nothing told the user which step they were on or what to do next. |
| Finishing | A dialog asked "Fill gaps or Finish anyway" with no picture of the gaps. | The decision came without the evidence. |
| Processing | One line of small text on a library card. | The longest wait in the app had no screen. |
| Viewer | Three toggle chips, a list switch, sort chips and a share menu with six raw file formats. | Every control was visible all the time. |
| Output | Six formats in one flat menu. The PDF was images with no cover, no index and no page furniture. | People choose by what they want to do, not by file type. |

## Principles

1. **One screen, one job, one main button.** Everything else sits one tap deeper.
2. **Choices follow the journey.** Options appear at the moment they matter, in the order people need them.
3. **The wall is the hero.** Chrome is small, translucent and out of the way.
4. **Picture first, words second.** Icons, shape and progress carry meaning. Words are short and plain.
5. **Nothing times out or counts down.** Hints stay while the cause is true. The app never advances by itself.
6. **Read-aloud on anything with more than a line of text.** A visible speaker icon sits beside it.
7. **Colour never works alone.** Every colour has a name and a pattern or label beside it.

## The journey

```
Home ──► New wall ──► Capture: Aim ─► Ready ─► Walk ─► Check ──► Making your wall ──► Your wall
  │                                                                                    │
  ├─ Search                                                                            ├─ Wall (look around)
  ├─ Settings (detail level, deleted walls, how it works)                              ├─ Notes (read, group, edit)
  └─ Manage a wall (rename, copy, free space, delete)                                  └─ Share (report, picture, notes…)
```

Four stages for capture, always shown as a four-dot rail:

1. **Aim.** Point at the wall. Tap it to lock on.
2. **Ready.** Stand about 1.2 m away. Tap Start walking.
3. **Walk.** Walk sideways at a steady pace. The map fills in.
4. **Check.** See the gaps. Go back for them, or make the wall.

## Navigation and menus

- **Home** has one primary action (New wall), a search field and a gear. Per-wall actions move into one "Manage" sheet.
- **Your wall** has two modes in a segmented control, Wall and Notes, plus one Share button. Layers is one small button on the picture.
- **Share** is one sheet with three plain choices, Report, Picture and Notes spreadsheet. File formats for specialists sit under "More ways to share".
- **Settings** holds only things people set once: detail level, deleted walls, how it works.

## Visual system

- **Colour.** Warm paper background, white surfaces, near-black ink, one indigo accent. Sticky-note colours are data and appear only on notes. Dark theme follows the phone.
- **Type.** System sans. Body 16 sp on 24. Titles medium and semibold. No capitals for emphasis. Left-aligned, ragged right.
- **Shape.** 16 dp cards, 28 dp pill buttons, 28 dp sheet corners. Hairline borders instead of shadows.
- **Targets.** 48 dp minimum, 56 dp for primary buttons.
- **Motion.** Almost none. State changes are instant or a simple fade.

## Output design

Everything a person shares should look like it came from a professional.

- **Report (PDF, A4 landscape).** Cover with title, date, place, wall size, counts, a colour legend and the whole wall. A notes index grouped by theme. Detail pages with a mini-map showing where you are, a scale bar and page numbers. Text stays searchable.
- **Picture (JPEG).** A share-ready card: title and date on top, the whole wall, a scale bar and the colour legend underneath.
- **Notes spreadsheet (CSV).** Opens cleanly in Excel with accents intact.
- **More.** Full-detail PNG, data file (JSON), note photos (ZIP).

## Writing

All copy lives in `Copy.kt`. It follows the plain-writing standard: sentences of 20 words or fewer, main message first, plain words, no idiom. A unit test fails the build if any line breaks the length rule.
