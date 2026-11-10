# REST API 基线

规范：`openapi.json`（OpenAPI 3.1）。`x-implementation-phase: 01` 标记身份接口，`02` 标记组织、供应商及范围接口，`03` 标记采购订单/版本与 Mock 同步接口；`planned` 为后续契约，不能把规划路径当作已上线功能。
维护 `scripts/generate_openapi.py` 后重新生成契约，`check_project.py` 检查二者一致；标准解析器另做 OpenAPI 语义校验。

## 通用约定

- 基路径 `/api/v1`，JSON camelCase；UUID 为对象键，业务外部键不可当作跨系统唯一主键。
- 数量为十进制字符串（最多 13 位整数、6 位小数），日期 ISO date，时间 UTC ISO date-time；来源版本为不透明字符串。
- 登录使用服务端会话 Cookie：HttpOnly、Secure、SameSite=Lax；所有变更请求带 `X-CSRF-Token`（登录也需要从 `/auth/csrf` 获取匿名会话令牌）；开发 HTTP 放宽仅允许显式 local 配置。
- 角色与组织范围由服务端取得；前端不能提供身份或通过 companyId 切换权限。列表过滤条件只是进一步收窄范围。
- Phase 01 实现 `/auth/csrf`、`/auth/bootstrap/status`、`/auth/bootstrap`、`/auth/login`、`/auth/logout`、`/auth/me`、`/auth/profile`、`/users`、`/users/{id}`、`/users/{id}/roles`、`/audit-events`。
- 列表 `page` 从 0 开始，`size` 默认 20、最大 100；固定 createdAt/id 稳定排序，`total` 只计可见记录。高流量同步接口使用独立不透明游标。
- 详情响应 ETag 来自本地 lockVersion；已有对象变更要求 `If-Match`。412 表示本地版本已变化；409 表示业务/来源版本冲突。
- 业务创建/提交/审批/取消/重试/配置修改要求 `Idempotency-Key`，长度 8–128；不是登录鉴权手段。对同主体/操作/键保存规范化 JSON 请求摘要，重放校验当前权限后返回原状态码及响应；内容不同 409。
- 当前摘要使用私有密钥的 HMAC-SHA256，防止数据库泄露后对含密码的创建请求进行快速字典攻击。初始化尚无用户身份，使用一次性令牌和数据库行锁保证单次生效，重试返回 404；登录/注销为会话操作，不使用业务幂等缓存。
- 幂等保留至少 7 天；ASN/确认外部业务 ID 和 inbox 去重记录永久保留业务需要的周期，不能在 TTL 后重复发布。同一幂等键并发进行中返回 409 IDEMPOTENCY_IN_PROGRESS/Retry-After。外部发布 UNKNOWN 必须对账。
- 多对象批量命令原子成功或失败。审计/业务/outbox/幂等结果同事务，不在请求中直接等待 ERP 写操作。

## 错误

统一 `application/problem+json`，字段 code、status、title、requestId，可选 fieldErrors；不输出 SQL、凭据、内网地址或堆栈。
`X-Request-ID` 由服务端生成，同一请求的错误响应和业务审计使用同一追踪编号。
401 未认证、403 缺少动作、404 不存在或范围越界、409 状态/数量/来源版本/幂等冲突、412 If-Match 不符、422 格式合法但业务校验失败、429 限流、503 依赖不可用。
关键错误码：SOURCE_REVISION_CONFLICT、CONSISTENCY_HOLD、QUANTITY_EXCEEDED、SELF_APPROVAL_FORBIDDEN、IDEMPOTENCY_CONFLICT、IDEMPOTENCY_IN_PROGRESS、ERP_RESULT_UNKNOWN、VALIDATION_FAILED。
无效字段返回可定位字段路径，但不包含敏感原值。

## 接口族与权限

组织/用户/授权维护需要对应管理范围；供应商维护需要授权采购或管理动作。
订单、确认、变更、计划、ASN、收货读取必须同时经过对象组织及供应商绑定检查。供应商命令不能指定其他供应商主体。
审批需要审批动作和组织范围，并禁止自批。同步查询/重试使用连接器范围；`audit-events` 使用审计范围。
真实收货没有用户创建接口。订单由集成端口导入，不提供通用用户创建订单接口。ERP 写入端口不对浏览器暴露。
所有写入接口记录审计；通知已读只能修改自己的通知。

## 实现阶段补充

Phase 02 实现 companies、plants、purchasing-organizations、suppliers 的 GET/POST/详情/PATCH，供应商公司关系 POST、用户 grants 的 GET/POST/DELETE、supplier-binding 的 GET/PUT 和 auth/access。
所有列表在 SQL 授权过滤后分页及计数；query 对编码/名称做大小写不敏感的字面子串搜索，不把输入的 `%`/`_` 当作通配符。目录跨维度筛选仍匹配同一条授权。
grants GET 返回完整活动授权数组（包含暂时因组织停用而无效的记录），auth/access 返回当前有效授权；管理接口受 scope.manage 限制。grant/绑定写入以目标用户版本为 If-Match，返回更新后的 User；调用方应刷新版本。绑定 supplierId 可为 null，解除绑定也使目标旧会话失效。
供应商响应只包含可见公司关系，canEdit 表示是否拥有全关联公司管理范围。记录停用保留数据，不提供物理删除。幂等重放在当前鉴权和原响应公司可见性检查后返回；当前单实例写入由数据库 app_guard 行锁串行化，后续并发扩展需单独设计。

契约覆盖第一期接口形状，字段/路径变更须同步设计与测试。HTTP 会话、ETag、幂等重放、OpenAPI 响应一致性在 Phase 01–05 用真实后端验证；Phase 00 不提供假装业务服务的 mock HTTP 页面。

Phase 03 实现 purchase-orders GET/详情/revisions、erp-connections GET/POST/详情、sync-jobs GET/POST/详情/retry/issues。连接映射在创建后不可改，本阶段只支持 mock；任务请求 entityType 必须 PURCHASE_ORDER，scenario 为固定虚构场景，不允许浏览器提交 ERP 原始负载。
订单返回可见行及计划行，不返回隐藏行数量或原始全量快照；历史也逐行过滤且要求当前订单仍可见。没有用户创建/修改采购订单或实际收货接口。
启动/重试返回 202 和当次结果，后台按页提交后任务版本递增；幂等重放返回当初的响应，不能用它代替 GET 查询当前状态。retry 要求当前任务 ETag，且失败状态与次数符合限制。监控含 processed/applied/skipped/quarantined/attempts、安全错误码、固定快照和不透明游标，隔离详情不返回原始负载。

## Phase 04 实际契约

`x-implementation-phase: 04` 标记确认、交期申请/审批/撤回、交货计划读取及审批事件。确认创建使用订单 If-Match；延期/改单申请也使用订单版本，审批/撤回使用申请版本。来源版本和当前计划行必须一致。所有变更带 CSRF 和幂等键；合法重放返回原结果，先重新鉴权及校验来源/状态/冻结。
计划行响应增加 effectiveQuantity、pendingQuantity、unconfirmedQuantity，均为精确十进制字符串；来源订购量不变，协同统计反映当前记录状态。确认/变更/计划列表支持 orderId/companyId/plantId/status，SQL 过滤后计数分页；审批事件返回数组（当前每申请最多一个终态决策）。
初次延期自动创建 INITIAL 申请；已有生效承诺使用 RESCHEDULE。确认表单直接提交，没有草稿接口、人工创建交货计划或通用状态修改接口。新增角色范围 confirmation.submit/delivery.approve，与读取范围及供应商身份共同校验。

## Phase 05 实际契约

GET /asns 和 GET /asns/{id} 为整单授权，任一行超出范围则头、详情和列表计数均不暴露。POST /asns 创建草稿；PUT /asns/{id} 修改草稿（reference 不变）；POST /asns/{id}/submit 和 /cancel 使用 ASN If-Match。所有写入要求 CSRF、Idempotency-Key 和供应商 asn.manage 完整组织元组。

请求为 `{reference,lines:[{planId,sourceRevision,orderVersion,quantity}]}`；quantity 是精确十进制字符串。保存草稿不预留，提交按保存的订单本地版本和当前来源重新校验全部行，原子预留；失败不产生部分送货。重复计划行拒绝。ASN 只使用本地 DRAFT/SUBMITTED/CANCELLED/SUPERSEDED，不伪造 ERP 受理状态。

GET /purchase-orders/{id}/shipping-balances 返回当前授权计划行的 submittedQuantity、receivedQuantity、inTransitQuantity、availableQuantity；账本跨不可变来源版本累计逻辑行历史。GET /goods-receipts 返回 ERP 来源事件，receipt_status 为原收货的派生冲销状态；GET /goods-receipts/{id} 返回来源凭证及 ASN 分配。没有人工创建收货接口。

POST /sync-jobs 支持 entityType=GOODS_RECEIPT，场景仅使用 GR_ 前缀；订单类型保持 PURCHASE_ORDER。收货事件由 GoodsReceiptProvider 读取，任务 nextCursor 持久化，失败任务通过既有 retry 接口（202）续传；来源异常在 issues 中保留，同键同内容忽略、同键异内容隔离。字段精确契约见生成的 OpenAPI。

## Phase 07 工作台与站内通知

`GET /dashboard` 返回当前用户的可见订单/冻结数、按动作范围过滤的待确认/待批/逾期未发完数、本人连接的失败同步数、未读数及每类最多 10 项待办。仅登录即可访问；缺少业务权限的指标为零。

`GET /statistics` 需要 order.read，支持 companyId/plantId/purchasingOrgId/supplierId（UUID）进一步缩小范围；返回公司/工厂/单位分组数量数组，不跨单位相加。当前版本订购/确认与历史 ASN/收货口径分开，数量为精确十进制字符串；订购量保留关闭/取消的最新来源快照原始量，不表示未结采购量。

`GET /notifications?page=0&size=20&unread=false`、`GET /notifications/unread-count`、`GET /notifications/{id}` 只返回本人且当前仍有数据范围权限的消息。消息含 eventType、关联订单显示编号、对象引用和 readAt/version，不返回源负载。`POST /notifications/{id}/read` 需要 Idempotency-Key 和 If-Match，已读只能属于本人，撤权后连原幂等响应也不能重放。

`GET /notification-deliveries` 和 `POST /notification-deliveries/{id}/retry` 仅有 user.manage 的非供应商管理员在自己的业务读取/同步连接范围内使用。重试需 Idempotency-Key、If-Match，仅 FAILED 状态可安排新一轮有限自动重试；来源失效则由后台标记 SUPERSEDED，不投递过期事项。不提供任意收件人、正文或外发渠道。
