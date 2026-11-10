# Phase 00 验收记录 — 2026-11-06

## 范围

本地新建 OpenSRM 的设计基线及可执行规格，不是完整 SRM 业务应用验收。
工作区未发现带 SRM 标识的已有项目；没有改写其他项目、删除原数据、连接真实 ERP、部署生产、上传 GitHub 或选择开源许可证。

## 命令与结果

结论：**Phase 00 设计基线验收通过。** 只覆盖下表的本地检查，不代表 Phase 01–08 或正式公开验收通过。

| 项目 | 命令 | 状态 |
| --- | --- | --- |
| 文档/链接/契约/端口一致性 | `python scripts/check_project.py` | PASS，0 个问题 |
| 业务规则/权限/契约/扫描回归 | `python -m unittest discover -s tests -v` | PASS，57/57；最终运行 0.382 秒 |
| 发布范围启发式敏感扫描 | `python scripts/scan_sensitive.py` | PASS，34 个文件，0 命中 |
| 标准 OpenAPI 语义校验 | `.venv/Scripts/python -m openapi_spec_validator docs/api/openapi.json` | PASS；45 个路径、59 个操作、57 个 schema |
| 开发依赖一致性 | `.venv/Scripts/python -m pip check` | PASS，No broken requirements found |
| 开发依赖漏洞检查 | `.venv/Scripts/python -m pip_audit -r requirements-dev.txt --strict --cache-dir tmp/audit-cache --timeout 20 --progress-spinner off` | PASS，No known vulnerabilities found |
| CI / Dependabot YAML 解析 | Python PyYAML 解析工作流及更新配置 | PASS，仅本地解析，未在 GitHub 执行 |

校验环境：Windows，Python 3.12.10；OpenAPI 校验工具 0.7.2、pip-audit 2.9.0 安装在项目 `.venv/`。缓存位于忽略的 `tmp/`，不进入发布范围。
漏洞结果仅表示本次解析的开发工具及传递依赖在检查时未发现已知漏洞，不包含尚未建立的 Java/npm/镜像依赖，也不是许可证审查。

## 覆盖的关键场景

- 供应商隔离、公司隔离、完整授权元组匹配、管理员无业务授权拒绝、授权撤销/用户禁用后幂等重放拒绝。
- 部分确认与合计超量拒绝、待批确认预占确认额度、延期未批不可发货、自批拒绝、重复/过期审批拒绝。
- ASN 不计收货、分批发货与部分收货、无 ASN 收货、超送拒绝、取消释放未收货预留、已收货 ASN 禁止取消。
- ERP 服务身份验证、来源事件去重及同键异内容拒绝、真实超收保留并冻结、取消后收货仍保留且冻结、部分/全额冲销及重复/超额冲销。
- 新版本失效旧确认、缩量低于在途冻结、旧版本提交拒绝、取消和冻结供应商阻止新协同。
- 幂等同内容重放、异内容冲突、主体隔离、失败无业务/审计副作用；十进制精度与格式校验。
- OpenAPI 引用解析、业务写入 CSRF/幂等/If-Match 要求、没有用户创建收货/订单的入口；暂存区敏感内容扫描、防止工作树脱敏掩盖索引内容。

## 本轮发现与修正

首轮临时 Git 仓库受 Windows 沙箱目录所有权限制，导致一个扫描测试失败；现只在该测试的 Git 调用中信任自己新建的临时仓库，不修改全局 Git 配置。
扫描器一处规则名称被自身秘密赋值规则误报，已调整规则标识并复扫。设计复核补充取消后 ERP 收货触发一致性冻结及对应断言。
初次依赖下载受网络限制，后在项目内完成安装和在线漏洞核查；无系统级软件安装。

## 必须保留的限制

- 可执行规格为单计划行内存模型，不具备持久化、并发事务、HTTP 鉴权、多行交货计划或实际 ERP 适配器；不能替代后端验收。
- ERD 尚未变成执行过的 Flyway 迁移；Java/Vue/Docker 工程尚未建立。
- 当前环境有 Java 21、Node.js、Python 3.12、Git，未发现 Maven/Docker 命令；未测试应用构建或 Compose。
- GitHub CI/Dependabot/Secret Scanning/Push Protection 尚无远端实际启用或运行证据；专用完整历史扫描和许可证审查未完成。
- 启发式扫描不保证发现全部敏感信息，公开前仍需人工复核与完整历史检查。
- 生产及 SAP 只读/写入连接均未执行。目标性能和恢复指标尚未测试。

## 下一阶段

按 `docs/plan.md` 的 Phase 01 建立真实工程、数据库、会话与权限基础，验证构建和容器环境；保留本阶段的业务规则作为后续 Java 回归场景。
