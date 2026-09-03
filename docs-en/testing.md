# Testing Guide

## 1. Compile check

Run in the project root:

```powershell
./gradlew :app:assembleDebug
```

Run Android static checks when needed:

```powershell
./gradlew :app:lintDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Record and evaluate compile warnings; new code must not introduce unexplained warnings.

## 2. ADB preparation

List devices:

```powershell
adb devices
```

Install or upgrade over the old build:

```powershell
adb -s <device serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch:

```powershell
adb -s <device serial> shell am start -n com.ane.filemanager/.MainActivity
```

Force-stop and relaunch, to verify persistence:

```powershell
adb -s <device serial> shell am force-stop com.ane.filemanager
adb -s <device serial> shell am start -n com.ane.filemanager/.MainActivity
```

The MuMu ADB address depends on local configuration; the common current example is `127.0.0.1:16384`. Do not depend on this address in code or product logic.

## 3. Test data

- Put ADB screenshots in `temp/photo/` and ideas or design drafts in `temp/docs/`. Do not commit the actual contents of these directories.
- Do not place test media at the project root.
- Delete, move and undo tests only use explicitly created test files; never experiment on the user's real files.
- Screenshot names should describe the scenario, e.g. `fab-delete-selected.png`, `dock-reorder-cancel.png`.

## 4. Main-page regression

### Layout and adaptation

- Both list and grid modes can scroll with inertia.
- Font, icon and line-spacing settings take effect immediately.
- Narrow windows do not cause address text, menus or dialogs to overflow or crash.
- Text, shadows, thumbnails and floating-button contrast are normal in light and dark modes.
- The status bar, navigation bar, notches and DeX task bar do not obscure content.

### Selection and input

- Single click selects; a second click within the system double-click time opens.
- In multi-select mode a single click only toggles selection, not open.
- Drag-select and ordinary vertical scrolling do not misfire against each other.
- Movement during the first 400ms after pressing a file or tab is ordinary scrolling. At 400ms, selection, haptic and drag-ready feedback appear; moving between 400ms and 800ms starts dragging, while releasing in that window only keeps the selection. Remaining stationary opens the menu at 800ms.
- The multi-select corner handle keeps its original behavior: movement beyond system touch slop before long press cancels the timer and immediately starts continuous selection. One gesture only adds or only removes, and a new gesture after release may reverse direction. Holding the handle still follows the ordinary file long-press flow.
- Releasing in place after the menu appears does not activate an item; continued movement neither closes the menu nor turns the gesture into a drag.
- Files and folders can both be shared from the long-press menu. A single ordinary file is shared directly; a single folder or any multi-selection is packaged into one ZIP in app-private storage, then the temporary archive is deleted after the receiving app finishes reading it.
- Mouse right-click does not open the menu twice due to DeX duplicate events.
- Once a file is selected with a mouse, pressing it again and moving immediately starts a drag. A direct touch swipe over a selected file still scrolls the list; touch dragging starts only by moving after a successful long-press.
- Releasing a drag without hitting a valid directory or tab simply ends the gesture without a "no target" message.
- Desktop shortcuts and address input do not steal from each other.

### Tab bar

- "Storage" is pinned on the far left.
- Pinned, temporary and current tabs display correctly.
- A temporary tab does not disappear automatically after switching to another tab.
- Long-press enters tab editing rather than reusing file selection state.
- After long-press, choose "Manage tabs"; the Dock shows delete buttons in place: temporary tabs close directly, pinned tabs first confirm unpinning without also closing, "Storage" is not operable, and tapping blank space or the system back exits management state.
- Tab dragging only swaps order, not moving folders; during a swap, the dragged and adjacent tabs smoothly move from their current visual positions to the new position, without teleporting or jitter.
- When switching tabs by tap or keyboard, the selection indicator glides smoothly; after the new directory loads, content briefly fades in, and content is not tappable during the animation.
- Dragging to the cancel area gives feedback and the order stays unchanged.
- A long tab bar can scroll horizontally, and long names scroll continuously from the start.

### File transactions

- Create files and folders.
- Rename a single item.
- Copy, cut and paste into the current directory.
- Drag files into a target folder.
- Delete then undo multiple steps consecutively, without a one-step limit.
- Show correct errors for same-name, insufficient permission, source disappeared and target inside the source directory.
- On batch copy or move failure midway, pause at the current item; "Retry" only redoes the failed item, "Skip" continues with the rest, "Cancel" keeps completed items and you can undo the whole batch.
- The UI shows a busy state while a file task runs and recovers after completion.
- ZIP, 7z, RAR, TAR and common compressed streams can be extracted to the same-name folder in the same directory by double-click or long-press; existing content is not overwritten on name conflict.
- Standard ZIP, numbered ZIP/7z and legacy/new RAR volumes can be started from any volume; with the full volume set in the same directory they auto-reassemble; when a middle volume is missing, show the missing volume filename and leave no partial output.
- Encrypted archives automatically show the password prompt; a wrong password can be retried, and passwords are not written to logs or disk.
- Corrupted archives and archives containing `../` escape paths fail to extract, leaving no temporary directory or partial files.
- "Manage plugins" in the top-left enters a secondary menu; import, disable, enable and uninstall update actions immediately.
- Tab management and plugin management use the same title, safe area and enter/exit shell; switching pages in a narrow window does not jump or cross the status bar.
- The top-left menu shows only a unified "Settings" entry; language, theme, display mode, font, icon, line spacing and hidden files are all inside the settings secondary page.
- Font, icon and line-spacing sliders update the value and file area in real time while dragging, and persist after restart; cards reflow dynamically in portrait/landscape and small windows and sliders remain precisely operable.
- Quickly alternating clicks on the plus button, top-left menu and sort button: the popup background, shadow and text must fade in/out together, leaving no borderless text.
- Switching Dock, entering a folder or returning to a directory: the old list stays until the new directory scan completes, then atomically replaces, without a single "empty folder" frame; the old list is not tappable during the transition.
- Plugin ZIPs with invalid manifest, API version or Dex SHA-256 are rejected with no half-finished residue.
- The launcher icon keeps the folder foreground intact under circular, rounded-square and system custom masks, without stretching or edge cropping; Android 8 and below use a compatible icon with safe padding.
- On Activity destruction it does not crash, does not update a stale UI, and does not force-interrupt ongoing transactions.

## 5. Viewer regression

### Images

- Swipe left/right to switch same-directory images.
- Two-finger zoom around the gesture center and does not misfire switching while zoomed.
- Large image loading does not block the main thread or cause obvious memory spikes.

### Video and audio

- Play, pause, seek and previous/next work.
- After switching files, title, position and player state update.
- Backgrounding pauses and saves a reasonable resume position.
- The player is released after leaving the Activity.

### Text

- UTF-8, BOM-bearing text and common encodings can be read and saved preserving encoding.
- Input in TXT, Markdown, XML and similar files tracks the finger.
- XML/HTML highlight debounce does not block input.
- The edit area has inertial scrolling.
- Tab, Shift+Tab and multi-line indentation are correct.
- Unsaved-exit shows a confirmation dialog.

## 6. Persistence and upgrade

Verify the following flows separately:

1. Normal back to the launcher then re-enter.
2. `force-stop` then re-enter.
3. Upgrade with `adb install -r` then enter.
4. Fully kill the process then enter.

Each time check tab order, pinned state, temporary tabs, active directory and history. The undo stack is not required to persist across processes; the old recycle directory is cleaned on the start of a new session.

## 7. Physical-device checks

After passing the emulator, verify on at least one physical device:

- Finger long-press and vibration feedback.
- In the terminal, long-pressing text allows drag selection and copy; long-pressing blank space offers paste without typing on release.
- In the terminal, dragging with the primary mouse button selects text while the wheel handles scrollback; right-click only opens the action menu and preserves the selection until the user chooses Copy or starts another action.
- Swipe inertia and two-finger zoom.
- System font scaling and display scaling.
- Portrait/landscape, split-screen and free-window sizes.
- Mouse right-click, keyboard Tab and DeX task bar.
- Tab persistence after an upgrade install.

Proportional problems found on physical devices should be fixed in the responsive-layout calculation, not by adding device or resolution special-casing.
