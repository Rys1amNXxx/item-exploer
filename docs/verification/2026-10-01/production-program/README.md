# 熔炉生产程序首版验证

此目录保留接口内配置原型的历史验证。后续实现已迁移为终端目录程序文件，并扩展原版设备支持；当前验证见 [终端程序验证](../terminal-programs/README.md)。

日期：2026-10-01。Minecraft 1.20.1 / Forge 47.4.23 / JDK 17。

## 服务端与构建

执行 `.\dev.ps1 build runGameTestServer`，最终结果 **125 / 125 项通过**，其中新增生产程序测试 19 项。
构建输出 `build/libs/itemexplorer-1.20.1-0.4.0-dev.jar`，网络协议为 9。
简要记录见 [build-gametest-summary.txt](build-gametest-summary.txt)。

新增覆盖：真实世界 tick 完成烧制；重复启动；煤炭余火；熔岩桶返桶；湿海绵生成水桶；
缺原料和燃料时不跨目录取料；满盘保留炉内成品；断线时停止转移并在恢复后续跑；
取消不退款；同位置替换设备不会接管；正常序列化恢复；目录删除；未知版本保护；
炉内异常锁定；一炉多接口竞争；错误配置拒绝；菜单重放、距离和关闭校验。

正常恢复测试是同一测试世界中保存/加载 NBT，未宣称独立进程重启或强杀恢复已验收。

## 真实客户端

执行：

```powershell
.\dev.ps1 -GradleArguments @('-I', 'scripts/production-client-test.init.gradle', 'runClient')
```

测试在 `work/production-client/saves/Production Probe` 的独立存档副本中运行，并校验真实路径后才创建场景。
复跑前需准备该目录中的 `level.dat`，在 `work/production-client/options.txt` 设置 `lang:zh_cn`、`guiScale:2`。
脚本只为此客户端运行加入 GameTest 源集；验收代码不进入发布 JAR。

[客户端报告](client-report.txt)记录保存、启动、一次真实铁矿石烧制、样本耗尽后的余火重启、
取消保留在制原料以及无背包转移/退款，结果 **PASS**。
初次探针失败来自同一渲染帧内过早读取按钮状态，已改为等待下一次实际渲染，再完成全部检查。

640×480 和 854×480 窗口（GUI 320×240 / 427×240）均检查十个控件的位置及不重叠，
四张实际截图均已目视检查：

- [最小窗口：保存配置](production-minimum-configured.png)
- [最小窗口：运行中](production-minimum-running.png)
- [宽窗口：完成](production-854-completed.png)
- [宽窗口：取消后保留炉内物品](production-854-cancelled.png)

本次没有验证 NAS 程序、并发管道对同一熔炉的访问隔离或异常中断原子恢复；
这些不属于已实现保证。实际范围见 [使用说明](../../../production-program-usage.md)。
