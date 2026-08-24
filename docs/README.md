# 开发文档

本目录描述 ANE — Android Native Explorer 当前实现的结构和开发约束。文档以仓库中的实际代码为准；修改架构、交互约定或构建流程时，应在同一次提交中同步更新对应文档。

## 文档索引

- [architecture.md](architecture.md)：模块划分、数据流、状态和线程模型。
- [development.md](development.md)：编码规范、功能修改流程和关键约束。
- [plugins.md](plugins.md)：热插拔协议、插件 manifest、边界、安全与验收规范。
- [testing.md](testing.md)：编译、ADB 安装、模拟器和真机回归测试。

## 推荐阅读顺序

首次参与开发时先阅读架构说明，确认功能所属模块；再按开发规范实施；最后使用测试指南完成模拟器和真机验证。

## 目录约定

```text
ane/
├─ app/
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/com/ane/filemanager/
│     │  ├─ pluginmanager/   安装、发现、启停及自有 res
│     │  └─ plugin/          相互独立且各自带 res 的内置插件
│     └─ res/                仅文件管理器核心资源
├─ plugin-api/              插件开发 SDK（AAR）
├─ docs/
├─ testphoto/
├─ README.md
├─ build.gradle
├─ settings.gradle
└─ gradle.properties
```

- 根目录 `README.md` 项目介绍、编译和用户入门。
- `docs/` 开发者文档。
- `testphoto/` 验证截图、测试媒体和临时测试文件。
- 构建产物只应出现在 Gradle 的 `build/` 目录中。
- 注：如果您希望pr，您在本地环境下验证采用的testphoto和build文件夹内容请勿上传到pr。
