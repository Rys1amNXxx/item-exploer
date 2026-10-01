# 终端程序文件与原版机器验证

日期：2026-10-01。Minecraft 1.20.1 / Forge 47.4.23 / JDK 17。

本次验证对应终端目录程序文件、通用机器接口及熔炉、高炉、烟熏炉、酿造台适配。
此前接口内配置原型的记录保留在 [历史验证](../production-program/README.md)。

## 构建与服务端

执行 `.\dev.ps1 build runGameTestServer`，最终 **148 / 148 项必需测试通过**，构建成功。
其中生产相关测试共 42 项：`ProductionProgramTests` 19、`TerminalProgramTests` 8、
`BrewingProgramTests` 9、`TerminalProgramSafetyTests` 3、`ProgramFileCopyTests` 3。

覆盖真实机器加工、燃料和容器、单瓶酿造与药水数据、目录文件持久化、运行次数独立于保存定义、
断线取消、改绑后旧接口返回、同文件重新启动后旧运行恢复、重放请求、复制名称和目录删除保护。
旧接口原型存档的升级测试验证停止自动控制并保留机器内真实物品。

简要输出见 [构建和测试记录](build-gametest-summary.txt)。
开发包为 `build/libs/itemexplorer-1.20.1-0.4.0-dev.jar`，网络协议 **10**；客户端与服务端须一起更新。
检查 JAR 包含程序库和机器适配器，不包含 GameTest 或客户端验收场景类。

SHA-256：`12D579A5A63601BE537643562D165E641B49F0D622A34019DA1338E70DD3E11E`。

## 真实中文客户端

执行：

```powershell
.\dev.ps1 -GradleArguments @('-I', 'scripts/production-client-test.init.gradle', 'runClient')
```

场景只修改经过真实路径校验的 `work/production-client/saves/Production Probe` 独立测试存档。
复跑需事先准备该目录中的 `level.dat`，并在独立的 `options.txt` 设置 `lang:zh_cn`、`guiScale:2`。
此运行临时加入 GameTest 源集，验收场景不进入发布 JAR。

[客户端报告](client-report.txt)结果为 **PASS**，完整执行了：

1. 进入终端子目录，通过“新建 → 自动程序”创建文件，左键打开。
2. 选择导线连接的机器、原料、燃料和产物目录，保存。
3. 不再次保存，将本次次数从 1 改为 2，启动并等待两次原版烧制完成。
4. 校验 3 个铁矿石和 1 个煤炭变为剩余 1 个铁矿石、2 个入库铁锭，程序定义仍保持单次模板。
5. Esc 返回原文件夹和页码，执行改名、复制、移动、删除副本，确认副本未运行且物品守恒。

玩家距机器接口超过 8 格，位于终端旁，验证编辑器以终端距离授权。
窗口 640×480 / 854×480（GUI 320×240 / 427×240）的控件位置和间距经过检查，四张实际截图已目视确认：

- [终端文件夹中的程序](production-terminal-program-file.png)
- [最小窗口：已保存配置](production-minimum-configured.png)
- [最小窗口：运行两次](production-minimum-running.png)
- [宽窗口：完成两次加工](production-854-completed.png)

## 验证范围

真实客户端流程使用熔炉；另外三类设备由 GameTest 验证。序列化测试使用同一测试环境中的 NBT 保存和载入，
没有宣称独立进程重启、真实多人网络或崩溃期间的跨设备原子恢复已通过验收。
当前范围和操作方法见 [自动程序使用说明](../../../production-program-usage.md)。
