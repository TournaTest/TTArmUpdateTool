# Downloads

For Android, install [TournaTest-Updater-beta.apk](android/TournaTest-Updater-beta.apk).
This is version 0.4.0, signed with the existing debug key, and can update the
previous test app in place. It includes all five firmware ZIPs. The adjacent
`.sha256` file is a checksum; only the APK needs to be copied to the phone.

The `TTArm*.zip` files in this folder are test-box firmware, not Android apps.
Leave them zipped. Both the Windows updater and Android build read these files.
Choose the firmware matching the test box's nRF52832 or nRF52840 board.

Android source and build tools are in `../android-loader/`. Run its `build.ps1`
to validate and replace the beta APK and checksum in `android/`.
