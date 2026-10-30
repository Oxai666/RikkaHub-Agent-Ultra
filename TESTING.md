# 测试清单

> 每次打包后照这个跑。A 段必过，B 段是回归，C 段是当前重点。

---

## 准备

1. 装 APK（包名 `excp.rikkahub.debug`，别装错到 `me.rerere.rikkahub.debug`）
2. 进 **设置 → 工具**，把写类工具勾上（点击 / 输入 / eval_js / debug_attach / set_breakpoint 等默认是关的）
3. 打开一个助手，`+` 菜单里能看到「浏览器」和「工具审批」两个入口 = 装对了

---

## A. 冒烟（2 分钟，必过）

| 步骤 | 调用 | 预期 |
|---|---|---|
| 1 | `browser_open` url=https://example.com | `{"success":true,"title":"Example Domain"}` |
| 2 | `browser_get_text` | 文本里有 `Example Domain` |
| 3 | `browser_screenshot` | 图出来，不是白屏 |

任一步挂了，先别测后面的——说明 App 本体就坏了（大概率是装错包或 WebView 初始化失败）。

---

## B. Hook 引擎（5 分钟，回归）

### B1 规则下发

```
browser_hook_create  pattern="https://example.com/*"  action="log"  capture_body=true
```

返回 `{id:"rikka-...", applied:true}`。

```
browser_open  https://example.com          ← 必须重新加载，规则在 document-start 注入
browser_eval_js  "window.__rikkaHooks.rules.length"
→ 1

browser_eval_js  "JSON.stringify(window.__rikkaHooks.rules.map(function(r){return [r.id,r.captureBody]}))"
→ [["rikka-...",true]]      ← 注意是规则级 captureBody，不是 actions[0].captureBody
```

> 如果 `rules.length` 是 0：`encodeDefaults` 又退回去了（见 HANDOVER 坑 1）。

### B2 相对 URL 命中（这是修过的 bug）

```
browser_eval_js  "fetch('/robots.txt').then(function(r){return r.status})"
browser_network_list  limit=5  with_body=true
```

**预期捕捉到 `url: "https://example.com/robots.txt"`**（绝对化后的）。

如果 `network_list` 空但 `hook_hits` 有请求——说明相对 URL 又没解析（见坑 2）。

### B3 body

B2 的条目里应该有 `requestBody`（若 GET 则为空）和 `responseBody`（robots.txt 内容）。

### B4 mock

```
browser_hook_create  pattern="https://example.com/robots.txt"  action="mock"  status=200  value="mocked-by-rikka"
browser_open  https://example.com
browser_eval_js  "fetch('/robots.txt').then(function(r){return r.text()}).then(function(t){window.__m=t}); 'go'"
browser_eval_js  "window.__m"
→ "mocked-by-rikka"
```

---

## C. CDP 调试（当前重点，10 分钟）

### C1 attach

```
browser_open  https://example.com
browser_debug_attach
```

**成功标志**：

```json
{"attached": true, "url": "https://example.com/"}
```

失败会返回 `{"error":"IOException: Try again"}`。**失败立刻跑下一步**，别重试。

### C2 失败才需要：看诊断

```
browser_debug_targets
```

对照下表判断：

| `socket` | `candidates` | `errors` | 结论 |
|---|---|---|---|
| `""` | 有值 | 每个候选都 `GET /json IOException` | socket 名对但对面不应答 → WebView 没建 devtools socket（检查 `RikkaHubApp.onCreate` 里那段是否真的执行到） |
| `""` | **空** | `scan /proc/net/unix: EACCES` | `packagePids()` 没拿到进程列表（Koin Context 取不到 / ActivityManager 权限），只剩 myPid 兜底还失败 |
| 有值 | 有值 | — | attach 成功，走 C3 |

`/proc/net/unix: EACCES` 是 Android 10+ 的正常限制，**它本身不是失败原因**，只能靠猜 pid + `/json` 验证兜底。

### C3 状态

```
browser_debug_status
```

```json
{"devToolsSocketActive": true, "attachedTabs": 1, "tabs": [{"tabId":"foreground","url":"https://example.com/","paused":false,"breakpoints":0,"workers":0}]}
```

`devToolsSocketActive:true` 就说明 CDP 会话活着。

### C4 挑一个脚本（关键技巧）

CDP 断点按 **脚本 URL** 下，所以得先知道页面有哪些脚本：

```
browser_eval_js  "JSON.stringify([].slice.call(document.scripts).map(function(s){return s.src||'(inline '+s.textContent.length+'B)'}))"
```

`example.com` 只有一个内联脚本（`<script>var a=1</script>`），**内联脚本没法按 URL 下断点**。要测断点请换一个带外链 JS 的站，比如：

```
browser_open  https://m.huaban.com/discovery
```

然后再取一次 `document.scripts`，挑一个 `.js` 结尾的（形如 `https://m.huaban.com/js/mobile/main.js?...`）。

### C5 下断点

```
browser_debug_set_breakpoint  url="main.js"  line=0
```

- `url` 支持**后缀匹配**，写文件名就够，不用全路径
- `line` 是 **0-based**（CDP 原生），第 1 行写 0
- 可选 `condition`，比如 `condition="window.__stopHere"`，只有表达式为真才断

返回：

```json
{"id":"1:0:0:https://.../main.js","url":"main.js","line":0,"column":0}
```

### C6 触发

```
browser_open  https://m.huaban.com/discovery     ← 同 URL 重载即可
```

如果脚本加载时第 0 行就执行，页面会**当场冻住**——这是正常的，不是崩溃。

### C7 读暂停态

```
browser_debug_state
```

```json
{"paused": true, "reason": "other", "hitBreakpoints": "...",
 "callFrames": [{"callFrameId":"...","functionName":"","url":"https://.../main.js","line":0,"column":0,"scopes":[{"type":"local","objectId":"..."}]}]}
```

**`callFrames` 非空 = 断点真的命中了，整条 CDP 链路通。**

`paused` 为 `false` 说明断点没命中（行号没执行到 / URL 没匹配上），换个脚本或换行号。

### C8 单步 / 放开

```
browser_debug_step  mode="over"      → {"stepped":true}
browser_debug_state                  → line 变成下一行
browser_debug_resume                 → {"resumed":true}
```

**⚠️ 测完必须 resume**，否则页面一直冻着，后续所有工具都超时。

### C9 收尾

```
browser_debug_list_breakpoints       → 列出下的断点
browser_debug_remove_breakpoint id=<上一步的 id>
browser_debug_detach                 → {"detached":1}
```

detach 会自动 resume，断点会快照保存，下次 attach 自动重放。

---

## D. 常见失败对照

| 现象 | 最可能原因 | 去看 |
|---|---|---|
| attach 一直 `Try again` | WebView devtools socket 没建 / 候选全错 | `browser_debug_targets` 的 `candidates` 和 `errors` |
| attach 成功但 `state` 永远 `paused:false` | 断点 url 没匹配上，或该行没执行 | 换 `document.scripts` 里真实存在的 .js，line 换 3–5 |
| 页面卡住不动，所有工具超时 | 停在断点上忘了 resume | `browser_debug_resume`，或 `browser_debug_detach` |
| `network_list` 空但页面有请求 | 相对 URL 没命中 / 规则 pattern 写错 | `browser_hook_hits` 有没有条目 |
| `hook_hits` 也没有 | 规则没下发 | `browser_eval_js "window.__rikkaHooks.rules.length"` |
| 所有工具都 `{"error":"no active page"}` | 浏览器 tab 被关掉了 | 重新 `browser_open` |

---

## E. 一次跑完（贴给 AI 的傻瓜版）

把下面整段丢进对话，让 AI 自己跑：

```
依次执行，每步把原始返回贴出来，失败就停下并跑 browser_debug_targets：
1. browser_open https://example.com
2. browser_get_text max_chars=100
3. browser_debug_attach
4. browser_debug_status
5. browser_open https://m.huaban.com/discovery
6. browser_eval_js 取 document.scripts 列表
7. browser_debug_set_breakpoint 挑其中一个 .js，line=0
8. browser_open 同 URL 重载
9. browser_debug_state
10. browser_debug_resume
11. browser_debug_detach
```
