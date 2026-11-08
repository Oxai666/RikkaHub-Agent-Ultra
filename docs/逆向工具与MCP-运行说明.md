# 逆向工具与 MCP：运行方式

回答一个具体问题：**这些东西装在哪、怎么跑起来、AI 怎么用到它们**。

---

## 先看清三层结构

```
┌─────────────────────────────────────────────┐
│  App 本体（Kotlin，跑在 Android 上）          │
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │ AI 模型                                │  │
│  │   ↓ 调工具                             │  │
│  │ workspace_shell  ──┐                   │  │
│  │ mcp__xxx__yyy    ──┤                   │  │
│  └────────────────────┼───────────────────┘  │
│                       ↓                      │
│  ┌────────────────────────────────────────┐ │
│  │ proot 沙箱（Ubuntu 24.04，用户态）      │ │
│  │   /            ← rootfs，工具装在这      │ │
│  │   /workspace   ← App 的文件区（双向）    │ │
│  │   java / jadx / apktool / frida ...    │ │
│  └────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
          ↑ 127.0.0.1 (共享网络命名空间)
          │
   jadx-mcp HTTP 服务（也在沙箱里）
```

三件事要分清：

| 东西 | 装在哪 | 怎么用 |
|---|---|---|
| **命令行工具**（jadx / apktool / radare2 / python 逆向库） | 沙箱里，`apt` / `pip` 装 | AI 调 `workspace_shell` 直接跑 |
| **jadx-headless-mcp** | 沙箱里，`java -jar` 起 HTTP 服务 | App 的 MCP 客户端连 `127.0.0.1:8080` |
| **frida-server** | **不走沙箱**，设备端 `/data/local/tmp` | 需要 root，App 通过 `root_shell` 推 + 起 |

---

## 第一步：装 rootfs（一次性，5 分钟）

沙箱要先有根文件系统，否则 `workspace_shell` 跑不了。

```
侧边栏 → 扩展 → 工作区 → 选中助手绑定的那个工作区
  → 「安装 rootfs」
  → 两种方式：
      ① 填 URL（比如 ubuntu-base 官方地址）
      ② 从本地选文件（ubuntu-base-24.04-base-arm64.tar.gz）
```

装完状态应该从 `NEEDS_INSTALL` 变成 `READY`。

**要求**：必须是 `ubuntu-base-*-arm64.tar.gz` 或 `.tar.xz`，解压后根目录要有 `/bin/sh` 或 `/bin/bash`。其他发行版的包会被拒——`RootfsInstaller` 有校验。

装好之后，`/workspace` 就是 App 的文件区，双向同步。

---

## 第二步：装工具链（10~30 分钟，看网速）

让 AI 做，或者自己粘命令。

**让 AI 做**（推荐）：开一个对话，说一句

> 用 re-toolchain 技能，在沙箱里把逆向环境装好

它会按技能里的步骤跑：58 个 apt 包 → pip 安全栈 → Node → jadx/apktool/smali。

**自己粘**：在「文件」页里让 AI 跑 `workspace_shell`，一条条粘也行。

装完自检：

```sh
git --version
python3 -c "import capstone, pwn, elftools, unicorn, androguard; print('py ok')"
node --version
jadx --version
apktool --version
r2 -v | head -1
java -version
```

七条全绿才算齐。

**几个坑**（技能里都写了）：

```
pip 必须 --break-system-packages      Ubuntu 24.04 有 PEP 668
fd 命令实际叫 fdfind                  要建软链
wabt 没有这个命令                     只有 wat2wasm / wasm2wat
jadx/apktool 要 GitHub                仓库版太老
```

---

## 第三步：起 jadx-mcp（每次要用才起）

它是个**常驻 HTTP 服务**，跑在沙箱里。

```sh
mkdir -p /opt/rev
curl -L -o /opt/rev/jadx-headless-mcp.jar \
  https://github.com/atxx/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar

nohup java -Xmx1500m -jar /opt/rev/jadx-headless-mcp.jar \
  --transport http --host 127.0.0.1 --port 8080 --path /mcp \
  --threads 2 --code-cache-size 32 --resources lite \
  >/var/log/jadx-mcp.log 2>&1 &

sleep 3
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/mcp
```

**这些参数不能省**：

| 参数 | 不给会怎样 |
|---|---|
| `-Xmx1500m` | 大 APK 直接把手机内存吃干，系统杀 App |
| `--threads 2` | 默认 4 线程预反编译，必 OOM |
| `--code-cache-size 32` | 默认 64，每份缓存是一个类的反编译结果 |
| `--resources lite` | 默认 full，连所有资源文件都索引 |

用完停掉：`pkill -f jadx-headless-mcp`

---

## 第四步：配 MCP（一次性，30 秒）

```
设置 → MCP → 添加服务器
  类型：Streamable HTTP     ← 不能选 SSE
  Name：jadx
  URL： http://127.0.0.1:8080/mcp
```

**为什么不能选 SSE**：jadx-mcp 的 `--transport http` 实现的是 Streamable HTTP（单端点、POST + 可选 SSE 流），不是老的 SSE 双端点协议。选错了会连不上。

保存后它会自动拉工具列表，能看到 26 个就通了。

工具名会被加前缀：

```
mcp__a1b2c3d4_jadx__load_apk
mcp__a1b2c3d4_jadx__get_class_summary
mcp__a1b2c3d4_jadx__get_xrefs_to_method
```

前缀规则：`mcp__<服务器id前8位>_<服务器名>__<工具名>`

---

## 第五步：用

### 命令行工具怎么用

AI 直接调 `workspace_shell`：

```
AI: workspace_shell { "command": "jadx -d /workspace/out target.apk" }
```

`/workspace` 是 App 的文件区，产出的结果你在「文件」页能直接看到。

### MCP 工具怎么用

AI 调 `mcp__xxx_jadx__*`：

```
load_apk { "path": "/workspace/target.apk" }
  → {"state":"LOADED","class_count":6754,"load_duration_ms":3284}

get_class_summary { "class_name": "com.example.MainActivity" }
  → 方法签名 + 字段名 + 内部类（不返回全文）

get_method_body { "class_name": "...", "method_name": "onCreate" }
  → 单个方法的 Java 或 SMALI

get_xrefs_to_method { ... }
  → 谁调用了它
```

**先 summary 再 source**：`get_class_source` 一次可能几万字，直接把上下文撑爆。

---

## frida-server：单独一条路

它**不能装在沙箱里**——frida 要 hook 的是设备上的进程，必须在设备端跑。

```sh
# 1. 推（AI 用 root_shell 做）
curl -L -o /data/local/tmp/frida-server \
  https://github.com/frida/frida/releases/download/17.23.3/frida-server-17.23.3-android-arm64
chmod 755 /data/local/tmp/frida-server

# 2. 起
nohup /data/local/tmp/frida-server >/dev/null 2>&1 &

# 3. 验
frida-ps -U | head
```

**版本必须对齐**：沙箱里 `pip install frida` 的版本和 `frida-server` 不一致会报 protocol error。

```sh
frida --version              # 沙箱里
# 和下载的 frida-server 版本必须一样
```

沙箱里的 python 通过 `frida -U` 连设备端，中间走 adb 或直接 socket。手机上有 root 就不需要 adb。

---

## 常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| `workspace_shell` 报没装 rootfs | 工作区没初始化 | 回第一步 |
| `apt-get` 报权限错 | 漏了 `sudo` 或没在沙箱里 | 沙箱里已经是 root，不要加 sudo |
| pip 报 `externally-managed` | Ubuntu 24.04 的 PEP 668 | 加 `--break-system-packages` |
| MCP 连不上 | 服务没起 / 端口占 | `curl http://127.0.0.1:8080/mcp` 探一下 |
| MCP 连上但没工具 | 类型选成 SSE | 改成 Streamable HTTP |
| 加载大包时 App 被杀 | 内存没限 | 加 `-Xmx1500m`，减 threads，加 `--include-packages` |
| frida 连不上 | 版本不一致 / 没起 | 看 `pgrep -f frida-server` |

---

## 一句话

```
rootfs 装一次  →  工具链装一次  →  jadx-mcp 每次要用才起
                                        ↓
                                   App 里配一次 MCP 指向它
                                        ↓
                     AI 用 workspace_shell 或 mcp__* 干活
```
