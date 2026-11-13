# SubData 多开 —— Xposed 数据目录隔离模块

同一个 APK、同一个 uid，靠「切换数据目录」实现多账号。类似应用双开，但数据层由本模块自己实现，
**不改 APK、不动系统分区、不 hook system_server**。

| 项 | 值 |
| --- | --- |
| 模块包名 | `com.xavier.subdata` |
| 技术栈 | Java 17 + Gradle 9.9 + AGP 9.5.1，传统 Xposed API（`de.robv.android.xposed:api:82`，仅编译期） |
| compileSdk / buildTools | 37 (android-37.0) / 37.0.0 |
| minSdk / targetSdk | 26 / 34 |
| 运行环境 | Android 13–17；目标机 Android 17 (SDK 37) / HyperOS 4 / KernelSU + LSPosed 或 Vector |
| 默认白名单 | `com.chaoxing.mobile`（学习通 7.0.4） |
| 版本 | `0.2.1`（versionCode 3） |

## 1. 原理

LSPosed/Vector 把模块注入到目标 App 的每个进程，模块把「数据根」整体搬到
`/data/data/<包名>/subdata/<slot>/`：

```
/data/data/com.chaoxing.mobile/
├── files, databases, shared_prefs ...     ← App 原始数据（模块从不修改）
└── subdata/                               ← 控制目录
    ├── slot                               ← 当前 slot，一行文本，如 a
    ├── subdata.conf                       ← 可选配置
    ├── subdata.log                        ← 模块运行日志
    ├── a/                                 ← slot a 的数据根（files/ databases/ shared_prefs/ ...）
    └── b/                                 ← slot b 的数据根
        ├── databases/ shared_prefs/ ...    ← 标准骨架，不克隆也会建好
        └── .skeleton                       ← 骨架已初始化标记
```

DE（设备加密）侧同样有一套，只是内容少一点：

```
/data/user_de/0/com.chaoxing.mobile/subdata/<slot>/{cache,code_cache,shared_prefs,...}
```

被 hook 的 API 返回的路径全部指向 `subdata/<slot>/...`，App 自己完全无感。
切 slot = 改 `slot` 文件 + 杀掉进程重启，两个 slot 的数据互不可见。

## 2. 已覆盖的 API

| 层 | 方法 | 说明 |
| --- | --- | --- |
| `ContextImpl` | `getDataDir` / `getFilesDir` / `getCacheDir` / `getCodeCacheDir` / `getNoBackupFilesDir` / `getObbDir` / `getExternalCacheDir` / `getPreferencesDir` | 主路径 |
| `ContextImpl` | `getDir(String,int)` / `getDatabasePath` / `getFileStreamPath` / `getSharedPreferencesPath` / `getExternalFilesDir` | 带参数版本 |
| `ContextImpl` | `getExternalFilesDirs` / `getExternalCacheDirs` / `getExternalMediaDirs` | 返回 `File[]` |
| `SharedPreferences` | 通过 `getFilesDir` + `getSharedPreferencesPath` 自然覆盖 | 无需单独 hook |
| `SQLiteOpenHelper` | 通过 `getDatabasePath` 覆盖；另外兜底 hook `SQLiteDatabase.openDatabase(String,...)` | |
| `java.io.File` | `File(String)` / `File(String,String)` / `File(File,String)` 构造器改写 | 兜住 App 自己拼 `/data/data/<pkg>/...` 的情况，可关 |
| `WebView` | `WebViewFactory.getDataDirectory()` | best effort |

所有 hook 都单独 try/catch，某个方法在特定 ROM 上不存在只会在日志里留一行，不影响其它 hook。

## 3. 编译

要求：JDK 17+、Android SDK 37（platform `android-37.0` + build-tools `37.0.0`）。
本机（WSL）已验证可用。

```bash
cd /mnt/d/code/android/subdata
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 47 个单元测试（路径映射 / slot 校验 / 配置读写 / 目录骨架 / 超级岛 JSON）
```

`local.properties` 里的 `sdk.dir` 指向本机 SDK；换机器时改这一行或改用 `ANDROID_HOME`。

Xposed API 以 jar 形式放在 `app/libs/xposed-api-82.jar`（`compileOnly`，不会打进 APK），
来源 `https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar`。

## 4. 安装与勾选

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`，或拷到手机点安装。
2. 打开 LSPosed / Vector 管理器 → 模块 → 启用「SubData 多开」。
3. 作用域勾选 **学习通 `com.chaoxing.mobile`**（`xposedscope` 已预置）。
4. 强制停止学习通，再打开。

首次启动模块会：创建 `subdata/`、写 `slot`（默认 `a`）、把原始数据克隆进 `subdata/a/`，
然后把 App 的读写全部指过去。日志：

```bash
su -c 'cat /data/data/com.chaoxing.mobile/subdata/subdata.log'
```

## 5. 切 slot

```bash
# 切到 b（需要 root / KernelSU）
su -c 'echo b > /data/data/com.chaoxing.mobile/subdata/slot; am force-stop com.chaoxing.mobile'
```

改完必须 force-stop（或从最近任务划掉）再启动才生效——slot 在进程注入时读取一次。
模块自己的 App 首页有这行命令的「一键复制」按钮。

**想要一个干净的第二账号**（不要 A 的登录态）：

```bash
# 方式一：关掉自动克隆，新 slot 会是空目录
#   （0.2.1 起模块仍会自动建好 databases / shared_prefs / files … 标准骨架，
#    并把原数据目录的目录结构镜像成空目录，只是不拷任何文件）
su -c 'echo clone_on_first_run=false >> /data/data/com.chaoxing.mobile/subdata/subdata.conf'
su -c 'echo b > /data/data/com.chaoxing.mobile/subdata/slot; am force-stop com.chaoxing.mobile'

# 方式二：先手工建好 slot 目录并打上 .cloned 标记，等价于「已初始化过」
su -c 'mkdir -p /data/data/com.chaoxing.mobile/subdata/b && touch /data/data/com.chaoxing.mobile/subdata/b/.cloned'
```

## 6. 配置项

`/data/data/<包名>/subdata/subdata.conf`（`key=value`，`#` 注释，改完 force-stop 生效）：

| key | 默认 | 作用 |
| --- | --- | --- |
| `enabled` | `true` | 关掉后完全不重定向（等价于停用模块） |
| `clone_on_first_run` | `true` | 新 slot 首次使用时从默认数据目录克隆 |
| `redirect_external` | `true` | 连 `/sdcard/Android/data/<包名>` 一起重定向 |
| `hook_file_paths` | `true` | 兜底 hook `File` 构造器（App 拼绝对路径时）；有性能顾虑可关 |
| `hook_data_dir` | `true` | 是否 hook `ContextImpl.getDataDir()`。它对整棵路径树影响最大，App 异常崩溃时可先关它 |
| `slot` | `a` | 没有 `slot` 文件时用这个值 |
| `main_slot` | `a` | 主槽。`slot` 等于它时视为「原始数据」，不发超级岛提示 |
| `island_notice` | `true` | 非主槽时在小米超级岛提示「当前处于副本数据」 |

白名单在模块 App 首页编辑（存模块自己的 SharedPreferences），每行一个包名。

## 7. 超级岛提示（非主槽）

当 App 跑在**非主槽**（`slot != main_slot`）时，模块会在小米超级岛（HyperOS 焦点通知）
挂一条常驻提示，提醒用户「现在用的是副本数据」：

| 位置 | 内容 |
| --- | --- |
| 大岛标题 | 副本数据 |
| 大岛正文 | 当前使用 `b` 槽 · 与原数据隔离 |
| 小岛 | App 图标 + 「副本数据」 |

实现要点（v0.3.1 起改为「目标进程发广播、模块 App 发通知」）：

```
目标 App 进程（被注入）                      模块 App 进程（com.xavier.subdata）
SubDataModule → IslandNotice    ──显式广播──▶  IslandReceiver → IslandPoster
只判断要不要上岛，绝不 notify()                认人 + 节流 + post / cancel
```

- **为什么要换人发**：`NotificationManager.notify()` 的通知归属 `Context` 的包名。模块虽然跑在
  目标进程里，但拿到的是目标 App 的 Context → 通知归属目标 App；而 Via / 学习通**没有**
  `android.permission.POST_PROMOTED_NOTIFICATIONS`，永远拿不到 `PROMOTED_ONGOING`，
  反射 `promoteOngoing` 也找不到隐藏方法（`island: promoteOngoing not found (non-MIUI?), skip`）。
  对照 App `io.github.ccch1mneyyy.dsh_app` 声明了该权限、`granted=true`，所以它能上岛。
- **广播协议**：action `com.xavier.subdata.action.ISLAND_STATE`，显式组件
  `com.xavier.subdata/.IslandReceiver`，extras `pkg` / `slot` / `isMainSlot` / `want` / `v`。
  一次广播 = 一次状态同步，接收端 `want ? post : cancel`。
- **安全**（接收器是 `exported=true`）：`getSendingUid()` → `getPackagesForUid` →
  至少一个包名在白名单；extras 里的 `pkg` 还必须既在白名单、又真的属于该 uid；
  `slot` 过 `SlotConfig.sanitize`。uid 取不到时退回 `getSentFromPackage()`，都取不到就拒绝。
- **防风暴**：发送端与接收端各有一份 10s 时间窗节流（key = `pkg#slot#want`），只有主进程发。
- 通知内容不变：`android.requestPromotedOngoing=true` + `miui.focus.param=<JSON>`
  （`IslandParam#json`，`business=subdata_slot`）+ `miui.focus.pics`；`promoteOngoing` 失败只记日志。
- 模块 App 现在声明了 `POST_PROMOTED_NOTIFICATIONS`（普通权限，自动授予）与
  `POST_NOTIFICATIONS`（API 33+ 运行时权限，首次进首页申请一次）。
- 三级退化都不崩：没开通知权限 → 撤掉并跳过；没有 `POST_PROMOTED_NOTIFICATIONS` → 只靠 extras；
  没有 `promoteOngoing` → 同上。

> 模拟器**验不了**超级岛（没有 HyperOS 通知岛，也没有 LSPosed 注入）。
> 真机待验清单见 `docs/PLAN-v031.md` §6。

关掉：

```bash
su -c 'echo island_notice=false >> /data/data/com.chaoxing.mobile/subdata/subdata.conf'
su -c 'am force-stop com.chaoxing.mobile'
```

也可以在模块 App 首页「行为开关 → 超级岛提示」里点掉。主槽想换成别的名字就改
`main_slot`（比如 `main_slot=b`，那么 b 槽就不发提示、a 槽会发）。

## 8. 回滚

```bash
# 1) 只想恢复原状：LSPosed 里取消勾选作用域 → App 立刻回到原始数据（原始数据从未被改动）
# 2) 彻底清理模块痕迹：
su -c 'rm -rf /data/data/com.chaoxing.mobile/subdata'
```

## 8.5 v0.3.1 变更（0.3.1-beta / versionCode 5）

主题：**超级岛换发送者 + 白名单真生效 + EXT 树克隆 + 半成品槽自愈**（设计细节见 `docs/PLAN-v031.md`）。

| 项 | 变更 |
| --- | --- |
| 超级岛 | 目标进程不再自己发；改发显式广播，模块 App 用自己的包名 post（权限在模块 App 上） |
| 白名单 | manifest 增加 `<meta-data android:name="xposedsharedprefs" android:value="true"/>`，`XSharedPreferences` 终于能读到 UI 写入的 targets；日志会打印 prefs 路径 + readable + 原始值；不再依赖改 `DEFAULT_TARGETS` 的临时 hack |
| EXT 克隆 | 新增 `ExtCloner`：把 `Android/data/<pkg>/` 克隆到 `<ext>/subdata/<slot>/`（排除 `subdata`/`cache`/`code_cache`/`no_backup`/`lib`），>512MB 或 >25s 只建骨架并写跳过标记（Via 壁纸终于能跟着槽走） |
| 半成品自愈 | 新增 `SlotState` 状态机：`.cloning-<slot>` / `.cloned-<slot>` / `.clone-skipped-<slot>`；有 `.cloning` 残留 → 清掉重做，绝不把半成品当成品 |
| 原子写 | `subdata/slot` 与 `subdata.conf` 改为「临时文件 + `fsync` + rename」（root 侧用 `mv -f`），写一半掉电不会退回默认 slot |
| 单测 | 74 → 118（新增 `SlotStateTest` / `ExtClonerTest` / `AtomicFilesTest` / `IslandBroadcastTest` / `IslandDebounceTest`） |

## 9. 已知限制

- **native 层写死的路径拦不住**：`.so` 里直接 `open("/data/data/<pkg>/...")` 不会被 Java hook 影响。
  学习通的核心业务主要在 DEX（libDexHelper 壳保护的是 DEX），一般能覆盖，但不能保证 100%。
- **WebView** 的数据目录是 best effort；`getCodeCacheDir` 刻意保留在真实私有目录（放 slot 里意义不大且易踩坑）。
- 首次克隆发生在 App 极早期（Application.onCreate 之前），数据多时首启会慢几百毫秒到几秒。
- DE（`/data/user_de/0/<pkg>`）只建目录骨架、不克隆文件：DE 里多是迁移标记类配置，
  跟着 CE 一起复制反而容易踩坑（这也是 0.2.0 之前 DE 目录根本不存在的原因之一）。
- 只支持「同一 uid 内多份数据」。真正的双实例（两个进程同时跑、两个桌面图标）不在本模块范围。

## 10. 目录结构

```
subdata/
├── app/src/main/java/com/xavier/subdata/
│   ├── SubDataModule.java    # Xposed 入口：注入、hook 安装、日志
│   ├── PathMap.java          # 纯路径映射（无 Android 依赖，可单测）
│   ├── SlotConfig.java       # slot / 配置文件读取（含 main_slot / island_notice）
│   ├── SlotSkeleton.java     # slot 目录骨架（不克隆也要建目录；CE + DE）
│   ├── DataCloner.java       # 首次 clone（CE，接 SlotState 状态机）
│   ├── ExtCloner.java        # 首次 clone（EXT，512MB / 25s / 2 万条目三道闸）
│   ├── SlotState.java        # .cloning / .cloned / .clone-skipped 状态机 + 自愈
│   ├── AtomicFiles.java      # 临时文件 + fsync + rename 原子写
│   ├── ModuleMeta.java       # 模块包名 / prefs 名 / 权限名（纯 Java，App 侧可安全引用）
│   ├── IslandParam.java      # 超级岛 JSON / 文案（纯字符串，可单测）
│   ├── IslandNotice.java     # 发送端：判断要不要上岛 → 发显式广播
│   ├── IslandBroadcast.java  # 广播协议常量 + 发送方/包名/slot 校验（纯逻辑，可单测）
│   ├── IslandDebounce.java   # 10s 时间窗节流（纯逻辑，可单测）
│   ├── IslandReceiver.java   # 接收端：认人 → IslandPoster
│   ├── IslandPoster.java     # 用模块 App 身份 post / cancel 超级岛通知
│   └── MainActivity.java     # 模块配置界面
├── app/src/test/java/...     # 10 个测试类，共 118 个用例（0.3.1 新增 5 个类）
├── app/libs/xposed-api-82.jar
└── PLAN.md                   # 调研结论 / 设计 / 风险 / TODO
```
