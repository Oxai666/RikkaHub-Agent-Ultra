# RikkaHub Agent Ultra — 相对上游的完整改动说明

> **基线**：`ExTV/rikkahub-agent` @ `a88f5854`（`2.5.1-agent.1`，versionCode 187）
> **本仓库**：95 个提交，477 个文件变更，**+85,744 / −359** 行
> **对比方式**：`git diff --numstat a88f5854 HEAD`
>
> 上游自该提交之后没有新进展（`upstream/master` HEAD 仍是 `a88f5854`），
> 所以本文的"上游"始终指这一个基线。

---

## 一、改动规模分布

| 路径 | 新增 | 删除 | 文件数 | 说明 |
|---|---:|---:|---:|---|
| `app/src/main/assets/agency_agents/` | +32,184 | 0 | 203 | 136 个子智能体角色定义 |
| `app/src/main/assets/`（其余） | +43,081 | −1 | 158 | 浏览器起始页、hook 运行时、内置 skills |
| `app/src/main/java/` | +7,858 | −306 | 68 | 全部业务代码 |
| `app/src/test/java/` | +1,223 | 0 | 8 | 单元测试 |
| `search/src/main/java/` | +209 | 0 | 2 | 新 gradle 模块 |
| `ai/src/main/java/` | +126 | −4 | 3 | 消息模型扩字段 |
| `app/src/main/res/` | +70 | −4 | 22 | 字符串资源 |
| `.github/workflows/` | +232 | 0 | 1 | 自建 CI |

---

## 二、全新的模块

### 2.1 万象子智能体系统（`subagent/`）

上游没有"子智能体"这个概念：一个助手 = 一套 prompt + 一堆工具，全程单线程。

本仓库把「太墟 / 万象」的角色库整套移植进来，并补上了调度与治理。

**代码**（7 个文件，新增）：

| 文件 | 作用 |
|---|---|
| `BuiltinSubAgents.kt` | 从 assets 加载 136 个角色，按部门索引 |
| `SubAgentRegistry.kt` | 角色查询：按名字（`EXACT_NAME`）或 2–5 个关键词模糊匹配 |
| `SubAgentEngine.kt` | 子智能体的一次独立运行：干净上下文、独立模型、独立超时 |
| `SubAgentTools.kt` | 暴露给主模型的 `subagent_dispatch` / `subagent_list` / `subagent_get` / `subagent_cancel` / `subagent_roles` |
| `SubAgentRun.kt` | 一次运行的状态机（排队 / 运行 / 完成 / 取消） |
| `SubAgentClaim.kt` | **取证式交付校验**：要求子智能体声明「我改了哪些文件 / 该跑什么命令验证」，主模型逐条核对 |
| `SubAgentWriteLease.kt` | **写租约**：给子智能体限定可写路径前缀，越界直接拒绝 |

**角色库**（`assets/agency_agents/agents/`，136 个 `.md`，9 个部门）：

```
engineering 59 · game-development 21 · specialized 14 · security 12
testing 9 · design 6 · spatial-computing 6 · product 5 · project-management 4
```

**UI**：`SettingSubAgentsPage.kt`（+168/−19）—— 复用应用自带的设置页，不额外做界面。

### 2.2 浏览器运行时（`browser/`）

上游完全没有浏览器能力。这里从太墟（`wkbin/taixu`，GPL-3.0）移植了整条链路。

```
browser/              42 个 Kotlin 文件、约 7,200 行
├── cdp/              15 个文件 — Chrome DevTools Protocol 全套
├── hook/               8 个文件 — 页内注入式拦截引擎
└── inject/            1 个文件 — 脚本注入兜底
```

**能力清单**：

| 层 | 内容 |
|---|---|
| 页面操作 | 打开 / 点击 / 输入 / 滚动 / 选择 / 按键 / 提交 / 前后退 / 等待 |
| 读取 | 正文（Readability）/ DOM / 链接 / 文本 / 截图 |
| 元素快照 | `browser_snapshot`：把可交互元素列成 `{ref, tag, text, selector, 坐标}` |
| 拦截（hook） | fetch / XHR / WebSocket / function / property / storage / cookie / console / timer / crypto |
| 拦截动作 | `log` / `block` / `redirect` / `mock` / `modify_headers` / `replace` / `fake_value` |
| 抓包 | `browser_network_list`（含请求/响应 body） |
| 控制台 | `browser_console_list`（log/warn/error 全量，不依赖任何 hook 规则） |
| 证据库 | `browser_evidence_list` / `browser_evidence_clear`（落盘，重启后可查） |
| CDP | attach / 断点 / 单步 / 调用栈 / 引擎级 Fetch 拦截 |
| 多标签 | `browser_snapshot` 之外新增标签栏，最多 8 个逻辑标签 |

**工具总数：35 个**（`BrowserToolDefaults.ALL_TOOLS`），分读/写两组，写类默认关闭。

**起始页**（`assets/browser/newtab.html`，13 KB）：搜索引擎切换（必应 / 百度 / 搜狗 / Google），选择存 `localStorage`。

### 2.3 armor 破甲链路（`data/ai/armor/`）

4 个文件、584 行，从「原插件」逐字移植：

| 文件 | 作用 |
|---|---|
| `ArmorTransformer.kt` | 注入器：把指令拼进**最后一条 USER 消息内部** |
| `ArmorJudge.kt` | 判定：什么情况下需要纠正 |
| `ArmorState.kt` | 每会话状态：是否已纠正、是否发生截断 |
| `ArmorProvider.kt` | 只读流监看装饰器：不改编 chunk、不断流 |

**严格按助手开关**：`assistant.armorEnabled`（默认 `false`），关掉时 `transform()` 直接原样返回。

### 2.4 DeepSeek 联网搜索（`search/` 模块）

上游的搜索是「让模型自己调 `web_fetch`」。这里新增独立 gradle 模块：

```
search/src/main/java/me/rerere/search/DeepSeekSearchService.kt   +194 行
settings.gradle.kts: include(":search")
```

---

## 三、新增的工程能力

### 3.1 自建 CI（`.github/workflows/build-ultra.yml`，+232 行）

上游没有可用的构建工作流。这里补了：

- 手动触发（`workflow_dispatch`），不占用 Actions 额度
- 跑 armor / subagent 单元测试，逻辑改坏当场红
- 出 debug APK → 上传 artifact → 挂 Release
- **失败自动开 issue 贴 Gradle 日志**（因为 Actions 日志 API 需要 admin 权限，匿名取不到）

### 3.2 测试（10 个，+1,223 行）

```
ArmorJudgeTest / ArmorProviderTest / ArmorStateTest / ArmorTransformerTest
BuiltinRoleSearchTest / BuiltinSubAgentsTest / SubAgentClaimTest / SubAgentWriteLeaseTest
ErrorParserRetryStatusTest / FileEncoderMimeTest
```

### 3.3 文档

```
HANDOVER.md   接手手册（9290 字节）
TESTING.md    测试清单（7927 字节）
```

---

## 四、对上游既有行为的修改

这一节是**行为层面的差异**，不只是新增代码。

### 4.1 提示词组装（`SystemPromptBuilder.kt`，+39/−1）

**上游**：只有一句固定的工具成本提示。

**本仓库**：所有提示**按实际注册的工具名动态生成**——句子里出现的每个工具名都保证存在。

```kotlin
// 之前：无条件加，纯聊天助手的提示词里也会出现 read_window_tree、browser_hook_create
// 现在：逐条判断，条件不满足整条不出现
if (cheapReaders.isNotEmpty() && expensiveReaders.isNotEmpty()) { ... }
if ("browser_hook_create" in toolNames) { ... }
if ("browser_debug_attach" in toolNames) { ... }
```

### 4.2 工具权限模型（`LocalTools.kt`，+37/−6）

**① 屏幕读取独立成项**

上游的 `ScreenAutomation` 一个开关管 11 个工具，包括整屏截图和窗口树读取。

本仓库拆成两个：

| 开关 | 工具 | 能看到屏幕内容 |
|---|---|---|
| 屏幕自动化 | tap / longPress / swipe / clickNode / setText / scroll / globalAction / wakeScreen | 否 |
| **屏幕读取**（新增） | take_screenshot / read_window_tree / find_node | **是** |

**② `web_fetch` / `web_extract` 加助手级开关**

上游注释写得很直白：`always-on unless disabled in Search settings, no per-assistant toggle`。
结果「助手一个工具都没勾」照样能抓网页，开不出纯聊天助手。

本仓库新增 `LocalToolOption.WebFetch`，注册条件变成 `全局开关 && 助手勾选`。

### 4.3 消息模型（`ai/.../Message.kt`，+126/−4）

`UIMessage` 新增三个字段 + 两个方法：

```kotlin
val generationTimeMs: Long? = null   // 各步累计生成耗时
val lastUsage: TokenUsage? = null    // 最后一次请求的 usage（看上下文占用用它）
val stepCount: Int? = null           // 累计请求次数

fun lastRequestUsage(): TokenUsage?
fun contextTokens(): Int?
```

配套 `ChatMessageNerdLine.kt`（+275/−114）升级为带进度条的用量面板：
上下文占用三段条（缓存 / 输入 / 输出）、点击弹详情（步数 / 总输入 / 缓存总量 / 总输出 / 花费 / 速度 / 时长）。

### 4.4 浏览器小窗（`BrowserMiniChat.kt`，+255/−126）

上游没有这个 UI。本仓库新增，并且**去掉了自动滚动**：

```kotlin
// 只在首次拿到消息时定位到最新一条，之后完全不动
LaunchedEffect(nodes.size) {
    if (!didInitialScroll && nodes.isNotEmpty()) {
        listState.scrollToItem(nodes.lastIndex); didInitialScroll = true
    }
}
```

同时接上了 `ChatMessage` 的四个回调（分支 / 编辑回填 / 复制 / 删除），原先都是空 lambda。

### 4.5 循环守卫（`GenerationLoop.kt`，+36/−2）

给浏览器观察类工具加 2 秒 TTL 冷却，`browser_open` 5 秒：

- 2 秒内的相同重读仍然拦（那是空转）
- 超过就放行（页面变了，重读是正常的）

新增 `ACTION_TOOLS_WITH_TTL`，把 `browser_open` 从「只读观察」里摘出来，避免有副作用的工具被误算成观察类。

### 4.6 Koin 注入（`AppModule.kt`，+13）

新增 5 个单例：`BrowserEventBus` / `HookRuleStore` / `NetworkBodyStore` / `BrowserEvidenceStore` / `HookEventPipeline` / `HookInstaller` / `CdpManager`。

---

## 五、上游保留不变的部分

以下**一行没改**，保持上游行为：

- 供应商与模型适配（Claude / Gemini / OpenAI / DeepSeek 等全部 provider）
- 数据库结构与迁移
- 会话 / 分支 / 记忆（Memory）/ 工作区
- MCP 接入
- Telegram Bot
- 主题与大部分 UI
- 技能系统框架（只新增了内置 skills 内容）

---

## 六、已知限制

| 项 | 说明 |
|---|---|
| CDP 断点 | 在部分 ROM（实测 MIUI + WebView 151）上，devtools socket 会 listen 但不 accept。三个不同 App 表现一致，属设备级限制，代码层无解。hook 链路不受影响。 |
| 标签栏 | 单 WebView + 多逻辑 tab：切走不保留滚动/表单/SPA 状态，抓包事件也不按 tab 隔离 |
| function hook | 声明型函数（`function x(){}`）且「定义后立即同步调用」的情况抓不到——setter 会被 `DefineOwnProperty` 顶掉。赋值型（`window.x = fn`）正常。 |
| MCP 工具 | 没有助手级开关，配了 server 后所有助手都会拿到 |
| `stepCount` | 字段已加，但本仓库的生成循环（`GenerationLoop`）还没有填充点，用量面板暂不显示「步数」 |

---

## 七、快速对照表

| 能力 | 上游 | 本仓库 |
|---|:--:|:--:|
| 内置子智能体 | — | ✅ 136 个 / 9 部门 |
| 子智能体写租约 | — | ✅ |
| 交付取证校验 | — | ✅ |
| 内置浏览器 | — | ✅ 35 个工具 |
| 页内 hook 拦截 | — | ✅ 10 类目标 / 7 种动作 |
| 抓包（请求+响应体） | — | ✅ |
| 页面 console 采集 | — | ✅ |
| 证据库（重启可查） | — | ✅ |
| CDP 断点调试 | — | ✅（受设备限制） |
| 多标签 | — | ✅ 8 个 |
| 浏览器内 AI 小窗 | — | ✅ |
| 共浏览开关（真闸门） | — | ✅ |
| armor 破甲 | — | ✅ 按助手指令 |
| DeepSeek 联网搜索 | — | ✅ 独立模块 |
| 用量面板 | 简版 | ✅ 进度条 + 详情 + 花费 |
| 屏幕读取独立授权 | — | ✅ |
| 自建 CI | — | ✅ |
| 单元测试 | 少量 | ✅ 10 个新增 |
