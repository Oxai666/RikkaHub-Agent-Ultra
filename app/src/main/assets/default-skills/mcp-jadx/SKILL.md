---
name: mcp-jadx
description: 把 jadx-headless-mcp 接进 App 做 APK 静态分析时使用。覆盖在沙箱里起 HTTP 服务、在设置里配 MCP 服务器、参数调优、内存控制、连接排错。当用户提到 MCP、jadx、反编译一个 APK、分析包结构、找类找方法时触发。
category: tool
title: jadx-MCP 接入（APK 静态分析）
---

# jadx-headless-mcp 接入

约 42MB 的 jar，把 jadx 包成 MCP server，暴露 **26 个工具**：
类/方法/引用查询、反编译、Manifest 与资源读取。

**真实仓库：`1013503897/jadx-headless-mcp`，当前 0.8.0。**
（`atxx/jadx-headless-mcp` 是错的，404。）

---

## 一、为什么走 HTTP 不走 stdio

App 的 MCP 客户端只支持两种传输：

```
sse              SseClientTransport
streamable_http  StreamableHttpClientTransport
```

**没有 stdio**。所以不能用「本机进程拉起」那套写法。

jadx-mcp 自己支持 http，改成：

```
沙箱里跑 java -jar ... --transport http --port 8123
App 里加一个 streamable_http 服务器，URL = http://127.0.0.1:8123/mcp
```

proot 沙箱和 App 共享网络命名空间，`127.0.0.1` 是通的。

---

## 二、装（如果 re-toolchain 已经跑过就跳过）

```sh
. /workspace/rev/env.sh

curl -sSL -o /workspace/rev/jar/jadx-headless-mcp.jar \
  https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.8.0/jadx-headless-mcp-0.8.0-all.jar

# 确认下下来了（9 字节说明 URL 错了）
stat -c%s /workspace/rev/jar/jadx-headless-mcp.jar    # 期望 ≈ 42644877
```

要有 JDK：`apt-get install -y openjdk-21-jdk-headless`，然后 `java -version`。

---

## 三、起服务

```sh
. /workspace/rev/env.sh

nohup java -Xmx1500m -jar /workspace/rev/jar/jadx-headless-mcp.jar \
  --transport http --host 127.0.0.1 --port 8123 --path /mcp \
  --threads 2 --decompile-timeout-ms 60000 --code-cache-size 32 --resources lite \
  > /workspace/jadx-mcp.log 2>&1 &

sleep 8
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8123/mcp
```

`200` / `400` / `405` 都算活着（MCP 端点对 GET 不友好是正常的）。

**探活更准的方式**：

```sh
curl -s -X POST http://127.0.0.1:8123/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}'
```

返回里应该有 `"name":"jadx-headless-mcp","version":"0.8.0"`。

**停服务必须精确匹配**：

```sh
pkill -f '^java .*jadx-headless-mcp'     # 对
pkill -f jadx-headless-mcp               # 错，会把 proot 外壳一起杀掉
```

---

## 四、在 App 里配

```
设置 → MCP → 添加服务器
  类型：Streamable HTTP        ← 不能选 SSE
  Name：jadx
  URL： http://127.0.0.1:8123/mcp
```

**为什么不能选 SSE**：`--transport http` 实现的是 Streamable HTTP
（单端点、POST + 可选 SSE 流），不是老的 SSE 双端点协议。选错连不上。

工具名会被加前缀：`mcp__<服务器id前8位>_<服务器名>__<工具名>`

---

## 五、参数怎么调

| 参数 | 默认 | 手机上给 | 为什么 |
|---|---|---|---|
| `-Xmx` | JVM 默认 | **1500m** | 不给上限，大 APK 把整个手机拖死 |
| `--threads` | min(CPU,4) | **2** | 4 线程预反编译直接 OOM |
| `--code-cache-size` | 64 | **32** | 每份缓存是一个类的反编译结果 |
| `--resources` | full | **lite** | 只要 Manifest + strings + arsc |
| `--decompile-timeout-ms` | 90000 | **60000** | 手机上单类超过一分钟基本是卡死 |

**大包必须带包过滤**，否则 300MB 的包索引吃满内存：

```sh
--include-packages com.target.app
# 或运行时
load_apk { "path": "/workspace/target.apk", "include_packages": ["com.target.app"] }
```

---

## 六、26 个工具怎么用

| 组 | 工具 | 什么时候用 |
|---|---|---|
| 会话 | status / load_apk / unload_apk | 换包前先 unload，不然内存不释放 |
| 清单 | get_app_info / get_android_manifest / get_main_activity_class / get_strings / list_resource_files / get_resource_file | 一上来先看包名版本和入口 Activity |
| 类 | list_classes / get_class_source / get_class_summary / get_smali_of_class / get_fields_of_class / get_methods_of_class / get_inner_classes / resolve_class | 找类 |
| 方法 | get_method_by_name / get_method_body / get_method_smali / search_method_by_name | 找具体实现 |
| 引用 | search_classes_by_keyword / get_xrefs_to_class / get_xrefs_to_method / get_xrefs_to_field | 追调用链 |

**省上下文的三个**，优先用：

```
get_class_summary   只给方法签名 + 字段名 + 内部类，不返回全文
resolve_class       类名写不准（内部类 / $Companion / 点号 / 模糊）也能找到
get_method_body     单个方法，能反编译给 Java，不行就给 SMALI
```

**参数名的坑**：`search_method_by_name` 要的是 `method_name`，不是 `name`。

---

## 七、排错

| 现象 | 原因 | 解决 |
|---|---|---|
| App 连不上 | 服务没起 / 端口占 | 先 `curl` 探，再看 `/workspace/jadx-mcp.log` |
| 连上但工具是空的 | 类型选成 SSE | 改成 Streamable HTTP |
| `Address already in use` | 旧实例占端口 | 精确 pkill 后重启，或换端口 |
| 加载大包时 App 被杀 | 内存没限 | 加 `-Xmx1500m`，减 threads，加 `--include-packages` |
| `get_resource_file` 返回 not found | 服务跑在 `--resources lite` | 用 `get_strings`，或 `--resources full` 重启 |
| `search_method_by_name` 报 `method_name is required` | 参数名写成了 `name` | 传 `method_name` |
| 反编译超时 | 单类太大 / 混淆严重 | 先 `get_class_summary` 定位方法，再 `get_method_body` |

---

## 八、内存吃紧时

手机上同时跑 java + jadx 索引 + App 本体，很容易被系统杀：

1. **用完就停**：`pkill -f '^java .*jadx-headless-mcp'`
2. **减资源**：`--resources none`（只做代码分析）
3. **换工具**：只看包结构用 `apktool d`，不吃内存

---

## 九、和 REA 的关系

REA 是另一套（`rea-agents` npm 包），把 jadx 包在自己的 24 个工具名下，
名字和这边完全不同。两者不冲突，但**别同时起**——内存顶不住。

只想做 APK 静态分析：用这个 jar 就够。
要做 Electron / JS / EVM / 固件多引擎：才需要 REA。
