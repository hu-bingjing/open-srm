# 依赖与许可审查

Phase 08 检查运行和测试/构建依赖：Maven 125 项、npm 锁文件 99 项、Python 3.12 开发工具 41 项。Maven POM/父 POM、npm lock 和 Python distribution metadata 均取得许可声明，未解析声明为 0。原始清单仅保留在项目 tmp 内，可按下列命令重新生成；许可声明不代表法律审查通过。

```sh
cd backend
./mvnw -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree -DoutputType=json -DoutputFile=target/dependency-tree.json
cd ..
python3 scripts/audit-maven.py backend/target/dependency-tree.json
python3 scripts/license-inventory.py --maven-tree backend/target/dependency-tree.json --repository <maven-repository> --output tmp/license-inventory.json
python3 -m pip install --target tmp/python-audit -r requirements-dev.lock.txt
python3 scripts/python-license-inventory.py --packages tmp/python-audit --output tmp/python-licenses.json
```

Python 清单工具必须在安装了 `requirements-dev.lock.txt` 的独立 Python 3.12 环境中执行，不把宿主机其他工具计入项目依赖。npm 清单包含其他平台可选包，实际 Linux 安装数量可以更少。

## 需人工判断的声明

自动标记 16 个包含 EPL/LGPL/MPL 等条款的包：

| 包/版本 | 处理 |
| --- | --- |
| logback-classic / logback-core 1.5.38 | 复核双许可选择、分发与通知义务 |
| jakarta.annotation-api 3.0.0 | 复核双许可选择、分发与通知义务 |
| jna 5.18.1 | 复核可选条款、二进制分发与通知义务 |
| lightningcss 1.33.0 及其 11 个平台包 | 复核 MPL 文件级义务；区分构建工具与最终分发内容 |

工具根据声明关键词标记候选，不能直接把所有候选判为不兼容。须按实际分发方式逐项决定。项目许可证尚未选择，不能声称已完成与某个开源许可证的兼容性判断。

Trivy 还清点三个实际运行镜像中的许可声明：API 211、Web 108、数据库 61。镜像包含操作系统、运行时及第三方材料；镜像分发须保留其原许可/通知并复核相关义务，不能只审查 Maven/npm。全量镜像报告只存本地，不向外上传。

## 漏洞结果与供应链

本次 OSV/Maven、npm audit、pip-audit 均无已知应用/开发依赖漏洞。三个修补后运行镜像的全部已知漏洞为 0，秘密命中为 0。初扫发现 Web 系统包及数据库 Go gosu 高危，通过刷新固定摘要 Web 基础镜像、删除旧 gosu 并直接使用原生 su-exec 修复；随后三个镜像发现的中危 zlib CVE-2026-4738 均通过稳定仓库精确版本 1.4-r0 修复，未屏蔽 CVE。

扫描依赖当时的漏洞数据库，后续需定期重扫和更新固定摘要/锁文件。Actions 已固定完整提交 SHA；Dependabot 增加数据库 Dockerfile，但尚无远端与云端实际运行记录。项目公开还需完整历史秘密扫描、人工代码及版权/许可审核、独立部署门槛和用户授权。
