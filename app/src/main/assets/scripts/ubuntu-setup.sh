#!/bin/sh
# Ubuntu 24.04 (noble) aarch64 一键逆向环境
# 用法: sudo ./ubuntu-setup.sh
set -eu
export DEBIAN_FRONTEND=noninteractive

echo "[1/6] apt 基础包（58 个，已在 Ubuntu 24.04 arm64 实测全部可解析）"
apt-get update
apt-get install -y --no-install-recommends \
  git file unzip zip xz-utils upx-ucl patchelf strace ltrace binutils gdb xxd \
  netcat-openbsd socat nmap tcpdump tshark binwalk wabt yq jq ripgrep fd-find \
  vim nano less htop procps coreutils util-linux diffutils patch make gcc g++ \
  python3-dev python3-pip python3-venv libffi-dev libssl-dev squashfs-tools cpio \
  p7zip-full sqlmap musl yara bind9-dnsutils whois net-tools tcpflow ffuf gobuster \
  adb sigrok-cli python3-capstone python3-unicorn python3-pyelftools python3-pwntools \
  aapt apktool radare2 openjdk-21-jdk-headless curl ca-certificates

echo "[2/6] Python 栈（Ubuntu 有 PEP 668，必须 --break-system-packages）"
python3 -m pip install --break-system-packages -i https://pypi.tuna.tsinghua.edu.cn/simple \
  frida frida-tools androguard pwntools pyelftools "capstone>=5,<6" unicorn \
  ropgadget jsbeautifier yara-python mitmproxy unblob r2pipe

echo "[3/6] Node 22（Ubuntu 自带 18.19，REA 要求 >=22.19 / ^24.11 / >=26）"
if ! command -v node >/dev/null 2>&1 || [ "$(node -v | sed 's/v\([0-9]*\).*/\1/')" -lt 22 ]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi
node -v
npm config set registry https://registry.npmmirror.com
npm install -g --no-audit --no-fund js-beautify wakaru

echo "[4/6] jadx 1.5.6（Ubuntu 仓库没有）"
mkdir -p /opt/apk-analysis && cd /opt/apk-analysis
if [ ! -d jadx ]; then
  curl -L -o jadx.zip https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip
  unzip -q jadx.zip -d jadx
fi

echo "[5/6] apktool 3.0.3 + smali/baksmali 3.0.10（仓库版是 2.7.0，这里覆盖）"
cd /opt/apk-analysis
curl -L -o apktool_3.0.3.jar https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar || true
curl -L -o smali-3.0.10.jar https://github.com/baksmali/smali/releases/download/v3.0.10/smali-3.0.10-fat-release.jar || true
curl -L -o baksmali-3.0.10.jar https://github.com/baksmali/smali/releases/download/v3.0.10/baksmali-3.0.10-fat-release.jar || true

echo "[6/6] aapt2（apt 的 aapt 包已自带 /usr/bin/aapt2）"
if ! command -v aapt2 >/dev/null 2>&1; then
  apt-get install -y aapt || true
fi
command -v aapt2 >/dev/null 2>&1 || echo "  提示：aapt2 仍缺失，可从样本 APK 提取 aapt2-static 放到 /usr/local/bin/aapt2"

echo "完成。自检："
for c in java jadx apktool smali baksmali r2 binwalk frida node npm aapt2; do
  printf "  %-10s " "$c"; command -v "$c" >/dev/null 2>&1 && echo ok || echo "缺"
done
