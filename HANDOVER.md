# RikkaHub-Agent-Ultra 接手手册

> 写给下一个接手这个项目的 AI / 人。
> 读完这页就能开工，不用再问用户任何基础问题。

---

## 0. 三十秒上手

```bash
cd /workspace/rikkahub-agent          # 代码在这
git log --oneline -5                   # 看最近在干什么
git status --short                     # 有没有没提交的改动
```

循环就四步：**改 → 提交 → 推送 → 用户打包 → 报错回来修**。

- 构建**只能**走 GitHub Actions（手动 dispatch）。手机本地没有 Android SDK，编译不了，别试。
- 编译报错**看仓库 issue**，CI 失败会自动开一条，里面贴着 Gradle 日志。Actions 页面反而看不到（要 admin 权限）。

---

## 1. 事实清单（这些别猜）

| 项 | 值 |
|---|---|
| 仓库 | `Oxai666/RikkaHub-Agent-Ultra`（独立仓库，不是 fork） |
| 本地路径 | `/workspace/rikkahub-agent` |
| 主分支 | `main` |
| 上游 | `ExTV/rikkahub-agent`，remote 名 `upstream` |
| 基础版本 | RikkaHub 2.5.1-agent.1，versionCode 187 |
| 应用名 | RikkaHub Code |
| **安装后的包名** | **`excp.rikkahub.debug`** |
| 设备 | Android 手机，KernelSU root，PRoot 工作区 |
| 工作区挂载点 | `/workspace`（rootfs 里的绝对路径） |

**包名坑**：`/data/data/me.rerere.rikkahub.debug` 是**旧的残留目录**，也是 AI 自己的 proot 沙箱所在地。查 App 数据库、查工作区别往这儿找，会看错。

---

## 2. 参考源码在哪

| 用途 | 路径 |
|---|---|
| 万象开源上游（浏览器、CDP、hook 全在这） | `/workspace/taixu`（64M，GPL-3.0） |
| └ 浏览器运行时 | `taixu/runtime/browser/` |
| └ CDP 全套 | `taixu/runtime/browser/src/main/java/top/wkbin/taixu/runtime/browser/cdp/` |
| └ hook 全套 | `taixu/runtime/browser/.../browser/hook/` |
| 万象 APK 逆向资料 | `/workspace/reverse-out-wanxiang`（1-browser / 2-subagents / 3-skills） |
| 万象反编译源码 | `/workspace/wanxiang-src2` |

**移植顺序**：先读太墟实现 → 复制文件 → 改包名 `top.wkbin.taixu.*` → `me.rerere.rikkahub.*` → **核对被调用方有没有对应方法**（最容易漏的地方，见坑 3）。

---

## 3. 改代码的规矩

1. **先 grep 再动手**。这个项目里同名概念很多（三个 `BrowserEvent`、两个 `HookRuleStore` 之类的错觉），先看清楚现有实现。
2. **一次一个主题**。commit message 写「为什么」，不写「改了什么」。
3. **改完自查三样**：
   ```bash
   # ① 括号/引号平衡
   python3 -c "s=open('路径.kt').read(); print(s.count('{')==s.count('}'), s.count('(')==s.count(')'))"
   # ② 新增的 import 目标是否存在
   grep -rn "class 类名\|object 类名" app/src/main/java | head -3
   # ③ 调用的方法是否真的存在
   grep -rn "fun 方法名" app/src/main/java/me/rerere/rikkahub/
   ```
4. 没有编译器，**你写的代码在推送前从未被验证过**。所以第 3 条不是走形式。

---

## 4. 提交与推送

```bash
cd /workspace/rikkahub-agent
git add -A
git commit -m "fix(模块): 一句话说清为什么"
GIT_SSH_COMMAND="ssh -o BatchMode=yes" timeout 300 git push origin main
```

- SSH key 在 `~/.ssh/id_ed25519`，remote 用 `git@github.com:` 形式。
- `BatchMode=yes` 必加，否则 ssh 会卡在交互提示。
- 推送前先 `git fetch origin && git rev-list --left-right --count HEAD...origin/main` 确认没落后。

**用户名坑**：这台机器原来的 git config 是 `0xa1666`（错的），推上去会 `Repository not found`。已经改成 `Oxai666`，如果又变回去，用 `git -c user.name=Oxai666 -c user.email=Oxai666@users.noreply.github.com commit`。

---

## 5. 构建

**只能手动触发**（workflow 里没有 `push` / `schedule`，故意的：改代码期间不要自动构建）。

```
GitHub → Actions → build-ultra-apk → Run workflow → Branch: main
```

- `workflow_dispatch` 的按钮**只在默认分支上的 workflow 才显示**。
- 构建完到对应 run 页面下载 artifact（debug APK）。
- 或者到 Releases（`publish_release` 默认 true 时会挂上去）。

---

## 6. 看编译报错（重点）

**Actions 的日志 API 要 admin 权限，匿名取不到。** 所以 workflow 里写了：构建失败时自动 `gh issue create`，标题长这样：

```
CI failure: 2026-10-30T03:00Z @ b217fff
                             ^^^^^^^ 失败的那个 commit
```

匿名就能读（仓库是公开的）：

```bash
# ① 列出最近的失败
curl -s --max-time 25 \
  "https://api.github.com/repos/Oxai666/RikkaHub-Agent-Ultra/issues?state=all&per_page=10" \
| python3 -c "
import sys,json
for it in json.load(sys.stdin):
    print(f\"#{it['number']} [{it['state']}] {it['created_at'][:16]} {it['title']}\")"
```

```bash
# ② 抠出编译错误（把 N 换成 issue 号）
curl -s --max-time 30 \
  "https://api.github.com/repos/Oxai666/RikkaHub-Agent-Ultra/issues/N" \
| python3 -c "
import sys,json,re
b=json.load(sys.stdin).get('body') or ''
errs=[l for l in b.splitlines() if re.match(r'\s*e: ',l)]
print('错误 %d 条'%len(errs))
for l in errs[:30]: print(l.strip()[:200])"
```

Kotlin 的编译错误行格式固定：

```
e: file:///home/runner/work/.../CdpManager.kt:78:35 Unresolved reference 'urlOf'.
   └─ 路径最后一段是文件            └行:列  └ 符号/原因
```

**拿到这一行就够定位了**，直接去本地对应文件对应行看。

如果 issue 正文里没有 `e:` 行，说明是别的阶段挂了（签名、资源合并、Gradle 本身），此时看正文里 `FAILURE:` 附近的内容。

---

## 7. 真机验证

**测试靶站在 `testtarget/server.py`**（零依赖，`python3 server.py` 起在 8099）。
不要用 example.com 或别人家的站当靶子——example.com 只有内联脚本，断点永远下不上；
别人的站服务端渲染、抓不到 XHR。具体测法看 `TESTING.md`。


```bash
# 装完确认包名（不是 me.rerere.rikkahub.debug）
adb shell pm list packages | grep rikkahub

# 抓崩溃
adb logcat -d | grep -A 29 "FATAL EXCEPTION" | tail -40
# 或者按 tag
adb logcat -s RikkaWebView RikkaCdp
```

- 浏览器工具默认只有一部分开着，写类工具（点击、输入、下断点）要去 **设置 → 浏览器/工具** 里勾。
- 手动验证的典型姿势是让用户在对话里调工具，然后把返回贴回来。

---

## 7.5 CDP 在本机的实测结论（2026-10-30，重要）

**结论：CDP 调试在这台设备（MIUI + WebView 151）上不可用，不是代码问题。**

完整证据链：

```
RikkaHub   webview_devtools_remote_5204    → 超时
Chrome     chrome_devtools_remote          → 超时
万象       webview_devtools_remote_29075   → 超时
自建 socket（python bind 同进程）           → 秒通     ← 证明检测方式没问题
```

已排除：SELinux（setenforce 0 后仍超时）、权限（root 直连同样超时）、
App 代码（三个不同 App 表现一致）、socket 名字（pid 与 ps 对得上、在 /proc/net/unix 里）、
检测方式（同进程自建 abstract socket 秒通）。

设备侧补充事实（2026-10-30 实测）：
  getprop ro.debuggable = 0 / ro.build.type = user   ← user 版系统
  万象（top.wanxiang.app，自己那套太墟代码）在这个靶站上 attach 同样三次 Try again
  万象沙箱内连 socket 报 ECONNREFUSED（沙箱网络命名空间看不到宿主 abstract socket）
  注意：socket 建出来了，说明 setWebContentsDebuggingEnabled 是生效的，
        「user build」不能当作「开关没开」的理由。

症状是「socket 在 listen（Flags=0x00010000），连得上，但不 accept、不回 HTTP」。
换设备或换 ROM 才能验证 CDP；在本机不要再花时间排查这一层。

**不要做的事**：不要因为「attach 失败」去改 discoverTransport 的判定逻辑——
太墟上游的写法（connect 成功即通过）是对的，加额外校验只会把有效候选丢掉。

## 8. 坑清单（都是实战踩出来的）

| # | 现象 | 真因 | 修法 |
|---|---|---|---|
| 1 | hook 规则建了但页内 `rules` 是空 / `captureBody` 读不到 | `HookRuleStore` 用了 `encodeDefaults = false`，`v:1` 和 `actions` 被省略，页内 `__rikkaApplyRules` 第一句就 return | `encodeDefaults = true` |
| 2 | 页面自己的请求一条都抓不到，手写绝对地址就能抓 | hook wrapper 拿 `input` 原始字符串去比绝对 URL glob | 匹配前 `new URL(u, document.baseURI).href` |
| 3 | 编译报 `Unresolved reference 'xxx'` | 搬太墟代码时只搬了调用方，裁剪版被调用方没这个方法 | 搬完 grep 一遍 `xxx\.` 的调用，逐个核对目标类 |
| 4 | 工作区文件明明在，代码判成不存在 | `WorkspaceEntity.root` 只是目录名不是绝对路径；`/workspace` 是 rootfs 挂载点 | 一律走 `workspaceRepository.readText(workspaceId, path)`（suspend，同步回调里用 `runBlocking` 桥） |
| 5 | 小窗（悬浮窗）一开就崩 | `ChatMessage` 需要四个无默认值的 CompositionLocal | 必须提供 `LocalSettings` / `LocalNavController` / `LocalToaster` / `LocalTTSState` |
| 6 | 小窗停在半句话不刷新 | `remember(size, lastId)` 当缓存键，流式输出时键不变 | 去掉缓存键 |
| 7 | 补 import 之后出现 50+ 编译错误 | 按行号截断文件（`lines[:67]`）把 import 块砍掉了，后续锚点又落空 | **永远不要按行号截断改文件**，用 `str.replace` 锚定内容 |
| 8 | CI 报某个 test task 不存在 | 任务名猜错 | 用 `testDebugUnitTest` / `assembleDebug` |
| 9 | `gh issue` 里没有日志 | issue 是失败时才开 | 先确认 run 真的失败了 |
| 10 | CDP attach 报 `IOException: Try again` | ① 只验证 connect 不验证 `/json` → 抽象 socket 假阳性 ② `setWebContentsDebuggingEnabled` 调得太晚 ③ 只猜了 `myPid()` | 见 `a1d38177`：GET /json 验证 + 试遍同包进程 pid + App 启动即开 |
| 11 | `/proc/net/unix` 读不了（EACCES） | Android 10+ SELinux/hidepid 限制 | 正常现象，别在这上面浪费时间，靠猜 pid + 验证兜底 |

---

## 9. 禁止事项

1. **不要在手机本地跑 Gradle**。没 SDK，纯浪费。
2. **不要给 workflow 加 `push` 触发**。用户明确要求改代码期间不自动构建。
3. **不要把「读不到文件」当成「文件不存在」**。先确认自己看的包名/路径对不对。
4. **不要编造工具返回**。工具没返回 `{"attached":true}` 就是没成功，如实报告。
5. **不要用「我是 AI / 权限不足」当借口停手**。卡住了就把已知信息摊开、给出可选路径。

---

## 10. 当前进度（2026-10-30）

已完成：armor 破甲、DeepSeek 搜索、136 子智能体、claim 核验、写租约、浏览器起始页与引擎、
hook 抓包引擎（fetch/XHR log·mock·redirect·capture_body）、CDP 协议层 + 调试层（断点/单步/调用栈）。

最新提交：`a1d38177`（修 CDP attach 连到死 socket）。

待验证：CDP 调试链在真机上跑通（attach → set_breakpoint → state 看 callFrames → resume）。
