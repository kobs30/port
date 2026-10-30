# PortScan 0.1.1 — Android

An offline Android prototype for reading container identification numbers. For curved surfaces, it can assemble a number from overlapping views even when no single frame contains the entire number.

## Install

[Download the APK](artifacts/PortScan-debug.apk). Requires Android 8.0 or later. This is a debug-signed test build, not a Google Play release. Install it over the previous build to retain history and captures.

Open the APK on your phone, allow installation from the selected source, and grant camera access. The OCR model is included in the APK. No account or internet connection is required; the merged manifest has no internet permission.

The app interface is English, including buttons, statuses, dialogs, and error messages. External Android permission dialogs, file pickers, and sharing apps may follow the device language.

The original version was committed and pushed before these changes, with tag [v0.1.0](https://github.com/kobs30/port/tree/v0.1.0). Version 0.1.1 adds result filtering and the English interface.

## Verified / All

The selector is **above the camera**. **Verified** is selected by default, and the app remembers your choice across restarts.

- **Verified** shows complete numbers whose ISO 6346 check digit matches. It applies to the current scan, history, full-list dialog, and CSV export. Unverified partial numbers, fragments, and other digits are hidden. Repeat observation is not required; it remains a separate label.
- **All** also shows candidates with a mismatched check digit, the partial current number, and recognized fragments. CSV includes all complete numbers.

Filtering does not delete results or change recognition. Switching modes immediately displays previously collected results. The verified counter shows visible results out of the total history. Clearing history removes **all** results, including hidden ones, after confirmation.

A matching check digit helps detect errors; it does not guarantee that OCR or fragment assembly is correct.

## Scan a container

1. Select **New scan** before moving to another container. This button is below the current scan card, next to **Import +**; scroll down if necessary.
2. Point the camera at the beginning of the number, including the letter prefix. Tap the preview to focus.
3. Move slowly along the surface. Keep at least **three readable characters** overlapping between views. Avoid glare, motion blur, and steep angles.
4. In **All** mode, the number fills in as fragments arrive; dots mark unknown characters. In **Verified** mode, it appears only after the complete number passes the check-digit test.
5. Select **Video** or **Burst** to preserve source media. Video has no audio. A burst captures up to 60 photos, with a delay after each image is processed. Open saved media in **Captures**.
6. Compare the final number with the container. Tap a result to copy it, or select **CSV** to export the selected results.

Scanning starts automatically once camera permission is granted. **Pause scanning / Resume scanning** controls live recognition. **New scan** is disabled while recording, taking a burst, or processing files; stop that operation first.

If a phone cannot run Preview + VideoCapture + ImageAnalysis simultaneously, recognition runs after recording stops. The app explains this while recording.

**Import +** starts a new scan from photos or video of a single container. Files are decoded sequentially, one image at a time. Video is sampled every 200 ms. Stopping processing retains results already found. Low-frame-rate videos may return duplicate decoded frames, so repeated observations are not necessarily independent evidence.

Duplicates are combined within one scan. Complete-number history persists across launches, up to 1,000 entries; export and clear it when full. Unfinished fragments live in memory and are lost when the process ends. Saved media can be scanned again through **Captures**. Uninstalling the app removes its internal captures; share any files you need to retain first.

## Container numbers and validation

ISO 6346 identification consists of **3 owner letters + U/J/Z + 6 serial digits + 1 check digit**. Freight containers use `U`. Example: `CSQU 305438 3` — four letters and seven digits. See [BIC identification numbers](https://www.bic-code.org/identification-number/) and the [BIC check-digit explanation](https://www.bic-code.org/check-digit-calculator/).

- **Check digit ✓**: the ISO 6346 check digit matches. This does not check registration or ownership in the BIC registry.
- **Seen again**: every character is supported by a fragment observed in at least two processed frames.
- **Assembled**: the complete number was not observed in one OCR region. Compare it carefully with the physical marking.
- **Check digit mismatch**: a complete candidate was saved, but its check digit failed.
- Multiple candidates remain available in **All** mode instead of silently choosing one.

Other numeric markings appear under **Numbers in frame** in All mode. Only complete container-number candidates are stored in history and exported to CSV.

## Performance and prototype limits

- Native CameraX and bundled ML Kit Text Recognition; no frames are sent to a server.
- Camera YUV buffers go directly to ML Kit, without JPEG encoding in the recognition loop.
- `KEEP_ONLY_LATEST`, one OCR request at a time, and a maximum start rate of 8 frames/second. This is a rate limit, not a promised throughput.
- Preferred analysis resolution: 1280×720, subject to camera support. Imported photos are limited to a 1600-pixel longest side; sampled video frames to 1280 pixels.
- The screen reports OCR time and result arrival rate. OCR time excludes capture, decode, and rendering; the arrival rate includes delays between results.
- Assembly is bounded to 96 fragments and 128 hypotheses. Reaching a limit shows a warning. Repeated observations reuse cached assembly results.

Assembly uses **text overlap**, not 3D surface reconstruction. It needs readable fragments and at least one view containing the complete four-letter prefix. Strong curvature, vertical markings, rust, glare, or steep angles can defeat general-purpose OCR. Geometric unwrapping and a model trained specifically on container markings are not implemented.

Disconnected fragments cannot be reliably assembled. The app does not invent a missing check digit or automatically substitute `O/0`, `I/1`, or `B/8`. Check-digit matches cannot exclude OCR mistakes or accidental combinations of different markings. Use one scan per container; separate containers in the same scene are not tracked independently. A dropped frame might contain the only readable view, so rapid movement may require a slower repeat scan.

**Real-container accuracy and phone performance have not been benchmarked.** These require representative port videos and the target Android device.

## Build and validate

Requires JDK 17+ (built with JDK 21), Android SDK Platform 36, and Build Tools 36.0.0. Uses Gradle Wrapper 9.3.1 and Android Gradle Plugin 9.1.0. Dependency versions are pinned.

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
sh scripts/build-local.sh
```

The script runs `testDebugUnitTest lintDebug assembleDebug`. In the original workspace it can also use an existing toolchain from the adjacent project. Build output: `app/build/outputs/apk/debug/app-debug.apk`.

Core tests cover check digits, overlapping assembly in different frame orders, repeated observations, conflicting candidates, missing symbols, scan isolation, and memory limits. These tests exercise OCR text assembly, not image recognition accuracy.

On a device, test offline camera access, focus, rotation, pause/resume, denied permissions, video start/stop, bursts, import cancellation, and CSV export. Test Verified and All against both valid and invalid complete numbers, switch while paused, and confirm that the preference survives restart. Check New scan resets the current number without deleting history. Then evaluate at least 20 known container numbers, including curved surfaces, measuring correct complete readings, false complete candidates, time to first result, time to complete number, and heat during sustained use.

Main files: `MainActivity.java` for camera and UI; `OcrEngine.java` for OCR; `MediaReader.java` for sequential decoding; `core/ContainerAssembler.java` for fragment assembly; `core/ContainerCode.java` for check digits; `ScanHistory.java` for stored results, filtering, and CSV.
