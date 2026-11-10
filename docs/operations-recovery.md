# 测试部署备份、恢复与验收

适用范围：Ubuntu、Docker/Compose、Python 3.12、OpenSSL，专用 local/test 实例。ERP 写入必须关闭。所有命令从项目根目录执行；Docker 权限不足时使用 sudo。首次构建、依赖审计和漏洞数据库更新需联网；已完成备份的恢复不拉取镜像，也不连接 ERP。

## 升级前后

1. 核对实例、版本、三个健康状态和 ERP 写开关；确认没有活动同步/待投递任务。
2. 保留旧镜像标签、部署配置及数据库 pg_dump；使用 `verify-upgrade-preservation.py --capture --strict --baseline private/<new-baseline>.json` 保存原记录摘要。已有基线不覆盖。
3. 在独立 Mock 项目、新数据库卷和新端口完成构建、后端及页面回归，执行 `sh scripts/audit-images.sh`。不得在现有业务实例运行完整初始化 E2E。
4. 原实例使用同一卷升级，等待全部健康；执行原记录核对、HTTPS 专项和重启验证。升级失败时使用旧镜像/旧配置回退；存在未来数据库迁移时必须先按独立副本演练恢复，不能假定旧程序兼容新表结构。本次 0.8.0 没有新迁移，仍为 V8。

## 制作备份

选用新的备份目录；工具拒绝覆盖。HTTPS URL 必须是当前主机地址且由 `private/tls/server.crt` 信任；纯 HTTP 仅限回环。

```sh
python3 scripts/recovery.py backup --directory backups/test-drill --url https://<test-host> --allow-test-maintenance
```

维护期间 API 短暂停止，导出数据库及逐表摘要后 finally 启动并等到就绪。结果包含停顿与备份耗时。备份包含 PostgreSQL dump、白名单源码、全部运行镜像和加密指定配置；不含 SAP profile、SAP 证书或 CA 私钥。AES-256-CBC/PBKDF2 200000 次加密配置，SHA256 验证各文件意外损坏。SHA256 清单不是数字签名，CBC 不是带认证的加密，目录访问控制及可信交付链不可省略。

`private/recovery-key` 首次随机生成并单独保管；它不在备份内。数据库 dump 含业务数据，整个备份必须受控。离机复制时需加密传输/存储，并将恢复密钥走独立受控渠道保存。当前仅验证测试机本地副本，异地介质、独立密钥交接及第二主机恢复另验收。不要将备份、密钥或私有数据上传 GitHub。

## 恢复到全新隔离副本

```sh
python3 scripts/recovery.py restore --directory backups/test-drill --target tmp/test-recovered --port 8089 --allow-test-maintenance
python3 scripts/benchmark.py --root tmp/test-recovered --url http://127.0.0.1:8089
```

恢复工具校验文件 SHA256、镜像配置/全部引用层及运行镜像 ID，拒绝路径穿越、链接和已有目录。只创建新的 Compose 项目/新数据卷，不覆盖原库；镜像使用本地导入及 `--pull never`。逐表数量与内容摘要一致后才启动 API，再验证原会话或重新登录、bootstrap 关闭、统计、已有同步幂等重放。输出中 `sessionRestored=false` 表示备份会话已过期而使用重新登录，不应解释为原会话通过。

API 仅加入 internal 网络，Mock、无 SAP 配置、ERP 写入 false；Web 的单独入口只映射回环。Docker 29 的纯 internal Web 网络不会正常发布端口，因此只给 Web 增加入站网络。该入口容器不是全宿主机物理断网；API 无外部路由、禁拉镜像及完整导出层验证共同证明本次恢复路径无 ERP/镜像仓库依赖。

恢复结果保存在目标 `private/recovery-result.json`，原表摘要在备份 manifest 中；会话材料仅在私有文件内。失败副本保留用于诊断。完成后按目标 `recovery-state.json` 中的精确项目名停止该副本；不删除卷，不运行全局 prune 或清卷命令。

## 发布验证命令

```sh
python3 scripts/check_project.py
python3 -m unittest discover -s tests -v
python3 scripts/scan_sensitive.py
python3 -m openapi_spec_validator docs/api/openapi.json
python3 -m pip_audit -r requirements-dev.lock.txt --strict
sh scripts/test-backend.sh
# frontend 中执行 npm ci --ignore-scripts、npm run build、npm audit 及专用实例 npm run test:e2e
sh scripts/audit-images.sh
```

许可清单与应用依赖审计命令见 [依赖许可审查](dependency-license-review.md)。工作流还执行 API/数据库重启、恢复及副本性能检查；目前无 GitHub 远端，云端 CI 尚未实际执行。阶段实际结果和未验收门槛见 [Phase 08 验收](acceptance/phase-08.md)。
