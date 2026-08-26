# ANE 内置 PTY 终端插件

终端的唯一实现位于 `app/src/main/java/com/ane/filemanager/plugin/terminal`，随 APK 作为默认停用的内置插件交付。它在启用后通过右下角加号菜单提供“在此处打开终端”，从文件管理器当前浏览目录启动 `/system/bin/sh -i`，不占用文件或文件夹的长按菜单。此目录只保留兼容性打包脚本，可从同一份内置源码生成开发 ZIP，不维护第二份实现。

## 它是真正的终端

插件通过宿主 API v3 打开原生 PTY，而不是按行启动命令：

- 一个窗口对应一个持久 Shell 进程，`cd`、`export`、函数和 Shell 状态持续保留；
- 回车只向 PTY 写入 `CR`，不会显示每条命令的“退出码 0”；
- PTY 提供终端行规程、窗口行列数、前台进程组和控制字符；
- 使用自然比例的系统等宽字体，并按字形实际宽度在终端列中居中；可通过 `A− / A+` 独立调整并记住终端字号；
- 行列数根据终端实际尺寸自动计算，窗口、横竖屏、软键盘和字号变化时同步调整 PTY；
- 内置 ANSI/VT 模拟器支持光标移动、擦除、滚屏区、主/备用屏幕、SGR 样式、16/256/真彩色和 UTF-8 宽字符；
- 支持软键盘、物理键盘、Esc、Tab、方向键、Ctrl+C、Ctrl+D、粘贴和滚动历史；
- 可运行依赖 PTY 的交互命令；具体命令是否存在仍取决于 Android 系统环境。

Shell 以 ANE 的应用 UID 和已授权文件权限运行，不需要 Root，也不会绕过 Android 应用沙箱或 SELinux。

## 为 Agent 预留的边界

原生会话能力位于公共 API v3 的 `PluginTerminalSession`，而非终端对话框内部。后续 Agent 可以直接使用同一接口完成：

- 原始字节输入与流式输出；
- 动态调整终端行列数；
- 明确发送信号或关闭会话；
- 在一个长生命周期 Shell 中维持工作目录与环境状态。

审批、命令策略、审计、超时和工具探测应由 Agent 层包在会话接口之外。终端 UI 与 Agent 可以共享宿主 PTY 能力，但不需要互相依赖。

## 界面

导入插件不能直接引用宿主内部 UI 类，但可以复制其实现与设计参数。本插件沿用 ANE 文件管理器的语义色板、Monet 取色规则、深浅色偏好、文字大小、二级页面间距、圆角卡片和按钮样式，因此视觉上与原文件管理器一致，同时保持插件 Dex 独立。

## 构建

从仓库根目录执行：

```bash
./gradlew :plugin-api:assembleRelease :app:assembleDebug
ANDROID_HOME=/path/to/android-sdk ./gradlew -p terminal-plugin testDebugUnitTest packagePlugin
```

产物：

```text
terminal-plugin/build/dist/ane-terminal-0.3.4.zip
app/build/outputs/apk/debug/app-debug.apk
```

v0.3 插件需要 API v3 宿主 APK。先更新 ANE，再把 ZIP 复制到 Android 的 Download；在 ANE 中进入该目录，通过“管理插件 → 从当前文件夹导入”安装。

## 运行边界

- 无需 Root，但受 ANE 应用 UID、Android SELinux、存储授权和系统命令可用性的限制。
- 插件包只包含 `plugin.json` 与 `classes.dex`；各 ABI 的原生 PTY 库由宿主 APK 提供。
- API v3 宿主继续接受 v2 插件；API v2 宿主无法加载本插件。
- “在此处打开终端”由 API v3 的当前目录动作提供，只出现在右下角加号菜单，并始终使用当前浏览目录。
