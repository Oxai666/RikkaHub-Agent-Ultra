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
