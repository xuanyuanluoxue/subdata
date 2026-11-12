# SubData 项目计划书（调研 / 设计 / 风险 / TODO）

> 目标：让被注入的 App 拥有「多份互不干扰的数据目录」，同一 APK、同一 uid，靠切换数据目录实现多账号。
> 目标 App：学习通 `com.chaoxing.mobile` 7.0.4（targetSdk 31，libDexHelper 加固壳，双账号需求）。
> 目标机：Android 17 / SDK 37，MIUI HyperOS 4，KernelSU。
> 本文写作时间：2026-11-13。

---

## 一、调研结论

### 1.1 现成方案清单

| 项目 | 仓库 | 最后活跃 | 支持 Android | 还在维护 | 能不能直接用 |
| --- | --- | --- | --- | --- | --- |
| **Storage Redirect**（expirt） | `Xposed-Modules-Repo/io.github.expirt.storageredirect` | 2026-11（v2.15） | API 26+（java.nio）；<26 回退 libcore.io | ✅ 活跃 | ⚠️ 最接近。能把 App 内部数据 / shared_prefs / 外部数据搬到自定义目录，还支持改写 `File(String)` 里写死的路径。但它是「整体搬到一个固定目录」，**没有多 slot 并存 + 克隆 + 切换**，且主打 LSPatch 内嵌封装。思路可借鉴，需求对不上。 |
| storage-redirect-module | `AcE77505/storage-redirect-module` | 2026-10 | compileSdk 36 / targetSdk 36，minSdk 21 | ✅ 活跃 | ⚠️ 传统 Xposed 格式（`assets/xposed_init` + `xposedminversion=82`），把外部存储映射到 App 私有目录以逃离 FUSE。覆盖面小，非多开。**它的工程格式直接验证了我们的选型**。 |
| StorageRedirectionXposed | `fakerqu/StorageRedirectionXposed` | 2026-07 | Android 11+ | 半活跃 | ❌ 只 hook `MediaProvider` 做 `/sdcard` overlay，不碰 `/data/data`，解决不了账号隔离。 |
| Riru-Storage-Redirect（存储空间隔离） | `Magisk-Modules-Repo/riru_storage_redirect`（RikkaW 原作已归档） | 2019–2021 | Android 9–11 | ❌ 停更 | ❌ 依赖 Riru + 系统级挂载重定向，Riru 本身已死，Android 17 上不可行。 |
| VirtualApp | `asLody/VirtualApp` | 主线 2020 停更（仓库仍有零星 push） | 官方 ≤ Android 7，社区分支 ≤ 10，商业版到 14 | ❌ 半死 | ❌ 全量虚拟化（自己跑一份 AMS/PMS），工程量大、对加固壳兼容差，Android 17 上要重写。 |
| VirtualApp（社区分支） | `ServenScorpion/VirtualApp` | 2026-10 | 声称到 Android 12 | ✅ | ❌ 同上，且是「另一个 Launcher 里跑」，不是「原 App 直接多开」。 |
| VirtualXposed | `android-hacker/VirtualXposed` | 2019 停更 | ≤ Android 10 | ❌ | ❌ 同上且更老。 |
| BlackBox | `nnjun/BlackBox` | 2022 停更 | 5.0–12.0 | ❌ | ❌ 虚拟引擎，版本不够。 |
| DroidPlugin | `DroidPluginTeam/DroidPlugin`（360） | 2016 停更 | 4.x–6.x | ❌ | ❌ 完全不可用。 |
| VContainer / FakeSpace / AppDataRedirect | — | — | — | — | ❌ GitHub 上搜不到对应的成熟开源项目（可能是已删库的小工具或记混的名字）。 |

**结论：没有一个能「开箱即用」满足需求。**

- 虚拟化类（VirtualApp/VirtualXposed/BlackBox/DroidPlugin）解决的是「在另一个容器里跑 App」，
  体量巨大、对加固壳兼容差，且 Android 17 上没有可用分支。
- 存储重定向类（Storage Redirect / storage-redirect-module）解决的是「把数据换个地方放」，
  是**单份**数据；我们要的是**多份并存 + 按需切换 + 首份克隆**，还差一层。
- 系统级方案（Riru / 挂载 bind mount）需要动系统分区或常驻 root 守护进程，违背「不改系统」的约束。

### 1.2 Android 17 上 Xposed 侧改数据目录还有没有戏

**有戏，而且是目前唯一轻量的路。**

- `ContextImpl.getDataDir()` / `getFilesDir()` / `getCacheDir()` / `getDatabasePath()` 这些方法
  **至今仍是普通 Java 方法**（没有下沉到 native），反射 + hook 完全可行。这是整个方案的基石。
- 加载器现状（2026-11）：
  - **LSPosed 官方**最后 release 仍是 `v1.9.2`（2023-10），实质停更，不要指望它适配新系统。
  - **Vector**（`JingMatrix/Vector`，LSPosed 的活跃继任者）已到 **v2.2**（2026-08），
    实现 libxposed API 102（支持模块热重载），是 Android 16/17 上的主流选择。
    Pixel 系与 HyperOS/ColorOS 17 的现役模块（如 `MrxSiN/PixelLockScreenEvolved`，minSdk 37）都在用它。
  - KernelSU 环境下推荐 **Zygisk Next（`Dr-TSNG/ZygiskNext`）+ Vector/LSPosed 的 zygisk 版**；
    KSU 内置的 LSPosed 早已过时。`Zygisk_assistant` 用于补 Magisk/KSU 的 denylist 语义。
- **模块 API 选型**：新模块可以用 `io.github.libxposed:api:102.0.0` + `META-INF/xposed/{module.prop,java_init.list,scope.list}`，
  也可以继续用老的 `de.robv.android.xposed:api:82` + `assets/xposed_init` + manifest 里 `xposedmodule/xposedminversion`。
  **本项目选老格式**，理由：
  1. Vector 明确保留 legacy API（"a module targeting 102 leaves the legacy API alone"）；
  2. 2026-10 仍在更新的模块（AcE77505）用的就是老格式，实测可用；
  3. 老格式在 LSPosed 1.9.x 上也跑得动，向下兼容面最大；
  4. 少一个 `META-INF/xposed` 资源格式踩坑点，最小实现优先。
  后续想用热重载 / 新 hook API 再迁到 102，迁移成本集中在一个入口类。
- **加固壳（libDexHelper）会不会检测 Xposed**：会，而且这是本方案最大的不确定性。
  常见手段：栈帧/类加载器里找 `de.robv.android.xposed`、扫 `/proc/self/maps` 找 lspd/riru/zygisk、
  查 `/data/adb/lspd` 痕迹、反调试、对关键 native 方法做 inline-hook 检测。
  缓解手段在 LSPosed/Vector 侧（对该 App 开「隐藏」、配合 Shamiko / Zygisk denylist），
  模块侧能做的是**尽量少 hook、不 hook native、不在目标进程留可疑类名**。

### 1.3 最终选择：自研最小实现

理由：
1. 现成方案要么太重（虚拟化、Android 17 不可用），要么差一层语义（单份 vs 多 slot）；
2. 需求非常收敛——只要覆盖 `ContextImpl` 那一组 getter + `File` 兜底就够了，代码量可控（< 1000 行）；
3. 自己写才能做到：**不改 APK、不动系统分区、不 hook system_server**，随时可回滚；
4. 出问题能自己看日志、自己加 hook，比魔改别人的大工程快得多。

---

## 二、架构设计

```
LSPosed/Vector
   └─ 注入 com.chaoxing.mobile 的每个进程
        └─ SubDataModule.handleLoadPackage()
             ├─ 白名单检查（模块 SharedPreferences，默认 com.chaoxing.mobile）
             ├─ SlotConfig.load(dataDir)      # 读 subdata/slot、subdata/subdata.conf
             ├─ DataCloner.ensureSlot()       # 首次：从默认目录克隆到 subdata/<slot>
             ├─ new PathMap(slot, bases)      # 纯函数：<base> → <base>/subdata/<slot>
             └─ 安装 hook
                  ├─ ContextImpl 一组 File getter
                  ├─ ContextImpl 带参 getter（getDir/getDatabasePath/...）
                  ├─ File 三个构造器（可关）
                  ├─ SQLiteDatabase.openDatabase
                  └─ WebViewFactory.getDataDirectory
```

**关键设计点**

1. **映射幂等**：`<base>/subdata` 及其子树永远原样返回。
   这样 hook 之间不会套娃（`getDataDir` 返回 slot 目录后，`getFilesDir` 再映射一次也不会变成 `subdata/a/subdata/a/files`）。
2. **多层兜底**：`getDataDir` 是根，理论上改它就能带动大部分路径；
   但各 getter 有各自的字段缓存，所以逐个也 hook 一遍，任一失效仍有另一层兜住。
3. **控制目录放 App 自己的私有目录**（`/data/data/<pkg>/subdata`）：
   App 自己的 uid 一定能读写，不依赖模块 SharedPreferences 能否被跨进程读到，
   也不需要 root 守护进程。代价是「第一次得让 App 自己建出来」（模块注入时创建）。
4. **clone 是 best-effort**：单文件失败不影响其它；完成后写 `.cloned` 标记，避免每次冷启动重复扫描。
5. **不碰 lib / cache / code_cache**：`lib` 是 linker 用的，`cache`/`code_cache` 放 slot 里既无意义又容易出问题。
6. **失败隔离**：每个 hook 单独 try/catch，找不到方法只记一行日志。

---

## 三、已实现 vs 未实现

**已实现（并编译通过）**

- 工程脚手架：Gradle 9.9 + AGP 9.5.1 + Java 17，`./gradlew assembleDebug` 出 APK。
- `PathMap`：纯路径映射 + 幂等 + 边界保护，13 个单元测试全绿。
- `SlotConfig`：slot 文件 / subdata.conf 读取，slot 名校验（拒绝 `..`、`/`、中文、`subdata`）。
- `DataCloner`：首次克隆 + `.cloned` 标记 + 跳过 lib/cache/code_cache。
- `SubDataModule`：白名单、注入、ContextImpl 全套 getter、File 构造兜底、SQLite、WebView、日志落盘。
- `MainActivity`：白名单编辑 + 切 slot 命令一键复制 + 完整说明 + 回滚说明。

**未实现 / 后续 TODO**

- [ ] 真机验证（本机只有编译环境，没装机和跑学习通）。
- [ ] slot 切换的「不用 shell」入口：现在要 `su -c 'echo b > .../slot'`。
      可做成模块 App 里点按钮 → 用 root/Shizuku 写文件 + `am force-stop`（需申请 root 权限通道）。
- [ ] 多 slot 并存启动：现在一个包同时只能跑一个 slot（同一 uid 一份进程）。
      想同时在线两个账号，得配合「工作资料/B 用户」或虚拟化方案，超出本模块范围。
- [ ] 用 libxposed API 102 重写入口，换取热重载（改 hook 不用重启目标进程）。
- [ ] `getObbDir` / 外部存储的 slot 目录权限：`/sdcard/Android/data/<pkg>/subdata/a` 首次创建时需确认可写。
- [ ] 覆盖率自检：在目标 App 里 dump 一次实际被访问的路径，找出「漏网」的写死路径再补 hook。

---

## 四、风险清单

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| **加固壳检测 Xposed**（libDexHelper） | App 直接拒绝启动 / 闪退 / 弹窗 | 先只开本模块试；不行就在 LSPosed/Vector 里对该 App 开隐藏 + Shamiko/Zygisk denylist；再不行考虑「只在启动早期注入，之后尽量少干预」 |
| **Android 17 + Vector 的兼容性** | hook 不生效 / 目标进程崩溃 | 先用一个自己写的测试 App 验证注入与映射，再上学习通；日志看 `subdata.log` |
| **App 写死绝对路径 / native 直读** | 部分数据仍然串号 | 已开 `hook_file_paths` 兜 Java 层字符串；native 层无解，只能实测确认影响面 |
| **首次克隆期间 App 已经在写数据** | 克隆数据不一致 | 注入点在 Application 之前；若仍有问题把 `clone_on_first_run` 设 false，用干净 slot |
| **`File` 构造 hook 的性能** | 冷启动变慢 | 可 `hook_file_paths=false` 关掉，只留 ContextImpl 层 |
| **`getDataDir` hook 过于底层** | 某些框架代码按 dataDir 推断行为，可能闪退 | 已提供 `hook_data_dir=false` 单独关闭（其他 getter 仍然生效，因为它们各自被 hook 了） |
| **多进程（:push/:remote 等）** | 某进程没被注入 | 白名单按包名匹配，LSPosed 默认注入该包所有进程；日志里能看到每个进程的 installed 行 |
| **学习通自身有设备指纹/风控** | 两个账号被判定同设备 | 本模块只隔离数据目录，不改设备指纹。真要更彻底需要 XPrivacyLua 类模块，另说 |

---

## 五、真机验证步骤（交给手机端执行）

```bash
# 1. 编译产物
#    D:\code\android\subdata\app\build\outputs\apk\debug\app-debug.apk

# 2. 安装 + 在 LSPosed/Vector 里启用、勾选 com.chaoxing.mobile、强制停止该 App

# 3. 首次启动后检查
su -c 'ls -la /data/data/com.chaoxing.mobile/subdata'
su -c 'cat /data/data/com.chaoxing.mobile/subdata/slot'        # 期望 a
su -c 'cat /data/data/com.chaoxing.mobile/subdata/subdata.log' # 期望有 "installed slot=a hooks=.."

# 4. 确认数据真的落进 slot
su -c 'ls -la /data/data/com.chaoxing.mobile/subdata/a/files | head'
su -c 'ls -la /data/data/com.chaoxing.mobile/files | head'     # 原始目录应停止增长

# 5. 切 slot b（干净第二账号）
su -c 'echo clone_on_first_run=false >> /data/data/com.chaoxing.mobile/subdata/subdata.conf'
su -c 'echo b > /data/data/com.chaoxing.mobile/subdata/slot; am force-stop com.chaoxing.mobile'
# 重新打开 → 应该是一个未登录的新实例

# 6. 切回 a
su -c 'echo a > /data/data/com.chaoxing.mobile/subdata/slot; am force-stop com.chaoxing.mobile'
```

**验证通过的标准**：切到 b 时是未登录状态、切回 a 时登录态和课表数据还在、
两个 slot 的 `files/` `databases/` `shared_prefs/` 内容互相独立。

---

## 六、环境备忘（本机实测）

- WSL：JDK 21、Android SDK 在 `~/Android/Sdk`（platform `android-37.0` + build-tools `37.0.0`），
  Gradle 9.9.0 已缓存在 `~/.gradle/wrapper/dists`。
- 网络：`git config --global` 里有一条坏的 `insteadOf`（`gitclone.com`），**github clone 会失败**；
  clone 时用 `git -c url.https://github.com/.insteadOf=https://github.com/ clone ...` 覆盖。
  Maven 走 `maven.aliyun.com` 镜像即可，google() 不稳定。
  WSL 里 `127.0.0.1:7897` 有可用 HTTP 代理（gradle.properties 已配）。
- 构建命令见 README 第 3 节。
