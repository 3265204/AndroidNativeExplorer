# ANE — Android Native Explorer

[English](README-en.md) | [中文](README.md)

ANE (Android Native Explorer) is an Android local file manager written in Kotlin, targeting touch, mouse and desktop-like Android environments such as DeX. The main interface is drawn with a custom `View`, and it ships with image, video, audio viewers and a text editor with code highlighting.

## Features

- Browse, create, rename, copy, cut, paste, move and delete files and folders.
- ZIP, 7z, RAR, TAR and common gzip/bzip2/xz files can be browsed layer by layer like Windows Explorer, or extracted with one tap from the long-press menu or the browse page; password prompts appear automatically for encrypted archives.
- Unlimited in-session undo; deleted content is staged in a hidden recycle directory and auto-destroyed when the process closes.
- List and grid layouts, light/dark themes, thumbnails and long-filename scrolling.
- Multi-select, swipe selection, long-press drag and tab-bar drag sorting.
- Pinned tabs, temporary tabs and tab session persistence.
- Editable address bar, system back history and exit confirmation.
- Mouse right-click and common desktop shortcuts.
- Automatic image, video and audio playlists within a folder.
- Text editing, save, Tab/Shift+Tab indentation and basic code highlighting.
- Unified Dock tab management: per-item switch, pin, close, bulk cleanup, and control over whether to restore temporary tabs on startup.

## Usage

Grant "All files access" on first launch; otherwise the app can only access the limited directories allowed by the system.

- Single-click a file or folder: select.
- Double-click: open the file or enter the folder.
- Long-press a file: show the file menu; after moving, enter file dragging.
- Multi-select mode: a single click only toggles selection and does not open a file; swipe from the selection area for quick selection.
- The plus button at the bottom-right: enter/exit multi-select, copy, cut, paste, delete, create and undo; ineffective operations are hidden automatically.
- Tab bar: switch directories; long-press to enter tab editing or drag sorting.
- Address bar: tap to type the full path of a folder or file.
- System back: prefer the current tab's parent or history; ask to exit when there is nowhere to go.
- Mouse right-click: open the corresponding menu on files, tabs or blank areas.

Common desktop shortcuts:

| Shortcut | Behavior |
| --- | --- |
| `Ctrl+C / X / V` | Copy, cut, paste |
| `Ctrl+Z` | Undo |
| `Ctrl+A` | Select all and enter multi-select mode |
| `Ctrl+L` | Edit address |
| `Ctrl+Shift+N` | New folder |
| `F2 / Delete / Enter / F5` | Rename, delete, open, refresh |
| `Alt+Left / Alt+Up` | History back, enter parent directory |

`Esc` should not be mapped to an app action, mainly considering the special handling some tablets give to Esc.

Because of plugin permissions, do not install untrusted plugins.

## Development environment requirements

- Windows, macOS or Linux.
- Android Studio, or a usable command-line Android SDK.
- Android SDK Platform 36 and Build Tools.
- JDK 21 (current verified environment); the minimum compatible version follows the Android Gradle Plugin requirements.
- Gradle Wrapper downloads Gradle 9.4.1 automatically; no global Gradle install required.
- Debugging devices need Android 6.0 (API 23) or higher.

Current build configuration:

| Project | Value |
| --- | --- |
| Application ID | `com.ane.filemanager` |
| Min SDK | 23 |
| Target SDK | 35 |
| Compile SDK | 36 |
| Android Gradle Plugin | 9.2.1 |

## Build

Run in the project root:

```powershell
./gradlew :app:assembleDebug
```

Use `./gradlew.bat :app:assembleDebug` on Windows PowerShell.

The resulting APK is at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

You can also open the project in Android Studio, wait for the Gradle sync, then choose `Build > Build APK(s)`.

Install to a connected device via adb:

```powershell
adb devices
adb -s <device serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

After enabling local ADB in MuMu, you can connect by the actual port. Example for the developer environment:

```powershell
adb connect 127.0.0.1:16384
adb -s 127.0.0.1:16384 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 127.0.0.1:16384 shell am start -n com.ane.filemanager/.MainActivity
```

## Development

The code structure, responsibility boundaries, modification workflow and testing requirements are in [docs](docs/README.md). Before making changes, read in order:

1. [Architecture](docs-en/architecture.md)
2. [Development conventions](docs-en/development.md)
3. [Testing guide](docs-en/testing.md)

Please do not add non-default plugin optimization PRs under this project; plugins should be developed as a separate project, and this project is only for the file-manager system itself.

## Notes

If you like this project, perhaps you could give it a star.

The project supports secondary modification and closed-source monetization on the premise of adding new features, but the project cannot be resold; the specific license boundaries follow the [LICENSE](LICENSE).
