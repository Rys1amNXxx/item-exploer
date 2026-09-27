# 开发环境与验证

验证日期：2026-09-27。

## 本机环境

- 项目：`D:\Code\item-explorer`
- JDK：`C:\Program Files\Microsoft\jdk-17.0.13.11-hotspot`
- Java：Microsoft OpenJDK 17.0.13，64 位
- Minecraft 1.20.1，Forge 47.4.23，Gradle Wrapper 8.8，ForgeGradle 6.0.54
- Gradle 缓存：项目内 `.gradle-user-home/`
- 远程仓库：`https://github.com/Rys1amNXxx/item-exploer.git`

系统默认 Java 仍为原有版本；`dev.ps1` 为本次命令选择 JDK 17 并在结束后恢复环境。

## 自动验证

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 build --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 runGameTestServer --console=plain
```

构建通过。GameTestServer 在独立服务端环境加载模组，8 项必需测试全部通过：

1. 方块实体经过二进制 NBT 存档往返后，嵌套目录、改名和拆分后的数量仍然一致。
2. 满背包不能消耗库存，包括创造模式；只有部分空间时只转移实际可容纳的数量。
3. 不同耐久和自定义名称的物品不合并，存档后数据保留。
4. 容量上限生效；条目已满时拒绝拆分且不改变库存；整体移动仍然可用。
5. 目录名称、目录深度、直接嵌套终端和超大物品数据受到约束。
6. 移除方块准确掉落 130 个铁锭，重复移除不会再次掉落。
7. 快照每页最多 6 条，越界页码被限制，外部获取的物品副本不能修改库存。
8. 过期版本请求和远距离请求不能取出物品，合法请求正常转移。

第 8 项使用 Forge `FakePlayer`；它验证菜单校验逻辑，并不代表已经完成真实多人网络测试。
普通 Gradle `test` 没有 JUnit 源码；验证游戏逻辑必须显式运行 `runGameTestServer`。

构建产物：`build/libs/itemexplorer-1.20.1-0.1.0-dev.jar`。

## 客户端检查

`runClient` 已实际启动并进入单人世界。终端可以通过命令领取、放置、打开，
中文资源管理器界面的标题、目录区、工具栏、数量框和背包区正常显示。
后续客户端交互由用户接手试用；本次未将全部 GUI 路径记为自动验证通过。

尚未完成：两个真实客户端的并发操作、模拟高延迟、所有 GUI 缩放组合、
第三方模组特殊物品和长时间运行验证。服务端测试验证了 NBT 往返，
完整退出世界再进入、区块卸载再加载仍需补充端到端检查。

## 初始化记录

项目初始化时还通过了 `genIntellijRuns genVSCodeRuns` 和 `runData`。
IntelliJ 配置位于 `.idea/runConfigurations/`，VS Code 配置位于 `.vscode/`。
运行配置变化后可重新执行生成命令。首次使用 `runServer` 时需要自行处理 Minecraft EULA。

构建提示部分插件使用了将被 Gradle 9 移除的接口，因此使用已固定的 Gradle 8.8 Wrapper。
