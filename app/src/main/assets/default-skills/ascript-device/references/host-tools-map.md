# 路径 B：万象 host 工具 ↔ AScript MCP 对照表

万象在 Android 宿主侧自带一套能力（`host` 工具），**不需要安装任何东西、不需要开设备服务**，
可以直接操作**本机**（也就是这台手机）。它不能连别的设备 —— 这是与 AScript 的关键差别。

## 对照表

| AScript MCP 工具 | 万象 host 等价能力 | 说明 |
|---|---|---|
| `screen_capture` | `screen_capture` | 都是整屏截图（host 版存 PNG 到指定路径） |
| `dump_ui_tree` | `screen_observe` | 拿控件/节点树，host 版返回前台应用 + 节点树 |
| `test_selector` | ❌ 无直接等价 | host 是坐标模型，没有选择器引擎；需自己在节点树里找坐标 |
| `click` / `long_press` | `screen_click` / `screen_double_click` / `screen_long_press` | 手势，按坐标 |
| `swipe` / 滑动 | `screen_swipe` / `screen_scroll` | 支持方向与幅度 |
| `input_text` | `screen_input_text` / `paste_text` | 中文输入走剪贴板粘贴更可靠 |
| 返回/Home/最近任务 | `screen_key`（`back` / `home` / `recents` / `enter` …） | 导航键 |
| `get_run_log` | `logcat` | 抓指定包名日志，可过滤 tag / 关键词 / 优先级 |
| `get_device_status` | `device_status` | 电量、网络、前台应用、存储摘要 |
| 启动某个 App | `app_launch`（包名）/ `am start -n` | host 侧 |
| `list_projects` | `package_list` / `app_list` | 应用清单 |
| `create_project` / `upload_file` | `write` / `base`（沙箱文件系统） | 工程写到沙箱，再由你决定如何部署 |
| `eval_python` | `base`（沙箱 `python3`） | ⚠️ 注意：这是**沙箱**里的 Python，不是设备端 REPL，跑不到设备的 AScript API |
| `scan_devices` / `connect_device` | ❌ 无（只能控本机） | host 没有跨设备连接能力 |
| `ocr` / `find_colors` / `compare_colors` | ❌ 无内置 | 可在沙箱里用 Python(cv2/PIL) 对截图做识别 |
| 虚拟屏隔离运行 App | `virtual_screen_*` 系列 | 在独立虚拟屏启动第三方应用，**不影响主屏**，适合做自动化实验 |

## 怎么选

- **只要操作这台手机**（点、滑、输、看、抓日志）→ 直接用 host，最省事。
- **要跨设备 / 要选择器引擎 / 要设备端 Python REPL / 要 OCR 找图找色的成熟封装** → 用 AScript（路径 A）。
- **两者混用**是允许的：host 负责本机的手眼，AScript 负责设备侧脚本工程。

## 用 host 复刻官方工作流

官方那套「先观察 → 选策略 → 小步验证 → 运行 → 看日志」在 host 上同样成立：

1. **观察**：`screen_observe` 拿前台应用与节点树；不够就 `screen_capture` 截图。
2. **选策略**：能从节点树稳定定位就直接点坐标；canvas/游戏类再上截图识别。
3. **小步验证**：先点一下、看 `screen_observe` 有没有变化，再继续。
4. **执行**：连续操作，注意每次操作后重新观察（坐标会变）。
5. **看结果**：`logcat` 或再次 `screen_observe` 确认达到了目标页面 —— 不要只凭"我点了"就报成功。

## 关键差异（避免误用）

| 维度 | AScript | 万象 host |
|---|---|---|
| 作用域 | 局域网/USB 上的任意设备 | 仅本机 |
| 定位方式 | 选择器 + OCR + 找图找色 + 坐标 | 主要靠坐标 + 节点树 |
| 代码执行 | 设备端 Python REPL（可调 AScript API） | 沙箱 Python（**不**等于设备端） |
| 前置依赖 | 装 MCP 包 + 开设备服务 | 无 |
| 适用 | 复杂、可复用、要跨机的自动化工程 | 快速、本机、一次性或轻量任务 |
