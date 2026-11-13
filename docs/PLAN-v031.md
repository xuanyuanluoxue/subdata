# v0.3.1 设计（0.3.1-beta / versionCode 5）

> 依据：Lead 在真机（HyperOS + LSPosed）上对 Via / 学习通 / dsh_app 的实测结论。
> 本文只写「为什么这么做」和「协议长什么样」，操作步骤见 README。

## 0. 本期要解决的问题（真机实测）

| # | 现象 | 根因 |
| --- | --- | --- |
| 1 | 超级岛不上岛 | 模块在**目标 App 进程**里发通知 → 通知归属目标 App；Via / 学习通没有 `android.permission.POST_PROMOTED_NOTIFICATIONS`，永远拿不到 `PROMOTED_ONGOING`；反射 `promoteOngoing` 也拿不到隐藏方法（`island: promoteOngoing not found (non-MIUI?), skip`）。对照：`io.github.ccch1mneyyy.dsh_app` 声明了该权限且 `granted=true`，所以它能上岛 |
| 2 | 白名单不生效 | 目标进程用 `XSharedPreferences` 读模块 prefs，但 manifest 没声明 `xposedsharedprefs` → 读到空值。上一轮只能临时改 `DEFAULT_TARGETS` 才注入 Via |
| 3 | 换槽后壁纸丢失 | 只克隆 CE（`/data/data/<pkg>`）。Via 的壁纸在 EXT（`/storage/emulated/0/Android/data/mark.via/files/content/`），新槽只有空骨架 → Lead 手工拷 83MB 才恢复 |
| 4 | 半成品槽被当成品 | 克隆没有「进行中/完成」状态，83MB 拷到一半被 kill 后，目录里一半文件、无标记，下次启动直接当成品用 |

槽隔离本身是成立的（Via 实测：a 照片壁纸 / b 纯蓝 / 切回 a 照片回来，数据不串），
所以本期不动映射逻辑，只修上面四件事。

## 1. 超级岛改由模块 App 自己发（广播协议 v1）

### 1.1 角色

```
目标 App 进程（被注入）                     模块 App 进程（com.xavier.subdata）
┌───────────────────────────┐             ┌────────────────────────────────┐
│ SubDataModule → IslandNotice│  显式广播   │ IslandReceiver → IslandPoster  │
│ 只判断「要不要上岛」         │ ──────────▶ │ 认人 + 节流 + post/cancel       │
│ 自己绝不 notify()           │             │ 通知归属模块 App（带权限）       │
└───────────────────────────┘             └────────────────────────────────┘
```

关键点：`NotificationManager.notify()` 的道具归属是 `Context#getPackageName()`。
模块代码虽然跑在目标进程里，但拿到的 Context 是目标 App 的 → 通知归属目标 App。
所以只能请模块 App 自己发，权限与上岛资格都在它身上。

### 1.2 广播格式（跨进程契约，改名即不兼容）

```
action   com.xavier.subdata.action.ISLAND_STATE
target   component = com.xavier.subdata/.IslandReceiver     （显式组件，不用隐式广播）
extras   pkg        String   发起方包名（= 被注入的 App）
         slot       String   当前槽名
         isMainSlot boolean  是否主槽
         want       boolean  期望上岛 = island_notice && !isMainSlot
         v          int      协议版本，当前 1
```

一次广播 = 一次**状态同步**（不是「发/撤」两条命令）：接收端只做
`want ? post : cancel`，主槽 / `island_notice=false` / 无通知权限一律 cancel 并清掉残留。

### 1.3 安全：导出接收器的三道校验

`senderAllowed` / `claimedPkgAllowed`（纯逻辑，`IslandBroadcast`，有单测）：

1. `getSendingUid()`（反射取，取不到退回 `getSentFromUid()`；都取不到 → **拒绝**）
   → `PackageManager#getPackagesForUid` → 至少一个包名在模块白名单里；
2. extras 里的 `pkg` 必须既在白名单里、又真的属于该 uid（防「自称别的 App」；
   共享 uid 的粒度无法更细，这是 uid 校验的固有极限，写在这里）；
3. `slot` 过 `SlotConfig.sanitize`，非法直接丢。

### 1.4 防广播风暴

- 发送端（每个进程一份 `IslandDebounce`）：key = `pkg#slot#want`，窗口 10s；
- 接收端（模块进程一份）：同样的 10s 窗口，key 加 `#rx` 后缀；
- 只有**主进程**发（多进程 App 的推送/服务进程直接 return），并且延迟 2s 等 App 起来。

把 `want` 编进 key 是为了让「主槽 ↔ 非主槽」切换能立刻发出去，不被上一条挡住。

### 1.5 通知怎么做（`IslandPoster`）

与 v0.3.0 完全一致的那一套，只是换了发送者：
`android.requestPromotedOngoing=true` + `miui.focus.param=<JSON>`（沿用 `IslandParam`，
`business=subdata_slot`）+ `miui.focus.pics`。反射 `promoteOngoing(true)` 失败只记日志。

三级退化，任何一级失败都不崩：

1. `POST_NOTIFICATIONS` 没开（API 33+ 运行时权限；首页第一次进入时申请一次）→ cancel + 跳过；
2. `POST_PROMOTED_NOTIFICATIONS` 没拿到（非小米 / 系统没给）→ 照发，只靠 extras 尽力，日志记 `promotedPerm=false`；
3. `promoteOngoing` 不存在 → 同上，日志 `promoteOngoing not found (non-MIUI?), rely on extras only`。

> **模拟器验不了超级岛**（没有 HyperOS 的通知岛、也没有 LSPosed 注入）。
> 本期在模拟器只能验：APK 安装、四个 Activity 不崩、白名单写入后能读回、
> 广播接收器对「非白名单发送方」正确拒绝（`adb shell am broadcast` 的 shell uid 会被拒，
> logcat 里能看到 reject）。真正上岛**必须真机待验**。

## 2. 白名单修复（`xposedsharedprefs`）

- manifest 增加 `<meta-data android:name="xposedsharedprefs" android:value="true"/>`；
  LSPosed 据此允许被授权的目标进程读模块 prefs；
- `SubDataModule#readTargets()` 额外做三件事：
  - best-effort `XSharedPreferences#makeWorldReadable()`（对不认 meta-data 的实现兜底）；
  - 把 **prefs 路径 + readable 标志 + 读到的原始值** 写进 `subdata.log`，
    以后能一眼分清「没配内容」还是「没权限读」；
  - 读不到时退回 `DEFAULT_TARGETS`（`com.chaoxing.mobile`）并**明确记录**在退化。
- 删除对 `DEFAULT_TARGETS` 的临时 hack 依赖（代码里不再出现 `mark.via`），白名单真正生效；
- `TargetStore` 不再引用 `SubDataModule` 的静态成员（那是 Xposed 类，App 进程里加载不了），
  常量统一搬进纯 Java 的 `ModuleMeta`。

## 3. EXT 树克隆（`ExtCloner`）

真机证据：Via 壁纸 `.../Android/data/mark.via/files/content/`，新槽只有空骨架。

```
源   /storage/emulated/0/Android/data/<pkg>/
目标 /storage/emulated/0/Android/data/<pkg>/subdata/<slot>/
```

- 与 CE 的 `DataCloner` 共用同一套状态机（见 §4），`base` 换成 EXT 根；
- 排除：`subdata`（自身，防递归）、`cache`、`code_cache`、`no_backup`、`lib`；
- 三道闸：体积 > **512MB** 只建骨架并写跳过标记；单次 > **25s** 停下并写跳过；
  条目 > **20000** 停下并写跳过；
- `clone_on_first_run=false` 或切槽后发现骨架缺失 → 只建骨架（`files/cache/no_backup`
  + 顶层目录结构镜像，打 `.skeleton` 标记，每槽一次）；
- 属主/权限/SELinux 标签不用管：代码跑在目标进程内，
  系统在 mkdir 时自动给 `uid:ext_data_rw(1078)`、`2770`、`media_rw_data_file`；
- 顺序：**必须在 `PathMap` 装上之前**做，否则拷贝时读到的已经是重定向后的路径。

## 4. 半成品自愈 + 原子写

### 4.1 状态机（`SlotState`，CE 与 EXT 共用）

```
            begin()                    complete()
  NONE ───────────────▶ CLONING ─────────────────▶ CLONED
   ▲                      │  ▲
   │   recoverHalfCloned  │  │ begin()（重做）
   └──────────────────────┘  │
                             ▼
                          SKIPPED（超限/超时，sticky，不再重试）
```

标记都放在 `<base>/subdata/` 下（不在槽目录里，避免被自己克隆进去）：

| 文件 | 含义 | 备注 |
| --- | --- | --- |
| `.cloning-<slot>` | 克隆进行中 | 残留 = 上次没做完 |
| `.cloned-<slot>` | 完整克隆成功 | 判定优先级最高（非 CLONING 时） |
| `.clone-skipped-<slot>` | 主动跳过 | 内容是原因文本，sticky |
| `<slot>/.cloned` | 0.3.0 的旧标记 | 仍然认，兼容回退 |

判定优先级：**CLONING > CLONED > SKIPPED**。有 `.cloning` 残留就说明这个槽从来没有完成过，
`recoverHalfCloned()` 会把整个槽目录删掉再重做 —— 留着一半文件比删掉更危险
（会被当成品用，缺哪些文件没人知道）。

### 4.2 原子写（`AtomicFiles` + `RootShell#writeFileAtomic`）

`subdata/slot` 与 `subdata/subdata.conf` 是模块唯一的权威配置，写一半掉电 =
下次启动退回默认 slot = 用户看到的「数据不见了」。

- 进程内：`AtomicFiles.write()` → 同目录 `.tmp` → `fsync` → `rename`（`ATOMIC_MOVE` 优先）；
- root 侧（界面写配置）：`echo <b64> | base64 -d > path.tmp && sync && mv -f path.tmp path || { rm -f path.tmp; echo FAIL; }`。

## 5. 测试

| 测试类 | 覆盖 |
| --- | --- |
| `SlotStateTest` | 状态机、旧标记兼容、半成品自愈、非法 slot |
| `ExtClonerTest` | 克隆/排除/骨架/体积闸/超时闸/sticky 跳过/半成品重做 |
| `AtomicFilesTest` | 原子写、无 `.tmp` 残留、UTF-8、conf round-trip |
| `IslandBroadcastTest` | `want` 规则、key、发送方与自称包名校验、slot 校验 |
| `IslandDebounceTest` | 10s 窗口、时钟回拨、key 表上限 |

单测总数 74 → **118**（`./gradlew testDebugUnitTest`）。

## 6. 真机待验（模拟器覆盖不到）

1. 超级岛真的能上岛（模块 App 自己发）；
2. `POST_PROMOTED_NOTIFICATIONS` 的实际授权结果（`dumpsys package com.xavier.subdata`）；
3. `xposedsharedprefs` 生效后白名单能否直接注入 Via（不再依赖 `DEFAULT_TARGETS`）；
4. EXT 克隆 83MB 耗时与首启手感（本次上限 512MB / 25s）；
5. 半成品自愈：克隆中途强杀再启动，日志应出现 `recover half-cloned`。

## 7. 风险

- **uid 粒度**：共享 uid 的多包 App 只要有一个在白名单，广播就放行；这是 uid 校验的固有极限。
- **EXT 体积**：512MB 上限对「壁纸 + 站点数据」够用，但对「下载/离线视频」类 App 会退化成骨架，
  用户仍可能丢数据 —— UI 与日志都会写明原因，但不会自动拷。
- **通知权限**：用户拒绝 `POST_NOTIFICATIONS` 时模块不会重试弹窗，只静默跳过（避免骚扰）。
- **非 MIUI**：`promoteOngoing` 与 `miui.focus.param` 都是小米私有，别的 ROM 上最多发一条常驻通知。
