#!/bin/sh
# 从样本 APK 里提取内置的 linux-tools 与 frida
# 用法: ./extract-from-apk.sh /path/to/milk.apk [输出目录]
set -e
APK="${1:?用法: $0 <milk.apk> [outdir]}"
OUT="${2:-./extracted}"
mkdir -p "$OUT"
python3 - "$APK" "$OUT" <<'PY'
import sys,zipfile,json,hashlib,os
apk,out=sys.argv[1],sys.argv[2]
z=zipfile.ZipFile(apk)
integrity=json.loads(z.read("assets/linux-tools/bundled-integrity.json"))
os.makedirs(os.path.join(out,"linux-tools"),exist_ok=True)
for path,meta in integrity.items():
    data=z.read("assets/"+path)
    name=os.path.basename(path)
    dst=os.path.join(out,"linux-tools",name)
    open(dst,"wb").write(data)
    got=hashlib.sha256(data).hexdigest()
    ok="OK" if got==meta["sha256"] and len(data)==meta["bytes"] else "MISMATCH"
    os.chmod(dst,0o755)
    print(f"{name:20} {len(data):>9}  {ok}")
# frida
for n in z.namelist():
    if n.startswith("assets/frida/") and not n.endswith("/"):
        data=z.read(n); dst=os.path.join(out,os.path.basename(n))
        open(dst,"wb").write(data); os.chmod(dst,0o755)
        print(f"{os.path.basename(n):20} {len(data):>9}  {hashlib.sha256(data).hexdigest()[:16]}…")
# mcp jar
for n in z.namelist():
    if n.startswith("assets/mcp/") and n.endswith(".jar"):
        data=z.read(n); dst=os.path.join(out,os.path.basename(n))
        open(dst,"wb").write(data)
        print(f"{os.path.basename(n):34} {len(data):>9}  {hashlib.sha256(data).hexdigest()[:16]}…")

PY
echo "完成 → $OUT"
