# ascript-device（技能源目录）

万象内置技能「AScript 真机自动化」的源文件。**不要直接改 `app/src/main/assets/builtin_skills/` 里的 zip** ——
zip 由本目录打包生成，改完源重新打包，见下方命令。

## 这是什么

把 [AScript](https://www.ascript.cn) 的真机自动化能力（官方面向**电脑端 Codex** 的插件）
转译成**万象在手机内可用**的技能形态：

- **路径 A**：在万象里以 `STDIO` 方式接入官方 `ascript-mcp`（实测 29 个工具）；
- **路径 B**：不装任何东西，用万象自带 host 能力完成本机自动化；
- **路径 C**：照官方文档在电脑端安装 Codex 离线包。

方法论沿用官方 `SKILL.md` 与 `docs/AGENT_RULES.md`（先观察 → 选策略 → `eval_python` 小步验证 → 上传运行 → 验证证据）。

## 目录结构

```
ascript-device/
├── SKILL.md                               # 入口：路径决策 + 硬规则 + 安全红线（会被注入模型目录）
├── references/
│   ├── wanxiang-mcp-setup.md              # 路径 A：万象内接入 ascript-mcp
│   ├── host-tools-map.md                  # 路径 B：万象 host 工具对照表
│   ├── official-codex-install.md          # 路径 C：电脑端官方离线包
│   └── mcp-tools.md                       # 官方 29 个 MCP 工具速查（实测校正）
├── README.md                              # 本文件
└── LICENSE                                # MIT（内容来源的许可）
```

## 打包（zip 必须扁平，SKILL.md 在根）

```bash
cd skills-src/ascript-device
rm -f ../../app/src/main/assets/builtin_skills/ascript-device.zip
zip -r -X ../../app/src/main/assets/builtin_skills/ascript-device.zip . -x '.*' -x '*/.*'
```

校验结构（应与 `eight-honors.zip` 一致：无顶层目录）：

```bash
unzip -l app/src/main/assets/builtin_skills/ascript-device.zip
```

> 万象 `BuiltinSkillInstaller.deploy()` 会把 zip 内容直接解到 `/attachments/skills/ascript-device/`，
> 因此 zip 内**不能**再包一层目录，否则 `SKILL.md` 找不到。
> zip 条目名含中文时无 UTF-8 标志位也没关系（安装器已显式按 UTF-8 解码）。

## 注册

打包后需在 `app/src/main/java/top/wanxiang/app/BuiltinSkillInstaller.kt` 的 `SHIPPED` 列表里登记一条：

```kotlin
Shipped(
    zip = "ascript-device.zip",
    dirName = "ascript-device",
    id = "builtin_ascript_device",
    name = "AScript 真机自动化",
    triggerCommand = "/ascript",
    category = "设备自动化",
    iconName = "Android",
),
```

## 技能怎么生效（三种途径）

| 途径 | 操作 | 生效条件 | 特点 |
|---|---|---|---|
| **随包内置（推荐）** | 打包 zip → 放 assets/builtin_skills → 在 `SHIPPED` 注册 → 重新构建并安装 APK | 安装后**首次启动**由 `BuiltinSkillInstaller` 解包登记 | `isEnabled = true` 默认启用、用户可关；id 固定 `builtin_ascript_device` |
| 自动发现 | 把技能目录复制到 `/attachments/skills/ascript-device/`，重启 App | 启动时 `syncFromDirectories` 扫描 | **默认关闭**，需去设置页手动启用；id 为随机 `custom_xxxxxxxx` |
| 设置页导入 | 走 App 内的技能导入 / 市场通道 | 取决于外部包审计策略 | 外来包含提示词会被 `SkillAudit` 审计 |

⚠️ **不要两条路同时走**：内置 installer 按 `id` 插入、自动发现按 `resourcePath` 去重，
两者 id 不同 → 会各登记一条，技能列表出现**重复条目**。若先用手动目录试用、之后又装了新 APK，
请先删除 `/attachments/skills/ascript-device/`，或在设置页删掉重复项。

⚠️ **数据库 IGNORE 语义**：`installShippedBuiltin` 用 `insertAll(IGNORE)`，已存在的同 id 行不会被覆盖。
对**已装用户**升级安装时，新 id 首次出现会插入并默认启用；但若用户此前手动关掉过它，
再次安装不会把它重新打开 —— 这是刻意的（不静默改用户设置）。

⚠️ **本沙箱构建注意**：工程声明 `ndkVersion = 30.0.15729638`，而沙箱 ARM64 NDK 固定为 `29.0.14206865`
（上游无 r30 aarch64 制品），直接 `./gradlew` 会在配置期抛 `CXX1100`。
验证/构建请用工程内脚本：

```bash
sh .wanxiang/wx-managed-preview.sh /workspace/wanxiang :app:compileDebugKotlin   # 语法验证
sh .wanxiang/wx-managed-preview.sh /workspace/wanxiang assembleDebug             # 出 APK
```

## 来源与许可

- 流程与工具清单整理自 AScript 官方文档 <https://www.ascript.cn/docs/ai/codex/>、
  `ascript-codex-plugin` 离线包，以及 [ascript-cn/ascript-mcp](https://github.com/ascript-cn/ascript-mcp) README。
- 上游为 **MIT License，© 2026 北京奥悦科技有限公司**，见 `LICENSE`。
- 本技能仅做**形态适配**（面向万象运行环境重写导航与落地路径），未改动上游产品的任何行为。
