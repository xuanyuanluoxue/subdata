# SubData v0.4.0 · MCP 产品需求分析

> 产出方式：在工程机上用 `opencode` 起 3 个**只读** PM 视角 Agent（`pm-power` / `pm-privacy` / `pm-student`），
> 各自读代码（`TargetRepo` / `RootShell` / `SlotConfig` / `Diagnostics` / `HealthCheck` / Manifest）后独立提 TOP5；
> 本文是汇总、裁决与落地清单。三个 Agent 的原始结论高度一致，分歧点在第 4 节显式裁决。
>
> 时间：2026-11-13 ｜ 基线：`feat/v0.3.1`（0.3.1-beta，versionCode 5）

---

## 1. MCP 到底给谁用、解决什么痛点

**给「已经在用 AI 助手（DSH / 小爱 / 任意 MCP 客户端）的重度用户」用，不是给普通用户的新入口。**

现状的痛点是操作链太长且状态不可见：

| 痛点 | 现状 | MCP 之后 |
| --- | --- | --- |
| 切槽 | 进 App → 找槽 → 点确认 → 再手动强停目标 App（5–7 步） | 一句「切到 b」 |
| 看状态 | 必须点亮屏幕进 App，才知道当前是哪个槽、有没有生效 | `subdata.status` 一行 JSON |
| 看日志/体检 | 只能进 App 翻日志页 | `subdata.log.tail` / `subdata.health.check` |
| 排障取证 | 手动导出诊断包再发出来 | `subdata.diagnostics.export` 直接拿全文+路径 |

本质上 MCP **不新增任何 root 能力**，只是给已有的 `TargetRepo`（读槽、切槽、force-stop、删槽）
和 `Diagnostics`（拼报告）加一层语义化入口。风险面没有变大，**但暴露方式变了**：从「人手点确认框」变成
「AI 可以一次调用直接执行」，所以危险操作必须在协议层自带守卫。

---

## 2. 三个视角的 TOP5（原文归纳）

### 2.1 pm-power（效率工具视角）

| # | 需求 | 结论 |
| --- | --- | --- |
| 1 | 查当前状态（slot/conf/体积/体检/最后一条 installed） | 低危、最高频，MCP 的入口价值 |
| 2 | 一键切槽（写 slot + force-stop） | 高危，但这是 MCP 存在的理由 |
| 3 | 日志与诊断导出 | 低危，排障闭环 |
| 4 | 列槽 + 每个槽体积 | 低危，老痛点「看不到占用」 |
| 5 | 新建/删除槽 | 删除中高危，必须二次确认 |

否决：监听 `0.0.0.0`、暴露 `deleteControlDir`（删整个 subdata）、让 AI 读账号数据内容。

### 2.2 pm-privacy（隐私与稳定视角）

| # | 需求 | 结论 |
| --- | --- | --- |
| 1 | `list_slots`（含 `SlotState` 半成品态） | 低危；能直接暴露 `.cloning` 残留 |
| 2 | `health`（切槽前先体检） | 低危；挡住「目录不存在但 clone=false」这类必崩场景 |
| 3 | `switch_slot`（必须 sanitize + 说明「异步生效」） | 高危 |
| 4 | `read_log`（硬上限 + 脱敏提示） | 中危（日志会经 AI 上云） |
| 5 | `export_diagnostics`（落文件，别直接灌全文） | 中危 |

否决：任意文件读写、AI 全自动闭环（自动切回/自动删槽）、局域网开放。
额外指出两条最易忽略的风险：**127.0.0.1 不是安全边界（同机任何 App 都能连）**、
**日志/诊断会经客户端上传云端**。

### 2.3 pm-student（终端用户视角）

| # | 需求 | 结论 |
| --- | --- | --- |
| 1 | `subdata.status` | 低危，回答一切问题的前提 |
| 2 | `subdata.switch_slot` | 高危，核心价值 |
| 3 | `subdata.read_log` | 低危，「App 更新后失效」的唯一线索 |
| 4 | `subdata.export_diagnostics` | 低危 |
| 5 | `subdata.set_conf`（逃生通道：关 enabled/关 hook） | 中危，被本轮否掉（见 4.2） |

否决：MCP 暴露 `delete_slot` / `delete_control_dir`（无 UI 式双手确认）、通用 shell 透传、监听 0.0.0.0。
风险：Manifest 缺 `INTERNET`、su 并发（多路写操作同时弹授权框会互相干扰）、进程被冻结后假死。

---

## 3. 落地决策：11 个工具

判定标准只有三条：**能复用现成能力、有明确使用场景、危险度可被协议层约束住**。

| # | Tool | 入参 | 危险 | 复用 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 1 | `subdata.status` | — | low | `TargetStore`+`TargetRepo` | 版本/root/每个目标的 slot 与体积/MCP 自身状态 |
| 2 | `subdata.targets.list` | — | low | `TargetStore` | 白名单 + 当前选中 |
| 3 | `subdata.targets.add` | `pkg` | medium | `TargetStore` | 只写白名单，不碰数据；要求包已安装 |
| 4 | `subdata.targets.remove` | `pkg` | medium | `TargetStore` | 只移出白名单，**不删**数据目录 |
| 5 | `subdata.slots.list` | `pkg` | low | `TargetRepo.listSlots/slotSizes` | 含每个槽体积与当前标记 |
| 6 | `subdata.slots.create` | `pkg,slot` | medium | `TargetRepo.createSlot` | 只建空目录，克隆交给目标 App 首次启动 |
| 7 | `subdata.slots.switch` | `pkg,slot` | **high** | `TargetRepo.switchAndStop` | 写 slot + `am force-stop`，返回 `force_stopped:true` |
| 8 | `subdata.slots.delete` | `pkg,slot,confirm` | **high** | `TargetRepo.deleteSlot` | `confirm=true` 才执行；当前槽/主槽一律拒绝 |
| 9 | `subdata.log.tail` | `pkg,lines` | low | `TargetRepo.readLogTail` | `lines` 夹在 1..200 |
| 10 | `subdata.health.check` | `pkg` | low | `HealthCheck.inspect` | 复刻首页体检 |
| 11 | `subdata.diagnostics.export` | `pkg?` | low | `Diagnostics.buildReport` | 返回全文 + 落盘路径，省一次文件传输 |

**安全守卫（写死在工具层，不靠客户端自觉）**：

1. 所有 `pkg` 必须过 `SlotConfig.sanitizePkg`（挡 `..`/`/`）**且**在白名单内；
   `targets.add` 例外——它只校验格式，否则永远加不进新包。
2. 所有 `slot` 必须匹配 `[A-Za-z0-9_-]{1,32}` 且不等于控制目录名 `subdata`。
3. `delete` 必须 `confirm=true`（字符串 `"false"` 不认）；当前槽、主槽（`main_slot`）直接拒绝。
4. `switch` 目标槽必须已存在；已在目标槽时返回 `changed:false`，不做无谓的 force-stop。
5. 所有 root 读写仍然只落在 `/data/data/<pkg>/subdata` 内，绝不碰原始数据目录。

---

## 4. 被否决项（以及为什么）

### 4.1 三份分析一致否决

| 想法 | 理由 |
| --- | --- |
| **监听 0.0.0.0 / 局域网默认开放** | 三个 Agent 全部反对。把 root 级操作暴露到 Wi-Fi 是越界。**本版折中**：默认只绑 `127.0.0.1`，局域网是设置里默认关闭的一档，打开要过风险弹窗——保留能力但把默认踩死。 |
| **MCP 暴露任意 shell / 文件读写** | 等于把 `RootShell.run` 交给 AI；也破坏「只隔离数据」的定位，可能把 cookie/登录态直接送进 LLM。 |
| **暴露 `deleteControlDir`（删整个 subdata）** | AI 一句「清理干净」就团灭所有槽。删除粒度只到单个 slot，且要 confirm。 |
| **读目标 App 的账号数据内容** | 违反模块定位与隐私红线；日志已经够排障了。 |
| **AI 全自动闭环（异常自动切回/自动删槽）** | 写操作无人工确认，误判就删数据。MCP 只暴露原语，编排交给人或上层 Agent。 |

### 4.2 单独裁决

| 想法 | 裁决 | 理由 |
| --- | --- | --- |
| `subdata.set_conf`（pm-student 提议的逃生通道） | **本轮不做** | 配置键写错会让注入直接失效且界面无提示；需要先做「键值白名单 + 写入前体检」，排到 0.4.x。 |
| SSE / 资源 / 提示词 / 批量 JSON-RPC | **不做** | 客户端（DSH 等）用 `POST /mcp` 的单请求单响应就够；多一层长连接等于多一份保活负担。 |
| 独立的 MCP 设置页 | **不做，放首页卡片** | 4 个 Activity 已够多；首页一屏能看到「运行中 / 请求数 / token」更符合排查习惯。 |
| 工具级限流 | **本轮不做** | 单机、单用户、Bearer 已挡住非授权调用；记入待办（见第 6 节）。 |

---

## 5. 交付形态

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 传输 | **JSON-RPC 2.0 over HTTP，`POST /mcp`** | MCP 规范的最小可用集；不需要 SSE 就能跑 `initialize` / `tools/list` / `tools/call` |
| 实现 | 模块 App 内自写 `ServerSocket` HTTP（约 300 行） | Android 没有 `com.sun.net.httpserver`；NanoHTTPD/Ktor 会给 5 个类的模块平白加依赖 |
| 端口 | 默认 **8773**，可改（1024–65535） | 不常见端口，避免和 Immich/网关联调时撞车 |
| 监听 | 默认 `127.0.0.1`；局域网需显式打开 + 风险确认 | 见 4.1 |
| 鉴权 | `Authorization: Bearer <token>`，token 在 `files/mcp.token`（600） | 不进 SharedPreferences（那份会被 `xposedsharedprefs` 开放给目标进程）、不进日志/诊断 |
| 保活 | 前台服务 `McpService`（LOW 通知） | 这是评审里被点三次的风险：App 被冻结后客户端全部超时 |
| 并发 | HTTP 4 线程接入，**工具执行单线程串行** | 切槽 + force-stop 不是并发安全的；也避免多路 `su` 互相干扰 |

---

## 6. 风险登记（按优先级）

| # | 风险 | 缓解 | 状态 |
| --- | --- | --- | --- |
| 1 | App 被冻结 → 客户端超时 | 前台服务 + START_STICKY；关闭开关即停服务、关端口 | ✅ 已实现 |
| 2 | `127.0.0.1` 对同机所有 App 可达，token 是唯一防线 | 256-bit `SecureRandom`、600 权限、常量时间比较、401 计数、UI 可一键重置 | ✅ 已实现 |
| 3 | MCP 让「读日志」一键化，日志可能经客户端上云 | 返回长度上限（200 行）、文档明示「日志含包名与路径」 | ⚠️ 部分（未做自动脱敏） |
| 4 | 多路 `su` 并发弹授权框互相干扰 | 所有工具调用串行化（单线程执行器） | ✅ 已实现 |
| 5 | 工具级限流 / 暴力猜 token | 未做；token 熵足够，401 计数可观测 | ⏳ 待办 |
| 6 | 设备侧 mount namespace 被裁剪（实测新增，见 `docs/MCP.md` 第 7 节） | `RootShell` 自动探测并 `nsenter -t 1 -m` 回全局 namespace | ✅ 已实现（本次真机调试发现） |

---

## 7. 结论

- MCP 的价值不在「多一个接口」，而在**把强停 + 写 slot + 读状态合成一次幂等调用**；
- 三个视角的共识是：**能力可以给，确认和边界必须自己扛**——所以 `delete` 强制 confirm、`pkg` 强制白名单、
  `slot` 强制 sanitize、默认只听 loopback；
- 三个视角的分歧（局域网）用「默认关 + 显式风险确认」折中，既保留场景又不让它成为默认攻击面。
