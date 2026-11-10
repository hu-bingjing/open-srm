# Phase 06 验收记录 — 2026-11-09

2026-11-10 已再次限量复查：元数据正常，但两业务实体列表及 `$count` 均为 0；真实业务对账继续待验。见 [补验复查记录](sap-business-followup-2026-11-10.md)。

SAP 只读适配器及同步管理已实现，Ubuntu 测试实例已升级并通过内部验收。真实 SAP 元数据及业务查询连通，但当前账号/客户端可见的采购订单、物料凭证均为空，真实订单与收货/冲销数量对账尚未完成。不能将本阶段标为全部外部验收通过。

## 实现与边界

- 七端口通过通用 ErpAdapter 按连接路由；独立 SAP OData 适配器仅发送 GET，核心模块不依赖 SAP 包。
- 私有 CA、认证、精确订单白名单及外部到本地完整组织/供应商/工厂映射；未配置范围时业务读取失败关闭。元数据任务不导入业务数据。
- 采购订单头/行/计划行映射、最后变更时间来源修订、重复与冲突隔离、分页检查点及读取中变更检查；不声称 SAP 提供原生版本号或跨系统原子快照。
- 101 收货、102 引用冲销；不可变凭证键、计划行歧义隔离及未分配收货。ASN 不产生实际收货，原收货取消标志不重复产生冲销。
- 同步管理页面、脱敏错误码、有限指数退避和 Retry-After、版本化手动重试。ERP 发布返回 WRITE_DISABLED，部署写开关为 false。

## 实际验证

- Ubuntu 最终 backend-accepted 日志：40 项 Java 单元测试、88 项 PostgreSQL/Testcontainers HTTP 验收，失败、错误及跳过均为零，BUILD SUCCESS。
- 隔离浏览器首轮 29 项通过、SAP 首项失败、另 1 项未运行；修正测试辅助函数对 204 空响应的处理后，SAP 两项独立复验通过。记录为 29 + 2 分次通过，未宣称单次全套 31 项通过。
- 主测试实例通过 HTTPS 定向执行 sap.spec.ts、shipping.spec.ts，8 项通过，无跳过。最初验收脚本误用非部署端口导致连接失败，改为核实后的 HTTPS 端口后通过；原失败日志保留。
- 隔离实例 API/数据库重启持久化已通过。主实例收货分页失败后 API 重启、会话保留、原游标续传、重复事件跳过和历史收货保留通过；随后 API、数据库分别重启，会话、账号数量及 30 张业务表内容摘要不变。
- 升级及上述验收后，32 张表的原记录指纹全部保留，原有 13 个账号保留；sync_job 比较时仅排除 V7 新增 next_attempt_at 字段。
- V1–V7 迁移全部成功，API/Web 为 0.6.0-local，数据库及三服务健康。严格 CA/SAN 校验的 HTTPS 返回 200。升级前数据库备份存在，旧 0.5.0 镜像保留，未清理任何数据卷。
- 本轮本地：61 项 Python 规格/契约检查、项目一致性、OpenAPI 标准校验通过；TypeScript/Vite 构建通过，仅有既有大分块提示；发布候选白名单敏感扫描零发现。
- 本地 Maven 尝试受 Windows 沙箱回环网络权限限制，SAP TLS fixture 未能启动，该次运行未通过且已停止；上述 Java 通过结论来自实际 Ubuntu 完整日志，不将本地失败计为通过。本轮未重新执行依赖联网审计。
- 本地与主实例 99 个实现/测试/部署/脚本文件的规范化内容摘要一致。隔离实例仅 OpenAPI 生成器比主实例少了枚举去重修正，后端及前端实现/测试相同；本地最终 OpenAPI 已通过标准校验。

## SAP 真实环境读取

在此前获授权的 SAP 测试环境执行，只读 HTTPS，使用私有 CA 校验；未连接生产 ERP，未修改 SAP 配置或权限。

- 两项服务元数据通过真实 Java 适配器后台任务及页面验证。
- 经用户进一步授权自行寻找业务样本，采购订单列表限制最多 20 条，仅选取业务键/组织范围字段；响应成功但 results 为 0，无下一页。
- 物料凭证列表限制最多 20 条，仅选取凭证键、订单引用、工厂及移动类型；响应成功但 results 为 0，无下一页。
- 未找到可批准导入的样本，因此私有业务白名单、映射继续为空，没有真实业务导入，没有发送 ERP 写请求。

空结果只说明当前账号、客户端和接口可见范围内未返回数据，不能据此断言 SAP 全局无业务数据，也不能确认业务授权已经完备。真实订单分页、版本变化、收货/冲销对账及故障恢复仍需可见的授权样本；这些场景目前通过独立合同环境验证，不能替代真实业务验收。

## 复现与证据

通用命令沿用 README。后端使用 `sh scripts/test-backend.sh`，全套浏览器仅在独立 Compose 项目/测试卷执行。主测试实例仅运行：

```sh
cd frontend
npx playwright test sap.spec.ts shipping.spec.ts
cd ..
python3 scripts/verify-receipt-resume.py --url "$TEST_URL" --allow-test-restart
python3 scripts/verify-restart.py --url "$TEST_URL" --allow-test-restart
python3 scripts/verify-upgrade-preservation.py
```

部署主机 tmp 保留 phase06-backend-accepted.log、phase06-isolated-browser.log、phase06-sap-browser-probe.log、phase06-isolated-restart.log、phase06-main-upgrade.log、phase06-live-browser.log、phase06-live-restart.log 和 phase06-live-acceptance.log。私有业务探查响应、配置、账号、备份及浏览器跟踪不进入发布文件。

生产部署、生产 ERP、ERP 写入、完整备份恢复、容量压测和公开前许可证/完整安全审查均不在本轮通过范围。下一阶段可继续 Phase 07，SAP 外部业务验收单独保留待办。
