# Item Explorer / 物品资源管理器

独立的 Minecraft Forge 存储模组，目标是用文件夹和资源管理器式界面管理游戏物品。
目前已实现可游玩的初版：存储终端、目录树、按数量分类、背包存取和存档。
这是早期开发版，容量、外观和交互还可以继续调整。

## 游戏中使用

合成“物品存储终端”，放置后右键打开。也可在创造模式“功能方块”分类找到，
或在允许命令的世界使用 `/give @s itemexplorer:storage_terminal`。

配方：7 个铁锭、1 个红石粉、1 个箱子。

```text
铁 红石 铁
铁 箱子 铁
铁  铁  铁
```

| 操作 | 方法 |
| --- | --- |
| 存入 | Shift 点击下方背包中的物品，存入当前文件夹 |
| 存入鼠标上的物品 | 点击“存入”，或点击右侧物品区 |
| 新建文件夹 | 进入父文件夹，点击“新建目录”，输入名字后确定 |
| 改名 / 删除 | 进入文件夹后操作；只能删除空文件夹，主存储盘不可删除 |
| 指定数量取出 | 选择条目，输入数量，点击“取出” |
| 快速取出 | 右键条目取 1 个，Shift 点击条目取最多 64 个 |
| 指定数量移动 | 选择条目、填写数量，再拖到左侧目录，或点击“移动到…”后选择目录 |
| 移动 / 取出全部 | 选择条目，点击“全部”填入数量，再执行移动或取出 |
| 翻页 / 滚动目录 | 使用上一页、下一页按钮，或在对应区域滚动鼠标滚轮 |

同种物品可以分配到多个目录；分类移动不会增减总库存。
耐久、名称、附魔等数据不同的物品分别存储。取出时遵守原版堆叠上限，
背包放不下的部分继续留在设备中。

初版所有目录共享 **4,096 件物品、128 个条目**的容量；同种物品在不同目录分别占一个条目。
最多 64 个目录（含主存储盘）、8 层子目录，名称最长 24 个 UTF-16 代码单元。
单个物品样本序列化数据上限为 8 KiB，存储终端本身不能存入终端。

**拆除设备会将库存掉落到世界中，目录结构不会保留在掉落方块上。**
掉落物仍受原版消失、火焰、爆炸等规则影响，搬家前建议先取出物品。
目前未接入漏斗 / 管道、远程存储、搜索、排序、目录重排或权限系统。
可以接近设备的玩家都能使用它。

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
将此 JAR 放入安装了对应 Forge 的 Minecraft 1.20.1 实例的 `mods/` 目录。
多人游戏的客户端与服务端都需要安装。
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

运行库存与菜单的游戏内自动测试：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 runGameTestServer
```

GameTest 使用独立的 `run-gametest/` 世界，测试代码和测试结构不打进发布 JAR。
`build` 只进行构建，不代替 `runGameTestServer`；当前没有普通 JUnit 测试。

其他任务：`runServer` 启动独立开发服务器，`runData` 运行资源生成。
客户端使用 `run/`，服务端使用 `run-server/`，数据生成使用 `run-data/`。
首次使用独立服务器时需自行阅读并处理 Minecraft EULA。

## 代码与资源

- `src/main/java/dev/itemexplorer/ItemExplorer.java`：模组入口，客户端和服务端共用。
- `src/main/java/dev/itemexplorer/storage/`：库存、目录和背包转移逻辑。
- `src/main/java/dev/itemexplorer/block/`：设备方块、存档和拆除掉落。
- `src/main/java/dev/itemexplorer/menu/`：服务端操作校验与原版背包槽位。
- `src/main/java/dev/itemexplorer/network/`：操作请求与分页快照。
- `src/main/java/dev/itemexplorer/client/`：客户端注册和资源管理器界面。
- `src/gametest/`：Minecraft GameTest 自动测试。
- `src/main/resources/META-INF/mods.toml`：模组信息及依赖声明。
- `gradle.properties`：游戏、Forge、模组版本及名称。
- `docs/design.md`：首版设计、数据规则与尚未实现的功能。
- `docs/version-choice.md`：版本选择理由与官方来源。
- `docs/setup-verification.md`：本机环境与当前验证结果。
- `docs/forge-mdk/`：保留的官方 MDK 说明、版权与许可证资料。

目前没有 AE2、Refined Storage 或其他游戏模组依赖。
项目名称、作者和项目代码许可证可在正式发布前确定；暂沿用 MDK 默认的
`All Rights Reserved` 元数据。第三方 MDK 文件遵循其原始许可证。
