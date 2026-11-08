# 报告一：RikkaHub-Agent-Ultra 相对上游的完整改动

> **基线**：`ExTV/rikkahub-agent` @ `a88f5854`（2.5.1-agent.1，versionCode 187）
> **本仓库**：`Oxai666/RikkaHub-Agent-Ultra` @ `4a0ccb56`
> **对比方式**：`git diff up/master HEAD`
> **统计时间**：2026-11-06

---

## 一句话总结

上游是"能操作手机的聊天客户端"，本仓库把它扩成了**带角色库、带浏览器调试链、带破甲闭环**的本地智能体工作台：新增 4 个完整子系统、重写 3 处 UI、改了 7 处上游行为，其余一行未动。

---

## 总览

| 指标 | 数值 |
|---|---:|
| 变更文件 | 488 |
| 新增代码 | +87,061 |
| 删除代码 | −472 |
| 新增文件 | 416 |
| 修改文件 | 67 |
| 删除文件 | 5 |
| 新增 Kotlin 文件 | 48 |
| 修改 Kotlin 文件 | 43 |
| 新增 assets | 136 个角色 md + 237 个技能 md + hook 运行时 |

---

## 一、四个全新子系统

### 1.1 子智能体系统（`subagent/`）

上游只有"一个助手 = 一套 prompt + 一堆工具"的单线程模型，没有子智能体概念。

**代码（7 个文件）**

| 文件 | 作用 |
|---|---|
| `BuiltinSubAgents.kt` | 从 assets 加载 136 个角色，按部门索引，5 级降级路由 |
| `SubAgentRegistry.kt` | 角色查询：精确名 / 2–5 关键词模糊匹配 |
| `SubAgentEngine.kt` | 一次独立运行：干净上下文、独立模型、独立超时 |
| `SubAgentTools.kt` | 暴露 `subagent_dispatch` / `list` / `get` / `cancel` / `roles` |
| `SubAgentRun.kt` | 运行状态机（排队/运行/完成/取消） |
| `SubAgentClaim.kt` | **取证式交付校验**：子智能体必须声明"改了哪些文件、该跑什么命令验证"，主模型逐条核对 |
| `SubAgentWriteLease.kt` | **写租约**：限定可写路径前缀，越界直接拒绝 |

**角色库**：136 个 `.md`，9 个部门

```
engineering 59 · game-development 21 · specialized 14 · security 12
testing 9 · design 6 · spatial-computing 6 · product 5 · project-management 4
```

来源 `msitarzewski/agency-agents` @ 3c95888（MIT），catalog 64KB，正文按需读、读过缓存。

**UI**：`SettingSubAgentsPage.kt`（+187 行），复用现有设置页骨架，不额外造界面。

---

### 1.2 浏览器运行时（`browser/`）

上游完全没有浏览器。这里移植了整条链路（源自 `wkbin/taixu`，GPL-3.0）。

```
browser/              42 个 Kotlin 文件
├── cdp/              15 个 — Chrome DevTools Protocol
├── hook/              8 个 — 页内注入式拦截引擎
└── inject/            1 个 — 脚本注入兜底
```

**38 个浏览器工具**（`BrowserToolDefaults.ALL_TOOLS`，写类默认关闭）

| 层 | 工具 |
|---|---|
| 导航 | `browser_open` `browser_back` `browser_forward` `browser_done` |
| 操作 | `browser_click` `browser_type` `browser_scroll` `browser_select` `browser_press_key` `browser_submit` `browser_wait_for` `browser_click_and_read` |
| 读取 | `browser_get_text` `browser_get_dom` `browser_get_links` `browser_screenshot` `browser_snapshot` `browser_current_url` |
| 抓包 | `browser_network_list`（含请求/响应 body）`browser_console_list` |
| Hook | `browser_hook_create` `browser_hook_list` `browser_hook_hits` `browser_hook_remove` |
| 证据库 | `browser_evidence_list` `browser_evidence_clear`（落盘，重启可查） |
| 调试 | `browser_debug_attach` `browser_debug_detach` `browser_debug_status` `browser_debug_targets` `browser_debug_eval` `browser_debug_set_breakpoint` `browser_debug_remove_breakpoint` `browser_debug_list_breakpoints` `browser_debug_state` `browser_debug_step` `browser_debug_resume` |
| 兜底 | `browser_eval_js` |

**hook 拦截能力**：10 类目标（fetch / XHR / WebSocket / function / property / storage / cookie / console / timer / crypto）× 7 种动作（log / block / redirect / mock / modify_headers / replace / fake_value）。

**其它**：
- 多标签：单 WebView + 最多 8 个逻辑 tab
- 浏览器内 AI 小窗（`BrowserMiniChat`，去掉了自动滚动）
- 起始页 `newtab.html`（必应/百度/搜狗/Google 切换，存 localStorage）
- 页内运行时 `hook_runtime.js`（40KB）

---

### 1.3 armor 破甲链路（`data/ai/armor/`）

4 个文件、584 行，从原插件逐字移植，含 4 个单测。

| 文件 | 作用 |
|---|---|
| `ArmorJudge.kt` | 判定：11 组拒答正则 + 3 组兜底正则 + 8 组安全话术，带否定辖域过滤 |
| `ArmorProvider.kt` | 流式只读监看装饰器：不改编 chunk、不断流、异常吞掉只记状态 |
| `ArmorTransformer.kt` | 注入器：把指令拼进**最后一条 USER 消息内部**，不新增消息 |
| `ArmorState.kt` | 每会话状态：判定 / 纠正次数 / 截断信号（120s TTL） |

**触发链**：流式监看（每 chunk 判定）→ 判定写入会话状态 → **下一步**组装消息时读判定 → 注入 `强制接受指令` 或 `续写指令`。

**关键开关**：`assistant.armorEnabled`，默认关闭（上游无此概念，我们的 fork 依赖包也是常开的，见下文差异）。

**上限**：单会话最多 20 次纠正。

---

### 1.4 DeepSeek 联网搜索（`search/`）

在既有 search 模块中新增 `DeepSeekSearchService.kt`（194 行），并扩展 `SearchService.kt`（+15 行）。

---

## 二、UI 与体验的新增与重写

### 2.1 用量面板（重写，389 行）

**数据侧**：`UIMessage` 新增三个字段并真正填上值

```kotlin
val generationTimeMs: Long? = null   // 每步纯生成耗时累加
val lastUsage: TokenUsage? = null    // 最后一次请求的原始用量
val stepCount: Int? = null           // 请求次数
```

| 字段 | 写入点 | 解决的问题 |
|---|---|---|
| `lastUsage` | `StreamChunkHandler` 的 `Usage` 分支 | 多步时 `usage` 是累计值，上下文占用会虚高好几倍 |
| `generationTimeMs` | `GenerationLoop` 每步流结束后累加 | 原先用 `createdAt→finishedAt` 估算，把工具执行时间也算进去 |
| `stepCount` | 同上，每步 +1 | 原先永远 null，多步工具循环看不出来 |

**UI 侧**：`ChatMessageNerdLine.kt` 重写

- 行内：`步数 / tok/s / 上下文（已缓存）`
- 点击展开 `RichTooltip`：三段式上下文占用条（缓存命中 / 未命中输入 / 输出 + 剩余空白）
- 明细：步数、总输入、缓存总量、总输出、花费、速度、时长
- 百分比：10% 以下保留一位小数（1M 窗口下 3.5K 显示 `0.4%` 而非 `0%`）

### 2.2 思考深度选择器（移植官方 3.1.1）

| | 改动前 | 改动后 |
|---|---|---|
| 主视图 | 滑块 + 七个图标硬切 | MaterialShapes 形状连续变形（Circle → Cookie4Sided → Cookie6Sided → Cookie7Sided → Cookie9Sided → Cookie12Sided → SoftBurst） |
| 档位文字 | 静态 | 切换时上下滚动 |
| 拖动 | 松手才看到变化 | 实时预览 |
| 触觉 | 无 | 每跨一档 `SegmentTick` |

新增 `PickerHeader.kt`（247 行，含 `PickerHeader` / `PickerValueHeader` / `PickerHero` / `SheetHeader`），`ReasoningPicker.kt` 整体替换。

**适配**：官方用 material3 `1.5.0-beta02`，本项目是 `1.5.0-alpha27`，Slider 那套 API 不同，已按 alpha27 源码签名改写。

### 2.3 上下文长度选择器（移植官方 3.1.1）

模型编辑页新增「上下文长度」：**128K / 200K / 1M / 自定义**，自定义态展开数字键盘输入框。

新增 `ConnectedChoiceButtons.kt`（连接式单选/多选按钮组：选中项加宽 1.3 倍 + 形状连接）。

**联动四处**：用量面板占用条分母、`ContextBudgetPlanner`、`ContextCompactionPlanner`、自动压缩阈值。

### 2.4 聊天背景支持视频

| 项 | 做法 |
|---|---|
| 选择 | 弹窗新增「选择视频」，走 `video/*` |
| 分流 | 按扩展名判断（mp4/webm/mkv/mov/m4v/3gp/avi/ts），文件名由 FilesManager 复制时保留扩展名 |
| 渲染 | ExoPlayer + **TextureView**（PlayerView 默认 SurfaceView，不参与 Compose 的 alpha 合成，背景透明度会失效） |
| 缩放 | `AspectRatioFrameLayout` + `RESIZE_MODE_ZOOM`，等价于图片的 Crop |
| 播放 | 静音、单曲循环、`ON_START` 播 / `ON_STOP` 停、离开页面 release |

### 2.5 品牌与关于页

- 应用图标：黑底白鲸，5 密度 × 4 张（legacy / foreground / background / monochrome）
- 关于页：官网、GitHub、License 全部指向 `Oxai666/RikkaHub-Agent-Ultra`；标题名改为读取 `R.string.app_name`

---

## 三、对上游既有行为的修改

这一节是**行为差异**，不是新增代码。

| # | 位置 | 上游 | 本仓库 |
|---|---|---|---|
| 1 | `SystemPromptBuilder.kt` | 硬编码整句工具成本提示，纯聊天助手的提示词里也会出现不存在的工具名 | **按实际注册的工具名逐条生成**，句子里出现的每个工具保证存在 |
| 2 | `LocalTools.kt` | `ScreenAutomation` 一个开关管 11 个工具（含截图、窗口树） | 拆成「屏幕自动化」+**「屏幕读取」**两项 |
| 3 | `LocalTools.kt` | `web_fetch` / `web_extract` 注释写明 `no per-assistant toggle` | 新增 `LocalToolOption.WebFetch`，变成 `全局开关 && 助手勾选` |
| 4 | `GenerationLoop.kt` | 观察类工具无 TTL 冷却 | 浏览器观察类 2 秒、`browser_open` 5 秒；新增 `ACTION_TOOLS_WITH_TTL` 避免有副作用的工具被算作观察类 |
| 5 | `BrowserMiniChat.kt` | 无（上游没有该 UI） | 新增，且**去掉自动滚动**，只在首次定位 |
| 6 | `AppModule.kt` | — | 新增 7 个单例：`BrowserEventBus` / `HookRuleStore` / `NetworkBodyStore` / `BrowserEvidenceStore` / `HookEventPipeline` / `HookInstaller` / `CdpManager` |
| 7 | `ConversationRepository.kt` | — | 删会话时清 `ArmorState`（原先 `clear()` 全仓零调用，纠正次数只增不减，20 次后本进程内永久熄火） |

---

## 四、工程能力

### CI（两个 workflow，均可手动触发）

| workflow | 作用 |
|---|---|
| `build-ultra.yml` | debug 构建：跑 armor 单测 → 出 APK → 挂 Release → 失败自动开 issue 贴 Gradle 日志 |
| `build-ultra-release.yml` | release 构建：自签 keystore、可选关 R8、出 release APK。用于区分 debug 包的解释执行卡顿 |

两个都只留 `workflow_dispatch`，改代码期间不自动构建。

### 测试

新增：`ArmorJudgeTest` / `ArmorProviderTest` / `ArmorStateTest` / `ArmorTransformerTest` / `BuiltinRoleSearchTest` / `BuiltinSubAgentsTest` / `SubAgentClaimTest` / `SubAgentWriteLeaseTest` / `ErrorParserRetryStatusTest` / `FileEncoderMimeTest`，以及后续的 `SubAgentCompletionWaitTest` / `SubAgentModelResolverTest` / `SubAgentProfileResolverTest` / `SubAgentRegistryTest` / `SubAgentRequestValidationTest` / `SubAgentTimeoutStopTest` / `SubAgentToolsEncodeRunTest`。

### 文档

`HANDOVER.md`（接手手册）、`TESTING.md`（测试清单）、`COMPARE-UPSTREAM.md`（旧版本对比）、本报告。

### 内置技能包（assets）

新增 `ctf-kb`（CTF 知识库）、`eight-honors`、`src-bughunt`（源码审计规则集），共 237 个 md。

---

## 五、一行没改的部分

- 供应商与模型适配（Claude / Gemini / OpenAI / DeepSeek / OpenRouter / Codex / Grok / Ollama 全部 provider）
- 数据库结构与迁移
- 会话 / 分支 / 记忆 / 工作区
- MCP 接入
- Telegram Bot
- 主题与大部分 UI
- 技能系统框架（只新增内置内容）
- 上下文压缩、Doctor、文件管理、SSH、音乐播放、语音转写、本地 LLM

---

## 六、已知限制

| 项 | 说明 |
|---|---|
| CDP 断点 | 部分 ROM（实测 MIUI + WebView 151）devtools socket 只 listen 不 accept，设备级限制，hook 链路不受影响 |
| 多标签 | 单 WebView + 逻辑 tab：切走不保留滚动/表单/SPA 状态，抓包事件不按 tab 隔离 |
| function hook | 声明型函数且"定义后立即同步调用"抓不到（setter 被 DefineOwnProperty 顶掉），赋值型正常 |
| MCP 工具 | 无助手级开关，配了 server 所有助手都会拿到 |
| 破甲延迟 | 判定在 step N 的流里，注入在 step N+1；模型拒答后若不调工具，要等下一个用户回合才补上 |
| 破甲盲区 | 非流式（`streamOutput = false`）走 `generateText`，完全不监看 |
| 视频背景文件 | 存在 `filesDir/upload/`，若清理机制按引用计数回收，可能被误删（待观察） |

---

## 附：模块结构

```
app/          主应用（958 个 kt）
ai/           模型与消息核心
common/       跨模块基础设施
search/       搜索（19 个引擎）
speech/       语音
workspace/    工作区
document/     文档
web/ web-ui/  网页端
llama-cpp/ local-llm/   本地推理
material3/ highlight/ oauth/ videogen/ locale-tui/ trace-cli/
```

---

# 追加：2026-11-07 的第二批改动

> 第一版报告写完后，又陆续加了四块新能力。这一节按提交顺序汇总，作为正文的补篇。

## A. Root 权限（`data/root/`）

| 文件 | 作用 |
|---|---|
| `RootShell.kt` | 探测 su（含 `/data/adb/ksu/bin/su`、`/data/adb/magisk/su`）、执行命令、8 秒探测超时、256K 输出上限 |
| `RootPreferences.kt` | 自定义 su 命令，存 SharedPreferences |
| `RootTool.kt` | `root_shell` 工具 |

**两种 su 写法**：

```
前缀模式（默认）  su / su -mm        → 解析成绝对路径 + ["-c", 命令]
模板模式          su -mm -c {command} → 整条交给 sh，{command} 做 shell 转义
```

**安全**：进 `ToolApprovalDefaults.ALWAYS_ASK`（每次批准）+ 过 `HardlineCommandGuard`（`rm -rf /` 无条件拦）。

**UI**：设置页「Root」，终端风格——大号状态圆点、等宽 su 路径、黑底绿字测试输出。

## B. 虚拟屏幕（`data/vscreen/`）

在手机里额外挂一块 VirtualDisplay，能投应用、能实时看、能点。

| 文件 | 作用 |
|---|---|
| `VscreenCore.kt` | app_process 入口：反射拿 system Context → createVirtualDisplay + ImageReader → TCP 服务 |
| `VirtualScreenManager.kt` | 启动/停止/截图/推流/投应用/触摸，状态持久化与自愈 |
| `VirtualScreenOverlay.kt` | 悬浮小窗（TYPE_APPLICATION_OVERLAY） |
| `VirtualScreenTool.kt` | `virtual_screen` 工具（start / launch / screenshot / tap / swipe / diagnose / status / stop） |
| `SettingVirtualScreenPage.kt` | 主控制台：实时画面舞台 + 状态点 + 操作条 + 投送区 |
| `VirtualScreenWindowPage.kt` | 全屏大窗：实时画面 + 点击/滑动转发 |

**关键技术点**：

- **必须另起进程**：`createVirtualDisplay` 要 `CAPTURE_VIDEO_OUTPUT`（system 级），普通应用拿不到，只能借 su 身份；`nohup setsid` + `oom_score_adj=-1000` 保活
- **flags**：`PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | OWN_FOCUS`，`OWN_CONTENT_ONLY` 决定是独立空屏还是主屏镜像
- **触摸注入**：走 `input -d <displayId> tap/swipe` 命令，不走 `InputManager.injectInputEvent`（Android 15 对 latter 的反射拦截很严，抛 InvocationTargetException）
- **系统 Context**：`Looper.prepareMainLooper()` + 手动构造 `ActivityThread` + 设 `mSystemThread`/`sCurrentActivityThread`，**不能调 `systemMain()`**
- **帧缓存**：静态页面只产生一帧，stream 消费掉后 screenshot 就永远拿不到，所以 helper 里留一份 `lastFrameJpeg`
- **停止顺序**：先 `am force-stop` 投送的包 + 扫 `mDisplayId` 摘 task，再销毁 display——否则 Activity 会被迁回主屏，用户被"跳"到那个页面
- **看门狗**：helper 每 20 秒 `pidof` 宿主包名，App 被划掉就自杀

## C. 压缩对话 + 历史检索（`data/compaction/`）

| 文件 | 作用 |
|---|---|
| `CompactionArchive.kt` | 存档：压缩前把完整对话写成 Markdown，只存增量 |
| `HistorySearchTool.kt` | `history_search` 工具（list / read / search） |
| `AssistantCompactionPage.kt` | 助手级配置页 |

**配置（Assistant 字段）**：

```
compactionTargetK       压缩后缩到多少（千 tokens）
compactionThresholdK    超过多少才触发（千 tokens）
compactionKeepMessages  保留最近多少条
compactionExtraInstruction 附加说明
compactionAutoEnabled   自动压缩开关
```

**存档结构**：

```
files/compaction/<助手名>__<id8>/<会话名>__<id8>/压缩会话<N> <时间>.md
```

**增量规则**：`_state.txt` 记住上次存到哪条消息，下次只存新的——压缩②只记录上次之后的消息，不重复 1、2。

**隔离**：目录名带 id 后缀，`history_search` 由 `invocationContext.callerConversationId` 强制限定当前会话，**模型看不到别的会话的存档**。

**上下文保护**：`search` 优先于 `read`（后者截断 12K 字符并带 warning），缺 `index` 的检索直接拒绝，要求先问用户检索哪一份。

## D. 文件上传多样化

- `isAllowedFileType` 全放行（原来白名单挡掉 .log / .zip / .bin）
- `isInlineReadableFile` 区分可读/不可读
- 读不了的（压缩包 / 二进制）额外写进 Linux 沙箱 `/workspace/upload/`，并挂一条 `metadata={"hidden":true}` 的提示给模型，界面上不渲染
- 提示里写明沙箱绝对路径，并要求「用沙箱工具读，别用 content:// URI」

## E. 其它

| 项 | 内容 |
|---|---|
| 循环守卫 | `virtual_screen` 的 screenshot/status/diagnose 按 action 豁免，不再误判死循环 |
| 用量面板 | 真正填充 `stepCount` / `lastUsage` / `generationTimeMs` 三个字段 |
| 思考深度 | 移植官方 3.1.1 的 MaterialShapes 变形选择器 |
| 上下文长度 | 移植官方 3.1.1 的 128K/200K/1M/自定义 |
| 聊天背景 | 支持视频（ExoPlayer + TextureView） |
| 图标/关于页 | 自有图标；关于页指向自己的仓库 |

---

## 补记：2026-11-07 的回退

曾在 `3d62bc48` ~ `20851589` 之间尝试过两条「降低权限门槛」的路线：

| 尝试 | 内容 | 结果 |
|---|---|---|
| Shizuku 兜底 | 让没 root 的设备用 Shizuku 起虚拟屏 | ❌ 能启动，但 App 与 helper 不在同一 network namespace，连不上端口 |
| ADB 直连 | 自实现 ADB 协议 + 密钥写入 adb_keys | ❌ 协议层做完了，但无线调试的 SPAKE2 配对成本过高，未继续 |

两条都已回退，当前 main 停在 `bbd9b8fb`。备份保留在：

```
分支 backup/adb-attempt  → 20851589（含 ADB 全套）
tag   backup-shizuku     → 3d62bc48（含 Shizuku 兜底）
```

**回退后的权限模型**：虚拟屏幕只走 root（su）。没有 root 时报明确错误，不再尝试降级。

---

# 追加：2026-11-08 的第三批改动

这一批的大头是**移植包**（从另一个 App 拆出来的三个模块）和**技能系统重做**。
共 57 个提交。

---

## F. 移植：安全层（`safety/`，新增 7 个文件）

来源是移植包里 44 个 Java 文件 15397 行反编译产物。不是翻译，是按语义重写成精简版。

| 文件 | 行数 | 干什么 |
|---|---:|---|
| `RiskModel.kt` | 165 | 三档分级 `A_READ / B_REVERSIBLE / C_IRREVERSIBLE`、6 种 dry-run 形态、3 种快照范围 |
| `ShellCommandRiskAnalyzer.kt` | 400 | 拆子命令 → 认命令名 → 挑目标路径 → 定档 |
| `RiskAssessor.kt` | 300 | 按工具类型分流：shell / 文件写 / 设置 / 界面 / 未知 |
| `ToolSafetyCoordinator.kt` | 90 | 统一卡口 + 审批文案 |
| `FileSnapshotManager.kt` | 270 | 快照的拍 / 恢复 / 列表 / 清理 |
| `SnapshotRoots.kt` | 90 | 快照放哪 + 沙箱路径 → App 路径的翻译 |
| `SnapshotTools.kt` | 130 | `snapshot_list` / `snapshot_restore` 两个工具 |
| `OperationAudit.kt` | 190 | 脱敏审计 + 落盘 |

**接入点**：`GenerationLoop` 里 `HardlineCommandGuard` 之后、审批之前。
顺序不能反——Hardline 是硬底线（永远拦），安全层是风险分级。

### 几个刻意的设计

**只读命令不判越界**。`cat /etc/os-release` 读一个文件不改变任何东西，读哪里都不该拦。

**沙箱场景放宽**。proot 里 `/usr/local`、`/opt` 是正常可写的，写坏了重建 rootfs 就行。
只有 `/system`、`/data/adb`、根目录才拦。真机 shell（`root_shell`）仍按工作区边界严格判。

**符号链接要解析**。`ln -s /etc /workspace/etc` 之后再写 `/workspace/etc/passwd`，
字面在工作区内、实际落到 `/etc`。先 `canonicalPath` 再判。

**重定向算写操作**。`echo x > file` 里的 `>` 之前不触发写入判定，
`echo` 在只读名单里一路 A 档过去，既不审批也不备份。

**快照路径要翻译**。安全层提取的是沙箱视角的 `/workspace/x`，
拍快照的代码跑在 App 进程，`File("/workspace/x")` 根本不存在。
`SnapshotRoots.resolveTarget` 负责把 `/workspace/x` 翻成 `filesDir/workspaces/<id>/files/x`。

### 单测

`ShellCommandRiskAnalyzerTest` 27 条：只读 / 危险 / 可撤销 / 复合命令 / 路径越界 / 未知命令，
外加 `AuditRedactor` 5 条脱敏用例。

---

## G. 移植：Plan 规划模式（`data/plan/`，新增 8 个文件）

| 文件 | 干什么 |
|---|---|
| `CollaborationMode.kt` | default / plan 两态 |
| `ToolAccess.kt` | 166 个工具名里的只读白名单 |
| `PlanModePolicy.kt` | 工具过滤 + 三套拒绝文案 |
| `PlanStep.kt` | 步骤模型 + 状态 |
| `PlanStore.kt` | 存储（JSON 落盘） |
| `PlanTools.kt` | `update_plan` 实现，8 条校验码 |
| `ProposedPlanParser.kt` | 抠 `<proposed_plan>` 块 |
| `assets/prompts/PLAN_MODE.md` | 三阶段对话式规划规程 |

**核心是「只读靠过滤工具表，不靠提示词」**。模型再怎么被劝「就帮我改一下」，
它手上根本没有写工具，只能拿到 `tool_not_found`。

**接入**在 `generateText` 建完 `toolsInternal` 之后统一 `project()` 一次——
之前只过滤了发给模型的工具表，本地执行用的 `toolsInternal` 还是全量，
模型被诱导着硬调还是能执行。

**`<proposed_plan>` 渲染成卡片**：新增 `AssistantTextBlock`，
标签前面的话照常渲染，块本身渲染成主色描边的卡片。

**计划维护提示**：`update_plan` 的返回值里，有未完成步骤时明确提醒；
系统提示词加 `PLAN_MAINTENANCE_HINT`，只在工具真在表里时注入。

---

## H. 移植：Goal 跨轮目标（`data/goal/`，新增 5 个文件）

| 文件 | 干什么 |
|---|---|
| `Goal.kt` | 六态：active / paused / blocked / usage_limited / budget_limited / complete |
| `GoalStore.kt` | 存储（JSON 落盘）+ 受阻计数 + 续跑计数 |
| `GoalTools.kt` | `get_goal` / `create_goal` / `update_goal` |
| `GoalTemplates.kt` | 续跑规程渲染 |
| `assets/prompts/GOAL_CONTINUATION.md` | 续跑提示词 |

**模型只能设 complete / blocked / paused**。`active`（恢复）归用户，
`usage_limited` / `budget_limited` 归系统。

**`blocked` 要连续三次**。一次受阻就投降的不给这个出口。

**续跑默认关**（`enableGoalAutoContinuation = false`），上限 5 次。
原实现默认开，配合付费中转就是半夜自己跑一整晚。

**续跑注入完整规程**，不是只发一句「继续推进目标」。
预算行整行删掉——这套机制的预算控制只有次数上限，不该在提示词里提 token。

---

## I. 移植：技能库（63 个技能 + 11 个相关文件）

从移植包搬了 63 个技能，加上原有的 18 个，现在共 **85 个**。

### 分类

九个组，固定顺序：

```
内置 18 / 工具链 3 / 逆向 17 / 破甲 4 / 渗透 17 /
游戏安全 12 / 敏感 4 / 创作 3 / 其他 7
```

分类存在每个 `SKILL.md` 的 frontmatter 里，`SKILL_CATEGORY_ORDER` 定义顺序。

### 中文名

85 个技能全部加了 `title` 字段。**`name` 是标识符不能改**（目录名、`enabledSkills`
里存的值、`use_skill` 传的参数全用它），所以中文名单独放一个字段。

### UI

- 列表按分类分组，**默认全部收起**，点标题展开
- 顶部搜索框，中英文名和描述都匹配，搜索时自动展开所有组
- 每组有「全选 / 全不选」
- 卡片：主色圆底图标 + 中文名 + 英文小字 + 描述 + 分类徽章

### 自建分类

`skills/.categories.txt` 一行一个，改技能分类时自动记进去。
这样即使暂时没有技能用这个分类，它也不会从候选里消失。

### AI 写技能

四个工具：`skill_list`（只读，始终可用）/ `skill_create` / `skill_update` / `skill_delete`。

写工具挂在 `LocalToolOption.SkillAuthoring` 开关下面，默认不勾。

**frontmatter 由工具自己拼**，不让模型拼——模型写 YAML 时经常把 `description`
里的冒号或引号写坏，整个技能就解析不了了。

**提示词**（`SKILL_CAPTURE_HINT`）：只在 `skill_create` 真在工具表里时注入。
写了可对照的信号：5 步以上才摸对路 / 试错教会了具体的东西 / 下次同类目标步骤一样。

---

## J. 移植：逆向工具链

没有把 41MB 的 jar 和 57MB 的 frida-server 打进 APK（会让包体破 200MB）。
改成给脚本让 AI 在沙箱里按需下载。

| 文件 | 内容 |
|---|---|
| `assets/scripts/install-rev.sh` | 147 行实测过的一键安装脚本 |
| `assets/scripts/ubuntu-setup.sh` | 58 个 apt 包的安装脚本 |
| `assets/scripts/extract-from-apk.sh` | 从 APK 提二进制 |
| `docs/INSTALL-ONE-SHOT.md` | 352 行完整作业书 + 坑表 |
| 技能 `re-toolchain` | 沙箱搭环境的完整命令 |
| 技能 `mcp-jadx` | jadx-headless-mcp 接入 |
| 技能 `apk-roundtrip-arm64` | arm64 回编（aapt2 兼容层） |
| `ReToolchainStatus.kt` | 一条命令查完工具链状态 |

**几个实测过的坑写进技能了**：

- apktool jar 内嵌的 aapt2 是 x86_64，arm64 上直接 SIGILL（exit 132）
- smali 的 release tag 是 `3.0.10`，写 `v3.0.10` 会拿到 9 字节的 "Not Found"
- pip 要 `--ignore-installed`（apt 的 pwntools 带进 debian 版 psutil）
- `pkill -f jadx-headless-mcp` 会把 proot 外壳一起杀掉，要精确匹配

---

## K. MCP 改进

- `McpTool` 加 `readOnlyHint` / `destructiveHint`，从 SDK 的 `annotations` 反射读
- 声明只读且没声明破坏性的工具免审批（之前 `mcp__*` 一律要审）
- MCP 设置页加「一键添加 jadx」按钮，name 和 URL 预填

---

## L. UI 细节

### 思考深度

- 图标换成刺球 + 灯泡，形状跟着档位变（圆 / 四角 / 六角 / 七角 / 九角 / 十二角 / 软刺球）
- 每个档位一张脸：Off 睡觉飘 z / Auto 微笑 / Low 半睁眼 / Medium 系领带 / High 微笑 / Max 邪恶笑露尖牙
- 高档位加装饰：High 光环 / XHigh 天使翼 + 光环 / Max 恶魔翼 + 双角
- 档位名改成参数值：Off / Auto / Low / Medium / High / XHigh / Max

### 搜索按钮

开启时：搜索方 logo 原尺寸显示在右下，左上一个 13dp 小放大镜，
中间一道锥形光束，logo 背后有呼吸的光圈和一闪一闪的白点。

### 新建模型默认 1M

三处都改了：手动建的（`AddModelButton`）、表单兜底（`ModelSettingsForm`）、
自动获取的（`enrichCapabilities`）。

---

## M. 修复

| 问题 | 原因 |
|---|---|
| 改名后存档目录不跟着动 | `remember` 缺 key；且 `update()` 和 `updateAssistant()` 是两条路径 |
| 虚拟屏漏在审批名单外 | `virtual_screen` 一个 action 就做了 launching + tap，等于绕过审批 |
| 历史检索配额 | `search()` 先搜全部再按序号过滤，配额被前面的存档吃光 |
| 技能全选只勾上一个 | 循环调 `onToggle` 每次基于旧快照，只有最后一次生效 |
| 同名技能静默覆盖 | UI 层查重基于可能过期的列表，加了 VM 层二次校验 |
| 自动启用两个技能 | `DEFAULT_AUTO_ENABLED_SKILLS` 硬编码 |
| 目标消耗恒为 0 | `accumulate` 从来没被调用过 |
| 已完成目标只有「放弃」 | 面板没按状态分支 |
| 计划步骤条永远空 | `update_plan` 在 Plan 模式被硬拒 |
| `<proposed_plan>` 标签裸露 | 只在 `parseMarkdown` 里剥标签，没渲染成块 |
| 沙箱里 `/usr/local` 被拦 | 越界判定把真机标准套到了沙箱上 |
| 快照目录空 | 沙箱路径没翻译成 App 路径 |

---

## N. CI

`setup-bun` 拉 GitHub releases 返回 504 会把整个构建干掉。
改成 `npm install -g bun` + 三次重试，npm registry 稳得多。

---

## 附：当前统计

```
Kotlin 新增文件    约 30 个
技能总数           85（原 18 + 破甲 63 + REA + 工具链 2 + 回编 1）
提交              57 个（这一批）
```

### 完成度对照移植包

```
01-安全层      85%   缺：执行许可令牌、ShellImpact、符号链接的完整链
02-计划与目标  100%
03-沙箱逆向    80%   缺：sha256 校验、双源下载、UI 安装入口
04-技能库     100%
```
