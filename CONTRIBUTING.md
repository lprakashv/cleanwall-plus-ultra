# Contributing to Clean Wall +Ultra

Thank you for helping improve Clean Wall +Ultra. This guide covers the local workflow and the information that helps reviewers test a change.

## Before you start

Use the GitHub issue templates to report a bug or suggest a feature. Open an issue to discuss a large change. For a bug, include the TV model, Android version, steps to reproduce, and expected and actual results. Remove personal data from logs and screenshots.

Project code is available under the [MIT License](LICENSE). Third-party media and trademarks may have separate rights and terms; keep their credits and terms intact.

## Make a change

1. Clone the repository and create a branch for your change.
2. Set up JDK 17, Gradle 9.9.2, Android SDK platform 35, and build tools 35.0.0 as described in the [README](README.md#development).
3. Keep changes focused. Add or update tests when behavior changes. Update the README when a user-facing feature or setup step changes.
4. Run the checks below from the repository root:

   ```sh
   gradle :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:createDebugUnitTestCoverageReport
   ```

5. For UI changes, test with a TV remote or emulator D-pad. Check focus, Back, long-press actions, and reduced motion where applicable. Include the device and Android version in your pull request.
   For Continue watching, check each view alone and both together with an app that shares Watch Next entries. Check artwork, progress bars, the app icons, and the shared source selection. Confirm that the source list matches Watch and the open list keeps Utilities in place. Check the Left, Right, and Down shortcuts to Utilities, permission denial, playback links, and D-pad navigation when either optional row is hidden.
6. Open a pull request. Describe the problem, the change, and the checks you ran. Add screenshots or a silent recording for visible changes. Use sample data if you show activity or viewing history. Mention any known limits.

The coverage report is in `app/build/reports/coverage/test/debug/index.html`. GitHub Actions runs the same build, lint, unit tests, and coverage tasks on pushes and pull requests. CI does not test on a TV or emulator.

## Check TV compatibility

For release checks, use a physical Android TV and current Android TV and Google TV emulator images. Include a 720p profile and a 16 KB page-size image when available.

1. Install the APK and open it from the TV's Apps list. If the TV lets you select a Home app, check the Home button after selection.
2. Use the D-pad to reach all visible rows. Open an app and return to the launcher. Hold OK on a card and check that Back cancels moving the card and restores focus.
3. Check that the Home controls fit inside the central 90% of the screen. Open Settings and change the motion setting on a test device. Check that the menus and focus remain usable in both modes.
4. Check that you can return to Home when Google Play or a system settings screen is unavailable. Record the TV model or emulator image, Android version, and any feature limits.

Use this ADB command to check whether Android can resolve the TV launch intent. A successful run reports `Status: ok`.

```sh
adb shell am start -W -a android.intent.action.MAIN \
  -c android.intent.category.LEANBACK_LAUNCHER -p dev.lprakashv.cleanwall
```

See [Deployment](README.md#deployment) to sign an installable APK for a release. Use the [TV install steps](README.md#get-started) to check an APK before you distribute it.

Do not include debug keys, credentials, private device data, or locally generated build files in a contribution.
