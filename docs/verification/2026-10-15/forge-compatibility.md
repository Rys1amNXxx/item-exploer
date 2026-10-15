# Forge 最低版本兼容性验证

日期：2026-10-15。环境：Windows 11、Minecraft 1.20.1、Temurin JDK 17.0.20.1、Gradle 8.8、ForgeGradle 6.0.54。

## 结论

Item Explorer 0.4.1 的编译基线与最低 Forge 要求均降为 **47.0.0**，依赖范围为 `[47.0.0,48)`。
这是 [Forge 官方 Maven 元数据](https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml) 中 Minecraft 1.20.1 的首个版本。
用户现有的 47.3.22 在本次测试范围内可用。

| Forge 运行版本 | GameTest 服务端 | 真实中文客户端 |
| --- | --- | --- |
| 47.0.0 | 187/187 通过 | 通过 |
| 47.3.22 | 187/187 通过 | 通过 |
| 47.4.23 | 187/187 通过 | 通过 |

三次运行使用在 **47.0.0 上编译的生产代码**。切换运行版本时显式跳过生产代码、GameTest 和客户端探针的 Java 编译；运行后逐文件核对 SHA-256，114 个生产 class 文件均未改变。
这些运行通过 ForgeGradle 的开发启动器加载 class 输出，发布 JAR 另外通过 `build`/`reobfJar` 生成并检查元数据。
没有逐一测试所有中间 Forge 版本，也未加载用户的完整整合包。

## 修改依据

初次直接用 47.0.0 编译时，三处 `ResourceLocation.fromNamespaceAndPath` 无法解析；改用既有的双参数构造方法。
Mod 入口也改回无参构造，并在构造内取得 `FMLJavaModLoadingContext`，避免旧加载器在运行时找不到构造方法。
[Forge 官方更新记录](https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.23/forge-1.20.1-47.4.23-changelog.txt) 将构造参数注入列于 47.3.10，将资源路径便利方法回移列于 47.3.19。
兼容性与重启测试辅助代码中的 `ResourceLocation.parse` 同步改为旧构造方法；此次未重新运行含 AE2/Mekanism 的额外兼容测试及重启测试。

## 验证内容与证据

- 187 项既有 GameTest 覆盖存储、序列化、NAS、导线、物流、基站、终端传输、搜索、合成及自动加工等逻辑。
- 真实客户端探针检查 112 个方块状态、9 个物品模型的烘焙纹理，完整与缺件基站面板、施工指南、导线断连、远端 NAS 发现与磁盘选择、实际取出物品，以及 960×720 和 640×480 窗口下的界面与渲染。
- 汇总日志：[forge-results.txt](forge-results.txt)。客户端报告：[47.0.0](47.0.0-client-report.txt)、[47.3.22](47.3.22-client-report.txt)、[47.4.23](47.4.23-client-report.txt)。报告中列出的截图留在本地探针目录，未重复收入仓库。
- 完整命令输出保存在本地忽略目录 `work/forge-compat/`；GameTest 世界为 `run-gametest/`，客户端使用已有的 `work/base-station-client/saves/Base Station Probe` 测试副本。
- 47.3.22 初次下载依赖遇到 TLS 握手中断，重试后成功；没有降低 TLS 要求或修改系统设置。

## 复跑

在项目根目录执行；PowerShell 使用参数数组，避免 `-I` 被解释成 PowerShell 的公共参数缩写。

```powershell
& .\dev.ps1 -GradleArguments @('build', 'runGameTestServer', '--console=plain')

# 需要先准备上述专用测试世界；探针只允许操作这个测试目录。
& .\dev.ps1 -GradleArguments @('runClient', '-I', 'scripts/base-station-client-test.init.gradle', '--console=plain')

# 保留最低版本编译结果，再切换 Forge 运行环境。
foreach ($forge in @('47.3.22', '47.4.23')) {
    & .\dev.ps1 -GradleArguments @(
        'runGameTestServer', 'runClient', '-I', 'scripts/base-station-client-test.init.gradle',
        "-Pforge_version=$forge", '-x', 'compileJava', '-x', 'compileGametestJava',
        '-x', 'compileStationClientTestJava', '--console=plain'
    )
    if ($LASTEXITCODE -ne 0) { throw "Forge $forge verification failed" }
}
```

CI 已加入相同的三个运行版本，始终以 47.0.0 编译后跳过 Java 重编译来运行 GameTest。
本地执行结果如上；GitHub Actions 执行记录见仓库的 [Build and GameTests 工作流](https://github.com/Rys1amNXxx/item-exploer/actions/workflows/verify.yml)。

## 本地构建产物

- 文件：`build/libs/itemexplorer-1.20.1-0.4.1.jar`，423,655 字节。
- SHA-256：`86f7accc0ea1251480875c8c4b5ac93d43d332ec678650b94cda92fdaaaa93f6`。
- JAR 内版本为 `0.4.1`，Forge 范围为 `[47.0.0,48)`，Minecraft 范围为 `[1.20.1]`。
- 已确认 JAR 不包含 GameTest、客户端探针或重启测试类。
