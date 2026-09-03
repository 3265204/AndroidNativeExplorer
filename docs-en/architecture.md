# Architecture

## Overview

The project consists of an `app` application module and a standalone `plugin-api` v3 module. `plugin-api` provides both the stable runtime contract and the UI capabilities shared between host and plugins; the main page uses custom drawing with no XML layout, and plugin interfaces are built dynamically in Kotlin.

```text
MainActivity
└─ FileManagerView                  composes state and routes input
   ├─ ui/render                     drawing, hit areas, thumbnails
   ├─ ui/menu                       menu state and command assembly
   ├─ ui/motion                     menu animation, inertia and gesture timing
   ├─ ui/secondary                  secondary page shell, safe area and responsive width
   ├─ ui/selection                  selection, multi-select, double-click and drag-select
   ├─ ui/appearance                 theme and display parameters
   ├─ navigation                    tabs, history and persistence
   ├─ operation                     file transactions, errors and undo
   ├─ core/file                     text file read/write and same-directory file sequence
   └─ input                         mouse and desktop shortcut parsing

pluginmanager                     responsible only for install, discovery, enable/disable and invocation boundaries
├─ plugin-api                      stable ABI, plus UI/input/file host capability contracts
├─ plugin/archive/{code,res}        archive extraction, password and its own copy
├─ plugin/image/{code,res}         image viewing, zoom and its own copy
├─ plugin/video/{code,res}         video playback, directory switching and its own copy
├─ plugin/audio/{code,res}         audio playback, directory switching and its own copy
└─ plugin/text/{code,res}          text editing, encoding, highlighting and its own copy
```

## Entry point and lifecycle

`MainActivity` is mainly responsible for:

- Creating `FileManagerView`.
- Requesting and checking storage permissions.
- Providing system dialogs, toasts and external file open capability.
- Handling the final exit confirmation.
- Closing the background controllers of `FileManagerView` on destruction.

`FileManagerView` is the Android View boundary and the composition root. It may hold the controllers, but should not keep absorbing file business, menu copy, persistence formats or drawing details.

## UI layering

### render

`FileManagerRenderer` only draws the interface from the `RenderState` and records hit areas. It handles the address bar, file list/grid, Dock, floating buttons, menus, drag preview and the busy overlay.

`ThumbnailLoader` loads image and video thumbnails asynchronously, and results enter a memory cache. The drawing phase must not synchronously decode large media.

### Host UI and input capabilities

`plugin-api/src/main/.../api/ui` declares host UI capabilities, semantic models and reusable standard components, without creating a second SDK module and without changing `PluginApi.VERSION = 3`. `PluginHost.ui` obtains the current host page implementation via `PluginUiProvider`; generic controls, window adaptation, media-sequence orchestration and font policy live in the API, while the host page shell and runtime composition live in `app/ui/PluginUiService` and `HostUi`. Plugins therefore depend on the API contract, and colors, font sizes, control spacing and generic motion never leak back into plugins.

Plugins submit only semantic parameters, such as page title, breadcrumb, previous/next item, key actions and accessibility copy. `browserPage`, media switch buttons, playback controls, terminal key bars and editor surfaces are all laid out by the shared UI capabilities. `AneMediaSequenceStage` updates title, position, previous/next state and orchestrates movement through callbacks, without exposing the host `SiblingFileSequence`; `AneTypography` unifies the monospace font policy for editors and terminals. Plugin business code may keep returned View references to update state, but must not re-set shared components' font sizes, semantic colors, corner radii, alpha or standard padding. Domain implementations such as custom canvases, terminal emulators, code highlighting and zoom algorithms stay in the plugins.

`plugin-api/.../api/input` likewise only declares `AnePluginInput`. `PluginHost.input` enters `app/input/HostPluginInput` through `PluginInputProvider`; on-screen terminal keys and hardware keys use the same host mapping. These providers are all implemented by the host object of the current `PluginRegistry`, adding no abstract members to the `PluginHost` interface, so the v3 ABI is preserved.

### File content capability

`plugin-api/.../api/file` declares `PluginHost.files`, `fileQueries`, and `outputs`. The host centralizes text encoding detection, size limits, and encoding-preserving writes; text writes are serialized by the session-wide `FileTransactionService` and enter branching history. `fileQueries` supplies path resolution and sibling sequences. Plugins that generate files or directories write to an `outputs.begin` staging path, then let the host choose the conflict-free destination, commit it, and create one history node.

`core/file/SiblingFileSequence` remains a host-internal implementation. Image, audio, and video plugins provide only their format filters; the host returns a read-only sequence contract through `PluginHost.fileQueries` without exposing the implementation or media-specific types.

### menu

`FileMenuController` stores menu type, position and open/close animation state.

`FileMenuCoordinator` generates operations dynamically based on the current selection, clipboard, undo state and tab state. Invalid operations should be hidden rather than shown as a permanently disabled entry; please follow this convention in development.

### motion and selection

`ui/motion` keeps animation, inertial scrolling and system gesture timing. Long-press, double-click and drag distances follow Android `ViewConfiguration` where possible.

`FileSelectionController` only maintains the selection set and click/double-click/multi-select semantics; it neither draws nor directly modifies files.

### Secondary pages

All host fullscreen secondary pages use `SecondaryPageScaffold`. It uniformly provides safe area, themed background, page margin, back area, title and summary, available-width changes and enter/exit; management pages must not recreate this shell themselves. Tabs, plugins and other features still have their own cards, states and operations, and the common shell must not reference concrete business types.

Host settings uniformly enter `ui/settings/SettingsDialog`; the main menu does not directly modify appearance state. Language is persisted by `AppLanguage`, and display parameters are persisted by `AppearanceController`; the settings page only orchestrates controls and refresh callbacks. Font, icon and line spacing use continuous integer sliders with lower/upper bounds, and choice-type settings use the app's own themed popups.

## Navigation and persistence

`DockSessionController` maintains:

- Pinned tabs and temporary tabs.
- The current active tab.
- Each tab's current directory and back history.
- Tab ordering, pinning, renaming and closing.

`DockSessionStore` writes the session to `SharedPreferences`. Pinned tabs are restored across processes; temporary tabs belong only to the current session by default, and the user can choose in the tab manager to also restore temporary tabs on next launch.

"Storage" is the default pinned tab on the far left. A temporary tab, after switching to another tab, still remains until the user explicitly closes or pins it. Long-pressing any Dock tab enters an in-place management state, where a delete button on the Dock quickly closes temporary tabs; a pinned tab must first be confirmed for unpinning, and the operation only unpins it without also closing it, and "Storage" cannot be unpinned. The top-left app menu retains the full tab manager entry. The full manager uniformly handles selecting and entering, renaming, changing directory, pinning, closing, bulk cleanup and the startup-restore strategy; changing directory must clear the original tab's back history.

## File transactions and undo

File operations are layered in three levels:

1. `FileOps`: low-level copy, move, delete and path checks.
2. `FileOperationService`: returns structured `FileResult`, converting exceptions to `FileProblem`.
3. `FileActionController`: connects selection, dialogs, lifecycle-bound coroutines, branching operation history, and UI prompts.

`FileTransactionService` is the sole session owner of the file worker, trash payloads, and history tree. Normal UI actions, text-plugin writes, and plugin-output commits share its single-thread queue. Its suspending entry point does not occupy the caller thread; cancelling a caller only stops result delivery, while an accepted filesystem transaction still finishes safely. `FileActionController` now owns presentation, selection, and failure interaction only; the composition root closes the transaction service last.

Deletion is not immediate destruction: the file is moved to `.ane-filemanager-trash` under the storage root. `FileHistoryController` stores bidirectional actions with parent/child relationships; recording after undo preserves the old branch, and history can redo the newest child or check out a node explicitly. Records remain process-session only, with no fixed step cap. Old trash is cleaned on restart, so history is not persisted across processes.

See [agent-plugin.md](agent-plugin.md) for the file Agent boundary and planned host transaction API.

## Plugins

The file-management core never references concrete plugin classes. `pluginmanager/PluginRegistry` scans built-in asset manifests and import manifests in the app-private directory, instantiate plugins through the public `plugin-api`. The management layer contains no format detection, playlists or plugin UI; all such runtime logic stays in the corresponding plugin directory.

Plugins that only filter by file and launch one Activity use `AneIntentPluginEntry`. The API unifies the Intent extra and launch flow; the plugin only keeps the target Activity and format-filter configuration; the format set is the single source of truth inside the plugin, and entry matching and same-directory navigation must not each duplicate a copy.

The plugins shipped with the app currently include:

- Images enter `image/ui/ImageActivity`; format filtering and zoom stay in the image plugin, while same-directory navigation uses the `PluginHost.fileQueries` sequence contract.
- Videos enter `video/ui/VideoPlayerActivity`; playback stays in the video plugin and navigation uses the same query contract.
- Audio enters `audio/ui/AudioPlayerActivity`; playback and progress stay in the audio plugin and navigation uses the same query contract.
- Text enters `TextEditorActivity`, reading and writing back with preserved encoding through `TextFileService`; highlighting, inertial scrolling and indentation remain in the text plugin.
- The terminal submits its domain content to `PluginHost.ui` via `TerminalConsoleDialog`; `TerminalSessionController` manages the PTY lifecycle, and both on-screen keys and hardware keys enter the host `HostPluginInput` through `PluginHost.input`, while `TerminalView` only handles terminal drawing, emulator and input connection.

Each plugin uses `plugin-api`'s UI package to uniformly adapt the status bar, navigation bar, notches, DeX task bar, theme and standard dialogs, and maintains its own copy in its own `res/values`. The shared UI only provides the visual language, and owns no player, editor, archive browser or other business components, nor establishes an implicit `plugin/shared` runtime layer.

Protocol fields, plugin boundaries, return results and security requirements are described in the [plugin development specification](plugins.md).

## Desktop input

`DesktopShortcutResolver` only resolves key combinations and returns a semantic `DesktopAction`. The concrete file command is still executed by the main-interface controllers.

Touch long-press, mouse right-click and keyboard shortcuts can trigger the same business command, but platform event parsing must stay separate. Some DeX devices send two events per right-click; the compatibility de-duplication is centralized in the gesture-timing module.
