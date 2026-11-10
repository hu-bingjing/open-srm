# 独立状态机与不变量

机器可读迁移表见 `specs/state-machines.json`（路径相对项目根）。迁移表只定义生命周期合法边；业务命令还必须通过以下守卫，不能直接给外部调用通用“改状态”接口。

## 采购订单

生命周期：ACTIVE → CLOSED / CANCELLED；ERP 明确重新开启允许 CLOSED → ACTIVE；CANCELLED 为终态，恢复必须新来源版本并走专门对账流程，不自动重开。
协同进度独立派生：UNCONFIRMED / PARTIALLY_CONFIRMED / CONFIRMED。
收货进度独立派生：UNRECEIVED / PARTIALLY_RECEIVED / RECEIVED；冲销可使进度回退；它不是采购订单生命周期的自动迁移。
头关闭必须来自 ERP 且所有行满足关闭语义；不同工厂的行分别授权。
同版本同摘要忽略；同版本异摘要进入异常；旧版本隔离。新版本保留快照，失效未完成确认/延期/交货计划/草稿 ASN。已提交 ASN 保留并冻结新增协同，先对账；取消后仍保留在途和收货历史。

## 供应商确认

Phase 04 已实现服务器直接提交（不保存草稿）：SUBMITTED/EFFECTIVE、REJECTED、SUPERSEDED；草稿保存/取消尚无接口。延期初次确认预占数量，拒绝/本人撤回均释放。已生效确认改期通过独立 RESCHEDULE 申请，待批时保留原承诺。

DRAFT → SUBMITTED → EFFECTIVE / REJECTED；DRAFT 可 CANCELLED；SUBMITTED/EFFECTIVE 可因新订单版本变为 SUPERSEDED。
提交不能负数/零/超量，必须当前订单版本、订单 ACTIVE、供应商未冻结且无一致性冻结。
按计划行记录部分数量，多条有效确认合计不超过订单数量。日期不晚于当前要求时可自动生效；晚于当前要求生成延期审批，确认保持 SUBMITTED，批准后 EFFECTIVE；拒绝则 REJECTED。
已确认部分和未确认余额分别显示。变更已生效确认必须保留旧版本并通过新申请；不能悄悄覆盖承诺。

## 交期变更与交货计划

延期申请 DRAFT → SUBMITTED → APPROVED / REJECTED / WITHDRAWN / SUPERSEDED；APPROVED 不允许二次审批。申请人不可审批自己的申请；审批人必须有组织动作权限。
批准前校验原订单/确认版本；重复或过期审批拒绝。批准原子更新承诺日期、确认、计划、审计与 outbox；批准不修改 ERP 原要求日期。
交货计划 PLANNED → PARTIALLY_SHIPPED → SHIPPED；未发货计划可 CANCELLED；版本变化可 SUPERSEDED。已发货部分保留历史，未发部分冻结/重计划；计划不能自由超量生成。
Phase 00 可执行规格只模拟单计划行与延期守卫；完整多行计划和审批持久化在 Phase 04/05 实现。

Phase 04 的确认、申请、审批事件及 PLANNED/SUPERSEDED 计划已持久化；批准初次延期才生成计划，批准改期将旧计划失效后等量重建，拒绝/撤回改期保留原计划。ERP 新版本同事务失效旧活跃确认、待批申请和未发货计划，保留完成的审批。Phase 05 已追加 ASN 发货预算、PARTIALLY_SHIPPED/SHIPPED 状态及已发货承诺改期保护；收货/冲销由独立 ERP 事件账本派生。

## ASN

Phase 05 本地实现 DRAFT → SUBMITTED → CANCELLED，以及来源变更使草稿 SUPERSEDED。尚未启用 ERP 发布。未来发布协议设计为 DRAFT → SUBMITTED → ACKNOWLEDGED；DRAFT/SUBMITTED 可 CANCELLED（SUBMITTED 取消须核查 ERP 是否已受理以及是否已收货）；SUBMITTED 发布失败 → FAILED → SUBMITTED（复用外部业务幂等键）；未知结果保持 SUBMITTED 且发布状态 UNKNOWN，查询对账后处理。
ACKNOWLEDGED 只表示 ERP 受理 ASN，仍不表示收货。已收货 ASN 不能取消；ACKNOWLEDGED 的取消需要专门 ERP 协议，第一期不提供自动取消。
一个 ASN 多行、一个计划多批 ASN。头与所有行必须同供应商/公司，可同公司多工厂但逐行校验授权。
提交预留数量，取消释放尚未消耗预留；草稿不预留，提交时并发重算。

## 实际收货

每个 ERP 事件不可变：POSTED → PARTIALLY_REVERSED → REVERSED；支持 POSTED → REVERSED 全额冲销。冲销是新事件，不删除或改写原单。
收货须来自 GoodsReceiptProvider；ASN、人工页面、确认发布都无权创建实际收货。
每个来源复合键只入账一次；同键不同内容隔离。未知订单、超收、无法匹配或冲销先到都进入异常队列；真实 ERP 超收仍保留权威事件，订单进入一致性冻结，不能静默截断数量。
收货可无 ASN；分配到 ASN 的收货消耗相应发货预留；未分配量也计入订单累计净收货，避免再次超送。

## 数量示例与公式

对一个当前有效计划行：`Q` 为订单数量，`C` 为有效确认总量，`R` 为 ERP 收货减冲销净量，`T` 为已提交/受理 ASN 尚未被收货消耗的在途量。
必须 `0 ≤ C ≤ Q`，可新增发货量 `max(0, min(C, Q) - R - T)`；实际实现还按交货计划/确认分配逐桶校验，防止跨计划重复使用数量。
订单 Q=100，有效确认 C=60，提交 ASN 30 后 T=30、R=0；收到 ERP 20 后 R=20、T=10；可再发 30。未经 ERP 回传，R 始终为 0。
无 ASN ERP 收货 10，则 R 增 10，T 不减；保守减少可发量，待人工匹配/集成对账，不能将无关收货消耗任意 ASN。
ERP 缩量至 Q=15，但 R+T=30 时进入冻结，不能负数出现在途或自动删除 ASN；取消也执行同类检查。

关键操作全部校验动作、完整组织范围元组、供应商绑定、版本、状态和数量；成功业务、幂等结果、审计、outbox 同一事务。校验失败应无业务副作用；Phase 01 起用数据库并发测试证明。

## Phase 05 实现补充

来源改版时保留已提交 ASN、ERP 收货及分配，旧草稿失效；有已提交 ASN 或历史收货时冻结新增协同，不自动释放历史占用。旧部分/全部发货计划保留发货状态，不能再次使用旧来源发货。新来源的逻辑行预算聚合所有历史版本的 R/T，避免更换 schedule UUID 绕过限额。

原收货的 POSTED/PARTIALLY_REVERSED/REVERSED 是累计冲销的派生状态，事件不覆盖。部分冲销需指定 ASN 分配，恢复对应在途，但不恢复计划累计发货额度。当前不支持自动解除对账冻结，也不把本地 SUBMITTED 映射为 ERP 受理。
