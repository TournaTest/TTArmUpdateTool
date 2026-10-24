# TournaTest Android USB Firmware Updater

An Android app for installing the same application firmware ZIPs used by the Windows TournaTest updater. It connects directly to the test box's Silicon Labs CP210x USB-to-UART interface. A computer is not required when running the app.

## Requirements

- Android 8.0 or later, with USB host/OTG support.
- A USB **data** cable for the test box and a USB host/OTG adapter if needed for the phone's connector.
- An already-programmed TournaTest Digital test box, with its battery disconnect switch on.
- Enough phone and test-box battery power to finish the update. A powered USB hub may help if the phone cannot supply the required USB power; hub compatibility needs testing.

No root access, Windows driver, Bluetooth pairing, or Internet permission is needed. The app runs offline with its bundled firmware. A phone may require enabling OTG in its settings.

## Install and use

1. Copy `TournaTest-Updater-debug.apk` from this folder to the phone and open it. If Android prompts, allow installation from the app you are using to open the APK. This is a development-signed APK for testing, not a Play Store release.
2. Open **TournaTest Updater** and connect the test box to the phone.
3. Select a bundled firmware build, or tap **Choose a downloaded ZIP**. Leave the firmware ZIP intact.
4. Tap **Scan USB devices** and select the test box's CP210x interface.
5. Tap **Connect and Update**. Allow USB access. Notification permission is optional; allowing it makes background progress visible.
6. Wait while the app validates the ZIP and checks the test box's startup message. Review the firmware filename and application SHA-256.
7. Tap **Install this firmware**, then confirm **Install**.
8. Keep the USB cable connected until the app reports completion. The app waits for activation and checks that TournaTest starts again before reporting success.

The service owns the update independently of the screen, so rotating the phone or switching apps does not restart the transfer. A foreground notification and a bounded CPU wake lock support background operation. Do not force-stop the app, reboot the phone, or disconnect the cable during an update. **Cancel** is available before installation starts.

## Build

Open **this `android-loader` directory** as a project in Android Studio. Install Android SDK Platform 35 and Build Tools 35.0.0, use JDK 17, and let Gradle sync. `local.properties` should identify your Android SDK, or set `ANDROID_HOME`.

The checked-in Gradle wrapper downloads Gradle 8.11.1 and verifies its distribution SHA-256. The app uses Android Gradle Plugin 8.9.2 and `usb-serial-for-android` 3.9.0. First builds need Internet access to retrieve build dependencies. The app itself does not use the network.

On Windows, from this folder:

```powershell
.\build.ps1
```

This runs protocol tests, builds the debug APK, runs Android lint, and copies the APK plus its SHA-256 file into this folder. If PowerShell execution policy prevents running the script, run the Gradle command directly:

```powershell
.\gradlew.bat :protocol:test :app:assembleDebug :app:lintDebug
```

On macOS/Linux:

```sh
sh ./gradlew :protocol:test :app:assembleDebug :app:lintDebug
```

The Gradle APK output is `app/build/outputs/apk/debug/app-debug.apk`. Android Studio can also install the debug build on a connected phone. A distributable production release needs a private signing key maintained outside source control; use Android Studio's signed APK workflow. Debug APKs built with different debug keys cannot update each other in place.

`app:bundleFirmware` copies `TTArm-build*.zip` from the **repository root** into generated application assets, newest filename first in the app. Add a firmware ZIP there and rebuild to include it. There are no duplicate firmware source files to maintain. Keep the parent repository files when copying this Android project.

## Implementation

- `app`: native Java Android UI, system document picker, USB permission flow, CP210x serial adapter, foreground update service, progress, and copyable logs.
- `protocol`: Android-independent Java firmware parser, startup detector, SLIP/HCI codec, and legacy Adafruit DFU transport.
- USB settings: 115200 baud, 8 data bits, no parity, 1 stop bit, no flow control.
- Identity gate: DTR reset and a maximum 15-second wait for `Initializing channels`, preserving partial messages across USB reads. The same check runs again after confirmation, before any DFU write.
- Firmware: application-only DFU 0.5 ZIPs with nRF52 device type `0x0052`. The parser bounds compressed/expanded sizes, rejects unsafe and duplicate paths, checks manifest/init agreement, and verifies application CRC-16. Bootloader, SoftDevice, and secure DFU packages are rejected.
- Transfer: starts at sequence 1, uses 512-byte firmware chunks, validates acknowledgement headers/sequence numbers, retries a missing acknowledgement at most twice using the identical packet, and preserves Adafruit's erase/write/activation delays. Partial USB writes fail rather than restarting a partially sent frame.
- Completion: waits through the conservative dual-bank activation delay before closing/resetting the connection, then checks application startup. The existing startup message does not expose an installed-build query; this checks that the application runs, not an independent readback of the flashed image. Confirm the displayed build on the test box during hardware validation.

CRC and the displayed SHA-256 identify corruption/content; they do not authenticate a firmware publisher. Use firmware from a trusted TournaTest source. The identity gate reproduces the existing updater's behavior; the app does not program a blank Feather or bypass device detection to recover an interrupted update.

## Validation and remaining hardware checks

The implementation has passed APK compilation, Android lint, and 21 JVM tests. Tests cover all three repository firmware ZIPs, known frames generated by Adafruit's unmodified Python implementation, sequence wraparound, escaped/fragmented/coalesced USB data, stale/corrupt/lost acknowledgements, bounded retries, partial-write failures, split startup messages, and invalid firmware rejection.

No physical Android-phone/Test-Box update has been performed as part of development. Before distributing this loader:

1. Test USB enumeration, permission denial/retry, and device identification on a representative phone and cable.
2. Complete an update on a test box with a known serial bootloader recovery route; verify the displayed build and normal test functions afterward.
3. Repeat with screen rotation, backgrounding, and notification permission denied. Ensure the same update continues.
4. Check that an unrelated CP210x device fails identification without receiving DFU packets, and that disconnects/invalid ZIPs produce clear failures.

If an update is interrupted and the normal application no longer starts, the startup gate may prevent retrying from this app. The board may need to be placed manually in serial DFU mode and recovered with the appropriate desktop/programming tools. This first version does not expose a recovery bypass.

## Troubleshooting

**No USB device:** Confirm OTG/host support, turn on OTG if your phone requires it, use a data cable, reconnect, and scan. Close other USB serial apps. Only a single-port CP210x interface is accepted.

**USB access denied:** Tap **Connect and Update** again and allow access. Android USB permission is per attached device and is lost on disconnect.

**Startup message not found:** Confirm the correct test box is selected and that it already runs TournaTest firmware. Reconnect and retry. The app deliberately refuses to flash an unknown device.

**Transfer failed or startup could not be verified:** Copy the log before leaving the app, record the phone model/Android version, and inspect the test box's display. Do not assume success based only on the progress bar. An interrupted box may require bootloader recovery as described above.

## References and notices

- [Android USB host API](https://developer.android.com/develop/connectivity/usb/host)
- [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)
- [Adafruit serial DFU reference](https://github.com/adafruit/Adafruit_nRF52_nrfutil)

Third-party license texts are included in `app/src/main/res/raw/third_party_notices.txt` and are accessible through **Open-source licenses** in the app. The Gradle wrapper's Apache 2.0 license is in `gradle/LICENSE`.
