# OpenSRM

面向制造业、可独立部署的供应商协同平台，源码仓库为 [hu-bingjing/open-srm](https://github.com/hu-bingjing/open-srm)。
采用模块化单体、前后端分离；单实例支持集团内多公司、多工厂、多采购组织。

**Phase 08 内部测试部署验收通过，Ubuntu 实例已升级至 0.8.0；SAP 真实业务数量对账继续待补验。**
已有 Vue/Element Plus 前端、Spring Boot 后端、PostgreSQL/Flyway、数据库会话、首次管理员初始化、登录/注销、用户角色/状态、个人资料、身份审计及幂等基础。
包含 Docker Compose、非 root Nginx、HTTPS 测试配置及自动化测试。实际验收结果见 [Phase 01](docs/acceptance/phase-01.md)。
新增公司、工厂、跨公司采购组织、供应商及公司关系、组织授权和供应商账号绑定。组织及供应商访问在数据库查询、计数、详情和写入中执行范围过滤；共享供应商修改要求覆盖全部关联公司的管理权限。实际部署和验收见 [Phase 02](docs/acceptance/phase-02.md)。
新增采购订单头/行/计划行、不可变来源版本、行级数据权限，以及七个 ERP Java 端口、Mock 读取适配器、来源映射、分页同步检查点、去重、隔离与失败重试。订单及同步监控页面连接真实后端和 PostgreSQL；验收见 [Phase 03](docs/acceptance/phase-03.md)。
新增计划行部分确认、数量预占、延期审批/撤回、有效承诺改期、派生交货计划和 ERP 版本联动失效；业务、审计、内部事件和幂等结果同事务。验收见 [Phase 04](docs/acceptance/phase-04.md)。
新增 ASN 草稿/编辑/提交/取消、分批数量预留、独立 ERP 收货/冲销账本与逐 ASN 分配、异常隔离和分页续传。ASN 不增加实际收货；收货只由 GoodsReceiptProvider 导入，当前使用 Mock。验收见 [Phase 05](docs/acceptance/phase-05.md)。新增 SAP 只读适配器、按连接路由、元数据校验任务及私有范围限制，设计见 [Phase 06](docs/phase-06-design.md)。实际结果见 [Phase 06 验收](docs/acceptance/phase-06.md)。ERP ASN 发布保持关闭，完整状态见 [功能与风险清单](docs/gap-analysis.md)。

新增协同工作台、待确认/待审批/逾期未发完待办、站内通知及投递监控、按公司/工厂/单位分组的数量统计。通知读取/已读与计数实时校验归属和数据权限，投递支持事务回滚、去重、有限退避及按版本人工重试；ERP 写入继续关闭。设计见 [Phase 07](docs/phase-07-design.md)，实际结果见 [验收记录](docs/acceptance/phase-07.md)。

Phase 08 增加自动化安全回归、依赖/运行镜像扫描、许可声明清单、加密配置备份及新卷恢复和只读性能实测。结果及云端 CI/第二主机等外部门槛见 [Phase 08 验收](docs/acceptance/phase-08.md)，操作见 [备份恢复](docs/operations-recovery.md)。ERP 写入仍关闭。

现有测试实例已于 2026-11-10 按授权清除测试业务及 37 个测试账号，保留原始管理员；见 [清理记录](docs/acceptance/test-data-cleanup-2026-11-10.md)。历史验收数据在备份中保留，当前库不再含这些测试单据。

## 测试部署

运行环境：Ubuntu 24.04 x86_64、Docker Engine/Compose、Python 3、OpenSSL；建议 4 核/8 GB/40 GB。容器运行无需主机 Java/Node/PostgreSQL。
Docker 命令由具有 Docker 权限的用户执行；未配置该权限时使用 sudo，本项目不自动修改用户组授权。

```sh
python3 scripts/init-local.py
docker compose --env-file .env -f deploy/compose.yml config --quiet
docker compose --env-file .env -f deploy/compose.yml up -d --build --wait --wait-timeout 300
```

访问 `http://localhost:8080`，默认仅回环开放。首次从私有 `private/bootstrap-token` 取得令牌并自选管理员账号/密码，没有默认账号。
局域网 HTTPS 模式、证书、停机与数据保留见 [安装说明](docs/installation.md)。不执行清卷命令，不接入真实 ERP。

## 应用构建与测试

构建环境：Java 21、Node 24.22.0；首次 Wrapper/npm/容器构建需联网。Maven 由 Wrapper 下载并校验为 3.9.11。直接 Java/前端版本见各构建清单，npm 传递依赖由锁文件固定。

```sh
cd backend
chmod +x mvnw
./mvnw -B -ntp clean verify
# 有 Docker 时在当前 backend 目录构建测试数据库镜像：
docker build -t open-srm-database:0.8.0-local ../deploy/database
# 然后启用 PostgreSQL Testcontainers 验收：
./mvnw -B -ntp -Pintegration verify
cd ../frontend
npm ci --ignore-scripts
npm run build
npm audit --audit-level=high
# 在专用测试应用已运行时：
npx playwright install --with-deps chromium
npm run test:e2e
cd ..
```

Windows 在 backend 目录使用 `mvnw.cmd`。只有 Docker 的 Linux 主机可在根目录运行 `sh scripts/test-backend.sh`。
E2E 会在专用测试实例初始化管理员和创建虚构用户；不得在真实业务实例运行。随机凭据和浏览器跟踪只留忽略的私有/临时目录。

## 本地验证

只需 Python 3.12，Phase 00 检查无第三方运行依赖，可离线执行：

```sh
python scripts/check_project.py
python -m unittest discover -s tests -v
python scripts/scan_sensitive.py
```

OpenAPI 的标准校验另需联网安装开发依赖（输入见 `requirements-dev.txt`，完整精确锁定见 `requirements-dev.lock.txt`）：

```sh
python -m venv .venv
# Windows: .venv\Scripts\python -m pip install -r requirements-dev.lock.txt
# Linux/macOS:
.venv/bin/python -m pip install -r requirements-dev.lock.txt
.venv/bin/python -m openapi_spec_validator docs/api/openapi.json
```

`specs/domain.py` 仍是核对后续采购规则的可执行规格，不是 Java 后端，不应把规格测试当作未实现业务的应用验收。

## 目标技术栈与环境

| 层次 | 决策 | 落地阶段 |
| --- | --- | --- |
| 前端 | Vue 3.5.45、TypeScript 5.9.3、Vite 8.3.6、Element Plus 2.14.7 | Phase 01 已建立 |
| 后端 | Java 21、Spring Boot 4.1.2、Maven Wrapper 3.9.11 | Phase 01 已建立 |
| 存储 | PostgreSQL 17.11、Flyway；暂不引入 Redis | Phase 01 已建立 |
| API | REST、OpenAPI 3.1；标记已实现/规划路径 | 身份接口已实现 |
| 部署 | Docker Compose、Nginx，基础镜像固定摘要 | Phase 08 测试部署已验；生产独立授权验收 |
| 测试 | JUnit、PostgreSQL Testcontainers、Playwright | 结果见阶段验收 |

默认使用 Mock；通过可选私有部署配置启用 SAP 只读适配器，ERP 写入始终关闭。SAP 真实业务数量对账与接口合同测试分别验收。

## 源码与配置

用户已明确授权公开本项目的脱敏源码。源码获取：

```sh
git clone https://github.com/hu-bingjing/open-srm.git
cd open-srm
```

进入项目根目录后按“测试部署”或“应用构建与测试”执行。仓库只包含源码、通用文档、构建脚本及依赖清单，不包含现有服务器账号、私有配置、SAP 证书/凭据、数据库、备份、日志或 PDF 交付产物。当前项目许可证仍未选择，公开可见不等于已授予开源许可。
公开示例只使用 Company A、Company B、Plant A 等虚拟组织。优先使用初始化脚本生成私有配置，`.env.example` 只说明非秘密变量。
私有部署参数、真实导入数据和密钥存储在被忽略的 `private/`、`data/` 或外部秘密存储中，不进入版本控制。

## 文档入口

- [OpenSRM 操作手册](docs/user-manual.md)：管理员、采购员、供应商和审计人员的页面操作与常见问题。
- [项目结项状态](docs/project-status.md)、[SAP 只读接入与退出参考](docs/sap-readonly-operations.md)。实际环境的 SAP 操作/清理手册另行私有交付。

- [文档索引](docs/README.md)、[PRD](docs/prd.md)、[架构](docs/architecture.md)
- [权限矩阵](docs/permissions.md)、[ERD](docs/data-model.md)、[状态与一致性](docs/state-machines.md)
- [API 使用约定](docs/api/README.md)、[OpenAPI](docs/api/openapi.json)、[ERP 适配器](docs/erp-integration.md)
- [安装与运行规划](docs/installation.md)、[开发与验收计划](docs/plan.md)
- [安全及公开前检查](docs/security-release.md)、[Phase 00 验收](docs/acceptance/phase-00.md)、[Phase 01 验收](docs/acceptance/phase-01.md)

## 许可证

**尚未选择许可证，当前不授予开源许可。** 不添加默认 MIT/Apache 等许可证；正式公开之前单独确认许可证、贡献归属和依赖许可。
安全问题报告流程见 [SECURITY.md](SECURITY.md)，贡献流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。
