# 基站基础结构使用说明

实际游戏画面与测试记录见 [2026-09-30 基站验收](verification/2026-09-30/base-station/README.md)。

当前开发版已加入基站部件、实际方块模型、合成配方、结构检查与搭建指南。
**本阶段还没有数据导线、终端接入或远程物品收发。** 控制器绿色灯只表示结构完整，
界面中的网络状态仍为“未接入”；不需要能量，也没有供电端口。

## 获取部件

在创造模式“物品资源管理器”分页中找到以下七种部件，也可用原版材料合成。

| 部件 | 一座基站需要 | 注册 ID |
| --- | ---: | --- |
| 基站机壳 | 5 | `itemexplorer:base_station_casing` |
| 基站控制器 | 1 | `itemexplorer:base_station_controller` |
| 基站网络接口 | 1 | `itemexplorer:base_station_network_port` |
| 空白扩展模块 | 2 | `itemexplorer:base_station_module` |
| 信号立柱 | 3 | `itemexplorer:base_station_mast` |
| 天线板 | 4 | `itemexplorer:base_station_antenna` |
| 天线顶帽 | 1 | `itemexplorer:base_station_cap` |

例如 `/give @s itemexplorer:base_station_controller`。
机壳每次合成 6 个，立柱每次 3 个，天线每次 4 个；其余每次 1 个。
控制器、网络接口和空白模块的配方会消耗机壳，制作材料时需另外准备这四个机壳。
配方可从原版配方书查看；拿到铁锭、机壳或铁栏杆后会解锁相应配方。

## 搭建

完整结构占地 **3 × 3 格，高 5 格，共 17 个方块**。底座正面放控制器，
控制器放下时朝向玩家，整座基站可朝任意水平方向。不要求特定地基或露天环境。

下面是底座俯视图，上方为背面、下方为正面。立柱必须放在中央机壳正上方。

```text
机壳    网络接口    机壳
模块    中央机壳    模块
机壳     控制器     机壳
```

1. 铺好完整九格底座。网络接口面朝背面，两个空白模块的面板分别朝左右外侧。
2. 在中央机壳上竖直放三段信号立柱。
3. 在最上段立柱四个水平邻格各放一块天线板，板面朝外、安装臂朝中心。
   对着立柱水平侧面放置天线板会自动取得相应朝向。
4. 在最上段立柱上方放顶帽。
5. 右键控制器检查；正确部件达到 **17 / 17** 后，面板绿灯点亮。

网络接口、模块、天线等部件可用 **空手潜行右键** 每次水平旋转 90°。
控制器右键始终打开检查窗口；需要改变基站正面时拆下控制器重新放置，并调整其他部件。

每个部件放下时已经显示自身模型。立柱只有柱芯和连接圈，天线为薄板与短臂；
它们虽然看起来较细，仍各自占一格，不能与其他方块共用位置。
正确朝向的天线邻接立柱时，立柱相应一侧会显示连接臂，拆除或转错天线后连接臂消失。

右键控制器后的“搭建指南”可查看五层格位及符号说明。
仓库中的 [逐层搭建图](concepts/base-station-v1/structure.png) 也可对照使用。
最初的概念效果图存在底座分格误差，以逐层图和实际游戏部件为准。

## 检查与拆除

检查窗口显示匹配部件数，并逐项列出问题部件的世界坐标：

- **缺少部件**：所需位置为空。
- **方块类型不符**：该位置放了其他方块。
- **朝向不符**：控制器、接口、模块或天线没有朝预期方向。
- **所在区域未加载**：检查所需的区块当前不可用。

部件拆装与空手旋转会刷新成型状态，另有每 20 tick（正常运行约一秒）的兜底复核。
缺件位置上的普通方块发生变化时，最迟在周期复核或下次打开窗口时更新诊断。
成型状态是现场计算结果，不会仅凭保存的旧灯色认定结构有效。
基站检查不会强制加载区块。

拆除部件按原版正常掉落规则返回相应方块。基站本身没有库存，拆除不会搬运或复制物品。
当前只验证 17 个必需格位，不把其他空格或天空可见度当作成型条件；
装饰方块可自由布置，但可能遮挡天线、控制器或操作位置。

## 当前边界

- 空白模块没有升级效果；跨维度、连接数量和吞吐量模块尚未实现。
- 基站网络接口暂不连接导线或第三方管道，不暴露物品库存。
- 原有“文件夹物流接口”继续负责第三方物流，与本次新增部件分工不同。
- 不会把两地生产机器网络合并，不会自动打开库存权限，也不会创建云盘。
- 当前检查界面只读，支持多人分别查看；没有命名、配对或网络配置功能。

完整方向见 [物流规划](logistics-plan.md)，外观原稿见 [基站设计记录](concepts/base-station-v1.md)。

## 开发与验证

游戏资源由原生像素与模型脚本生成：

```powershell
node scripts/generate-base-station-assets.mjs
node scripts/check-base-station-models.mjs
powershell -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 build runGameTestServer --console=plain
```

专用 GameTest 覆盖四种朝向、17 个部件逐一拆装、错误朝向和错误部件、未加载位置不读取、
重载复核、立柱连接、细模型碰撞/面剔除与掉落。它们随其他存储测试运行，不包含在发布 JAR 中。

可选客户端探针需要先在 `work/base-station-client/saves/Base Station Probe` 准备独立测试存档，
并让该运行目录的 `options.txt` 使用 `zh_cn`；不会自动创建或选择普通游玩存档。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 runClient --init-script scripts/base-station-client-test.init.gradle --console=plain
```

此命令运行隐藏的验收客户端，校验真实模型烘焙、贴图引用与检查窗口同步，
截图并写入 `work/base-station-client/report.txt` 后自动退出；不能用它代替正常游玩的 `runClient`。
它只在严格校验测试世界路径后修改场景，测试代码不会打进发布 JAR。

正式模型的离线预览可用 `python scripts/preview-base-station-models.py` 生成，
需要 Pillow 与 NumPy；它直接读取游戏 JSON 和贴图，但不模拟 Minecraft 环境光照。
