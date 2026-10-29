# 内置技能审批记录（RikkaHub-Agent-Ultra）

移植来源：万象 0.18.11 的 `3-skills/builtin_skills`。
审批标准：**技能里承诺的每一种能力，本客户端必须真能执行**。做不到就不进包——
放进去只会让模型对着不存在的东西编造调用。

## 结论

| 技能 | 结论 | 依据 |
|---|---|---|
| ascript-device | **拒绝** | 依赖 ascript-mcp（STDIO + 9096 端口）、`eval_python`、控件树、万象 host 能力、`/attachments/skills` 部署路径，全部只存在于万象。本客户端没有这些，留着等于教模型说假话。 |
| ctf-kb | **通过** | 纯 Markdown 知识库，零外部依赖，配合 shell / 搜索 / Python 即可用。 |
| src-bughunt | **通过（已清理）** | 方法论与知识库可用。已删除/改写的环境假设见下。 |
| eight-honors | **通过** | 纯行为约束，无外部依赖。 |

## src-bughunt 的清理项

- 删除 `rules/desktop-task-folder.md`：整篇是 Windows 桌面路径（`Desktop\.playwright-mcp`），Android 上不成立。
- 删除 `rules/playwright-browser-mcp.md`：要求 Playwright MCP 常驻，本客户端没有该服务。
- `rules/researcher-blackbox-whitebox.md`：把「Playwright MCP is always enabled」改成条件句，指明要走 shell / 内置抓取。
- `SKILL.md` 与 `知识库/recon-methodology.md`：把「搜资产用 MCP fofa，不要自己 curl」改成「有就用、没有就自己 curl」，避免缺工具时停摆。
- 全局把 `~/.grok/...` 与 `Desktop\{任务}_SRC挖洞\` 路径改成技能内相对路径。

## 保留没动的

`tools-registry.json` 与 `scripts/`（万象的 MCP server 与构建脚本）没有搬——本客户端没有 MCP host，
搬进来就是死代码。要接得先做本地 MCP 服务器管理。
