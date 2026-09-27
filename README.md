# Item Explorer / 物品资源管理器

独立的 Minecraft Forge 存储模组，目标是用文件夹和资源管理器式界面管理游戏物品。
当前是已通过构建和数据生成启动检查的开发骨架，尚未实现存储方块或游戏内管理界面。

## 开发版本

| 组件 | 固定版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.23 |
| Java | JDK 17，64 位 |
| Gradle Wrapper | 8.8 |
| ForgeGradle | 6.0.54 |
| 映射 | Mojang official 1.20.1 |
| Mod ID | `itemexplorer` |
| 基础包名 | `dev.itemexplorer` |

## Windows 快速开始

项目内的 `dev.ps1` 自动寻找已安装的 JDK 17，并将 Gradle 缓存放在项目的
`.gradle-user-home/` 中；只在命令执行期间调整环境变量。

```powershell
cd D:\Code\item-explorer
powershell -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 build
powershell -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 runClient
```

打包输出：`build/libs/itemexplorer-1.20.1-0.1.0-dev.jar`。
这是开发骨架 JAR，目前不会添加存储设备。
`-ExecutionPolicy Bypass` 仅适用于当前 PowerShell 子进程，不修改系统策略。

如果 JDK 17 位于非标准目录，可在当前终端指定：

```powershell
$env:ITEM_EXPLORER_JAVA_HOME = 'D:\your-jdk-17'
powershell -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 build
```

## IDE

IntelliJ IDEA：打开项目根目录，作为 Gradle 项目导入。
将 Project SDK 和 Gradle JVM 都设为 JDK 17，Gradle distribution 使用 Wrapper。
Gradle user home 可设为项目内的 `.gradle-user-home`，复用已经下载的依赖。

生成运行配置：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 genIntellijRuns genVSCodeRuns
```

VS Code 需要 Java 和 Gradle 的相应扩展；本项目不自动安装 IDE 扩展。
命令行 `runClient` 不依赖 IDE。

其他任务：`runServer` 启动独立开发服务器，`runData` 运行资源生成。
客户端使用 `run/`，服务端使用 `run-server/`，数据生成使用 `run-data/`。
首次使用独立服务器时需自行阅读并处理 Minecraft EULA。

## 代码与资源

- `src/main/java/dev/itemexplorer/ItemExplorer.java`：模组入口，客户端和服务端共用。
- `src/main/resources/META-INF/mods.toml`：模组信息及依赖声明。
- `gradle.properties`：游戏、Forge、模组版本及名称。
- `docs/design.md`：首版范围与开发顺序。
- `docs/version-choice.md`：版本选择理由与官方来源。
- `docs/setup-verification.md`：本机环境与首次验证结果。
- `docs/forge-mdk/`：保留的官方 MDK 说明、版权与许可证资料。

目前没有 AE2、Refined Storage 或其他游戏模组依赖。
项目名称、作者和项目代码许可证可在正式发布前确定；暂沿用 MDK 默认的
`All Rights Reserved` 元数据。第三方 MDK 文件遵循其原始许可证。
