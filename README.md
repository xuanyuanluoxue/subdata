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
./gradlew testDebugUnitTest      # 13 个单元测试（路径映射 / slot 校验 / 配置读写）
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

白名单在模块 App 首页编辑（存模块自己的 SharedPreferences），每行一个包名。

## 7. 回滚

```bash
# 1) 只想恢复原状：LSPosed 里取消勾选作用域 → App 立刻回到原始数据（原始数据从未被改动）
# 2) 彻底清理模块痕迹：
su -c 'rm -rf /data/data/com.chaoxing.mobile/subdata'
```

## 8. 已知限制

- **native 层写死的路径拦不住**：`.so` 里直接 `open("/data/data/<pkg>/...")` 不会被 Java hook 影响。
  学习通的核心业务主要在 DEX（libDexHelper 壳保护的是 DEX），一般能覆盖，但不能保证 100%。
- **WebView** 的数据目录是 best effort；`getCodeCacheDir` 刻意保留在真实私有目录（放 slot 里意义不大且易踩坑）。
- 首次克隆发生在 App 极早期（Application.onCreate 之前），数据多时首启会慢几百毫秒到几秒。
- 只支持「同一 uid 内多份数据」。真正的双实例（两个进程同时跑、两个桌面图标）不在本模块范围。

## 9. 目录结构

```
subdata/
├── app/src/main/java/com/xavier/subdata/
│   ├── SubDataModule.java    # Xposed 入口：注入、hook 安装、日志
│   ├── PathMap.java          # 纯路径映射（无 Android 依赖，可单测）
│   ├── SlotConfig.java       # slot / 配置文件读取
│   ├── DataCloner.java       # 首次 clone
│   └── MainActivity.java     # 模块配置界面
├── app/src/test/java/...     # PathMapTest（13 个断言）
├── app/libs/xposed-api-82.jar
└── PLAN.md                   # 调研结论 / 设计 / 风险 / TODO
```
