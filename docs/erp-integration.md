# ERP 端口与 SAP 边界

七个端口的结构化定义见 `specs/erp-ports.json`。Phase 03 已落地 integration.port 下七个 Java 接口、类型化 DTO、固定快照及独立 adapters.mock；下面是通用接口语义，不是可调用 SAP SDK。

| 端口 | 方法语义 | 通用返回/请求 |
| --- | --- | --- |
| OrganizationProvider | fetchOrganizations(cursor,pageSize) | 公司、工厂、采购组织及关系，下一游标 |
| SupplierProvider | fetchSuppliers(cursor,pageSize) | 供应商、状态、公司关系 |
| MaterialProvider | fetchMaterials(cursor,pageSize) | 物料、单位、状态 |
| PurchaseOrderProvider | fetchPurchaseOrders(cursor,pageSize) | 订单头、行、计划行、来源版本/摘要 |
| GoodsReceiptProvider | fetchGoodsReceipts(cursor,pageSize) | 采购订单引用、物料凭证/年度/行、收货/冲销、数量、过账日 |
| OrderConfirmationPublisher | publishConfirmation(command,idempotencyKey) | ACCEPTED / REJECTED / UNKNOWN、外部引用与脱敏错误 |
| AdvanceShippingNoticePublisher | publishAsn(command,idempotencyKey) | ACCEPTED / REJECTED / UNKNOWN、外部引用与脱敏错误 |

所有来源 DTO 包含 `sourceSystem`、`sourceClient`、`externalKey`、`sourceRevision`、`sourceUpdatedAt`；游标为不透明字符串，可使用页令牌+时间高水位+去重窗口。固定快照内分页，避免边同步边更新造成漏单。
核心业务使用通用 UUID、数量、日期和组织引用，不能依赖 SAP 表名、事务码或固定组织代码。

## Mock Adapter（Phase 03 交付）

当前实际同步实体为采购订单，物料通过独立端口预置到来源映射；组织/供应商读取端口返回虚构样例，未实现通用主数据同步任务。收货读取端口本阶段返回空页，发布端口明确返回 UNSUPPORTED，真实写入保持关闭。
页面先选择已有公司、采购组织、供应商与工厂建立连接，再选择固定模拟场景导入。范围、任务状态及异常码见 [Phase 03 实现](phase-03-design.md)。初始/新版本/重复/乱序/冲突/取消/关闭/重开/移除行/映射失败/分页故障已实现；部分确认、延期、ASN 与收货场景仍属后续模块，以下为整体 Mock 规划。

Company A / Company B、Plant A / Plant B、Supplier A 服务两公司、Supplier B 仅服务 Company B。
提供确定性场景：正常单、多计划行、部分确认、延期、版本改变、取消、重复事件、乱序版本、分批 ASN、无 ASN 收货、部分/全额冲销、超收、超时后未知结果。
Fake publisher 只记录虚构命令，不做真实网络调用；测试可注入超时/拒绝。种子可重置只能作用于明确的本地测试数据库，不能写成通用清库脚本。
Phase 00 提供端口定义与数量规格，不把其称为完整 Mock ERP Adapter。

## SAP S/4HANA 1909 只读适配（Phase 06）

独立 `adapters.sap` 与私有部署配置启用，默认 adapter=mock、writeEnabled=false。
优先评估目标 SAP 实例实际开放且有权限的 OData/CDS/BAPI/RFC 只读接口；业务对象抽象不绑定某一协议或唯一版本。
Phase 06 已按授权测试环境的实际元数据实现两项 OData V2 服务，字段、版本策略、计划行歧义、只读限制及验收边界见 [Phase 06 设计](phase-06-design.md)。不保证所有 1909 补丁或其他 ERP 版本默认提供相同字段。
只读账户、分页、增量策略、单位/时区、删除标识、取消/冲销、来源版本映射在沙箱验证；不使用写 RFC 权限账户。
连接器 capability 声明 readOrganizations/readSuppliers/readMaterials/readOrders/readReceipts；SAP 只读模式 publisher 返回 UNSUPPORTED，不降级为真实写入。
后续任何真实确认/ASN 发布都需单独适配、测试和明确授权；全局 writeEnabled 只是必要条件，不代替授权。

## 同步管理与错误恢复

按连接器/实体流单任务串行推进游标；业务导入、inbox 去重和游标同事务。唯一键包含来源系统、client、实体、外部键。
重试采用指数退避+抖动，默认上限 5 次进入人工处理队列；认证/权限错误暂停，格式/映射错误隔离单事件，网络/限流错误按 Retry-After。
只读拉取重试可自动；UNKNOWN 发布结果先查询对账，不能盲发。游标回退仅运维授权，并通过幂等 inbox 安全重放。
监控：成功/失败/隔离数量、延迟、游标更新时间、耗时、重试次数。运维页面不显示原始凭据或未脱敏负载；重试也需动作与连接器范围授权及审计。
初次全量后按来源计数/数量汇总对账；每日抽查或增量回看，不依赖单一更新时间保证完整性。
