# 安全与公开准备

## 当前措施

- `.gitignore` 排除私有配置、真实数据、秘密、依赖/产物/备份/日志；`.env.example` 只有通用占位符。
- `scripts/scan_sensitive.py` 对明确发布路径做 UTF-8 启发式扫描，命中只输出路径/行号/规则，不输出原文；未知文件、链接或二进制不能静默通过。
- `.githooks/pre-commit` 扫描 Git 暂存内容（包含删除后不存在于工作树的版本）；本地可选启用。CI 扫描受版本控制的工作树内容。
- GitHub Actions 包含规格测试、标准 OpenAPI 校验、敏感检查和依赖审计；未建立远端，**没有实际云端执行结果**。
- Dependabot 配置覆盖 pip、GitHub Actions、Maven、npm、Docker；CI 包含 Java/PostgreSQL 集成测试、前端构建/E2E、Maven OSV/npm 依赖审计。正式仓库仍未创建，不能宣称服务端安全设置或云端 CI 已启用。

启发式扫描不能识别所有企业名称、非结构化业务数据、历史秘密或新型令牌；测试通过不是已安全公开的证明。公开前增加专用扫描器的完整 Git 历史扫描及人工复核，历史中出现的凭据必须撤销/轮换。

## 发布白名单

只允许根目录列出的说明/示例/依赖清单，和 `docs/`、`specs/`、`scripts/`、`tests/`、`.github/`、`.githooks/` 文本文件。新增源码目录要显式扩展 `scripts/scan_sensitive.py` 白名单并复核。
禁止真实公司/组织代码、内部地址、含用户身份的本机路径、真实票据/台账/个人资料、口令令牌、截图和未经审查二进制进入发布范围。
`private/`、`data/`、`tmp/`、`backup/`、`.venv/`、构建结果只留本地。忽略规则不能撤销已跟踪文件，发布时需核查完整 Git 清单。

## 仓库建立后的管理员操作（尚未执行）

1. 只有收到同步/创建指示才建立 GitHub 仓库，默认私有，不自动发布 Release 或 CI 附件。
2. 核实账号与套餐，启用依赖图、Dependabot alerts/security updates、Secret Scanning 和 Push Protection（可用时），启用私密漏洞报告；记录实际设置及限制。
3. 私有仓库是否支持 Secret Protection 依账号/套餐而定；不能为获得扫描而擅自公开仓库。参考 [GitHub 官方说明](https://docs.github.com/en/code-security/how-tos/secure-your-secrets/detect-secret-leaks/enable-secret-scanning)。
4. 保护主分支，要求 PR 审查及 CI 必须通过；限制 Actions 权限，PR 不使用生产秘密，不采用 `pull_request_target` 执行外来代码。
5. CI 不保存构建附件；Dependabot PR 必须经测试和人工审查，不自动合并。

## 正式公开检查表

- [ ] 确认项目开源许可证、版权/贡献归属；不能以缺失 LICENSE 视为自由开源。
- [ ] 人工审查完整代码/文档/样例/图片，确认无真实标识和业务数据。
- [ ] 专用扫描器检查所有 refs/提交历史；完整发布清单/大小复核；发现秘密先轮换再清理。
- [ ] 依赖漏洞和许可证清单（包括传递依赖、前端资源和镜像），解决不兼容或限制。
- [x] Actions 固定已核验完整提交 SHA；Python 完整开发依赖锁定，运行基础镜像固定摘要。后续更新仍须复扫回归，见 [Phase 08](acceptance/phase-08.md)。
- [ ] 干净环境构建、自动化回归、授权负向测试、备份恢复、部署验收记录。
- [ ] SECURITY 私密报告入口可用；README 功能、版本、构建运行要求准确。
- [ ] 明确授权公开及实际部署；不把文档基线通过视为公开授权。
