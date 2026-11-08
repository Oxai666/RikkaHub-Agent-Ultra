---
name: apk-roundtrip-arm64
description: 在 arm64 的 proot 沙箱里拆包再回编 APK 时使用。覆盖 aapt2 架构不匹配（SIGILL/132）、apktool 3.x 参数兼容、资源名带 $ 的处理、回编后校验。当用户提到 apktool 回编、重新打包、改完 smali 装回去、exit 132、aapt2 报错时触发。
category: tool
title: APK 回编（arm64 沙箱）
---

# arm64 沙箱里拆包再回编

**核心问题：apktool jar 里内嵌的 aapt2 是 x86_64 的。**

在 arm64 上执行它直接 `SIGILL`，`apktool b` 返回 `exit code = 132`。
错误信息里不会说「架构不对」，只给一个 SIGILL，很容易误判成别的问题。

---

## 一、可行方案：换系统 aapt2 + 过滤参数

```sh
REV=/workspace/rev

# 1. 复制系统原生 aapt2（arm64）
cp -f "$(command -v aapt2)" "$REV/bin/aapt2-host"
chmod +x "$REV/bin/aapt2-host"

# 2. 兼容层：系统 aapt2 2.19 不认 apktool 3.x 传的 6 个新参数
cat > "$REV/bin/aapt2-compat" <<'EOS'
#!/bin/bash
SKIP=" --no-auto-version --no-version-vectors --no-version-transitions --no-resource-deduping --no-compile-sdk-metadata --warn-manifest-validation "
out=()
for a in "$@"; do
  case "$SKIP" in *" $a "*) continue ;; esac
  out+=("$a")
done
exec /workspace/rev/bin/aapt2-host "${out[@]}"
EOS
chmod +x "$REV/bin/aapt2-compat"

# 3. 让裸 `apktool b` 自动挂兼容层
cat > "$REV/bin/apktool" <<'EOS'
#!/bin/sh
JAR=/workspace/rev/jar/apktool.jar
COMPAT=/workspace/rev/bin/aapt2-compat
case "$1" in
  b|build)
    for a in "$@"; do [ "$a" = "--aapt" ] && exec java -jar "$JAR" "$@"; done
    exec java -jar "$JAR" "$@" --aapt "$COMPAT" ;;
  *) exec java -jar "$JAR" "$@" ;;
esac
EOS
chmod +x "$REV/bin/apktool"
```

**这六个参数为什么能安全过滤**：它们都是「更严格/更新的校验或去重」开关，
关掉只是少做几个可选优化，不改变产物的语义。

---

## 二、标准回编流程

```sh
. /workspace/rev/env.sh
cd /workspace/work

# 拆
apktool d -f -o dec target.apk

# 改（smali / res / AndroidManifest.xml）

# 回编（wrapper 会自动带 --aapt 兼容层）
apktool b dec -o rebuilt.apk

# 签名（不签装不上）
keytool -genkey -keystore ks.jks -alias k -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass android -keypass android -dname "CN=t"
apksigner sign --ks ks.jks --ks-pass pass:android --key-pass pass:android \
  --out signed.apk rebuilt.apk

# 校验
apksigner verify --print-certs signed.apk
```

`apksigner` 在 `build-tools` 里。沙箱没装的话用 `apktool` 自带的 `--use-aapt2`
或者先 `apt install apksigner`。

---

## 三、验证回编结果

**不能只看「命令返回 0」**，要再拆一遍比对：

```sh
apktool d -f -o verify rebuilt.apk
cat verify/AndroidManifest.xml | head -20
cat verify/res/values/strings.xml
```

判据：
- `package="..."` 跟原来一致
- label 还是 `@string/app_name` 这种引用，不是被解析成字面量
- 资源条目数量对得上

---

## 四、死路存档（别走）

### ① 用 aapt（v1）

```
First type is not attr!
exit 134
```

apktool 2.7.0 那套走 aapt v1，对 modern APK 的 `resources.arsc` 解析不了。
**放弃这条路。**

### ② 拿 framework-res.apk 做回编测试

能拆开，回编一定失败。原因：它里面有

```
$chooser_direct_share_icon_placeholder__1
```

这种带 `$` 的资源名，aapt2 处理不了。

**测试要用自己造的小 APK**，别拿系统包。

### ③ 用 termux 的 aapt2 / android-tools

bionic 链接，缺 `/system/bin/linker64`。proot 里没有 `/system`，跑不起来。

---

## 五、自造测试 APK（用来验证工具链）

```sh
cd /workspace/rev/test
mkdir -p mini/res/values
cat > mini/AndroidManifest.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.luna.mini" android:versionCode="1" android:versionName="1.0">
    <application android:label="@string/app_name" android:hasCode="false" />
</manifest>
EOF
cat > mini/res/values/strings.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">LunaMini</string>
    <string name="hello">hello from luna</string>
</resources>
EOF

cd mini
aapt2-host compile --dir res -o compiled.zip
aapt2-host link -o mini.apk \
  -I /usr/share/android-framework-res/framework-res.apk \
  --manifest AndroidManifest.xml compiled.zip
apktool d -f -o dec mini.apk
apktool b dec -o rebuilt.apk
apktool d -f -o verify rebuilt.apk
```

**判据**：`rebuilt.apk` 约 1.2KB，再拆开 manifest 和 strings 跟原文件一致。

---

## 六、坑表

| 现象 | 原因 | 处置 |
|---|---|---|
| `exit code = 132` / SIGILL | 内嵌 aapt2 是 x86_64 | 用 `aapt2-compat` |
| `W: --trace-folder arg ...` 然后 `exit 1` | 系统 aapt2 不认新参数 | 同上 |
| `First type is not attr!` + `exit 134` | 走了 aapt v1 | 放弃，回 aapt2 方案 |
| 回编成功但装不上 | 没签名 / 签名不对 | `apksigner sign` |
| 装上了但闪退 | smali 改错 / 资源 id 对不上 | 再拆一遍比对，看 `res/values/public.xml` |
| `aapt2: not found` | 只装了 `aapt` 没装 `aapt2` | `apt install aapt` 会带 aapt2 |
| 拆 framework-res 回编失败 | 资源名带 `$` | 别拿它做测试 |
