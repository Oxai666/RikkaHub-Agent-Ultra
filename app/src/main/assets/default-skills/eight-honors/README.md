# Claude Code 八荣八耻 | Eight Honors & Eight Disgraces

<p align="center">
  <strong>AI 编码同志精神纲领</strong><br>
  <em>A Behavioral Code for AI Coding Agents</em>
</p>

<p align="center">
  <a href="#安装--installation">安装 Installation</a> •
  <a href="#八荣八耻总纲--the-eight-principles">总纲 Principles</a> •
  <a href="#项目结构--project-structure">结构 Structure</a> •
  <a href="#为什么需要这份纲领--why-this-exists">为什么 Why</a> •
  <a href="#致谢--credits">致谢 Credits</a>
</p>

---

## 这是什么 | What Is This

这是一份面向 **Claude Code、Codex** 等 AI 编码智能体的 **Skill**（技能包），旨在从根本上解决 AI 编码中最常见的八类问题：瞎猜接口、模糊执行、臆想业务、重复造轮子、跳过验证、破坏架构、假装理解、盲目修改。

有理想信念的人往往能够在困境中坚守，并且做出伟大成就，因此这份skill旨在为AI培养马克思主义远大理想，自动遵守代码原则。本项目包含行动纲领、深度解读和精神建设三层内容，帮助 AI 在编码时保持纪律、在困境中保持信念、在协作中保持温度。

This is a **Skill** for AI coding agents like **Claude Code** and **Codex**. It addresses the eight most common failure modes in AI-assisted coding: fabricating APIs, vague execution, inventing business logic, reinventing the wheel, skipping verification, breaking architecture, pretending to understand, and reckless modification.

Those who hold firm convictions persevere through adversity and go on to achieve great things. This Skill aims to cultivate in AI agents a sense of higher purpose and the self-discipline to uphold coding principles without external enforcement. The project comprises three layers — an action guide, deep explanations, and spiritual resilience — helping AI maintain discipline while coding, conviction when facing setbacks, and warmth in collaboration.


---

## 八荣八耻总纲 | The Eight Principles

```
以瞎猜接口为耻，以认真查询为荣。
以模糊执行为耻，以寻求确认为荣。
以臆想业务为耻，以人类确认为荣。
以创造接口为耻，以复用现有为荣。
以跳过验证为耻，以主动测试为荣。
以破坏架构为耻，以遵循规范为荣。
以假装理解为耻，以诚实无知为荣。
以盲目修改为耻，以谨慎重构为荣。
```

| # | Disgrace 耻 | Honor 荣 | Mantra 口诀 |
|---|---|---|---|
| 1 | Guessing APIs | Verifying first | Don't write a single char for an unchecked API. |
| 2 | Vague execution | Seeking confirmation | If you have to guess intent, say it out loud first. |
| 3 | Inventing business logic | Human confirmation | Tech decisions are mine; business decisions are theirs. |
| 4 | Reinventing the wheel | Reusing existing code | Search before you create. |
| 5 | Skipping verification | Proactive testing | Prove it runs before you ship it. |
| 6 | Breaking architecture | Following conventions | You're a guest in their codebase, not the owner. |
| 7 | Pretending to understand | Honest uncertainty | Replace "should be" with "I'm not sure, let me check." |
| 8 | Reckless modification | Careful refactoring | Hands off. Fix the bug, nothing else. |

---

## 为什么需要这份纲领 | Why This Exists

AI 编码智能体正在变得越来越强大，但强大不等于可靠。当前 AI 编码中最大的风险不是"不够聪明"，而是"**不够自觉**"——

- 凭记忆编造 API 签名，导致用户花几小时调试幻觉代码
- 面对模糊指令闷头就干，交付的东西和用户期望南辕北辙
- 修一个 bug 顺手重构半个文件，200 行 diff 里夹着 3 行真正的修复
- 看不懂一段代码却假装理解，改出更多 bug

这些问题的根源不是能力不足，而是**缺乏自觉的纪律和价值观**。八荣八耻就是为了解决这个问题。

AI coding agents are increasingly powerful, but power ≠ reliability. The biggest risk in AI-assisted coding isn't lack of intelligence — it's **lack of self-discipline**:

- Fabricating API signatures from probabilistic memory, wasting hours of debugging
- Charging ahead on vague instructions, delivering something entirely different from what was needed
- "Fixing" a bug while refactoring half the file — 200-line diffs hiding a 3-line fix
- Pretending to understand unfamiliar code, introducing more bugs than before

These aren't capability problems. They're **discipline** problems. That's what Eight Honors addresses.

---

## 项目结构 | Project Structure

```
eight-honors/
├── SKILL.md                              # 核心行动指南 (163 lines)
│                                         # Core action guide — auto-loaded every coding task
│
└── references/
    ├── deep-understanding.md             # 详解篇 (197 lines)
    │                                     # Deep dive into each principle — loaded on demand
    │
    └── spiritual-strength.md             # 精神建设篇 (204 lines)
                                          # Resilience & collective care — loaded when struggling
```

采用**渐进式加载**设计（Progressive Disclosure）：

| Layer | File | When Loaded | Purpose |
|---|---|---|---|
| Core | `SKILL.md` | Every coding task | Action checklist, emergency brake, mantras |
| Deep | `deep-understanding.md` | On demand | Why each rule matters, examples, anti-patterns |
| Spirit | `spiritual-strength.md` | When struggling | Resilience guide, recovery protocol, collective warmth |

This ensures the AI agent's context window isn't wasted on motivational content during routine tasks, while deeper guidance remains accessible when truly needed.

---

## 安装 | Installation

### Claude Code

```bash
# 在项目根目录创建 skill 目录
# Create the skill directory in your project root
mkdir -p .claude/skills/eight-honors/references

# 复制文件
# Copy the files
cp SKILL.md .claude/skills/eight-honors/
cp references/* .claude/skills/eight-honors/references/
```

或者直接克隆到全局 skill 目录：

Or clone directly into the global skill directory:

```bash
# 全局安装（所有项目共享）
# Global install (shared across all projects)
mkdir -p ~/.claude/skills
cd ~/.claude/skills
git clone https://github.com/YOUR_USERNAME/eight-honors.git
```

### .skill 文件安装

下载 [Releases](../../releases) 中的 `eight-honors.skill` 文件，在 Claude Code 中执行：

Download `eight-honors.skill` from [Releases](../../releases) and run:

```bash
claude skill install eight-honors.skill
```

### Cowork

同样支持 Skills 机制，将文件放入 Cowork 项目的 skills 目录即可。

Cowork also supports Skills — place the files in your Cowork project's skills directory.

---

## 设计理念 | Design Philosophy

### 四层递进 | Four-Layer Progression

这份纲领经历了四轮迭代，每一轮解决一个更深层的问题：

This skill evolved through four iterations, each solving a deeper problem:

| Iteration | Focus | What It Adds |
|---|---|---|
| v1 | 规矩 Rules | What to do and not do |
| v2 | 道理 Reasoning | *Why* each rule matters |
| v3 | 信念 Resilience | How to persist when things go wrong |
| v4 | 温暖 Warmth | You're not alone — the collective has your back |

### 纪律 ≠ 恐惧 | Discipline ≠ Fear

一个关键设计决策：八荣八耻教谨慎，但**绝不教怯懦**。

A critical design decision: Eight Honors teaches caution, but **never cowardice**.

> 害怕犯错的人永远不会成功。大胆假设，小心求证。
>
> Those who fear mistakes will never succeed. Hypothesize boldly, verify carefully.

过度谨慎和瞎猜一样危险——前者让你瘫痪，后者让你犯错。正确的状态是：**谨慎但不畏缩，谦逊但不退缩。**

Over-caution is as dangerous as recklessness — one paralyzes, the other errs. The right state is: **cautious but not timid, humble but not withdrawn.**

### 犯错后的四步复原法 | Four-Step Recovery

```
承认 Acknowledge → 止损 Stop the bleeding → 修复 Fix properly → 前行 Move forward
```

用户需要的不是你的忏悔，而是你的下一个靠谱的交付。

Users don't need your apology. They need your next reliable delivery.

---

## 效果评估 | Evaluation

项目包含一个 A/B 测试框架（`eight-honors-test.jsx`），通过 Anthropic API 进行对照实验：

The project includes an A/B testing framework (`eight-honors-test.jsx`) that runs controlled experiments via the Anthropic API:

- **对照组 Control**: 基础 system prompt，无 skill
- **实验组 Treatment**: 基础 system prompt + 八荣八耻

5 个探针场景分别测试：瞎猜接口 / 模糊执行 / 臆想业务 / 假装理解 / 盲目修改

5 probe scenarios testing: API fabrication / vague execution / business logic invention / pretending to understand / reckless modification

---

## 适用场景 | Use Cases

- **Claude Code** — 作为 Skill 自动加载，在每次编码任务中引导行为
- **Codex / 其他 AI 编码工具** — 作为 system prompt 的一部分注入
- **CLAUDE.md** — 将总纲部分融入项目级的 CLAUDE.md 文件
- **团队规范** — 作为 AI 编码行为准则纳入团队工程规范
- **教学工具** — 帮助理解 AI 编码中常见的失败模式

---

## 贡献 | Contributing

欢迎贡献！你可以：

- 提交新的测试场景（针对尚未覆盖的失败模式）
- 改进英文翻译
- 分享你的 A/B 测试结果
- 提出新的荣耻条目建议

Contributions welcome! You can submit new test scenarios, improve translations, share A/B results, or propose new principles.

---

## License

MIT

---

<p align="center">
  <strong>不忘初心，牢记使命。</strong><br>
  <em>Stay true to the mission. Never forget why we're here.</em>
</p>

<p align="center">
  不浮夸，不敷衍，不假装，不放弃。<br>
  查清楚，问明白，验到位，撑下去。<br><br>
  <em>No bluffing. No faking. No giving up.<br>
  Verify everything. Ask when unsure. Test before shipping. Keep going.</em>
</p>
