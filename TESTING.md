# 测试清单

> 用**自己的靶站**测，不用 example.com，也不用别人家的站。
> 原因：example.com 只有一个内联脚本（CDP 按 URL 匹配，断点永远下不上）；别人的站服务端渲染、抓不到 XHR；外网依赖让结果不可重复。

---

## 0. 起靶站（Termux）

```bash
cd ~/../usr/var/lib/proot-distro/installed-rootfs/ubuntu/root/workspace/rikkahub-agent/testtarget
# 或者你在 PRoot 里时的路径：
cd /workspace/rikkahub-agent/testtarget

python3 server.py            # 监听 0.0.0.0:8099
python3 server.py 9000       # 换端口
```

看到 `测试靶站已启动： http://127.0.0.1:8099/` 就行，**别关这个会话**。

浏览器（App 里）打开：`http://127.0.0.1:8099/`

App 和 Termux 是同一台设备，loopback 直通。页面应该显示绿按钮和一行灰色说明。

### 靶站有什么

| 路径 | 用途 |
|---|---|
| `GET /` | 页面，外链 `/app.js` |
| `GET /app.js` | **第 2 行（0-based）= `fetch('/api/ping')`**，断点下这里必中 |
| `GET /api/ping` | JSON，页面加载时用**相对路径**请求 → 验「相对 URL 是否被绝对化」 |
| `GET /robots.txt` | 纯文本 → 验 mock / capture_body |
| `POST /api/post` | 回显 body → 验请求体捕获（页面上的绿按钮） |
| `GET /slow?ms=800` | 延迟响应 → 验暂停时的超时行为 |

靶站会把每条请求打到 stdout。**服务器收到几个 vs hook 抓到几个**，这个差值就是排查依据。

---

## 准备

1. 装 APK，包名 `excp.rikkahub.debug`
2. 进 **设置 → 工具**，勾上写类工具（`debug_attach` / `set_breakpoint` / `eval_js` / 点击输入默认都关着）
3. 起靶站，App 里打开 `http://127.0.0.1:8099/`

---

## A. 冒烟（2 分钟）

```
browser_open      http://127.0.0.1:8099/
browser_get_text  max_chars=300
```

预期：文本里有「RikkaHub 测试靶站」和「POST /api/post」。
页面底部灰框会显示 `/api/ping` 的返回 JSON —— **这说明页面自己的 JS 跑起来了**，是后面测 hook 的前提。

---

## B. Hook 引擎（5 分钟）

### B1 相对 URL 命中（核心回归）

```
browser_hook_create  pattern="http://127.0.0.1:8099/*"  action="log"  capture_body=true
browser_open         http://127.0.0.1:8099/
browser_eval_js      "window.__rikkaHooks.rules.length"          → 1
browser_network_list limit=10  with_body=true
```

**预期**：列表里有 `url: "http://127.0.0.1:8099/api/ping"`，且 `responseBody` 带 `{"ok": true, ...}`。

页面发的是 `fetch('/api/ping')`（相对），能抓到就说明绝对化修好了（`476c3e05`）。
靶站 stdout 会同步打一条 `GET /api/ping 200` —— 两边数量对得上才算真通。

### B2 capture_body

B1 里那条记录的 `requestBody` / `responseBody` 是否都有值。页面加载时那条是 GET，请求体为空是正常的。

再点一下页面上的绿按钮（或 `browser_click selector="#btn"`），然后：

```
browser_network_list limit=5 with_body=true
```

**预期**：出现 `POST /api/post`，`requestBody` = `{"from":"target-page","at":...}`，`responseBody` 是回显。

### B3 mock

```
browser_hook_create  pattern="http://127.0.0.1:8099/robots.txt"  action="mock"  status=200  value="mocked-by-rikka"
browser_open         http://127.0.0.1:8099/
browser_eval_js      "fetch('/robots.txt').then(function(r){return r.text()}).then(function(t){window.__m=t});'go'"
browser_eval_js      "window.__m"                                → "mocked-by-rikka"
```

靶站 stdout **不该**出现 `GET /robots.txt`（请求被本地截住了）。

---

## C. CDP 调试（重点，10 分钟）

### C1 attach

```
browser_open         http://127.0.0.1:8099/
browser_debug_attach
```

**成功**：

```json
{"attached": true, "url": "http://127.0.0.1:8099/"}
```

失败返回 `{"error":"IOException: Try again"}` → **别重试**，跑 C2。

### C2 失败才需要：诊断

`browser_debug_targets`，对照：

| `socket` | `candidates` | 结论 |
|---|---|---|
| `""` | 有值，`errors` 里每个都 `GET /json IOException` | socket 名对但没人应答 → WebView 没建 devtools socket |
| `""` | **空** | `packagePids()` 没拿到进程列表，只剩 myPid 兜底也失败 |
| 有值 | 有值 | 正常，继续 C3 |

`/proc/net/unix: EACCES` 是 Android 10+ 的正常限制，不是失败原因，别在这上面花时间。

### C3 状态

```
browser_debug_status
```

要看到 `"devToolsSocketActive": true` 且 `attachedTabs: 1`。

### C4 下断点（靶站固定行，不用猜）

```
browser_debug_set_breakpoint  url="app.js"  line=2
```

- `url` 后缀匹配，写 `app.js` 就够
- `line` 是 **0-based**，靶站 `app.js` 第 3 行（人工数第 3 行）= `fetch('/api/ping')` = **line=2**

返回：

```json
{"id":"...","url":"app.js","line":2,"column":0}
```

### C5 触发

```
browser_open  http://127.0.0.1:8099/
```

`boot()` 在 app.js 末尾调用，所以页面加载时就会执行到 line 2 → **立刻暂停**。

此时靶站 stdout **不应出现** `GET /api/ping`（请求还没发出去）—— 这是个很好的旁证。

### C6 读暂停态（关键验收）

```
browser_debug_state
```

```json
{"paused": true,
 "callFrames": [{"callFrameId":"...","functionName":"boot","url":"http://127.0.0.1:8099/app.js","line":2,"column":2,
                 "scopes":[{"type":"local","objectId":"..."}]}]}
```

**`callFrames` 非空 + `functionName` 是 `boot` + `line:2` = 整条 CDP 链路通了。**

`paused:false` 说明断点没命中 → 检查是不是 reload 了但脚本被缓存（靶站已发 `Cache-Control: no-store`，理论上不会）。

### C7 单步

```
browser_debug_step   mode="over"     → {"stepped":true}
browser_debug_state                  → line 应该往 3 / 4 走
```

### C8 放开

```
browser_debug_resume                 → {"resumed":true}
```

放开后靶站 stdout 立刻出现 `GET /api/ping 200` —— 说明暂停期间请求确实被拦住了。

**⚠️ 一定要 resume**，否则页面冻死，后面所有工具都超时。

### C9 收尾

```
browser_debug_list_breakpoints             → 列表里有刚才那条
browser_debug_remove_breakpoint id=<id>
browser_debug_detach                       → {"detached":1}
```

---

## D. 失败对照

| 现象 | 最可能原因 | 去看 |
|---|---|---|
| attach 一直 `Try again` | devtools socket 没建 / 候选全错 | `browser_debug_targets` 的 `candidates` 与 `errors` |
| attach 成功但 `paused` 永远 false | 断点 url 没匹配 / 该行没执行 | 确认 `url="app.js"`、`line=2`，且**重载过页面** |
| 页面卡死，所有工具超时 | 停在断点忘了 resume | `browser_debug_resume` 或 `browser_debug_detach` |
| `network_list` 空，靶站 stdout 有请求 | 相对 URL 没命中 / pattern 写错 | `browser_hook_hits` 有无条目；`window.__rikkaHooks.rules.length` |
| 靶站 stdout 一个请求都没有 | 页面没加载出来 / 端口不对 | 先在 App 里手动打开 `http://127.0.0.1:8099/` 看得到页面吗 |
| 页面灰框一直是「（这里显示 fetch 结果）」 | 页面 JS 没跑（hook 把 fetch 搞坏了？） | 关掉所有 hook 规则再 reload |

---

## E. 傻瓜版（整段丢给 AI）

```
先确认靶站已启动在 http://127.0.0.1:8099/ ，然后依次执行，每步贴原始返回，失败就停：

1. browser_open http://127.0.0.1:8099/
2. browser_get_text max_chars=300
3. browser_hook_create pattern="http://127.0.0.1:8099/*" action="log" capture_body=true
4. browser_open http://127.0.0.1:8099/
5. browser_eval_js "window.__rikkaHooks.rules.length"
6. browser_network_list limit=10 with_body=true
7. browser_debug_attach
8. browser_debug_status
9. browser_debug_set_breakpoint url="app.js" line=2
10. browser_open http://127.0.0.1:8099/
11. browser_debug_state
12. browser_debug_step mode="over"
13. browser_debug_resume
14. browser_debug_detach
```
