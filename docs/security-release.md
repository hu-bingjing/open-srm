# 安全与公开准备

2026-11-10：已按用户明确授权公开脱敏源码，启用 GitHub 私密漏洞报告；待发布文件和全部 Git 历史已用项目扫描器及 Gitleaks 检查，零秘密命中。云端 CI 当前结果以 [Actions](https://github.com/hu-bingjing/open-srm/actions) 为准。以下“未建立远端”等阶段记录保留其当时边界；公开可见仍不等于默认授予项目许可证或通过生产验收。

## 当前措施

- `.gitignore` 排除私有配置、真实数据、秘密、依赖/产物/备份/日志；`.env.example` 只有通用占位符。
- `scripts/scan_sensitive.py` 对明确发布路径做 UTF-8 启发式扫描，命中只输出路径/行号/规则，不输出原文；未知文件、链接或二进制不能静默通过。
- `.githooks/pre-commit` 扫描 Git 暂存内容（包含删除后不存在于工作树的版本）；本地可选启用。CI 扫描受版本控制的工作树内容。
- GitHub Actions 已实际运行规格测试、标准 OpenAPI 校验、敏感检查和依赖审计；每次提交的最终结果以 Actions 为准。
- Dependabot 配置覆盖 pip、GitHub Actions、Maven、npm、Docker；CI 包含 Java/PostgreSQL 集成测试、前端构建/E2E、Maven OSV/npm 依赖审计。私密漏洞报告已核验启用；其他服务端设置不因存在配置文件而推定已启用。
- 镜像扫描容器以宿主 UID/GID 写入项目缓存，并只补充 Docker socket 所属组；保留能力移除、只读 socket 挂载及漏洞/秘密门槛，适配普通用户运行的 CI。

启发式扫描不能识别所有企业名称、非结构化业务数据、历史秘密或新型令牌；测试通过不是已安全公开的证明。公开前增加专用扫描器的完整 Git 历史扫描及人工复核，历史中出现的凭据必须撤销/轮换。

## 发布白名单

只允许根目录列出的说明/示例/依赖清单，和 `docs/`、`specs/`、`scripts/`、`tests/`、`.github/`、`.githooks/` 文本文件。新增源码目录要显式扩展 `scripts/scan_sensitive.py` 白名单并复核。
禁止真实公司/组织代码、内部地址、含用户身份的本机路径、真实票据/台账/个人资料、口令令牌、截图和未经审查二进制进入发布范围。
`private/`、`data/`、`tmp/`、`backup/`、`.venv/`、构建结果只留本地。忽略规则不能撤销已跟踪文件，发布时需核查完整 Git 清单。

## 仓库管理员操作与后续事项

1. 已按用户明确要求建立公开源码仓库，未发布 Release 或 CI 附件。
2. 核实账号与套餐，启用依赖图、Dependabot alerts/security updates、Secret Scanning 和 Push Protection（可用时），启用私密漏洞报告；记录实际设置及限制。
3. 私有仓库是否支持 Secret Protection 依账号/套餐而定；不能为获得扫描而擅自公开仓库。参考 [GitHub 官方说明](https://docs.github.com/en/code-security/how-tos/secure-your-secrets/detect-secret-leaks/enable-secret-scanning)。
4. 保护主分支，要求 PR 审查及 CI 必须通过；限制 Actions 权限，PR 不使用生产秘密，不采用 `pull_request_target` 执行外来代码。
5. CI 不保存构建附件；Dependabot PR 必须经测试和人工审查，不自动合并。

## 正式公开检查表

- [ ] 确认项目开源许可证、版权/贡献归属；不能以缺失 LICENSE 视为自由开源。
- [x] 人工审查本次完整发布清单，排除私有配置、真实标识和业务数据。
- [x] 专用扫描器检查所有 refs/提交历史；完整发布清单/大小复核，零秘密命中。
- [ ] 依赖漏洞和许可证清单（包括传递依赖、前端资源和镜像），解决不兼容或限制。
- [x] Actions 固定已核验完整提交 SHA；Python 完整开发依赖锁定，运行基础镜像固定摘要。后续更新仍须复扫回归，见 [Phase 08](acceptance/phase-08.md)。
- [ ] 干净环境构建、自动化回归、授权负向测试、备份恢复、部署验收记录。
- [x] SECURITY 私密报告入口可用；README 已更新实际功能、版本、构建运行要求与验证限制。
- [x] 用户明确授权本次公开；Ubuntu 仅为既有测试部署，未授权生产部署。
