# Phase 07 验收记录 — 2026-11-09

工作台、站内通知与组织内统计已实现，并完成既有 Ubuntu 测试实例部署验收。版本 0.7.0，追加 V8；保留原数据、TLS 和 SAP 私有挂载。ERP 写入继续关闭，SAP 真实业务数量对账仍待可见样本补验。

## 已实现

- 工作台显示可见订单、冻结订单、待确认计划行、本人可审批且不能自批的交期申请、逾期未发完计划、本人失败同步及未读通知；每类待办最多展示 10 项，操作回到既有业务接口重新鉴权。
- 组织统计按公司、工厂和计量单位分组；订购/生效确认/待批确认按当前来源版本，ASN、ERP 净收货与在途保留历史版本。订购量是最新来源快照原始量，包含关闭/取消快照，不表示未结采购量；不同单位不相加。
- ERP 净收货为 POSTED 减 REVERSAL；在途按每条有效 ASN 行扣其净收货分配后取非负。未分配收货不抵扣其他 ASN；各账本独立汇总，避免关联倍增。支持公司、工厂、采购组织、供应商范围缩小。
- 独立 notification_delivery/notification 表复用事务 outbox，来源唯一、当前版本/状态检查、当前接收者权限、站内消息唯一键及失败事务回滚；原 outbox 不承担投递状态字段。
- 同步失败和订单冻结记录内部通知事件。同步失败只投递到当前有连接所有权及同步组织授权的账号；行事件额外校验工厂，供应商仍需绑定及完整组织元组。
- 通知列表、详情、未读数、已读、幂等重放均实时校验归属和范围。人工已读和重试具备 If-Match、幂等和公司/供应商审计；仅范围内管理员可查看投递监控并重试。
- 后台分批投递、指数退避，最多自动 5 次，安全错误码；显式人工重试安排新一轮。来源失效标记 SUPERSEDED，重复执行不重复生成消息。

## 实际验证

- 40 项 Java 单元测试及 102 项 PostgreSQL/Testcontainers HTTP 验收全部通过，失败、错误、跳过均为零，BUILD SUCCESS。新增 14 项覆盖通知归属/版本/幂等、撤权/解绑/停用、完整元组、投递回滚/5 次失败/人工重试/重放去重、失效来源、工作台、时区逾期和分单位独立 SQL 对账。
- 隔离实例全套 Playwright 单次 35 项通过，无跳过。主测试实例仅运行 workspace.spec.ts、shipping.spec.ts、sap.spec.ts，12 项通过；不在原实例运行会改原管理员资料的全套身份测试。
- 隔离及主实例均验证收货分页失败后 API 重启，原登录会话及分页游标保留；重试跳过重复事件，历史收货不变。随后 API、数据库分别重启，会话、账号数量及 32 张业务/通知表全部内容摘要不变，包含通知已读和投递状态。
- 严格 Phase 07 升级基线保留 32 张旧表所有原字段，包括既有 sync_job.next_attempt_at；升级及全部验收后原记录和 17 个原账号全部保留。最终权限收紧前还捕获了包含通知及投递表的 34 张表/24 个账号完整行指纹，最终更新、定向验收及重启后同样全部保留，已读状态未变。较早 Phase 06 基线的 32 张表/13 个账号也再次通过保留检查，未重建或覆盖旧基线。
- V1–V8 迁移全部成功，API/Web 0.7.0-local、数据库三服务健康；严格 CA/SAN 验证的 HTTPS 返回 200，部署 ERP 写环境为 false。
- 升级前已保存数据库 SQL、部署/环境和源码备份，保留 0.6.0 镜像供回退。未清除任何数据卷；验收后的隔离容器停止，数据卷仍保留。
- 本地 61 项 Python 规格检查、项目一致性、OpenAPI 标准校验、TypeScript/Vite 构建通过；仅既有 Element Plus 大分块提示。npm audit 零漏洞；125 个 Maven 依赖 OSV 审计零发现。最终发布候选白名单敏感扫描零发现，未上传 GitHub。
- 本地、隔离和主实例的 109 个实现/测试/部署/脚本文件规范化内容摘要一致。实际工作台、统计、已读通知截图已检查，中文及数量显示正常，底部导航可滚动访问。

## 发现并修正

旧测试固定断言 7 次迁移，V8 后提前中断基础数据初始化，修正断言后重跑。逾期用例一度使用已被前置 Mock 故障冻结的订单，按预期不可操作；调整夹具顺序后通过，没有放松冻结规则。

首轮浏览器 23 项通过、3 项超时、9 项未运行；新增导航使较低视口的底部入口不可访问。修正侧栏独立滚动后，全套 35 项通过。验收浏览器补装中文字体以进行实际截图检查，应用未增加依赖。收尾复核还发现尚未分发的投递记录在工厂缓存字段为空时，投递监控范围过宽；改为从不可变事件引用解析真实工厂后再过滤，补充待投递/失败监控与重试跨工厂负向用例。未改原业务行或迁移，完整后端 102 项及全部页面重跑通过。所有首轮失败日志保留。一次本地 Vite 命令误用参数，改为在前端目录执行标准构建后通过；一次 SSH 超时重试后恢复，未绕过部署门槛。

## 复现和证据

后端和静态验证命令见 README。全套浏览器仅独立测试卷运行。现有测试实例定向验收和严格升级检查：

```sh
cd frontend
npx playwright test workspace.spec.ts shipping.spec.ts sap.spec.ts
cd ..
python3 scripts/verify-receipt-resume.py --url "$TEST_URL" --allow-test-restart
python3 scripts/verify-workspace-restart.py --url "$TEST_URL" --allow-test-restart
python3 scripts/verify-upgrade-preservation.py --strict --baseline private/phase07-upgrade-before.json
```

部署主机 tmp 保留 phase07-backend.log、phase07-isolated-browser.log、phase07-isolated-restart.log、phase07-main-upgrade.log、phase07-live-browser.log、phase07-live-restart.log、phase07-main.log、phase07-main-scope-final.log、phase07-npm-audit.log、phase07-maven-audit.log。早期失败见 phase07-backend-migration-count.log、phase07-backend-overdue-fixture.log 和 phase07-isolated-browser-first.log。私有部署核验摘要、凭据、原行指纹、备份、截图和跟踪均不进入发布范围。

## 限制

站内通知是历史提示，不替代当前业务状态；已完成投递不追补给后来新授权的用户。统计不跨单位、不含金额/币种分析或预测；本轮只实现设计中的组织内数量统计。

仅 Ubuntu 测试部署，不连接生产 ERP，不发送 ERP 写请求或外部通知。SAP 真实订单、收货/冲销数量对账仍未完成。Phase 08 的完整灾难恢复、容量压测、生产安全与许可证审查仍待独立实施；未选择许可证、未公开或发布仓库。
