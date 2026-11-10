# Phase 02 验收记录 — 2026-11-07

本阶段已完成现有 Ubuntu 测试实例升级和验收。沿用原项目、数据库卷及私有配置，追加 V3，保留既有身份实现和数据。不涉及生产部署、真实业务数据或 ERP。

## 已实现

多公司、公司下多工厂、跨公司采购组织；供应商档案/冻结及多公司关系；BUYER/SUPPLIER 角色、完整组织授权元组、单供应商绑定；按权限过滤 SQL 列表/计数/详情/关系、业务审计；所有写入版本检查、事务幂等和审计；真实 Vue 管理页面。

## 验证

- Java 21 / Spring Boot 4.1.2，JUnit 6 项单元测试及 28 项 PostgreSQL Testcontainers 真实 HTTP 验收通过，零失败/跳过。
- 60 项 Python 规格/契约/安全脚本测试通过；前端 TypeScript/Vite 构建通过；标准 OpenAPI 校验通过。
- 隔离 Compose 实例的完整 Playwright 回归 9/9 通过；现有实例 HTTPS 入口的组织/供应商流程 5/5 通过，均零失败/跳过。真实 Chromium 验证公司新增/编辑/刷新，工厂创建，采购组织关联两家公司，供应商共享/冻结，以及界面添加授权、供应商绑定和撤权后读取拒绝。
- 现有实例 API、数据库、Web 三服务 healthy，API/Web 使用 0.2.0-local 镜像；证书校验后的 HTTPS 入口返回 200，V1/V2/V3 全部迁移成功。
- 升级前在部署主机完成 pg_dump 和私有配置备份，保留 Phase 01 镜像。升级后以及新增独立验收账号之后，按原用户 ID 比对 3 个原账号的账号、名称、口令散列、状态和版本摘要，保持一致。未修改原管理员资料/口令。
- API/数据库分别重启后，同一登录会话和账号数据保留；九张组织/供应商/授权及审计范围表逐表完整内容摘要不变，包括已撤销的授权。重启后全部服务 healthy。
- OSV 审计 125 个已解析 Maven 依赖，零已知漏洞；npm 审计零漏洞。源码候选白名单敏感扫描零发现，私有配置、测试报告、截图、日志和备份均排除。公开前的历史扫描、人工复核及许可证审查仍单独执行。
- 真实数据库测试包含跨公司/跨供应商列表、计数、详情和筛选；整批越权回滚；同元组匹配；限定范围不扩大为公司档案；共享供应商主档限制；关系撤销；停用/重新启用；供应商重绑旧会话失效；版本冲突；并发重复提交只有一次效果；撤权后缓存响应拒绝；业务审计隔离。
- 首轮测试发现撤销操作的审计操作名超长，事务回滚未改变授权；改为唯一 grant ID 的短操作名并将用户 ID 纳入幂等摘要。修复后全量复验通过。未通过的中间运行不计验收通过数。
- 浏览器初次运行修正了装饰导航图标的无障碍名称和 Element Plus 选择框定位方式，之后完整回归通过；未绕过真实 API、权限校验或数据库。

## 可复现命令

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

后端测试需要 Docker，使用独立临时 PostgreSQL，不接入原应用数据卷。浏览器全套需要已运行的独立 Compose 栈及私有初始化配置；现有测试实例只运行 organization.spec.ts，详见安装说明。

部署机执行了 `python3 scripts/verify-restart.py --url <test-https-url> --allow-test-restart`。自签证书主机名需匹配证书 SAN，脚本验证私有 CA，不能用未包含在证书中的回环地址替代。
原始证据保存在部署机项目 tmp：phase02-final-verify.log、phase02-audit.log、phase02-e2e-4.log、phase02-live-e2e.log、phase02-restart.log，以及隔离实例的 failsafe-reports。报告与凭据未导出。新增虚构账号及业务记录保留供测试，隔离回归实例停止但数据卷保留。

## 边界

用户授权使用现有 Ubuntu 测试机。备份、真实连接配置、凭据、截图及测试输出保留部署主机，不进入公开源码。未创建/上传 GitHub 仓库、未选择许可证、未访问 SAP。
订单/ERP Mock 同步、确认、交期、ASN、收货等为 Phase 03–08。当前写入按实例行锁串行化，未做生产性能压测、完整恢复演练或生产安全验收。HTTPS 测试证书仍是自签证书，正式部署需受信任证书。
页面由服务器内 Chromium 做功能验收；截图未导出，未在本机执行人工视觉审查。依赖审计不等同于构建插件、镜像系统包和许可证的完整审计。前端 Element Plus 公共包存在体积提示，不阻断构建，后续按实际性能需求优化。
