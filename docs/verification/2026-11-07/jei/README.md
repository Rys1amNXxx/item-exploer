# 终端 JEI 配方填充验证

日期：2026-11-07。Minecraft 1.20.1 / Forge 47.0.0 / Java 17，JEI 15.20.0.106。

## 实现范围

- 固定终端和便携终端共用 JEI 工作台配方搬运注册，合成输入为菜单槽 37–45，产物槽 36 不参与搬运。
- 来源和退料位置仅包含背包与快捷栏，并排除正在使用的便携终端所在槽位；已有合成格材料仍可由 JEI 使用。
- 单击「＋」填入一组，Shift + 点击按完整配方尽量填满；领取产物继续使用原有手动合成逻辑。
- 不从终端或 NAS 存储取料，不生成自动程序，不自动合成或领取结果。
- JEI 使用可选编译依赖。未安装 JEI 时不加载兼容入口，原有终端和合成逻辑继续可用。
- 搬运使用 JEI 的服务端协议，多人客户端与服务器均需安装 JEI；单人游戏由集成服务器执行。

JEI 15.20 的空间预检将空配方格计入输入数量，实际客户端复现满背包时仍可点击「＋」。终端新增独立的只读空间预检：模拟将当前合成格物品归还背包，按物品及附加数据匹配堆叠，检查槽位容量且不使用被锁定的便携终端槽位。空间不足显示中英文提示。预检保守地要求整个旧合成格可归还，不依赖即将消耗的材料腾出空位；实际搬运与并发库存变化仍由 JEI 协议处理。

## 服务端验证

带 JEI 的独立 GameTest 世界中，**311 项必需测试全部通过**，包含 7 项新增测试：

- 一组有序配方只填格，不自动合成，不读取存储库存。
- 最大批量只投入完整组数，保留余料，Shift 合成后的数量守恒。
- 缺料时恢复全部物品，保留旧合成格。
- 无序配方替换后，旧合成格物品的数量与 NBT 保持不变。
- 主手与副手便携终端不被搬走，断开存储连接时背包合成仍可用。
- 伪造引用被锁定设备槽位的搬运被 JEI 服务端拒绝。
- 满背包、两个不同余料争用一个空槽、同物品不同 NBT 的空间判断，以及预检不改变实际库存。

```powershell
.\dev.ps1 runGameTestServer -PwithJei -PjeiTestDirectory=work/jei-integration/gametest-final --init-script scripts/jei-test.init.gradle --console=plain
```

不安装 JEI、并从 GameTest 运行类路径排除 JEI API 后，构建与 **304 项原有测试全部通过**。隔离脚本内容如下，复跑时使用新的工作目录：

```groovy
gradle.beforeProject { p -> p.afterEvaluate {
    p.sourceSets.gametest.runtimeClasspath = p.sourceSets.gametest.runtimeClasspath.filter { !it.name.startsWith('jei-') }
    p.minecraft.runs.gameTestServer.workingDirectory = p.file('work/jei-integration/no-jei-gametest')
} }
```

日志：`work/jei-integration/jei-tests-final.log`、`work/jei-integration/no-jei-tests.log`。

## 实际客户端

```powershell
.\dev.ps1 runClient -PwithJei --init-script scripts/jei-test.init.gradle --init-script scripts/jei-client-test.init.gradle --console=plain
```

验收使用独立 `JEI Probe` 世界，打开真实 JEI 配方窗口并点击实际「＋」按钮，通过标准搬运包验证服务端库存。中文客户端验证固定终端有序配方、手动领取成品、缺料提示、满背包拦截、便携终端无序配方与设备槽保护；已目视检查截图。

- [客户端报告](client-report.txt)
- [固定终端填充结果](fixed-terminal.png)
- [便携终端填充结果](portable-terminal.png)

未安装 JEI 的客户端也通过现有 `production-client-test.init.gradle` 验收：终端打开、程序文件操作、原版机器实际加工与界面返回均正常。生成的客户端运行类路径确认不含 JEI 或其 API。日志：`work/jei-integration/no-jei-client.log`。

初次脚本运行暴露了测试本身的三个假设问题：旧合成格退料占用预定空槽、染料配方 ID 选错、结果预览比合成格同步先到。均已修正脚本；满背包可错误启动则通过生产代码的空间预检修复。

## 安装包与限制

本页记录接入阶段产物：`build/libs/itemexplorer-1.20.1-0.5.1.jar`。该阶段未修改网络协议或存档格式，尚未发布新 Release；后续正式版本见 [0.5.2 发布验收](../release-v0.5.2.md)。

已检查 JAR 包含 JEI 兼容入口，不包含 JEI 本体、GameTest 或客户端验收代码。

SHA-256：`A82267FBF58226CFC5CD86684D4FB47638D421A4B9AE8DEA25CCF89676685FBD`。

本次未运行独立多人客户端与服务器的联机验收，也未逐一验证第三方自定义合成配方。JEI API 依据其[官方注册接口](https://github.com/mezz/JustEnoughItems/blob/1.20.1/CommonApi/src/main/java/mezz/jei/api/registration/IRecipeTransferRegistration.java)，具体行为以本报告记录的版本与实测为准。
