#!/bin/bash
# 逆向工具链一键安装 —— 适配 proot / Ubuntu 24.04 / aarch64
# 所有组件落 $REV，不用 /usr/local 和 /opt（很多沙箱写不进去）
set -uo pipefail

REV=/workspace/rev
mkdir -p "$REV"/bin "$REV"/jar "$REV"/test
export DEBIAN_FRONTEND=noninteractive
log(){ printf '\n========== %s ==========\n' "$*"; }
big(){ # 判断文件是不是真的下下来了（防 9 字节的 "Not Found"）
  local f=$1 min=$2
  [ -f "$f" ] && [ "$(stat -c%s "$f" 2>/dev/null || echo 0)" -gt "$min" ]
}

# ---------- 1. apt 基础包 ----------
log "1/7 apt 基础包（约 1.4GB，5 分钟）"
if ! command -v r2 >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
  apt-get update
  apt-get install -y --no-install-recommends \
    git file unzip zip xz-utils upx-ucl patchelf strace ltrace binutils gdb xxd \
    netcat-openbsd socat nmap tcpdump tshark binwalk wabt yq jq ripgrep fd-find \
    vim nano less htop procps coreutils util-linux diffutils patch make gcc g++ \
    python3-dev python3-pip python3-venv libffi-dev libssl-dev squashfs-tools cpio \
    p7zip-full sqlmap musl yara bind9-dnsutils whois net-tools tcpflow ffuf gobuster \
    adb sigrok-cli python3-capstone python3-unicorn python3-pyelftools \
    python3-pwntools aapt apktool radare2 openjdk-21-jdk-headless curl ca-certificates
else
  echo "已装过，跳过"
fi

# ---------- 2. Python 安全栈 ----------
log "2/7 Python 安全栈（frida / androguard / pwntools ...）"
# --ignore-installed 是必须的：apt 的 python3-pwntools 会带进 debian 版 psutil，
# pip 想覆盖时因缺 RECORD 直接报错，导致整个事务回滚（一个包装不上）
python3 -m pip install --break-system-packages --ignore-installed --no-input \
  -i https://pypi.tuna.tsinghua.edu.cn/simple \
  frida frida-tools androguard pwntools pyelftools \
  "capstone>=5,<6" unicorn ropgadget jsbeautifier yara-python unblob r2pipe

# ---------- 3. Node 22 ----------
log "3/7 Node 22（官方 tarball，不走 nodesource 脚本）"
if ! big "$REV/node/bin/node" 1000000; then
  cd "$REV" || exit 1
  V=$(curl -sL https://nodejs.org/dist/latest-v22.x/SHASUMS256.txt \
      | grep -o 'node-v[0-9.]*-linux-arm64.tar.xz' | head -1)
  echo "选中版本: $V"
  curl -L -o node.tar.xz "https://nodejs.org/dist/latest-v22.x/$V"
  mkdir -p node && tar -xJf node.tar.xz -C node --strip-components=1 && rm -f node.tar.xz
fi
ln -sf ../node/bin/node "$REV/bin/node"
ln -sf ../node/bin/npm  "$REV/bin/npm"
ln -sf ../node/bin/npx  "$REV/bin/npx"

# ---------- 4. jadx / apktool / smali ----------
log "4/7 jadx 1.5.6 / apktool 3.0.3 / smali 3.0.10"
cd "$REV" || exit 1
big jar/jadx.zip 10000000 || curl -sSL -o jar/jadx.zip \
  https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip
[ -x jadx/bin/jadx ] || unzip -oq jar/jadx.zip -d jadx
big jar/apktool.jar 1000000 || curl -sSL -o jar/apktool.jar \
  https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar
# 注意 tag 是 3.0.10，没有前缀 v；写成 v3.0.10 会拿到 9 字节的 "Not Found"
big jar/smali.jar 1000000 || curl -sSL -o jar/smali.jar \
  https://github.com/baksmali/smali/releases/download/3.0.10/smali-3.0.10-fat-release.jar
big jar/baksmali.jar 1000000 || curl -sSL -o jar/baksmali.jar \
  https://github.com/baksmali/smali/releases/download/3.0.10/baksmali-3.0.10-fat-release.jar

# ---------- 5. aapt2 兼容层（arm64 回编的关键） ----------
log "5/7 aapt2 兼容层 + wrappers"
# apktool jar 内嵌的 aapt2 是 x86_64，arm64 上执行直接 SIGILL(132)。
# 所以复制系统原生 aapt2，再过滤掉它不认的新参数。
cp -f "$(command -v aapt2)" "$REV/bin/aapt2-host" && chmod +x "$REV/bin/aapt2-host"

cat > "$REV/bin/aapt2-compat" <<'EOS'
#!/bin/bash
# Debian aapt2 2.19 不认 apktool 3.x 传的这几个新参数，过滤后行为等价
SKIP=" --no-auto-version --no-version-vectors --no-version-transitions --no-resource-deduping --no-compile-sdk-metadata --warn-manifest-validation "
out=()
for a in "$@"; do
  case "$SKIP" in *" $a "*) continue ;; esac
  out+=("$a")
done
exec /workspace/rev/bin/aapt2-host "${out[@]}"
EOS

cat > "$REV/bin/apktool" <<'EOS'
#!/bin/sh
# 让裸 `apktool b` 自动挂 --aapt 兼容层
JAR=/workspace/rev/jar/apktool.jar
COMPAT=/workspace/rev/bin/aapt2-compat
case "$1" in
  b|build)
    for a in "$@"; do [ "$a" = "--aapt" ] && exec java -jar "$JAR" "$@"; done
    exec java -jar "$JAR" "$@" --aapt "$COMPAT" ;;
  *) exec java -jar "$JAR" "$@" ;;
esac
EOS

printf '#!/bin/sh\nexec /workspace/rev/jadx/bin/jadx "$@"\n'            > "$REV/bin/jadx"
printf '#!/bin/sh\nexec java -jar /workspace/rev/jar/smali.jar "$@"\n'    > "$REV/bin/smali"
printf '#!/bin/sh\nexec java -jar /workspace/rev/jar/baksmali.jar "$@"\n' > "$REV/bin/baksmali"
printf '#!/bin/sh\nexec /usr/bin/fdfind "$@"\n'                          > "$REV/bin/fd"
printf '#!/bin/sh\nexec /usr/local/bin/ROPgadget "$@"\n'                 > "$REV/bin/ropgadget"
chmod +x "$REV"/bin/*

# ---------- 6. jadx-headless-mcp ----------
log "6/7 jadx-headless-mcp 0.8.0（26 个工具）"
# 真实仓库是 1013503897/jadx-headless-mcp，别用 atxx/...（404）
big jar/jadx-headless-mcp.jar 10000000 || curl -sSL -o jar/jadx-headless-mcp.jar \
  https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.8.0/jadx-headless-mcp-0.8.0-all.jar

cat > "$REV/bin/jadx-mcp" <<'EOS'
#!/bin/sh
# 手机友好参数：--threads 2 / cache 32 / lite，默认值会 OOM
JAR=/workspace/rev/jar/jadx-headless-mcp.jar
if [ "$1" = "--http" ]; then
  shift; PORT="${1:-8000}"; shift 2>/dev/null
  exec java -Xmx1500m -jar $JAR --transport http --port "$PORT" --threads 2 \
    --decompile-timeout-ms 60000 --code-cache-size 32 --resources lite "$@"
fi
exec java -Xmx1500m -jar $JAR --transport stdio --threads 2 \
  --decompile-timeout-ms 60000 --code-cache-size 32 --resources lite "$@"
EOS
chmod +x "$REV/bin/jadx-mcp"

# ---------- 7. 环境脚本 + 自检 ----------
log "7/7 写 env.sh 并自检"
cat > "$REV/env.sh" <<'EOS'
# 用法：. /workspace/rev/env.sh
export REV_HOME=/workspace/rev
export PATH=$REV_HOME/bin:$REV_HOME/node/bin:$PATH
export npm_config_registry=https://registry.npmmirror.com
EOS

. "$REV/env.sh"

echo "--- 自检 ---"
echo "git      : $(git --version)"
echo "python   : $(python3 -c 'import frida,androguard,pwn,capstone,unicorn,elftools,yara;print("ok frida",frida.__version__,"androguard",androguard.__version__,"capstone",capstone.__version__)' 2>&1 | tail -1)"
echo "node     : $(node --version) / npm $(npm --version)"
echo "jadx     : $(jadx --version 2>&1 | head -1)"
echo "apktool  : $(apktool --version 2>&1 | head -1)"
echo "smali    : $(smali --version 2>&1 | head -1)"
echo "r2       : $(r2 -v | head -1)"
echo "java     : $(java -version 2>&1 | grep -o 'openjdk version .*' | head -1)"
echo "mcp jar  : $(stat -c%s "$REV/jar/jadx-headless-mcp.jar" 2>/dev/null) bytes"
echo "=== ALL DONE ==="
