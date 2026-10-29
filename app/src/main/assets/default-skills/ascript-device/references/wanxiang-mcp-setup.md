# 路径 A：在万象内接入 ascript-mcp

> 目标：让万象自带的 AI 直接拿到 AScript 的 **29 个** MCP 工具，从而驱动真机做自动化。
> 原理：万象支持 `STDIO` 型 MCP 服务 —— 在 Linux 沙箱里拉起一个进程，用标准输入输出做 JSON-RPC。
>
> **截至当前版本，万象已把这条服务内置**：
> - 「设置 → MCP 插件与协议生态」里预置了 **「AScript 真机自动化」（server id `mcp_ascript`）**，默认已启用，命令为 `python3 -u -m ascript_mcp.local`；
> - 运行依赖由「设置 → 开发工具 / 环境套件 → **Python & AI 开发者套件** → **AScript 真机自动化运行环境**」组件负责预装（`setup_ascript_mcp.sh`，已锁 `ascript-mcp==1.7.0` + `mcp==1.30.0`）。
>
> 所以正常流程**不再需要手工 pip 或手工加服务**——只要装好上面那个组件、并让手机端 AScript App 开着设备服务即可。下面的「手工步骤」保留作为排错/自定义参考。

## 为什么这条路在万象里成立

万象的内置 MCP 服务本身就是这个形态（`command = "python3"`，`args = ["-u", "/opt/wanxiang/scripts/xxx_mcp_server.py"]`），
AScript 官方 MCP 也是 Python 包，因此把它作为一条自定义 STDIO 服务加进去即可，不需要 uvx、不需要 Node。

## 环境实况（万象沙箱实测，非推测）

| 项目 | 实测结果 | 影响 |
|---|---|---|
| 沙箱系统 | Ubuntu 24.04 (aarch64, PRoot) | ✅ 支持 |
| Python | `3.12.3`（`/usr/bin/python3`） | ✅ 满足 ascript-mcp 要求 |
| 联网 | `pypi.org` **不通**（`000`）；清华/阿里/腾讯镜像 **200** | pip 已预配 TUNA 镜像，装包正常 |
| `uv` / `uvx` | **未安装** | 官方 Codex 插件走 uvx；万象内改走 pip 即可 |
| `pip3 install ascript-mcp==1.7.0` | ✅ **安装成功** | 但依赖会拉到 `mcp 2.x` → 必须锁 `mcp<2` |
| `python3 -m ascript_mcp.local` | ✅ **可稳定启动**，stderr 干净 | stdio 入口确认可用 |
| MCP 握手 `tools/list` | ✅ **成功，返回 29 个工具** | server 自报 `ascript-local` |
| 手机上 AScript App | ✅ 已安装（`com.aojoy.airscript`） | 前提具备 |
| 本机 `127.0.0.1:9096` | ✅ HTTP 200（打开 App 后） | 服务绑 `0.0.0.0`，回环可直连 |
| 沙箱 → 宿主回环 | ✅ **已端到端打通**（连接 / 截图 / 状态全通） | "手机自控自己"成立，见 4.1 |

## 步骤 1：安装 ascript-mcp（推荐用内置组件；下面是等价的手工命令）

推荐：设置 → 环境套件 → Python & AI 开发者套件，勾选 **「AScript 真机自动化运行环境」** 并安装。
该组件会在装配阶段执行 `/opt/wanxiang/scripts/setup_ascript_mcp.sh`，自动锁版本安装并自检 `import ascript_mcp`。

想手工安装（或内置组件失败时排错），等价命令：

```bash
pip3 install --break-system-packages "ascript-mcp==1.7.0" "mcp<2"
```

**必须锁 `mcp<2`，这不是可选项。** 实测踩坑记录：

- `ascript-mcp 1.7.0` 的依赖是 `mcp>=1.0.0`（无上界），直接 `pip install ascript-mcp==1.7.0` 会装到 `mcp 2.3.0`；
- 后果不是警告，是**启动即崩**：
  ```
  File "/usr/local/lib/python3.12/dist-packages/ascript_mcp/local.py", line 316, in <module>
      @server.list_prompts()
  AttributeError: 'Server' object has no attribute 'list_prompts'
  ```
- 修复：`pip3 install --break-system-packages "mcp>=1.0,<2"` → 装到 `mcp 1.30.0`，恢复正常。
- **实测可用组合：`ascript-mcp 1.7.0` + `mcp 1.30.0`**。这与官方 Codex 插件的 `--with 'mcp<2'` 约束一致。

验证：

```bash
python3 -c "import ascript_mcp, mcp; print('ok')"
python3 -c "import importlib.util as u; print(u.find_spec('ascript_mcp.local') is not None)"   # 应为 True
```

`ascript_mcp` 包内模块：`__main__`、`api_store`、`device`、`examples`、`local`、`server`。

## 步骤 2：启用万象内置的「AScript 真机自动化」服务

打开「设置 → MCP 插件与协议生态」，找到内置服务 **「AScript 真机自动化」（`mcp_ascript`）**，确认其为**已启用**（默认即启用）。
若你之前手工关过，重新打开即可。**正常情况下无需再「添加」自定义服务。**

> 该内置预设的定义等价于下表（来自 `BuiltinMcpPresets`）：
>
> | 字段 | 值 |
> |---|---|
> | 传输类型 | `STDIO` |
> | 命令 | `python3` |
> | 参数 | `-u -m ascript_mcp.local` |
> | 环境变量 | （留空） |
>
> 也可按上表把它当**自定义**服务再添加一份（例如想改镜像源或加环境变量时）；但注意不要和内置那份同时启用，避免两个同名进程。

等价写法（若你选择装 uv）：

| 字段 | 值 |
|---|---|
| 命令 | `uvx` |
| 参数 | `--with` → `mcp<2` → `ascript-mcp==1.7.0` |

> `python -m ascript_mcp.local` 是官方 README 里给 Cursor 的 stdio 入口，与 `uvx ascript-mcp==1.7.0` 等价。

## 步骤 3：验证服务真的起来了

三选一（从快到慢）：

1. MCP 设置页点该服务的**连通性检测**，应显示 ONLINE；
2. 问 AI：「列一下 ascript 服务提供的工具」——应报出 **29 个**（`scan_devices`、`connect_device`、`observe_device`、`deploy_and_run` …）；
3. 让 AI 调一个只读工具，例如 `get_platform_overview`，看是否返回真实内容。

**判据**：能列出工具名且能返回内容 = 服务通。仅"配置已保存"不算通。

手工验证（不走万象 UI，直接对话 MCP 进程）：

```bash
printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}' \
 '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}' \
 '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
 | python3 -m ascript_mcp.local
```

预期：先返回 `serverInfo = {"name":"ascript-local", ...}`，再返回含 29 个工具的 `tools/list` 结果。

> ⚠️ **手工探测姿势有讲究**：上面 `printf ... | 进程` 的写法只适合**快**调用（`tools/list`）。
> 若要验证 `get_device_status`、`screen_capture` 这类耗时调用，**必须先保持 stdin 打开**、逐条发送并等待响应。
> 一旦 stdin 被提前关闭（例如用 `communicate()` 一次性喂完请求），server 的异步任务会被取消，
> 慢工具的结果直接丢失，表现为"调用没返回"——**这是探测方式的假象，不是工具坏了**。

## 步骤 4：连接设备

### 4.1 万象连本机（手机自控自己）—— **已实测打通**

1. 在手机上打开 **AScript App**。它的设置页顶部有「开发者地址」，显示形如 `http://10.57.85.101:9096/`
   —— 看到这一行就说明设备服务已在监听；
2. 让 AI 执行 `connect_device`：`ip` 传 `127.0.0.1`（**必填**）、`port` 传 `9096`；
3. 让 AI 用 `screen_capture` / `get_device_status` 证明连通。

**实测结果（万象沙箱内，目标设备 = 本机）**：

| 调用 | 真实返回 |
|---|---|
| `connect_device(ip=127.0.0.1, port=9096)` | `已连接：Android (127.0.0.1:9096)，平台：android` |
| `screen_capture` | image 内容，**534,456 字节真实截图** |
| `get_device_status` | 真实状态 JSON：电池温度 `41.5°C`、网络 `mobile`、存储 499 GB 总 / 184 GB 空闲、内存、权限清单 |

**两个补充事实**：

- App 的「开发者地址」显示的是**局域网 IP**（如 `10.57.85.101:9096`），但服务实际绑在 `0.0.0.0`，
  所以 **`127.0.0.1:9096` 与局域网 IP 都能连**（实测两者均 HTTP 200）。同机自控用回环最省事。
- 此前测到的 `连接失败：无法访问 127.0.0.1:9096 的 AirScript 服务` 是**设备服务当时未开启**所致
  （裸 TCP 得到 `ConnectionRefusedError` —— 端口未监听，而非网络不可达）；打开 App 后即恢复。

**操作注意事项**：

- 打开 AScript App 时，MIUI 会弹出 **「Root或激活模式」授权框**（"AScript-需要'Root超级用户权限'支持"）。
  给第三方 App 授予 root 是**敏感权限变更**，必须由用户本人决定，AI 不要代为点"确定"。
- 局域网模式通常不强制 root；若 `run_mode` 只能是 `screen_only`，说明权限受限，需在 App 内切换模式。

### 4.2 万象连局域网内其他设备

IP 填目标设备在局域网中的地址，端口默认 `9096`。前提是目标设备与手机同一局域网、且已开启设备服务。

### 4.3 Android USB/ADB

需要把 `connection_mode` 设为 `ADB` 并传 ADB 序列号。注意：万象沙箱内的 adb daemon 不持久，
命令行 ADB 需"一条命令内完成 connect + 操作"；如需长期使用，优先局域网方式。

## 步骤 5：开工

连通后即可让 AI：

- **先观察**：`observe_device` / `dump_ui_tree` / `screen_capture` / `ocr`
- **小步验证**：`eval_python`（有界、30 秒内、禁止 `while True`）
- **落地成工程**：`create_project` / `upload_file` / `deploy_and_run` / `run_project` / `get_run_log`

## 排错表

| 现象 | 可能原因 | 处理 |
|---|---|---|
| 一启动就抛 `AttributeError: ... 'list_prompts'` | 装到了 `mcp 2.x` | 锁版本重装 `"mcp<2"`（实测 `1.30.0` 可用） |
| MCP 服务显示 OFFLINE | 未安装「AScript 真机自动化运行环境」组件（`import ascript_mcp` 失败）/ 命令写错 | 先装该环境组件；再在 base 里手跑 `python3 -m ascript_mcp.local` 看报错 |
| `pip` 卡住或报网络错 | 沙箱访问不了 `pypi.org` | 用镜像：`-i https://pypi.tuna.tsinghua.edu.cn/simple` |
| `connect_device` 报"无法访问 `ip:9096`" | 设备服务未开（端口未监听） | 在 AScript App 里开启「设备服务」；这是 TCP 可达但端口关闭，不是网络不通 |
| 工具列表为空 | MCP 协议版本不兼容 | 确认 `mcp<2` |
| 连上了但操作无效 | 运行模式不匹配 | 看 `get_device_status().run_mode.code`，按 `mcp-tools.md` 的观察策略选模式 |
| 偶发断开 | 手机休眠 / 网络切换 | 保持屏幕常亮与同一 Wi-Fi；重连后重新验证状态 |

## 回退策略

任一步持续失败（比如 pip 装不上、用户暂时不想开设备服务）时：**不要卡在这里**。
直接切路径 B —— 万象自带 host 工具能用同样的思路完成截图、点击、输入、抓日志，只是不能跨设备。
对照表见 `host-tools-map.md`。
