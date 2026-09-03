# Development Conventions

## Basic principles

- Prefer Kotlin for new code. Only introduce Java or another language when a platform or third-party interface explicitly requires it.
- One file has one clear responsibility; do not keep piling business logic back into `FileManagerView`.
- UI, filesystem, persistence and media playback communicate through clear boundaries.
- Reuse existing controllers and result types before considering parallel implementations.
- Do not write conditional branches for the fixed resolution of a specific device.

## Where should my feature go

| What you change | Directory |
| --- | --- |
| File list, Dock, menu drawing | `ui/render/` |
| Menu actions and display conditions | `ui/menu/` |
| Animation, inertia, gesture thresholds | `ui/motion/` |
| Selection, multi-select, drag-select | `ui/selection/` |
| Theme, font size, spacing | `ui/appearance/` |
| Theme, components, dialogs and motion shared between host and plugins | `plugin-api/.../api/ui/` |
| Common shell for fullscreen secondary pages | `ui/secondary/` |
| Host settings page and control orchestration | `ui/settings/` |
| Tabs and directory history | `navigation/` |
| File create, copy, delete, undo | `operation/` |
| Text content read/write, same-directory file sequence | `core/file/` |
| Mouse and shortcuts | `input/` |
| Optional file content capabilities | `plugin/<plugin-id>/`, following the [plugin development specification](plugins.md) |
| File-manager core Android resources | `app/src/main/res/` |
| Plugin Android resources | the corresponding `plugin/<plugin-id>/res/`, forbidden in the core public values pool |
| Plugin-manager Android resources | `pluginmanager/res/` |

If a change needs to evaluate the same state across more than three modules, add a model or controller interface first instead of duplicating the condition.

File extensions, MIME matching and format detection belong to the specific plugin. The core must not import plugin implementation classes nor add to the built-in plugin list; each built-in plugin keeps its discovery manifest in its own `assets/ane-plugins/`, and imported plugins are discovered dynamically through the manifest and public API of a plain ZIP.

## UI and responsive layout

- Host fullscreen secondary pages use `SecondaryPageScaffold`; imported plugins request pages, components and dialogs through `PluginHost.ui`, and built-in plugin Activities use the same implementation via the host `HostUi`; do not duplicate the color table or window adaptation.
- The secondary page shell only handles the host UI protocol; business cards, state and copy of plugins or managers remain in their own directories.
- When adding a host display setting, keep the persistence interface in `AppearanceController`; the settings page must not read/write `SharedPreferences` directly.
- Continuous numeric settings use sliders with semantic lower/upper bounds; update the current value and refresh the preview live while dragging.
- Compute layout from the current View available size, system Insets, font measurement and content constraints.
- Use `dp` for sizes and `sp` for text during development; pixels are only used for pixel-semantic values such as decode target sizes.
- Grid column count, row height and text width must be derived from the container size and made dynamic where possible.
- All text must be constrained to its draw region; on narrow windows use clipping, truncation or scrolling so it never overflows the address bar or dialogs.
- The status bar, navigation bar, notches and DeX task bar are uniformly adapted through Insets.
- Floating button icons are computed by geometric center; do not use special characters that rely on font baselines.

## Animation and gestures

- Click selection gives immediate feedback; do not add meaningless animations to ordinary file clicks.
- Menu animations expand from the trigger point and return to the same trigger point on dismissal.
- Dock reorder animation must start from the tab's current draw position, resuming from the visual position during the animation when dragging across multiple tabs, to avoid teleporting.
- Animations are managed by `ui/motion`; business controllers must not maintain animation progress themselves.
- Long-press and double-click use the system timeouts from Android `ViewConfiguration`.
- Drag distances use `scaledTouchSlop`.
- Device compatibility de-duplication, animation durations and debounce intervals must use semantic constants; do not scatter bare millisecond values across business branches.
- In multi-select mode a single click toggles selection once; a double-click equals two toggles and must not open a file.
- The first 400ms after pressing a file or tab are reserved for ordinary scrolling. At 400ms, selection, haptic and drag-ready feedback appear; movement between 400ms and 800ms starts dragging, while releasing in that window only keeps the selection. A stationary press opens the menu at 800ms. The multi-select corner handle keeps its original behavior: movement beyond system touch slop cancels long press and immediately starts continuous selection, with either add or remove polarity fixed for that gesture; a new gesture after release may reverse it. Holding the handle still follows the ordinary file long-press flow. Once shown, the menu is locked as menu interaction and must not turn into a drag.

## Copy and i18n

- User-visible text is kept in the respective component's own `res/values/strings.xml`; plugins may only maintain values within their own directory, and the core resource pool must not collect plugin copy.
- ANE core's default `values` is Chinese, with English in `values-en`; the first launch follows the system-selected language by default. A plugin's language set, default language and optional override setting are maintained by the plugin itself, and the default-language signal comes from the device system, not bound to ANE's supported language set.
- Do not concatenate Chinese business copy such as "rename failed" or "copy succeeded" in code; place them in the file directory uniformly.
- Resource names describe business semantics, e.g. `action_delete_selected`; do not keep using special names whose meaning has changed to denote tabs.
- Text with quantities or dynamic content uses formatted resources.
- `contentDescription` must also use string resources.

## File operations

- The UI layer must not call `File.renameTo`, recursive delete, or copy streams itself.
- Low-level exceptions are converted to `FileResult`/`FileProblem` in `FileOperationService`.
- Large-file copy, move, delete and undo must not run on the main thread.
- File transactions use `FileActionController`'s single-thread queue to avoid concurrent overwrites and recycle-bin races.
- Executors must have an explicit lifecycle: after closing, reject new tasks, do not force-interrupt ongoing file transactions, and suppress stale UI callbacks.
- When adding a reversible operation, define the corresponding `PendingUndo`; irreversible operations must state the reason in the design notes.
- Updating UI, selection and the undo stack must happen after the transaction succeeds.
- Text encoding, BOM detection and encoding-preserving write-back uniformly use `TextFileService`; imported plugins call it through `host.files`, and copying the codec inside a plugin directory is forbidden.
- `host.files` is a synchronous file capability and must be placed inside `host.execute` or a plugin's own controlled background task; it must not read or write files on the main thread.

## State and persistence

- Tab state is modified through `DockSessionController`; do not directly rewrite the tab set in the UI.
- The persistence format is the sole responsibility of `DockSessionStore`.
- When adding a persisted field, tolerate missing old data, missing paths and out-of-range indices.
- Selection and undo are session state and are not written to disk unless the product requires it.
- When an Activity or View is destroyed, clean up Handlers, animations, media players and background executors.

## Plugin UI and editors

- The plugin API stays at v3; `plugin.api.ui` provides semantic models and reusable standard components, and `plugin.api.input` and `plugin.api.file` provide host capabilities through providers; do not add new `PluginHost` members just to encapsulate.
- Plugins that only need to launch an Activity by file type inherit `AneIntentPluginEntry`; do not copy the `supports + Intent + EXTRA_FILE_PATH` boilerplate again; entry matching and the Activity's same-directory filter must reference the same format config in the plugin.
- Imported plugins' standard messages, selection, input, fullscreen pages, browser lists and media controls must be requested from `host.ui`; built-in plugin Activities depend on `app/ui/HostUi` and must not bypass the host to build another set of common styles.
- Plugins only pass semantic parameters such as title, copy, direction, state and callbacks. Font sizes, colors, corner radii, transparency, standard margins, control sizes and generic motion durations must live in the host UI implementation, not in plugin control construction code.
- The Activity's system bars and root-layout safe area use `applyAneSystemBars` and `applyAneSystemInsets`; black stages such as video may explicitly override the navigation bar color.
- Images, videos and audio use `core/file/SiblingFileSequence` to reuse scan, sort and movement, and orchestrate title, position and previous/next state through the callback-based `AneMediaSequenceStage`; each plugin still provides its own format filter, and the API must not expose `SiblingFileSequence` or extract a shared Viewer layer.
- Editor surround styles use `AneComponents.configureTextEditor`/`host.ui.configureTextEditor`, and the terminal default font size uses `AneTypography`; plugins may keep their own font-size override value but must not directly read host appearance preference keys.
- The terminal page enters the host shell through `host.ui.page` and `populateConsolePage`; PTY lifecycle lives in `TerminalSessionController`, and both on-screen keys and hardware keys enter the host input layer through `host.input`, so the terminal plugin must not re-hardcode control sequences or the full visual hierarchy.
- The image zoom focus must stay at the two-finger center; no image switching while zoomed.
- Video and audio release the old player after switching and save a reasonable resume position.
- Text load and save go through `TextFileService` to preserve the original encoding and BOM; dirty state, highlighting, indentation and editing interaction stay in the text plugin.
- Highlighting calculation runs on a background thread, only processes the area near the visible region, and discards stale results via revision/token.
- Tab inserts four spaces; multi-line selection supports unified indentation and Shift+Tab outdent.

## Desktop and keyboard

- New shortcuts are added centrally to `DesktopShortcutResolver`.
- Shortcut-triggered behavior reuses the same controller methods as the touch menu.
- Input fields and text editors consume text-editing keys first, so global shortcuts do not steal them.
- To avoid certain special tablet key mappings, `Esc` must not be mapped to an app action.
- Mouse right-click needs to verify both Android native mouse events and DeX/DeX-like event behavior.

## Modification workflow

1. Determine which module the requirement belongs to and whether it changes the existing state model.
2. Search existing implementations and string resources to avoid duplicate controllers or duplicate copy.
3. Modify the model/controller first, then the drawing and hit logic.
4. When persistence is involved, verify upgrade installs and force-stop recovery.
5. Run `gradle :app:assembleDebug`.
6. During testing you can complete interaction regression on the emulator, then check Insets, font scaling and touch on at least one physical device.
7. When architecture or conventions change, update `docs/` and `docs-en/` in the same change.

## Pre-commit checklist

- Any new hardcoded user copy?
- Any unnamed time threshold or fixed screen coordinates?
- Any heavy file or media work on the main thread?
- Any unclosed Handler, Animator, MediaPlayer or Executor?
- Do narrow windows, portrait/landscape, font scaling and system Insets behave correctly?
- Does the menu only show currently valid actions?
- Do delete, move, paste and undo cover both success and failure paths?
