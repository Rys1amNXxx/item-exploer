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

构建通过。GameTestServer 在独立服务端环境加载模组，10 项必需测试全部通过：

1. 方块实体经过二进制 NBT 存档往返后，嵌套目录、改名和拆分后的数量仍然一致。
2. 满背包不能消耗库存，包括创造模式；只有部分空间时只转移实际可容纳的数量。
3. 不同耐久和自定义名称的物品不合并，存档后数据保留。
4. 容量上限生效；条目已满时拒绝拆分且不改变库存；整体移动仍然可用。
5. 目录名称、目录深度、直接嵌套终端和超大物品数据受到约束。
6. 移除方块准确掉落 130 个铁锭，重复移除不会再次掉落。
7. 默认快照每页最多 6 条，越界页码被限制，外部获取的物品副本不能修改库存。
8. 过期版本请求和远距离请求不能取出物品，合法请求正常转移。
9. 文件夹与物品混合分页无遗漏、无重复，只显示直接子目录；页面大小变化后正确重排并限制请求范围。
10. 五种逻辑分辨率下界面区域与槽位不越界；缩放后槽位 ID、背包索引和实际物品保持一致。

第 8 项使用 Forge `FakePlayer`；它验证菜单校验逻辑，并不代表已经完成真实多人网络测试。
普通 Gradle `test` 没有 JUnit 源码；验证游戏逻辑必须显式运行 `runGameTestServer`。

构建产物：`build/libs/itemexplorer-1.20.1-0.1.1-dev.jar`。

## 客户端检查

0.1.1 使用 `work/ui-review/` 中的独立客户端目录和存档副本进行 UI 检查。
实际确认：灰色容器布局在 GUI 缩放 4 下完整显示，窗口为 440 × 340 逻辑像素；
原先三处常驻说明已移除。

- 创建 `wooden` 后，点击路径返回主存储盘，主区域出现其文件夹卡片。
- 从背包存入 64 个云杉原木后，文件夹与物品同时显示，文件夹排列在前。
- 将物品拖入主区域的 `wooden` 卡片，再点击卡片进入，目录内显示 64 个原木。
- 右键取出 1 个后，设备总量为 63，背包增加 1 个，数据一致。
- 折叠左侧目录树不改变当前打开目录和库存。
- 关闭客户端并重新进入副本后，`wooden` 目录和 63 个库存保留。
- 修复新建弹窗焦点后，无需再次点击输入框即可直接输入中文名称并创建目录。

这些是实际客户端操作检查，与服务端 GameTest 分开记录。

尚未完成：两个真实客户端的并发操作、模拟高延迟、所有 GUI 缩放组合、
第三方模组特殊物品、区块卸载再加载和长时间运行验证。

## 初始化记录

项目初始化时还通过了 `genIntellijRuns genVSCodeRuns` 和 `runData`。
IntelliJ 配置位于 `.idea/runConfigurations/`，VS Code 配置位于 `.vscode/`。
运行配置变化后可重新执行生成命令。首次使用 `runServer` 时需要自行处理 Minecraft EULA。

构建提示部分插件使用了将被 Gradle 9 移除的接口，因此使用已固定的 Gradle 8.8 Wrapper。
