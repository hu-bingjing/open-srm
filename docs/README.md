# 设计文档索引

业务使用请先阅读 [OpenSRM 操作手册](user-manual.md)，覆盖管理员、采购员、供应商及审计人员的实际页面流程。

当前交付边界见 [项目结项状态](project-status.md)；SAP 接入维护见 [通用操作与退出参考](sap-readonly-operations.md)，具体环境手册不公开。

本文档集包含设计基线、分阶段实现/验收记录和操作手册。当前功能以操作手册及最新阶段验收为准；早期章节保留其当时的实现边界。

| 文档 | 用途 |
| --- | --- |
| [PRD](prd.md) | 角色、场景、范围、需求编号与验收 |
| [架构](architecture.md) | 模块边界、部署拓扑、事务与技术决策 |
| [权限矩阵](permissions.md) | 操作权限、范围元组、拒绝规则 |
| [数据模型](data-model.md) | ERD、实体、键、约束和索引 |
| [状态机](state-machines.md) | 独立生命周期及数量/版本规则 |
| [API](api/README.md) | REST 约定与 OpenAPI 3.1 契约 |
| [ERP](erp-integration.md) | 七个端口、Mock、SAP 只读边界 |
| [安装规划](installation.md) | 当前可执行命令、后续构建与部署条件 |
| [开发计划](plan.md) | Phase 00–08 依赖、交付和测试门槛 |
| [差距与风险](gap-analysis.md) | 当前能力、未实现内容、决策与限制 |
| [公开前检查](security-release.md) | 白名单、敏感检查、GitHub 安全设置 |
| [验收记录](acceptance/phase-00.md) | 本次实际检查结果与未验证项 |
| [Phase 01 验收](acceptance/phase-01.md) | 真实工程、数据库、权限和容器/页面验收 |

机器可读基线：`api/openapi.json`、`../specs/state-machines.json`、`../specs/erp-ports.json`。
可执行业务规格：`../specs/domain.py`；只用于验证规则和指导后续 JUnit 测试。

- [Phase 06 设计](phase-06-design.md)：实际元数据、版本和收货/冲销映射、私有配置及只读验收边界。

- [Phase 06 验收](acceptance/phase-06.md)：Ubuntu 内部测试、数据保留、SAP 空结果及真实对账待办。

- [Phase 07 设计](phase-07-design.md)：工作台、站内通知投递、权限和分单位统计口径。

- [Phase 07 验收](acceptance/phase-07.md)：实际页面、当前范围、通知去重/重试、数量对账及 Ubuntu 数据保留。

- [Phase 08 设计](phase-08-design.md)、[验收](acceptance/phase-08.md)、[备份恢复](operations-recovery.md)、[依赖许可](dependency-license-review.md)、[部署清单](deployment-checklist.md)。
