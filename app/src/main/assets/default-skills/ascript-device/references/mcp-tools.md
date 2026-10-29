# AScript MCP 工具速查（v1.7.0）

来源：`ascript-mcp` 官方 README（MIT，© 2026 北京奥悦科技有限公司），并经**万象沙箱实测 `tools/list` 校正**。
实测协议：发 `initialize` + `tools/list` 到 `python3 -m ascript_mcp.local`，server 自报 `ascript-local`，共 **29 个工具**。

> ⚠️ 官方 README 的分类只覆盖 27 个；实测多出 **`observe_device`** 与 **`deploy_and_run`**。
> 以运行时的 `tools/list` 为准，不要凭文档假设工具集。

## API 文档（5）

| 工具 | 说明 |
|---|---|
| `get_platform_overview` | 获取平台 API 模块概览 |
| `get_module_apis` | 获取模块详细 API 文档 |
| `search_api` | 按关键词搜索 API |
| `get_code_example` | 获取代码示例 |
| `get_setup_guide` | 获取环境搭建指南 |

## 在线插件（2）

| 工具 | 说明 |
|---|---|
| `list_plugins` | 查询插件库列表（OCR / YOLO / HID / 大模型等） |
| `get_plugin_detail` | 获取插件详细文档 |

## 设备连接（5）

| 工具 | 说明 |
|---|---|
| `auto_connect` | 从工程配置自动连接（工程含 `.vscode/settings.json` 的 `ascript.deviceId` / `ascript.platform` 时用） |
| `scan_devices` | 扫描局域网 + ADB 设备（可选参数 `port`） |
| `connect_device` | 手动连接指定设备。实测入参：`ip`（**必填**）、`port`、`password`、`connection_mode` |
| `get_device_status` | 设备完整运行状态：运行模式 / 权限 / 屏幕 / 电池 / 内存 / 正在跑的脚本（仅 Android，无入参） |
| `list_python_packages` | 列出设备 AScript App 已装的 Python 第三方库（Android + iOS） |

## 观察界面（7）

| 工具 | 说明 |
|---|---|
| `observe_device` | 一次性观察设备当前界面（截图 + 控件树等综合观测），适合"先看一眼再决定" |
| `screen_capture` | 截取设备屏幕 |
| `dump_ui_tree` | 获取控件树 |
| `test_selector` | 测试选择器是否匹配（用于证明唯一性） |
| `ocr` | 屏幕文字识别 |
| `find_colors` | 多点找色 |
| `compare_colors` | 多点比色 |

## 开发部署（8）

| 工具 | 说明 |
|---|---|
| `deploy_and_run` | 上传并立即运行（部署 + 运行一步到位） |
| `create_project` | 在设备上创建工程 |
| `upload_file` | 上传文件到设备（自动创建工程） |
| `run_project` | 运行工程 |
| `run_project_debug` | 调试模式运行（Android + ADB，自动 forward `5678` 端口并返回 VS Code attach 配置） |
| `stop_project` | 停止运行 |
| `get_run_log` | 获取运行日志 |
| `eval_python` | 设备端 Python REPL（Android + iOS）：主进程 exec 立即拿结果，几百毫秒一轮；适合探索调试、复合决策、自定义工作流。iOS 会自动把 `ascript.android.*` 转译为 `ascript.ios.*` 并预加载 `cv2`/`np`/`Image` |

## 文件管理（2）

| 工具 | 说明 |
|---|---|
| `list_projects` | 列出设备上的工程 |
| `get_project_files` | 获取工程文件树 |

---

## Android 运行模式与观察策略

`get_device_status().run_mode.code` 决定该用哪套办法：

| run_mode | 做法 |
|---|---|
| `accessibility` | 优先 `dump_ui_tree(mode=2)` 与 `Selector(mode=2)`；必要时依次试 3、0/1 |
| `root` | dump 与 selector 用 `mode=9` |
| `hid` | dump 与 selector 用 `mode=6`；点击可能需要已确认的 HID 通道 |
| `screen_only` | 选择器树不可用，改用截图 / OCR / 找图 / 找色 |

- dump 的 mode 与 Selector 的 mode **必须一致**。
- iOS **不要**传 Android 的 mode 值；优先用语义选择器（稳定 id / label / 结构）。

## `eval_python` 约束（硬性）

- 结果赋给 `_result`；
- 探索代码套 `try/except` 并序列化结构化结论；
- **禁止 `while True`**；
- 单次 sleep ≤ 5 秒，总时长约 30 秒以内；
- 循环 / 监听 / 会话类长任务一律放进工程再 `run_project`；
- 导入设备端可选依赖前，先 `list_python_packages`。

## 工程规则

- 入口文件：`__init__.py`；
- AScript 把工程根作为 Python 包加载 → 用**包相对导入**；
- 不用 `if __name__ == "__main__":`、`sys.argv`、`argparse` 作为入口逻辑；
- 选择器优先稳定语义 ID，用 `test_selector` / `find_all()` 验证唯一性；
- 不引用不存在的图片模板（先截图生成资源）。

## 连接排错

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动即崩：`AttributeError: 'Server' object has no attribute 'list_prompts'` | 装到了 `mcp 2.x` | 锁版本重装：`pip3 install --break-system-packages "ascript-mcp==1.7.0" "mcp<2"`（实测 `mcp 1.30.0` 可用） |
| `/mcp` 看不到 `ascript-local` | uvx 缺失或包版本冲突 | 装 uv；确认 `--with 'mcp<2' ascript-mcp==1.7.0` |
| `connect_device` 报"无法访问 `ip:9096` 的 AirScript 服务" | 设备服务未开启（TCP 层 `Connection refused`） | 在 AScript App 里开启「设备服务」；这属于 TCP 可达但端口未监听，**不是**网络不通 |
| 扫描不到设备 | 不在同一局域网 / 设备服务未开 | 检查 Wi-Fi、AScript App「开发者 → 设备服务」 |
| USB 连不上 | 未见 `adb devices` 的 `device` 状态 | 打开 USB 调试并确认手机上的 ADB 授权弹窗 |
| 连上但点击无效 | 运行模式与点击通道不匹配 | 查 `run_mode.code`；HID 通道需先确认可用 |
| 多设备混淆 | 未确认目标 | 先显示设备标识让用户确认，再 `connect_device` |
