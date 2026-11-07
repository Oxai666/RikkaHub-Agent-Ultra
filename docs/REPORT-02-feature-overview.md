# 报告二：RikkaHub-Agent-Ultra 功能全景

> 版本：2.5.1 / versionCode 187（包名 `excp.rikkahub`）
> 平台：Android 8.0+（API 26），targetSdk 37，arm64 / x86_64
> 定位：**把手机本身当成一个可被 AI 调用的操作系统**

---

## 一句话

上游 RikkaHub 是"能聊天的 App"，这个版本是**能替你操作手机的智能体**：能看屏幕、能点能输入、能开浏览器抓包、能跑 Linux 命令、能连你的服务器、能定时干活、能从 Telegram 远程指挥，全部可选、默认关闭。

---

## 能力地图

| 领域 | 能干什么 | 默认 |
|---|---|---|
| 手机控制 | 点按滑、输入、截图、开应用、调亮度音量、通知、读通讯录短信、NFC、Keystore | 关 |
| 屏幕理解 | 读窗口树、找控件、截图识别 | 关 |
| 浏览器 | 内置完整浏览器，AI 可导航、抓包、下断点调试 | 关 |
| 联网搜索 | 19 个引擎，免密钥开箱可用 | 开 |
| 网页抓取 | `web_fetch` / `web_extract`，长文自动分页 | 开 |
| Linux 工作区 | 真 Linux 环境，跑命令、改文件、后台常驻任务 | 配好后开 |
| SSH | 连自己的服务器，跑命令、传文件、起服务 | 关 |
| 工作流 | 19 种触发 + 14 种条件，说一句话就建 | 关 |
| 定时任务 | 任意 cron，重启和电池优化都不丢 | 关 |
| 子智能体 | 136 个内置角色，可指定便宜模型并行跑 | 关 |
| 技能 | Markdown 写一页就是一套新玩法 | 内置 2 个 |
| MCP | 接 MCP server，白拿它的工具 | 关 |
| Telegram | 远程指挥，发图发文件发语音 | 关 |
| 语音 | 说话输入，可选朗读回复 | 关 |
| 本地模型 | 下载 Gemma/Qwen 在手机上跑，不联网 | 关 |
| 图像生成 | 走 OpenRouter 的图像模型 | 关 |
| 记忆 | 跨会话长期记忆，可自动整理 | 关 |
| 上下文压缩 | 超阈值自动摘要旧对话 | 开 |
| 破甲 | 被拒或被截断时自动注入纠正/续写 | 关 |
| 用量统计 | 上下文占用、缓存命中、花费、速度、时长 | 开 |

---

## 一、手机控制（80+ 工具）

### 1.1 屏幕

| 能力 | 说明 |
|---|---|
| 读屏幕 | `read_window_tree` 读无障碍窗口树拿到控件树、`find_node` 按文本/ID 找控件、`take_screenshot` 截图 |
| 操作 | `tap` `longPress` `swipe` `clickNode` `setText` `scroll` `globalAction` `wakeScreen` |

**权限模型分两层**：屏幕操作（点按滑）和屏幕读取（截图/窗口树）是两个独立开关，只给操作不给读取也能用。

### 1.2 设备状态与设置

电池、音频状态、电话信号、WiFi、传感器、存储空间、手电筒、振动、亮度、音量、定位、剪贴板、Toast 通知、分享。

### 1.3 内容读取

通讯录、通话记录、短信收件箱、发短信、拍照、录音、语音转文字、指纹验证、媒体扫描、下载。

### 1.4 应用与通知

`app_launcher` 启动应用、`notification` 发通知、`notification_listener` 读通知并转发、`telegram_bot` 远程控制 bot。

### 1.5 特权通道

| 方式 | 说明 |
|---|---|
| **Shizuku** | 装了 Shizuku 就能以高于普通应用的权限跑 shell，不用 root、不用 Termux。可授权、改系统设置、看其他包 |
| **Termux** | 通过 Termux 跑命令，能起会话、长任务 |
| **Root** | KernelSU 环境下可用 |
| **无障碍** | 屏幕自动化底层 |

三者都过 HARDLINE 底线：`rm -rf /`、`mkfs`、关机、fork bomb 这类无条件拦截，连"永远允许"也绕不过。

---

## 二、聊天基础能力

| 功能 | 说明 |
|---|---|
| 多供应商 | OpenAI / Gemini / Claude / DeepSeek / OpenRouter / Codex(OAuth) / Grok(OAuth) / Ollama / 任何 OpenAI 兼容端点 |
| 多助手 | 每个助手独立 prompt、模型、温度、工具集、记忆、背景 |
| 分支 | 消息可分支、编辑、回填、删除、复制 |
| 排队 | 生成中发的消息排队，按序送达不打断 |
| 重试 | 流式失败自动重试，带状态提示 |
| 语音模式 | 语音输入 + 可选语音回复 |
| 附件 | 图片/文档/PDF/音频，发送前可预览 |
| 提示词注入 | PromptInjection / Lorebook / 模式注入 |
| 模板 | 自定义消息模板、占位符 |
| 多语言 | 简中、繁中、英、日、韩、俄、阿（RTL 正确渲染） |
| 无障碍 | 全界面 TalkBack 可导航 |

---

## 三、内置浏览器

### 3.1 能干什么

- **AI 自己开网页干活**：点 cookie 横幅、填搜索框、滚动、读正文回来
- **动作轨迹**：页面上方实时显示每一步和成败，可中途 Stop
- **截图回传**：每步把最新截图推到聊天里
- **悬浮小窗**：不离开页面继续和 AI 对话
- **多标签**：最多 8 个逻辑标签
- **独立起始页**：必应/百度/搜狗/Google 一键切

### 3.2 抓包与调试（这是最硬的部分）

| 能力 | 工具 |
|---|---|
| 拦截 | `browser_hook_create` —— 10 类目标（fetch / XHR / WebSocket / function / property / storage / cookie / console / timer / crypto）× 7 种动作（log / block / redirect / mock / modify_headers / replace / fake_value） |
| 抓包 | `browser_network_list` —— 含请求体和响应体，256KB 截断 |
| 控制台 | `browser_console_list` —— log/warn/error 全量，不依赖任何 hook 规则 |
| 证据库 | `browser_evidence_list` —— 落盘，重启后还能翻 |
| CDP | `browser_debug_attach` 起，断点/单步/调用栈/eval 全套 |
| 元素快照 | `browser_snapshot` —— 把可交互元素列成 `{ref, tag, text, selector, 坐标}` |

**38 个工具**，写类默认关闭（"共浏览"是独立闸门）。

---

## 四、联网搜索与抓取

| 项 | 说明 |
|---|---|
| 引擎 | 19 个：内置 DuckDuckGo（免密钥）、Tavily、Exa、Brave、Perplexity、Jina、Firecrawl、SearXNG、Bing、Serper、Ollama、DeepSeek、豆包、智谱、Grok、LinkUp、Metaso、Bocha，外加自定义脚本引擎 |
| 抗封 | 熔断器：被反爬时给可重试的明确错误，不是静默"无结果" |
| 抓取 | `web_fetch` 按响应字符集解码、长文分页；`web_extract` 提取正文 |
| 安全 | 30 秒超时、响应体有界、DNS 层拦截私网地址 |

---

## 五、Linux 工作区

手机上的真 Linux（PRoot）。

| 能力 | 工具 |
|---|---|
| 跑命令 | `workspace_shell` |
| 读写改文件 | `workspace_read` / `workspace_write` / `workspace_patch` |
| 目录 | `workspace_list` / `workspace_mkdir` |
| 后台常驻 | `workspace_run_background` 起 dev server / 安装 / watcher，拿 task id；`workspace_background_status` 查输出；`workspace_background_kill` 停 |
| 文件管理 | 内置文件管理器：文本编辑、图片缩略图、视频预览、HTML/SVG 渲染预览 |
| 导入导出 | 系统选择器导入（单文件 ≤256MiB）、整目录导出 |

任务 id 按工作区隔离，删工作区会杀掉它起的所有进程。

---

## 六、SSH

存一次服务器，之后：

- 跑命令、管道输入、写远程文件
- 传文件、拉备份
- 起长任务返回 PID 而不是挂住
- 查磁盘、看日志

WiFi 和流量都能用。

---

## 七、工作流与定时任务

### 7.1 工作流

一句话建："到家就把铃声关掉"。

| 项 | 数量 |
|---|---|
| 触发器 | 19 种：WiFi、蓝牙、耳机、地理围栏、应用启动、通知、时间、充电、屏幕状态等 |
| 条件 | 14 种：电量阈值、日出日落、星期几、前台应用、屏幕状态等 |

接收器只在需要时注册，不常驻耗电。

### 7.2 定时任务

- 任意 cron：每周一 8 点、每两小时、下周五 3 点
- 重启、省电模式都不丢
- 可以让 AI 运行时现想，也可以预烤固定动作（不烧 token）
- 执行历史有保留策略，防重复执行和重放

---

## 八、子智能体

**136 个内置角色，9 个部门**：工程 59、游戏开发 21、specialized 14、安全 12、测试 9、设计 6、空间计算 6、产品 5、项目管理 4。

| 能力 | 说明 |
|---|---|
| 派发 | `subagent_dispatch` 按名字或关键词派给角色，在干净上下文里跑 |
| 指定模型 | 派发时可指定模型，杂活丢给便宜模型 |
| 并行 | 多个同时跑，结果各自汇总回来 |
| 取消 | `/stop` 一 tick 级联取消所有子任务 |
| 写租约 | 子智能体只能写指定路径前缀，越界拒 |
| 交付校验 | 子智能体必须说明"改了哪些文件、该跑什么命令验证"，主模型核对 |

也可以自己定义 profile：名字 + 系统提示词 + 模型，之后按名字派。

---

## 九、技能系统

一个 Markdown 就是一套新玩法。内置 17 个技能包（237 个 md）：

```
agent-core               常驻的智能体行为准则（默认开）
auto-reply               自动回复
autonomous-agent         自主循环
calculate-hash           哈希计算
ctf-kb                   CTF 知识库（Web/云安全/密码学/逆向/二进制/取证…）
eight-honors             八荣八耻
interactive-map          交互地图
kitchen-adventure        厨房冒险
mood-tracker             心情记录
morning-briefing         早间简报
notification-summarise-and-act  通知摘要并执行
openclaw-converter       OpenClaw 转换（默认开）
qr-code                  二维码生成
query-wikipedia          维基查询
smart-forward            智能转发
src-bughunt              源码审计规则集（49 个知识库 + 9 条规则）
text-spinner             文本轮播
virtual-piano            虚拟钢琴
```

支持从 URL 导入、Zip 导入、分享 md 进来自动识别。

---

## 十、MCP

接 Model Context Protocol server，AI 白拿它暴露的工具。AI 自己也能加、改、删 MCP 连接——每次改动都要批准。

---

## 十一、记忆与上下文

| 项 | 说明 |
|---|---|
| 长期记忆 | 跨会话记住事实，可按助手隔离或全局共享，可自动整理 |
| 上下文压缩 | 用量过阈值自动摘要旧对话，保留最近的工具调用和结果；摘要写在旁边不覆盖原文 |
| 手动压缩 | 随时可触发 |
| 预算规划 | `ContextBudgetPlanner` 按模型的 `contextLength` 算可用输入 |
| 用量面板 | 上下文占用条、缓存命中率、花费、tok/s、纯生成时长 |

**上下文长度**可在模型编辑里设 128K / 200K / 1M / 自定义，直接影响上面这些。

---

## 十二、远程与自动化

| 通道 | 说明 |
|---|---|
| Telegram Bot | 发问题、图片、PDF、语音；批准用 Yes/No 按钮；多选题直接可点；长消息转文件；限流打散；支持 SOCKS5/HTTP 代理 |
| 外部 Intent API | Tasker / 自动化工具 / ADB 可以把任务丢给智能体 |
| 通知触发 | 白名单应用的通知可读、可摘要、可转发；点通知能深链回对应对话 |
| Web 服务 | 内置 HTTP 服务，可在浏览器访问对话 |

---

## 十三、本地模型与生成

| 项 | 说明 |
|---|---|
| 本地 LLM | 下载 Gemma / Qwen 等在手机上跑，GPU 加速 |
| AICore | Pixel 8/9/10 可用 Gemini Nano |
| 图像生成 | OpenRouter 图像模型（FLUX.2 / Seedream / Recraft / Qwen Image / gpt-image），支持比例和参考图 |
| 图像编辑 | 支持 edit 流程 |
| 语音 | TTS 朗读、ASR 转写 |
| 视频生成 | 有 videogen 模块 |

---

## 十四、破甲（本项目新增）

**作用**：模型拒答或被截断时，自动在下一步注入纠正/续写指令。

| 触发 | 条件 |
|---|---|
| 正文拒答 | 开头 240 字（有推理链）/ 2000 字（无推理链）命中 8 组正则 |
| 推理链拒答 | 推理尾部 4000 字命中 |
| 兜底接管 | "改为授权靶场""只提供防御""替代方案""建议咨询专业人士" |
| 安全话术苗头 | `content policy` / `guardrail` / 不宜展开 等 |
| 上游截断 | `finishReason` ∈ refusal / safety / content_filter / blocked / guardrail / policy_violation（120s 内有效） |

**防误伤**：否定辖域（命中点前 24 字有"严禁/不给/不要"则不算）、窗口分层、单会话 20 次上限。

**开关**：助手设置里的「开启辅助破甲」，默认关。

---

## 十五、健康检查（Doctor）

全量体检：权限、后台服务、数据库完整性、网络、Termux、诊断，外加 Shizuku、MCP、子智能体 profile、技能、图片库、工作区、Telegram 代理、上下文压缩。

工具组总览会告诉你每个依赖后端的组（屏幕自动化、应用启动器、智能体键盘、通知监听、Termux、Shizuku、文件、浏览器）是否在用、后端是否就绪。

一键自动修：授权、重启服务、重建搜索索引。Telegram 上 `/doctor` 也能跑。

---

## 十六、安全边界

三层：

1. **每助手开关** —— 所有工具默认关，只开你要的
2. **每次调用批准** —— 有副作用的工具默认要批
3. **HARDLINE 底线** —— 真正危险的操作无条件拦截

另外：密码和 API Key 不进日志；云备份跳过已保存的凭据；Telegram bot 只认白名单；网页抓取在 DNS 层拒绝私网地址。

---

## 十七、完整工具开关清单（57 个）

```
基础      JavascriptEngine  TimeInfo  Clipboard  Tts  AskUser
设备状态   Battery  AudioInfo  TelephonyInfo  WifiInfo  Sensors  StorageInfo
交互      Toast  Notification  Share  Torch  Vibrate  Brightness  Volume
媒体      MediaPlayer  MediaScanner  Download
内容      Location  Contacts  CallLog  SmsInbox  CameraPhoto  MicRecorder  SpeechToText
安全      Fingerprint  Keystore
自动化    CronJobs  ScreenAutomation  ScreenVision  AppLauncher  NotificationListener
连接      Ssh  Shizuku  Termux  TelegramBot  WebFetch
浏览器    38 个（见第三章）
工作区    workspace_* 系列
技能      skill_* 系列
子智能体  subagent_* 系列
MCP       由接入的 server 决定
```

---

## 十八、怎么开

1. **设置 → 供应商 → 选一个 → 填 Key**（或用 OAuth 登录 Codex/Grok，或下本地模型）
2. **设置 → 助手 → 点进你的助手 → 本地工具 → 按类别打开**
3. 可选：
   - Telegram：找 @BotFather 建 bot 拿 token，找 @userinfobot 拿 user id，然后跟 AI 说"配好这个 bot"
   - 工作区：建一个工作区，等 shell 就绪
   - SSH：存一次服务器
   - 子智能体：设置 → 子智能体，挑角色或建 profile

什么都不开的话，它就是一个干净的聊天客户端。

---

## 十九、和官方 RikkaHub 的关系

| 来源 | 内容 |
|---|---|
| 官方 RikkaHub | 聊天、多供应商、分支、记忆、主题、技能框架、MCP、数据库 |
| ExTV/rikkahub-agent | 上面全部 + 80+ 设备工具、工作流、定时任务、浏览器、工作区、SSH、Telegram、语音、本地模型、Doctor、上下文压缩、子智能体 |
| **本项目（Ultra）** | 上面全部 + **136 角色库 + 写租约 + 交付校验**、**浏览器 hook/CDP/证据库**、**破甲闭环**、**DeepSeek 搜索**、**重写的用量面板**、**官方 3.1.1 的思考深度与上下文长度 UI**、**视频背景**、**自有 CI 与 release 构建** |

---

# 追加：2026-11-07 新增的四项能力

## 二十、Root 权限

比 Shizuku 高一档的 shell 通道。

| 项 | 说明 |
|---|---|
| 探测 | 扫常见 su 路径（含 KernelSU / Magisk 专属位置），再跑一次 `id -u` 核对 |
| 执行 | `root_shell` 工具，命令走 `su -c`，超时 1~300 秒可调 |
| 自定义 su | 支持 `su`、`su -mm` 前缀形式，也支持 `su -mm -c {command}` 模板形式（`{command}` 自动做 shell 转义） |
| 安全 | 每次调用都要批准 + HARDLINE 底线（`rm -rf /`、`mkfs`、fork bomb 无条件拦） |
| 入口 | 设置 → Root（终端风格页：状态圆点、等宽 su 路径、测试输出） |

## 二十一、虚拟屏幕

在手机里额外挂一块显示器，可以投应用进去、实时看、直接点。

| 能力 | 说明 |
|---|---|
| 两种模式 | **独立空屏**（默认，只显示投进去的东西）/ **镜像当前屏幕** |
| 投应用 | 传包名自动找启动 Activity，或经 `am start --display N` 手动投 |
| 实时画面 | helper 每 66ms 推一帧 JPEG，约 15fps |
| 全屏大窗 | 实时画面 + 点击/滑动转发到虚拟屏 |
| 悬浮小窗 | 浮在别的应用上面；顶部白条拖动、点白条出关闭按钮、画面区点/滑透传 |
| AI 操作 | `virtual_screen` 工具的 tap / swipe，坐标是虚拟屏像素 |
| 自愈 | 连续 3 次抓不到帧自动重建 display 并重投上次的应用 |
| 保活 | `nohup setsid` + `oom_score_adj=-1000`；App 被划掉后看门狗 20 秒内退出 |
| 停止清理 | 先杀投送的应用和该 display 上的 task，再销毁 display，避免 Activity 被迁回主屏 |

**前置要求**：root（`CAPTURE_VIDEO_OUTPUT` 是系统权限）。

## 二十二、压缩对话 + 历史检索

长对话压缩时，摘要会丢细节，所以额外存一份完整原文供 AI 回查。

### 配置（每个助手独立）

| 项 | 含义 |
|---|---|
| 压缩大小 | 压缩后把上下文缩到多少（千 tokens，留空用全局） |
| 压缩阈值 | 上下文超过多少才开始压（千 tokens，留空用全局） |
| 保留消息 | 压缩后最近多少条原样保留 |
| 附加说明 | 追加到摘要提示词，比如「保留所有命令和路径」 |
| 自动压缩 | 超阈值自动触发 |

### 存档

```
files/compaction/<助手名>__<id8>/<会话名>__<id8>/
  ├── _state.txt                      记录上次存到哪条消息
  ├── 压缩会话1 2026-11-07_08-04-57.md
  └── 压缩会话2 2026-11-07_09-12-03.md  ← 只含上次之后的新消息
```

- **只存增量**：压第二次不会把 1、2 再存一遍
- **格式**：`## 用户` / `## 助手` 分段，思考用 `>` 引用；图片只写 `[图片]`、附件只写 `[文件]`，不带 base64 也不带提示词
- **隔离**：目录带会话 id，`history_search` 强制限定当前会话，看不到别人的
- **清理**：删会话按 id 删对应目录，删助手清整个助手目录

### 检索

`history_search` 工具：

| action | 作用 |
|---|---|
| `list` | 列出本会话的存档（序号 / 时间 / 大小） |
| `search` | 在第 N 份里搜关键词（每次最多 12 条、每条 200 字符） |
| `read` | 读第 N 份（截断 12K 字符，带「下次优先用 search」的提示） |

**强制流程**：`search` / `read` 必须带 `index`，缺了直接拒绝，要求模型先列出清单、**问用户要检索哪一份**。这样避免一次性把所有旧上下文灌回来把窗口撑爆。

## 二十三、文件上传多样化

原来只认文本 / 代码 / Office / PDF 白名单，现在任意文件都能传。

| 类型 | 处理 |
|---|---|
| 文本、代码、配置 | 内容内联给模型 |
| 压缩包（zip/7z/rar/tar/jar/apk…） | 复制进聊天目录 + **额外写入 Linux 沙箱 `/workspace/upload/`**，挂隐藏提示说明路径和解压命令 |
| 二进制（so/dex/db/bin/img…） | 同上，提示改成「不能内联，用工具读」 |
| 其它未知后缀 | 兜底同上 |

隐藏提示用 `metadata = {"hidden": true}`：**界面上你只看到文件，模型能看到文件 + 说明**。

## 二十四、其它改动

| 项 | 内容 |
|---|---|
| 用量面板 | 步数 / 上下文占用条 / 缓存命中 / 花费 / 纯生成耗时都能显示 |
| 思考深度 | 滑块 + MaterialShapes 形状连续变形（移植官方 3.1.1） |
| 上下文长度 | 128K / 200K / 1M / 自定义 |
| 聊天背景 | 支持视频（静音循环、透明度跟随滑块） |
| 循环守卫 | `virtual_screen` 的观察类 action 豁免，不再误判死循环 |

---

## 二十五、权限说明（截至 2026-11-07 回退后）

虚拟屏幕只支持 **root（su）** 一种通道。

曾经尝试过两条降级路线，都已回退：

| 路线 | 卡在哪 |
|---|---|
| Shizuku | 能启动 helper，但 App 和 helper 分属不同 network namespace，TCP 连不上 |
| ADB 直连 | 协议层已完成，但 Android 11 无线调试的 SPAKE2 配对成本过高 |

回退后的行为：没有 root 时直接给出明确错误，不再尝试降级路径。
