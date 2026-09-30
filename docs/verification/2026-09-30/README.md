# 0.4.0-dev 搜索验证证据（2026-09-30）

- [构建与 GameTest 摘录](search-build-excerpt.txt)：完整构建成功，84 项必需测试通过，
  包含 21 项新增搜索测试及此前 7 项 AE2 / MEK 真实兼容场景。
- [真实客户端自动验收报告](search-client-report.txt)：中文与注册 ID 查询、
  递归范围、数量守恒、取出后保留选择、混合分页定位、Esc 恢复、大小窗口均通过。
- [640 × 480 全盘搜索](search-minimum-full-drive.png)。
- [640 × 480 目录树搜索及已选条目](search-minimum-recursive-selected.png)。
- [960 × 720 全盘搜索](search-expanded-full-drive.png)。

客户端为原版资源、中文、GUI 缩放 2；最小界面 320 × 234，扩大后 440 × 340。
三张截图已目视检查，名称、数量、来源路径、查询框、范围、分页及背包均在窗口内。
客户端与集成服务端使用独立测试世界；最终库存 173 件、玩家取出 1 件，初始总量 174。
测试客户端正常保存退出；日志在本地 `work/search-client-launch.log`。

探针属于显式启用的 `searchClientTest` source set，不进入默认客户端或发布 JAR。
执行前需在 `work/search-client/saves/Search Probe` 准备一个可丢弃的测试世界；
探针会精确检查路径，然后重建其局部场景。

```powershell
pwsh -NoProfile -File dev.ps1 runClient --init-script scripts/search-client-test.init.gradle --console=plain
```

探针通过实际客户端界面的点击、字符和按键方法驱动，保留真实菜单与网络处理。
未模拟 Windows 输入，未验证实体键盘 Ctrl+F、输入法组合输入或所有 GUI 缩放。
首两次探针修正了“快照已解码但界面尚未绘制”的验收时序，最终运行在每次绘制完成后操作；
原始报告与隔离世界保留在本地 `work/search-client-first/` 和 `work/search-client-second/`。

发布产物为 `itemexplorer-1.20.1-0.4.0-dev.jar`，网络协议 6；持久化格式不变。
JAR 检查没有测试 / 探针、AE2 或 MEK 类，未新增强制模组依赖。
