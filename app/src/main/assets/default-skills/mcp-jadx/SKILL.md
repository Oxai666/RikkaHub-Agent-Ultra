---
name: mcp-jadx
description: 把 jadx-headless-mcp 接进 App 做 APK 静态分析时使用。覆盖在沙箱里起 HTTP 服务、在设置里配 MCP 服务器、参数调优、内存控制、连接排错。当用户提到 MCP、jadx、反编译一个 APK、分析包结构、找类找方法时触发。
---

# jadx-headless-mcp 接入

41MB 的 jar，把 jadx 包成 MCP server，暴露 **26 个工具**：类/方法/引用查询、反编译、Manifest 与资源读取。

## 一、为什么走 HTTP 不走 stdio

App 的 MCP 客户端只支持两种传输：

```
sse              SseClientTransport
streamable_http  StreamableHttpClientTransport
```

**没有 stdio**。所以不能用 `--transport stdio` 那种本机进程拉起的写法。

好在 jadx-mcp 自己支持 http，改成：

```
沙箱里跑 java -jar ... --transport http --host 127.0.0.1 --port 8080 --path /mcp
App 里加一个 streamable_http 服务器，URL = http://127.0.0.1:8080/mcp
```

proot 沙箱和 App 共享网络命名空间，`127.0.0.1` 是通的。

## 二、先把 jar 下下来

```sh
mkdir -p /opt/rev
curl -L -o /opt/rev/jadx-headless-mcp.jar \
  https://github.com/atxx/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar
# ↑ 这个仓库地址是照原始包里的 jar 文件名推的，没有实际验证过。
#   404 的话先 `curl -s https://api.github.com/search/repositories?q=jadx-headless-mcp`
#   找到真实仓库再换 URL。

# 要有 JDK
apt-get install -y openjdk-21-jdk-headless
java -version
```

## 三、起服务（后台常驻）

```sh
# 内存给到 1.5G 封顶，手机上别开大
nohup java -Xmx1500m -jar /opt/rev/jadx-headless-mcp.jar \
  --transport http \
  --host 127.0.0.1 \
  --port 8080 \
  --path /mcp \
  --threads 2 \
  --decompile-timeout-ms 60000 \
  --code-cache-size 32 \
  --resources lite \
  >/var/log/jadx-mcp.log 2>&1 &

# 等三秒再探
sleep 3
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/mcp
```

返回 `200` / `400` / `405` 都算活着（MCP 端点对 GET 不友好是正常的）。连不上看日志：

```sh
tail -50 /var/log/jadx-mcp.log
```

## 四、在 App 里配

```
设置 → MCP → 添加服务器 → 类型选「Streamable HTTP」
  Name: jadx
  URL:  http://127.0.0.1:8080/mcp
```

保存后点一下测试连接，能看到 26 个工具就通了。

工具名会被加上前缀：

```
mcp__<服务器id前8位>_<服务器名>__<工具名>
例：mcp__a1b2c3d4_jadx__get_class_source
```

## 五、参数怎么调

| 参数 | 默认 | 手机上给 | 为什么 |
|---|---|---|---|
| `-Xmx` | JVM 默认 | **1500m** | 不给上限，大 APK 会把整个手机拖死 |
| `--threads` | min(CPU,4) | **2** | 4 线程预反编译直接 OOM |
| `--code-cache-size` | 64 | **32** | 每份缓存是一个类的反编译结果 |
| `--resources` | full | **lite** | 只要 Manifest + strings + arsc |
| `--decompile-timeout-ms` | 90000 | **60000** | 手机上单类超过一分钟基本是卡死了 |
| `--max-source-bytes` | 60000 | 不动 | 单次返回上限，截断在反编译之后 |

**大包必须带包过滤**，否则 300MB 的包索引会吃满内存：

```sh
# 起服务时
--include-packages com.target.app

# 或者运行时调 load_apk
load_apk { "path": "/workspace/target.apk", "include_packages": ["com.target.app"] }
```

## 六、26 个工具怎么用

| 组 | 工具 | 什么时候用 |
|---|---|---|
| 会话 | status / load_apk / unload_apk | 换包之前先 unload，不然内存不释放 |
| 清单 | get_app_info / get_android_manifest / get_main_activity_class / get_strings / list_resource_files / get_resource_file | 一上来先看包名版本和入口 Activity |
| 类 | list_classes / get_class_source / get_class_summary / get_smali_of_class / get_fields_of_class / get_methods_of_class / get_inner_classes / resolve_class | 找类 |
| 方法 | get_method_by_name / get_method_body / get_method_smali / search_method_by_name | 找具体实现 |
| 引用 | search_classes_by_keyword / get_xrefs_to_class / get_xrefs_to_method / get_xrefs_to_field | 追调用链 |

**省上下文的三个**，优先用它们：

```
get_class_summary   只给方法签名 + 字段名 + 内部类，不返回全文
resolve_class       类名写不准（内部类 / $Companion / 点号 / 模糊）也能找到
get_method_body     单个方法，能反编译给 Java，不行就给 SMALI
```

`get_class_source` 一次可能几万字符，除非确定要看整类，否则先 summary。

## 七、排错

| 现象 | 原因 | 解决 |
|---|---|---|
| App 连不上 | 服务没起 / 端口占用 | `curl` 探一下，看 `/var/log/jadx-mcp.log` |
| 连上但工具是空的 | 用了 sse 类型 | 必须选 streamable_http |
| 加载大包时 App 被杀 | 内存没限 | 加 `-Xmx1500m`，减 threads，加 include_packages |
| 反编译超时 | 单类太大 / 混淆严重 | 先 get_class_summary 找目标方法，再 get_method_body |
| `load_apk` 路径报错 | 路径是**跑 java 那台机器**的 | 沙箱里跑就是沙箱内的路径 |

## 八、内存吃紧时

手机上同时跑 `java` + jadx 索引 + App 本体，很容易被系统杀。三个办法：

1. **用完就停**：`pkill -f jadx-headless-mcp`
2. **减资源**：`--resources none`（连 Manifest 都不留，只做代码分析）
3. **换工具**：只想看包结构就用 `apktool d`，不吃内存

## 九、和 REA 的关系

REA 是另一套（`rea-agents` npm 包），它把 jadx 包在自己的 24 个工具名下，名字和这边**完全不同**。两者不冲突，但别同时起——内存顶不住。

只想做 APK 静态分析：**用这个 jar 就够了**。
要做 Electron / JS / EVM / 固件多引擎：才需要 REA。
