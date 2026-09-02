# 架构说明

## 总览

项目包含 `app` 应用模块和独立的 `plugin-api` v3 模块。`plugin-api` 同时提供稳定运行契约和宿主/插件共用的 UI 能力；主页面采用自定义绘制，不使用 XML 页面布局，插件界面使用 Kotlin 动态构建。

```text
MainActivity
└─ FileManagerView                  组合状态并路由输入
   ├─ ui/render                     绘制、命中区域、缩略图
   ├─ ui/menu                       菜单状态与命令组装
   ├─ ui/motion                     菜单动画、惯性和手势时序
   ├─ ui/secondary                  二级页外壳、安全区和响应式宽度
   ├─ ui/selection                  选择、多选、双击和滑选
   ├─ ui/appearance                 主题和显示参数
   ├─ navigation                    标签、历史和持久化
   ├─ operation                     文件事务、错误和撤回
   ├─ core/file                     文本文件读写与同目录文件序列
   └─ input                         鼠标与桌面快捷键解析

pluginmanager                     只负责安装、发现、启停与调用边界
├─ plugin-api                      稳定 ABI，以及 UI/输入/文件宿主能力契约
├─ plugin/archive/{代码,res}        归档解压、密码与自有文案
├─ plugin/image/{代码,res}          图片查看、缩放与自有文案
├─ plugin/video/{代码,res}          视频播放、目录切换与自有文案
├─ plugin/audio/{代码,res}          音频播放、目录切换与自有文案
└─ plugin/text/{代码,res}           文本编辑、编码、高亮与自有文案
```

## 入口与生命周期

`MainActivity` 主要负责：

- 创建 `FileManagerView`。
- 请求和检查存储权限。
- 提供系统对话框、Toast 和外部文件打开能力。
- 处理最终退出确认。
- 在销毁时关闭 `FileManagerView` 的后台控制器。

`FileManagerView` 是 Android View 边界和组合根。它可以持有各控制器，但不应继续吸收文件业务、菜单文案、持久化格式或绘制细节。

## UI 分层

### render

`FileManagerRenderer` 只根据 `RenderState` 绘制界面并记录命中区域。它负责地址栏、文件列表/网格、Dock、浮动按钮、菜单、拖动预览和忙碌遮罩。

`ThumbnailLoader` 异步加载图片和视频缩略图，结果进入内存缓存。绘制阶段不得同步解码大型媒体。

### 宿主 UI 与输入能力

`plugin-api/src/main/.../api/ui` 声明宿主 UI 能力、语义模型和可直接复用的标准组件，不建立第二个 SDK 模块，也不改变 `PluginApi.VERSION = 3`。`PluginHost.ui` 通过 `PluginUiProvider` 取得当前宿主页面实现；通用控件、窗口适配、媒体序列编排和字体策略位于 API，宿主页面外壳与运行期组合位于 `app/ui/PluginUiService` 和 `HostUi`。因此插件依赖的是 API 契约，颜色、字号、控件间距和通用动效不会散落回插件。

插件只提交语义参数，例如页面标题、面包屑、上一项/下一项、按键动作和无障碍文案。`browserPage`、媒体切换按钮、播放控制、终端按键栏及编辑器表面均由公共 UI 能力完成具体布局。`AneMediaSequenceStage` 通过回调更新标题、位置、前后项状态并编排移动，不暴露宿主 `SiblingFileSequence`；`AneTypography` 统一编辑器和终端的等宽字体策略。插件业务代码可以保存返回的 View 引用来更新状态，但不得重新设置公共组件的字号、语义颜色、圆角、透明度或标准内边距。自定义画布、终端模拟器、代码高亮和缩放算法等领域实现仍留在插件。

`plugin-api/.../api/input` 同样只声明 `AnePluginInput`。`PluginHost.input` 通过 `PluginInputProvider` 进入 `app/input/HostPluginInput`，屏幕终端键与硬件键使用同一宿主映射。这些 provider 都由当前 `PluginRegistry` 的宿主对象实现，没有向 `PluginHost` 接口增加抽象成员，因此仍保持 v3 ABI。

### 文件内容能力

`plugin-api/.../api/file` 声明 `PluginHost.files`、`fileQueries` 与 `outputs`。宿主统一负责文本编码检测、大小限制和保留编码写回；文本写入通过会话级 `FileTransactionService` 串行执行并进入树形历史。`fileQueries` 提供路径解析与同目录序列。需要生成文件或目录的插件先通过 `outputs.begin` 取得暂存路径，写入完成后由宿主选择无冲突目标、提交并创建一个历史节点。

`core/file/SiblingFileSequence` 是宿主内部共享业务实现：它扫描父目录、按本地 Collator 排序、定位当前文件并提供 previous/next。图片、音频和视频插件只提供自己的格式过滤器；宿主通过只读 `PluginHost.fileQueries` 返回公共序列契约，不向插件暴露内部实现或媒体类型。

### menu

`FileMenuController` 保存菜单类型、位置和开合动画状态。

`FileMenuCoordinator` 根据当前选择、剪贴板、撤回状态和标签状态动态生成操作。无效操作应隐藏，而不是显示一个永远不可用的入口，请在开发时遵循这个规范。

### motion 与 selection

`ui/motion` 保存动画、惯性滚动和系统手势时序。长按、双击和拖拽距离优先跟随 Android `ViewConfiguration`。

`FileSelectionController` 只维护选择集合及单击/双击/多选语义，不绘图也不直接修改文件。

### 二级页面

所有宿主全屏二级页面使用 `SecondaryPageScaffold`。它统一提供安全区、主题背景、页边距、返回区、标题与摘要、可用宽度变化和进退场；管理页面不得再次自行创建这套外壳。标签页、插件等功能仍各自拥有卡片、状态和操作，公共外壳不得引用具体业务类型。

宿主设置统一进入 `ui/settings/SettingsDialog`，主菜单不直接修改外观状态。语言由 `AppLanguage` 持久化，显示参数由 `AppearanceController` 持久化；设置页只编排控件与刷新回调。字体、图标和行距使用有上下界的连续整数滑块，选择类设置使用应用自有主题弹层。

## 导航与持久化

`DockSessionController` 维护：

- 固定标签和临时标签。
- 当前活动标签。
- 各标签的当前目录和返回历史。
- 标签排序、固定、重命名和关闭。

`DockSessionStore` 将会话写入 `SharedPreferences`。固定标签会跨进程恢复；临时标签默认只属于当前会话，用户可以在标签页管理器中选择下次启动也恢复临时标签。

“存储”是最左侧的默认固定标签。临时标签切换到其他标签后仍需保留，直到用户明确关闭或固定。
长按任意 Dock 标签后可进入就地管理态，在 Dock 上通过删除按钮快速关闭临时标签；固定标签必须先经用户确认取消固定，且本次操作只取消固定、不顺带关闭，“存储”不可取消固定。左上角应用菜单保留完整标签页管理器入口。完整管理器统一负责选择与进入、重命名、更改目录、固定、关闭、批量清理与启动恢复策略；更改目录时必须清空原标签的返回历史。

## 文件事务与撤回

文件操作分为三层：

1. `FileOps`：底层复制、移动、删除和路径判断。
2. `FileOperationService`：返回结构化 `FileResult`，把异常转换为 `FileProblem`。
3. `FileActionController`：连接选择、对话框、后台线程、树形操作历史和 UI 提示。

`FileTransactionService` 是会话内文件执行器、回收 payload 与历史树的唯一所有者。普通 UI、文本插件和插件输出提交共用它的单线程队列，避免形成彼此不可见的修改历史。`FileActionController` 只保留提示、选区和失败交互；事务服务由组合根最后关闭。

删除并非立即销毁：文件会移动到存储根目录下的 `.ane-filemanager-trash`。`FileHistoryController` 保存带父子关系的双向动作；撤回后执行新操作会保留原分支，并可重做到最新子分支或按节点切换分支。记录仍只保存在当前进程会话内，没有固定步数上限；重新启动应用时会清理旧回收内容，因此历史不是跨进程持久化功能。

文件 Agent 的能力边界和后续宿主事务 API 见 [agent-plugin.md](agent-plugin.md)。

## 插件

文件管理核心不引用具体插件类。`pluginmanager/PluginRegistry` 扫描内置 assets 清单和应用私有目录中的导入清单，通过公共 `plugin-api` 实例化插件。管理层不包含格式判断、播放列表或插件界面；这些运行逻辑全部留在对应插件目录内。

只按文件过滤并启动一个 Activity 的插件使用 `AneIntentPluginEntry`。API 统一 Intent extra 和启动流程，插件只保留目标 Activity 与格式过滤配置；格式集合是插件内的单一事实来源，入口匹配和同目录导航不得各自复制一份。

当前随应用提供的插件包括：

- 图片进入 `image/ui/ImageActivity`，格式过滤和缩放由 image 插件负责，同目录导航使用 `PluginHost.fileQueries` 的序列契约。
- 视频进入 `video/ui/VideoPlayerActivity`，播放由 video 插件负责，同目录导航使用同一查询契约。
- 音频进入 `audio/ui/AudioPlayerActivity`，播放与进度由 audio 插件负责，同目录导航使用同一查询契约。
- 文本进入 `TextEditorActivity`，通过 `TextFileService` 读取和保留编码写回；高亮、惯性滚动和缩进仍由 text 插件负责。
- 终端由 `TerminalConsoleDialog` 把领域内容提交给 `PluginHost.ui`；`TerminalSessionController` 管理 PTY 生命周期，屏幕键和硬件键都通过 `PluginHost.input` 进入宿主 `HostPluginInput`，`TerminalView` 只负责终端绘制、模拟器和输入连接。

各插件通过 `plugin-api` 的 UI 包统一适配状态栏、导航栏、刘海、DeX 任务栏、主题和标准弹窗，并在自己的 `res/values` 维护文案。公共 UI 只提供视觉语言，不拥有播放器、编辑器、压缩浏览等业务组件，也不建立 `plugin/shared` 一类隐式运行层。

协议字段、插件边界、返回结果和安全要求见 [插件开发规范](plugins.md)。

## 桌面输入

`DesktopShortcutResolver` 只解析按键组合并返回语义化 `DesktopAction`。具体文件命令仍由主界面控制器执行。

触屏长按、鼠标右键和键盘快捷键可以触发同一个业务命令，但平台事件解析必须保持分离。部分 DeX 设备一次右键会发送两个事件，兼容性去重集中定义在手势时序模块中。
