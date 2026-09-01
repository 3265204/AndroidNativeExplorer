# Plugin Development Specification

ANE uses a plain ZIP as the in-app plugin installer. The user first navigates to the ZIP's directory in ANE, then selects it from "top-left menu → Manage Plugins → Import from current folder"; the plugin is then loaded immediately in the current process. After disable or uninstall, its double-click handling and long-press menu actions disappear immediately, requiring no modification of the main project nor an app restart.

## 1. Boundaries and directories

- The common ABI, UI, input and file capability contracts all live in the standalone `plugin-api` module, using the `plugin.api`, `plugin.api.ui`, `plugin.api.input` and `plugin.api.file` packages respectively; the host implementations live in `app/ui`, `app/input` and `app/core/file`, and the capability encapsulation does not change the current v3 protocol.
- Built-in plugins live in `plugin/archive`, `plugin/text`, `plugin/audio`, `plugin/image`, `plugin/video`, `plugin/terminal` respectively.
- Each plugin owns its extensions, MIME types, file-signature detection, parsing, passwords, media behavior, UI and runtime resources; the host only provides a media-type-agnostic same-directory sequence implementation.
- Under `plugin`, forbid creating a `shared`, `support`, `runtime` or `viewer` top-level directory; plugins must not share a common file-type table or an implicit runtime layer.
- Adding a built-in plugin only adds the implementation class and `assets/ane-plugins/<id>.json`; do not edit the type list in `PluginRegistry`.

## 2. API v3

The current host API version is v3.

The plugin entry must be a public class with a no-arg constructor implementing:

```kotlin
class MyPlugin : AnePlugin {
    override fun supports(file: PluginFile): Boolean =
        file.extension == "demo"

    override fun open(file: PluginFile, host: PluginHost): Boolean {
        // Return true to indicate the double-click has been handled.
        return true
    }

    override fun fileActions(file: PluginFile, host: PluginHost) = listOf(
        PluginFileAction("convert", "Convert") {
            // This action is automatically added to the file long-press menu.
        }
    )
}
```

`supports` is implemented by the plugin and may use extension, MIME or file-header detection. When multiple plugins match, the long-press menu merges all actions; double-click tries each plugin in descending `priority` order from the manifest until one returns `true`.

When only launching an Activity by file type, use the config-driven entry; the file path extra is uniform via the API:

```kotlin
class ImagePlugin : AneIntentPluginEntry(
    ImageActivity::class.java,
    ImageFiles::supports
)

object ImageFiles {
    private val extensions = setOf("jpg", "png", "webp")
    fun supports(file: PluginFile) = file.extension.lowercase() in extensions
}

// ImageActivity
val path = intent.getStringExtra(AneIntentPluginEntry.EXTRA_FILE_PATH)
```

The entry matching and the Activity's same-directory filter must reference the same plugin format config; do not maintain two extension sets. Plugins needing selection, icons, directory actions or a custom open flow continue to implement the corresponding interfaces directly.

To contribute actions based on the current selection to the plus menu, a plugin may additionally implement `PluginSelectionActionProvider`. The host only passes the single- or multi-selected files to enabled plugins and does not recognize concrete business types:

```kotlin
class ArchivePlugin : AnePlugin, PluginSelectionActionProvider {
    override fun selectionActions(files: List<PluginFile>, host: PluginHost) =
        if (files.isEmpty()) emptyList() else listOf(
            PluginFileAction("compress", "Add to archive") {
                // The plugin detects writable formats, shows the choice UI and runs the task.
            }
        )

    // AnePlugin's supports/open/fileActions still implement normal file capability.
}
```

Plus-menu actions that act on the current browsing directory rather than the selection should implement `PluginDirectoryActionProvider`. The host passes the current directory regardless of whether there is a selection; such actions do not appear in the long-press menu of files or folders:

```kotlin
class TerminalPlugin : AnePlugin, PluginDirectoryActionProvider {
    override fun directoryActions(directory: PluginFile, host: PluginHost) = listOf(
        PluginFileAction("terminal", "Open terminal here") { /* use directory.path */ }
    )
}
```

Both selection actions and directory actions must be returned dynamically by the plugin; after disabling or uninstalling the plugin they disappear automatically from the plus menu. The host must not hardcode plugin IDs, archive formats or button copy.

For a dedicated file icon, a plugin may additionally implement `PluginFileIconProvider`. The file type is still recognized by the plugin, e.g. an archive plugin returns `PluginFileIcon.ARCHIVE`; the host only draws the corresponding semantic icon and does not maintain an archive-extension list. Files not handled by any plugin are routed to Android's external apps uniformly. ANE first shows "Just once / Always": the former only applies to the current open operation, and the latter remembers the chosen app by MIME type (extension for unknown MIME). "Open with" in the file long-press menu shows the same modes again and allows overriding the existing association.

Time-consuming tasks must go to `host.execute`; passwords are obtained through `host.requestPassword` and cleared after use; after creating output, refresh, select and register session undo via the task result or `host.reportOutput`.

Plugins needing a truly interactive terminal should declare `apiVersion: 3` and request the host PTY via `PluginHost.openTerminal`:

```kotlin
val session = host.openTerminal(
    PluginTerminalRequest(
        executable = "/system/bin/sh",
        arguments = listOf("-i"),
        workingDirectory = file.path,
        environment = mapOf("TERM" to "xterm-256color"),
        rows = 24,
        columns = 80
    ),
    object : PluginTerminalListener {
        override fun onOutput(bytes: ByteArray) {
            // Raw PTY bytes; hand to an ANSI/VT terminal emulator, don't split into command results by line.
        }

        override fun onExit(exitCode: Int?, signal: Int?) = Unit
        override fun onError(message: String) = Unit
    }
)

session?.write("pwd\r".toByteArray())
session?.resize(rows = 32, columns = 100)
```

`openTerminal` returns a persistent `PluginTerminalSession`. Input is a raw byte stream and Enter usually sends `CR` (`0x0d`); one Enter does not spawn a new process nor produce an "exit code 0" event. `onExit` is only called when the Shell itself exits. `close()` must be called when the terminal window is closed or the plugin is uninstalled.

The terminal view should compute the PTY `rows` and `columns` from actually available pixels and call `resize` on window, orientation, soft keyboard or terminal-font changes. The host updates the kernel PTY via `TIOCSWINSZ`; foreground programs can receive `SIGWINCH`, so the terminal must not be fixed to 80×24.

The PTY process still runs under the ANE app UID, requiring no Root and not breaking the Android sandbox. Terminal control keys such as `Ctrl+C` should preferentially write control bytes so the terminal line discipline sends the signal to the foreground process group; `sendSignal` is for the Agent or lifecycle layer to explicitly control the session.

### v3 visual SDK

Plugins should request ANE's semantic theme, pages, components and standard dialogs through `PluginHost.ui`. The API only declares the contract; the current host implements the visual policy; `PluginHost` itself has no new abstract members, so the manifest still declares `apiVersion: 3`.

```kotlin
import com.ane.filemanager.plugin.api.ui.AneDialogAction
import com.ane.filemanager.plugin.api.ui.ui

host.ui.message(
    title = strings.finished,
    message = strings.outputCreated,
    actions = listOf(AneDialogAction(strings.confirm, primary = true))
)
```

Repeated pages and controls should not be hand-styled by plugins. For example, a media switch button only declares direction, content and action:

```kotlin
import com.ane.filemanager.plugin.api.ui.AneMediaDirection

val next = host.ui.attachMediaSwitchButton(
    context = host.activity,
    container = stage,
    direction = AneMediaDirection.NEXT,
    symbol = strings.nextSymbol,
    contentDescription = strings.next,
    onClick = ::openNext
)
```

The plugin must not set the button's `textSize`, ARGB, corner radii, size, margins or disabled alpha. Imported plugins prefer `host.ui.page`/`browserPage` to mount content; built-in plugin Activities use the host `HostUi`. Domain components such as custom canvases, terminal emulators, code highlighting, media decoding and zoom algorithms may continue to use native Views, but their surrounding controls are still provided by the host.

Same-directory media UI uses `AneMediaSequenceStage`, passing only callbacks to the API, not host-internal sequence types:

```kotlin
val sequenceStage = host.ui.mediaSequenceStage(
    context = host.activity,
    navigationLabel = strings.backSymbol,
    navigationDescription = strings.back,
    onNavigate = ::close,
    navigation = AneMediaSequenceNavigation(
        currentTitle = { sequence.currentName },
        positionLabel = { sequence.positionLabel },
        hasPrevious = { sequence.hasPrevious },
        hasNext = { sequence.hasNext },
        moveBy = { delta -> sequence.moveBy(delta) }
    ),
    onMoved = ::showCurrent
)
```

This component unifies title, position label, previous/next disabled state and the move call; media decoding, playback, transitions and the actual sequence type remain up to the plugin. The text editor can call `host.ui.configureTextEditor(editor)`; terminal-like custom views can use `AneTypography.terminalTextSp(context)` to get the default font size consistent with the host appearance settings.

Terminal-type plugins request key sequences through `PluginHost.input`. The plugin only passes `PluginTerminalKey` or Android key state; control bytes, xterm modifiers and the Alt prefix are generated by the host input layer; on-screen and hardware keys must not maintain two mappings.

### v3 file content capability

Text content is read and written back through `PluginHost.files`. The host handles encoding/BOM detection and saving; the plugin only keeps the returned encoding enum:

```kotlin
import com.ane.filemanager.plugin.api.file.files

host.execute("Read text", {
    val document = host.files.readText(file)
    val editedText = document.text // Replace with the plugin-processed text here.
    host.files.writeText(file, editedText, document.encoding)
    PluginTaskResult(success = true)
})
```

`readText`/`writeText` are synchronous capabilities and must not be called directly on the main thread. A `PluginTextTooLargeException` is thrown when exceeding the default size limit. The current backend still uses normal app permissions to access paths, but the plugin no longer reads text via `File(path)` itself, keeping a host access point for a future file-backend switch.

## 3. plugin.json

The plugin package root must contain both `plugin.json` and `classes.dex`:

```json
{
  "id": "example.demo",
  "name": "Demo plugin",
  "version": "1.0.0",
  "description": "Demonstrates double-click handling and long-press actions",
  "defaultLocale": "en",
  "localizations": {
    "zh": {
      "name": "Demo 插件",
      "description": "演示双击处理和长按动作"
    }
  },
  "apiVersion": 3,
  "entryClass": "example.demo.DemoPlugin",
  "priority": 100,
  "defaultEnabled": true,
  "codeSha256": "64-char lowercase SHA-256 of classes.dex"
}
```

- `id` only allows 3–64 lowercase letters, digits, dots, underscores and hyphens, starting with a letter or digit.
- `description` is optional, at most 240 characters, used for the plugin management card; plugins should not rely on it to carry runtime parameters.
- `defaultLocale` should be the BCP-47 language of the root-level name and description. This way, when the system has multiple candidate languages, the root-level text participates in matching at the right position in its own language priority.
- `localizations` is optional, keyed by any BCP-47 language tag, providing localized name and description for the management card; it is not limited to ANE's own supported language list, falling back to the plugin author's root-level fields when unmatched.
- `apiVersion` must be within the host's publicly supported range. The current host accepts v2–v3; plugins using PTY must set v3.
- `defaultEnabled` only controls the initial state when the plugin first appears, defaulting to `true` when omitted; afterwards the user's selection in plugin management takes precedence.
- `entryClass` must implement the host-provided `AnePlugin`; the plugin package must not bundle `plugin-api` classes.
- `priority` only affects double-click takeover order, not whether long-press actions appear.
- `codeSha256` must match the `classes.dex` inside the package; generate the Dex and digest first, then write the manifest, then package.

The plugin package is a standard `.zip`. Development tools only need to produce a ZIP, no ANE-private format. Example structure:

```text
demo-plugin.zip
├── plugin.json
└── classes.dex
```

## 4. Build conventions

Build the runtime protocol and visual SDK first:

```bash
gradle :plugin-api:assembleRelease
```

The output is `plugin-api/build/outputs/aar/plugin-api-release.aar`, containing the runtime, UI, input and file capability contracts. The plugin project references it with `compileOnly`; the produced `classes.dex` must not contain a `plugin-api` copy. The packaging flow must be reproducible and compute the SHA-256 after generating the Dex. The plugin ZIP still only contains the manifest and Dex; the native PTY library, visual implementation, keyboard mapping and file capability implementation are all provided by the host APK and must not, and may not, be duplicated by the plugin.

## 5. Plugin localization specification

- A plugin's supported languages are entirely decided by the plugin. For example, ANE has no French UI, yet a plugin may still provide `fr`, `fr-FR` or `fr-CA`.
- By default the plugin reads the device's real BCP-47 language priority from `PluginHost.systemLocaleTags`; this list is unaffected by whether the user chose Chinese or English for ANE.
- `PluginHost.hostLocaleTags` only represents ANE's current UI language. A plugin may actively adopt it for visual consistency, but the host must not force it.
- A plugin may provide its own language override option and persist it itself. The preference file and keys must be named with the plugin ID and released by the plugin on uninstall; without an override setting it falls back to the system language.
- Built-in plugins maintain any language in their own `res/values`, `values-fr`, `values-en`, etc. directories. ANE's built-in plugins use Chinese as the default resource, but that is not a requirement for third-party plugins. Forbid placing plugin translations into the host public values pool.
- Imported plugins' buttons, dialogs, errors and task states are chosen by the plugin from its own text tables based on the language signal above; the host does not maintain a plugin translation whitelist.
- The management card's name and description use `plugin.json.localizations`, which may declare any BCP-47 tags. The root-level `name` and `description` are the plugin author's final fallback and are not language-constrained.
- When a full tag (e.g. `fr-CA`) is not found, fall back to the base language (`fr`), then to the root-level fields. After changing language, the plugin should rebuild its UI and not keep caching old strings.
- Translations are maintained by the plugin itself; resource keys must carry the plugin prefix; formatted parameters, quantities and `contentDescription` must also be localized.

## 6. Management, install, enable/disable and uninstall

Plugin management is a secondary page under ANE's left menu. Each plugin is shown as an independent card with name, version, description, source, status and an enable/disable toggle; only imported plugins show the uninstall action. The import entry only reads `.zip` files in ANE's current browsing directory, without launching a system file picker or another file manager.

The host performs the following steps when importing:

1. Limit the package, manifest and Dex sizes.
2. Validate the manifest fields, API version and `classes.dex` SHA-256.
3. Copy the full package into the app-private directory and set it read-only before writing code, to satisfy Android 14+ dynamic code-loading requirements.
4. Use a `DexClassLoader` with the host as parent to create the instance and call `onLoad`.

Disabling calls `onUnload` and removes all action references; re-enabling creates a new instance. Uninstall only applies to imported plugins and deletes their private program files after confirmation. Built-in plugins can be disabled but not uninstalled. Built-in manifests may declare `defaultEnabled: false` so the plugin stays disabled on first appearance; after the user explicitly enables/disables, their choice takes precedence over the manifest default. Built-in plugins cannot be replaced by an imported package with the same ID.

## 7. Lifecycle and security

- Capabilities are only provided after `onLoad`; after `onUnload` a plugin must stop its own tasks and release players, Handlers, listeners and other references.
- Do not extract, hash, parse large files or perform network requests on the main thread.
- Volume-segment detection and reassembly belong to the archive plugin's own capability; the host must not maintain extension or volume-number rules. The built-in archive plugin supports standard ZIP volumes (`.z01 … .zip`), numbered ZIP/7z volumes (`.zip.001 …`, `.7z.001 …`) and legacy/new RAR volumes (`.part01.rar …`, `.rar + .r00 …`); any volume should resolve to the first volume, and a volume already confirmably missing from file names should be reported before the task starts.
- File writes go to a same-directory temporary location first and commit only after success; do not silently overwrite existing paths.
- The archive plugin must block `../`, absolute paths, symbolic links, hard links and other directory escapes.
- Passwords use `CharArray` and are zero-filled after completion; must not be written to logs, preferences, disk or exception text.
- Plugin Dex runs in the same process and the same UID as the host, technically holding the file manager's full privileges and cannot be considered a security sandbox. Only install plugins from trusted sources; SHA-256 only verifies intra-package consistency, not author trust.
- Loading dynamic code may not comply with some app-store policies. Re-check channel rules before store distribution; production should add a trusted signature or whitelist.

## 8. Acceptance

1. After importing a plugin, without modifying or recompiling ANE, the long-press menu of matching files immediately shows new actions.
2. After disable/enable, the actions immediately disappear/reappear and double-click routing changes accordingly.
3. After uninstalling an imported plugin, its code and actions both disappear; built-in plugins have no uninstall entry.
4. When two plugins match the same file, both long-press actions appear and double-click follows `priority`.
5. Packages with invalid manifest, API or SHA-256 must be rejected and must not leave a loadable half-finished artifact.
6. When a plugin throws an exception, the file manager stays usable and gives a plugin-level error prompt.
