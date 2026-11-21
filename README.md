# Quirk Deck

Quirk Deck is an Android TV launcher. Use Left and Right to browse a row and OK to open an app. Focused cards grow and the other cards dim. Reduce motion disables the transition.

- **Watch** includes the streaming apps Quirk Deck recognizes. **Utilities** contains the other apps. **Google Play** opens from the top bar next to Settings.
- Hold OK on a card to open the inline actions. Choose **Pick up to move**, use Left or Right, then press OK to drop or Back to cancel. The actions also let you move an app between Watch and Utilities or hide it. Use **Settings → Hidden apps** to open a hidden app or restore it.
- From a Watch tile, press Up for Settings. Down from Settings or Google Play returns to the last focused app. Use the weather or activity widget to move between the Watch and Utilities rows.
- Open **Settings** to see the current wallpaper, glass tint, motion, and weather city. Select an item to change it. Settings and app menus open from the side so the carousel stays visible.
- The clock uses the TV's time settings. The clock and weather reading animate when they change. Weather refreshes every 15 minutes while Home is visible. Open-Meteo receives the city search or the selected city's coordinates.
- Select **Active time today** to open App activity. Move Left or Right through the chart to preview a day. Press OK to browse the day’s apps and select one to see its 7-day trend. Quirk Deck reads available Android usage events on the TV. It counts foreground time for installed TV apps, including apps opened outside Home, and excludes background, locked, and display-off time when Android reports those events. Days outside the available event history can show zero.
- The daily Bing photo includes its credit on Home. The launcher keeps the latest image on the TV for offline use.

## Development

Install JDK 17, Gradle, Android SDK platform 35, and build tools 35.0.0. Set `ANDROID_HOME` to the SDK directory. Connect an authorized Android TV over ADB.

Build and check the app:

```sh
gradle :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Install the debug build:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.quirkdeck.launcher/.MainActivity
```

The debug APK is signed with the local Android debug key. Keep that key to install later updates over this build. Google Play does not distribute updates for this app.

## Deployment

The installed version uses the debug build. A production release needs a release signing key and a release distribution method. These are not configured or validated in this project.

To use Quirk Deck, select it as the default Home app in Android Settings. Some TV firmware may give the built-in Home app priority. Disable it if Android does not select Quirk Deck as the default.

Android requires **Usage Access** for active-time statistics. Some TV firmware may not open the Android Usage Access screen. With the device owner's approval, enable access for Quirk Deck after installation.

```sh
adb shell cmd appops set --user 0 dev.quirkdeck.launcher GET_USAGE_STATS allow
```

Set the same operation to `default` to revoke the grant.
