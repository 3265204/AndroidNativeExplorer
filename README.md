# ANE — Android Native Explorer

[中文](README.md) | [English](README-en.md)

ANE（Android Native Explorer）是使用 Kotlin 编写的 Android 本地文件管理器，主要面向触屏、鼠标和 DeX 等桌面化 Android 环境。主界面使用自定义 `View` 绘制，并内置图片、视频、音频查看器和带代码高亮的文本编辑器。

## 功能

- 文件与文件夹浏览、创建、重命名、复制、剪切、粘贴、移动和删除。
- ZIP、7z、RAR、TAR 及常见 gzip/bzip2/xz 文件可像 Windows 资源管理器一样逐层浏览目录，也可通过长按菜单或浏览页一键解压；加密归档会自动提示密码。
- 会话内无限步撤回，删除内容暂存到隐藏回收目录，进程关闭后自动销毁。
- 列表与网格布局、深浅色主题、缩略图和长文件名滚动显示。
- 多选、滑动选择、长按拖动和标签栏拖动排序。
- 固定标签、临时标签和标签会话持久化。
- 可编辑地址栏、系统返回历史和退出确认。
- 鼠标右键与常用桌面快捷键。
- 文件夹内自动建立图片、视频和音频播放列表。
- 文本编辑、保存、Tab/Shift+Tab 缩进和基础代码高亮。
- Dock 标签页统一管理：逐项切换、固定、关闭、批量清理，并可控制启动时是否恢复临时标签。

## 使用

首次启动时授予“所有文件访问权限”，否则应用只能访问系统允许的有限目录。

- 单击文件或文件夹：选中。
- 双击：打开文件或进入文件夹。
- 长按文件：显示文件菜单；移动后进入文件拖动。
- 多选模式：单击只切换选择状态，不打开文件；可从选择区域滑动快速选择。
- 右下角加号：进入/退出多选、复制、剪切、粘贴、删除、新建和撤回；无效操作会自动隐藏。
- 标签栏：切换目录；长按进入标签编辑或拖动排序。
- 地址栏：点击后输入文件夹或文件的完整路径。
- 系统返回：优先返回当前标签的上一级或历史位置，无处可退时询问是否退出。
- 鼠标右键：在文件、标签或空白区域打开对应菜单。

常用桌面快捷键：

| 快捷键 | 行为 |
| --- | --- |
| `Ctrl+C / X / V` | 复制、剪切、粘贴 |
| `Ctrl+Z` | 撤回 |
| `Ctrl+A` | 全选并进入多选模式 |
| `Ctrl+L` | 编辑地址 |
| `Ctrl+Shift+N` | 新建文件夹 |
| `F2 / Delete / Enter / F5` | 重命名、删除、打开、刷新 |
| `Alt+Left / Alt+Up` | 历史后退、进入上级目录 |

`Esc` 不应当映射为应用动作，主要考虑到某些平板对 Esc 的特殊处理。

因为插件的权限原因，请不要安装不信任的插件。


## 开发环境要求

- Windows、macOS 或 Linux。
- Android Studio，或可用的命令行 Android SDK。
- Android SDK Platform 36 和 Build Tools。
- JDK 21（当前验证环境）；最低兼容版本以 Android Gradle Plugin 要求为准。
- Gradle Wrapper 会自动下载 Gradle 9.4.1，无需全局安装 Gradle。
- 调试设备需要 Android 6.0（API 23）或更高版本。

项目当前构建配置：

| 项目 | 值 |
| --- | --- |
| Application ID | `com.ane.filemanager` |
| Min SDK | 23 |
| Target SDK | 35 |
| Compile SDK | 36 |
| Android Gradle Plugin | 9.2.1 |

## 编译

在项目根目录执行：

```powershell
./gradlew :app:assembleDebug
```

Windows PowerShell 使用 `./gradlew.bat :app:assembleDebug`。

生成的 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

也可以使用 Android Studio 打开项目，等待 Gradle 同步后选择 `Build > Build APK(s)`。

adb 安装到已连接设备：

```powershell
adb devices
adb -s <设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

MuMu 开启本地 ADB 后，可按实际端口连接。以开发者开发环境示例：

```powershell
adb connect 127.0.0.1:16384
adb -s 127.0.0.1:16384 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 127.0.0.1:16384 shell am start -n com.ane.filemanager/.MainActivity
```


## 开发

代码结构、职责边界、修改流程和测试要求见 [docs](docs/README.md)，英文版见 [docs-en](docs-en/README.md)。开始修改前建议依次阅读：

1. [架构说明](docs/architecture.md) / [Architecture](docs-en/architecture.md)
2. [开发规范](docs/development.md) / [Development conventions](docs-en/development.md)
3. [测试指南](docs/testing.md) / [Testing guide](docs-en/testing.md)

请不要在该项目下添加非默认插件优化的pr，插件应作为另一个独立项目开发，该项目仅做为文件管理系统的开发使用。

## 碎碎念

如果你喜欢这个项目，或许可以给我一个 star。

项目支持二次修改并且在新增功能的前提下闭源盈利，但是项目不能倒卖，具体的许可边界依照 [LICENSE](LICENSE) 决定。
