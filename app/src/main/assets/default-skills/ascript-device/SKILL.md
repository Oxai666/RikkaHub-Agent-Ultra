---
name: ascript-device
description: >
  在万象内使用 AScript 真机自动化：驱动 Android/iOS/Windows 设备、读控件树与截图、OCR 找字、
  找色比色、执行设备端 Python（eval_python）、上传运行工程与看运行日志。当用户提到 AScript、
  AScript App、真机自动化、设备服务 9096 端口、局域网或 USB/ADB 连设备、控件树、选择器、
  自动化脚本工程，或要求"控制手机 / 模拟点击 / 自动操作某个 App / 跑自动化脚本"时使用。
  内含三条落地路径（万象内接 ascript-mcp / 万象自带 host 能力 / 电脑端 Codex 官方插件）、
  官方 29 个 MCP 工具速查与设备连接排错。
---

# AScript 真机自动化（万象版）

AScript 是一套 Android / iOS / Windows 真机自动化框架：用 Python 写脚本，由手机上的 **AScript App**
提供"设备服务"，外部程序通过局域网（默认端口 `9096`）或 Android USB/ADB 连接真机执行。

官方原本给的是**电脑端 Codex 插件**（在你电脑上跑 `uvx ascript-mcp`，再连手机）。万象运行在手机内部，
形态不同，所以本技能把官方能力**转译成三条可落地路径**，并保留官方那套"先观察、再验证、后运行"的方法论。

## 第一步：选路径（30 秒决策）

| 你的目标 | 走哪条路径 | 依赖 |
|---|---|---|
| 让万象（本机 AI）直接驱动这台手机做自动化 | **路径 A** 万象内接入 `ascript-mcp`（stdio，**服务已内置**） | 装「AScript 真机自动化运行环境」组件 |
| 只想让 AI 在本机点一点、看一眼、抓日志，做轻量自动化 | **路径 B** 万象自带 host 工具 | 零依赖，开箱可用 |
| 在电脑上用 Codex / Cursor 连手机 | **路径 C** 官方离线包 / 手工 MCP 配置 | 电脑端有 `uvx`，手机同局域网 |

> 路径 A 与 B 不冲突：A 拿到的是 AScript 全套能力（29 个工具、跨设备），B 拿到的是"不开任何服务就能用"的最小可用集。

## 路径 A：在万象内接入 ascript-mcp

万象支持 `STDIO` 型 MCP 服务（在 Linux 沙箱内跑一个进程，用标准输入输出通信），恰好能直接跑 AScript 官方 MCP。

> **实测结论（端到端已打通）**：pip 装包成功 → `python3 -m ascript_mcp.local` 稳定启动 →
> MCP 握手成功并列出 **29 个工具** → `connect_device(127.0.0.1:9096)` 连上本机 Android →
> `screen_capture` 返回数百 KB 的真实截图（JPEG 1080×2400）、`get_device_status` 返回真实设备状态。
> **唯一必须先解决的坑**是 `mcp` 主版本（见下）。

1. **装运行依赖（万象已把服务内置，这里只需装依赖）**：
   打开「设置 → 环境套件 → Python & AI 开发者套件」，勾选 **「AScript 真机自动化运行环境」** 组件并安装。
   它会在装配阶段执行 `setup_ascript_mcp.sh`，锁定 `ascript-mcp==1.7.0` + `mcp==1.30.0` 安装并自检。

   - **为什么必须锁 `mcp<2`**：`ascript-mcp 1.7.0` 声明的是 `mcp>=1.0.0`（无上界），直接装会拉到 `mcp 2.x`，
     启动时报 `AttributeError: 'Server' object has no attribute 'list_prompts'` 直接崩溃。
     实测可用组合：**`ascript-mcp 1.7.0` + `mcp 1.30.0`**（内置脚本已锁死）。
   - 沙箱可能连不上 `pypi.org`，但 pip 已预配清华 TUNA 镜像，正常可用。
   - 手工等价命令（仅排错用）：`pip3 install --break-system-packages "ascript-mcp==1.7.0" "mcp==1.30.0"`。
2. **确认内置服务已启用**：万象「设置 → MCP 插件与协议生态」里预置了 **「AScript 真机自动化」（`mcp_ascript`）**，
   命令 `python3`、参数 `-u -m ascript_mcp.local`，**默认已启用，无需手工添加**。
   （与官方 Codex 插件等价的 `uvx --with 'mcp<2' ascript-mcp==1.7.0` 只在用 uv 时才需要。）
3. **验证服务**：在万象 MCP 设置页点"连通性检测"，或直接问 AI"列出 ascript 服务提供了哪些工具"
   —— 应能报出 29 个工具名。
4. **连设备**：打开 AScript App（其设置页「开发者地址」显示形如 `http://10.57.85.101:9096/` 即表示服务在监听），
   再让 AI 执行 `connect_device`（`ip` 必填，传 `127.0.0.1`、端口 `9096`）；有工程配置时也可用 `auto_connect`。
5. **证明连通**：必须让 AI 调 `get_device_status` / `screen_capture` 拿到**真实返回**才算连上
   （实测截图数百 KB、状态 JSON 含电量/网络/存储/权限）。

详细步骤、参数与失败回退见 `references/wanxiang-mcp-setup.md`。

## 路径 B：万象自带 host 工具（零依赖）

不装任何东西也能做本机自动化 —— 万象自带一套 Android 宿主能力（读屏、点击、滑动、输入、抓日志、拉起应用）。
用它们可以覆盖 AScript 常见任务的一大半，只是**只能操作本机**，不能连别的设备。

工具对照表（AScript MCP ↔ 万象 host）见 `references/host-tools-map.md`。

## 路径 C：电脑端官方 Codex 插件

照官方文档装离线包：下载 → 解压 → `uvx` 就绪 → `codex plugin marketplace add` → `codex plugin add ascript@ascript`
→ 新任务里 `/mcp` 看到 `ascript-local` → 扫描并连接设备。

完整命令、目录结构、USB/局域网两种连法，以及"Codex 版本过旧时"的手工 `codex-config.toml` 备用方案，
见 `references/official-codex-install.md`。

## 硬规则（来自官方 AGENT_RULES，务必遵守）

1. **禁止凭记忆编写 AScript 代码**：API 或参数不确定时，先用 `search_api` / `get_module_apis` / `get_code_example` 查证。
2. **动手顺序**：`get_device_status` + `list_python_packages` → 关键片段用 `eval_python` 小步验证 → 最后才 `upload_file` + `run_project`。
3. **选 API 的决策树**：控件（`dump_ui_tree` / `test_selector`）→ OCR → 找图 → 找色 → 坐标仅作最后手段。
4. **`eval_python` 必须有界**：结果赋给 `_result`；探索代码套 `try/except`；**禁止 `while True`**；单次 sleep ≤ 5 秒、总时长 ≈ 30 秒内。循环/监听这类长任务要放进工程再运行。
5. **工程规则**：入口文件是 `__init__.py`；工程根按 Python 包加载，用包相对导入；**不要用** `if __name__ == "__main__":`、`sys.argv`、`argparse`。
6. **选择器要证明唯一**：优先稳定语义 ID，用 `test_selector` 或 `find_all()` 验证；避免随机 ID、生成式 view ID、脆弱路径。
7. **不要引用不存在的图片模板**：先截图生成资源，再写引用。
8. **run 成功 ≠ 目标达成**：MCP 调用成功不代表自动化目标达成，必须给出可验证证据（变化后的界面锚点、预期页面、日志行）。

## 安全红线

- **不要把 `9096` 暴露到公网**。该端口是本地真机调试服务，仅限局域网或本机回环。
- **AI Studio 配对码不适用于本地插件**：配对码属于 AI Studio 服务器 Relay 链路，本地直连不读取它，也不能因为手机已配对就直接控制。
- 设备上的写操作、上传工程、运行脚本属于有副作用动作：目标不明确或有多个候选设备时，先向用户确认再执行。
- 给 AScript App 授予 **root / 激活模式属于敏感权限变更**：必须由用户本人决定，不要代为点确认。

## 资源导航

| 文件 | 内容 |
|---|---|
| `references/wanxiang-mcp-setup.md` | 路径 A 完整步骤：装包、MCP 配置、连通验证、失败回退与排错 |
| `references/host-tools-map.md` | 路径 B：万象 host 工具 ↔ AScript MCP 工具对照表与选型建议 |
| `references/official-codex-install.md` | 路径 C：官方离线包安装、USB/局域网连接、手工备用配置 |
| `references/mcp-tools.md` | AScript MCP 1.7.0 全 29 个工具速查 + 设备连接排错 |

> 本技能为"知识库型"：入口只给导航，细节在 `references/` 下按需读取，不要一次性读完整个目录。
