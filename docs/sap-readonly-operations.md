# SAP 只读接入操作与退出参考

本文只含通用步骤。实际主机、Client、角色/账号、证书、配置版本和变更证据由部署方私有保存，不随源码公开。接入前确认是获准的非生产环境；当前适配器仅 GET，ERP 写入关闭。

## 准备和实际验证范围

1. SAP GUI“系统 → 状态”和 SMICM 核实系统、实例、Client、监听和 Gateway 部署方式。
2. STRUST 核实 SSL Server PSE；按现有 PSE 生成 CSR，由可信签发方签发包含实际访问名称/IP SAN 的服务器证书，再向原 PSE 导入响应并保存。保留服务器私钥在 SAP 内。
3. Basis 按批准窗口配置适用的 icm/server_port_<n>，检查、保存、激活，并实际验证运行态 HTTPS。不覆盖无关 HTTP/SMTP 参数，不以“已激活配置”代替监听验证。
4. /IWFND/MAINT_SERVICE 检查后端是否实际提供 API_PURCHASEORDER_PROCESS_SRV 与 API_MATERIAL_DOCUMENT_SRV，选择准确版本/别名后按需要注册；记录技术名、外部名、ICF 节点和包。
5. SU01/PFCG 建立专用技术账号和服务访问角色，按实际 IWSG/IWSV 默认值、S_SERVICE 和服务授权要求最小化配置。业务显示权限另按批准范围核定，不能仅凭 metadata 200 认定完备。
6. OpenSRM 将地址、CA 和认证保存在 private/sap，通过 Compose secret 挂载。先 GET metadata；精确订单/组织白名单和映射就绪后才读取少量业务。

空壳系统只能验证连通、认证、元数据和空响应；订单、收货/冲销数量对账需要实际源数据。合同/Mock 验证不等同真实 SAP 业务验收。不要为测试禁止写入而在 SAP 尝试创建、修改或过账请求。

## 退出和清理原则

本节是维护参考，不是自动执行指令。先核查所有权和依赖、保存恢复点，由有权限的 SAP Basis/安全管理员处理。

- 专用账号：先确认无其他使用者，优先锁定并观察；必要时解除项目角色分配、用户比较，再决定删除。
- 角色：导出定义和分配清单，不能删除其他用户共用的公共角色或直接清理 SAP 表。
- Gateway：只对已确认的项目注册对象停用/注销；不能删除后端标准实现或停用整个 ICF 父节点。
- HTTPS：端口是实例级资源；确认无其他依赖后，基于当前有效配置定点移除项目新增参数，不整份回滚旧版本。
- PSE/证书：可能由多个应用共用并包含私钥；不直接删除整份 SSL Server PSE、不在文件系统手改。无可靠恢复备份时，优先保留证书，仅关闭专用账号访问。
- 客户端和 CA：确认依赖后处理私有认证/信任文件。私钥和口令不进入仓库、日志或手册；公开证书不是完整私钥/PSE 备份。

SRM 测试账号/业务数据清理与 SAP 配置清理是两件事；恢复 SRM 数据库也不会自动回退 SAP 设置。

## 官方参考

- [SAP：创建 SSL Server PSE](https://help.sap.com/docs/SAP_NETWEAVER_AS_ABAP_752/e73bba71770e4c0ca5fb2a3c17e8e229/492371abbf5a1902e10000000a42189c.html)
- [SAP：由 CA 签发并导入证书响应](https://help.sap.com/saphelp_snc700_ehp04/helpdata/en/3b/6e89fcda784b6c8d645740f644a602/content.htm)
- [SAP：注册 Gateway 服务](https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/2240005993f2429088e42085ebf697c5/d124df57c7c2083ee10000000a4450e5.html)
- [SAP：OData 服务授权与 PFCG/SU01](https://help.sap.com/saphelp_ssb/helpdata/en/6f/0e415370107d77e10000000a441470/content.htm)

页面名称和权限对象随 SAP 版本/部署方式变化，以目标系统元数据、授权跟踪及当前 SAP 指南核实，不将示例直接用于生产。
