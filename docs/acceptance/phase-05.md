# Phase 05 验收记录 — 2026-11-07

Phase 05 已完成业务实现、隔离回归和现有 Ubuntu 测试实例部署验收；仅测试环境，未接入真实 ERP。设计见 [Phase 05](../phase-05-design.md)。

## 已实现

- ASN 多行草稿、编辑、提交和取消；同公司/供应商下跨订单、工厂；同计划多批送货；草稿不占量，提交才占用在途。
- 精确数量与订单来源/本地版本、ASN 版本、计划额度、同一草稿行累计及并发检查；幂等、审计、内部 outbox 同事务。
- 独立 ERP 收货/冲销账本、不可变来源收件箱、逐 ASN 分配；实际收货只来自 GoodsReceiptProvider，无人工收货写接口。
- 一收多 ASN、无 ASN 收货、部分/全额冲销；原收货状态派生为未冲销/部分冲销/全部冲销，来源事件不覆盖。
- 收货任务分页检查点、去重、来源冲突、未知单、超收和冲销先到隔离；失败续传。超收按原量入账并冻结，不截断权威数据。
- 改版保留已提交 ASN/收货与分配；失效草稿，有历史发货/收货则冻结新增协同；已发货承诺不能整量改期重建计划。
- 公司、工厂、采购组织完整元组与供应商绑定；ASN 头/详情/计数整单授权，撤权后缓存重放拒绝；未绑定供应商不可读取 ASN。
- 真实页面提供草稿编辑/提交/取消、数量账本、ERP 收货/冲销列表和凭证分配明细、同步场景及异常监控。

## 已执行验证

- 22 项 Java 单元测试通过，其中发货数量规则 8 项。
- 76 项真实 PostgreSQL/Testcontainers HTTP 验收通过，失败/错误/跳过均为零，包含 Phase 01–04 回归。
- 61 项 Python 规格/契约检查通过；OpenAPI 语义校验和项目一致性检查通过；TypeScript/Vite 构建通过，仅有既有 Element Plus 大分块提示。
- Maven 125 个解析后的依赖通过 OSV 审计，零发现；npm audit 零漏洞。
- 首轮 HTTP 73 项中仅新重试测试误将既有 202 约定写成 200，修正后重跑通过；随后扩展权限和多工厂用例，最终 76 项通过。

- 隔离 Compose 项目全套 Playwright 29 项通过；现有 HTTPS 实例只运行新增 shipping.spec.ts，6 项通过；无跳过。
- 隔离与现有实例均验证收货分页失败后 API 重启，保留登录会话及原游标；从检查点继续，3 条来源事件均去重，历史收货不变。
- API、数据库分别重启后，会话、账号数量和 30 张业务表完整行内容摘要保留。
- 升级前备份数据库、私有配置、部署文件及旧镜像；保留原数据卷，追加 Flyway V6，V1–V6 全部成功；API/Web 0.5.0-local 和数据库三服务健康；严格 CA/SAN 验证 HTTPS 返回 200。
- 升级与全部验收后，原有 10 个账号及 27 张表的所有原记录保留。sync_job 因 V6 新增 entity_type 列，以移除新增字段后的原字段摘要比较；其他表逐行原内容摘要比较。只追加独立虚构验收数据，不修改原账号/主数据。
- 浏览器发现并修复：初始加载期间按钮点击被忽略（改为禁用忙碌操作）；草稿编辑回传了读取字段导致 400（改为显式请求字段）；旧账号测试在多次验收后超过一页（补翻页查找）。修复后完整回归通过。
- 最终发布白名单敏感扫描零发现。真实连接信息、凭据、测试截图/跟踪和备份不进入源码发布范围。

## 验收复现

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

全套 Playwright 仅在独立 Compose 项目、独立测试数据卷执行。原实例仅运行 `npx playwright test shipping.spec.ts`，使用追加的虚构公司/供应商和专用账号，不运行会修改原管理员资料的全套身份测试。

## 部署证据与重启复现

部署主机 tmp 内保留 phase05-backend-accepted.log、phase05-e2e-accepted.log、phase05-live-e2e.log、phase05-isolated-restart.log、phase05-restart.log、phase05-audit.log、phase05-deploy.log 和 phase05-finish.log；不导出凭据、截图及跟踪。仅停止本轮隔离项目的容器/网络，保留测试数据卷。

在专用测试部署、全部同步任务结束后执行：

```sh
python3 scripts/verify-receipt-resume.py --url "$TEST_URL" --allow-test-restart
python3 scripts/verify-restart.py --url "$TEST_URL" --allow-test-restart
```

前者使用 shipping.spec.ts 在部署主机创建的 private/phase05-test.json 专用虚构账号及连接，创建一次分页失败并重启 API 续传；后者重启 API、数据库并核对 30 张表。隔离实例必须设置 COMPOSE_PROJECT_NAME 与 OPEN_SRM_HTTP_PORT，HTTPS URL 必须匹配私有证书 SAN；不在生产执行。

## 边界

仅 Mock ERP 读取和测试部署；真实 SAP、ERP ASN 发布/受理/取消协议未启用。SUBMITTED 不表示 ERP 已受理，也不表示收货。来源冲突/改版的冻结目前保守保留，未提供自动解除冻结或人工数量修正。收货异常的原始事件和错误码保留，同场景可在先决条件修复后受控重放，未实现通用映射编辑器。

未做生产容量压测、完整灾难恢复、真实 ERP 沙箱验收、人工视觉审查或公开前依赖许可证审查；开源许可证尚未选择，没有 GitHub 上传。
