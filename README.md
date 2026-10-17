# Swath

Swath is an Android app that turns a sideways walk past a whiteboard or sticky-note wall into one straight-on, zoomable, searchable image, with each sticky note extracted as its own record. It runs fully on the phone: no account, no upload, works in airplane mode.

"Swath" is a working name, and so is "Pinup" for the notes mode. Both live in `gradle.properties` (`appName`, `appSlug`, `notesModeName`), so renaming touches one file.

The full specification is in [`SPEC.md`](SPEC.md). Where the build departs from it, or is not finished, [`DECISIONS.md`](DECISIONS.md) says so.

## Where it stands

| Part | State |
| --- | --- |
| `core`: geometry, capture trigger, coverage grid, refinement, mosaic, glare rejection, tiling, note detection, export | Built and unit-tested on a synthetic wall. |
| `replay-cli`: generate a synthetic walk, replay a recorded session on the desktop | Built and tested end to end. |
| `capture`, `storage`, `ocr-mlkit`, `app`: ARCore capture, Room database, ML Kit OCR, Compose screens | **Written but never compiled or run on a phone.** The development PC had no Android SDK. The CI workflow is the first compile. |
| Field validation on real phones and walls | Not started. Every threshold in `CaptureConfig` and `ProcessingConfig` is an estimate. |

## Layout

```
core/         pure Kotlin/JVM: all the maths and algorithms, no Android APIs
capture/      ARCore session, GL camera background, burst and save   (Android)
storage/      Room database and per-session folders                  (Android)
ocr-mlkit/    ML Kit text recognition behind core's TextReader       (Android)
app/          Jetpack Compose screens: Library, Capture, Viewer, Search (Android)
replay-cli/   desktop tool: synthetic walls and Phase C on recorded sessions
```

## Building

You need a JDK 17 or newer. The Android modules also need the Android SDK (set `ANDROID_HOME`, or put `sdk.dir=...` in `local.properties`). Without an SDK Gradle skips them and builds only `core` and `replay-cli`.

```bash
./gradlew :core:test :replay-cli:test      # JVM tests, no phone or SDK needed
./gradlew assembleDebug                    # debug APK, needs the Android SDK
```

Install the APK on an ARCore-supported phone. The first launch asks for camera access and, if needed, installs Google Play Services for AR.

## Trying the pipeline without a phone

```bash
./gradlew :replay-cli:installDist
replay-cli/build/install/swath-replay/bin/swath-replay generate sessions/demo --width-mm 6000
replay-cli/build/install/swath-replay/bin/swath-replay replay sessions/demo --px-per-mm 1
```

`generate` renders a synthetic sticky-note wall as seen by a shaky, drifting, glare-affected walk and writes it in the recorded-session folder format. `replay` runs refinement, builds the mosaic and tile pyramid, finds notes, and writes `mosaic/full.png`, `tiles/`, `notes.csv`, `session-export.json` and `metrics.json`. A session recorded on a phone replays the same way, which is how the algorithms get tuned from the command line.

## Building on a PC with HTTPS-scanning antivirus

If Gradle cannot download plugins and fails with "Plugin ... was not found", antivirus software (AVG's Web Shield, for example) may be re-signing HTTPS traffic with a certificate Java does not trust. Make a truststore that includes it and point Java at it:

```bash
cp "$JAVA_HOME/lib/security/cacerts" ~/.gradle/avg-truststore.jks
keytool -importcert -noprompt -alias avg-web-shield -file <your AV's root certificate .pem> \
  -keystore ~/.gradle/avg-truststore.jks -storepass changeit
```

On Windows, `tools/gradle-local.bat` uses `%USERPROFILE%\.gradle\avg-truststore.jks` automatically if it exists, and runs Gradle with Android Studio's bundled JDK.

## Continuous integration

`.github/workflows/ci.yml` runs the JVM tests and then builds the debug APK (uploaded as a workflow artifact). It uses the committed Gradle wrapper.
