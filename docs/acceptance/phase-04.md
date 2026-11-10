# Phase 04 验收记录 — 2026-11-07

Phase 04 已完成实现及 Ubuntu 测试实例部署验收。设计见 [Phase 04](../phase-04-design.md)。仅测试环境，不连接真实 ERP。

## 已实现

供应商按计划行部分确认；按期承诺直接生效；延期确认预占数量并生成审批申请；采购批准/拒绝；本人撤回；已生效承诺改期；等量交货计划与旧计划保留；新 ERP 版本同事务失效旧确认/待批申请/计划；审计、内部 outbox、版本与幂等控制。

## 已验证

- Java 单元测试 14 项、真实 PostgreSQL/Testcontainers HTTP 验收 60 项全部通过，零失败、错误或跳过，包含 Phase 01–03 回归。
- 验证 60+40、待批预占/释放、超量与精度、并发、误配置自批防御、重复审批、审批与改期、冻结、元组/供应商权限、撤权后重放、版本/取消联动，以及审计/outbox/幂等原子性。
- 本地 TypeScript/Vite 构建与 61 项 Python 规格/契约/安全检查通过，OpenAPI 语义校验和项目一致性检查通过。前端构建有 Element Plus 大分块提示，当前不影响验收。
- 独立 Compose 项目、独立数据卷的全套 Playwright 23 项通过；现有实例仅运行新增 workflow.spec.ts，HTTPS 8 项通过，均无跳过。首轮旧采购测试因新增余额列出现两个相同数值而触发严格选择器错误；修正数量断言后全套重跑通过。
- 隔离及现有实例均验证待审批申请和登录会话跨 API 重启保留；随后审批并按相同幂等键重放，只有一条不可变审批记录和一份等量交货计划。
- 现有实例 API、数据库分别重启后，会话与用户数量保留，25 张业务表完整行内容摘要一致。
- 升级追加 Flyway V5，V1–V5 全部成功；运行镜像 0.4.0-local，数据库/API/Web 三服务健康；使用私有证书 CA 严格验证 HTTPS 返回 200。
- 125 个解析后的 Maven 依赖通过 OSV 审计，无发现；npm audit 无漏洞；发布白名单敏感扫描零发现。审计是本次时间点结果，不替代公开前人工审查。
- 升级前已在部署主机备份 Phase 03 数据库、私有配置及旧镜像，保存原账号、角色、主数据、订单及同步表的逐行摘要。升级及全部验收后再次比对，原有 7 个账号及 22 张表的每条原记录均保留且未修改；仅追加本轮独立虚构测试数据。备份、凭据及实际连接信息不进入公开源码。

## 复现命令

```sh
sh scripts/test-backend.sh
python3 -m unittest discover -s tests -v
python3 scripts/check_project.py
python3 -m openapi_spec_validator docs/api/openapi.json
python3 scripts/scan_sensitive.py
cd frontend
npm ci --ignore-scripts
npm run build
npm audit --audit-level=high
npm run test:e2e
```

浏览器全套仅在隔离数据卷执行；现有实例只执行 `npx playwright test workflow.spec.ts`，追加独立虚构公司、供应商和专用账号，不修改原账号/主数据。凭据、截图、跟踪和运行日志留在服务器 private/tmp，不导出或上传。

## 部署及证据

先完成隔离回归和重启验收，再在保留原数据卷和私有配置的条件下以本地镜像升级；未初始化原实例、未执行删除数据或清卷命令。仅停止本次隔离项目的容器和网络，保留数据卷。
部署主机 tmp 中保留 phase04-backend-complete.log、phase04-e2e-complete.log、phase04-live-e2e.log、phase04-isolated-restart.log、phase04-restart.log、phase04-audit.log 和 phase04-deploy.log；截图与跟踪留在主机，不导出。

重启验收（仅专用测试部署，URL 使用匹配证书 SAN 的测试地址）：

```sh
python3 scripts/verify-collaboration-restart.py --url "$TEST_URL" --allow-test-restart
python3 scripts/verify-restart.py --url "$TEST_URL" --allow-test-restart
```

前者依赖 workflow.spec.ts 在该主机生成的 private/phase04-test.json，应在其待审批状态尚未被处理时运行；隔离实例必须设置对应 COMPOSE_PROJECT_NAME 和 OPEN_SRM_HTTP_PORT，避免操作其他项目。

## 边界

当前没有草稿保存、通用状态修改、人工改确认数量或人工改交货计划接口；确认表单直接提交，计划由有效确认派生。提前/延期修改已生效承诺均需采购审批。
outbox 仅持久记录内部事件，尚未分发通知或发布到 ERP。ASN、分批送货、实际收货/冲销与已发货保护属于 Phase 05。计划不代表实际送货/收货；尚无这些记录，因此不宣称已完成其数量对账。
仅测试部署，自签证书、实例行锁串行写入，未执行生产性能/灾难恢复/全量依赖许可证审查。GitHub 未上传，许可证未选择。
