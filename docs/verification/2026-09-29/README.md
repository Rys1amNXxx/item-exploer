# 0.3.0-dev 验收证据

对应 [补充验收报告](../../acceptance-2026-09-29.md)，测试对象为 `266fc77` 的生产代码。
以下文件从当日隔离测试结果中归档，便于在仓库内查看；测试世界、运行缓存和完整启动日志保留在本地 `work/`，不随代码提交。

| 归档 | 来源 / 说明 |
| --- | --- |
| [NAS 错误提示截图](nas-full-inventory-overlap.png) | `work/acceptance-ui/evidence/nas-full-inventory-overlap.png`，原图复制；普通提示约 4.5 秒后消失 |
| [重启与强杀执行摘要](restart-summary.txt) | `work/acceptance-restart/20260929-200705/verification-summary.txt`，仅将本机项目绝对路径改成相对路径 |
| [正常重启断言](restart-normal.txt) | 上述运行的 `normal/restart-probe-verify.txt`，42 条 PASS |
| [保存后强杀恢复断言](restart-flushed-crash.txt) | 上述运行的 `flushed-crash/restart-probe-verify.txt`，42 条 PASS |
| [真实能量重启断言](restart-energy.txt) | `work/acceptance-restart/20260929-201401/normal/restart-probe-verify.txt`，47 条 PASS |
| [双客户端服务端记录](multiplayer-server.txt) | `work/acceptance-multiplayer/20260929-201517/server-probe.log`，原文复制，改用 `.txt` 扩展名 |
| [双客户端执行摘要](multiplayer-summary.txt) | 上述运行的 `verification-summary.txt`，三个进程退出码均为 0 |
| [构建和 GameTest 摘录](build-gametest-excerpt.txt) | 从 `work/acceptance-final-regression.log` 提取完成行：63 项通过、构建成功；不是完整日志 |

限制：保存后强杀不覆盖保存中断或未保存的变更；真实能量场景验证正常重启；双客户端使用本机真实 TCP，没有注入延迟。详细范围及待验收项以补充验收报告为准。
