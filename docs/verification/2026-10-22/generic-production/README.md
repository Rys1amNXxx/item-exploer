# 通用物品加工一期验证

日期：2026-10-22。源码/产物版本：0.5.1-dev；网络协议：19。

## 实现范围

- 原版熔炉、高炉、烟熏炉和酿造台继续使用既有适配与程序格式。
- 非原版设备通过按面 Forge `IItemHandler` 接入；一个物品接口最多 256 槽，不缓存失效能力。
- 每个加工样板支持 1–9 行输入、1–9 行产物，每行有目录、物品及附加数据、数量和可选机器面覆盖。
- 按批次保存已预留/已投入/已抽取/已交付数量；完整材料及共享槽容量预检，实际插入以返回余量结算。
- 允许原料进入机器内部后等待产物，所有预期产物交付后才完成一批。
- 取消归还接口持有的材料，断线/满盘保留缓冲，禁止覆盖待归还缓冲；拆除掉落实物，已投机器材料不退款。
- 客户端只发送库存/已保存样本引用，服务端解析样本并校验目录、数量、面、会话、上下文和修订。

## 构建与服务端

最低支持版本 Forge 47.0.0 / Java 17：

```powershell
.\dev.ps1 build runGameTestServer --console=plain
```

构建成功，**275 项必需 GameTest 全部通过**。完整本地日志：`work/generic-final-build-tests.log`。

新增 `GenericProductionTests` 23 项，覆盖整批缺料、多输入多输出、逐行不同面、联合槽容量/库存/输出空间、机器吞料后等待、部分接收、取消、断联、满盘、保存恢复、机器替换、能力失效、安装规则、NBT 样本与已交付空缓冲恢复。

新增 `GenericProductionMenuTests` 5 项，覆盖网络编解码与长度/方向/数量边界、服务器样本解析、独立产物目录、耗尽样本再次保存、非法引用及旧会话/上下文/修订/运行中编辑拒绝。

真实模组验证使用 Forge 47.4.23（测试依赖 Mekanism 的最低要求高于本模组）：

```powershell
.\dev.ps1 runGameTestServer '-Pforge_version=47.4.23' --init-script scripts/compat-test.init.gradle --console=plain
```

**284 项必需 GameTest 全部通过**，包含 AE2 15.4.11、Mekanism 10.4.16.80 和 GuideME 20.1.15。完整本地日志：`work/generic-compat-tests.log`。新增真实加工用例：

- Mekanism 电力熔炉：真实配方与服务端 ticker，连续两批沙子加工为两个玻璃。
- Mekanism 组合机：主料和辅料从不同面进入，连续两批蓝色/红色染料加工为八个紫色染料。

测试场景给真实机器补充电力，本模组生产代码不提供供能或 Mekanism 专用调用。已有七项真实 AE2/MEK 物流测试仍通过。最终安装包随后重新按 Forge 47.0.0 构建，不包含测试或第三方模组类。

## 实际中文客户端

```powershell
.\dev.ps1 runClient --init-script scripts/generic-production-client-test.init.gradle --console=plain
.\dev.ps1 runClient --init-script scripts/production-client-test.init.gradle --console=plain
```

两项均成功，使用各自独立的测试世界。

通用编辑器在 320×240 逻辑分辨率验证了两行输入、两行产物、独立目录、数量、每行方向、保存网络往返、未保存禁启动、退出重开、运行次数不需重存、启动/取消与实际物料守恒。测试容器为桶，用于验证真实 UI/请求/搬运流程，不表示桶具备加工能力；真实加工由上方 Mekanism 测试覆盖。

原版流程验证了实际两次烧制、程序创建/保存/运行、返回目录、复制/移动/删除，以及 320×240 和 427×240 布局。

- [通用客户端报告](client-report.txt)
- [原版客户端报告](vanilla-client-report.txt)
- [输入编辑截图](generic-minimum-inputs.png)
- [产物编辑截图](generic-minimum-outputs.png)
- [重开已保存程序](generic-minimum-reopened.png)
- [运行状态截图](generic-minimum-running.png)

已目视检查输入/产物截图，控件与文字在最小窗口内可见且无重叠。

## 安装包与边界

产物：`build/libs/itemexplorer-1.20.1-0.5.1-dev.jar`。

SHA-256：`1AF36B58D29592A474EE139B1280BB1BE1DB9CCF16650B1702EAC6AF9B9470E4`。

一期只处理确定数量的物品配方，首次配置需要物品样本。机器须专用且无旧加工任务，涉及的库存面在批次开始时为空。流体、气体、概率产物、同种催化剂返回、特殊 UI 按钮、外部库存挂载和配方自动规划未实现。

标准物品接口不能识别所有外部干预、隐藏的旧任务或不同面包装器的底层槽别名；实际接受数量与缓冲账本用于处理部分接收。正常序列化/重载通过验证；本次不承诺进程崩溃或跨设备保存中断时的原子恢复，也未将结果扩展为所有模组/版本均兼容。
