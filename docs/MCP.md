# SubData MCP 服务

v0.4.0 起，SubData 模块 App 内置一个 **MCP（Model Context Protocol）服务**，让 DSH、小爱或任意支持
MCP 的 AI 客户端直接查询状态、切槽、看日志、导出诊断，不必人工点界面。

- 传输：**JSON-RPC 2.0 over HTTP**，端点 `POST /mcp`
- 默认监听：`127.0.0.1:8773`（只本机）
- 鉴权：**所有请求**都要带 `Authorization: Bearer <token>`
- 保活：前台服务（低优先级常驻通知），关掉开关即停

---

## 1. 快速开始

```text
1. 打开 SubData → 首页「MCP 服务」→ 启用 MCP 服务
2. 复制「访问令牌」
3. 客户端填 http://127.0.0.1:8773/mcp  + Bearer <token>
```

**电脑调试（手机与电脑同内网时）**：

```bash
adb connect 192.168.0.118:5555          # 手机侧白名单只放行这台电脑
adb forward tcp:8773 tcp:8773           # 把手机的 127.0.0.1:8773 映射到电脑
curl -s http://127.0.0.1:8773/mcp ...
```

> 服务只监听手机本机回环，**不要**为了电脑直连去开「允许局域网访问」——用 `adb forward` 更安全。

---

## 2. 协议与鉴权

### 2.1 支持的方法

| 方法 | 说明 |
| --- | --- |
| `initialize` | 握手，返回 `protocolVersion`（2025-06-18）、`capabilities.tools`、`serverInfo` |
| `notifications/initialized` | 通知，**无 id**，服务器回 `202` 空体 |
| `tools/list` | 返回 11 个工具及其 `inputSchema`、`annotations` |
| `tools/call` | `params.name` + `params.arguments`，返回 `content[]` + `isError` |
| `ping` | 健康探测，返回空 result |

不支持：SSE、批量（batch）请求、资源（resources）、提示词（prompts）。

### 2.2 HTTP 语义

| 情况 | 状态码 | 响应体 |
| --- | --- | --- |
| 正常 | `200` | JSON-RPC 响应 |
| 通知（无 id） | `202` | 空 |
| 无/错 token | `401` | `{"error":{"code":-32001,...}}` + `WWW-Authenticate: Bearer` |
| 方法不是 POST / 路径不对 | `405` / `404` | JSON-RPC error |
| JSON 解析失败 | `400` | `-32700 parse error` |
| 请求体 > 256 KiB | `400` | `bad request` |

### 2.3 Token

- 生成：`SecureRandom` 32 字节 → URL-safe Base64（43 字符）
- 位置：`/data/data/com.xavier.subdata/files/mcp.token`，权限 `600`
- 不进 SharedPreferences（那份会被 `xposedsharedprefs` 暴露给目标进程）、不进 `mcp.log`、不进诊断报告
- UI 可「复制 / 重置」；重置后旧客户端全部 401
- 比较用 `MessageDigest.isEqual`（常量时间）

局域网开关**不用 SharedPreferences**，而是 `files/mcp.lan`（`1` 开 / `0` 关，读不到一律按关）。
原因：实测重启进程后 SharedPreferences 会读到空表，安全开关不能有这种不确定性（fail-closed）。

---

## 3. 工具清单

危险级别会写进 `annotations.danger`，同时映射 MCP 官方 `readOnlyHint` / `destructiveHint`。

| Tool | 入参 | 返回要点 | 危险 |
| --- | --- | --- | --- |
| `subdata.status` | — | 版本、root、每个目标的 slot/槽/体积、MCP 自身状态 | low |
| `subdata.targets.list` | — | 白名单 + 当前选中 | low |
| `subdata.targets.add` | `pkg` | 加入白名单（要求已安装，只改白名单） | medium |
| `subdata.targets.remove` | `pkg` | 移出白名单（**不删数据**） | medium |
| `subdata.slots.list` | `pkg` | `current` + 每槽 `bytes/size/current` | low |
| `subdata.slots.create` | `pkg,slot` | 新建空槽目录 | medium |
| `subdata.slots.switch` | `pkg,slot` | `from/to/changed/force_stopped` | **high** |
| `subdata.slots.delete` | `pkg,slot,confirm=true` | 删除该槽目录；当前槽/主槽拒绝 | **high** |
| `subdata.log.tail` | `pkg,lines(1..200)` | 注入日志尾部文本 | low |
| `subdata.health.check` | `pkg` | `worst` + `issues[]` | low |
| `subdata.diagnostics.export` | `pkg?` | 报告全文 + 落盘路径 | low |

### 3.1 守卫规则（协议层强制，不靠客户端自觉）

- `pkg`：必须匹配 `sanitizePkg`（挡 `..`/`/`）**且**在白名单内；`targets.add` 只校验格式。
- `slot`：`[A-Za-z0-9_-]{1,32}`，且不能是控制目录名 `subdata`。
- `slots.delete`：`confirm` 必须是布尔 `true`；删当前槽、删主槽（`main_slot`）一律拒绝。
- `slots.switch`：目标槽必须已存在；已在目标槽时返回 `changed:false`（不重复强停）。
- 所有 root 操作只落在 `/data/data/<pkg>/subdata`，绝不碰原始数据目录。

### 3.2 返回结构

`tools/call` 永远返回 MCP 规范结构：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{"type": "text", "text": "{\"ok\":true,...}"}],
    "isError": false
  }
}
```

`content[0].text` 是一段 JSON 字符串（方便 `jq`）。业务失败（白名单外、槽不存在、缺 confirm）
走 `isError: true` + `{"ok":false,"error":"..."}`，**不是**协议错误；只有协议层问题才用 `error` 字段。

---

## 4. curl 示例

```bash
TOKEN=$(cat token.txt)
B=http://127.0.0.1:8773/mcp
H=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")

# 握手
curl -s "${H[@]}" -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' $B

# 列工具
curl -s "${H[@]}" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' $B | jq '.result.tools[].name'

# 看状态
curl -s "${H[@]}" -d '{"jsonrpc":"2.0","id":3,"method":"tools/call",
  "params":{"name":"subdata.status","arguments":{}}}' $B | jq -r '.result.content[0].text' | jq .

# 列槽
curl -s "${H[@]}" -d '{"jsonrpc":"2.0","id":4,"method":"tools/call",
  "params":{"name":"subdata.slots.list","arguments":{"pkg":"mark.via"}}}' $B

# 切槽（会 force-stop 目标 App）
curl -s "${H[@]}" -d '{"jsonrpc":"2.0","id":5,"method":"tools/call",
  "params":{"name":"subdata.slots.switch","arguments":{"pkg":"mark.via","slot":"b"}}}' $B

# 删槽（必须 confirm=true）
curl -s "${H[@]}" -d '{"jsonrpc":"2.0","id":6,"method":"tools/call",
  "params":{"name":"subdata.slots.delete",
            "arguments":{"pkg":"mark.via","slot":"c","confirm":true}}}' $B

# 错误示范：白名单外的包
curl -s "${H[@]}" -d '{"jsonrpc":"2.0","id":7,"method":"tools/call",
  "params":{"name":"subdata.slots.list","arguments":{"pkg":"com.example.other"}}}' $B
# → {"ok":false,"error":"包名不在白名单内，拒绝操作：..."}

# 错误示范：无 token
curl -s -o /dev/null -w "%{http_code}\n" -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' $B
# → 401
```

---

## 5. 客户端接入

### DSH / 任意 MCP 客户端（HTTP 传输）

```json
{
  "mcpServers": {
    "subdata": {
      "type": "http",
      "url": "http://127.0.0.1:8773/mcp",
      "headers": {"Authorization": "Bearer <粘贴 UI 里的 token>"}
    }
  }
}
```

如果客户端只支持 stdio，可用一层转发（电脑侧）：

```bash
# 需自行安装 mcp-proxy 之类；本质是把 stdio 的 JSON-RPC 转成 POST /mcp
mcp-proxy --url http://127.0.0.1:8773/mcp --header "Authorization: Bearer $TOKEN"
```

### 推荐给模型的用法

1. 先 `subdata.status` 确认 root 与目标；
2. 切槽前 `subdata.slots.list` 确认槽存在；
3. `subdata.slots.switch` 会**强停**目标 App——先提示用户，再调用；
4. 排障顺序：`subdata.log.tail` → `subdata.health.check` → `subdata.diagnostics.export`。

---

## 6. 排障

| 现象 | 检查 |
| --- | --- |
| 401 | token 是否复制完整；是否刚点过「重置」 |
| 连不上（电脑） | `adb forward --list`；服务是否「运行中」；关掉再打开开关 |
| 请求 0 次但显示运行中 | 端口可能被别的进程占用：改端口后重新启用 |
| `目标 App 不可用（未安装或数据目录不可读）` | 见第 7 节 namespace 问题 |
| 明明关了局域网却在听 `0.0.0.0` | 检查 `files/mcp.lan` 是否为 `0`；旧版本读 SharedPreferences 会残留 true |
| App 被杀后客户端超时 | 前台服务应保持常驻；确认通知栏有「SubData MCP 运行中」 |

日志：模块私有目录 `files/mcp.log`（超过 512 KiB 轮转成 `mcp.log.1`），记录启动、每条请求的方法/工具/耗时、
401 与解析错误。**不记录 token、不记录请求体全文。**

---

## 7. 已知限制 / 本次真机调试发现的问题

### 7.1 mount namespace 被裁剪（已修复）

小米 Android 17 + KernelSU 环境下，**模块 App 自己的 mount namespace 里 `/data/data` 只剩 3 个条目**
（gms / 自己 / aiservice），目标 App 的目录完全不可见：

```text
su -c "test -d /data/data/mark.via"   → 退出码 1，无输出
nsenter -t <模块pid> -m -- ls /data/data | wc -l  → 3
（全局 namespace：493）
```

后果：`TargetRepo.appInstalled()` 返回 false，界面显示「未安装」，切槽全部失败——**这不是权限问题**。
修复：`RootShell` 启动时探测 `nsenter -t 1 -m -- /system/bin/true`，可用就把所有 root 命令包一层
`nsenter -t 1 -m -- /system/bin/sh -c '<cmd>'`，不可用则保持原行为（老 ROM 兼容）。

### 7.2 其他

- 不做 SSE，长连接客户端请用轮询或 HTTP 传输；
- 明文 HTTP：只在本机回环或经 `adb forward` 使用，**不要**在不可信 Wi-Fi 上开局域网模式；
- 目标 App 未运行时也能查状态（读文件即可），但 `switch` 的强停对已停止的 App 是 no-op；
- 工具调用串行执行：并发 10 个请求会排队，单个 `su` 最坏 12s 超时。
