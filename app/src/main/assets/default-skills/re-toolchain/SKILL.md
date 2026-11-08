---
name: re-toolchain
description: 在 proot 沙箱里搭 Android 逆向/渗透工具链时使用。覆盖 apt 基础包、pip 安全栈、Node、jadx/apktool/smali、jadx-headless-mcp（26 个 MCP 工具）、frida-server 推送。当用户提到装逆向环境、jadx、apktool、frida、radare2、pwntools、反编译工具链、REA 时触发。
category: reverse

---

# 沙箱逆向工具链

在 `workspace_shell` 里跑。沙箱是 Ubuntu 24.04 proot，已经是 root，**不要加 sudo**。

全部命令都按「先检查、后安装」写：已经有的跳过，避免重复下载几十 MB。

## 一、先看现状

```sh
for c in git file python3 node java jadx apktool r2 frida nmap; do
  printf '%-10s' "$c"; command -v "$c" >/dev/null 2>&1 && echo OK || echo MISSING
done
```

## 二、基础包（58 个，Ubuntu 24.04 arm64 全部可解析）

```sh
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  git file unzip zip xz-utils upx-ucl patchelf strace ltrace binutils gdb xxd \
  netcat-openbsd socat nmap tcpdump tshark binwalk wabt yq jq ripgrep fd-find \
  vim nano less htop procps coreutils util-linux diffutils patch make gcc g++ \
  python3-dev python3-pip python3-venv libffi-dev libssl-dev squashfs-tools cpio \
  p7zip-full sqlmap musl yara bind9-dnsutils whois net-tools tcpflow ffuf gobuster \
  adb sigrok-cli python3-capstone python3-unicorn python3-pyelftools \
  python3-pwntools aapt apktool radare2 openjdk-21-jdk-headless curl ca-certificates
```

**命名坑**（实测）：

| 包 | 实际命令 |
|---|---|
| `fd-find` | `fdfind`，要软链：`ln -sf "$(command -v fdfind)" /usr/local/bin/fd` |
| `wabt` | `wat2wasm` / `wasm2wat` / `wasm-objdump`，没有 `wabt` 这个命令 |
| `netcat-openbsd` | `nc` |
| `bind9-dnsutils` | `dig` / `nslookup` |
| `musl` | `musl-gcc` |
| `aapt` | 顺带装了 `aapt2` |

## 三、Python 安全栈

Ubuntu 24.04 的 Python 是 externally-managed，直接 pip 会报 `externally-managed-environment`。

```sh
python3 -m pip install --break-system-packages \
  -i https://pypi.tuna.tsinghua.edu.cn/simple \
  frida frida-tools androguard pwntools pyelftools \
  "capstone>=5,<6" unicorn ropgadget jsbeautifier yara-python unblob r2pipe
```

只装你需要的，全套下来 300MB+。

## 四、Node（REA 要 `^22.19 || ^24.11 || >=26`，Ubuntu 仓库只有 18）

```sh
curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
apt-get install -y nodejs
node -v   # 期望 v22.x
```

想钉版本（和原实现一致）：

```sh
curl -LO https://nodejs.org/download/release/v26.8.1/node-v26.8.1-linux-arm64.tar.xz
tar -xJf node-v26.8.1-linux-arm64.tar.xz -C /usr/local --strip-components=1
```

npm 换源：`npm config set registry https://registry.npmmirror.com`

## 五、jadx / apktool / smali（仓库版太老，走 GitHub）

```sh
mkdir -p /opt/rev && cd /opt/rev

# jadx 1.5.6
curl -L -o jadx.zip https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip
unzip -oq jadx.zip -d /opt/rev/jadx
ln -sf /opt/rev/jadx/bin/jadx /usr/local/bin/jadx

# apktool 3.0.3
curl -L -o /usr/local/bin/apktool \
  https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar
printf '#!/bin/sh\nexec java -jar /usr/local/bin/apktool "$@"\n' > /usr/local/bin/apktool.sh
chmod +x /usr/local/bin/apktool.sh && mv /usr/local/bin/apktool.sh /usr/local/bin/apktool

# smali / baksmali 3.0.10
curl -L -o /usr/local/bin/smali.jar \
  https://github.com/baksmali/smali/releases/download/v3.0.10/smali-3.0.10-fat-release.jar
curl -L -o /usr/local/bin/baksmali.jar \
  https://github.com/baksmali/smali/releases/download/v3.0.10/baksmali-3.0.10-fat-release.jar
```

自检：`jadx --version` / `apktool --version` / `java -version`

## 六、jadx-headless-mcp（41MB jar，26 个工具）

这是把 jadx 包成 MCP server 的 jar，能做类/方法/引用查询和反编译。**手机上不建议常开**，内存吃得厉害。

```sh
mkdir -p /opt/rev
curl -L -o /opt/rev/jadx-headless-mcp.jar \
  https://github.com/atxx/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar
# ↑ 这个仓库地址是照原始包里的 jar 文件名推的，没有实际验证过。
#   404 的话先 `curl -s https://api.github.com/search/repositories?q=jadx-headless-mcp`
#   找到真实仓库再换 URL。

# 自检
java -jar /opt/rev/jadx-headless-mcp.jar --help
```

**手机上的启动参数**（默认值会 OOM）：

```sh
java -Xmx1500m -jar /opt/rev/jadx-headless-mcp.jar \
  --transport stdio \
  --threads 2 \
  --decompile-timeout-ms 60000 \
  --code-cache-size 32 \
  --resources lite
```

| 参数 | 默认 | 手机上建议 | 原因 |
|---|---|---|---|
| `--threads` | min(CPU,4) | **2** | 4 线程预反编译大 APK 必 OOM |
| `--code-cache-size` | 64 | **32** | 每份缓存是一个反编译结果 |
| `--resources` | full | **lite** | 只要 Manifest + strings + arsc |

大包先 `load_apk` 带 `include_packages` 只留目标包，再查——388MB 的包全量索引会吃满内存。

### 26 个工具

| 组 | 工具 |
|---|---|
| 会话 | status / load_apk / unload_apk |
| 清单 | get_app_info / get_android_manifest / get_main_activity_class / get_strings / list_resource_files / get_resource_file |
| 类 | list_classes / get_main_application_classes_names / get_class_source / get_class_summary / get_smali_of_class / get_fields_of_class / get_methods_of_class / get_inner_classes / resolve_class |
| 方法 | get_method_by_name / get_method_body / get_method_smali / search_method_by_name |
| 引用 | search_classes_by_keyword / get_xrefs_to_class / get_xrefs_to_method / get_xrefs_to_field |

省上下文的三个：`get_class_summary`（只给签名）、`resolve_class`（类名写不准也能找到）、`get_method_body`（单方法反编译）。

## 七、frida-server（要 root，不走沙箱）

frida-server 是**设备端**二进制，必须在 `/data/local/tmp` 跑，不能装在 proot 里。

```sh
# 1. 确认芯片
getprop ro.product.cpu.abi     # 期望 arm64-v8a

# 2. 下载对应版本（Python 端 frida 版本必须和它一致）
# release 是 .xz 压缩包，不是裸二进制
curl -L -o /data/local/tmp/fs.xz \
  https://github.com/frida/frida/releases/download/17.23.3/frida-server-17.23.3-android-arm64.xz
xz -d /data/local/tmp/fs.xz
mv /data/local/tmp/fs /data/local/tmp/frida-server
chmod 755 /data/local/tmp/frida-server

# 3. 起（要 root）
nohup /data/local/tmp/frida-server >/dev/null 2>&1 &

# 4. 验
frida-ps -U | head
```

状态判定：

```sh
[ -f /data/local/tmp/frida-server ] || echo FRIDA_ABSENT
pgrep -f frida-server >/dev/null && echo FRIDA_RUNNING || echo FRIDA_STOPPED
```

**版本必须对齐**：PC/沙箱里的 `frida` Python 包版本和 `frida-server` 不一致会报 protocol error。查：`frida --version`。

## 八、装完自检

```sh
git --version
python3 -c "import capstone, pwn, elftools, unicorn, androguard; print('py ok')"
node --version
jadx --version
apktool --version
r2 -v | head -1
java -version 2>&1 | head -1
```

七条全过就算齐了。
