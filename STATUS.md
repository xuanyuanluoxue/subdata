# subdata · 电脑端工程状态

> 最初由手机端 Lead 于 2026-11-13 00:52 写下的交接占位；**现已由执行子 Agent 于 00:58 完成**，
> 进度表已按实际结果更新。权威文档：`README.md`（怎么用）、`PLAN.md`（调研/设计/风险/TODO）。

## 一句话

在电脑上开发一个 Xposed/LSPosed 模块：让被注入的 App（学习通 com.chaoxing.mobile 7.0.4）
拥有多份独立数据目录，按 slot（a/b）切换，实现一台手机两个账号。

## 当前进度

| 阶段 | 状态 |
|---|---|
| 建目录 | ✅ `D:\code\android\subdata`（WSL `/mnt/d/code/android/subdata`），已 git init + 首次提交 |
| 环境探测 | ✅ JDK 21；Android SDK `~/Android/Sdk`（platform `android-37.0` + build-tools `37.0.0`）；Gradle 9.9.0 已在 `~/.gradle/wrapper/dists` 缓存；AGP 9.5.1 已缓存 |
| 开源调研 | ✅ 完成，结论见 `PLAN.md` 第一节（含 2026-11 活跃项目 `io.github.expirt.storageredirect`、`AcE77505/storage-redirect-module`；加载器用 `JingMatrix/Vector` v2.2） |
| 工程骨架 | ✅ Gradle 9.9 + AGP 9.5.1 + Java 17，传统 Xposed API（api-82，compileOnly，jar 已 vendor 到 `app/libs/`） |
| 编译通过 | ✅ `./gradlew clean assembleDebug` BUILD SUCCESSFUL；`testDebugUnitTest` 13 个测试全绿 |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk`（888 KB，com.xavier.subdata，compileSdk 37 / minSdk 26 / targetSdk 34） |

## 下一步（手机端接手）

1. 装 APK → LSPosed/Vector 启用模块 → 作用域勾 `com.chaoxing.mobile` → 强制停止该 App
2. 首次启动后看 `su -c 'cat /data/data/com.chaoxing.mobile/subdata/subdata.log'`，应为 `installed slot=a hooks=..`
3. 切成干净第二账号：
   `su -c 'echo clone_on_first_run=false >> /data/data/com.chaoxing.mobile/subdata/subdata.conf'`
   `su -c 'echo b > /data/data/com.chaoxing.mobile/subdata/slot; am force-stop com.chaoxing.mobile'`
4. 回滚：LSPosed 里取消作用域即可，原始数据从未被改动

## 禁止（仍然有效）

- 不要改系统分区、不要 hook system_server
- 不要执行删盘/格式化/重启

## v0.3.0-beta（B 线四期）

主题：**看得见 / 切得动 / 救得回**。没有新增任何 hook，没动 `PathMap` / `SubDataModule` 的映射逻辑。

| 功能 | 落点 |
|---|---|
| 每槽数据占用 | `SlotStats`（纯 Java，du 解析 + 体积格式化）+ `TargetRepo.slotSizes()` |
| 首页一键切槽 | `TargetRepo.switchAndStop()` + `MainActivity` 槽 chip 行 |
| 删除单个副本 | `TargetRepo.deleteSlot()` + `SlotActivity` 动作菜单（双重确认 + 当前槽保护） |
| 配置体检 | `HealthCheck`（INFO/WARN/DANGER 三级）+ 首页 `tv_health` |
| 诊断包导出 | `Diagnostics` + `LogActivity` 按钮 + FileProvider 分享 |
| 越界防护 | `SlotConfig.sanitizePkg()` + `TargetRepo.validPkg()` 守卫所有 rm/mkdir/写文件入口 |

- 单测：74 例全绿（含 v0.2.1 带入的 25 例；本版新增 27 例）
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（versionCode 4 / versionName 0.3.0-beta）
- 产品规划与被否决项：见 `docs/PRODUCT-v030.md`
- 待真机验证：超级岛「副本数据」提示、诊断包分享面板、真实目标 App 的槽体积与切槽


## v0.3.1-beta（B 线五期）

依据 Lead 的真机实测（槽隔离成立；超级岛失败于权限；prefs 不可读；EXT 树不克隆）：

| 变更 | 落点 |
|---|---|
| 超级岛改由模块 App 发 | `IslandNotice`（发送端，只发显式广播）+ `IslandBroadcast`（协议/校验）+ `IslandReceiver` + `IslandPoster`（模块 App 身份 post）；模块 App 新声明 `POST_PROMOTED_NOTIFICATIONS` + `POST_NOTIFICATIONS` |
| 白名单生效 | manifest `<meta-data name="xposedsharedprefs">`；`SubDataModule#readTargets()` 打印 prefs 路径/可读性/原始值；不再依赖改 `DEFAULT_TARGETS`；常量搬进纯 Java `ModuleMeta` |
| EXT 树克隆 | `ExtCloner`：`Android/data/<pkg>/` → `<ext>/subdata/<slot>/`，排除 `subdata`/`cache`/`code_cache`/`no_backup`/`lib`；>512MB / >25s / >2 万条目 → 只建骨架 + 跳过标记 |
| 半成品自愈 | `SlotState`：`.cloning-<slot>` / `.cloned-<slot>` / `.clone-skipped-<slot>`；`.cloning` 残留 → 清掉重做 |
| 原子写 | `AtomicFiles`（tmp + fsync + rename）+ `RootShell#writeFileAtomic`（`mv -f`）覆盖 `slot` 与 `subdata.conf` |

- 单测：118 例全绿（新增 44 例：`SlotStateTest` / `ExtClonerTest` / `AtomicFilesTest` / `IslandBroadcastTest` / `IslandDebounceTest`）
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（versionCode 5 / versionName 0.3.1-beta）
- 模拟器（emulator-5554）冒烟：装包 + 四个 Activity 不崩 + 白名单写入可读回 + 广播对非白名单发送方拒绝
- **真机待验**：真的能上岛、`POST_PROMOTED_NOTIFICATIONS` 授权结果、`xposedsharedprefs` 后直接注入 Via、EXT 克隆 83MB 的首启手感、半成品自愈日志
- 设计文档：`docs/PLAN-v031.md`


## v0.4.0（B 线七期）· MCP 服务

主题：**让 AI 客户端能查、能切、能取证**，不新增 hook、不改映射逻辑。

| 交付 | 落点 |
| --- | --- |
| 产品分析 | `docs/PRODUCT-MCP.md`（3 个只读 PM agent + 汇总裁决；11 个工具；否决 5 项） |
| 协议文档 | `docs/MCP.md`（方法、鉴权、工具表、curl 示例、客户端配置、排障） |
| 传输层 | `McpServer`（自写 HTTP）+ `McpHttp`（请求解析）+ `McpToken`（Bearer，600 权限） |
| 协议层 | `McpDispatcher`（initialize / tools/list / tools/call / ping / notifications） |
| 工具层 | `McpTools`（11 个工具 + 白名单/sanitize/confirm 守卫），后端接缝 `McpBackend` / `AndroidMcpBackend` |
| 生命周期 | `McpManager`（设置/单例/状态）+ `McpService`（前台服务保活） |
| UI | 首页「MCP 服务」卡片：开关、端口、token 复制/重置、运行状态、请求数、局域网风险确认 |
| 单测 | **193 例全绿**（新增 `JsonTest` / `McpTokenTest` / `McpHttpTest` / `McpDispatcherTest` / `McpToolsTest`） |
| 产物 | `app-debug.apk`（versionCode 7 / versionName 0.4.0），md5 见提交说明 |

### 真机实测（小米 M332BF / Android 17 / KernelSU + Vector 2.2，目标 `mark.via` 7.3.4）

- 安装 0.4.0 → Vector 作用域 `mark.via`（`scope ls` 确认只有它）→ Via 注入正常（`installed slot=a hooks=21`）
- 电脑 `adb forward tcp:8773 tcp:8773` 后 curl：`initialize` / `tools/list` / `status` / `slots.list` /
  `slots.switch a→b→a` / `delete`（无 confirm 被拒）/ `health.check` / `log.tail` / `diagnostics.export` 全部通过
- 切槽验证：`slot=b` → 启动 Via → 日志 `installed slot=b ... main=no`；切回 a → `installed slot=a ... main=yes`
- 鉴权：错 token → 401；学习通包名 → `包名不在白名单内` 拒绝
- 结束时：Via 留在 slot a、局域网关闭（`host=127.0.0.1`）、MCP 服务运行中

### 真机发现并修复

模块 App 的 mount namespace 被裁剪（`/data/data` 仅 3 条），`test -d /data/data/mark.via` 返回 1。
`RootShell` 增加 `nsenter -t 1 -m` 自动逃逸（探测可用才启用），修复后 `slots.list` 正常返回体积。

另修：进程重启后 SharedPreferences 读到空表，导致「局域网」开关自己变回开。安全开关改为
`files/mcp.lan`（fail-closed：读不到按关），实测 40s 内稳定 `127.0.0.1`，局域网 IP 连接被拒。
