# 路径 C：电脑端官方 AScript Codex 插件

> 来源：<https://www.ascript.cn/docs/ai/codex/>（本文为流程整理，命令与目录结构照官方文档）。
> 适用：你在**电脑**上用 Codex（或其他支持 MCP 的 IDE）连手机做自动化。
> 许可：AScript Codex 插件与 ascript-mcp 均为 MIT License，© 2026 北京奥悦科技有限公司。

## 这一版为什么走"离线包"

官方离线包把**本地 AScript MCP + 自动化开发 Skill + 本地 Marketplace** 一次性打包好，
装完不用配官方插件目录，也不需要付款方式或审核提交。走的是
**本地 Marketplace + 本地 MCP + 局域网/ADB 真机连接**，不依赖公网 MCP。

## 包内含什么

```
ascript-codex-plugin/
├── .agents/plugins/marketplace.json          # Marketplace 入口
└── plugins/ascript/
    ├── .codex-plugin/plugin.json             # 插件清单与能力声明
    ├── .mcp.json                             # 本地 AScript MCP（固定兼容版本）
    ├── assets/logo.png
    └── skills/ascript/SKILL.md               # 真机自动化开发工作流
```

## 下载

| 文件 | 地址 |
|---|---|
| Codex 插件离线包 | `https://www.ascript.cn/downloads/ai-coding/ascript-codex-plugin.zip` |
| 中文安装说明 | `https://www.ascript.cn/downloads/ai-coding/ascript-codex-plugin/README.zh-CN.md` |
| 备用 MCP 配置 | `https://www.ascript.cn/downloads/ai-coding/codex-config.toml` |
| 备用技能文件 | `https://www.ascript.cn/downloads/ai-coding/ascript-skill.md` |

## 安装步骤

### 1. 解压

解压到任意目录，进入 `ascript-codex-plugin/`。

### 2. 准备 uvx

插件通过 `uvx` 启动本地 MCP。先确认终端能跑 `uvx --version`，不能则先装 uv：

- macOS / Linux：`curl -LsSf https://astral.sh/uv/install.sh | sh`
- Windows PowerShell：`powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"`

### 3. 安装插件

macOS / Linux（在解压目录内）：

```bash
codex plugin marketplace add "$(pwd)"
codex plugin add ascript@ascript
```

Windows PowerShell：

```powershell
codex plugin marketplace add (Get-Location).Path
codex plugin add ascript@ascript
```

> Marketplace 只需添加一次。包更新后，重新解压覆盖原目录，再执行一次 `codex plugin add ascript@ascript` 即可。

### 4. 验证插件

新开一个 Codex 任务，输入 `/mcp`，应能看到 `ascript-local`。随后可以试：

```
$ascript 检查当前 AScript 工程，连接设备并给出运行验证计划
```

### 5. 连接手机

> 插件装好只代表 MCP loaded，**不代表手机已连上**。

**局域网（Android / iOS）**

1. 手机端 AScript App →「开发者」→ 开启设备服务；
2. 手机与电脑连同一局域网；
3. 让 AI 先扫描："扫描局域网 AScript 设备，先把设备名称、平台和 IP 告诉我，等我确认后再连接。"
4. 确认后再 `connect_device`，默认端口 `9096`。

**USB（Android）**

1. 打开开发者选项与 USB 调试，连接电脑并确认 ADB 授权；
2. `adb devices` 中状态应为 `device`；
3. 让 AI 扫描并连接，随后读取设备状态与当前屏幕。

**验证连通**：`get_device_status`、`screen_capture` 或 `observe_device` 返回**真实**状态/截图才算通。

## 备用：手工直连 MCP

仅当你的 Codex 版本暂不支持插件时使用：

1. 下载 `codex-config.toml` 与 `ascript-skill.md`；
2. 把 `[mcp_servers.ascript-local]` 段合并进 `~/.codex/config.toml`；
3. 把 `ascript-skill.md` 保存为 `~/.codex/skills/ascript/SKILL.md`。

`codex-config.toml` 内容：

```toml
[mcp_servers.ascript-local]
command = "uvx"
args = ["--with", "mcp<2", "ascript-mcp==1.7.0"]
startup_timeout_sec = 60
tool_timeout_sec = 120
default_tools_approval_mode = "prompt"
```

## 官方"注意"三条

1. 本地插件固定使用 `uvx --with 'mcp<2' ascript-mcp==1.7.0`，不要随意升级。
2. **不要**把真机调试 MCP 改成 `https://mcp.ascript.cn/sse` —— 线上 MCP 只适合文档查询。
3. 插件不包含 AI Studio 服务器的地址、token、项目绑定或配对中转配置；**AI Studio 配对码不能用于本地插件**。
