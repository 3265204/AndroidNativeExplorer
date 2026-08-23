# 架构说明

## 总览

项目是单 `app` 模块 Android 应用。主页面采用自定义绘制，不使用 XML 页面布局；查看器 Activity 使用 Kotlin 动态构建界面。

```text
MainActivity
└─ FileManagerView                  组合状态并路由输入
   ├─ ui/render                     绘制、命中区域、缩略图
   ├─ ui/menu                       菜单状态与命令组装
   ├─ ui/motion                     菜单动画、惯性和手势时序
   ├─ ui/selection                  选择、多选、双击和滑选
   ├─ ui/appearance                 主题和显示参数
   ├─ navigation                    标签、历史和持久化
   ├─ operation                     文件事务、错误和撤回
   └─ input                         鼠标与桌面快捷键解析

ViewerRouter
├─ viewer/image                     图片查看与缩放
├─ viewer/video                     视频播放与目录切换
├─ viewer/audio                     音频播放与目录切换
└─ viewer/text                      文本编辑、编码与高亮
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

### menu

`FileMenuController` 保存菜单类型、位置和开合动画状态。

`FileMenuCoordinator` 根据当前选择、剪贴板、撤回状态和标签状态动态生成操作。无效操作应隐藏，而不是显示一个永远不可用的入口，请在开发时遵循这个规范。

### motion 与 selection

`ui/motion` 保存动画、惯性滚动和系统手势时序。长按、双击和拖拽距离优先跟随 Android `ViewConfiguration`。

`FileSelectionController` 只维护选择集合及单击/双击/多选语义，不绘图也不直接修改文件。

## 导航与持久化

`DockSessionController` 维护：

- 固定标签和临时标签。
- 当前活动标签。
- 各标签的当前目录和返回历史。
- 标签排序、固定、重命名和关闭。

`DockSessionStore` 将会话写入 `SharedPreferences`。应用升级、进程被杀或重新启动后应恢复标签顺序、固定状态、活动目录和历史。

“存储”是最左侧的默认固定标签。临时标签切换到其他标签后仍需保留，直到用户明确关闭或固定。

## 文件事务与撤回

文件操作分为三层：

1. `FileOps`：底层复制、移动、删除和路径判断。
2. `FileOperationService`：返回结构化 `FileResult`，把异常转换为 `FileProblem`。
3. `FileActionController`：连接选择、对话框、后台线程、撤回栈和 UI 提示。

复制、移动、删除和撤回通过命名的单线程执行器串行执行，避免多个事务同时修改相同路径或回收目录。控制器关闭后不再接受新任务；已排队的事务安全完成，但不再回调失效 Activity。

删除并非立即销毁：文件会移动到存储根目录下的 `.ane-filemanager-trash`。撤回记录保存在当前进程会话内，没有固定步数上限；重新启动应用时会清理旧回收内容，因此撤回不是跨进程持久化功能。

## 内置查看器

`ViewerRouter` 根据文件扩展名路由：

- 图片进入 `ImageViewerActivity`，支持双指缩放和左右切换。
- 视频进入 `VideoViewerActivity`，支持同目录上一个/下一个。
- 音频进入 `AudioViewerActivity`，支持播放列表和进度拖动。
- 文本进入 `TextEditorActivity`，支持编码识别、保存、惯性滚动、缩进和代码高亮。

所有查看器必须通过 `ViewerInsets` 适配状态栏、导航栏、刘海和 DeX 任务栏。

## 桌面输入

`DesktopShortcutResolver` 只解析按键组合并返回语义化 `DesktopAction`。具体文件命令仍由主界面控制器执行。

触屏长按、鼠标右键和键盘快捷键可以触发同一个业务命令，但平台事件解析必须保持分离。部分 DeX 设备一次右键会发送两个事件，兼容性去重集中定义在手势时序模块中。

