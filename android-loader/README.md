# TournaTest Android USB Firmware Updater

An Android app for installing the same application firmware ZIPs used by the Windows TournaTest updater. It connects directly to the test box's Silicon Labs CP210x USB-to-UART interface. A computer is not required when running the app.

## Requirements

- Android 8.0 or later, with USB host/OTG support.
- A USB **data** cable for the test box and a USB host/OTG adapter if needed for the phone's connector.
- A TournaTest Digital test box with its Adafruit-compatible serial bootloader installed and its battery disconnect switch on. Normal mode also requires recognizable TTArm startup messages; **Force flash** skips that message requirement.
- Enough phone and test-box battery power to finish the update. A powered USB hub may help if the phone cannot supply the required USB power; hub compatibility needs testing.

No root access, Windows driver, Bluetooth pairing, or Internet permission is needed. The app runs offline with its bundled firmware. A phone may require enabling OTG in its settings.

## Install and use

1. Copy `TournaTest-Updater-debug.apk` (version **0.3.0**) from this folder to the phone and open it. It can be installed over the previous APK built with the same signing key. If Android prompts, allow installation from the app you are using to open the APK. This is a development-signed APK for testing, not a Play Store release.
2. Open **TournaTest Updater** and connect the test box to the phone.
3. Select a bundled firmware build, or tap **Choose a downloaded ZIP**. Leave the firmware ZIP intact.
4. Tap **Scan USB devices** and select the test box's CP210x interface.
5. Tap **Connect and Update**. Allow USB access. Notification permission is optional; allowing it makes background progress visible.
6. Wait while the app validates the ZIP and checks the test box's startup message. If the message is missing, enable **Force flash (skip TTArm message check)** and tap **Connect and Update** again. You can also select force mode before connecting. Review the firmware filename and application SHA-256.
7. Tap **Install this firmware** (or **Force flash this firmware**), then confirm **Install**.
8. Keep the USB cable connected until the app reports completion. Normal mode waits for activation and checks that TournaTest starts again. Force mode reports **Firmware transfer complete** without requiring startup messages; check the test box's display and operation afterward.

The service owns the update independently of the screen, so rotating the phone or switching apps does not restart the transfer. A foreground notification and a bounded CPU wake lock support background operation. Do not force-stop the app, reboot the phone, or disconnect the cable during an update. **Cancel** is available before installation starts.

## Switching test boxes

After an update finishes, tap **Flash another box**. This clears the completed session, parsed firmware, previous device, pending permission callbacks, progress, result details, log, and completion notification, then rescans USB devices. The selected firmware ZIP and force-flash choice are kept for batch updates. Copy the log first if you need it. This button is also available after a failed or cancelled update; it cannot reset or interrupt a running flash.

Disconnect the finished box, connect the next one, and tap **Connect and Update**. You can also connect the next box before pressing **Flash another box**. The app refreshes the USB list on connection changes, when returning to the app, and every two seconds while the screen is active. Each row shows **access required** or **access granted** for the currently attached box. Android USB access must be granted for each new connection; access to a previous box is not reused.

Permission results are received even if Android stops the screen for its USB dialog, and each request has its own token. Returning to the app before a dialog result arrives no longer discards that result. **Scan USB devices** remains available while waiting for USB permission so you can retry a request. Disconnecting a box before confirmation cancels that prepared update, allowing the next box to start a fresh one.

Force mode skips the application startup-message checks before and after transfer. It still requires Android USB access, a working CP210x connection, a valid firmware ZIP, and valid bootloader acknowledgements. It attempts to use the installed bootloader; it cannot install a missing bootloader or repair a USB cable or permission failure.

## Build

Open **this `android-loader` directory** as a project in Android Studio. Install Android SDK Platform 35 and Build Tools 35.0.0, use JDK 17, and let Gradle sync. `local.properties` should identify your Android SDK, or set `ANDROID_HOME`.

The checked-in Gradle wrapper downloads Gradle 8.11.1 and verifies its distribution SHA-256. The app uses Android Gradle Plugin 8.9.2 and `usb-serial-for-android` 3.9.0. First builds need Internet access to retrieve build dependencies. The app itself does not use the network.

On Windows, from this folder:

```powershell
.\build.ps1
```

This runs protocol and Android lifecycle tests, builds the debug APK, runs Android lint, and copies the APK plus its SHA-256 file into this folder. If PowerShell execution policy prevents running the script, run the Gradle command directly:

```powershell
.\gradlew.bat :protocol:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

On macOS/Linux:

```sh
sh ./gradlew :protocol:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The Gradle APK output is `app/build/outputs/apk/debug/app-debug.apk`. Android Studio can also install the debug build on a connected phone. A distributable production release needs a private signing key maintained outside source control; use Android Studio's signed APK workflow. Debug APKs built with different debug keys cannot update each other in place.

`app:bundleFirmware` copies `TTArm-build*.zip` from the **repository root** into generated application assets, newest filename first in the app. Add a firmware ZIP there and rebuild to include it. There are no duplicate firmware source files to maintain. Keep the parent repository files when copying this Android project.

## Implementation

- `app`: native Java Android UI, system document picker, USB permission flow, CP210x serial adapter, foreground update service, progress, and copyable logs.
- `protocol`: Android-independent Java firmware parser, startup detector, SLIP/HCI codec, legacy Adafruit DFU transport, and normal/force update workflow.
- USB settings: 115200 baud, 8 data bits, no parity, 1 stop bit, no flow control.
- Identity gate: in normal mode, DTR reset and a maximum 15-second wait for `Initializing channels`, preserving partial messages across USB reads. The same check runs again after confirmation, before any DFU write. Explicit force mode skips these checks.
- Firmware: application-only DFU 0.5 ZIPs with nRF52 device type `0x0052`. The parser bounds compressed/expanded sizes, rejects unsafe and duplicate paths, checks manifest/init agreement, and verifies application CRC-16. Bootloader, SoftDevice, and secure DFU packages are rejected.
- Transfer: starts at sequence 1, uses 512-byte firmware chunks, validates acknowledgement headers/sequence numbers, retries a missing acknowledgement at most twice using the identical packet, and preserves Adafruit's erase/write/activation delays. Partial USB writes fail rather than restarting a partially sent frame.
- Completion: waits through the conservative dual-bank activation delay before closing/resetting the connection. Normal mode then checks application startup; force mode returns a separate transfer-complete result without a startup check. The existing startup message does not expose an installed-build query; neither result is an independent readback of the flashed image. Confirm the displayed build on the test box.

CRC and the displayed SHA-256 identify corruption/content; they do not authenticate a firmware publisher. Use firmware from a trusted TournaTest source. The optional identity gate reproduces the existing updater's behavior. Force mode permits an update attempt without a running or identifiable application, provided the serial bootloader can still respond.

## Validation and remaining hardware checks

The implementation has passed APK compilation, Android lint, and 54 tests: 28 protocol/workflow tests and 26 Android lifecycle tests (13 scenarios each on simulated Android 8 and Android 15). Tests cover all three repository firmware ZIPs, known frames generated by Adafruit's unmodified Python implementation, sequence wraparound, escaped/fragmented/coalesced USB data, stale/corrupt/lost acknowledgements, bounded retries, partial-write failures, split startup messages, invalid firmware rejection, delayed/denied permission results, box switching, stale callbacks, rotation, and normal/force behavior. Reset tests check both flash modes, cleanup after success/failure/cancellation, notification and wake-lock release, preserved batch choices, fresh permission requests for the next box, rotation after reset, ignored late callbacks, and refusal to reset an active flash.

The user reported a successful physical update with version 0.1.0 and difficulty resetting the app after subsequent flashes. Version 0.3.0 adds the explicit **Flash another box** reset flow, which still needs physical phone/test-box verification. Before distributing this loader:

1. Test USB enumeration, permission denial/retry, and device identification on a representative phone and cable.
2. Complete an update on a test box with a known serial bootloader recovery route; verify the displayed build and normal test functions afterward.
3. Repeat with screen rotation, backgrounding, and notification permission denied. Ensure the same update continues.
4. After each normal and forced update, tap **Flash another box** and update a second box. Check that progress and results reset, the firmware selection is kept, and access is requested for the new box.
5. Check that normal mode rejects an unidentifiable CP210x device without sending DFU packets, and that force mode works on a TournaTest lacking startup messages. Disconnects, denied USB access, invalid ZIPs, and missing bootloader acknowledgements must still fail clearly.

If an update is interrupted and the normal application no longer starts, try force mode if its serial bootloader still responds. If automatic entry fails, the board may need to be placed manually in serial DFU mode or recovered with the appropriate desktop/programming tools.

## Troubleshooting

**No USB device:** Confirm OTG/host support, turn on OTG if your phone requires it, use a data cable, reconnect, and scan. Close other USB serial apps. The screen distinguishes no USB device from an unsupported interface; the copied log includes all detected USB vendor/product IDs and access status. Only a single-port CP210x interface is accepted.

**USB access denied:** Tap **Connect and Update** again and allow access. Android USB permission is per attached device and is lost on disconnect. If a request appears stuck, tap **Scan USB devices**, then retry. Force mode also needs USB access.

**Startup message not found:** This means the USB port opened, but the expected TTArm text was not received. Select **Force flash (skip TTArm message check)** and attempt the update again.

**Transfer failed or startup could not be verified:** Copy the log before leaving the app, record the phone model/Android version, and inspect the test box's display. Do not assume success based only on the progress bar. An interrupted box may require bootloader recovery as described above.

## References and notices

- [Android USB host API](https://developer.android.com/develop/connectivity/usb/host)
- [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)
- [Adafruit serial DFU reference](https://github.com/adafruit/Adafruit_nRF52_nrfutil)

Third-party license texts are included in `app/src/main/res/raw/third_party_notices.txt` and are accessible through **Open-source licenses** in the app. The Gradle wrapper's Apache 2.0 license is in `gradle/LICENSE`.
