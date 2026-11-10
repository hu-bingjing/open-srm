# Phase 06：SAP 只读适配与同步管理

## 边界

`integration.port.ErpAdapter` 聚合七个通用端口，按连接的 adapterType 路由，核心不引用 SAP 包。Mock 与 SAP OData 可共存。组织、供应商主数据沿用已有管理功能；SAP 本阶段不拉取这些主数据。材料由已批准订单行按来源键和单位建立映射，单位变化隔离。

SAP 私有 JSON 配置由 `OPEN_SRM_SAP_CONFIG` 指向；包含 HTTPS 根地址、client、来源系统、技术账号、CA 文件路径、允许的订单、公司、采购组织、供应商和工厂列表。配置不由 REST 或页面接收，不返回给用户，不写日志。默认无配置且 Mock 可用。禁止重定向，下一页限定同源、同服务实体并保留原过滤条件；不接受 $batch、写方法或任意 URL。

真实业务读取必须同时具备：明确的外部组织映射、私有精确白名单（最多 20 个试点订单）、当前应用动作与组织权限，以及 SAP 账号对应的业务读取权限。私有 mappings 将外部公司/采购组织/供应商和外部工厂绑定到准确的本地档案编码元组；逐请求校验，防止连接创建者重新映射后跨组织导入。白名单为空时只允许 METADATA 任务，业务任务以 SAP_SCOPE_NOT_CONFIGURED 失败。服务入口权限不等于业务只读权限；实际组织控制需由 SAP 业务授权和本地映射共同落实。

## 实际元数据契约

采购订单采用 API_PURCHASEORDER_PROCESS_SRV，头、行和计划行分别使用 A_PurchaseOrder、A_PurchaseOrderItem、A_PurchaseOrderScheduleLine。计划行的键是 PurchasingDocument / PurchasingDocumentItem / ScheduleLine，不能直接照搬订单头字段名。展开读取 to_PurchaseOrderItem/to_ScheduleLine；展开集合仍有分页时拒绝不完整导入。数量使用十进制；单位必须一致，计划行合计必须等于订单行数量。退货订单、阻止状态、缺失物料、零数量或未知映射不猜测，隔离供核对。

来源接口不提供原生 SAP 版本号。以 LastChangeDateTime 毫秒建立 `sap-ts-...` 来源修订和单调序列；本地锁版本独立。重复相同内容跳过；同一时间戳内容不同隔离并冻结协同；旧版本拒绝覆盖；新版本保留不可变历史并调用现有协同失效逻辑。删除标志 L 映射取消，全部已交货映射关闭，部分删除保留其他行并触发既有版本一致性检查。不能把本地来源修订称作 SAP 原生订单版本号。

SAP OData 没有已核实的原生固定快照或可靠 delta token。任务保存读取批次和分页检查点，对最多 20 个批准订单重复扫描，按稳定键排序；每单展开读取后再次 GET 最后变更时间，中途变化隔离。任务开始之后的新变更交给下次扫描。消失的单据不能根据列表缺失推断取消。此策略允许检测变化，但不保证跨整个 SAP 系统的原子快照。

物料凭证采用 API_MATERIAL_DOCUMENT_SRV，使用 A_MaterialDocumentItem 展开 to_MaterialDocumentHeader。年度/凭证/行组成不可变来源键；使用 QuantityInEntryUnit + EntryUnit，过账日取头 PostingDate。101 入账，102 通过 ReversedMaterialDocumentYear/Document/Item 引用原收货；原收货的 GoodsMovementIsCancelled 是可变标志，不能再凭该标志额外生成冲销。其他移动类型本阶段不处理，不能宣称覆盖全部库存业务。

## 计划行与 ASN

实际接口无采购收货计划行键。明确的私有 receiptSchedules 映射优先，其次仅在当前订单行只有一个计划行时自动映射；多个计划行进入 RECEIPT_SCHEDULE_AMBIGUOUS。冲销优先沿原收货记录找到计划行，原收货未到则隔离，后续重复扫描可重试。不把销售订单计划行当作采购计划行，不按先进先出猜数量，不凭 ASN 创建 ERP 实际收货。没有可靠 ASN 引用时记录未分配收货；既有数量与超收一致性规则继续生效。

## 同步管理与恢复

新增 METADATA / SAP_METADATA、PURCHASE_ORDER / SAP_READ、GOODS_RECEIPT / SAP_RECEIPTS。页面可选择适配器、映射组织和工厂、启动真实后台任务、查看状态/检查点/隔离原因并按版本重试。元数据任务检查两项 XML EDMX 的必要字段，不导入业务数据。

每页导入、inbox、计数和游标与原同步框架同事务。认证和授权错误停止，不自动放大权限；网络、服务端和限流故障有限指数退避，最多 5 次，可在监控查看安全错误码。手动重试保留原批次及检查点，仍检查连接所有者和当前组织权限。敏感远端响应、Authorization、原始 URL 和凭据不返回页面。

所有 publisher 始终返回 WRITE_DISABLED；启动全局 ERP 写入会被拒绝，不提供启用真实写入的界面。适配器客户端只有 GET 路径。

## 私有部署

启用时将私有 adapter.json 与公开 CA 放到 private/sap，使用 deploy/compose.sap.yml 叠加现有 Compose；JSON 中 caFile 为容器内 /run/secrets/sap_ca。私有目录仅部署用户可进入，配置只读挂载，CA 私钥不传服务器。保留原数据库和 HTTPS 配置。原部署备份及真实环境验收细节只留 private/ 或备份目录。

首次联调先运行元数据任务。无测试组织和单据时，完成真实连接及元数据验证、在独立 TLS 合同环境验证订单/收货持久化，并明确标记真实 SAP 业务数据对账未完成。不得用模拟收货冒充 SAP 实际收货验收。
