# 自动程序配方总览改版

日期：2026-10-23。源码及安装包版本：0.5.1-dev；网络协议：20。

## 本次改动

- “加工配方／连接设置／运行”三页，保留 320×238 逻辑尺寸。
- 通用样板左右同时展示各最多 9 项原料与预期产物，使用真实物品图标与数量；点击空格添加样本，选中后更换、删除或修改数量。
- 同一菜单内的搜索列表选择设备、物品、目录、方向；物品候选显示来源目录和库存数量，长名称有完整提示。Esc 先关闭选择器。
- 输入样本选中后绑定其来源目录，输出样本与存入目录独立。更换目录不会悄悄替换为另一种原料。
- 连接页设置世界方向、默认进出料面和逐项覆盖；原版设备自动管理方向，可以按原料／燃料或辅料／产物角色设置目录。
- 运行页预览多种物料的总量，可滚动查看。原版燃料标记按需消耗，酿造烈焰粉另行补充；未确认的原版配方不会显示上一次保存的旧产物。
- 保存并试做固定执行 1 批，保留下次计划批数。运行期间使用服务端实际批数及保存配方显示总量。
- 显示无效配置原因、离线、等待产物、待归还等状态；未保存草稿不会被普通后台快照替换。

未改变加工规则、物料搬运、程序及存档格式。本版没有实现配方自动识别、拖入 JEI 样板、流体加工或外部库存挂载。

## 保存后启动

菜单快照新增持久 `SaveReceipt`，避免瞬时保存消息被下一帧普通快照覆盖。客户端匹配回执序号、会话、连接上下文、请求版本、处理后版本、机器及实际保存内容，再发起启动。

保存失败、期间发生其他修改、连接变化、超时或关闭界面不会自动启动。回执处理前清除待启动意图，不能因持续收到相同回执重复启动。已经保存且未修改的程序可以直接启动，因此原版样本被消耗完后仍能等待补货。

新增 3 项菜单 GameTest 验证回执持久、同内容重存、后续其他修改、拒绝覆盖先前成功回执、无效会话和原版路径。请求仍由服务端解析库存样本，不信任客户端提供的物品数据。

## 构建与服务端测试

Java 17 / Forge 47.0.0，构建通过。全新独立测试世界中 **278 项必需 GameTest 全部通过**。

```powershell
.\dev.ps1 build runGameTestServer --init-script work/processing-overview-clean-tests.init.gradle --console=plain
```

本次隔离脚本内容如下；复跑时可换一个尚未存在的工作目录，保留旧测试数据以便排查。

```groovy
gradle.beforeProject { p -> p.afterEvaluate {
    p.minecraft.runs.gameTestServer.workingDirectory = p.file('work/processing-overview-gametest-clean-20261023-a')
} }
```

日志：`work/processing-overview-clean-build-tests.log`。

第一次使用已有 `run-gametest` 世界运行时为 277/278，通过之外的一项为既有基站测试 `overlappingStationsOwnTheirTicketsIndependentlyAndControllerRemovalReleasesOnlyItsOwn`，断言 Owner B 的 Forge 区块票据集合不符。保留日志 `work/processing-overview-build-tests.log`。没有修改基站生产代码或放宽断言；全新世界中原测试通过，旧世界失败的根因未进一步确认。

## 实际中文客户端

```powershell
.\dev.ps1 build runClient --init-script scripts/generic-production-client-test.init.gradle --console=plain
.\dev.ps1 runClient --init-script scripts/production-client-test.init.gradle --console=plain
```

两套验收使用各自独立世界，实际点击控件、输入搜索词，通过真实菜单请求和服务端响应验证。

最终运行日志：`work/processing-overview-final-generic-client.log`、`work/processing-overview-final-vanilla-client.log`。

- 通用样板：两种原料、两种产物，独立目录与逐项方向，搜索列表、保存、重开、修改配方后保存并试做、取消；重名保存被服务端拒绝时不投料，失败后仍可编辑；试做 1 批不覆盖计划 2 批，实际物料守恒。
- 原版熔炉：实际两次熔炼，产物 2 个、剩余原料 1 个、煤炭耗尽；已保存定义仍可再次启动；程序改名、复制、移动、删除继续通过。
- 两套界面均验证 320×240、427×240 逻辑窗口三页布局及控件边界；已目视检查截图。

通用 UI 的测试设备是桶，用于可控地验证物品接口、配置与投料，并不表示桶具有加工能力。真实 Mekanism 加工兼容性见上一期报告，本次没有重跑第三方模组套件。

- [通用客户端报告](generic-client-report.txt)
- [原版客户端报告](vanilla-client-report.txt)
- [最小窗口配方总览](generic-minimum-recipe.png)
- [搜索物品与来源目录](generic-material-search.png)
- [连接设置](generic-minimum-connections.png)
- [批次总量预览](generic-minimum-run-preview.png)
- [实际试做状态](generic-854-running-trial.png)
- [原版配方](production-minimum-configured.png)

## 安装

安装包位于 `build/libs/itemexplorer-1.20.1-0.5.1-dev.jar`。网络协议为 **20**，客户端和服务端必须一起更新，不能与协议 19 的一期开发包混连。已有程序和物品库存继续读取。

安装包大小：585,897 字节。

SHA-256：`3E257CF873EFE61CE54C530814E20AD2D4954D79113BC9CB4DD82D33B67D0712`。

已检查安装包不包含 GameTest、客户端验收场景或第三方模组类。
