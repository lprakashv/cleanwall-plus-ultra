# Agent guide

## Product and constraints

Clean Wall +Ultra is a remote-first Android TV launcher. Read [README.md](README.md) and [CONTRIBUTING.md](CONTRIBUTING.md) before a change. Keep the existing behavior unless the task asks to change it. The app supports Android 8.0 (API 26) and later; it uses Kotlin and Android Views. Use simple, readable, modular code. Prefer existing code and platform APIs. Before adding a dependency, check widely used alternatives and their licenses; use only open-source components with non-restrictive licenses. Do not add features or abstractions for hypothetical needs. Define the smallest necessary contract or format before implementing a new interface.

## Find the right code

- `MainActivity.kt` owns Home, D-pad navigation, app rows, settings, and lifecycle coordination.
- `HomeTheme.kt` defines shared colors, surfaces, and focused states. `HomeSheet.kt` implements the side sheet. `UsagePanel.kt` implements App activity.
- Define Home cards in `WidgetCatalog.kt`. `WidgetData.kt` defines the Kotlin types, `HomeWidgets.kt` renders the cards, and `WidgetUpdates.kt` schedules remote loaders.
- `HomeWallpaper.kt`, `BingWallpaper.kt`, and `PublicWallpapers.kt` manage wallpaper selection and media. `Weather.kt` uses Open-Meteo. `AndroidActivityTime.kt` and `ForegroundTime.kt` calculate app activity.
- `app/src/main/res/values/strings.xml` contains user-facing strings. Unit tests are in `app/src/test/`. The README describes setup, behavior, format, privacy, and media credits.

If a `.codegraph/` directory exists at the repository root, use CodeGraph to locate and understand code before text search or file reads. Otherwise, use normal repository search. Check callers, tests, configuration, resources, and documentation before changing a shared behavior.

## TV interaction and appearance

- Keep all actions usable with a D-pad and OK; preserve Back, long-press, focus visibility, row navigation, and focus restoration. Account for empty rows and unavailable apps.
- Reuse `HomeTheme`, existing controls, and `HomeSheet` so glass tint and focus styles remain consistent. Use legible text and clear focus from a viewing distance. Give actionable icons content descriptions.
- Respect the reduced-motion setting for transitions and wallpaper playback. Preserve the Home layout and the app's visual identity unless a task requests a change.
- Put new user-facing text in string resources. Maintain usable layouts on different TV screen sizes.

## State, data, and integrations

- Preserve saved preferences, app ordering, and hidden apps. If a preference must change, keep existing user data readable or provide a migration.
- Define widgets in Kotlin. Keep remote loaders bounded and keep their results in memory.
- Keep cache cleanup limited to app-owned scratch files and retired wallpaper entries. Keep network and file work off the UI thread. Respect activity visibility and lifecycle cleanup for workers, animation, and wallpaper playback. Handle unavailable services and permissions without blocking Home.
- Usage Access is optional; report activity only from available Android events. Weather sends a city search or selected coordinates to Open-Meteo. Keep media credits and third-party license terms intact.
- Do not commit credentials, signing keys, personal usage data, or generated build output. Ask before publishing private screenshots or changing device settings beyond the task.

## Checks and documentation

Run the checks appropriate to the change; for a full local check use the command in [CONTRIBUTING.md](CONTRIBUTING.md#make-a-change). Add focused unit tests for changed logic. For visual or navigation changes, exercise the D-pad, focus, Back, and reduced motion on a TV or emulator when available. Report checks that could not run.

Update the README, example files, and other affected parent documentation when behavior or commands change. Validate new commands. Write documentation and explanations in ASD-STE100 style. State material assumptions in documentation or comments. Keep comments concise and explain why when the code cannot.
