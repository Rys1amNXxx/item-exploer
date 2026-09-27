# 版本选择记录

日期：2026-09-27。

## 决定

首版针对 Minecraft **1.20.1**、Forge **47.4.23**、JDK **17**。
ForgeGradle 固定为 **6.0.54**，使用官方 MDK 提供的 Gradle **8.8** Wrapper。

选择理由是当前已确定使用 Forge，功能集中在独立容器和 GUI，1.20.1 官方文档完整，
且 Forge 仍有该版本的维护发布。本项目不需要新游戏版本的特性。
这是针对当前项目范围的工程选择，不代表 1.20.1 是最新游戏版本，也不是正式的 LTS 支持承诺。

## 对比

- **1.20.1**：采用。可以基于已核实的菜单、方块实体、网络文档推进首版。
- **1.21.1**：Forge 同样提供发行版，是可行选项；若明确要加入某个新版整合包，应重新按该整合包的游戏版本和加载器选型。
- **26.3**：核对时 Forge 已提供 66.0.5，但本项目没有追随最新游戏版本的功能需求。

升级 Minecraft 版本不保证只修改版本号即可，特别是物品数据、序列化、GUI 和网络接口。
因此当前将 Minecraft 兼容范围限定为 1.20.1，Forge 最低版本保守限定为开发使用版本。

## 来源与下载校验

- [Forge 1.20.1 下载页](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)
- [Forge 1.21.1 下载页](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.21.1.html)
- [Forge 当前下载页](https://files.minecraftforge.net/net/minecraftforge/forge/)
- [Forge 1.20.1 开发环境说明](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/)
- [ForgeGradle 元数据](https://maven.minecraftforge.net/net/minecraftforge/gradle/ForgeGradle/maven-metadata.xml)

原始 MDK：
`https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.23/forge-1.20.1-47.4.23-mdk.zip`

官方 SHA-1（下载后已比对）：`b046c49aaa7aef022b9cbe1c2aceb441c7ea43c3`。
