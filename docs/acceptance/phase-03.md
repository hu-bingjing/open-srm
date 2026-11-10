# Phase 03 验收记录 — 2026-11-07

已完成现有 Ubuntu 测试实例升级和验收。设计见 [Phase 03](../phase-03-design.md)。本阶段不涉及生产部署或真实 ERP。

## 范围

采购订单头、行项目、计划行及不可变来源版本；列表、行、历史版本和计数的组织/供应商权限；独立 ERP 端口及 Mock；明确主数据映射；持久化同步任务、分页检查点、收件去重、隔离事件、重试上限；真实订单和同步监控页面。

新版本不删除旧快照；同版本异内容冻结一致性；旧版本隔离；取消后不自动重开；关闭可由较新来源版本重开。数量使用精确十进制。没有人工创建订单或实际收货接口，没有真实 ERP 调用。

## 验收结果

- 最终 JUnit 9 项单元测试、PostgreSQL Testcontainers 43 项真实 HTTP 验收通过，零失败/错误/跳过，包含原 Phase 01/02 回归。覆盖多行多计划、新旧版本、重复/乱序/冲突、取消/关闭/重开、移除行保留历史、精确数量、连接负责人隔离、同元组筛选、供应商绑定、列表计数/历史过滤、撤权、同连接活动任务互斥、幂等及五次重试上限。
- 前端 TypeScript/Vite 与 Docker 干净构建通过；61 项 Python 规格/契约/安全和架构边界测试通过，标准 OpenAPI 校验通过。架构检查确认核心没有适配器包依赖。
- 隔离 Compose 完整 Playwright 回归 15/15 通过（约 1.2 分钟）；现有实例 HTTPS 的新增采购流程 6/6 通过。实际页面创建来源映射、运行同步、查看行/计划行/历史、查看冲突、重试失败页并验证供应商仅见授权工厂行。
- 隔离与现有实例均通过检查点恢复：制造第二页一次读取失败，已提交两条事件及游标；重启 API 后快照、游标和登录会话保持，再重试完成剩余页。四张订单及其全部版本/计划数量与重试前一致，未重复入库。
- 现有实例分别重启 API 和数据库后，同一会话/账号数据和二十张组织、供应商、订单、同步表的完整内容摘要保持一致，包括已撤销授权。最终三服务 healthy，API/Web 镜像为 0.3.0-local；V1–V4 迁移均成功，验证私有证书后的 HTTPS 返回 200。
- 升级前在测试机保留 Phase 02 数据库/私有配置备份、旧镜像，以及原账号、角色和主数据行摘要。升级后及所有验收完成后逐行摘要集合比对：5 个原账号、原角色关系及原组织/供应商/授权数据全部保留，未修改原资料/口令或删除原数据。只追加独立虚构验收记录。
- OSV 审计 125 个已解析 Maven 依赖，零已知漏洞；npm 审计零漏洞；源码白名单敏感扫描零发现。凭据、主机地址、备份及运行输出排除在源码之外。

本阶段首次数据库集成即通过；测试数据库关闭时观察到后台定时器仍访问已停止的临时容器，已在测试 AfterAll 中先停止调度器，最终全量复验通过。此为测试资源清理修正，未跳过测试或改变业务结果。

## 复现

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

全套浏览器回归只在隔离测试栈运行；现有测试实例使用 `npx playwright test purchasing.spec.ts` 创建独立虚构数据与专用账号。测试凭据留在服务器 private，报告与截图留在 tmp，不导出或上传。

部署机还执行：

```sh
python3 scripts/verify-sync-resume.py --url <test-https-url> --allow-test-restart
python3 scripts/verify-restart.py --url <test-https-url> --allow-test-restart
```

原始证据在部署机项目 tmp：phase03-backend-final.log、phase03-build.log、phase03-audit.log、phase03-e2e.log、phase03-live-e2e.log、phase03-restart.log，以及隔离源码目录内的 failsafe-reports。私有验收账号用于本机续传验证，未导出。隔离回归实例停止但保留数据卷。

## 限制

确认、延期审批、交货计划、ASN、ERP 收货及 SAP 只读连接属于后续阶段。当前版本变更尚无这些模块可联动，不宣称已验证其跨模块失效和数量保护；Phase 04/05 必须接入同事务守卫。
Mock 快照在本地内存确定性生成，导入/检查点/审计在数据库事务中持久化；真实 ERP 网络读取、自动退避、对账和连接授权扩展在 Phase 06 验收。当前只有连接负责人加公司级范围可操作连接，尚无负责人转移功能。
仅测试部署，使用自签证书。未执行生产性能/完整恢复/镜像系统包和许可证全量审计，未创建 GitHub 远端或选择项目许可证。
当前同步为单执行器、一页一个实例写锁事务；无大规模吞吐承诺。恢复证明覆盖失败检查点加服务重启，未模拟数据库损坏或进程在提交中被强杀。浏览器做功能验收，截图未导出，未在本机做人工视觉审查；Element Plus 公共包仍有体积提示，不阻断构建。
