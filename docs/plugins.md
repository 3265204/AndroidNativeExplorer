# 插件开发规范

ANE 使用普通 ZIP 作为应用内插件安装。用户先在 ANE 中进入 ZIP 所在目录，再从“左上角菜单 → 管理插件 → 从当前文件夹导入”选择它；插件随后在当前进程立即加载。停用或卸载后，其双击处理和长按菜单动作立即消失，不需要修改主工程或重启应用。

## 1. 边界与目录

- 公共 ABI 位于独立 `plugin-api` 模块；安装、发现和启停位于 `pluginmanager`，不伪装成一个插件实现或运行层。
- 内置插件分别位于 `plugin/archive`、`plugin/text`、`plugin/audio`、`plugin/image`、`plugin/video`、`plugin/terminal`。终端的独立 ZIP 工程只引用 `plugin/terminal` 这份源码，不维护第二份实现。不存在 `viewer` 总分类。
- 每个插件自行拥有扩展名、MIME、文件签名探测、解析、密码、目录序列、界面和运行期资源。
- `plugin` 下禁止建立 `shared`、`support`、`runtime` 或 `viewer` 总目录；插件之间不得共享文件类型总表或隐式运行层。
- 新增内置插件只添加实现类和 `assets/ane-plugins/<id>.json`，不得编辑 `PluginRegistry` 中的类型列表。

## 2. API v3

当前宿主 API 版本为 v3，并继续加载 `apiVersion: 2` 的旧插件。v3 新增宿主拥有的原生 PTY 会话；普通文件处理、长按动作和选区动作的接口保持兼容。

插件入口必须是公开、无参构造的类，并实现：

```kotlin
class MyPlugin : AnePlugin {
    override fun supports(file: PluginFile): Boolean =
        file.extension == "demo"

    override fun open(file: PluginFile, host: PluginHost): Boolean {
        // 返回 true 表示已经接管双击。
        return true
    }

    override fun fileActions(file: PluginFile, host: PluginHost) = listOf(
        PluginFileAction("convert", "转换") {
            // 该按钮会自动加入文件长按菜单。
        }
    )
}
```

`supports` 由插件自行实现，允许扩展名、MIME 或文件头探测。多个插件匹配时，长按菜单合并所有动作；双击按 manifest 的 `priority` 从高到低尝试，直到某个插件返回 `true`。

插件若要为加号菜单贡献基于当前选区的动作，可额外实现 `PluginSelectionActionProvider`。宿主只负责把单选或多选文件传给已启用插件，不识别具体业务类型：

```kotlin
class ArchivePlugin : AnePlugin, PluginSelectionActionProvider {
    override fun selectionActions(files: List<PluginFile>, host: PluginHost) =
        if (files.isEmpty()) emptyList() else listOf(
            PluginFileAction("compress", "添加到压缩包") {
                // 插件自行探测可写格式、显示选择界面并执行任务。
            }
        )

    // AnePlugin 的 supports/open/fileActions 仍按普通文件能力实现。
}
```

不依赖选区、而是作用于当前浏览目录的加号菜单动作，应实现 `PluginDirectoryActionProvider`。宿主无论当前是否有选区都会传入当前目录；这类动作不会出现在文件或文件夹的长按菜单：

```kotlin
class TerminalPlugin : AnePlugin, PluginDirectoryActionProvider {
    override fun directoryActions(directory: PluginFile, host: PluginHost) = listOf(
        PluginFileAction("terminal", "在此处打开终端") { /* 使用 directory.path */ }
    )
}
```

选区动作和当前目录动作都必须由插件动态返回；停用或卸载插件后会自动从加号菜单消失。宿主不得硬编码插件 ID、压缩格式或按钮文案。

插件若需要专用文件图标，可额外实现 `PluginFileIconProvider`。文件类型仍由插件识别，例如压缩插件返回 `PluginFileIcon.ARCHIVE`；宿主只绘制对应的语义图标，不维护压缩扩展名列表。未被插件接管的文件统一交给 Android 外部应用路由。ANE 先统一显示“仅此一次 / 始终”：前者只用于当前打开操作，后者按 MIME 类型（未知 MIME 按扩展名）记住所选应用。文件长按菜单中的“选择打开方式”会再次显示相同模式，并允许覆盖原有关联。

耗时任务必须交给 `host.execute`；密码通过 `host.requestPassword` 获取并在使用后清零；创建输出后通过任务结果或 `host.reportOutput` 刷新、选中并登记会话撤回。

需要真正交互式终端的插件应声明 `apiVersion: 3`，通过 `PluginHost.openTerminal` 请求宿主 PTY：

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
            // 原始 PTY 字节，应交给 ANSI/VT 终端模拟器，不要按行拆成命令结果。
        }

        override fun onExit(exitCode: Int?, signal: Int?) = Unit
        override fun onError(message: String) = Unit
    }
)

session?.write("pwd\r".toByteArray())
session?.resize(rows = 32, columns = 100)
```

`openTerminal` 返回持久的 `PluginTerminalSession`。输入是原始字节流，回车通常发送 `CR`（`0x0d`）；一次回车不会创建新进程，也不会产生“退出码 0”事件。只有 Shell 本身退出时才调用 `onExit`。终端窗口关闭或插件卸载时必须调用 `close()`。

终端视图应从实际可用像素计算 PTY 的 `rows` 与 `columns`，并在窗口、横竖屏、软键盘或终端字号变化时调用 `resize`。宿主会通过 `TIOCSWINSZ` 更新内核 PTY；前台程序可收到 `SIGWINCH`，因此不应把终端固定为 80×24。

PTY 进程仍以 ANE 应用 UID 运行，无需 Root，也不会突破 Android 沙箱。`Ctrl+C` 等终端控制键应优先写入控制字节，让终端行规程把信号送到前台进程组；`sendSignal` 用于 Agent 或生命周期层明确控制会话。

## 3. plugin.json

插件包根目录必须同时包含 `plugin.json` 和 `classes.dex`：

```json
{
  "id": "example.demo",
  "name": "Demo 插件",
  "version": "1.0.0",
  "description": "演示双击处理和长按动作",
  "defaultLocale": "zh",
  "localizations": {
    "en": {
      "name": "Demo plugin",
      "description": "Demonstrates double-click handling and long-press actions"
    }
  },
  "apiVersion": 3,
  "entryClass": "example.demo.DemoPlugin",
  "priority": 100,
  "defaultEnabled": true,
  "codeSha256": "classes.dex 的 64 位小写 SHA-256"
}
```

- `id` 只允许 3–64 位小写字母、数字、点、下划线和横线，首位为字母或数字。
- `description` 可选，最多 240 个字符，用于插件管理卡片；插件不应依赖它传递运行参数。
- `defaultLocale` 建议填写根级名称和说明所用的 BCP-47 语言。这样当系统存在多个候选语言时，根级文本能在自己的语言优先级位置正确参与匹配。
- `localizations` 可选，以任意 BCP-47 语言标签为键，为管理卡片提供本地化名称和说明；它不受 ANE 自身支持语言列表限制，未匹配时回退到插件作者定义的根级字段。
- `apiVersion` 必须位于宿主公开的支持范围内。当前宿主接受 v2–v3；使用 PTY 的插件必须填写 v3。
- `defaultEnabled` 仅控制插件第一次出现时的初始状态，省略时为 `true`；之后以用户在插件管理中的选择为准。
- `entryClass` 必须实现宿主提供的 `AnePlugin`；插件包不要重复打入 `plugin-api` 类。
- `priority` 只影响双击接管顺序，不影响长按动作是否出现。
- `codeSha256` 必须与包内 `classes.dex` 一致；先生成 Dex 和摘要，再写 manifest，最后打包。

插件包就是标准 `.zip` 文件。开发工具只需能生成 ZIP，无需识别 ANE 私有格式。示例结构：

```text
demo-plugin.zip
├── plugin.json
└── classes.dex
```

## 4. 构建约定

先构建公共 SDK：

```bash
gradle :plugin-api:assembleRelease
```

产物为 `plugin-api/build/outputs/aar/plugin-api-release.aar`。插件工程以 `compileOnly` 引用它，生成不包含 API 副本的 `classes.dex`。打包流程必须可复现，并在 Dex 生成后计算 SHA-256。插件 ZIP 仍只包含 manifest 与 Dex；原生 PTY 库由宿主 APK 提供，不需要也不允许插件重复携带。需要复杂界面时，可使用 `PluginHost.activity` 动态创建 Dialog/View。

## 5. 插件多语言规范

- 插件支持语言完全由插件决定。例如 ANE 没有法语界面，插件仍可提供 `fr`、`fr-FR` 或 `fr-CA`。
- 插件默认按 `PluginHost.systemLocaleTags` 读取设备真实的 BCP-47 语言优先级；这个列表不受用户给 ANE 选择中文或英文的影响。
- `PluginHost.hostLocaleTags` 仅表示 ANE 当前界面语言。插件可以为了视觉一致性主动采用它，但宿主不得强制。
- 插件可以提供自己的语言覆盖选项并自行持久化。偏好文件和键必须以插件 ID 命名，卸载时由插件释放；没有覆盖设置时回到系统语言。
- 内置插件在自己的 `res/values`、`values-fr`、`values-en` 等目录维护任意语言。ANE 自带插件以中文作为默认资源，但这不是第三方插件的强制要求。禁止把插件翻译放入宿主公共 values 池。
- 导入插件的按钮、对话框、错误和任务状态由插件根据上述语言信号从自己的文本表选择；宿主不维护插件翻译白名单。
- 管理卡片的名称和说明使用 `plugin.json.localizations`，可声明任意 BCP-47 标签。根级 `name` 与 `description` 是插件作者选择的最终回退值，不限定语言。
- 找不到完整标签（如 `fr-CA`）时先回退到基础语言（`fr`），再回退根级字段。插件改变语言后应重建界面，不得继续缓存旧字符串。
- 翻译由插件自身维护，资源键必须带插件前缀；格式化参数、数量和 `contentDescription` 同样必须本地化。

## 6. 管理、安装、启停与卸载

插件管理是 ANE 左侧菜单下的二级页面。每个插件以独立卡片显示名称、版本、说明、来源、状态和启停开关；只有导入插件显示卸载操作。导入入口只读取 ANE 当前浏览目录中的 `.zip` 文件，不启动系统文件选择器或其他文件管理器。

宿主导入时会执行以下步骤：

1. 限制包、manifest 和 Dex 大小。
2. 校验 manifest 字段、API 版本和 `classes.dex` SHA-256。
3. 将完整包复制到应用私有目录，并在写入代码前设为只读，以满足 Android 14+ 动态代码加载要求。
4. 使用以宿主为 parent 的 `DexClassLoader` 创建实例并调用 `onLoad`。

停用会调用 `onUnload` 并移除所有动作引用；重新启用会创建新实例。卸载只适用于导入插件，会在确认后删除其私有程序文件。内置插件可以停用但不能卸载。内置清单可声明 `defaultEnabled: false`，使插件首次出现时保持停用；用户显式启停后，其选择优先于清单默认值。内置插件不能被同 ID 的导入包替换。

## 7. 生命周期与安全

- `onLoad` 后才可提供能力；`onUnload` 后必须停止自己的任务并释放播放器、Handler、监听器和其他引用。
- 不得在主线程解压、哈希、解析大文件或执行网络请求。
- 分卷识别与组卷属于压缩插件自身能力，不得由宿主维护扩展名或卷号规则。内置压缩插件支持标准 ZIP 分卷（`.z01 … .zip`）、编号 ZIP/7z 分卷（`.zip.001 …`、`.7z.001 …`）以及新旧 RAR 分卷（`.part01.rar …`、`.rar + .r00 …`）；任意卷均应解析到首卷，已能从文件名确认的缺卷应在任务开始前报告。
- 文件写入先到同目录临时位置，成功后再提交；不得静默覆盖已有路径。
- 归档插件必须阻止 `../`、绝对路径、符号链接、硬链接等目录越界。
- 密码使用 `CharArray`，完成后填零；不得写入日志、偏好设置、磁盘或异常文本。
- 插件 Dex 与宿主运行在同一进程、同一 UID，技术上拥有文件管理器的全部权限，无法视为安全沙箱。只安装可信来源插件；SHA-256 只能验证包内一致性，不能证明作者可信。
- 动态加载代码可能不符合部分应用商店政策。面向商店分发前应单独复核渠道规则；生产环境建议增加受信任签名或白名单。

## 8. 验收

1. 导入插件后不修改、不重编译 ANE，匹配文件的长按菜单立即出现新按钮。
2. 停用/启用后按钮立即消失/恢复，双击路由同步变化。
3. 卸载导入插件后代码和动作均消失；内置插件无卸载入口。
4. 两个插件匹配同一文件时，长按动作均显示，双击遵循 `priority`。
5. manifest、API 或 SHA-256 无效的包必须拒绝，且不得留下可加载的半成品。
6. 插件抛出异常时文件管理器继续可用，并给出插件级错误提示。
