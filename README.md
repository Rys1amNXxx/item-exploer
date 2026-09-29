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
| 浏览子文件夹 | 主区域优先显示当前目录的子文件夹，单击进入；点击上方路径返回祖先目录 |
| 展开 / 折叠目录树 | 点击左侧文件夹前的箭头，点击名称则进入目录 |
| 改名 / 删除 | 进入文件夹后操作；只能删除空文件夹，主存储盘不可删除 |
| 指定数量取出 | 选择条目，输入数量，点击“取出” |
| 快速取出 | 右键条目取 1 个，Shift 点击条目取最多 64 个 |
| 指定数量移动 | 选择条目、填写数量，再拖到左侧目录、主区域的文件夹或上方路径，也可点击“移动到…”后选择目标 |
| 移动 / 取出全部 | 选择条目，点击“全部”填入数量，再执行移动或取出 |
| 翻页 / 滚动目录 | 使用上一页、下一页按钮，或在对应区域滚动鼠标滚轮 |

同种物品可以分配到多个目录；分类移动不会增减总库存。
耐久、名称、附魔等数据不同的物品分别存储。取出时遵守原版堆叠上限，
背包放不下的部分继续留在设备中。

窗口使用接近原版容器的灰色边框和凹槽，保留原版按钮。
根据屏幕可用空间从 320 × 234 扩展到 440 × 340 逻辑像素，每页可显示 6–25 个文件夹或物品。
操作说明在按钮悬停时显示，成功操作不显示常驻文字；失败提示短暂显示后消失。

终端本地盘的所有目录共享 **4,096 件物品、128 个条目**的容量；同种物品在不同目录分别占一个条目。
最多 64 个目录（含主存储盘）、8 层子目录，名称最长 24 个 UTF-16 代码单元。
单个物品样本序列化数据上限为 8 KiB；终端、NAS 和硬盘不能放入任何存储盘。

**拆除终端会将本地盘库存掉落到世界中，目录结构不会保留在掉落方块上。**
掉落物仍受原版消失、火焰、爆炸等规则影响，搬家前建议先取出物品。
0.3.0 可通过单独的物流接口接入漏斗 / 管道；远程存储、搜索、排序、目录重排和权限系统尚未实现。
可以接近设备的玩家都能使用它。

0.1.2 增加存档保护：未知版本、损坏目录或异常物品数据会使终端进入“数据保护中”，
暂停存取并原样保存整份数据；服务端会尝试在世界目录的 `itemexplorer-recovery/`
生成独立的压缩 NBT 恢复文件，日志记录原因、方块位置和文件路径。
恢复文件不会自动导入库存。恢复步骤与多人验收清单见 [P0 稳定性说明](docs/p0-reliability.md)。

## NAS 与硬盘（0.2.0）

将四盘位 NAS 放在终端的正后方（屏幕的相反方向），右键 NAS，
Shift 点击背包硬盘安装，或拿起硬盘后点对应盘位的“安装”。“弹出”需要背包有一个空位。
回到终端，左侧硬盘与主存储盘并列；选择硬盘根节点后点“改名”可自定义盘名。

| 硬盘 | 物品容量 | 条目上限 |
| --- | ---: | ---: |
| 64K | 65,536 | 512 |
| 256K | 262,144 | 1,024 |
| 1M | 1,048,576 | 2,048 |
| 16M | 16,777,216 | 4,096 |

每块硬盘最多 128 个目录（含根目录），用途由玩家决定，盘之间不共用容量或目录。
拆除 NAS 掉落硬盘，盘内库存与目录保留，可在同一存档内换机箱使用。
手动跨盘搬运先经过背包；外部物流可通过 0.3.0 的接口接入，尚无远程连接或直接跨盘拖动。
硬盘的完整数据保存在世界文件中，复制硬盘物品到另一个存档不会携带库存。
操作方法、配方和存档说明见 [NAS 使用说明](docs/nas-usage.md)。

## 外部物流接口（0.3.0）

潜行右键 NAS 或终端的表面安装“文件夹物流接口”，右键接口选择硬盘和目录，
设置允许存入 / 取出及是否包含子目录，然后点击“应用配置”。管道或总线连接外侧中央接口。
接口独占一个相邻方块位置，支持六个方向；目录改名不影响绑定，拔盘或目标失效时暂停存取。
新物品始终进入选定目录，子目录选项只扩展查询与取出范围。

同时更新四档硬盘的金属外壳、容量铭牌像素贴图和金色触点。
配方、连接限制、可选 MEK / AE2 测试和操作方法见 [物流接口使用说明](docs/logistics-usage.md)。
内部无线传输与云存储仍在 [规划阶段](docs/logistics-plan.md)。

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

打包输出：`build/libs/itemexplorer-1.20.1-0.3.0-dev.jar`。
将此 JAR 放入安装了对应 Forge 的 Minecraft 1.20.1 实例的 `mods/` 目录。
多人游戏的客户端与服务端都需要安装相同版本。0.3.0 继续读取 0.1.x
使用的终端版本 1 存档及 0.2.x 硬盘数据；网络协议更新为 5，不能与旧版混用客户端和服务端。
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
GitHub Actions 会在 push / pull request 时执行 `build runGameTestServer`，
保存验证日志，并在全部通过后保存 JAR；也可以手动触发。

其他任务：`runServer` 启动独立开发服务器，`runData` 运行资源生成。
客户端使用 `run/`，服务端使用 `run-server/`，数据生成使用 `run-data/`。
首次使用独立服务器时需自行阅读并处理 Minecraft EULA。

## 代码与资源

- `src/main/java/dev/itemexplorer/ItemExplorer.java`：模组入口，客户端和服务端共用。
- `src/main/java/dev/itemexplorer/storage/`：通用存取接口、动态容量、目录和背包转移逻辑。
- `src/main/java/dev/itemexplorer/disk/`：容量等级、磁盘身份和独立世界存档。
- `src/main/java/dev/itemexplorer/block/`：设备方块、存档和拆除掉落。
- `src/main/java/dev/itemexplorer/menu/`：服务端操作校验与原版背包槽位。
- `src/main/java/dev/itemexplorer/network/`：操作请求与分页快照。
- `src/main/java/dev/itemexplorer/client/`：客户端注册和资源管理器界面。
- `src/gametest/`：Minecraft GameTest 自动测试。
- `src/main/resources/META-INF/mods.toml`：模组信息及依赖声明。
- `gradle.properties`：游戏、Forge、模组版本及名称。
- `docs/design.md`：首版设计、数据规则与尚未实现的功能。
- `docs/logistics-plan.md`：物流讨论留档，以及尚未实现的内部无线发送与云存储规划。
- `docs/logistics-usage.md`：0.3.0 外部物流接口的已实现行为、连接方式和验证方法。
- `docs/version-choice.md`：版本选择理由与官方来源。
- `docs/setup-verification.md`：本机环境与当前验证结果。
- `docs/forge-mdk/`：保留的官方 MDK 说明、版权与许可证资料。

目前没有 AE2、Refined Storage 或其他游戏模组依赖。
项目名称、作者和项目代码许可证可在正式发布前确定；暂沿用 MDK 默认的
`All Rights Reserved` 元数据。第三方 MDK 文件遵循其原始许可证。
