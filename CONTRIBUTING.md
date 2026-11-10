# Contributing

当前为 0.8.0 一期功能及内部测试验收完成版本，源码按项目所有者授权公开，许可证尚未选择。正式接收外部贡献前须先明确授权、贡献归属和许可证，不默认授予任何开源权利。

1. 阅读 `docs/prd.md`、`docs/architecture.md`、`docs/permissions.md` 和 `docs/plan.md`，针对具体需求提交小范围变更。
2. 新业务规则先给出失败场景；权限测试必须同时覆盖授权访问、越组织和跨供应商访问。
3. 只使用虚构样例；私有配置、生产信息、真实单据及日志不能进入 Issue、PR、代码或截图。
4. 运行 README 的本地检查、标准 OpenAPI 校验及对应 Java/PostgreSQL、前端/E2E 测试；测试在新卷 Mock 实例执行，不能重跑到已清理的现有用户实例。
5. PR 写明问题、最终行为、验证和限制；同步相关 API、状态机、ERD 与 CHANGELOG。
6. 使用功能分支与 PR；安全问题按 SECURITY.md 私下报告。分支保护和服务端安全能力以仓库实际配置为准，不因存在工作流文件而认定已启用。

开发依赖安装见 README。可选的本地提交检查：

```sh
git config core.hooksPath .githooks
```

需先在项目中建立本地 Git 仓库。Linux/macOS 还需 `chmod +x .githooks/pre-commit`，提交时保留可执行位。此命令只配置本仓库，不设置全局钩子。钩子可被绕过，必须配合 CI 与仓库保护。
