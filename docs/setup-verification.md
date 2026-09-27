# 初始化验证

验证日期：2026-09-27。

## 本机环境

- 项目：`D:\Code\item-explorer`
- JDK：`C:\Program Files\Microsoft\jdk-17.0.13.11-hotspot`
- Java：Microsoft OpenJDK 17.0.13，64 位
- Gradle 缓存：项目内 `.gradle-user-home/`
- Git：已初始化本地 `main` 分支，尚无提交或远程仓库

系统默认 Java 仍为原有版本；`dev.ps1` 为本次命令选择 JDK 17 并在结束后恢复环境。

## 已执行并通过

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 build genIntellijRuns genVSCodeRuns --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 runData --console=plain
```

- 首次构建与 IDE 配置生成：退出码 0，`BUILD SUCCESSFUL`。
- `runData`：退出码 0；Forge 47.4.23 加载并为 `itemexplorer` 初始化 Data Gatherer。
- JAR 中存在 `dev/itemexplorer/ItemExplorer.class`、展开后的 `META-INF/mods.toml` 和 `pack.mcmeta`。
- 字节码 major version 为 61（Java 17）。
- Minecraft 版本范围为 `[1.20.1]`；Forge 版本范围为 `[47.4.23,48)`。
- IntelliJ 运行配置位于 `.idea/runConfigurations/`；VS Code 运行配置位于 `.vscode/`。

构建产物：`build/libs/itemexplorer-1.20.1-0.1.0-dev.jar`。

## 验证范围

当前没有测试用例或数据生成器，`test` 显示 `NO-SOURCE` 是预期结果。
`runData` 验证了开发环境和模组加载路径，不代表游戏内 GUI 或多人存储逻辑已实现或验证。
未启动图形游戏客户端，未接受或启动独立服务器 EULA。
MDK 的 `runGameTestServer` 配置保留；尚未编写 GameTest 时不要用它验证游戏功能。

构建提示部分插件使用了将被 Gradle 9 移除的接口，因此使用已固定的 Gradle 8.8 Wrapper。
