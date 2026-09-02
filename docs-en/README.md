# Development Documentation

This directory describes the structure and development constraints of ANE — Android Native Explorer as currently implemented. The documentation reflects the actual code in the repository; when you modify architecture, interaction conventions, or build flow, update the corresponding documentation in the same commit.

## Documentation index

- [architecture.md](architecture.md): module layout, data flow, state, and threading model.
- [development.md](development.md): coding conventions, feature-change workflow, and key constraints.
- [plugins.md](plugins.md): hot-plug protocol, plugin manifest, boundaries, security, and acceptance criteria.
- [agent-plugin.md](agent-plugin.md): file Agent, transaction plans, and branching history design.
- [testing.md](testing.md): build, ADB install, emulator, and physical-device regression testing.

## Recommended reading order

On your first contribution, read the architecture doc first to confirm which module your feature belongs to; then implement following the development conventions; finally use the testing guide to verify on the emulator and a physical device.

## Directory conventions

```text
ane/
├─ app/
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/com/ane/filemanager/
│     │  ├─ pluginmanager/   install, discovery, enable/disable, and its own res
│     │  └─ plugin/          independent built-in plugins, each with its own res
│     └─ res/                file-manager core resources only
├─ plugin-api/              plugin runtime protocol plus UI, input and file capability contracts (AAR, currently v3)
├─ docs/
├─ docs-en/                 English developer documentation
├─ testphoto/               adb test files
├─ README.md
├─ README-en.md             English project README
├─ build.gradle
├─ settings.gradle
└─ gradle.properties
```

- The root `README.md` is the project introduction, build instructions and user quick-start.
- `docs/` is the Chinese developer documentation; `docs-en/` is the English developer documentation.
- `testphoto/` holds verification screenshots, test media and temporary test files.
- Build artifacts should only appear under Gradle's `build/` directory.
- Note: if you open a PR, do not upload the contents of the `testphoto` and `build` folders verified locally.
