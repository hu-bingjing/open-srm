# Phase 01 安装与测试部署

## 环境

建议 Ubuntu 24.04 LTS / x86_64、4 核、8 GB 内存、40 GB 可用磁盘；安装 Docker Engine、Compose 插件、Python 3、OpenSSL。运行容器无需在主机安装 Java、Node 或 PostgreSQL。
下列 Docker 命令需由具有 Docker 权限的用户执行；否则加 sudo。安装过程不自动授予用户 Docker 组权限。
按 [Docker 官方 Ubuntu 文档](https://docs.docker.com/engine/install/ubuntu/) 安装，先检查既有组件，不直接卸载已有容器软件。首次镜像拉取和 Maven/npm 构建需要联网；不关闭 TLS 校验，不使用来源不明的镜像源。
源码版本：Java 21、Spring Boot 4.1.2、Maven 3.9.11；Node 24.22.0、Vue 3.5.45、Vite 8.3.6、Element Plus 2.14.7、TypeScript 5.9.3。npm 有锁文件，基础镜像固定摘要。
建议源码目录 `/opt/open-srm`。本项目未上传 GitHub，先将脱敏源码复制至测试机；以下命令均在项目根目录执行。基础镜像采用 Docker Official Images 的官方 Amazon ECR Public 渠道及 NGINX 发布者的官方 ECR 仓库，摘要保持固定。

## 回环 HTTP 测试

```sh
python3 scripts/init-local.py
docker compose --env-file .env -f deploy/compose.yml config --quiet
docker compose --env-file .env -f deploy/compose.yml up -d --build --wait --wait-timeout 300
docker compose --env-file .env -f deploy/compose.yml ps
```

访问 `http://localhost:8080`，默认只绑定回环地址；远程使用 SSH 转发。数据库和 API 不映射宿主端口。
初始化脚本拒绝覆盖 `.env` 和已有秘密；已经初始化的数据库不能靠修改口令文件变更数据库密码。

## HTTPS 测试（新实例）

将 `<test-host>` 替换为测试机 IP 或 DNS；实际地址只进入私有证书/配置：

```sh
python3 scripts/init-local.py --tls-host <test-host>
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose.tls.yml config --quiet
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose.tls.yml up -d --build --wait --wait-timeout 300
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose.tls.yml ps
```

访问 `https://<test-host>`，使用 30 天自签测试证书，浏览器需信任/确认测试证书。TLS 模式启用 Secure Cookie；正式环境须换受信任证书并另行验收。
HTTPS 端口仅对测试网络开放。Docker 发布端口可能绕过 UFW 的常规规则，须按官方文档核验，不能仅凭 UFW 状态判断隔离。
私有父目录 0700；供容器读取的 secret 文件为 0644。TLS 子目录 0755，仅该目录挂入非 root Nginx。宿主机私有父目录保护真实配置，禁止将整个 `private/` 挂入 Web 容器。

## 首次使用

私下查看服务器 `private/bootstrap-token`，在初始化页面填写令牌、自选账号/名称/密码。账号至少 3 位小写字母/数字或 `._-`；密码至少 12 个字符且 UTF-8 最多 72 字节。
成功后初始化入口关闭，不再创建第二个管理员。没有默认账号或固定密码。
管理员可新建用户、授予 ADMIN/AUDITOR/USER、禁用/启用账号；普通用户只能查看自身账号及修改显示名称。ADMIN 不默认拥有 AUDITOR 审计权限。
禁用或角色变更后，已有会话在下一请求失效；不能禁用自己，至少保留一个有效管理员。
尚无密码找回/OIDC、组织/供应商管理和采购业务。完整组织数据权限在 Phase 02 接入；当前只有组织范围基础策略及单元测试。

## 构建与测试

本地 Java 构建使用 JDK 21；Wrapper 首次联网下载并校验 Maven：

```sh
cd backend
chmod +x mvnw
./mvnw -B -ntp clean verify
# 有 Docker 时，运行真实 PostgreSQL 集成验收：
./mvnw -B -ntp -Pintegration verify
cd ..
```

Windows 在 backend 目录使用 `mvnw.cmd`。可用 `-Dmaven.repo.local=<project-cache>` 及 `MAVEN_USER_HOME` 将缓存放在项目 tmp。
仅安装 Docker 的 Linux 测试机可运行 `sh scripts/test-backend.sh`；其测试使用独立临时 PostgreSQL 容器，不接入应用数据卷，不清空已有数据库。

```sh
cd frontend
npm ci --ignore-scripts
npm run build
npm audit --audit-level=high
npx playwright install --with-deps chromium
npm run test:e2e
cd ..
python3 scripts/check_project.py
python3 -m unittest discover -s tests -v
python3 scripts/scan_sensitive.py
```

Playwright 需要正在运行的专用测试应用，默认访问回环 HTTP。HTTPS 入口用 `OPEN_SRM_E2E_URL` 指定；允许自签证书仅用于测试。E2E 会创建初始管理员和虚构用户，不能在真实业务实例执行；不删除/重置任何业务数据。
随机测试管理员凭据在 `private/initial-admin.json`；报告、截图和跟踪文件在忽略的 `tmp/playwright-results/`，可能含会话数据，不上传。可用 `OPEN_SRM_E2E_BROWSER_PATH` 指定测试机已有 Chromium；默认使用 Playwright 安装的浏览器。初始化令牌和测试凭据保留在部署主机，不为执行测试而复制到其他主机。
E2E 之后可在专用测试机运行 `python3 scripts/verify-restart.py --url <test-url> --allow-test-restart`，验证 API/数据库重启后的会话与账号数据。该脚本会短暂重启服务，不在真实业务实例使用。

后端依赖审计：在 backend 运行 Wrapper 的 `org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree -DoutputType=json -DoutputFile=target/dependency-tree.json`，再在根目录执行 `python3 scripts/audit-maven.py backend/target/dependency-tree.json`。仅向 [OSV](https://google.github.io/osv.dev/post-v1-querybatch/) 发送公开依赖名/版本，网络或解析失败不算通过。

## 状态与停止

```sh
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose.tls.yml ps
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose.tls.yml logs --tail 100 api
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose.tls.yml down
```

HTTP 模式省略 TLS 文件参数。`down` 保留数据卷，不执行 `down -v`。后端重启不会重新开放初始化；数据库会话可跨后端重启存续，仍校验有效期/撤销版本。
Flyway 已执行迁移不改写。数据库、凭据和 HMAC 密钥均需私有备份；完整恢复演练在 Phase 08 验收。

## Phase 02 升级与初始授权

版本 0.2.0 追加 V3 迁移，不修改 V1/V2，不重置已有账号或数据库。升级前在部署主机备份 PostgreSQL（pg_dump 自定义格式）、私有配置和旧镜像标签；备份目录仅部署管理员可读。保留这些私有内容在部署主机，不随源码传输或上传。
先在独立 Compose 项目/独立数据卷运行全量测试。仅替换经过构建和测试的源码及镜像，再使用原有 .env/private 和原 Compose 项目执行 up -d --wait；不要调用 init-local.py 覆盖旧环境，不删除卷。失败时保留数据并诊断；旧镜像回退不等于数据库回退，不自动覆盖新数据。

管理员登录后依次创建公司、工厂和采购组织。在“用户与角色 → 数据授权”给采购用户配置公司范围；供应商管理需要 supplier.manage 且不限制工厂/采购组织。管理员本人也需要该公司业务授权。
供应商可关联多家公司；仅拥有部分公司授权的人可查看其授权关系，但不能修改共享主档。管理全部关联公司后才能修改供应商名称/冻结状态。一个供应商账号只选择 SUPPLIER 角色，并设置供应商绑定及 supplier.read 公司授权。
组织/关系停用保留历史。恢复后原授权可能重新生效，永久撤权应在用户授权中显式撤销。接口授权变更即时检查，供应商绑定变更及角色变更需要重新登录。

全套 E2E 在隔离测试栈执行；它包含修改隔离管理员资料的身份回归。已初始化的测试实例可以只运行 `npx playwright test organization.spec.ts`：该套件读取本机私有初始管理员凭据，创建专用虚构验收管理员/用户和独立编码的业务数据，不修改原管理员资料。会保留虚构测试记录，不在生产实例运行。浏览器凭据、截图和跟踪始终留在测试主机。

## Phase 03 订单与 Mock 同步

0.3.0 追加 V4，不修改已执行迁移。与 Phase 02 同样先备份原库、私有配置和旧镜像，在独立 Compose 项目测试后使用原数据卷升级；不运行清库/清卷命令。
在“数据授权”为采购/供应商账号添加“采购订单查看”（order.read），可限制工厂和采购组织；供应商另需正确绑定及有效公司关系。管理员也需要订单范围。
建立 Mock 连接的账号需要 ADMIN 角色、公司级“公司同步管理”（sync.manage）及供应商管理范围。进入“ERP 同步监控”，选择现有公司、采购组织、供应商及工厂映射；工厂选择顺序对应虚构来源 PLANT-1、PLANT-2。连接由创建者负责，不自动授予其他管理员。
选择“初始订单”并启动同步，后台持久化分批导入；在“采购订单”查看授权行/计划行及历史版本。“订单新版本”“重复事件”“同版本内容冲突”“取消订单”等是确定性 Mock 场景，不会向真实 ERP 发请求。失败任务可以从原检查点重试，最多五次；隔离记录不覆盖原快照。

现有测试实例只运行 `npx playwright test purchasing.spec.ts`，创建独立虚构公司和专用账号，保留原账号/主数据。套件把专用验收账号与来源映射写入部署机 `private/phase03-test.json`，仅供本机重启验收使用，不复制到其他主机。
测试专用实例可在浏览器套件结束后执行：

```sh
python3 scripts/verify-sync-resume.py --url <test-url> --allow-test-restart
python3 scripts/verify-restart.py --url <test-url> --allow-test-restart
```

前者验证分页失败后重启 API，再从原游标完成且不重复订单/版本；后者在无活动同步任务时验证 API/数据库重启与二十张业务表摘要。HTTPS 地址必须匹配本机私有证书 SAN；不在生产实例运行。实际结果见 [Phase 03](acceptance/phase-03.md)。

## Phase 04 供应商确认与采购审批

0.4.0 追加 V5，先备份原库/私有配置/旧镜像，再使用原数据卷升级。供应商用户需要 SUPPLIER、正确绑定、采购订单查看和供应商确认提交（confirmation.submit）范围；采购用户需要 BUYER、订单查看和采购交期审批（delivery.approve）范围。范围按公司/工厂/采购组织同元组校验；管理员不默认具有这些业务动作。
供应商在采购订单详情的计划行点击“部分确认”，填写精确数量和承诺日期。按期直接生效并生成等量计划；延期必须填写原因，数量预占，采购到“订单协同 → 交期申请与审批”批准或拒绝。供应商可撤回本人待批申请，在确认列表申请改变已生效承诺。改期批准前原计划保留，批准后等量替换；不能手工修改计划数量。
订单新版本使旧活跃确认、待批申请和计划失效；历史、审批与原数量/日期保留。来源冲突/冻结/取消/关闭阻止新的协同写入，客户端遇到版本冲突应刷新。
现有测试实例仅运行 `npx playwright test workflow.spec.ts`，使用独立虚构公司及专用账号。凭据写入部署机 private/phase04-test.json，仅供本机验收，不导出。隔离实例可执行完整 E2E。

```sh
python3 scripts/verify-collaboration-restart.py --url <test-url> --allow-test-restart
python3 scripts/verify-restart.py --url <test-url> --allow-test-restart
```

第一条验证待审批申请在 API 重启后继续批准、幂等重放只有一个决策和等量计划；第二条在无活动同步时验证 API/数据库重启、会话及二十五张业务表摘要。仅在专用测试部署执行。审批仅生成内部事件，不发送真实 ERP 或外部通知，交货计划不等于发货/收货。

## Phase 05 ASN 与实际收货

0.5.0 追加 V6。升级前备份数据库、.env、private 和旧镜像，在独立 Compose 项目及独立数据卷完成测试后再升级原实例，禁止重新初始化原数据卷。原账号不会自动获得新增业务范围。

管理员在用户授权中为供应商账号增加 asn.manage（ASN 送货管理）以及相同组织范围的 order.read，绑定供应商；确认业务仍需 confirmation.submit。公司级 sync.manage 及连接所有者才能运行 ERP 收货模拟同步。

先同步 CONFIRMATION 演示订单并确认交货计划，再到“送货与实际收货”建立 ASN 草稿、添加计划行和本批数量，保存后提交。草稿不占量；提交只增加在途，ERP 净收货保持原值。同一计划可分批建立多份 ASN。订单或计划版本变化后必须明确重新加载计划行，不能盲目重试旧版本。

Mock 验收：在同一连接的 PO-1001/00010/0001 建立 ASN-A 第一行 10 和 ASN-B 第一行 20 并提交；从 ERP 同步监控选择“ERP 收货及 ASN 分配”。来源事件为收货 20 分配两份 ASN 各 10，以及无 ASN 收货两项各 5；净收货 30、在途 10。部分及剩余全额冲销后净收货 10、在途恢复 30。名称是隔离的虚构场景约定，核心服务按 ERP 返回的分配引用校验，不依赖这些名称。

“ERP 实际收货与冲销”中查看独立来源凭证、冲销状态和分配明细；ASN 数量账本分别显示净收货、在途和可发余额。未知单、来源冲突、先到冲销或超收在同步异常中显示；超收保留来源真实数量并冻结订单。当前没有自动解除冻结或人工填数收货功能。

全套浏览器回归只运行于隔离数据卷；现有测试实例仅运行 `npx playwright test shipping.spec.ts`，追加虚构组织及专用账号，不修改原账号。运行日志、截图和凭据留在部署主机。部署后在无活动同步任务时运行 verify-restart.py 核对 30 张业务表和会话。


## Phase 06 SAP 只读配置

0.6.0 追加 V7：允许按连接选择适配器、增加元数据任务及重试时间。不改写 V1–V6，不迁移或清空原业务数据。

默认部署仍使用 Mock。获得测试环境授权后，把 JSON 放到 private/sap/adapter.json，把公开 CA 链放到 private/sap/test-ca.cer；不要把 CA 私钥复制到部署主机。配置结构如下，示例全部为虚构占位值：

```json
{
  "baseUrl": "https://sap.example.invalid:44300",
  "client": "000",
  "sourceSystem": "ERP_TEST",
  "username": "CHANGE_ME",
  "password": "CHANGE_ME",
  "caFile": "/run/secrets/sap_ca",
  "allowedOrderIds": [],
  "allowedCompanies": [],
  "allowedPurchasingOrgs": [],
  "allowedSuppliers": [],
  "allowedPlants": [],
  "receiptSchedules": {},
  "mappings": []
}
```

白名单为空仅允许元数据校验；不要用 * 代替准确范围。业务读取前填入已经批准的公司、采购组织、供应商、工厂和最多 20 个订单号，保留前导零。receiptSchedules 使用“年度/物料凭证/行”到采购计划行号的明确映射，不确定则留空并等待隔离核对。当前账号只有服务入口权限时，元数据成功不代表业务读取授权通过。

私有目录权限 0700；Compose 文件型 secret 直接绑定源文件，源文件需允许容器非 root 用户读取，例如在受限私有父目录内使用 0444。不要把秘密复制到源码/临时测试目录；隔离栈通过只包含路径的私有 Compose 覆盖文件引用原秘密。

```sh
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose.sap.yml config --quiet
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose.sap.yml up -d --build --wait
# 已配置应用 HTTPS 时，同时传入 -f deploy/compose.tls.yml。
```

先在独立 Compose 项目和数据库卷验收，再备份并升级测试实例。提供 `scripts/verify-upgrade-preservation.py --capture` / 不带参数检查原行指纹；原版本缺少 next_attempt_at 时按旧字段形状比较。不导出数据库真实行或秘密。浏览器测试 sap.spec.ts 创建虚构组织和专用应用管理员，验证真实 SAP 元数据任务及未配置范围时的拒绝路径，不读取 SAP 业务数据。详细映射与限制见 [Phase 06 设计](phase-06-design.md)。

私有 mappings 必须批准完整映射元组，不能仅用外部代码白名单代替。例如一项映射包含 company/purchasingOrg/supplier 外部代码、localCompany/localPurchasingOrg/localSupplier 本地档案编码，以及 plants（外部工厂代码到本地工厂编码的对象）。本地编码必须准确匹配现有档案；改名、换公司或重新映射后重新批准配置。连接创建者不能单靠页面输入把一个已批准 SAP 组织导入另一个本地组织。未批准时在发请求前报 SAP_MAPPING_NOT_APPROVED。元数据任务不读取业务数据，不要求业务映射。

## Phase 07 升级与验收

版本 0.7.0 追加 V8，保留原数据卷、TLS 和 SAP 私有挂载；不要清卷、重置账号或复制 Secret 到隔离源码。升级前数据库/源码/部署文件备份，另用严格基线保留 V7 已有的重试时间字段：

```sh
python3 scripts/verify-upgrade-preservation.py --capture --strict --baseline private/phase07-upgrade-before.json
# 隔离回归通过且保留旧镜像后升级；原 HTTPS/SAP 覆盖文件继续传入。
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose.tls.yml -f deploy/compose.sap.yml up -d --build --wait
python3 scripts/verify-upgrade-preservation.py --strict --baseline private/phase07-upgrade-before.json
```

工作台/统计/通知为实际后端页面。投递工作者每秒处理最多 20 项，源事件扫描每轮最多 100 项；失败指数退避、最多自动 5 次，管理员在有范围的投递监控中按版本重试。来源无效事件只记录 SUPERSEDED，消息不补发给投递完成后新授权的用户。站内提示不代替实时业务状态。

全套浏览器仅隔离测试卷运行；已有测试实例仅运行 `npx playwright test workspace.spec.ts shipping.spec.ts sap.spec.ts`，创建独立虚构组织和专用账号。workspace.spec.ts 写入私有测试凭据后可执行：

```sh
python3 scripts/verify-workspace-restart.py --url "$TEST_URL" --allow-test-restart
```

该脚本重启 API/数据库，核对会话、账号数量和 32 张业务/通知表内容，包含已读状态与投递记录。仅专用测试部署，在同步/通知工作静止后使用；SAP 真实数量对账仍待可见样本，不启用 ERP 写入。

Phase 07 实际测试与限制见 [验收记录](acceptance/phase-07.md)。

## Phase 08 安全回归与恢复

0.8.0 保持 V8，不新增业务迁移。Compose 现在构建独立数据库镜像：仍为相同固定 PostgreSQL 17.11，引入原生 su-exec 替代旧辅助工具，三个运行镜像固定 zlib 1.4-r0。原卷继续沿用；不要将镜像名变化当作新建/清空数据库的理由。首次 apk 构建需要稳定 Alpine 仓库联网；包版本不存在时构建失败，不自动回退。

Origin 的协议来自 Secure Cookie 部署模式；HTTPS 覆盖文件必须保持 `OPEN_SRM_COOKIE_SECURE=true`，本机 HTTP 模式使用 false。客户端转发头不能改变此模式。跨站保护与原 CSRF、权限、版本、审计及幂等共同工作。

升级/恢复操作与命令见 [维护恢复](operations-recovery.md)，门槛见 [部署清单](deployment-checklist.md)，实际测试及待补项见 [Phase 08](acceptance/phase-08.md)。现有实例只运行 `npx playwright test security.spec.ts shipping.spec.ts workspace.spec.ts` 专项，完整初始化回归使用新卷 Mock 实例。备份密钥与恢复包分开保管，既有备份目录不覆盖，原始数据卷不删除。
