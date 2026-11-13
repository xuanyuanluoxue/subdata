# 测试报告（root 层 + 界面层，模拟器）

- 日期：2026-11-13
- 设备：**模拟器 `xiaoi-p0`（emulator-5554）**，android-37.0 / aosp_atd / x86_64，KVM 加速，`adb root` 可用（`userdebug`）
- 未触碰真机：全程 `adb` 都显式 `-s emulator-5554`（真机 `100.124.42.34:5555` 在 `adb devices` 里可见，一次都没操作）
- 被测 APK：`app/build/outputs/apk/debug/app-debug.apk`（v0.2.0-beta）

## 0. 结论速览

| 层 | 能否在模拟器验证 | 结果 |
|---|---|---|
| 纯逻辑（PathMap / SlotConfig） | ✅ | 22 个单测全绿；设备上 app_process 直接跑 CLI 通过 |
| root 层（mount --bind 双数据目录） | ✅ | **全部 PASS**：隔离成立、App 能启动、inode/属主/SELinux 标签无损、无残留挂载 |
| 界面层（Activity 渲染） | ✅（用 uiautomator 取视图树） | 6 个区块全部正常渲染，无崩溃；**截图受模拟器 GPU 限制**，见 §5 |
| Xposed 注入层 | ❌ 未验证 | 模拟器内没有 LSPosed/Zygisk，见 §6 |

## 1. 构建与单元测试

```
BUILD SUCCESSFUL in 54s
39 actionable tasks: 23 executed, 16 up-to-date
TESTS=22 FAILURES=0 ERRORS=0 SKIPPED=0     # 原有 13 个 + 新增 9 个
```

新增 `SlotConfigTest`（9 个）覆盖：conf 解析（注释/空行/大小写/非法行）、`formatConf` 稳定顺序、
**format→parse 无损往返**、`truthy` 各种写法、`sanitize` 拒绝 `..`/`a/b`/`subdata`/超长/中文、
以及对真实临时目录跑 `scan()` 验证「根目录+子项都被重定向、控制目录不算漏、结果幂等」。

## 2. 纯逻辑层：PathMap CLI（app_process 直跑）

把 `PathMap` + `SlotConfig`（都不依赖 Android）用 `javac` + `d8` 打成 dexjar，推上设备，
以 **root** 扫一个真实 App 的目录树：

```bash
tools/build-pathmap-cli.sh com.android.deskclock
```

设备内输出：

```
slot=a bases=2 root=/data/data/com.android.deskclock
  base[0] /data/data/com.android.deskclock  ->  /data/data/com.android.deskclock/subdata/a
  base[1] /data/user/0/com.android.deskclock  ->  /data/user/0/com.android.deskclock/subdata/a
scan: dirs=7 files=1 redirected=8 passthrough=0 missed=0 escaped=0 notIdempotent=0
RESULT OK
```

**含义**：这棵真实目录树里 8 个路径全部被正确重定向，没有「漏掉的路」，
没有映射跑到 slot 根之外（`escaped=0`），`map(map(p)) == map(p)` 幂等性成立。
退出码 0/1 可直接接 CI：一旦映射规则改动导致漏映射或越界，`RESULT FAIL`。

> 说明：为了让扫描有意义，先 `am start` 让 DeskClock 自己生成 `shared_prefs` / `cache` 等
> （App 自己的写入，不涉及任何外部改动）。

## 3. root 层：`tools/root-test.sh`（双数据目录 mount 切换）

在设备内以 root 执行（`adb push` 后 `sh /data/local/tmp/root-test.sh com.android.deskclock`）：

```
[10:28:29] === SubData root-layer test ===
[10:28:29] target=com.android.deskclock data=/data/data/com.android.deskclock
[10:28:29] selinux=Enforcing
[10:28:29] baseline inode/uid/gid/mode: 49534 10065 10065 700
[10:28:29] baseline context: u0_a65 u:object_r:app_data_file:s0:c65,c256,c512,c768
  PASS  chcon 对齐 SELinux 标签: u:object_r:app_data_file:s0:c65,c256,c512,c768
--- 场景 1: 挂载 slot a，启动 com.android.deskclock ---
  PASS  mount --bind a -> /data/data/com.android.deskclock
  PASS  数据目录指向 slot a (marker=a)
  PASS  App 启动成功 pid=2101
  PASS  写入落到 slot a
--- 场景 2: 切到 slot b，验证两套数据互不干扰 ---
  PASS  mount --bind b -> /data/data/com.android.deskclock
  PASS  数据目录指向 slot b (marker=b)
  PASS  slot b 看不到 slot a 的独有文件（隔离成立）
  PASS  slot a 期间写入的文件没有串到 b
  PASS  切到 b 后 App 仍能正常启动 pid=2157
--- 场景 3: umount 还原，检查 inode / 属主 / SELinux 标签 ---
  PASS  已 umount，无残留挂载
  PASS  inode/uid/gid/mode 未变 (49534 10065 10065 700)
  PASS  SELinux 标签未变
  PASS  inode 与基线一致，可安全回滚
  PASS  原始数据目录完好
--- 场景 4: PathMap 扫描真实目录树（漏映射检查） ---
  ...
RESULT: ALL PASS
```

事后检查：`mount | grep subdata` = 0 条残留，`/data/local/tmp/subdata-test` 已删除。

### 关键发现（对真机有意义）
1. **SELinux 是 Enforcing**，`mount --bind` 一个 `/data/local/tmp/...` 的目录到 App 数据目录后，
   仅路径正确不够——**必须把源目录的 SELinux 标签 `chcon` 成 App 的 `app_data_file` 及其 MLS
   categories**，否则 App 读写会被拒。脚本里 `chcon` 失败会直接计为 FAIL。
2. 反复 bind/umount 后 **inode、uid/gid、mode、SELinux 标签四项全部与基线一致**，
   说明这条路径不会破坏 App 原始数据，回滚是安全的（这正是学习通真机上最该担心的事）。
3. 切换期间 `am force-stop` 是必须的：进程没停就换挂载点，App 会持有旧目录的 fd。

## 4. 界面层验证

`uiautomator dump` 拿到的视图树（`docs/screenshots/ui-hierarchy.xml`）确认 6 个区块都正常渲染，
文案全部来自 `strings.xml`：

| 区块 | 验证到的内容 |
|---|---|
| 顶部 | `SubData 多开` / `数据目录隔离 · 同一 APK 多份数据` |
| 状态卡 | 「检查中…」→ 无 root/未选择目标时的降级文案；`Beta` 角标 |
| 行为开关 | 「行为开关」+ 5 行（`enabled` / `clone_on_first_run` / `redirect_external` / `hook_file_paths` / `hook_data_dir`） |
| 槽位 | 「Slot 管理」+「切换 slot 后要强制停止目标 App…」 |
| 日志 | 「运行日志」+ 「暂无日志…」 |
| 危险操作 | 「危险操作」+ 二次确认文案 |
| 关于 | 原理 / 风险提示 / 回滚 / `Beta 版本 · root 环境请谨慎` |

`logcat -b crash` 里**没有** `com.xavier.subdata` 的条目（唯一的 tombstone 是模拟器自己的
`screencap`，见下）。

## 5. 截图：受模拟器 GPU 后端限制（未拿到）

- `adb exec-out screencap -p` → `Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`，
  栈在 `/vendor/lib64/hw/mapper.ranchu.so (GoldfishMapper::readFromHost)`。
  这是 **gfxstream + `-gpu swiftshader_indirect` 的宿主回读缺陷**，不是 App 的问题：
  同一时刻 `uiautomator dump`、`dumpsys activity` 都正常。
- 处置：改用 `-gpu off` 重启模拟器重试 —— **该配置下 120 秒内没有 boot 完成**
  （`sys.boot_completed` 为空、截图 0 字节），预算内没有再折腾，已把模拟器恢复回
  `-gpu swiftshader_indirect`（可正常 `adb root` / 装 APK / uiautomator）。
- 因此本轮**没有界面截图**，界面正确性由视图树（`ui-hierarchy.xml`）兜底验证：
  6 个区块的文案、可见性、控件类型都核对过，且 App 自身零崩溃。

## 6. 未验证项：Xposed 注入层

**未验证，原因是环境**：`aosp_atd` 是纯 AOSP 测试镜像，没有 LSPosed/Zygisk Next/Magisk；
在 15 分钟预算内装齐「管理器 + 框架 + 重启 + 作用域勾选」不现实，且这类框架对镜像版本很敏感。
明确记为**未验证**，不硬折腾。**因此「hook 真的生效」这件事目前仍然只有代码和单测支撑。**

## 7. 复现命令

```bash
export ANDROID_HOME=$HOME/Android/Sdk
A=$ANDROID_HOME/platform-tools/adb

# 0) 只给 emulator 用 ACL（不动 /etc/group）——否则 emulator 报 "no permissions to use KVM"
sudo setfacl -m u:$USER:rw /dev/kvm
$ANDROID_HOME/emulator/emulator -avd xiaoi-p0 -no-window -no-audio \
    -gpu swiftshader_indirect -no-snapshot &   # 截图需求时换 -gpu off

$A -s emulator-5554 root                      # userdebug 镜像
$A -s emulator-5554 wait-for-device

# 1) 构建 + 单测
./gradlew assembleDebug testDebugUnitTest

# 2) 纯逻辑 CLI
tools/build-pathmap-cli.sh com.android.deskclock

# 3) root 层
$A -s emulator-5554 push tools/root-test.sh /data/local/tmp/
$A -s emulator-5554 shell sh /data/local/tmp/root-test.sh com.android.deskclock
```

## 8. 上真机前必须确认的清单

1. **先备份**：`/data/data/com.chaoxing.mobile` 与 `subdata/`（真机上学习通有登录态，丢了很贵）。
2. **确认 root 方案**：Magisk / KernelSU 版本，`su -c id` 能看到 `uid=0`；LSPosed 已激活且模块在作用域内。
3. **确认 SELinux 状态**：`getenforce`。本模块**不改 SELinux、不做 mount**（只 hook 路径），
   但界面写 `subdata.conf`/`slot` 后必须 `restorecon`，已在 `RootShell.handOverToApp` 里做；真机上要复核一次。
4. **先只开 `enabled`，其它开关保持默认**；`hook_data_dir` 风险最高，出异常先关它。
5. **每次切换 slot 后 `am force-stop com.chaoxing.mobile`**，否则进程持有旧路径的 fd。
6. **确认回滚路径**：LSPosed 取消勾选 → 学习通立即回到原始数据；再「删除 subdata 目录」彻底清理。
7. **验证清单**：登录态是否还在 / 能否正常打开课程与下载 / 切换后两套数据互不串 / 崩溃日志有无
   `SubData` 相关。
8. 首次上机建议**只测一个账号 + 一个 slot**，稳定后再上第二份数据。
